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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H1-5（台账 §7.2）：D2 请求材料先落库——"模型可见即已记录"。
 *
 * <p>主方案 §5.1：最终请求材料（系统提示、消息序、工具 Schema、模型与参数）先持久化成功，
 * 再发送模型请求；崩溃后可从日志完整重建模型看到了什么。实现：AgentDriver.drive 在出流前对
 * 有会话关联的运行写 session_event（request/header 含工具采纳集 + request/context）。
 *
 * <p>判定：模型桩在 chatStream 被调时断言库中已存在该步的 header+context（时序=先落库后出流）；
 * 采纳集与冻结词表一致；独立运行（无 chatSession）不产生会话事件。
 * 边界：request/context 的 provider/contextWindow 字段待 H1-9/measure 接入后补全（本卡落
 * modelEntryId/modelBindingRef/invocationRef——重建所需键已齐）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class AgentD2RequestPersistTest {

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
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h5_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h5_%'");
    }

    String session() {
        String id = "h5_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h5-app','{}')",
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

    /** 运行夹具：冻结词表（lookup@42+合同摘要）+ 模型依赖 + 可选会话关联。 */
    long run(String sessionId) {
        long id =
                db.queryForObject(
                        "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'h5-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}',?::jsonb,'{}','STEP')"
                            + " returning id",
                        Long.class,
                        "r_h5_" + UUID.randomUUID(),
                        Json.write(snapshot(sessionId)));
        return id;
    }

    private Map<String, Object> snapshot(String sessionId) {
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        var snapshot =
                new LinkedHashMap<String, Object>(
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
        if (sessionId != null)
            snapshot.put("chatSession", Map.of("sessionId", sessionId, "turnNo", 1));
        return snapshot;
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

    @Test
    void requestMaterialPersistsBeforeModelDispatchAndCarriesAdoptedTools() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        AtomicInteger modelCalls = new AtomicInteger();

        RuntimeClients clients =
                new RuntimeClients() {
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
                            // D2 时序判定：模型出流时，本轮请求材料必须已经落库
                            if (modelCalls.incrementAndGet() == 1) {
                                assertEquals(
                                        2L,
                                        sessionEventCount(sid),
                                        "模型请求前 request/header+request/context 必须已落库");
                            }
                            return Json.read(
                                    "{\"text\":\"ok-h5\",\"toolCalls\":[],"
                                        + "\"usage\":{\"promptTokens\":10,\"completionTokens\":3,\"totalTokens\":13},"
                                        + "\"actualModel\":\"fixture\"}");
                        }
                        throw new AssertionError("意外出站: " + service + path);
                    }
                };

        var driver = new AgentDriver(store, clients, new ContextAssembler());
        var result = driver.drive(runRow, claim, config(), Json.read("{\"message\":\"hello\"}"), actor, false);
        assertEquals("ok-h5", result.path("text").asText());

        // 采纳集与冻结词表一致
        var header =
                db.queryForMap(
                        "select data from engine.session_event where session_id=? and seq=1", sid);
        var tools = Json.read(String.valueOf(header.get("data")), JsonNode.class).path("tools");
        assertEquals(1, tools.size());
        assertEquals("lookup", tools.get(0).path("toolId").asText());
        assertEquals(42, tools.get(0).path("versionRef").asInt());
        assertEquals(CONTRACT, tools.get(0).path("schemaDigest").asText());
        // header.config 可重建模型所见（含 invocationRef 与消息序）
        var headerConfig = Json.read(String.valueOf(header.get("data")), JsonNode.class).path("config");
        assertTrue(headerConfig.has("invocationRef"), "请求材料必须可重建（含 invocationRef）");
        assertTrue(headerConfig.has("messages"), "请求材料必须含消息序");
        // request/context
        var context =
                db.queryForMap(
                        "select type,data from engine.session_event where session_id=? and seq=2", sid);
        assertEquals("request/context", context.get("type"));
        assertEquals(
                1,
                Json.read(String.valueOf(context.get("data")), JsonNode.class).path("modelEntryId").asInt());
        assertEquals(2, sessionEventCount(sid), "单轮恰好 header+context 两件");
    }

    /** 独立运行（无 chatSession）：不产生任何会话事件——事实仅保留运行链路。 */
    @Test
    void unlinkedRunWritesNoSessionEvents() {
        long r = run(null); // 无 chatSession
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        RuntimeClients clients =
                new RuntimeClients() {
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
                        if (path.equals("/internal/model/chat/stream"))
                            return Json.read(
                                    "{\"text\":\"solo\",\"toolCalls\":[],\"usage\":{},\"actualModel\":\"fixture\"}");
                        throw new AssertionError("意外出站: " + service + path);
                    }
                };
        var driver = new AgentDriver(store, clients, new ContextAssembler());
        driver.drive(runRow, claim, config(), Json.read("{\"message\":\"hello\"}"), actor, false);
        assertEquals(0, sessionEventCount(null), "独立运行零会话事件");
    }

    private long sessionEventCount(String sid) {
        // sid=null=本类前缀计数（库中其他测试类的 s_/h2_ 残留不属于本卡口径）
        Long n =
                sid == null
                        ? db.queryForObject(
                                "select count(*) from engine.session_event where session_id like 'h5_%'",
                                Long.class)
                        : db.queryForObject(
                                "select count(*) from engine.session_event where session_id=?",
                                Long.class,
                                sid);
        return n == null ? 0 : n;
    }
}
