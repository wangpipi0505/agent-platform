package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.ChatSessionController;
import cn.agentplatform.engine.application.ChatSessions;

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
 * H1-9（台账 §7.2）：会话只读快照/事件增量/控制/SSE 端点。
 *
 * <p>验收映射（接口稿 §5.1/§7、contracts/session-events.md §3）：快照 eventWatermark+messages
 * 投影（本卡全量投影，角色白名单留 F3-6）；事件增量 afterSeq 排他游标——nextAfterSeq=最后扫描
 * seq 不 +1、整页全过滤（普通视图排除诊断类）游标照常推进且下一页首事件可见、末页
 * truncated=false；surfaceOnly=false 仅 OPERATOR/ADMIN——无权 403 并记一条拒绝审计（logback
 * 捕获）；诊断视图返回原始事件；control SET_PLAN_MODE 写 plan/mode 事件、STEER 写 user/message
 * 且活动轮次 pending=true、CANCEL_TURN 置 run 取消+turn/end{aborted} 且无活动轮次 409
 * SESSION_TURN_ACTIVE、COMPACT 占位；SSE 端点 200 + 首帧补发 + Last-Event-ID 排他续读 +
 * :ping 心跳（心跳间隔测试内反射改短）。网关登记以目录行断言验证（三条路径，照 S2-1 方式；
 * Routes.java 委派正则由 platform-gateway RoutesTest 断言）。端点行为经 standalone MockMvc
 * 真实 Controller 调用（先例 SessionListEndpointTest）。
 *
 * <p>夹具一律 h9_% 前缀；@BeforeEach+@AfterEach 双向清理，session_event 残留按
 * ChatTurnEventLinkageTest 的触发器禁用方式清理。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SessionEventsEndpointTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

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

    /** 双向清理：仅 h9_% 夹具，不触碰共享测试库中其他测试的行。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h9_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 'h9_%'");
        db.update("delete from engine.chat_session where public_id like 'h9_%'");
        db.update("delete from engine.run where public_id like 'h9_%'");
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

    private ChatSessions service() {
        return new ChatSessions(store, null, null);
    }

    private MockMvc mvc(ChatSessions service) {
        return MockMvcBuilders.standaloneSetup(new ChatSessionController(service))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private JsonNode callGet(MockMvc mvc, String url, int expected) throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    private JsonNode callPost(MockMvc mvc, String url, String bodyJson, int expected) throws Exception {
        var response =
                mvc.perform(
                                post(url)
                                        .contentType("application/json")
                                        .content(bodyJson))
                        .andReturn()
                        .getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    private String session(String tenant, String principal, String code) {
        String id = "h9_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,?,?,?,'{}'::jsonb)",
                id,
                tenant,
                principal,
                code);
        return id;
    }

    /** 直接落事件行（append-only 触发器只禁 UPDATE/DELETE，INSERT 合法）；seq 由夹具显式给定。 */
    private void event(String sessionId, int seq, String type, String dataJson) {
        db.update(
                "insert into engine.session_event(session_id,seq,type,data) values(?,?,?,?::jsonb)",
                sessionId,
                seq,
                type,
                dataJson);
    }

    private void turn(String sessionId, int turnNo, String runPublicId) {
        db.update(
                "insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id)"
                        + " values(?,?,?,?::jsonb,?)",
                sessionId,
                turnNo,
                "h9-r-" + sessionId + "-" + turnNo,
                "{}",
                runPublicId);
    }

    /** 活跃 run 夹具（RUNNING、ended_at 为空）；CHAT 触发器要求 app_version_id 非空。 */
    private String activeRun(String tenant, String principal) {
        String id = "h9_run_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,"
                        + "principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,"
                        + "inputs,request_id)"
                        + " values(?,'h9-app',1,'CHAT','RUNNING',?,?,'h9-in','{}'::jsonb,'{}'::jsonb,"
                        + "'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,?)",
                id,
                principal,
                tenant,
                "h9-req-" + id);
        return id;
    }

    // ---------------------------------------------------------------- 快照（交付物 1）

    /** 快照 eventWatermark=最大已提交 seq；messages 为 deriveMessages 全量投影（本卡如实说明）。 */
    @Test
    void snapshotCarriesWatermarkAndFullMessageProjection() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        event(sid, 1, "turn/start", "{\"turn\":1}");
        event(sid, 2, "user/message", "{\"content\":{\"message\":\"你好\"}}");
        event(sid, 3, "assistant/message", "{\"stream\":\"答\"}");
        event(sid, 4, "tool/call", "{\"callId\":\"c1\",\"name\":\"search\"}");
        // 空会话水位为 0。
        String empty = session(tenant, "alice", code);
        assertEquals(
                0,
                callGet(mvc(service()), "/v1/apps/" + code + "/sessions/" + empty, 200)
                        .path("data").path("eventWatermark").asLong());
        var data = callGet(mvc(service()), "/v1/apps/" + code + "/sessions/" + sid, 200).path("data");
        assertEquals(4, data.path("eventWatermark").asLong(), "水位=最大已提交 seq");
        var messages = data.path("messages");
        assertEquals(2, messages.size(), "只投影消息类事件（user/assistant），turn/start 与 tool/call 不入");
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals(2, messages.get(0).path("seq").asLong(), "消息携带原始 sourceSeq 衔接事件流");
        assertEquals("你好", messages.get(0).path("content").path("content").path("message").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("答", messages.get(1).path("content").path("stream").asText());
        // 既有字段保持：turns/nextTurn/versionNo 等不因快照增强而缺失。
        assertTrue(data.has("sessionId") && data.has("turns") && data.has("nextTurn"));
        assertTrue(data.has("maxTurns") && data.has("appCode"));
    }

    // ---------------------------------------------------------------- 事件增量（交付物 2）

    /** 游标排他：nextAfterSeq=本页最后扫描 seq（不 +1）；末页与越界页 truncated=false。 */
    @Test
    void eventsCursorIsExclusiveAndNextAfterSeqIsLastScannedSeq() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        event(sid, 1, "turn/start", "{\"turn\":1}");
        event(sid, 2, "user/message", "{\"content\":\"第一条\"}");
        event(sid, 3, "assistant/message", "{\"stream\":\"答\"}");
        event(sid, 4, "turn/end", "{\"turn\":1,\"reason\":\"completed\"}");
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/events";
        var page1 = callGet(mvc, url + "?afterSeq=0&limit=2", 200).path("data");
        assertEquals(2, page1.path("events").size());
        assertEquals(2, page1.path("events").get(1).path("seq").asLong());
        assertEquals(2, page1.path("nextAfterSeq").asLong(), "nextAfterSeq=最后扫描 seq，不得 +1");
        assertTrue(page1.path("truncated").asBoolean(), "水位之外还有未扫描事件");
        var page2 = callGet(mvc, url + "?afterSeq=2&limit=2", 200).path("data");
        assertEquals(2, page2.path("events").size());
        assertEquals(4, page2.path("nextAfterSeq").asLong());
        assertFalse(page2.path("truncated").asBoolean(), "末页 truncated=false");
        var page3 = callGet(mvc, url + "?afterSeq=4", 200).path("data");
        assertEquals(0, page3.path("events").size(), "越界页无事件");
        assertEquals(4, page3.path("nextAfterSeq").asLong(), "无可扫描事件时游标原地不动（不 +1）");
        assertFalse(page3.path("truncated").asBoolean());
        // 事件对象形状（接口稿 §5.1 响应 data）。
        var first = page1.path("events").get(0);
        assertTrue(first.has("seq") && first.has("type") && first.has("occurredAt") && first.has("data"));
    }

    /** 整页全被普通视图过滤：events 为空但游标推进到本页最后扫描 seq；下一页首事件可见不吞。 */
    @Test
    void cursorAdvancesWhenEntirePageIsSurfaceFiltered() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        event(sid, 1, "user/message", "{\"content\":\"可见\"}");
        event(sid, 2, "system/message", "{\"content\":\"诊断\"}");
        event(sid, 3, "request/header", "{\"config\":{},\"tools\":[]}");
        event(sid, 4, "user/message", "{\"content\":\"第二条\"}");
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/events";
        // 普通视图（surfaceOnly 缺省=true）扫描 2、3：两个都是诊断类 → 整页全过滤。
        var page = callGet(mvc, url + "?afterSeq=1&limit=2", 200).path("data");
        assertEquals(0, page.path("events").size(), "整页被过滤 events 为空");
        assertEquals(3, page.path("nextAfterSeq").asLong(), "游标推进到本页最后扫描 seq（含被过滤事件）");
        assertTrue(page.path("truncated").asBoolean(), "整页全过滤 truncated 照常");
        // 客户端以 nextAfterSeq=3 续传：事件 4 可见——过滤游标不吞下一页首事件。
        var next = callGet(mvc, url + "?afterSeq=3", 200).path("data");
        assertEquals(1, next.path("events").size());
        assertEquals(4, next.path("events").get(0).path("seq").asLong());
        assertEquals(4, next.path("nextAfterSeq").asLong());
        assertFalse(next.path("truncated").asBoolean());
        // 从头完整翻页不重不漏：可见事件恰为 1 与 4。
        var all = callGet(mvc, url, 200).path("data");
        assertEquals(2, all.path("events").size(), "普通视图只出 USER 可读类型");
        assertEquals(1, all.path("events").get(0).path("seq").asLong());
        assertEquals(4, all.path("events").get(1).path("seq").asLong());
        assertEquals(4, all.path("nextAfterSeq").asLong());
    }

    /** 非法入参 400：afterSeq 非数字、limit 越界、surfaceOnly 非布尔字面量。 */
    @Test
    void eventsRejectsInvalidCursorLimitOrSurface() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/events";
        assertEquals(
                "SESSION_EVENTS_CURSOR_INVALID",
                callGet(mvc, url + "?afterSeq=abc", 400).path("code").asText());
        assertEquals(
                "SESSION_EVENTS_CURSOR_INVALID",
                callGet(mvc, url + "?afterSeq=-1", 400).path("code").asText());
        assertEquals(
                "SESSION_EVENTS_LIMIT_INVALID",
                callGet(mvc, url + "?limit=0", 400).path("code").asText());
        assertEquals(
                "SESSION_EVENTS_LIMIT_INVALID",
                callGet(mvc, url + "?limit=1001", 400).path("code").asText());
        assertEquals(
                "SESSION_EVENTS_LIMIT_INVALID",
                callGet(mvc, url + "?limit=abc", 400).path("code").asText());
        assertEquals(
                "SESSION_EVENTS_SURFACE_INVALID",
                callGet(mvc, url + "?surfaceOnly=maybe", 400).path("code").asText());
        // 合法边界：limit=1000 放行。
        assertEquals(200, mvc.perform(get(url + "?limit=1000")).andReturn().getResponse().getStatus());
    }

    /** surfaceOnly=false 仅 OPERATOR/ADMIN：USER 无权 403 并恰好记一条拒绝审计。 */
    @Test
    void diagnosticViewForbiddenForUserRecordsSingleAuditLine() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        event(sid, 1, "system/message", "{\"content\":\"诊断\"}");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatSessions.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        appender.start();
        logger.addAppender(appender);
        try {
            var body =
                    callGet(mvc(service()), "/v1/apps/" + code + "/sessions/" + sid + "/events?surfaceOnly=false", 403);
            assertEquals("SESSION_DIAGNOSTIC_VIEW_FORBIDDEN", body.path("code").asText());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        var audited =
                appender.list.stream()
                        .filter(line -> line.getFormattedMessage().contains("AUDIT SESSION_DIAGNOSTIC_VIEW_FORBIDDEN"))
                        .toList();
        assertEquals(1, audited.size(), "必须恰好记一条拒绝审计: " + appender.list);
        assertTrue(audited.get(0).getFormattedMessage().contains(tenant), "审计行携带租户");
        assertTrue(audited.get(0).getFormattedMessage().contains(sid), "审计行携带会话号");
    }

    /** 诊断视图（有权 OPERATOR）：按原始 seq 返回全部原始事件（含诊断类），普通视图不受影响。 */
    @Test
    void diagnosticViewReturnsRawEventsForOperator() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "op-alice", List.of("OPERATOR"));
        String sid = session(tenant, "op-alice", code);
        event(sid, 1, "turn/start", "{\"turn\":1}");
        event(sid, 2, "system/message", "{\"content\":\"诊断\"}");
        event(sid, 3, "request/header", "{\"config\":{},\"tools\":[]}");
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/events";
        var diagnostic = callGet(mvc, url + "?surfaceOnly=false", 200).path("data");
        assertEquals(3, diagnostic.path("events").size(), "诊断视图返回原始事件全集");
        assertEquals("system/message", diagnostic.path("events").get(1).path("type").asText());
        assertEquals("request/header", diagnostic.path("events").get(2).path("type").asText());
        assertEquals(3, diagnostic.path("nextAfterSeq").asLong());
        // ADMIN 同权。
        identity(tenant, "op-alice", List.of("ADMIN"));
        assertEquals(
                3, callGet(mvc, url + "?surfaceOnly=false", 200).path("data").path("events").size());
    }

    // ---------------------------------------------------------------- 控制（交付物 3）

    /** control 入参门：requestId 必填、action 必填且在目录内、payload 形状按动作校验。 */
    @Test
    void controlRejectsMissingRequestIdAndUnknownActionOrPayload() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/control";
        assertEquals(
                "SESSION_CONTROL_REQUEST_INVALID",
                callPost(mvc, url, "{\"action\":\"SET_PLAN_MODE\",\"payload\":{\"active\":true}}", 400)
                        .path("code").asText());
        assertEquals(
                "SESSION_CONTROL_REQUEST_INVALID",
                callPost(mvc, url, "{\"requestId\":\"\",\"action\":\"STEER\",\"payload\":{\"message\":\"m\"}}", 400)
                        .path("code").asText());
        assertEquals(
                "SESSION_CONTROL_ACTION_INVALID",
                callPost(mvc, url, "{\"requestId\":\"rq1\",\"action\":\"NEW_SUBAGENT\"}", 400)
                        .path("code").asText());
        assertEquals(
                "SESSION_CONTROL_ACTION_INVALID",
                callPost(mvc, url, "{\"requestId\":\"rq1\"}", 400).path("code").asText());
        assertEquals(
                "SESSION_CONTROL_PAYLOAD_INVALID",
                callPost(mvc, url, "{\"requestId\":\"rq1\",\"action\":\"STEER\",\"payload\":{}}", 400)
                        .path("code").asText());
        assertEquals(
                "SESSION_CONTROL_PAYLOAD_INVALID",
                callPost(mvc, url,
                        "{\"requestId\":\"rq1\",\"action\":\"SET_PLAN_MODE\",\"payload\":{\"active\":\"yes\"}}",
                        400)
                        .path("code").asText());
    }

    /** SET_PLAN_MODE 写 plan/mode{active}（轮次外立即提交无 pending）；STEER 写 user/message{message}，
     *  活动轮次内 pending=true；COMPACT 占位 accepted+pending（压缩引擎随 T2-9 交付）。 */
    @Test
    void controlSetPlanModeWritesEventAndSteerMarksPendingInActiveTurn() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/control";
        // 轮次外 SET_PLAN_MODE：立即提交——无 pending 键，plan/mode 事件落盘。
        var outside = callPost(mvc, url,
                "{\"requestId\":\"rq-plan-off\",\"action\":\"SET_PLAN_MODE\",\"payload\":{\"active\":true}}", 200)
                .path("data");
        assertTrue(outside.path("accepted").asBoolean());
        assertFalse(outside.has("pending"), "轮次外立即提交，无 pending 键");
        var modeEvent =
                db.queryForMap(
                        "select data from engine.session_event where session_id=? and type='plan/mode'",
                        sid);
        assertTrue(Json.read(String.valueOf(modeEvent.get("data")), JsonNode.class).path("active").asBoolean());
        // 活动轮次夹具：turn + RUNNING run。
        String run = activeRun(tenant, "alice");
        turn(sid, 1, run);
        // STEER（活动轮次内）：写 user/message{message} 并 pending=true（下一步注入）。
        var steer = callPost(mvc, url,
                "{\"requestId\":\"rq-steer\",\"action\":\"STEER\",\"payload\":{\"message\":\"优先核对差异列\"}}", 200)
                .path("data");
        assertTrue(steer.path("accepted").asBoolean());
        assertTrue(steer.path("pending").asBoolean(), "活动轮次内 STEER pending=true");
        var steerEvent =
                db.queryForMap(
                        "select data from engine.session_event where session_id=? and type='user/message'"
                                + " and data->>'message'=?",
                        sid,
                        "优先核对差异列");
        assertNotNull(steerEvent, "STEER 写 user/message{message} 事件");
        // 活动轮次内 SET_PLAN_MODE：pending=true（下一 accepted pre-step 生效）。
        var inside = callPost(mvc, url,
                "{\"requestId\":\"rq-plan-in\",\"action\":\"SET_PLAN_MODE\",\"payload\":{\"active\":false}}", 200)
                .path("data");
        assertTrue(inside.path("pending").asBoolean(), "turn 内 SET_PLAN_MODE pending=true");
        // COMPACT 占位（台账 H1-9）：accepted+pending，不写压缩事件。
        var compact =
                callPost(mvc, url, "{\"requestId\":\"rq-compact\",\"action\":\"COMPACT\"}", 200).path("data");
        assertTrue(compact.path("accepted").asBoolean());
        assertTrue(compact.path("pending").asBoolean());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type like 'compaction/%'",
                        Integer.class,
                        sid).intValue(),
                "COMPACT 占位不写压缩事件（压缩引擎随 T2-9 交付）");
        // STEER 后消息进入普通视图事件流（USER 可读）。
        var events = callGet(mvc, "/v1/apps/" + code + "/sessions/" + sid + "/events", 200).path("data");
        boolean steerVisible = false;
        for (var item : events.path("events"))
            if ("user/message".equals(item.path("type").asText())
                    && "优先核对差异列".equals(item.path("data").path("message").asText()))
                steerVisible = true;
        assertTrue(steerVisible, "STEER 注入的消息经事件增量可见");
    }

    /** CANCEL_TURN：置活动 run 取消（USER_CANCEL）+ turn/end{aborted}；无活动轮次 409 SESSION_TURN_ACTIVE。 */
    @Test
    void controlCancelTurnCancelsActiveRunWritesTurnEndAndRejectsWhenIdle() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        var mvc = mvc(service());
        var url = "/v1/apps/" + code + "/sessions/" + sid + "/control";
        // 无任何轮次：409 SESSION_TURN_ACTIVE。
        assertEquals(
                "SESSION_TURN_ACTIVE",
                callPost(mvc, url, "{\"requestId\":\"rq-cancel-0\",\"action\":\"CANCEL_TURN\"}", 409)
                        .path("code").asText());
        String run = activeRun(tenant, "alice");
        turn(sid, 1, run);
        var accepted =
                callPost(mvc, url, "{\"requestId\":\"rq-cancel\",\"action\":\"CANCEL_TURN\"}", 200).path("data");
        assertTrue(accepted.path("accepted").asBoolean());
        var runRow = db.queryForMap("select status,stop_reason from engine.run where public_id=?", run);
        assertEquals("CANCELLED", runRow.get("status"), "活动 run 被置取消");
        assertEquals("USER_CANCEL", runRow.get("stop_reason"));
        var end =
                db.queryForMap(
                        "select data from engine.session_event where session_id=? and type='turn/end'",
                        sid);
        var endData = Json.read(String.valueOf(end.get("data")), JsonNode.class);
        assertEquals(1, endData.path("turn").asLong());
        assertEquals("aborted", endData.path("reason").asText(), "用户取消收口 aborted");
        // 已取消后再取消：无活动轮次 409。
        assertEquals(
                "SESSION_TURN_ACTIVE",
                callPost(mvc, url, "{\"requestId\":\"rq-cancel-again\",\"action\":\"CANCEL_TURN\"}", 409)
                        .path("code").asText());
    }

    // ---------------------------------------------------------------- SSE（交付物 4）

    private static void setHeartbeat(ChatSessions service, long millis) throws Exception {
        var field = ChatSessions.class.getDeclaredField("heartbeatMillis");
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
     * SSE 生命周期：首连从 seq 1 补发首帧（普通视图排除诊断类不出帧）；Last-Event-ID=2 排他续读
     * 首帧 seq=4（被过滤的 3 被重扫不出帧）；空闲 15s（测试反射改短）发 :ping；closeSessionStreams
     * 收口后响应 200 + text/event-stream。
     */
    @Test
    void streamReplaysFramesFromLastEventIdWithPingHeartbeat() throws Exception {
        String tenant = "h9-t-" + UUID.randomUUID(), code = "h9-app";
        identity(tenant, "alice", List.of("USER"));
        String sid = session(tenant, "alice", code);
        event(sid, 1, "turn/start", "{\"turn\":1}");
        event(sid, 2, "user/message", "{\"content\":{\"message\":\"你好\"}}");
        event(sid, 3, "system/message", "{\"content\":\"诊断不出帧\"}");
        event(sid, 4, "assistant/message", "{\"stream\":\"答\"}");
        var service = service();
        setHeartbeat(service, 80);
        var mvc = mvc(service);
        String url = "/v1/apps/" + code + "/sessions/" + sid + "/events/stream";
        // 首连：无 Last-Event-ID → 从头补发。
        var first = mvc.perform(get(url)).andExpect(request().asyncStarted()).andReturn();
        String content = awaitContent(first, "id:4");
        // 首帧落盘后响应媒体类型应为 SSE（Content-Type 随首帧写出）。
        assertEquals(
                "text/event-stream",
                Objects.requireNonNull(first.getResponse().getContentType(), "响应必须有媒体类型")
                        .split(";")[0],
                "SSE 媒体类型");
        assertTrue(content.contains("id:1\nevent:turn/start\ndata:{\"turn\":1}\n\n"), content);
        assertTrue(content.contains("id:2\nevent:user/message\n"), content);
        assertTrue(content.contains("id:4\nevent:assistant/message\n"), content);
        assertFalse(content.contains("id:3"), "普通视图：诊断事件不出帧（重连重扫时同样按 seq 幂等跳过）");
        // 排他续读：Last-Event-ID=2 → 从 seq>2 扫描；3 被过滤、4 出帧。
        var resume = mvc.perform(get(url).header("Last-Event-ID", "2"))
                .andExpect(request().asyncStarted()).andReturn();
        String resumed = awaitContent(resume, "id:4");
        assertFalse(resumed.contains("id:1"), "排他：不重发 seq<=Last-Event-ID");
        assertFalse(resumed.contains("id:2"), "排他：Last-Event-ID 本身不重发");
        assertTrue(resumed.contains("id:4\nevent:assistant/message\n"), resumed);
        // 心跳：空闲后出现 :ping 注释帧。
        awaitContent(resume, ":ping");
        // 非法 Last-Event-ID 400。
        assertEquals(
                "EVENT_CURSOR",
                callGet(mvc, url + "?Last-Event-ID=soon", 400).path("code").asText());
        // 收口：断开即停（不取消 turn——泵内无任何 run/turn 写路径），响应 200。
        assertEquals(2, service.closeSessionStreams(sid), "closeSessionStreams 一次收口本会话全部连接");
        assertEquals(0, service.closeSessionStreams(sid), "重复收口无残留");
        assertEquals(200, mvc.perform(asyncDispatch(first)).andReturn().getResponse().getStatus());
        var resumedResponse = mvc.perform(asyncDispatch(resume)).andReturn().getResponse();
        assertEquals(200, resumedResponse.getStatus());
        assertEquals(
                "text/event-stream",
                Objects.requireNonNull(resumedResponse.getContentType(), "续读响应必须有媒体类型")
                        .split(";")[0]);
    }

    // ---------------------------------------------------------------- 网关登记（交付物 5）

    /**
     * 网关登记（H1-9 交付物 5，照 S2-1 目录行方式）：三条新路径的 authority 目录行必须存在——
     * RouteCoverageVerifier 在引擎上下文启动时强制每个 handler 有目录行，缺行启动即失败。
     * integrationAllowed=false 与网关 IntegrationPolicy.allowedPath 不放行事件/控制/SSE 一致
     * （集成票据面不在本卡契约；门户角色面见接口稿 §5.1 权限行）。
     */
    @Test
    void gatewayCatalogRegistersEventControlAndStreamRoutes() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog =
                    Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            var expected = new String[][] {
                {"GET", "/v1/apps/{code}/sessions/{id}/events", "proxy.engine.get.ChatSessionController.events", "52"},
                {"POST", "/v1/apps/{code}/sessions/{id}/control", "proxy.engine.post.ChatSessionController.control", "64"},
                {"GET", "/v1/apps/{code}/sessions/{id}/events/stream", "proxy.engine.get.ChatSessionController.stream", "71"}
            };
            for (var row : expected) {
                var matches = new ArrayList<String>();
                for (var line : catalog) {
                    if (row[0].equals(line.path("method").asText())
                            && row[1].equals(line.path("template").asText())
                            && "engine".equals(line.path("target").asText())
                            && "gateway".equals(line.path("caller").asText())
                            && "delegated".equals(line.path("mode").asText())) {
                        matches.add(line.toString());
                        assertEquals(row[2], line.path("action").asText(), "委派动作必须指向本端点");
                        assertEquals(
                                "engine-runtime/src/main/java/cn/agentplatform/engine/api/ChatSessionController.java:"
                                        + row[3],
                                line.path("source").asText(),
                                "目录行 source 指向本卡 handler");
                        assertFalse(line.path("integrationAllowed").asBoolean(false), "集成票据面不在本卡契约");
                    }
                }
                assertEquals(1, matches.size(), row[0] + " " + row[1] + " 必须恰登记一行: " + matches);
            }
        }
    }
}
