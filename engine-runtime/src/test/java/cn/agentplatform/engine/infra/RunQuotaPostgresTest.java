package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.UsageController;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.*;
import java.util.*;

/**
 * T19.1（PLAT-19 缩水版，X-6）：token 计量聚合与 tenant/app/credential 三级滑动窗口配额。
 *
 * <p>计量事实只来自既有 trace 写入（engine.model_invocation.usage_accounted + response.usage），
 * 这里验证：配额内放行、三级各自可读拒绝（QUOTA 信封字段）、窗口滑出恢复放行、开关关闭即回滚。
 * 迁移在真实 PG 的既有库上增量应用（Flyway），V223 只增索引、不动旧数据。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunQuotaPostgresTest {
    static final Actor actor = new Actor("quota-test", "alice", Set.of("EDITOR"), Map.of());

    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
        var db = TracePostgresTest.db;
        // 与 RunQuotaAdmissionPostgresTest 同 JVM 分批跑时的残留隔离：按完整 FK 顺序清理配额租户。
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id"
                    + " like 'quota-%')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id"
                    + " like 'quota-%')");
        db.update(
                "delete from engine.model_invocation where run_id in(select id from engine.run"
                    + " where tenant_id like 'quota-%')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                    + " tenant_id like 'quota-%')");
        db.update("delete from engine.run where tenant_id like 'quota-%'");
    }

    RunQuota quota(boolean enabled, long window, long tenant, long app, long credential) {
        var q = new RunQuota(TracePostgresTest.db, Clock.systemUTC());
        q.quotaEnabled(enabled);
        q.windowSeconds(window);
        q.tenantTokens(tenant);
        q.appTokens(app);
        q.credentialTokens(credential);
        return q;
    }

    /** 冻结快照：主模型 modelEntryId=7，冻结引用带 providerId（credential 级配额主体）。 */
    JsonNode frozen(long providerId) {
        return Json.tree(
                Map.of(
                        "config",
                        Map.of("modelEntryId", 7),
                        "dependencyVersions",
                        Map.of(
                                "modelRefs",
                                List.of(
                                        Map.of(
                                                "modelEntryId",
                                                7,
                                                "entry",
                                                Map.of("providerId", providerId, "modelName", "model-a"))))));
    }

    /** 种一条已计量（usage_accounted=true）的模型用量事实，返回 run id。 */
    long seedUsage(
            String tenant, String app, String user, long providerId, long tokens, Instant usageAt) {
        var db = TracePostgresTest.db;
        long r =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,?,1,'TASK','SUCCEEDED',?,?,?,?::jsonb,'{}','{}','{}','{}','STEP')"
                            + " returning id",
                        Long.class,
                        "rq_" + UUID.randomUUID(),
                        app,
                        user,
                        tenant,
                        "pg:quota/" + UUID.randomUUID(),
                        "{}");
        long n =
                db.queryForObject(
                        "insert into"
                            + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,'n1',1,'AGENT','SUCCEEDED','{\"type\":\"AGENT\"}') returning id",
                        Long.class,
                        r);
        db.update(
                "insert into"
                    + " engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,response,status,usage_accounted,updated_at)"
                    + " values(?,?,'0','MAIN','{}'::jsonb,?::jsonb,'SUCCEEDED',true,?)",
                r,
                n,
                Json.write(
                        Map.of(
                                "usage",
                                Map.of(
                                        "promptTokens",
                                        tokens,
                                        "completionTokens",
                                        0,
                                        "totalTokens",
                                        tokens),
                                "actualModel",
                                Map.of(
                                        "modelEntryId",
                                        7,
                                        "modelName",
                                        "model-a",
                                        "providerId",
                                        providerId,
                                        "endpoint",
                                        "https://model.example"))),
                java.sql.Timestamp.from(usageAt));
        return r;
    }

    @Test
    void withinQuotaAdmitsAndSeedUsageAggregatesByAppUserModel() {
        Instant now = Instant.now();
        seedUsage("quota-test", "app-a", "alice", 3, 300, now.minusSeconds(60));
        seedUsage("quota-test", "app-a", "bob", 3, 200, now.minusSeconds(60));
        seedUsage("quota-test", "app-b", "alice", 4, 100, now.minusSeconds(60));
        var q = quota(true, 3600, 1000, 1000, 1000);
        q.requireRunAdmission(actor, "app-a", frozen(3));
        assertEquals(600, q.tenantWindowTokens("quota-test", 3600));
        assertEquals(500, q.appWindowTokens("quota-test", "app-a", 3600));
        assertEquals(500, q.credentialWindowTokens("quota-test", 3, 3600));
        assertEquals(Map.of("app-a", 500L, "app-b", 100L), scopes(q.aggregate(actor, "app", 3600)));
        assertEquals(Map.of("alice", 400L, "bob", 200L), scopes(q.aggregate(actor, "user", 3600)));
        assertEquals(Map.of("model-a", 600L), scopes(q.aggregate(actor, "model", 3600)));
    }

    @Test
    void tenantLevelOverrunRejectsReadableQuotaEnvelope() {
        seedUsage("quota-test", "app-a", "alice", 3, 1200, Instant.now().minusSeconds(30));
        var q = quota(true, 3600, 1000, 0, 0);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () -> q.requireRunAdmission(actor, "app-a", frozen(3)));
        assertEquals("QUOTA", rejected.code());
        assertEquals(429, rejected.status());
        String message = rejected.getMessage();
        assertTrue(message.contains("租户"), () -> "userMessage 必须说明哪一级配额: " + message);
        assertTrue(message.contains("1200"), () -> "userMessage 必须含当前用量: " + message);
        assertTrue(message.contains("1000"), () -> "userMessage 必须含上限: " + message);
    }

    @Test
    void appLevelOverrunRejectsReadableQuotaEnvelope() {
        seedUsage("quota-test", "app-a", "alice", 3, 30, Instant.now().minusSeconds(30));
        var q = quota(true, 3600, 10000, 20, 0);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () -> q.requireRunAdmission(actor, "app-a", frozen(3)));
        assertEquals("QUOTA", rejected.code());
        String message = rejected.getMessage();
        assertTrue(message.contains("应用"), () -> "userMessage 必须说明哪一级配额: " + message);
        assertTrue(message.contains("30"), () -> "userMessage 必须含当前用量: " + message);
        assertTrue(message.contains("20"), () -> "userMessage 必须含上限: " + message);
    }

    @Test
    void credentialLevelOverrunRejectsReadableQuotaEnvelope() {
        seedUsage("quota-test", "app-a", "alice", 3, 40, Instant.now().minusSeconds(30));
        var q = quota(true, 3600, 10000, 10000, 35);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () -> q.requireRunAdmission(actor, "app-a", frozen(3)));
        assertEquals("QUOTA", rejected.code());
        String message = rejected.getMessage();
        assertTrue(message.contains("模型凭据"), () -> "userMessage 必须说明哪一级配额: " + message);
        assertTrue(message.contains("40"), () -> "userMessage 必须含当前用量: " + message);
        assertTrue(message.contains("35"), () -> "userMessage 必须含上限: " + message);
    }

    @Test
    void slidingWindowRestoresAdmissionAfterUsageSlidesOut() {
        long window = 60;
        seedUsage(
                "quota-test", "app-a", "alice", 3, 1200, Instant.now().minusSeconds(window + 5));
        var q = quota(true, window, 1000, 0, 0);
        q.requireRunAdmission(actor, "app-a", frozen(3));
        assertEquals(0, q.tenantWindowTokens("quota-test", window));
        // 窗口内边界：刚好落在窗口内的用量仍计数并拒绝。
        seedUsage("quota-test", "app-a", "bob", 3, 1200, Instant.now().minusSeconds(window - 5));
        assertThrows(
                PlatformException.class, () -> q.requireRunAdmission(actor, "app-a", frozen(3)));
    }

    @Test
    void switchOffDisablesQuotaEnforcement() {
        seedUsage("quota-test", "app-a", "alice", 3, 1200, Instant.now().minusSeconds(30));
        var q = quota(false, 3600, 1000, 1000, 1000);
        q.requireRunAdmission(actor, "app-a", frozen(3));
    }

    @Test
    void zeroLimitMeansUnlimitedAndUnknownModelSkipsCredentialLevel() {
        seedUsage("quota-test", "app-a", "alice", 3, 1200, Instant.now().minusSeconds(30));
        var q = quota(true, 3600, 0, 0, 0);
        q.requireRunAdmission(actor, "app-a", frozen(3));
        // 无模型引用的快照（providerId<=0）：credential 级跳过，不误伤。
        q.requireRunAdmission(actor, "app-a", Json.tree(Map.of("config", Map.of())));
    }

    @Test
    void migrationAddsWindowIndexes() {
        Integer indexes =
                TracePostgresTest.db.queryForObject(
                        "select count(*) from pg_indexes where schemaname='engine' and indexname in"
                            + " ('idx_model_invocation_usage_window','idx_run_app_started')",
                        Integer.class);
        assertEquals(2, indexes, "V223 必须建立窗口聚合所需的两个索引");
    }

    /** 聚合读路径的控制器装配：tenant 取自已验 JWT，返回统一信封；非法维度走 INVALID_INPUT。 */
    MockMvc usageMvc() {
        return MockMvcBuilders.standaloneSetup(
                        new UsageController(quota(true, 3600, 0, 0, 0)))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("verified")
                                        .header("alg", "HS256")
                                        .subject("alice")
                                        .claim("tenant_id", "quota-test")
                                        .claim("roles", java.util.List.of("EDITOR"))
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build()));
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usageSummaryEndpointReturnsTenantScopedEnvelope() throws Exception {
        seedUsage("quota-test", "app-a", "alice", 3, 300, Instant.now().minusSeconds(60));
        login();
        var body =
                usageMvc()
                        .perform(get("/admin/usage/summary").param("by", "app"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var envelope = Json.read(body);
        assertEquals("OK", envelope.path("code").asText());
        assertEquals(
                Map.of("app-a", 300L),
                scopes(Json.mapper().convertValue(envelope.path("data"), List.class)));
    }

    @Test
    void usageSummaryRejectsUnknownDimensionWithReadableEnvelope() throws Exception {
        login();
        usageMvc()
                .perform(get("/admin/usage/summary").param("by", "everything"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    private Map<String, Long> scopes(List<Map<String, Object>> rows) {
        var result = new LinkedHashMap<String, Long>();
        for (var row : rows)
            result.put(
                    row.get("scope").toString(), Long.parseLong(row.get("tokens").toString()));
        return result;
    }
}
