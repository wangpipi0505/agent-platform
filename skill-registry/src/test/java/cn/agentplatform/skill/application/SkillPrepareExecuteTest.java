package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PLAT-15（契约 §14）：PREPARE/EXECUTE 两段的技能侧验收——PREPARE 返回 actionPlan 且不触发业务
 * 写、EXECUTE 以引擎盖章键出站并携带 executeKey、注册期契约边界（适配器/形状/风险）、PREPARE
 * 失败可读且无副作用、调试 Mock 跳过业务预演，以及旧契约（无 approval 声明）零回归锚点。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillPrepareExecuteTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);
    HttpFixture endpoint;
    HttpFixture prepareEndpoint;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    SkillAdapters adapters;
    Map<String, Object> request;
    String code;
    long version;
    long stubVersion;
    JsonNode registration;

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
    void prepareTest() throws Exception {
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
        prepareEndpoint = new HttpFixture();
        prepareEndpoint.response =
                "{\"summary\":\"创建用户 user-42 并启用\",\"target\":\"user-42\",\"diff\":{\"enable\":true}}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, manager, clients, "http://unused");
        adapters = new SkillAdapters(new ArtifactStore(db), new AlgorithmAdapter(db));
        executor =
                new SkillExecution(db, manager, catalog, adapters, clients, "http://engine.test");
        code = "prep_" + UUID.randomUUID().toString().replace("-", "");
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
                        List.of("APPROVER"),
                        "approval",
                        Map.of(
                                "mode",
                                "PREPARE_EXECUTE",
                                "risk",
                                "STANDARD",
                                "prepare",
                                Map.of("endpoint", prepareEndpoint.url()))));
        var created = catalog.create(body);
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
        stubVersion = version;
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
        registration =
                Json.tree(
                        Map.of(
                                "controlled_draft_skill_version_id",
                                0,
                                "trigger_type",
                                "TASK",
                                "mock_snapshot",
                                java.util.Collections.emptyMap()));
        when(clients.get(matches(".*/internal/tool-calls/\\d+$"))).thenReturn(registration);
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String url = invocation.getArgument(0);
                            if (url.endsWith("/claim")) {
                                var claim =
                                        Json.tree(
                                                Map.of(
                                                        "claimed",
                                                        true,
                                                        "status",
                                                        "EXECUTING",
                                                        "skill_version_id",
                                                        stubVersion,
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
                                                        "TASK",
                                                        "idempotency_key",
                                                        "logical-key",
                                                        "mock_snapshot",
                                                        java.util.Collections.emptyMap()));
                                ((com.fasterxml.jackson.databind.node.ObjectNode) claim)
                                        .put(
                                                "attempt_id",
                                                ((Number) request.get("attemptId")).longValue());
                                return claim;
                            }
                            if (url.endsWith("/submitted")) return Json.tree(Map.of("submitted", true));
                            if (url.endsWith("/fact")) return Json.tree(Map.of("accepted", true));
                            throw new AssertionError("unexpected internal request " + url);
                        });
    }

    @AfterEach
    void close() throws Exception {
        endpoint.close();
        prepareEndpoint.close();
    }

    Map<String, Object> contract() {
        var b = new LinkedHashMap<String, Object>();
        b.put("code", code);
        b.put("name", "两段写技能");
        b.put("type", "HTTP");
        b.put("description", "能力：创建用户。适用：已授权区域。不适用：查询。");
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

    void approvedWithPlan(long skillVersion, String planKey) {
        when(clients.get(contains("/approval")))
                .thenReturn(
                        Json.tree(
                                Map.of(
                                        "approved",
                                        true,
                                        "skillVersionId",
                                        skillVersion,
                                        "parametersDigest",
                                        Digest.sha256(Json.canonical(request.get("arguments"))),
                                        "actionPlan",
                                        Map.of(
                                                "id",
                                                7,
                                                "idempotencyKey",
                                                planKey,
                                                "digest",
                                                "a".repeat(64),
                                                "risk",
                                                "STANDARD",
                                                "expiresAt",
                                                Instant.now().plusSeconds(600).toString()))));
    }

    @Test
    void prepareReturnsActionPlanWithoutBusinessSideEffect() {
        when(clients.get(contains("/approval"))).thenReturn(Json.tree(Map.of("approved", false)));
        var response = executor.execute(code, request);
        assertEquals("WAITING_APPROVAL", response.get("status"));
        var confirmation = Json.tree(response.get("confirmation"));
        var plan = confirmation.path("actionPlan");
        assertTrue(plan.isObject(), "两段技能的 WAITING_APPROVAL 必须携带 actionPlan");
        assertEquals("创建用户 user-42 并启用", plan.path("summary").asText());
        assertEquals("user-42", plan.path("target").asText());
        assertEquals(true, plan.path("diff").path("enable").asBoolean());
        assertEquals("STANDARD", plan.path("risk").asText());
        // 验收①：PREPARE 只调预演入口，业务写端点零调用、零 claim（无业务副作用）。
        assertEquals(1, prepareEndpoint.calls.get());
        assertEquals(0, endpoint.calls.get());
        verify(clients, never()).post(endsWith("/claim"), any());
        verify(clients, never()).post(endsWith("/submitted"), any());
    }

    @Test
    void approvedExecuteUsesStampedKeyAndCarriesItToClaim() {
        approvedWithPlan(version, "plan-key-123");
        var response = executor.execute(code, request);
        assertEquals("SUCCEEDED", response.get("status"));
        // EXECUTE 出站键=引擎盖章的 actionPlan.idempotencyKey，claim 请求携带同一 executeKey。
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(clients, times(1)).post(endsWith("/claim"), body.capture());
        assertEquals(
                "plan-key-123",
                Json.tree(body.getValue()).path("executeKey").asText());
        assertEquals("plan-key-123", endpoint.lastIdempotencyKey);
        assertEquals(0, prepareEndpoint.calls.get());
    }

    @Test
    void prepareFailureIsReadableWithoutSideEffectOrWaiting() {
        prepareEndpoint.response = "boom".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(clients.get(contains("/approval"))).thenReturn(Json.tree(Map.of("approved", false)));
        var failure =
                assertThrows(PlatformException.class, () -> executor.execute(code, request));
        assertEquals("ACTION_PLAN_UNAVAILABLE", failure.code());
        assertEquals(502, failure.status());
        assertEquals(0, endpoint.calls.get());
        verify(clients, never()).post(endsWith("/claim"), any());
    }

    @Test
    void mockDebugRunBuildsPlaceholderPlanWithoutCallingPrepare() {
        registration =
                Json.tree(
                        Map.of(
                                "controlled_draft_skill_version_id",
                                0,
                                "trigger_type",
                                "DEBUG",
                                "mock_snapshot",
                                Map.of("skillCode", code, "mode", "FIXED")));
        when(clients.get(matches(".*/internal/tool-calls/\\d+$"))).thenReturn(registration);
        when(clients.get(contains("/approval"))).thenReturn(Json.tree(Map.of("approved", false)));
        var response = executor.execute(code, request);
        assertEquals("WAITING_APPROVAL", response.get("status"));
        var plan = Json.tree(response.get("confirmation")).path("actionPlan");
        assertTrue(plan.path("summary").asText().contains("调试替身"));
        assertEquals(0, prepareEndpoint.calls.get());
        assertEquals(0, endpoint.calls.get());
    }

    @Test
    void legacyContractWithoutApprovalKeepsSingleStepFlow() {
        // ⑦ 旧技能锚点：无 approval 声明 = SINGLE_STEP——不调预演入口、claim 不带 executeKey、
        // 业务键仍是逻辑调用键，行为与存量完全一致。
        code = "legacy_" + UUID.randomUUID().toString().replace("-", "");
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
        long legacyVersion = ((Number) created.get("skillVersionId")).longValue();
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                legacyVersion);
        db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where tenant_id=?"
                    + " and code=?",
                legacyVersion,
                "skill-tests",
                code);
        request.put("skillVersionId", legacyVersion);
        stubVersion = legacyVersion;
        approvedWithPlan(legacyVersion, "plan-key-should-be-ignored");
        when(clients.get(matches(".*/internal/tool-calls/\\d+$"))).thenReturn(registration);
        var response = executor.execute(code, request);
        assertEquals("SUCCEEDED", response.get("status"));
        assertEquals(0, prepareEndpoint.calls.get());
        assertEquals(1, endpoint.calls.get());
        assertEquals("logical-key", endpoint.lastIdempotencyKey);
        ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
        verify(clients, times(1)).post(endsWith("/claim"), captured.capture());
        assertTrue(Json.tree(captured.getValue()).path("executeKey").isMissingNode());
    }

    @Test
    void registrationRejectsInvalidApprovalDeclarations() {
        // 形状非法的 approval 声明在注册期被拒（契约 §14.1/§14.6），错误码可读。
        // create 是"新技能首版"入口，每个子用例用独立 code，互不干扰。
        code = "mode_" + UUID.randomUUID().toString().replace("-", "");
        var badMode = contract();
        badMode.put(
                "execution",
                Map.of(
                        "readonly", false,
                        "sideEffect", true,
                        "idempotent", true,
                        "cancellable", false,
                        "confirmRoles", List.of("APPROVER"),
                        "approval", Map.of("mode", "TWO_PHASE")));
        assertEquals(
                "APPROVAL_MODE_INVALID",
                assertThrows(PlatformException.class, () -> catalog.create(badMode)).code());

        code = "prep2_" + UUID.randomUUID().toString().replace("-", "");
        var missingPrepare = contract();
        missingPrepare.put(
                "execution",
                Map.of(
                        "readonly", false,
                        "sideEffect", true,
                        "idempotent", true,
                        "cancellable", false,
                        "confirmRoles", List.of("APPROVER"),
                        "approval", Map.of("mode", "PREPARE_EXECUTE")));
        assertEquals(
                "APPROVAL_PREPARE_INVALID",
                assertThrows(PlatformException.class, () -> catalog.create(missingPrepare)).code());

        code = "risk_" + UUID.randomUUID().toString().replace("-", "");
        var badRisk = contract();
        badRisk.put(
                "execution",
                Map.of(
                        "readonly", false,
                        "sideEffect", true,
                        "idempotent", true,
                        "cancellable", false,
                        "confirmRoles", List.of("APPROVER"),
                        "approval",
                        Map.of(
                                "mode",
                                "PREPARE_EXECUTE",
                                "risk",
                                "EXTREME",
                                "prepare",
                                Map.of("endpoint", prepareEndpoint.url()))));
        assertEquals(
                "APPROVAL_RISK_INVALID",
                assertThrows(PlatformException.class, () -> catalog.create(badRisk)).code());

        code = "ro_" + UUID.randomUUID().toString().replace("-", "");
        var readonlyTwoPhase = contract();
        readonlyTwoPhase.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", true,
                        "confirmRoles", List.of("APPROVER"),
                        "approval",
                        Map.of(
                                "mode",
                                "PREPARE_EXECUTE",
                                "prepare",
                                Map.of("endpoint", prepareEndpoint.url()))));
        assertEquals(
                "APPROVAL_PREPARE_INVALID",
                assertThrows(PlatformException.class, () -> catalog.create(readonlyTwoPhase))
                        .code());
    }

    @Test
    void registrationRejectsTwoPhaseOnAsyncSubmitQuerySkill() {
        // 契约 §14.1：PREPARE_EXECUTE 仅支持 HTTP 同步；SUBMIT_QUERY 自带任务态语义，声明两段 → 400。
        code = "async_" + UUID.randomUUID().toString().replace("-", "");
        var body = contract();
        body.put("invocationKind", "SUBMIT_QUERY");
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put("endpoint", endpoint.url() + "/jobs/{idempotencyKey}");
        reconciliation.put("method", "GET");
        reconciliation.put("donePath", "$.status");
        reconciliation.put("doneValue", "DONE");
        reconciliation.put("idempotencyKeyPath", "$.requestKey");
        body.put("reconciliation", reconciliation);
        body.put(
                "execution",
                Map.of(
                        "readonly", false,
                        "sideEffect", true,
                        "idempotent", true,
                        "cancellable", false,
                        "confirmRoles", List.of("APPROVER"),
                        "approval",
                        Map.of(
                                "mode",
                                "PREPARE_EXECUTE",
                                "prepare",
                                Map.of("endpoint", prepareEndpoint.url()))));
        assertEquals(
                "APPROVAL_PREPARE_UNSUPPORTED",
                assertThrows(PlatformException.class, () -> catalog.create(body)).code());
    }
}
