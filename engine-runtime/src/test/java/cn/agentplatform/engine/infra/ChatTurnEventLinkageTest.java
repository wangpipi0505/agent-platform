package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * H1-2（台账 §7.2）：turn 写入事件联动 + run 早关联。
 *
 * <p>主方案 §5.1：chat_turn 创建与 turn/start+user/message <b>同事务</b>（SessionStore(TraceStore)
 * 复用 ChatSessions 轮次预留事务，REQUIRED 传播加入外层 store.atomic）；run 受理即回写
 * chat_turn.run_public_id（既有时序保持，不等 turn 完成）。验收：轮次预留后/run 受理后/
 * 认领后/提交后四时点重启恢复到原轮次——重放（新 ChatSessions 实例=重启）不产生第二个轮次、
 * 不重复事件；运行失败后重放同样归原运行（H1-0 已证运行层受理恒 1）。
 * 边界：评测入口 evaluationTurn 不在本卡范围（内部质量工具线）；"预留后 run 未建"窗口
 * 位于 ticket.persist 单事务内部，公共 API 不可达，以"受理后运行失败"的恢复不变量覆盖。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ChatTurnEventLinkageTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** 类内每条用例前后都清理：防止残留 chat_session 行以 FK 阻断其他测试类的无界清理。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SchedulerTestResources.close();
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update("delete from engine.attempt_note");
        db.update("delete from engine.run_event");
        db.update("delete from engine.tool_call");
        db.update("delete from engine.attempt");
        db.update("delete from engine.node_instance");
        db.update("delete from engine.run_authority");
        db.update("delete from engine.run");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h2_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 'h2_%'");
        db.update("delete from engine.chat_session where public_id like 'h2_%'");
    }

    /** CHAT 会话夹具：published 型快照（含版本号与模型绑定依赖——createInternal CHAT 触发必读）。 */
    String session() {
        String id = "h2_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h2-app',?::jsonb)",
                id,
                Json.write(publishedSnapshot()));
        return id;
    }

    /**
     * 出站桩：app published 放行并返回同款 CHAT 快照；模型 measure 返回计量、chatStream 按
     * chatThrows 开关决定回终文本（运行成功）或抛运行失败；其余出站一律断言失败。
     */
    RuntimeClients stubClients(AtomicBoolean chatThrows) {
        var snapshot = publishedSnapshot();
        return new RuntimeClients() {
            @Override
            public JsonNode get(String service, String path, Actor a) {
                if (service.equals("app") && path.endsWith("/published")) return snapshot.deepCopy();
                throw new AssertionError("意外出站: " + service + path);
            }

            @Override
            public JsonNode chatStream(Object body, Actor a, java.util.function.Consumer<String> chunk) {
                return post("model", "/internal/model/chat/stream", body, a);
            }

            @Override
            public JsonNode post(String service, String path, Object body, Actor a) {
                if (path.equals("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":100,\"contextWindow\":10000,"
                                + "\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                if (path.equals("/internal/model/chat/stream")) {
                    if (chatThrows.get())
                        throw new PlatformException(502, "MODEL_DOWN", "故障注入：模型出站失败");
                    return Json.read(
                            "{\"text\":\"ok-h2\",\"toolCalls\":[],"
                                + "\"usage\":{\"promptTokens\":10,\"completionTokens\":3,\"totalTokens\":13},"
                                + "\"actualModel\":\"fixture\"}");
                }
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    private JsonNode publishedSnapshot() {
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        return Json.tree(
                Map.of(
                        "appCode", "h2-app",
                        "type", "CHAT",
                        "name", "h2",
                        "description", "",
                        "versionNo", 1,
                        "appVersionId", 1,
                        "versionId", 1,
                        "config", Map.of("modelEntryId", 1),
                        "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
                                                Map.of(
                                                        "modelEntryId",
                                                        1,
                                                        "entry",
                                                        modelEntry,
                                                        "modelBindingRef",
                                                        Map.of(
                                                                "bindingId",
                                                                1,
                                                                "sha256",
                                                                Digest.sha256(
                                                                        Json.canonical(modelEntry))))))));
    }

    RunService runs(RuntimeClients clients) {
        var agent = new AgentDriver(store, clients, new ContextAssembler());
        return SchedulerTestResources.runs(store, clients, new FlowDriver(store, agent), agent);
    }

    long watermark(String sid) {
        Long n =
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=?", Long.class, sid);
        return n == null ? 0 : n;
    }

    int chatTurns(String sid, String requestId) {
        Integer n =
                db.queryForObject(
                        "select count(*) from engine.chat_turn where session_id=? and request_id=?",
                        Integer.class,
                        sid,
                        requestId);
        return n == null ? 0 : n;
    }

    /** 全链：受理→模型→提交；重启重放归原轮次原运行，事件恰两件且不重复。 */
    @Test
    void turnLifecycleBindsRunEarlyAndReplaysToSameTurn() throws Exception {
        String sid = session();
        var chatThrows = new AtomicBoolean(false);
        var clients = stubClients(chatThrows);
        ChatSessions chats = new ChatSessions(store, clients, runs(clients));

        var req = Json.tree(Map.of("requestId", "h2-r1", "inputs", Map.of("q", "hello")));
        var first = chats.turn("h2-app", sid, req, actor);
        String runId = first.get("runId").toString();
        awaitTerminal(runId, clients);

        // run 受理后（P2）与提交后（P4）：run 早关联 + 事件恰两件（turn/start + user/message）
        var turn =
                db.queryForMap(
                        "select turn_no,run_public_id from engine.chat_turn where session_id=? and request_id=?",
                        sid,
                        "h2-r1");
        assertEquals(1, ((Number) turn.get("turn_no")).intValue());
        assertEquals(runId, turn.get("run_public_id"));
        assertEquals(4, watermark(sid)); // H1-5：turn对2件+request对2件
        assertEquals(
                "turn/start",
                db.queryForObject(
                        "select type from engine.session_event where session_id=? and seq=1",
                        String.class,
                        sid));
        assertEquals(
                "1",
                db.queryForObject(
                        "select data->>'turn' from engine.session_event where session_id=? and seq=1",
                        String.class,
                        sid));

        // 重启恢复：全新 ChatSessions 实例重放同一 requestId → 原轮次原运行，零新增
        ChatSessions restarted = new ChatSessions(store, clients, runs(clients));
        var replay = restarted.turn("h2-app", sid, req, actor);
        assertEquals(runId, replay.get("runId").toString());
        assertEquals(1, chatTurns(sid, "h2-r1"));
        assertEquals(4, watermark(sid), "重放不得重复写轮次事件");
    }

    /** 第二轮次：事件续写 seq 3,4（turn=2），轮次序严格递增；重启后各轮重放各归各运行。 */
    @Test
    void secondTurnAppendsDistinctEventsAndReplaysLandOnOwnRuns() throws Exception {
        String sid = session();
        var clients = stubClients(new AtomicBoolean(false));
        ChatSessions chats = new ChatSessions(store, clients, runs(clients));

        var r1 = chats.turn("h2-app", sid, Json.tree(Map.of("requestId", "h2-r1", "inputs", Map.of("q", "a"))), actor);
        awaitTerminal(r1.get("runId").toString(), clients); // 上一轮终态后才可开下一轮（CHAT_TURN_ACTIVE 语义）
        var r2 = chats.turn("h2-app", sid, Json.tree(Map.of("requestId", "h2-r2", "inputs", Map.of("q", "b"))), actor);
        awaitTerminal(r2.get("runId").toString(), clients);
        assertNotEquals(r1.get("runId"), r2.get("runId"));
        assertEquals(8, watermark(sid)); // 两轮各4件
        assertEquals(
                "2",
                db.queryForObject(
                        "select data->>'turn' from engine.session_event where session_id=? and seq=5",
                        String.class,
                        sid));

        var restarted = new ChatSessions(store, clients, runs(clients));
        assertEquals(
                r1.get("runId"),
                restarted
                        .turn("h2-app", sid, Json.tree(Map.of("requestId", "h2-r1", "inputs", Map.of("q", "a"))), actor)
                        .get("runId"));
        assertEquals(
                r2.get("runId"),
                restarted
                        .turn("h2-app", sid, Json.tree(Map.of("requestId", "h2-r2", "inputs", Map.of("q", "b"))), actor)
                        .get("runId"));
        assertEquals(8, watermark(sid), "重放零重复事件");
    }

    /** 运行失败（P2 后）：重放归原失败运行，不重试、不产生第二轮次/重复事件（运行层受理恒 1 由 H1-0 证）。 */
    @Test
    void failedRunReplayReturnsSameRunWithoutDuplicateTurn() throws Exception {
        String sid = session();
        var clients = stubClients(new AtomicBoolean(true));
        ChatSessions chats = new ChatSessions(store, clients, runs(clients));

        var req = Json.tree(Map.of("requestId", "h2-f1", "inputs", Map.of("q", "go")));
        var first = chats.turn("h2-app", sid, req, actor);
        awaitTerminal(first.get("runId").toString(), clients);
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "select status from engine.run where public_id=?",
                        String.class,
                        first.get("runId").toString()));

        ChatSessions restarted = new ChatSessions(store, clients, runs(clients));
        var replay = restarted.turn("h2-app", sid, req, actor);
        assertEquals(first.get("runId"), replay.get("runId"));
        assertEquals(1, chatTurns(sid, "h2-f1"));
        assertEquals(4, watermark(sid), "失败重放零重复事件（request材料已落，运行层失败不影响D2）");
    }

    private void awaitTerminal(String runId, RuntimeClients clients) throws Exception {
        var runs = runs(clients);
        for (int i = 0; i < 300; i++) {
            runs.recover();
            var status = runs.status(runId, actor).get("status");
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) return;
            Thread.sleep(20);
        }
        fail("运行未在时限内到达终态: " + runId);
    }
}
