package cn.agentplatform.engine.infra;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * S2-1（台账 §7.3）：本人会话列表端点——GET /v1/apps/{code}/sessions?mine=true。
 *
 * <p>验收映射：本人列表只含本人会话（tenant+principal 过滤）且 TASK_INTERNAL 不进列表、
 * 其他 principal 不可见；title=首条用户消息截断 ≤48 字；lastActiveAt 倒序 + before 游标
 * 翻页正确、nextBefore 末页省略；mine/limit/before 非法入参 400；state 为活跃/终态轮次摘要。
 * 网关登记以目录行断言验证（{@code RouteCoverageVerifier} 在引擎启动时强制目录覆盖，缺行
 * 即启动失败；Routes.java 的既有委派正则已由 platform-gateway RoutesTest 断言
 * /v1/apps/ops/sessions → engine）。端点行为经 standalone MockMvc 真实 Controller 调用
 *（先例 EvaluationConversationSessionTest）。
 *
 * <p>夹具一律 s21_% 前缀；@BeforeEach+@AfterEach 双向清理 chat_turn/chat_session/run，
 * session_event 残留按 ChatTurnEventLinkageTest 的触发器禁用方式清理。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SessionListEndpointTest {

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

    /** 双向清理：仅 s21_% 夹具，不触碰共享测试库中其他测试的行。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 's21_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 's21_%'");
        db.update("delete from engine.chat_session where public_id like 's21_%'");
        db.update("delete from engine.run where public_id like 's21_%'");
    }

    private void identity(String tenant, String principal) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("USER"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ChatSessionController(new ChatSessions(store, null, null)))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private JsonNode call(MockMvc mvc, String url, int expected) throws Exception {
        var response = mvc.perform(get(url)).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    /** 会话夹具：显式 created_at（now() 是事务级，同事务插入会同值，无法表达倒序分页）。 */
    private String session(
            String tenant, String principal, String code, Instant createdAt, String origin) {
        String id = "s21_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,"
                    + "snapshot,origin,created_at) values(?,?,?,?,'{}'::jsonb,?,?)",
                id,
                tenant,
                principal,
                code,
                origin == null ? "USER" : origin,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    private void turn(String sessionId, int turnNo, String inputsJson, Instant createdAt) {
        turn(sessionId, turnNo, inputsJson, createdAt, null);
    }

    private void turn(
            String sessionId, int turnNo, String inputsJson, Instant createdAt, String runPublicId) {
        db.update(
                "insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id,"
                    + "created_at) values(?,?,?,?::jsonb,?,?)",
                sessionId,
                turnNo,
                "s21-r-" + sessionId + "-" + turnNo,
                inputsJson,
                runPublicId,
                java.sql.Timestamp.from(createdAt));
    }

    /** 终态 run 夹具（state 断言用）；CHAT 触发器要求 app_version_id 非空。 */
    private String failedRun(String tenant, String principal, Instant endedAt) {
        String id = "s21_run_" + UUID.randomUUID().toString().replace("-", "");
        db.update(
                "insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,"
                    + "principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,"
                    + "inputs,request_id,ended_at)"
                    + " values(?,'s21-app',1,'CHAT','FAILED',?,?,'s21-in','{}'::jsonb,'{}'::jsonb,"
                    + "'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,?,?)",
                id,
                principal,
                tenant,
                "s21-req-" + id,
                java.sql.Timestamp.from(endedAt));
        return id;
    }

    /** 本人列表只含本人非 TASK_INTERNAL 会话：租户/principal/应用三重过滤 + TASK_INTERNAL 排除。 */
    @Test
    void listsOnlyOwnSessionsExcludingTaskInternal() throws Exception {
        String tenant = "s21-t-" + UUID.randomUUID(), code = "s21-app", other = "s21-other-app";
        identity(tenant, "alice");
        // 本人 B：无轮次，created_at=+100s；本人 A：轮次推到 +200s——按 lastActiveAt 倒序 B 后 A 前。
        String mineA = session(tenant, "alice", code, BASE.plusSeconds(10), null);
        turn(mineA, 1, "{\"message\":\"第一条\"}", BASE.plusSeconds(200));
        String mineB = session(tenant, "alice", code, BASE.plusSeconds(100), null);
        // 本人但 TASK_INTERNAL：lastActiveAt 最大（+900s）也必须被排除（内部根会话不进列表）。
        session(tenant, "alice", code, BASE.plusSeconds(900), "TASK_INTERNAL");
        // 非 principal 同租户；本人但另一应用——都不进本应用列表。
        session(tenant, "bob", code, BASE.plusSeconds(300), null);
        session(tenant, "alice", other, BASE.plusSeconds(400), null);

        var body = call(mvc(), "/v1/apps/" + code + "/sessions?mine=true", 200);
        assertEquals("OK", body.path("code").asText());
        var sessions = body.path("data").path("sessions");
        assertEquals(2, sessions.size(), body.toString());
        assertEquals(mineA, sessions.get(0).path("sessionId").asText(), "最近轮次时间倒序");
        assertEquals(mineB, sessions.get(1).path("sessionId").asText());
        // 响应字段契约（接口稿 §5.1）。
        for (var item : sessions) {
            for (String field : List.of("sessionId", "appCode", "title", "lastActiveAt", "createdAt", "state"))
                assertTrue(item.has(field), "缺字段 " + field + ": " + item);
            assertEquals(code, item.path("appCode").asText());
        }
        assertEquals(
                "2026-01-01T00:03:20Z", sessions.get(0).path("lastActiveAt").asText(), "lastActiveAt=最近轮次时间");
        assertEquals("2026-01-01T00:01:40Z", sessions.get(1).path("lastActiveAt").asText(), "无轮次→会话创建时间");
        assertEquals("ACTIVE", sessions.get(0).path("state").asText(), "轮次无 run→活跃");
        assertEquals("ACTIVE", sessions.get(1).path("state").asText(), "无轮次→活跃");
        assertEquals("第一条", sessions.get(0).path("title").asText());
    }

    /** 最近轮次 run 到终态时 state 给出终态摘要；turn_no 最大的轮次决定 state。 */
    @Test
    void stateReflectsLatestTurnRunTerminalStatus() throws Exception {
        String tenant = "s21-t-" + UUID.randomUUID(), code = "s21-app";
        identity(tenant, "alice");
        String sid = session(tenant, "alice", code, BASE.plusSeconds(1), null);
        turn(sid, 1, "{\"message\":\"r1\"}", BASE.plusSeconds(2), failedRun(tenant, "alice", BASE.plusSeconds(3)));
        turn(sid, 2, "{\"message\":\"r2\"}", BASE.plusSeconds(4)); // 最近轮次无 run → 活跃
        var active = call(mvc(), "/v1/apps/" + code + "/sessions?mine=true", 200)
                .path("data").path("sessions").get(0);
        assertEquals("ACTIVE", active.path("state").asText(), "最近轮次无 run→活跃");

        String sid2 = session(tenant, "alice", code, BASE.plusSeconds(5), null);
        turn(sid2, 1, "{\"message\":\"r1\"}", BASE.plusSeconds(6));
        turn(sid2, 2, "{\"message\":\"r2\"}", BASE.plusSeconds(7), failedRun(tenant, "alice", BASE.plusSeconds(8)));
        var rows = call(mvc(), "/v1/apps/" + code + "/sessions?mine=true", 200).path("data").path("sessions");
        assertEquals("FAILED", rows.get(0).path("state").asText(), "最近轮次 run 终态→终态摘要");
    }

    /** title=首条（turn_no 最小）用户消息截断 ≤48 字；稳定规则与无轮次空串见 ChatSessions.title。 */
    @Test
    void titleIsFirstUserMessageTruncatedTo48() throws Exception {
        String tenant = "s21-t-" + UUID.randomUUID(), code = "s21-app";
        identity(tenant, "alice");
        String sixty = "甲".repeat(60);
        String longSession = session(tenant, "alice", code, BASE.plusSeconds(1), null);
        turn(longSession, 1, Json.write(Map.of("message", sixty)), BASE.plusSeconds(2));
        turn(longSession, 2, Json.write(Map.of("message", "第二条不是标题")), BASE.plusSeconds(3));
        String textField = session(tenant, "alice", code, BASE.plusSeconds(4), null);
        turn(textField, 1, Json.write(Map.of("text", "文本字段标题")), BASE.plusSeconds(5));
        String objectFallback = session(tenant, "alice", code, BASE.plusSeconds(6), null);
        turn(objectFallback, 1, Json.write(Map.of("q", 1, "r", 2)), BASE.plusSeconds(7));
        session(tenant, "alice", code, BASE.plusSeconds(8), null); // 无轮次 → 空串

        var rows = call(mvc(), "/v1/apps/" + code + "/sessions?mine=true", 200).path("data").path("sessions");
        assertEquals(4, rows.size());
        var byId = new LinkedHashMap<String, JsonNode>();
        for (var item : rows) byId.put(item.path("sessionId").asText(), item);
        assertEquals(48, byId.get(longSession).path("title").asText().length(), "60 字截断到 48");
        assertEquals("甲".repeat(48), byId.get(longSession).path("title").asText());
        assertEquals("文本字段标题", byId.get(textField).path("title").asText(), "无 message 时取 text 字段");
        assertEquals("{\"q\":1,\"r\":2}", byId.get(objectFallback).path("title").asText(), "无 message/text→整体 JSON 文本");
        var emptyTitles =
                byId.values().stream().filter(i -> i.path("title").asText().isEmpty()).toList();
        assertEquals(1, emptyTitles.size(), "无轮次会话 title 必须为空串");
    }

    /** lastActiveAt 倒序 + before 排他游标翻页；nextBefore=本页最后一条 lastActiveAt，末页省略。 */
    @Test
    void cursorPaginationWalksAllPagesWithoutOverlapAndDropsNextBeforeOnLastPage() throws Exception {
        String tenant = "s21-t-" + UUID.randomUUID(), code = "s21-app";
        identity(tenant, "alice");
        Map<String, String> lastActive = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            String id = session(tenant, "alice", code, BASE.plusSeconds(i), null);
            lastActive.put(id, BASE.plusSeconds(i).toString());
        }
        var mvc = mvc();
        var seen = new ArrayList<String>();
        String before = null;
        for (int page = 0; page < 3; page++) {
            String url = "/v1/apps/" + code + "/sessions?mine=true&limit=2" + (before == null ? "" : "&before=" + before);
            var data = call(mvc, url, 200).path("data");
            var sessions = data.path("sessions");
            int expectedSize = page == 2 ? 1 : 2;
            assertEquals(expectedSize, sessions.size(), "第 " + (page + 1) + " 页: " + data.toString());
            String previous = before == null ? null : before;
            for (var item : sessions) {
                String id = item.path("sessionId").asText();
                assertFalse(seen.contains(id), "跨页重复: " + id);
                seen.add(id);
                if (previous != null)
                    assertTrue(item.path("lastActiveAt").asText().compareTo(previous) < 0, "游标必须排他");
            }
            if (page < 2) {
                assertTrue(data.has("nextBefore"), "还有更多时必须返回 nextBefore");
                before = data.path("nextBefore").asText();
                assertEquals(
                        sessions.get(sessions.size() - 1).path("lastActiveAt").asText(),
                        before,
                        "nextBefore=本页最后一条的 lastActiveAt");
            } else {
                assertFalse(data.has("nextBefore"), "末页不返回 nextBefore");
            }
        }
        assertEquals(5, seen.size(), "三页恰好走完全部会话");
        assertEquals(Set.copyOf(lastActive.keySet()), Set.copyOf(seen));
    }

    /** mine=true 首期固定：缺省/其他值 400；limit 越界 400；before 非法 400。 */
    @Test
    void rejectsMissingMineAndInvalidLimitOrCursor() throws Exception {
        String tenant = "s21-t-" + UUID.randomUUID(), code = "s21-app";
        identity(tenant, "alice");
        var mvc = mvc();
        assertEquals(
                "SESSION_LIST_MINE_REQUIRED",
                call(mvc, "/v1/apps/" + code + "/sessions", 400).path("code").asText());
        assertEquals(
                "SESSION_LIST_MINE_REQUIRED",
                call(mvc, "/v1/apps/" + code + "/sessions?mine=false", 400).path("code").asText());
        assertEquals(
                "SESSION_LIST_LIMIT_INVALID",
                call(mvc, "/v1/apps/" + code + "/sessions?mine=true&limit=0", 400).path("code").asText());
        assertEquals(
                "SESSION_LIST_LIMIT_INVALID",
                call(mvc, "/v1/apps/" + code + "/sessions?mine=true&limit=201", 400).path("code").asText());
        assertEquals(
                "SESSION_LIST_LIMIT_INVALID",
                call(mvc, "/v1/apps/" + code + "/sessions?mine=true&limit=abc", 400).path("code").asText());
        assertEquals(
                "SESSION_LIST_CURSOR_INVALID",
                call(mvc, "/v1/apps/" + code + "/sessions?mine=true&before=yesterday", 400)
                        .path("code")
                        .asText());
        // 合法边界：limit=1 与 limit=200 放行。
        assertEquals(200, mvc.perform(get("/v1/apps/" + code + "/sessions?mine=true&limit=1")).andReturn()
                .getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/v1/apps/" + code + "/sessions?mine=true&limit=200")).andReturn()
                .getResponse().getStatus());
    }

    /**
     * 网关登记（S2-1 交付物 2）：目录行必须存在——RouteCoverageVerifier 在引擎上下文启动时
     * 强制每个 handler 有目录行，缺行启动即失败（G-9 构建期漂移校验同一判定）。
     * integrationAllowed=false 与网关 IntegrationPolicy.allowedPath 不放行 GET 列表一致
     * （接口稿 §5.1 权限为门户角色 USER/BUILDER/OPERATOR/ADMIN，集成票据面不在本卡契约）。
     */
    @Test
    void gatewayCatalogRegistersGetSessionListRoute() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog =
                    Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            var matches = new ArrayList<String>();
            for (var row : catalog) {
                if ("GET".equals(row.path("method").asText())
                        && "/v1/apps/{code}/sessions".equals(row.path("template").asText())
                        && "engine".equals(row.path("target").asText())
                        && "gateway".equals(row.path("caller").asText())
                        && "delegated".equals(row.path("mode").asText())) {
                    matches.add(row.toString());
                    assertEquals(
                            "proxy.engine.get.ChatSessionController.list",
                            row.path("action").asText(),
                            "委派动作必须指向本端点");
                    assertFalse(row.path("integrationAllowed").asBoolean(false), "集成票据面不在本卡契约");
                }
            }
            assertEquals(1, matches.size(), "GET /v1/apps/{code}/sessions 必须恰登记一行: " + matches);
        }
    }
}
