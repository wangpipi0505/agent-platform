package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 导入边界用mock定位零创建；闭环用真实PG、真实HTTP与确定性的引擎/模型协议替身。 */
class OntologyManifestImportsTest {
    private static final String TENANT = "ontology-import-tests";
    @TempDir Path secretDir;
    private String previousDir;
    private String previousKey;
    private String authRef;
    SkillCatalog catalog = mock(SkillCatalog.class);
    OntologyManifestImports imports = new OntologyManifestImports(catalog);

    @BeforeEach
    void actor() {
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "ontology-import-test-master-key-0123456789");
        signIn(TENANT, List.of("ADMIN", "EDITOR", "SERVICE"));
        authRef = SecretVault.put("ontopa_import_test_only");
    }

    private void signIn(String tenant, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .claim("attributes", Map.of("region", List.of("A")))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        if (previousDir == null) System.clearProperty("agent.secret.dir");
        else System.setProperty("agent.secret.dir", previousDir);
        if (previousKey == null) System.clearProperty("agent.master.key");
        else System.setProperty("agent.master.key", previousKey);
    }

    /** 按ContractGenerator.formalize产出形状构造正式manifest。 */
    static Map<String, Object> formalManifest(String toolCode) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("spaceCode", "route3");
        meta.put("releaseId", "9f8e7d6c-1111-2222-3333-444455556666");
        meta.put("version", "v3");
        meta.put("contentDigest", "a".repeat(64));
        var exec = new LinkedHashMap<String, Object>();
        exec.put("readonly_flag", true);
        exec.put("side_effect", false);
        exec.put("idempotent", true);
        exec.put("cancellable", false);
        exec.put("timeout_ms", 30000);
        exec.put("max_rows", 1000);
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("tool_code", toolCode);
        manifest.put("name", "对象查询");
        manifest.put("description", "查询冻结模型");
        manifest.put("invocation_kind", "SYNC");
        manifest.put("adapter_type", "HTTP");
        manifest.put("category", "ontology_service");
        manifest.put(
                "endpoint",
                "https://onto.example.com/onto/r/route3/releases/9f8e7d6c-1111-2222-3333-444455556666/ops/"
                        + toolCode);
        manifest.put("http_method", "POST");
        manifest.put(
                "input_schema",
                Map.of("type", "object", "properties", Map.of("region", Map.of("type", "string"))));
        manifest.put(
                "output_schema",
                Map.of(
                        "type", "object",
                        "required", List.of("code", "data", "meta"),
                        "properties",
                                Map.of(
                                        "code", Map.of("const", "OK"),
                                        "data", Map.of("type", "object"),
                                        "meta", Map.of("type", "object"))));
        manifest.put("guard_attrs", List.of("region"));
        manifest.put("exec_attrs", exec);
        manifest.put("risk_level", "READ_ONLY");
        manifest.put("error_semantics", List.of(Map.of("match", "HTTP 403", "semantic", "NO_PERMISSION")));
        manifest.put(
                "return_spec",
                Map.of(
                        "modelSummary", Map.of("mode", "TEMPLATE", "template", "查询结果"),
                        "structuredPath", "$.data",
                        "fileRefPaths", List.of(),
                        "cardTemplate", "查询结果",
                        "ontologyMetaPath", "$.meta"));
        manifest.put("ontology_meta", meta);
        return manifest;
    }

    private PlatformException invalid(Map<String, Object> body) {
        return assertThrows(PlatformException.class, () -> imports.importManifests(body));
    }

    private Map<String, Object> request(Map<String, Object>... manifests) {
        return new LinkedHashMap<>(Map.of("manifests", List.of(manifests), "authRef", authRef));
    }

    @Test
    void 正式manifest完整映射为技能契约() {
        var drafts = new ArrayList<Map<String, Object>>();
        drafts.add(Map.of("code", "user_query", "status", "DRAFT"));
        when(catalog.create(any())).thenReturn(drafts.getFirst());
        var result =
                imports.importManifests(
                        request(formalManifest("user_query")));
        assertEquals(1, result.get("drafts") instanceof List<?> d ? d.size() : 0);
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(catalog).create(captor.capture());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) captor.getValue();
        assertEquals("user_query", body.get("code"));
        assertEquals("HTTP", body.get("type"));
        assertEquals("POST", body.get("httpMethod"));
        assertEquals(30000L, body.get("timeoutMs"));
        var execution = (Map<?, ?>) body.get("execution");
        assertEquals(true, execution.get("readonly"));
        assertEquals(false, execution.get("sideEffect"));
        // EX-04保留来源显式风险；确认要求仍不能低于执行旗标推导的底线。
        assertEquals("READ_ONLY", body.get("riskLevel"));
        var guard = (Map<?, ?>) body.get("guard");
        assertEquals(List.of("region"), guard.get("guardAttrs"));
        assertTrue(((List<?>) guard.get("roles")).isEmpty());
        var importSource = (Map<?, ?>) body.get("importSource");
        assertEquals("ONTOLOGY", importSource.get("kind"));
        assertEquals("route3", importSource.get("spaceCode"));
        assertEquals("v3", importSource.get("version"));
        assertEquals(authRef, body.get("authRef"));
        assertEquals("API_KEY", body.get(AuthScheme.FIELD));
        assertFalse(Json.write(body).contains("ontopa_import_test_only"));
    }

    @Test
    void preview与缺releaseId的manifest拒绝() {
        var preview = formalManifest("user_query");
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) preview.get("ontology_meta");
        meta.put("preview", true);
        var ex = invalid(request(preview));
        assertTrue(ex.getMessage().contains("正式"));
    }

    @Test
    void 非发布路由endpoint拒绝() {
        var manifest = formalManifest("user_query");
        manifest.put("endpoint", "https://onto.example.com/onto/internal/candidates/abc/ops/user_query");
        var ex = invalid(request(manifest));
        assertTrue(ex.getMessage().contains("发布消费路由"));
    }

    @Test
    void 非HTTP或非同步或非POST的manifest拒绝() {
        var adapter = formalManifest("user_query");
        adapter.put("adapter_type", "MCP");
        assertThrows(PlatformException.class, () -> imports.importManifests(request(adapter)));
        var kind = formalManifest("user_query");
        kind.put("invocation_kind", "SUBMIT_QUERY");
        assertThrows(PlatformException.class, () -> imports.importManifests(request(kind)));
        var method = formalManifest("user_query");
        method.put("http_method", "GET");
        assertThrows(PlatformException.class, () -> imports.importManifests(request(method)));
    }

    @Test
    void tool_code非法编码拒绝() {
        var ex = invalid(request(formalManifest("对象查询")));
        assertTrue(ex.getMessage().contains("ASCII"));
    }

    @Test
    void timeout越界与guard_attrs非法拒绝() {
        var timeout = formalManifest("user_query");
        ((Map<String, Object>) timeout.get("exec_attrs")).put("timeout_ms", 900000);
        var ex = invalid(request(timeout));
        assertTrue(ex.getMessage().contains("100至600000"));
        var guards = formalManifest("user_query");
        guards.put("guard_attrs", List.of(""));
        assertThrows(PlatformException.class, () -> imports.importManifests(request(guards)));
    }

    @Test
    void 整批校验先行任一非法则零创建() {
        var good = formalManifest("user_query");
        var bad = formalManifest("user_query2");
        ((Map<String, Object>) bad.get("exec_attrs")).put("side_effect", "yes");
        var ex = invalid(request(good, bad));
        assertTrue(ex.getMessage().contains("布尔"));
        verify(catalog, never()).create(any());
    }

    @Test
    void manifest编码重复拒绝() {
        var ex = invalid(request(formalManifest("user_query"), formalManifest("user_query")));
        assertTrue(ex.getMessage().contains("重复"));
        verify(catalog, never()).create(any());
    }

    @Test
    void 缺manifests拒绝() {
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of()));
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of())));
    }

    @Test
    void missingOrBlankCredentialRejectsTheWholeBatchBeforeCreation() {
        for (String reference : List.of("", "   ")) {
            var body = request(formalManifest("query_one"), formalManifest("query_two"));
            if (reference.isEmpty()) body.remove("authRef");
            else body.put("authRef", reference);
            var failure = invalid(body);
            assertEquals(400, failure.status());
            assertEquals(AuthScheme.CODE_CREDENTIAL_MISSING, failure.code());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void explicitConflictingOrUnknownSchemeIsNeverDiscarded() {
        for (String scheme : List.of("NONE", "BEARER", "BASIC", "")) {
            var body = request(formalManifest("user_query"));
            body.put(AuthScheme.FIELD, scheme);
            var failure = invalid(body);
            assertEquals(400, failure.status());
            assertEquals(
                    Set.of("NONE", "BEARER").contains(scheme)
                            ? AuthScheme.CODE_CONFLICT
                            : AuthScheme.CODE_INVALID,
                    failure.code());
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void explicitApiKeyDeclarationSurvivesImport() {
        var body = request(formalManifest("user_query"));
        body.put(AuthScheme.FIELD, "API_KEY");
        when(catalog.create(anyMap())).thenAnswer(call -> call.getArgument(0));
        var draft = ((List<Map<String, Object>>) imports.importManifests(body).get("drafts")).getFirst();
        assertEquals("API_KEY", draft.get(AuthScheme.FIELD));
        assertEquals(authRef, draft.get("authRef"));
    }

    @Test
    void invalidOrUnavailableCredentialRejectsBeforeCreationWithoutExposingReference() {
        for (String reference : List.of("plain-secret", "env:ONTO_CONSUMER_KEY", "vault:not-a-uuid", "vault:" + UUID.randomUUID())) {
            var body = request(formalManifest("query_one"), formalManifest("query_two"));
            body.put("authRef", reference);
            var failure = invalid(body);
            assertEquals(400, failure.status());
            assertFalse(failure.getMessage().contains(reference));
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void anotherTenantCredentialKeepsScopeDeniedAndCreatesNothing() {
        String otherRef = SecretVault.put("other-tenant-test-only", new Actor("other-tenant", "admin", Set.of("ADMIN"), Map.of()));
        var body = request(formalManifest("user_query"));
        body.put("authRef", otherRef);
        var failure = invalid(body);
        assertEquals(403, failure.status());
        assertEquals("SECRET_SCOPE_DENIED", failure.code());
        assertFalse(failure.getMessage().contains(otherRef));
        verifyNoInteractions(catalog);
    }

    @Test
    void credentialImportNeedsAdminBeforeAnyDraftIsCreated() {
        signIn(TENANT, List.of("EDITOR"));
        for (String reference : List.of(authRef, "vault:not-a-uuid")) {
            var body = request(formalManifest("user_query"));
            body.put("authRef", reference);
            var failure = invalid(body);
            assertEquals(403, failure.status());
            assertEquals("SYS-APP-4031", failure.code(), "必须先检查ADMIN，不能先解析引用而返回400");
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void strictRegistrationAcceptsTheImportedSchemeAndOntologySource() {
        var strict = new SkillCatalog(null, null, null, "http://unused", false);
        when(catalog.create(anyMap())).thenAnswer(call -> strict.normalize(call.getArgument(0)));
        var result = imports.importManifests(request(formalManifest("user_query")));
        var draft = ((List<Map<String, Object>>) result.get("drafts")).getFirst();
        assertEquals("API_KEY", draft.get(AuthScheme.FIELD));
        assertEquals("ONTOLOGY", SkillCatalog.object(draft.get("importSource")).get("kind"));
        assertEquals(authRef, draft.get("authRef"));
    }

    @Test
    void ontologySourceRejectsUnknownFieldsAndMalformedCoordinates() {
        var normalizer = new SkillCatalog(null, null, null, "http://unused");
        var validSource = SkillCatalog.object(imports.toSkillBody(formalManifest("user_query"), authRef).get("importSource"));
        var invalidSources = new ArrayList<Map<String, Object>>();
        for (String field : validSource.keySet()) {
            var missing = new LinkedHashMap<>(validSource);
            missing.remove(field);
            invalidSources.add(missing);
            var blank = new LinkedHashMap<>(validSource);
            blank.put(field, "");
            invalidSources.add(blank);
        }
        for (var mutation : List.of(
                Map.of("unexpected", "ignored-execution-field"),
                Map.of("kind", "UNKNOWN"),
                Map.of("kind", "OPENAPI"),
                Map.of("releaseId", "not-a-uuid"),
                Map.of("contentDigest", "A".repeat(64)),
                Map.of("contentDigest", "g".repeat(64)),
                Map.of("contentDigest", "a".repeat(63)),
                Map.of("contentDigest", "a".repeat(65)))) {
            var malformed = new LinkedHashMap<>(validSource);
            malformed.putAll(mutation);
            invalidSources.add(malformed);
        }
        for (var source : invalidSources) {
            var body = imports.toSkillBody(formalManifest("user_query"), authRef);
            body.put("importSource", source);
            var failure = assertThrows(PlatformException.class, () -> normalizer.normalize(body));
            assertEquals("INVALID_IMPORT_SOURCE", failure.code());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
    void postgresImportReadPublishAndExecuteKeepsOnlyApiKeyWithLegacyDisabled() throws Exception {
        // 建库由已授权的前置runner负责；任何直跑入口也必须先证明专库归属，才允许迁移。
        String database = "agent_platform_ontology_import_test";
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        var ownership = admin.queryForList(
                "select pg_get_userbyid(datdba) as owner,shobj_description(oid,'pg_database') as marker"
                        + " from pg_database where datname=?",
                database);
        assertEquals(1, ownership.size(), "先由EX-03授权runner核验并准备专库，不自动创建或跳过");
        assertEquals("agent_v12", ownership.getFirst().get("owner"), "专库owner不符，禁止迁移");
        assertEquals(
                "agent-platform EX-03 2026-10-28 ontology import isolated test database",
                ownership.getFirst().get("marker"),
                "缺EX-03准确归属标记，禁止迁移");
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/" + database, "agent_v12", password);
        Flyway.configure().dataSource(dataSource).schemas("skill").defaultSchema("skill").locations("classpath:db/migration").load().migrate();
        var db = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        String tenant = "ontology_" + UUID.randomUUID();
        signIn(tenant, List.of("ADMIN", "EDITOR", "SERVICE"));
        String reference = SecretVault.put("ontopa_import_test_only");
        var clients = mock(SkillClients.class);
        var persistentCatalog = new SkillCatalog(db, manager, clients, "http://app.test", false);
        var received = new CopyOnWriteArrayList<Map<String, List<String>>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String code = "query_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/onto/r/route3/releases/9f8e7d6c-1111-2222-3333-444455556666/ops/" + code;
        server.createContext(path, exchange -> {
            var headers = new LinkedHashMap<String, List<String>>();
            headers.put("X-API-Key", List.copyOf(exchange.getRequestHeaders().getOrDefault("X-API-Key", List.of())));
            headers.put("Authorization", List.copyOf(exchange.getRequestHeaders().getOrDefault("Authorization", List.of())));
            received.add(headers);
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"code\":\"OK\",\"data\":{\"found\":true},\"meta\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var manifest = formalManifest(code);
            manifest.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + path);
            manifest.put("return_spec", Map.of("structuredPath", "$.data"));
            var result = new OntologyManifestImports(persistentCatalog).importManifests(Map.of("manifests", List.of(manifest), "authRef", reference));
            var draft = ((List<Map<String, Object>>) result.get("drafts")).getFirst();
            long versionId = ((Number) draft.get("skillVersionId")).longValue();
            var stored = persistentCatalog.versionId(versionId);
            assertEquals("DRAFT", stored.get("status"));
            assertEquals("API_KEY", stored.get(AuthScheme.FIELD));
            assertEquals(reference, stored.get("authRef"));
            assertEquals("ONTOLOGY", SkillCatalog.object(stored.get("importSource")).get("kind"));
            assertEquals("CONNECTION_TEST_REQUIRED", assertThrows(PlatformException.class, () -> persistentCatalog.publish(code, 1)).code());

            var adapters = new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
            var execution = new SkillExecution(db, manager, persistentCatalog, adapters, clients, "http://engine.test");
            // 引擎权威接口是协议替身；成功事实必须由真实适配器HTTP和SkillExecution写入PG。
            executeFixture(execution, clients, code, versionId, stored.get("sha256").toString(), 1, true);
            assertEquals(1, received.size());
            var governance = new SkillGovernance(db, persistentCatalog, adapters, clients, "http://model.test", "http://engine.test");
            assertEquals(true, governance.connection(code, 1, Map.of("controlledCallId", 1)).get("ok"));
            assertEquals("ACCEPTANCE_REQUIRED", assertThrows(PlatformException.class, () -> persistentCatalog.publish(code, 1)).code());
            // 选型使用确定性模型协议替身，不代表真实模型验收；通过记录由生产治理代码计算后落库。
            when(clients.post(eq("http://model.test/internal/model/chat"), anyMap()))
                    .thenReturn(Json.tree(Map.of("toolCalls", List.of(Map.of("name", code, "arguments", Map.of("region", "A"))))));
            assertEquals(true, governance.acceptance(code, 1, Map.of("modelEntryId", 1, "cases", List.of(Map.of("question", "查询A", "expectSelected", code, "expectArgs", Map.of("region", "A"))))).get("pass"));
            assertEquals("PUBLISHED", persistentCatalog.publish(code, 1).get("status"));
            var published = persistentCatalog.versionId(versionId);
            assertEquals(stored.get("definition"), published.get("definition"));
            assertEquals(stored.get("sha256"), published.get("sha256"));
            executeFixture(execution, clients, code, versionId, published.get("sha256").toString(), 2, false);
            assertEquals(2, received.size());
            for (var headers : received) {
                assertEquals(List.of("ontopa_import_test_only"), headers.get("X-API-Key"));
                assertEquals(List.of(), headers.get("Authorization"));
            }
        } finally {
            server.stop(0);
        }
    }

    static void executeFixture(SkillExecution execution, SkillClients clients, String code, long versionId, String sha, long callId, boolean controlled) {
        var arguments = Map.of("region", "A");
        var authority = new LinkedHashMap<String, Object>();
        authority.put("claimed", true);
        authority.put("attempt_id", 1);
        authority.put("skill_version_id", versionId);
        authority.put("skill_code", code);
        authority.put("args_digest", Digest.sha256(Json.canonical(arguments)));
        authority.put("node_instance_id", 1);
        authority.put("runId", "r_ontology_import_" + callId);
        authority.put("idempotency_key", "ontology_import_" + callId);
        authority.put("trigger_type", controlled ? "DEBUG" : "FORMAL");
        if (controlled) {
            authority.put("controlled_draft_skill_version_id", versionId);
            authority.put("controlled_draft_skill_digest", sha);
        }
        String endpoint = "http://engine.test/internal/tool-calls/" + callId;
        when(clients.get(endpoint)).thenReturn(Json.tree(authority));
        when(clients.post(eq(endpoint + "/claim"), anyMap())).thenReturn(Json.tree(authority));
        // 提交权确认先于真实HTTP；仅替身准确协议响应，生产执行链仍检查submitted标志。
        when(clients.post(eq(endpoint + "/submitted"), anyMap())).thenReturn(Json.tree(Map.of("submitted", true)));
        when(clients.post(eq(endpoint + "/fact"), anyMap())).thenReturn(Json.tree(Map.of("accepted", true)));
        var request = new LinkedHashMap<String, Object>();
        request.put("callId", callId);
        request.put("skillVersionId", versionId);
        request.put("attemptId", 1);
        request.put("leaseOwner", "ontology-import-owner");
        request.put("nodeInstanceId", 1);
        request.put("runId", "r_ontology_import_" + callId);
        request.put("idempotencyKey", "ontology_import_" + callId);
        request.put("arguments", arguments);
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
    }
}
