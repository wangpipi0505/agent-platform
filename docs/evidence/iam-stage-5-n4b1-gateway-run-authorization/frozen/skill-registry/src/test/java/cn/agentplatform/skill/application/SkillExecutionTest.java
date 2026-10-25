package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillExecutionTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);
    HttpFixture endpoint;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    SkillAdapters adapters;
    Map<String, Object> request;
    String code;
    long version;
    boolean callbackUnavailable;
    boolean claimGranted = true;
    int submittedCalls;
    String authorityTrigger = "TASK";
    Map<String, Object> authorityMock = Map.of();

    @BeforeAll
    static void migrate() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void prepare() throws Exception {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("skill-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        endpoint = new HttpFixture();
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, manager, clients, "http://unused");
        adapters = new SkillAdapters(new ArtifactStore(db), new AlgorithmAdapter(db));
        executor =
                new SkillExecution(db, manager, catalog, adapters, clients, "http://engine.test");
        code = "http_" + UUID.randomUUID().toString().replace("-", "");
        var created = catalog.create(contract());
        version = ((Number) created.get("skillVersionId")).longValue();
        // 这里只为执行器测试建立已发布fixture；生产发布必须经过真实连接和模型验收报告。
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where tenant_id=?"
                        + " and code=?",
                version,
                "skill-tests",
                code);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                ids.incrementAndGet(),
                                "runId",
                                "r_test",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                Map.of("region", "A"),
                                "idempotencyKey",
                                "logical-key",
                                "leaseOwner",
                                "worker-generation",
                                "triggerType",
                                "FORMAL"));
        when(clients.get(anyString()))
                .thenAnswer(
                        invocation ->
                                Json.tree(
                                        Map.of(
                                                "approved",
                                                true,
                                                "skillVersionId",
                                                version,
                                                "parametersDigest",
                                                Digest.sha256(
                                                        Json.canonical(
                                                                request.get("arguments"))))));
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String url = invocation.getArgument(0);
                            if (url.endsWith("/claim")) {
                                var claim =
                                        Json.tree(
                                                Map.of(
                                                        "claimed",
                                                        claimGranted,
                                                        "status",
                                                        claimGranted ? "EXECUTING" : "UNKNOWN",
                                                        "skill_version_id",
                                                        version,
                                                        "skill_code",
                                                        code,
                                                        "args_digest",
                                                        Digest.sha256(
                                                                Json.canonical(
                                                                        request.get("arguments"))),
                                                        "node_instance_id",
                                                        123,
                                                        "runId",
                                                        "r_test",
                                                        "trigger_type",
                                                        authorityTrigger,
                                                        "idempotency_key",
                                                        "logical-key",
                                                        "mock_snapshot",
                                                        authorityMock));
                                ((com.fasterxml.jackson.databind.node.ObjectNode) claim)
                                        .put(
                                                "attempt_id",
                                                ((Number) request.get("attemptId")).longValue());
                                return claim;
                            }
                            if (url.endsWith("/submitted")) {
                                assertEquals(submittedCalls++, endpoint.calls.get());
                                return Json.tree(Map.of("submitted", true));
                            }
                            if (url.endsWith("/fact")) {
                                assertEquals(
                                        1,
                                        db.queryForObject(
                                                "select count(*) from skill.execution_fact where"
                                                        + " tenant_id=? and call_id=?",
                                                Integer.class,
                                                "skill-tests",
                                                request.get("callId")));
                                if (callbackUnavailable)
                                    throw new IllegalStateException("test-only unavailable");
                                return Json.tree(Map.of("accepted", true));
                            }
                            throw new AssertionError("unexpected internal request " + url);
                        });
    }

    @Test
    void durableProofIsCanonicalAndDoesNotRedispatch() {
        executor.execute(code, request);
        long call = ((Number) request.get("callId")).longValue();
        var proof = new LinkedHashMap<>(executor.proof(call));
        String digest = proof.remove("factSha256").toString();
        assertEquals(Digest.sha256(Json.canonical(proof)), digest);
        assertEquals(request.get("attemptId").toString(), proof.get("attemptId").toString());
        assertEquals(1, endpoint.calls.get());
        assertNotNull(proof.get("skillContractSha256"));
    }

    @Test
    void readonlyConnectionUsesControlledRunBeforeAnyExternalCall() {
        doReturn(Json.tree(Map.of("runId", "r_controlled", "status", "RUNNING")))
                .when(clients)
                .post(endsWith("/internal/skill-draft-tests"), any());
        var governance =
                new SkillGovernance(
                        db, catalog, adapters, clients, "http://model.test", "http://engine.test");
        var pending =
                governance.connection(
                        code,
                        1,
                        Map.of("inputs", Map.of("region", "A"), "requestId", "test-connection"));
        assertEquals("r_controlled", pending.get("runId"));
        assertEquals(false, pending.get("ok"));
        assertEquals(0, endpoint.calls.get());
        authorityTrigger = "DEBUG";
        executor.execute(code, request);
        var accepted =
                governance.connection(code, 1, Map.of("controlledCallId", request.get("callId")));
        assertEquals(true, accepted.get("ok"));
        assertEquals(1, endpoint.calls.get());
    }

    Map<String, Object> contract() {
        var b = new LinkedHashMap<String, Object>();
        b.put("code", code);
        b.put("name", "测试HTTP技能");
        b.put("type", "HTTP");
        b.put("description", "能力：查询测试值。适用：已授权区域查询。不适用：修改业务数据。");
        b.put("endpoint", endpoint.url());
        b.put(
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("region"),
                        "properties",
                        Map.of("region", Map.of("type", "string"))));
        b.put(
                "outputSchema",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("value"),
                        "properties",
                        Map.of("value", Map.of("type", "integer"))));
        b.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        true));
        b.put(
                "guard",
                Map.of("roles", List.of("EDITOR"), "attributes", Map.of("region", List.of("A"))));
        return b;
    }

    @AfterEach
    void close() throws Exception {
        SecurityContextHolder.clearContext();
        endpoint.close();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CHAT", "TASK"})
    void actualHttpExecutesOnceAndFactExistsBeforeCallback(String trigger) {
        authorityTrigger = trigger;
        assertEquals("SUCCEEDED", executor.execute(code, request).get("status"));
        assertEquals("SUCCEEDED", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
        assertEquals(1, executor.statistics(code).size());
        assertEquals("FORMAL", executor.statistics(code).getFirst().get("callKind"));
        assertEquals(
                trigger,
                db.queryForObject(
                        "select request_snapshot->>'authorityTriggerType' from skill.execution_fact"
                                + " where tenant_id=? and call_id=?",
                        String.class,
                        "skill-tests",
                        request.get("callId")));
    }

    @Test
    void debugBodyCannotInventMockAndAuthoritySnapshotCannotBeOverridden() {
        authorityTrigger = "DEBUG";
        request.put("mock", Map.of("enabled", true, "response", Map.of("value", 666)));
        var real = Json.tree(executor.execute(code, request).get("result"));
        assertFalse(real.path("isMock").asBoolean());
        assertEquals(7, real.path("structured").path("value").asInt());
        assertEquals(1, endpoint.calls.get());
        request.put("callId", ids.incrementAndGet());
        authorityMock =
                Map.of("skillCode", code, "mode", "FIXED", "fixedResponse", Map.of("value", 99));
        var mock = Json.tree(executor.execute(code, request).get("result"));
        assertTrue(mock.path("isMock").asBoolean());
        assertEquals(99, mock.path("structured").path("value").asInt());
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void lastRealRequiresPersistedOwnUnchangedNonMockSource() {
        var source = Json.tree(executor.execute(code, request).get("result"));
        long sourceCall = ((Number) request.get("callId")).longValue();
        authorityTrigger = "DEBUG";
        request.put("callId", ids.incrementAndGet());
        authorityMock =
                Map.of(
                        "skillCode",
                        code,
                        "mode",
                        "FIXED",
                        "fixedResponse",
                        source,
                        "sourceKind",
                        "LAST_REAL",
                        "sourceCallId",
                        sourceCall,
                        "sourceRunId",
                        "r_test",
                        "sourcePrincipal",
                        "skill-tester",
                        "sourceDigest",
                        Digest.sha256(Json.canonical(source)));
        var mock = Json.tree(executor.execute(code, request).get("result"));
        assertTrue(mock.path("isMock").asBoolean());
        assertEquals(7, mock.path("structured").path("value").asInt());
        assertEquals(1, endpoint.calls.get());
        request.put("callId", ids.incrementAndGet());
        var forged = new LinkedHashMap<>(authorityMock);
        forged.put("sourceDigest", "0".repeat(64));
        authorityMock = forged;
        assertEquals("FAILED", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void lastRealCannotReuseBroaderGuardedArgumentsAfterPermissionNarrows() {
        code = "scope_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "inputSchema",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("region"),
                        "properties",
                        Map.of(
                                "region",
                                Map.of("type", "array", "items", Map.of("type", "string")))));
        body.put(
                "guard",
                Map.of("roles", List.of("EDITOR"), "attributes", Map.of("region", List.of())));
        version = ((Number) catalog.create(body).get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        request.put("arguments", Map.of("region", List.of("A", "B")));
        scopeIdentity(List.of("A", "B"));
        var source = Json.tree(executor.execute(code, request).get("result"));
        long sourceCall = ((Number) request.get("callId")).longValue();
        scopeIdentity(List.of("A"));
        authorityTrigger = "DEBUG";
        request.put("callId", ids.incrementAndGet());
        authorityMock =
                Map.of(
                        "skillCode",
                        code,
                        "mode",
                        "FIXED",
                        "fixedResponse",
                        source,
                        "sourceKind",
                        "LAST_REAL",
                        "sourceCallId",
                        sourceCall,
                        "sourceRunId",
                        "r_test",
                        "sourcePrincipal",
                        "skill-tester",
                        "sourceDigest",
                        Digest.sha256(Json.canonical(source)));
        var rejected = executor.execute(code, request);
        assertEquals("FAILED", rejected.get("status"));
        assertEquals("NO_PERMISSION", rejected.get("errorSemantic"));
        assertEquals(1, endpoint.calls.get());
    }

    private void scopeIdentity(List<String> regions) {
        var jwt =
                Jwt.withTokenValue("verified-scope")
                        .header("alg", "HS256")
                        .subject("skill-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", regions))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void controlledArtifactCopyRequiresAuthoritativeSourceBindingAndCurrentLease() {
        var source = Json.tree(executor.execute(code, request).get("result"));
        long sourceCall = ((Number) request.get("callId")).longValue();
        request.put("callId", ids.incrementAndGet());
        var authority = new LinkedHashMap<String, Object>();
        authority.put("status", "EXECUTING");
        authority.put("runId", "r_test");
        authority.put("node_instance_id", 123);
        authority.put("attempt_id", 456);
        authority.put("claimed_by", "worker-generation");
        authority.put("trigger_type", "DEBUG");
        authority.put("skill_version_id", version);
        authority.put("skill_contract_sha256", source.path("unused"));
        authority.put("args_digest", Digest.sha256(Json.canonical(request.get("arguments"))));
        authority.put("arguments", request.get("arguments"));
        authority.put("mock_source_call_id", sourceCall);
        authority.put("mock_source_digest", Digest.sha256(Json.canonical(source)));
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        doAnswer(i -> Json.tree(authority)).when(clients).post(endsWith("/claim"), any());
        assertEquals(List.of(), executor.reproduceArtifacts(sourceCall, request).get("fileRefs"));
        assertEquals(1, endpoint.calls.get());
        authority.put("mock_source_call_id", sourceCall + 1);
        assertThrows(
                PlatformException.class, () -> executor.reproduceArtifacts(sourceCall, request));
        assertEquals(1, endpoint.calls.get());
        authority.put("mock_source_call_id", sourceCall);
        authority.put("claimed_by", "obsolete-worker");
        assertThrows(
                PlatformException.class, () -> executor.reproduceArtifacts(sourceCall, request));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void callbackRetryNeverRepeatsExternalBusiness() {
        callbackUnavailable = true;
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
        callbackUnavailable = false;
        assertEquals(
                "SUCCEEDED",
                executor.retry(((Number) request.get("callId")).longValue()).get("status"));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void invalidParametersHaveZeroExternalCalls() {
        request.put("arguments", Map.of());
        var result = executor.execute(code, request);
        assertEquals("FAILED", result.get("status"));
        assertEquals("PARAM_ERROR", result.get("errorSemantic"));
        assertEquals(0, endpoint.calls.get());
    }

    @Test
    void deniedScopeHasZeroExternalCalls() {
        request.put("arguments", Map.of("region", "B"));
        var result = executor.execute(code, request);
        assertEquals("NO_PERMISSION", result.get("errorSemantic"));
        assertEquals(0, endpoint.calls.get());
    }

    @Test
    void noClaimNeverExecutes() {
        claimGranted = false;
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals(0, endpoint.calls.get());
    }

    @Test
    void changedArgumentsCannotReuseCallIdentity() {
        executor.execute(code, request);
        request.put("arguments", Map.of("region", "B"));
        assertThrows(PlatformException.class, () -> executor.execute(code, request));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void writeTimeoutStaysUnknownAndNeverResubmits() {
        var mutable =
                Json.read(
                        db.queryForObject(
                                "select contract::text from skill.skill_version where tenant_id=?"
                                        + " and id=?",
                                String.class,
                                "skill-tests",
                                version),
                        Map.class);
        // 新建独立写技能fixture，不修改已发布不可变契约。
        code = "write_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        true,
                        "idempotent",
                        true,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("APPROVER")));
        body.put("timeoutMs", 100);
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        endpoint.delayMs = 400;
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void unknownReconciliationOnlyQueriesAndUpdatesSameStatistic() {
        code = "reconcile_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        true,
                        "idempotent",
                        true,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("APPROVER")));
        body.put("timeoutMs", 100);
        body.put(
                "reconciliation",
                Map.of(
                        "endpoint",
                        endpoint.url(),
                        "donePath",
                        "$.status",
                        "doneValue",
                        "DONE",
                        "resultPath",
                        "$.result"));
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        endpoint.delayMs = 400;
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        endpoint.delayMs = 0;
        endpoint.response =
                Json.write(
                                Map.of(
                                        "idempotencyKey",
                                        "logical-key",
                                        "status",
                                        "DONE",
                                        "result",
                                        Map.of("value", 7)))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(clients.get(anyString())).thenReturn(Json.tree(Map.of("status", "UNKNOWN")));
        assertEquals(
                "SUCCEEDED",
                executor.reconcile(((Number) request.get("callId")).longValue()).get("status"));
        assertEquals(2, endpoint.calls.get());
        assertEquals(1, executor.statistics(code).size());
        assertEquals("SUCCEEDED", executor.statistics(code).getFirst().get("status"));
    }

    @Test
    void unapprovedWriteNeverClaimsOrDispatches() {
        code = "confirm_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        true,
                        "idempotent",
                        true,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("APPROVER")));
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        request.put("confirmed", true);
        when(clients.get(anyString())).thenReturn(Json.tree(Map.of("approved", false)));
        assertEquals("WAITING_APPROVAL", executor.execute(code, request).get("status"));
        assertEquals(0, endpoint.calls.get());
        verify(clients, never()).post(endsWith("/claim"), any());
        verify(clients, never()).post(endsWith("/submitted"), any());
    }

    @Test
    void unpublishedDraftCanBeDeletedWithoutPublishedHistory() {
        String draft = "delete_" + UUID.randomUUID().toString().replace("-", "");
        catalog.create(Map.of("code", draft, "name", "待删除草稿"));
        catalog.delete(draft);
        assertThrows(PlatformException.class, () -> catalog.get(draft, null));
        assertThrows(PlatformException.class, () -> catalog.delete(code));
    }

    @Test
    void configuredReturnSpecSeparatesSummaryStructuredAndCard() {
        code = "return_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "returnSpec",
                Map.of(
                        "structuredPath",
                        "$.value",
                        "modelSummary",
                        Map.of("mode", "TEMPLATE", "tpl", "值{{value}}"),
                        "cardTemplate",
                        "{\"title\":\"值{{value}}\"}"));
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        var result = Json.tree(executor.execute(code, request).get("result"));
        assertEquals(7, result.path("structured").asInt());
        assertEquals("值7", result.path("modelSummary").asText());
        assertEquals(
                "值7", Json.read(result.path("cardPayloadJson").asText()).path("title").asText());
    }

    @Test
    void readonlyRetryRequiresExplicitNewEngineGeneration() {
        code = "retry_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put("timeoutMs", 100);
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        request.put("skillVersionId", version);
        endpoint.delayMs = 400;
        assertEquals("FAILED", executor.execute(code, request).get("status"));
        request.put("attemptId", 457);
        when(clients.get(anyString()))
                .thenReturn(
                        Json.tree(
                                Map.of(
                                        "status",
                                        "REGISTERED",
                                        "attempt_id",
                                        457,
                                        "skill_version_id",
                                        version,
                                        "args_digest",
                                        Digest.sha256(Json.canonical(request.get("arguments"))))));
        endpoint.delayMs = 0;
        assertEquals("SUCCEEDED", executor.execute(code, request).get("status"));
        assertEquals(2, endpoint.calls.get());
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.execution_fact where tenant_id=? and call_id=?",
                        Integer.class,
                        "skill-tests",
                        request.get("callId")));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.execution_fact_history where tenant_id=? and"
                                + " call_id=?",
                        Integer.class,
                        "skill-tests",
                        request.get("callId")));
    }

    @Test
    void formalAuthorityCannotBeSpoofedIntoMockByRequestBody() {
        request.put("triggerType", "DEBUG");
        request.put("mock", Map.of("enabled", true, "response", Map.of("value", 99)));
        var result = Json.tree(executor.execute(code, request).get("result"));
        assertFalse(result.path("isMock").asBoolean());
        assertEquals(7, result.path("structured").path("value").asInt());
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")
    void largeHttpResultPersistsOnlyOpaqueReferencesInExecutionFact() throws Exception {
        endpoint.response =
                Json.write(Map.of("value", 7, "padding", "x".repeat(5 * 1024 * 1024)))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = Json.tree(executor.execute(code, request).get("result"));
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertTrue(result.path("structured").isNull());
        assertTrue(result.path("modelSummary").asText().length() < 500);
        String id = result.path("fileRefs").get(0).path("artifactId").asText();
        assertTrue(id.startsWith("art_"));
        String fact =
                db.queryForObject(
                        "select result::text from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        "skill-tests",
                        request.get("callId"));
        assertFalse(fact.contains("s3://"));
        assertFalse(fact.contains("object_key"));
        assertEquals(1, endpoint.calls.get());
        var store = new ArtifactStore(db);
        try (var download = store.download(id, "r_test")) {
            assertEquals(endpoint.response.length, download.bytes());
            assertEquals(7, ArtifactStore.readJson(download.source()).path("value").asInt());
        } finally {
            db.update(
                    "update skill.artifact set expires_at=now()-interval '1 second' where"
                            + " tenant_id=? and artifact_id=?",
                    "skill-tests",
                    id);
            store.cleanTenant("skill-tests", 8);
            store.close();
        }
    }

    @Test
    void automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness() {
        callbackUnavailable = true;
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
        callbackUnavailable = false;
        when(clients.post(anyString(), any(), any(Actor.class)))
                .thenAnswer(
                        invocation -> {
                            Actor scope = invocation.getArgument(2);
                            assertEquals("skill-tests", scope.tenantId());
                            assertEquals("skill-tester", scope.principalId());
                            assertEquals(Set.of("SERVICE"), scope.roles());
                            return clients.post(
                                    invocation.getArgument(0), invocation.getArgument(1));
                        });
        db.update(
                "update skill.execution_fact set next_delivery_at=now()-interval '1 day' where"
                        + " tenant_id=? and call_id=?",
                "skill-tests",
                request.get("callId"));
        assertEquals(1, new SkillOutbox(db, executor).drainTenant("skill-tests", 1));
        assertEquals(
                "DELIVERED",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        "skill-tests",
                        request.get("callId")));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void controlledDraftWriteRequiresEngineProofAndRejectsEditedContract() {
        code = "controlled_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        true,
                        "idempotent",
                        true,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("APPROVER")));
        var created = catalog.create(body);
        version = ((Number) created.get("skillVersionId")).longValue();
        String sha = created.get("sha256").toString();
        request.put("skillVersionId", version);
        authorityTrigger = "DEBUG";
        when(clients.get(anyString()))
                .thenAnswer(
                        invocation ->
                                invocation.getArgument(0).toString().endsWith("/approval")
                                        ? Json.tree(
                                                Map.of(
                                                        "approved",
                                                        true,
                                                        "skillVersionId",
                                                        version,
                                                        "parametersDigest",
                                                        Digest.sha256(
                                                                Json.canonical(
                                                                        request.get("arguments")))))
                                        : Json.tree(
                                                Map.of(
                                                        "controlled_draft_skill_version_id",
                                                        version,
                                                        "controlled_draft_skill_digest",
                                                        sha,
                                                        "trigger_type",
                                                        "DEBUG")));
        assertEquals("SUCCEEDED", executor.execute(code, request).get("status"));
        assertEquals(1, endpoint.calls.get());
        body.put("description", "草稿挂起后已修改，必须重建测试并确认。");
        catalog.save(code, body);
        assertThrows(PlatformException.class, () -> executor.execute(code, request));
        assertEquals(1, endpoint.calls.get());
    }

    @Test
    void arbitraryDraftFlagCannotBypassEngineAuthority() {
        code = "untrusted_" + UUID.randomUUID().toString().replace("-", "");
        var created = catalog.create(contract());
        version = ((Number) created.get("skillVersionId")).longValue();
        request.put("skillVersionId", version);
        request.put("controlledDraftSkillVersionId", version);
        assertThrows(PlatformException.class, () -> executor.execute(code, request));
        assertEquals(0, endpoint.calls.get());
    }

    @Test
    void oldAcceptanceCannotApproveChangedDraft() {
        String draftCode = "draft_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put("code", draftCode);
        var created = catalog.create(body);
        long id = ((Number) created.get("skillVersionId")).longValue();
        String sha = created.get("sha256").toString();
        db.update(
                "insert into"
                    + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report)"
                    + " values('skill-tests',?,?,true,'{}')",
                id,
                sha);
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report)"
                    + " values(?,'skill-tests',?,?,true,1,'{}')",
                "test-" + UUID.randomUUID(),
                id,
                sha);
        body.put("description", "修改后的契约：能力说明与使用边界发生变化，旧验收必须失效。");
        catalog.save(draftCode, body);
        assertThrows(PlatformException.class, () -> catalog.publish(draftCode, 1));
    }
}
