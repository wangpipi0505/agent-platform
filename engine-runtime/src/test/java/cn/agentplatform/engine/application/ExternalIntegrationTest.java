package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.TaskController;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * T2-15 定向测试（台账 §7.3）：外部系统集成联调——SERVICE 票据面仅 AGENT。
 *
 * <p>验收映射（接口稿 §3.6 时序/§6 权限矩阵 SERVICE 列、contracts/task-states.md §4）：同一
 * SERVICE 凭据投影的绑定主体（biz:user）提交 AGENT 任务受理成功并取回自己的详情/事件/结果；
 * SERVICE 票据面提交 mode=PLAN→403 {@code SERVICE_AGENT_ONLY} 并恰好记一条拒绝审计；他人
 * （其他 principal）任务 404 不泄露存在性；白名单外任务端点以 authority 目录行
 * integrationAllowed=false 承接（网关 IntegrationPolicy.allowedPath 与目录行一一对应，全栈
 * 403+审计由 platform-gateway IntegrationSecurityTest 断言——引擎侧本测试如实断言目录行标志）；
 * 无附件任务的 result 端点返回完整形状 {taskId,state,result?,resultSummary?,artifacts?}。
 *
 * <p>外部 caller 判定与生产一致：attributes.bizSystemCode（网关 IntegrationPolicy.clip 唯一
 * 写入点，照 RunStatistics 外部集成判别式）。端点行为经 standalone MockMvc 真实 Controller
 * 调用（先例 TaskEventsSseTest/SessionEventsEndpointTest）。
 *
 * <p>夹具 tenant='engine-test'（共享测试库约定租户，清理照 TasksApiTest）+ h215_% 前缀双向
 * 兜底清理；@BeforeEach+@AfterEach 双向执行，不触碰共享测试库中其他测试的行。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ExternalIntegrationTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final String TENANT = "engine-test";
    /** SERVICE 票据面投影 Actor（网关 IntegrationPolicy.clip 后形状：bizSystemCode+userId 属性）。 */
    static final Actor service =
            new Actor(
                    TENANT,
                    "biz-h215:svc-ops",
                    Set.of("operator"),
                    Map.of("bizSystemCode", Set.of("biz-h215"), "userId", Set.of("svc-ops")));
    /** 同一业务系统的另一绑定用户（IntegrationClaims.principal(biz,user) 派生另一主体）。 */
    static final Actor serviceOtherUser =
            new Actor(
                    TENANT,
                    "biz-h215:svc-other",
                    Set.of("operator"),
                    Map.of("bizSystemCode", Set.of("biz-h215"), "userId", Set.of("svc-other")));
    static final Actor alice = new Actor(TENANT, "alice", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void connect() {
        var source =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(source);
        store = new TraceStore(db, new DataSourceTransactionManager(source));
    }

    /** 双向清理：任务域三表（tenant='engine-test'，照 TasksApiTest）+ h215_%/ti-% 会话兜底。
     *  本测试不造 run 夹具，因此不触碰 run/node_instance——共享库中其他测试的行原样保留。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='engine-test' or public_id like 'h215_%')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test' or public_id like 'h215_%')");
        db.update("delete from engine.task where tenant_id='engine-test' or public_id like 'h215_%'");
        db.update("delete from engine.session_event where session_id like 'ti-%' or session_id like 'h215_%'");
        db.update("delete from engine.chat_session where public_id like 'ti-%' or public_id like 'h215_%'");
    }

    /** 已验签身份注入（照 TaskEventsSseTest 先例）：attributes claim 携带外部集成投影属性。 */
    private void identity(String tenant, String principal, List<String> roles, Map<String, List<String>> attributes) {
        var builder =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180));
        if (attributes != null) builder.claim("attributes", attributes);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    private void serviceIdentity(String principal) {
        identity(
                TENANT,
                principal,
                List.of("operator"),
                Map.of(
                        "bizSystemCode", List.of("biz-h215"),
                        "userId", List.of(principal.substring("biz-h215:".length()))));
    }

    private Tasks tasks() {
        return new Tasks(store);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new TaskController(tasks(), new Plans(store), new TaskReconciliation(store)))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private JsonNode callGet(MockMvc mvc, String url, int expected) throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    private JsonNode callPost(MockMvc mvc, String url, String body, int expected) throws Exception {
        var response =
                mvc.perform(post(url).contentType("application/json").content(body))
                        .andReturn()
                        .getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    private String createBody(String requestId, String mode) {
        return Json.write(
                Map.of("requestId", requestId, "mode", mode, "goal", "h215 外部联调目标", "inputs", Map.of("a", 1)));
    }

    private long taskId(String publicId) {
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, publicId);
    }

    // ------------------------------------------------- 验收 1：同一凭据提交 AGENT 任务并取回自己的结果

    /**
     * SERVICE 票据面（integrationAllowed 面）提交 AGENT 任务受理成功；同一凭据（同 principal）
     * 幂等重放同号同参；详情/事件/结果三读面均返回自己的任务（无附件结果返回完整输出形状）。
     */
    @Test
    void serviceTicketSubmitsAgentTaskAndReadsBackOwnDetailEventsAndResult() throws Exception {
        serviceIdentity("biz-h215:svc-ops");
        var mvc = mvc();
        String requestId = "h215-req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var created =
                callPost(mvc, "/v1/apps/h215-app/tasks", createBody(requestId, "AGENT"), 200)
                        .path("data");
        String publicId = created.path("taskId").asText();
        assertEquals("ACCEPTED", created.path("state").asText());
        assertEquals("biz-h215:svc-ops", created.path("principalId").asText(), "归属=凭据绑定主体");
        assertEquals("AGENT", created.path("mode").asText());
        // 幂等：同号同参重放原任务（principal 维度 UNIQUE(tenant,principal,request_id)）。
        var replay =
                callPost(mvc, "/v1/apps/h215-app/tasks", createBody(requestId, "AGENT"), 200)
                        .path("data");
        assertEquals(publicId, replay.path("taskId").asText(), "SERVICE 面同号同参重放原任务");
        long id = taskId(publicId);
        // 详情可见（自己的任务）。
        var detail = callGet(mvc, "/v1/tasks/" + id, 200).path("data");
        assertEquals(publicId, detail.path("taskId").asText());
        assertEquals("h215-app", detail.path("appCode").asText());
        // 事件增量：受理事件 TASK_ACCEPTED 在列。
        var events = callGet(mvc, "/v1/tasks/" + id + "/events", 200).path("data");
        assertEquals("TASK_ACCEPTED", events.path("events").get(0).path("type").asText());
        // 无附件结果：完整形状 {taskId,state,result?,resultSummary?,artifacts?}（T2-13 口径见 Tasks.result）。
        var result = callGet(mvc, "/v1/tasks/" + id + "/result", 200).path("data");
        assertEquals(publicId, result.path("taskId").asText());
        assertEquals("ACCEPTED", result.path("state").asText());
        assertTrue(result.has("result"), "result 字段在形状内（首版=null，T2-13 接线后承载完整输出）");
        assertTrue(result.path("result").isNull());
        assertEquals("h215 外部联调目标", result.path("resultSummary").asText(), "goal 摘要");
        assertEquals(0, result.path("artifacts").size(), "无附件任务 artifacts 为空列表");
    }

    // ------------------------------------------------- 验收 2：SERVICE 提交 mode=PLAN → 403 + 审计

    /** SERVICE 票据面提交 mode=PLAN：403 SERVICE_AGENT_ONLY，恰好记一条拒绝审计，且不落任务行。 */
    @Test
    void serviceTicketPlanSubmissionIsRejectedWithSingleAuditLine() throws Exception {
        serviceIdentity("biz-h215:svc-ops");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Tasks.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        String requestId = "h215-plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            var body =
                    callPost(mvc(), "/v1/apps/h215-app/tasks", createBody(requestId, "PLAN"), 403);
            assertEquals("SERVICE_AGENT_ONLY", body.path("code").asText());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        var audited =
                appender.list.stream()
                        .filter(line -> line.getFormattedMessage().contains("AUDIT TASK_SERVICE_AGENT_ONLY"))
                        .toList();
        assertEquals(1, audited.size(), "必须恰好记一条拒绝审计: " + appender.list);
        String line = audited.get(0).getFormattedMessage();
        assertTrue(line.contains(TENANT), "审计行携带租户");
        assertTrue(line.contains("biz-h215:svc-ops"), "审计行携带凭据绑定主体");
        assertTrue(line.contains("biz-h215"), "审计行携带业务系统标识");
        assertTrue(line.contains(requestId), "审计行携带受理幂等号");
        assertTrue(line.contains("mode=PLAN"), "审计行携带被拒 mode");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.task where request_id=?", Integer.class, requestId),
                "被拒受理不得落任务行");
    }

    // ------------------------------------------------- 验收 3：他人任务 404（凭据绑定主体口径）

    /**
     * SERVICE 限凭据绑定主体提交的任务（接口稿口径）：其他 principal（门户用户、另一绑定用户、
     * 运维角色）查询→404 TASK_NOT_FOUND（不泄露存在性）；反向（门户用户查 SERVICE 任务）同口径。
     */
    @Test
    void serviceTicketCannotProbeOtherPrincipalsTasks() throws Exception {
        serviceIdentity("biz-h215:svc-ops");
        var created =
                callPost(mvc(), "/v1/apps/h215-app/tasks", createBody("h215-own-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "AGENT"), 200)
                        .path("data");
        long ownId = taskId(created.path("taskId").asText());
        // 门户用户 alice 建任务，SERVICE 主体查询→404。
        var aliceTask = tasks().create("h215-app", Json.tree(Map.of("requestId", "h215-alice-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "mode", "AGENT", "goal", "门户任务")), alice);
        long aliceId = taskId(aliceTask.get("taskId").toString());
        var mvc = mvc();
        serviceIdentity("biz-h215:svc-ops");
        var others =
                assertThrows(
                        PlatformException.class,
                        () -> tasks().detail(aliceId, service),
                        "SERVICE 查他人任务必须 404");
        assertEquals(404, others.status());
        assertEquals("TASK_NOT_FOUND", others.code());
        // 另一绑定用户（同 biz 不同 userId→另一 principal）同口径 404。
        var otherUser =
                assertThrows(PlatformException.class, () -> tasks().detail(ownId, serviceOtherUser));
        assertEquals(404, otherUser.status());
        // HTTP 面：MockMvc 经 Actor.current() 走同一归属核验。
        identity(TENANT, "alice", List.of("EDITOR"), null);
        var forbidden = callGet(mvc, "/v1/tasks/" + ownId, 404);
        assertEquals("TASK_NOT_FOUND", forbidden.path("code").asText(), "门户用户查 SERVICE 任务 404");
        identity(TENANT, "op", List.of("OPERATOR"), null);
        assertEquals("TASK_NOT_FOUND", callGet(mvc, "/v1/tasks/" + ownId, 404).path("code").asText());
    }

    // ------------------------------------------------- 验收 4：白名单外路径——目录行 integrationAllowed=false

    /**
     * 白名单外任务端点（cancel/resume/事件SSE/步骤调用/对账/计划/execute）以 authority 目录行
     * integrationAllowed=false 承接（如实说明：引擎侧断言目录行标志；全栈 403+审计由
     * platform-gateway IntegrationSecurityTest 对 IntegrationPolicy.allowedPath 断言）。
     */
    @Test
    void whitelistExternalTaskEndpointsStayCatalogClosed() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog = Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            // 四端点白名单（integrationAllowed=true）——动作必须指向本端点委派。
            for (String[] whitelisted :
                    new String[][] {
                        {"POST", "/v1/apps/{code}/tasks", "proxy.engine.post.TaskController.create"},
                        {"GET", "/v1/tasks/{taskId}", "proxy.engine.get.TaskController.detail"},
                        {"GET", "/v1/tasks/{taskId}/result", "proxy.engine.get.TaskController.result"},
                        {"GET", "/v1/tasks/{taskId}/events", "proxy.engine.get.TaskController.events"}
                    }) {
                var rows = taskRows(catalog, whitelisted[0], whitelisted[1]);
                assertEquals(1, rows.size(), whitelisted[1] + " 必须恰登记一行: " + rows);
                var row = rows.get(0);
                assertTrue(row.path("integrationAllowed").asBoolean(false), whitelisted[1] + " 对 SERVICE 开放");
                assertEquals("engine", row.path("target").asText());
                assertEquals("gateway", row.path("caller").asText());
                assertEquals("delegated", row.path("mode").asText());
                assertEquals(whitelisted[2], row.path("action").asText(), "委派动作必须指向本端点");
            }
            // 拒签名录：其余任务域端点 integrationAllowed=false（逐一核对）。
            for (String[] rejected :
                    new String[][] {
                        {"GET", "/v1/tasks/{taskId}/events/stream"},
                        {"POST", "/v1/tasks/{taskId}/cancel"},
                        {"POST", "/v1/tasks/{taskId}/resume"},
                        {"GET", "/v1/tasks/{taskId}/steps/{stepId}/calls"},
                        {"POST", "/v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile"},
                        {"POST", "/v1/tasks/{taskId}/plans"},
                        {"GET", "/v1/tasks/{taskId}/plans/{planId}/revisions/{revision}"},
                        {"POST", "/v1/tasks/{taskId}/plans/{planId}/revisions/{revision}/confirm"},
                        {"POST", "/v1/tasks/{taskId}/execute"}
                    }) {
                var rows = taskRows(catalog, rejected[0], rejected[1]);
                assertEquals(1, rows.size(), rejected[1] + " 必须恰登记一行: " + rows);
                assertFalse(rows.get(0).path("integrationAllowed").asBoolean(true), rejected[1] + " 不入外部白名单");
            }
        }
    }

    private List<JsonNode> taskRows(JsonNode catalog, String method, String template) {
        var matches = new ArrayList<JsonNode>();
        for (var line : catalog) {
            if (method.equals(line.path("method").asText())
                    && template.equals(line.path("template").asText())
                    && "engine".equals(line.path("target").asText())) matches.add(line);
        }
        return matches;
    }

    // ------------------------------------------------- 验收 5：result 端点归属与他人 404

    /** result 端点与 detail 同一 ownedTask 口径：他人（其他 principal）404，不泄露存在性。 */
    @Test
    void resultEndpointHidesOtherPrincipalsTasksBehind404() {
        var own =
                tasks()
                        .create(
                                "h215-app",
                                Json.tree(
                                        Map.of(
                                                "requestId",
                                                "h215-r-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                                                "mode",
                                                "AGENT",
                                                "goal",
                                                "归属核验")),
                                service);
        long ownId = taskId(own.get("taskId").toString());
        assertEquals("归属核验", tasks().result(ownId, service).get("resultSummary"));
        var hidden = assertThrows(PlatformException.class, () -> tasks().result(ownId, alice));
        assertEquals(404, hidden.status());
        assertEquals("TASK_NOT_FOUND", hidden.code());
    }
}
