package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.api.PublishWaiverController;
import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * PLAT-02 发布门禁分层与显式豁免的失败路径。
 *
 * <p>只连显式提供的独立 PostgreSQL（不替换内存库）；评测/模型依赖用本进程 HTTP 桩，保证门禁之外的结构、
 * 参数、引用检查真实通过，从而把断言集中在本卡的门禁语义上。app-management 既有的 Y-4 基座债务不在本类
 * 断言范围，本类只按 PublishGate* 定向运行。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PublishGateTest {
    static JdbcTemplate db;
    static AppService apps;
    static HttpServer stub;
    static volatile boolean evalDown;
    String code;

    @BeforeAll
    static void database() throws Exception {
        // AppService 在本类由手工构造、不经 Spring 容器，因此显式完成与 SecurityConfiguration 相同的
        // 部署登记与签名密钥设置：服务票据签发因此仍走真实签名路径，而不是绕开门禁依赖。
        System.setProperty("agent.jwt.secret", "T02-publish-gate-test-secret-0123456789abcdef");
        var authority = new org.springframework.mock.env.MockEnvironment();
        authority.setActiveProfiles("test");
        authority.setProperty("agent.security.mode", "test");
        authority.setProperty("agent.security.service-id", "app");
        authority.setProperty("server.address", "127.0.0.1");
        SecurityConfiguration.bindDeployment(authority);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("app")
                .defaultSchema("app")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 默认"没有合格报告"：门禁必须自己决定阻断、告警还是豁免，不能靠依赖返回 accepted=true 蒙混通过。
        // evalDown 模拟监控侧故障（503）：门禁必须 fail-closed，且 OFF 模式不得再依赖该服务。
        stub.createContext(
                "/internal/evaluations/accepted",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    if (evalDown) {
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                        return;
                    }
                    respond(exchange, "{\"data\":{\"accepted\":false}}");
                });
        reply(
                "/internal/model-entries",
                "{\"data\":{\"id\":41,\"provider\":{\"status\":\"ENABLED\"}}}");
        reply(
                "/internal/model-bindings/freeze",
                "{\"data\":{\"modelEntryId\":41,\"bindingId\":7,\"sha256\":\"freeze-7\","
                        + "\"entry\":{\"id\":41,\"modelName\":\"gate-model\"}}}");
        reply("/internal/model-bindings/validate", "{\"data\":{\"valid\":true}}");
        stub.start();
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        apps =
                new AppService(
                        db,
                        new DataSourceTransactionManager(ds),
                        "http://127.0.0.1:1",
                        base,
                        base,
                        "http://127.0.0.1:1");
    }

    private static void reply(String path, String json) {
        stub.createContext(
                path,
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    respond(exchange, json);
                });
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterAll
    static void stop() {
        if (stub != null) stub.stop(0);
    }

    static void actor(String tenant, String... roles) {
        var jwt =
                Jwt.withTokenValue("test-verified")
                        .header("alg", "HS256")
                        .subject("publisher")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of(roles))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @BeforeEach
    void create() {
        actor("application-tests", "EDITOR", "ADMIN");
        evalDown = false;
        apps.configurePublishGate("BLOCKING", true);
        code = "gate_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        apps.create(Map.of("code", code, "name", "发布门禁测试", "type", "CHAT"));
        // 配置一次带模型绑定的对话应用：修订从1走到2，后续豁免绑定在这个真实候选上。
        apps.save(
                code,
                Map.of("draftRev", 1, "config", Map.of("modelBinding", Map.of("modelEntryId", 41))),
                false);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private PublishWaiverController controller() {
        return new PublishWaiverController(apps);
    }

    private Map<String, Object> waiverBody(String expiresAt, String sha) {
        var app = apps.get(code);
        var body = new HashMap<String, Object>();
        body.put("reason", "评测报告缺失，安全与回归已人工复核，同意本次发布");
        body.put("draftRev", ((Number) app.get("draftRev")).longValue());
        body.put("candidateSha256", sha == null ? app.get("candidateSha256").toString() : sha);
        body.put("expiresAt", expiresAt);
        return body;
    }

    private Map<String, Object> report() {
        return db.queryForMap(
                "select gate_mode,waiver_id,passed,checks::text as checks from"
                    + " app.publish_report where tenant_id=? and app_code=? order by created_at"
                    + " desc,id desc limit 1",
                "application-tests",
                code);
    }

    private Object data(Object response) {
        return ((ApiResponse<?>) response).data();
    }

    @Test
    void blockingWithoutAcceptedReportRejectsWith409AndKeepsAuditRow() {
        var blocked = assertThrows(PlatformException.class, () -> apps.precheck(code));
        assertEquals(409, blocked.status());
        assertEquals("EVAL_GATE_BLOCKED", blocked.code());
        var row = report();
        assertEquals("BLOCKING", row.get("gate_mode"));
        assertNull(row.get("waiver_id"));
        assertEquals(false, row.get("passed"));
        assertTrue(row.get("checks").toString().contains("评测门禁"));
        assertTrue(row.get("checks").toString().contains("BLOCKED"));
        assertTrue(apps.versions(code).isEmpty());
    }

    @Test
    void advisoryWithoutReportPublishesWithWarnings() {
        apps.configurePublishGate("ADVISORY", true);
        var precheck = apps.precheck(code);
        assertEquals(true, precheck.get("passed"));
        assertEquals("PASS", precheck.get("result"));
        var warnings = (List<?>) AppService.object(precheck.get("report")).get("warnings");
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.getFirst().toString().contains("ADVISORY"));
        assertEquals(
                "ADVISORY",
                db.queryForObject(
                        "select gate_mode from app.publish_report where tenant_id=? and id=?",
                        String.class,
                        "application-tests",
                        precheck.get("precheckId")));
        var commit =
                apps.commit(
                        code,
                        Map.of(
                                "precheckId", precheck.get("precheckId"),
                                "draftRev", precheck.get("draftRev"),
                                "candidateSha256", precheck.get("candidateSha256"),
                                "idempotencyRequestNo", "req-" + UUID.randomUUID()));
        assertEquals(1, commit.get("versionNo"));
        assertEquals(1, apps.versions(code).size());
    }

    @Test
    void onlyAdminMayGrantOrRevokeWaiver() {
        actor("application-tests", "EDITOR");
        var granted =
                assertThrows(
                        PlatformException.class,
                        () ->
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        null)));
        assertEquals(403, granted.status());
        var revoked =
                assertThrows(
                        PlatformException.class,
                        () -> controller().revoke(code, "pw_missing", Map.of()));
        assertEquals(403, revoked.status());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from app.publish_waiver where tenant_id=? and app_code=?",
                        Integer.class,
                        "application-tests",
                        code));
    }

    @Test
    void adminWaiverUnblocksBlockingPublishAndIsRecorded() {
        var expiresAt = Instant.now().plusSeconds(7200).toString();
        var granted = AppService.object(data(controller().create(code, waiverBody(expiresAt, null))));
        String waiverId = granted.get("id").toString();
        assertEquals("ACTIVE", granted.get("status"));
        assertEquals("publisher", granted.get("approvedBy"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from app.publish_waiver where tenant_id=? and app_code=?"
                                + " and id=? and reason<>''",
                        Integer.class,
                        "application-tests",
                        code,
                        waiverId));

        var precheck = apps.precheck(code);
        assertEquals(true, precheck.get("passed"));
        assertEquals("BLOCKING", precheck.get("gateMode"));
        assertEquals(waiverId, precheck.get("waiverId"));
        var row = report();
        assertEquals(waiverId, row.get("waiver_id"));
        assertTrue(row.get("checks").toString().contains("WAIVED"));

        var commit =
                apps.commit(
                        code,
                        Map.of(
                                "precheckId", precheck.get("precheckId"),
                                "draftRev", precheck.get("draftRev"),
                                "candidateSha256", precheck.get("candidateSha256"),
                                "idempotencyRequestNo", "req-" + UUID.randomUUID()));
        assertEquals(1, commit.get("versionNo"));

        // 恢复窗口与理由长度在入口就拒绝：豁免不能被写成长期免检或空理由。
        var tooLong =
                assertThrows(
                        PlatformException.class,
                        () ->
                                apps.createWaiver(
                                        code,
                                        waiverBody(
                                                Instant.now().plusSeconds(90000).toString(), null)));
        assertEquals(400, tooLong.status());
        assertEquals("PUBLISH_WAIVER_INVALID", tooLong.code());
        var shortReason = new HashMap<>(waiverBody(expiresAt, null));
        shortReason.put("reason", "太短");
        var rejectedReason =
                assertThrows(
                        PlatformException.class, () -> apps.createWaiver(code, shortReason));
        assertEquals("PUBLISH_WAIVER_INVALID", rejectedReason.code());
    }

    @Test
    void revokedAndExpiredWaiversNoLongerAllowPublishing() {
        var first =
                AppService.object(
                        data(
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        null))));
        String firstId = first.get("id").toString();
        var revoked = AppService.object(data(controller().revoke(code, firstId, Map.of())));
        assertEquals("REVOKED", revoked.get("status"));
        // 撤销幂等：第二次仍返回同一豁免，不复查也不复活。
        assertEquals(
                "REVOKED",
                AppService.object(data(controller().revoke(code, firstId, Map.of())))
                        .get("status"));
        var afterRevoke = assertThrows(PlatformException.class, () -> apps.precheck(code));
        assertEquals(409, afterRevoke.status());
        assertEquals("EVAL_GATE_BLOCKED", afterRevoke.code());

        var second =
                AppService.object(
                        data(
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(600).toString(),
                                                        null))));
        // 直接把窗口推到过去：等价于等待到期，但避免用 sleep 制造不稳定测试。
        db.update(
                "update app.publish_waiver set approved_at=now()-interval '2 hours',"
                        + " expires_at=now()-interval '1 hour' where tenant_id=? and id=?",
                "application-tests",
                second.get("id"));
        var afterExpiry = assertThrows(PlatformException.class, () -> apps.precheck(code));
        assertEquals(409, afterExpiry.status());
        assertEquals("EVAL_GATE_BLOCKED", afterExpiry.code());
    }

    @Test
    void waiverBoundToAnotherCandidateNeverApplies() {
        var other = "b".repeat(64);
        var granted =
                AppService.object(
                        data(
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        other))));
        assertEquals(other, granted.get("candidateSha256"));
        var blocked = assertThrows(PlatformException.class, () -> apps.precheck(code));
        assertEquals(409, blocked.status());
        assertEquals("EVAL_GATE_BLOCKED", blocked.code());
        assertNull(report().get("waiver_id"));
    }

    @Test
    void offModePublishesWithoutEvaluationAndMarksSkipped() {
        apps.configurePublishGate("OFF", true);
        var precheck = apps.precheck(code);
        assertEquals(true, precheck.get("passed"));
        assertEquals("OFF", precheck.get("gateMode"));
        assertTrue(report().get("checks").toString().contains("SKIPPED"));
        var commit =
                apps.commit(
                        code,
                        Map.of(
                                "precheckId", precheck.get("precheckId"),
                                "draftRev", precheck.get("draftRev"),
                                "candidateSha256", precheck.get("candidateSha256"),
                                "idempotencyRequestNo", "req-" + UUID.randomUUID()));
        assertEquals(1, commit.get("versionNo"));
    }

    @Test
    void rollbackSwitchDisablesWaiversAndDowngradesRelaxedModes() {
        apps.configurePublishGate("ADVISORY", false);
        var blocked = assertThrows(PlatformException.class, () -> apps.precheck(code));
        assertEquals(409, blocked.status());
        assertEquals("EVAL_GATE_BLOCKED", blocked.code());
        assertEquals("BLOCKING", report().get("gate_mode"));
        var disabled =
                assertThrows(
                        PlatformException.class,
                        () ->
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        null)));
        assertEquals(403, disabled.status());
        assertEquals("PUBLISH_WAIVER_DISABLED", disabled.code());
    }

    @Test
    void waiverRevokedAfterPrecheckBlocksCommit() {
        var granted =
                AppService.object(
                        data(
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        null))));
        var precheck = apps.precheck(code);
        assertEquals(true, precheck.get("passed"));
        assertEquals(granted.get("id"), precheck.get("waiverId"));
        controller().revoke(code, granted.get("id").toString(), Map.of());
        // 报告窗口15分钟，豁免可能更短：提交时必须重新读豁免状态，不能只信预检结论。
        var blocked =
                assertThrows(
                        PlatformException.class,
                        () ->
                                apps.commit(
                                        code,
                                        Map.of(
                                                "precheckId", precheck.get("precheckId"),
                                                "draftRev", precheck.get("draftRev"),
                                                "candidateSha256",
                                                        precheck.get("candidateSha256"),
                                                "idempotencyRequestNo",
                                                        "req-" + UUID.randomUUID())));
        assertEquals(409, blocked.status());
        assertEquals("EVAL_GATE_BLOCKED", blocked.code());
        assertTrue(apps.versions(code).isEmpty());
    }

    @Test
    void evaluationOutageBlocksEvenInAdvisoryModeAndIgnoresWaivers() {
        apps.configurePublishGate("ADVISORY", true);
        var granted =
                AppService.object(
                        data(
                                controller()
                                        .create(
                                                code,
                                                waiverBody(
                                                        Instant.now().plusSeconds(3600).toString(),
                                                        null))));
        assertNotNull(granted.get("id"));
        evalDown = true;
        try {
            // 依赖故障不是"结论不通过"：放宽模式与豁免都不能把不确定性变成放行。
            var blocked = assertThrows(PlatformException.class, () -> apps.precheck(code));
            assertEquals(409, blocked.status());
            assertEquals("EVAL_GATE_BLOCKED", blocked.code());
            var row = report();
            assertEquals("ADVISORY", row.get("gate_mode"));
            assertNull(row.get("waiver_id"));
            assertEquals(false, row.get("passed"));
            assertTrue(row.get("checks").toString().contains("ERROR"));
            assertTrue(apps.versions(code).isEmpty());
        } finally {
            evalDown = false;
        }
    }

    @Test
    void offModeDoesNotDependOnEvaluationService() {
        apps.configurePublishGate("OFF", true);
        evalDown = true;
        try {
            // 门禁关闭后发布通道不再咨询评测服务：监控侧故障期间仍应能走完预检与提交。
            var precheck = apps.precheck(code);
            assertEquals(true, precheck.get("passed"));
            assertEquals("OFF", precheck.get("gateMode"));
            assertTrue(report().get("checks").toString().contains("SKIPPED"));
            var commit =
                    apps.commit(
                            code,
                            Map.of(
                                    "precheckId", precheck.get("precheckId"),
                                    "draftRev", precheck.get("draftRev"),
                                    "candidateSha256", precheck.get("candidateSha256"),
                                    "idempotencyRequestNo", "req-" + UUID.randomUUID()));
            assertEquals(1, commit.get("versionNo"));
        } finally {
            evalDown = false;
        }
    }
}
