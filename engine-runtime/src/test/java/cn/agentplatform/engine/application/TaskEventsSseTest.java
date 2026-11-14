package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * T2-11 定向测试（台账 §7.3）：任务事件 TASK_RESUMED 角色裁剪/断线补发不重不漏/SSE 帧与心跳/权限。
 *
 * <p>验收映射（接口稿 §5.2/§7、contracts/task-states.md §4）：TASK_RESUMED 与详情和 resume 共用
 * 同一角色投影——USER 事件 data 仅 recoverySource/reasonCode/reasonSummary（无 checkpoint/
 * reusedSteps/blockedReasons），授权 OPERATOR/ADMIN 全量；事件流 afterSeq 排他游标
 * （nextAfterSeq=最后扫描 seq 不 +1），分页拼接按 seq 集合断言补发不重不漏；SSE 端点
 * 200 text/event-stream+帧 {@code id=<seq>/event=<type>/data=<投影 json>}+Last-Event-ID 排他
 * 续读+空闲 :ping 心跳（照 ChatSessions SSE 测试先例反射改短心跳）+断开收口（断开不取消任务）；
 * 非归属主体 404。网关登记以目录行断言验证（照 H1-9 方式；Routes 委派正则由 platform-gateway
 * RoutesTest 断言）。端点行为经 standalone MockMvc 真实 Controller 调用（先例
 * SessionEventsEndpointTest）。
 *
 * <p>夹具一律 h211_% 前缀；@BeforeEach+@AfterEach 双向清理，不触碰共享测试库中其他测试的行。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TaskEventsSseTest {

    static JdbcTemplate db;
    static TraceStore store;

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

    /** 双向清理：仅 h211_% 夹具，不触碰共享测试库中其他测试的行。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.task_event where task_id in (select id from engine.task where public_id like 'h211_%')");
        db.update("delete from engine.task_step where task_id in (select id from engine.task where public_id like 'h211_%')");
        db.update("delete from engine.task where public_id like 'h211_%'");
    }

    private void identity(String tenant, String principal, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Tasks service() {
        return new Tasks(store);
    }

    private MockMvc mvc(Tasks service) {
        return MockMvcBuilders.standaloneSetup(
                        new TaskController(service, new Plans(store), new TaskReconciliation(store)))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    /** 任务夹具（h211_% 前缀）：state 直插，事件行由 taskEvent 显式给 seq。 */
    private long task(String tenant, String principal, String state) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        db.update(
                "insert into engine.task(public_id,tenant_id,principal_id,app_code,config_release_id,"
                    + "config_digest,mode,purpose,goal,input_ref,input_digest,state,request_id,request_digest)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "h211_" + suffix,
                tenant,
                principal,
                "h211-app",
                "unpinned-h211",
                Digest.sha256("h211-config:" + suffix),
                "AGENT",
                "EXECUTE",
                "T2-11 夹具任务",
                "h211-input/" + suffix,
                Digest.sha256("h211-input:" + suffix),
                state,
                "h211-req-" + suffix,
                Digest.sha256("h211-request:" + suffix));
        return db.queryForObject(
                "select id from engine.task where public_id=?", Long.class, "h211_" + suffix);
    }

    private void taskEvent(long taskId, int seq, String type, String payloadJson) {
        db.update(
                "insert into engine.task_event(task_id,seq,type,payload,state_version) values(?,?,?,?::jsonb,1)",
                taskId,
                seq,
                type,
                payloadJson);
    }

    /** TASK_RESUMED 全量负载夹具（contracts/task-states.md §4 负载形状）。 */
    static final String RESUMED_PAYLOAD =
            "{\"recoverySource\":\"AUTO\",\"reasonCode\":\"LEASE_EXPIRED\","
                + "\"reasonSummary\":\"执行租约到期后已接续\","
                + "\"checkpoint\":{\"eventWatermark\":58,\"runRefs\":[\"run-551\"]},"
                + "\"reusedSteps\":[{\"stepId\":\"detect_a\",\"resultDigest\":\"ab41\"}],"
                + "\"blockedReasons\":[]}";

    /**
     * SSE 帧形状断言专用 ASCII 负载：MockMvc 的 SseEmitter 字符串数据经 StringHttpMessageConverter
     * 落帧（H1-9 先例同口径），非 ASCII 值在该路径被响应默认编码替换为 '?'——帧结构/投影裁剪断言
     * 用 ASCII 负载做精确比对；中文负载的投影口径由事件增量/详情 JSON 路径断言（taskResumedIs...）。
     */
    static final String RESUMED_PAYLOAD_ASCII =
            "{\"recoverySource\":\"AUTO\",\"reasonCode\":\"LEASE_EXPIRED\","
                + "\"reasonSummary\":\"lease expired and recovered\","
                + "\"checkpoint\":{\"eventWatermark\":58,\"runRefs\":[\"run-551\"]},"
                + "\"reusedSteps\":[{\"stepId\":\"detect_a\",\"resultDigest\":\"ab41\"}],"
                + "\"blockedReasons\":[]}";

    private JsonNode callGet(MockMvc mvc, String url, int expected) throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    private JsonNode callPost(MockMvc mvc, String url) throws Exception {
        var response = mvc.perform(post(url)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    // ------------------------------------------------- 交付物 1：TASK_RESUMED 角色裁剪

    /**
     * TASK_RESUMED 与详情和 resume 共用角色裁剪（接口稿 §5.2）：USER 的事件增量 data 仅
     * recoverySource/reasonCode/reasonSummary，与详情 recovery 同形状（同一投影口径）；
     * 授权 OPERATOR/ADMIN 全量（checkpoint/reusedSteps/blockedReasons 原样）。其余事件类型原样。
     */
    @Test
    void taskResumedIsPrunedForUserAndFullForPrivilegedConsistentWithDetail() throws Exception {
        String tenant = "h211-t-" + UUID.randomUUID();
        identity(tenant, "alice", List.of("USER"));
        long taskId = task(tenant, "alice", "RUNNING");
        taskEvent(taskId, 1, "STEP_STARTED", "{\"stepId\":\"detect_a\"}");
        taskEvent(taskId, 2, "TASK_RESUMED", RESUMED_PAYLOAD);
        taskEvent(taskId, 3, "STEP_FINISHED", "{\"stepId\":\"detect_a\"}");
        var mvc = mvc(service());

        // USER：事件增量的 TASK_RESUMED 三字段，无检查点/复用清单/阻塞原因。
        var userEvents = callGet(mvc, "/v1/tasks/" + taskId + "/events", 200).path("data");
        assertEquals(3, userEvents.path("events").size(), "USER 事件类型不裁剪——三种类型全出");
        var userResumed = userEvents.path("events").get(1);
        assertEquals("TASK_RESUMED", userResumed.path("type").asText());
        assertEquals("AUTO", userResumed.path("data").path("recoverySource").asText());
        assertEquals("LEASE_EXPIRED", userResumed.path("data").path("reasonCode").asText());
        assertTrue(userResumed.path("data").has("reasonSummary"));
        assertFalse(userResumed.path("data").has("checkpoint"), "USER 增量无检查点");
        assertFalse(userResumed.path("data").has("reusedSteps"), "USER 增量无复用清单");
        assertFalse(userResumed.path("data").has("blockedReasons"), "USER 增量无阻塞原因");
        // 其余事件类型原样（不裁剪）。
        assertEquals(
                "detect_a", userEvents.path("events").get(0).path("data").path("stepId").asText());
        // 与详情 recovery 同一口径（三字段同形状）。
        var userDetail = callGet(mvc, "/v1/tasks/" + taskId, 200).path("data");
        assertEquals(
                userEvents.path("events").get(1).path("data").toString(),
                userDetail.path("recovery").toString(),
                "事件 data 与详情 recovery 共用同一角色投影");

        // OPERATOR（同归属主体，工作区授权目录随工作区授权模型统一——T2-12 同口径）：全量。
        identity(tenant, "alice", List.of("OPERATOR"));
        var opEvents = callGet(mvc, "/v1/tasks/" + taskId + "/events", 200).path("data");
        var opResumed = opEvents.path("events").get(1);
        assertEquals(
                "run-551",
                opResumed.path("data").path("checkpoint").path("runRefs").get(0).asText(),
                "OPERATOR 事件全量含 checkpoint");
        assertTrue(opResumed.path("data").has("reusedSteps"));
        assertTrue(opResumed.path("data").has("blockedReasons"));
        var opDetail = callGet(mvc, "/v1/tasks/" + taskId, 200).path("data");
        assertEquals(
                opResumed.path("data").toString(),
                opDetail.path("recovery").toString(),
                "OPERATOR 事件 data 与详情 recovery 同为全量");
        // ADMIN 同权。
        identity(tenant, "alice", List.of("ADMIN"));
        var adminEvents = callGet(mvc, "/v1/tasks/" + taskId + "/events", 200).path("data");
        assertTrue(adminEvents.path("events").get(1).path("data").has("checkpoint"), "ADMIN 事件全量");

        // resume 响应复用详情投影（USER 身份）：新 TASK_RESUMED 三字段、无检查点（与详情同口径）。
        identity(tenant, "alice", List.of("USER"));
        var resume = callPost(mvc, "/v1/tasks/" + taskId + "/resume").path("data");
        assertEquals("MANUAL", resume.path("recovery").path("recoverySource").asText());
        assertEquals("MANUAL_REQUEST", resume.path("recovery").path("reasonCode").asText());
        assertFalse(resume.path("recovery").has("checkpoint"), "resume USER 无检查点");
        assertFalse(resume.path("recovery").has("reusedSteps"), "resume USER 无复用清单");
    }

    // ------------------------------------------------- 交付物 2：断线补发不重不漏

    /** afterSeq 排他游标断线补发：分页中断→逐页续读，拼接 seq 集合恰为全集（不重不漏）；
     *  nextAfterSeq=最后扫描 seq 不 +1，末页 truncated=false；从头整页重放同集合（seq 幂等去重）。 */
    @Test
    void cursorReplayAcrossReconnectIsExactlyOnce() throws Exception {
        String tenant = "h211-t-" + UUID.randomUUID();
        identity(tenant, "alice", List.of("USER"));
        long taskId = task(tenant, "alice", "RUNNING");
        for (int seq = 1; seq <= 5; seq++)
            taskEvent(taskId, seq, "STEP_STARTED", "{\"seq\":" + seq + "}");
        var mvc = mvc(service());
        String url = "/v1/tasks/" + taskId + "/events";

        // 首连取 2 条后"断线"：客户端已持有 seq 1,2。
        var page1 = callGet(mvc, url + "?afterSeq=0&limit=2", 200).path("data");
        assertEquals(2, page1.path("events").size());
        assertEquals(2, page1.path("nextAfterSeq").asLong(), "nextAfterSeq=最后扫描 seq 不 +1");
        assertTrue(page1.path("truncated").asBoolean());
        // 重连补发（Last-Event-ID 语义同 afterSeq 排他游标）。
        var page2 = callGet(mvc, url + "?afterSeq=2&limit=2", 200).path("data");
        var page3 = callGet(mvc, url + "?afterSeq=4&limit=2", 200).path("data");
        assertFalse(page3.path("truncated").asBoolean(), "末页 truncated=false");
        assertEquals(5, page3.path("nextAfterSeq").asLong());
        // 拼接不重不漏：1..5 每条恰出现一次。
        var seen = new ArrayList<Long>();
        for (var page : new JsonNode[] {page1, page2, page3})
            for (var item : page.path("events")) seen.add(item.path("seq").asLong());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seen, "断线重连补发不重不漏");
        // 越界续读（客户端游标已到末尾）不再出新事件且游标原地不动。
        var page4 = callGet(mvc, url + "?afterSeq=5", 200).path("data");
        assertEquals(0, page4.path("events").size());
        assertEquals(5, page4.path("nextAfterSeq").asLong(), "无可扫描事件时游标不 +1");
        // 从头整页重放：同一 seq 集合（重放按 seq 幂等去重）。
        var all = callGet(mvc, url, 200).path("data");
        var replay = new ArrayList<Long>();
        for (var item : all.path("events")) replay.add(item.path("seq").asLong());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), replay);
    }

    // ------------------------------------------------- 交付物 3：SSE 帧/心跳/续读

    private static void setHeartbeat(Tasks service, long millis) throws Exception {
        var field = Tasks.class.getDeclaredField("taskHeartbeatMillis");
        field.setAccessible(true);
        field.setLong(service, millis);
    }

    /** 轮询等待 SSE 内容出现指定片段（泵线程异步写帧）。 */
    private static String awaitContent(MvcResult result, String mustContain) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (content.contains(mustContain)) return content;
            Thread.sleep(20);
        }
        fail("SSE 内容未出现 `" + mustContain + "`: " + content);
        return content;
    }

    /**
     * SSE 生命周期：首连从头补发，帧形状 id=seq/event=type/data=投影后 json；USER 的 TASK_RESUMED
     * 帧按角色投影裁剪；Last-Event-ID=1 排他续读不重发；空闲反射改短间隔发 :ping；closeTaskStreams
     * 收口后响应 200+text/event-stream（断开不取消任务——泵内无任何任务状态写路径）。
     */
    @Test
    void streamFramesProjectedPayloadWithPingAndExclusiveResume() throws Exception {
        String tenant = "h211-t-" + UUID.randomUUID();
        identity(tenant, "alice", List.of("USER"));
        long taskId = task(tenant, "alice", "RUNNING");
        taskEvent(taskId, 1, "STEP_STARTED", "{\"stepId\":\"detect_a\"}");
        taskEvent(taskId, 2, "TASK_RESUMED", RESUMED_PAYLOAD_ASCII);
        taskEvent(taskId, 3, "STEP_FINISHED", "{\"stepId\":\"detect_a\"}");
        var service = service();
        setHeartbeat(service, 80);
        var mvc = mvc(service);
        String url = "/v1/tasks/" + taskId + "/events/stream";
        // 首连：无 Last-Event-ID → 从头补发。
        var first = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        String content = awaitContent(first, "id:3");
        // 首帧落盘后响应媒体类型应为 SSE（Content-Type 随首帧写出）。
        assertEquals(
                "text/event-stream",
                Objects.requireNonNull(first.getResponse().getContentType(), "响应必须有媒体类型")
                        .split(";")[0],
                "SSE 媒体类型");
        // 帧形状：id=seq/event=type/data=投影后 json；TASK_RESUMED 帧为 USER 投影（无 checkpoint）。
        assertTrue(content.contains("id:1\nevent:STEP_STARTED\ndata:{\"stepId\":\"detect_a\"}\n\n"), content);
        assertTrue(
                content.contains(
                        "id:2\nevent:TASK_RESUMED\ndata:{\"recoverySource\":\"AUTO\",\"reasonCode\":\"LEASE_EXPIRED\","
                            + "\"reasonSummary\":\"lease expired and recovered\"}\n\n"),
                "TASK_RESUMED 帧按 USER 投影裁剪: " + content);
        assertFalse(content.contains("checkpoint"), "USER SSE 帧无检查点");
        assertFalse(content.contains("reusedSteps"), "USER SSE 帧无复用清单");
        assertTrue(content.contains("id:3\nevent:STEP_FINISHED\n"), content);
        // 心跳：空闲后出现 :ping 注释帧。
        awaitContent(first, ":ping");
        // 排他续读：Last-Event-ID=1 → 只补发 seq>1。
        var resume = mvc.perform(get(url).header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted()).andReturn();
        String resumed = awaitContent(resume, "id:2");
        assertFalse(resumed.contains("id:1"), "排他：Last-Event-ID 本身不重发");
        assertTrue(resumed.contains("id:2\nevent:TASK_RESUMED\n"), resumed);
        // 非法 Last-Event-ID 400 EVENT_CURSOR。
        var bad = mvc.perform(get(url).header("Last-Event-ID", "soon")).andReturn().getResponse();
        assertEquals(400, bad.getStatus(), bad.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(
                bad.getContentAsString(StandardCharsets.UTF_8).contains("EVENT_CURSOR"),
                bad.getContentAsString(StandardCharsets.UTF_8));
        // 收口：断开即停（不取消任务），响应 200+text/event-stream。
        assertEquals(2, service.closeTaskStreams(taskId), "closeTaskStreams 一次收口本任务全部连接");
        assertEquals(0, service.closeTaskStreams(taskId), "重复收口无残留");
        assertEquals(200, mvc.perform(asyncDispatch(first)).andReturn().getResponse().getStatus());
        var resumedResponse = mvc.perform(asyncDispatch(resume)).andReturn().getResponse();
        assertEquals(200, resumedResponse.getStatus());
        assertEquals(
                "text/event-stream",
                Objects.requireNonNull(resumedResponse.getContentType(), "续读响应必须有媒体类型")
                        .split(";")[0]);
        assertEquals("RUNNING", db.queryForObject("select state from engine.task where id=?", String.class, taskId),
                "断开不取消任务：泵收口不触碰任务状态");
    }

    // ------------------------------------------------- 交付物 4：权限

    /** 非归属主体（同租户他人）直链事件增量与 SSE：404（照 T2-2 ownedTask 口径——不泄露存在性）。 */
    @Test
    void nonOwnerIsRejectedOnEventsAndStream() throws Exception {
        String tenant = "h211-t-" + UUID.randomUUID();
        identity(tenant, "alice", List.of("USER"));
        long taskId = task(tenant, "alice", "RUNNING");
        taskEvent(taskId, 1, "STEP_STARTED", "{\"stepId\":\"detect_a\"}");
        var mvc = mvc(service());
        // bob 同租户非归属：404 TASK_NOT_FOUND（不泄露存在性）。
        identity(tenant, "bob", List.of("USER"));
        var events = mvc.perform(get("/v1/tasks/" + taskId + "/events")).andReturn().getResponse();
        assertEquals(404, events.getStatus(), events.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(
                events.getContentAsString(StandardCharsets.UTF_8).contains("TASK_NOT_FOUND"),
                events.getContentAsString(StandardCharsets.UTF_8));
        var stream = mvc.perform(get("/v1/tasks/" + taskId + "/events/stream")).andReturn().getResponse();
        assertEquals(404, stream.getStatus(), stream.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(
                stream.getContentAsString(StandardCharsets.UTF_8).contains("TASK_NOT_FOUND"),
                stream.getContentAsString(StandardCharsets.UTF_8));
        // 异租户同样 404。
        identity("h211-other-" + UUID.randomUUID(), "carol", List.of("USER"));
        assertEquals(
                404,
                mvc.perform(get("/v1/tasks/" + taskId + "/events"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    // ------------------------------------------------- 交付物 5：网关登记

    /**
     * 网关登记（T2-11 交付物 5，照 H1-9 目录行方式）：events/stream 路径的 authority 目录行必须
     * 存在——RouteCoverageVerifier 在引擎上下文启动时强制每个 handler 有目录行，缺行启动即失败。
     * integrationAllowed=false 与网关 IntegrationPolicy 不放行 SSE 一致（接口稿 §7 鉴权与投影行）。
     */
    @Test
    void gatewayCatalogRegistersTaskEventStreamRoute() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog =
                    Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            var matches = new ArrayList<String>();
            for (var line : catalog) {
                if ("GET".equals(line.path("method").asText())
                        && "/v1/tasks/{taskId}/events/stream".equals(line.path("template").asText())
                        && "engine".equals(line.path("target").asText())
                        && "gateway".equals(line.path("caller").asText())
                        && "delegated".equals(line.path("mode").asText())) {
                    matches.add(line.toString());
                    assertEquals(
                            "proxy.engine.get.TaskController.stream",
                            line.path("action").asText(),
                            "委派动作必须指向本端点");
                    assertFalse(line.path("integrationAllowed").asBoolean(false), "集成票据面不放行 SSE");
                }
            }
            assertEquals(1, matches.size(), "GET /v1/tasks/{taskId}/events/stream 必须恰登记一行: " + matches);
        }
    }
}
