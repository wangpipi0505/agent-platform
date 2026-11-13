package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.harness.loop.AgentEngine;
import cn.agentplatform.engine.harness.loop.Inbox;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * H1-4（台账 §7.2）：harness.loop.AgentEngine 影子验证——行为清单 B1/B2/B4
 * （contracts/oracle-replay-adapter.md §3）+ 取消语义 + 中断补写。
 *
 * <p>影子策略（主方案 R2）：引擎并行运行、默认路径（AgentDriver 经 RunService）不切。
 * 与 legacy 的差异=引擎新增 turn 信封与 inbox 事实（粒度差异，映射说明记录）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class AgentEngineShadowTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final String CONTRACT = Digest.sha256("fixture-contract");

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

    @BeforeEach
    @AfterEach
    void clean() {
        SchedulerTestResources.close();
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run_authority");
        db.update("delete from engine.run where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.agent_inbox where session_id like 'h4_%'");
        db.update("delete from engine.session_event where session_id like 'h4_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 'h4_%'");
        db.update("delete from engine.chat_session where public_id like 'h4_%'");
    }

    String session() {
        String id = "h4_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h4-app','{}')",
                id);
        return id;
    }

    long node(long r) {
        return db.queryForObject(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE',?,'{\"type\":\"CODE\",\"expression\":\"1\"}') returning id",
                Long.class,
                r,
                "READY");
    }

    long run(String sid) {
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        return db.queryForObject(
                "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                    + " values(?,'h4-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}',?::jsonb,'{}','STEP')"
                    + " returning id",
                Long.class,
                "r_h4_" + UUID.randomUUID(),
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "lookup",
                                                        "name",
                                                        "lookup",
                                                        "skillVersionId",
                                                        42,
                                                        "contractDigest",
                                                        CONTRACT))),
                                "chatSession",
                                Map.of("sessionId", sid, "turnNo", 1),
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
                                                                        Json.canonical(modelEntry)))))))));
    }

    JsonNode config() {
        return Json.read(
                "{\"modelEntryId\":1,\"tools\":[{\"code\":\"lookup\",\"skillVersionId\":42,"
                    + "\"contractDigest\":\""
                    + CONTRACT
                    + "\",\"name\":\"lookup\",\"definition\":{\"type\":\"function\",\"function\":"
                    + "{\"name\":\"lookup\",\"description\":\"受控查询\",\"parameters\":{\"type\":\"object\","
                    + "\"properties\":{\"x\":{\"type\":\"integer\"}},\"required\":[\"x\"]}}}}]}");
    }

    /** 模型桩：第一次返回工具调用，其后返回终文本；execute 桩按权威 SUCCEEDED 回执受理恰好一次。 */
    RuntimeClients stubClients(AtomicBoolean chatThrows) {
        return new RuntimeClients() {
            final AtomicInteger chats = new AtomicInteger();
            final AtomicInteger accepts = new AtomicInteger();

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
                    if (chats.incrementAndGet() == 1)
                        return Json.read(
                                "{\"text\":\"需要查询\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"lookup\","
                                    + "\"arguments\":{\"x\":1}}],\"usage\":{},\"actualModel\":\"fixture\"}");
                    return Json.read(
                            "{\"text\":\"shadow-done\",\"toolCalls\":[],\"usage\":{},\"actualModel\":\"fixture\"}");
                }
                if (path.equals("/internal/skills/lookup/execute")) {
                    long callId = Json.tree(body).path("callId").asLong();
                    var token =
                            Json.tree(
                                    Map.of(
                                            "owner",
                                            Json.tree(body).path("leaseOwner").asText(),
                                            "attemptId",
                                            Json.tree(body).path("attemptId").asLong()));
                    var calls = new ToolCalls(store);
                    assertEquals(true, calls.claim(callId, token, a).get("claimed"));
                    calls.submitted(callId, token, a);
                    assertEquals(1, accepts.incrementAndGet(), "外部受理恰好一次");
                    // H1-6：技能服务经 /fact 写权威回执——触发 tool/result 会话事件（登记事务链路）
                    var factReq =
                            Json.tree(
                                    Map.of(
                                            "status",
                                            "SUCCEEDED",
                                            "result",
                                            Map.of("structured", Map.of("value", 42)),
                                            "attemptId",
                                            Json.tree(body).path("attemptId").asLong(),
                                            "owner",
                                            Json.tree(body).path("leaseOwner").asText(),
                                            "durationMs",
                                            13,
                                            "isMock",
                                            false));
                    calls.fact(callId, factReq, a);
                    return Json.read("{\"status\":\"SUCCEEDED\",\"result\":{\"structured\":{\"value\":42}}}");
                }
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    /** 影子主链：turn 信封完整（B4）、调用-结果成对（B2）、采纳集=实际执行（B1）、inbox 消费一次。 */
    @Test
    void shadowTurnProducesCompleteEnvelopeWithPairedToolCalls() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var clients = stubClients(new AtomicBoolean(false));
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var inbox = new Inbox(store);
        var engine = new AgentEngine(store, driver, inbox, new SessionStore(store));

        inbox.enqueue(sid, "NEXT_TURN", "req-h4-1", Json.tree(Map.of("q", "hello")));
        var result = engine.runTurn(sid, 1, runRow, claim, config(), Json.read("{\"message\":\"hello\"}"), actor, "engine-h4");
        assertEquals("shadow-done", result.path("text").asText());

        var events = new SessionStore(store).read(sid, 0, 0);
        // B4：turn 信封完整且有序（inbox/enqueue 事件先于信封——入队持久事实，按粒度差异记录映射）
        var lifecycle = events.stream().filter(e -> !"inbox/enqueue".equals(e.type()) && !"inbox/consume".equals(e.type())).toList();
        assertEquals("turn/start", lifecycle.get(0).type());
        assertEquals(
                "turn/end",
                lifecycle.get(lifecycle.size() - 1).type());
        assertEquals(
                "completed",
                lifecycle.get(lifecycle.size() - 1).data().path("reason").asText());
        // B1：request/header 采纳集含 lookup，且唯一 tool/call 的 name 已声明
        boolean headerDeclared = false;
        for (var e : events)
            if ("request/header".equals(e.type())
                    && "lookup".equals(e.data().path("tools").path(0).path("toolId").asText()))
                headerDeclared = true;
        assertTrue(headerDeclared, "采纳集必须声明实际执行的工具");
        // B2（事件级，H1-6）：会话事件中 tool/call 与 tool/result 按 callId 成对——
        // tool/call 在登记事务内（H1-6 共用登记入口）、tool/result 在权威回执确认事务内
        Map<String, Integer> calls = new LinkedHashMap<>();
        Map<String, Integer> results = new LinkedHashMap<>();
        for (var e : events) {
            if ("tool/call".equals(e.type()))
                calls.merge(e.data().path("callId").asText(), 1, Integer::sum);
            if ("tool/result".equals(e.type()))
                results.merge(e.data().path("callId").asText(), 1, Integer::sum);
        }
        assertEquals(1, calls.size(), "唯一调用事件");
        assertEquals(calls, results, "调用-结果必须成对");
        assertEquals(
                "lookup", events.stream().filter(e -> "tool/call".equals(e.type())).findFirst().orElseThrow().data().path("name").asText());
        // 外部受理恰好一次（桩内 assert 已验）；inbox 输入确认消费（consume 后物理删除）
        assertEquals(0, inboxRows(sid));

        // H1-6 重放唯一性：同一逻辑调用（同 runId/nodeId/seq/version）幂等命中 SUCCEEDED 既有事实，
        // 不产生第二条 tool/call 会话事件
        var replay = driver.skill(runRow, claim, "lookup", 42, Json.read("{\"x\":1}"), 1, actor);
        assertEquals(42, replay.path("structured").path("value").asInt());
        long toolCallEvents =
                events.size() > 0
                        ? new SessionStore(store).read(sid, 0, 0).stream()
                                .filter(e -> "tool/call".equals(e.type()))
                                .count()
                        : 0;
        assertEquals(1, toolCallEvents, "重放复用首个锚点，tool/call 事件不重复");
    }

    /** 取消语义：取消中的 turn 不提交 system/user——aborted 收口，零模型出站。 */
    @Test
    void cancelledTurnAbortsWithoutDispatch() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var clients = stubClients(new AtomicBoolean(false));
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var inbox = new Inbox(store);
        var engine = new AgentEngine(store, driver, inbox, new SessionStore(store));
        engine.cancel();

        inbox.enqueue(sid, "NEXT_TURN", "req-h4-2", Json.tree(Map.of("q", "hello")));
        assertNull(engine.runTurn(sid, 1, runRow, claim, config(), Json.read("{\"message\":\"hello\"}"), actor, "engine-h4"));
        var events = new SessionStore(store).read(sid, 0, 0);
        var lifecycle = events.stream().filter(e -> !"inbox/enqueue".equals(e.type()) && !"inbox/consume".equals(e.type())).toList();
        assertEquals(1, lifecycle.size());
        assertEquals("turn/end", lifecycle.get(0).type());
        assertEquals("aborted", lifecycle.get(0).data().path("reason").asText());
    }

    /** 中断语义：模型出站失败 → 未闭合调用补 UNKNOWN（无则无补写）+ turn/end{interrupted}；不消费输入（租约过期重投幂等）。 */
    @Test
    void interruptedTurnClosesWithInterruptedAndLeavesInputForRedelivery() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var clients = stubClients(new AtomicBoolean(true));
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var inbox = new Inbox(store);
        var engine = new AgentEngine(store, driver, inbox, new SessionStore(store));

        inbox.enqueue(sid, "NEXT_TURN", "req-h4-3", Json.tree(Map.of("q", "hello")));
        assertThrows(
                PlatformException.class,
                () -> engine.runTurn(sid, 1, runRow, claim, config(), Json.read("{\"message\":\"hello\"}"), actor, "engine-h4"));
        var events = new SessionStore(store).read(sid, 0, 0);
        var last = events.get(events.size() - 1);
        assertEquals("turn/end", last.type());
        assertEquals("interrupted", last.data().path("reason").asText());
        assertEquals(1, inboxRows(sid), "失败轮次不消费输入——租约过期后重投幂等");
    }

    private int inboxRows(String sid) {
        Integer n =
                db.queryForObject(
                        "select count(*) from engine.agent_inbox where session_id=?", Integer.class, sid);
        return n == null ? 0 : n;
    }
}
