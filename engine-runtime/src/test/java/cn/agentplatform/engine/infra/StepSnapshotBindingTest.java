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
import java.util.function.Consumer;

/**
 * H1-7（台账 §7.2）：步骤工具快照绑定——采纳集随 D2 落库（H1-5 已交），重启从原
 * request/header 恢复工具面，实时授权复核<b>只收窄、不放宽</b>。
 *
 * <p>三场景（主方案 §5.3，验收=快照共用+只收窄）：
 * <ol>
 *   <li><b>恢复收窄</b>：第一次 drive 落 header（原采纳集两条）；人为改小 config.tools
 *       （模拟撤权后收窄）→ 新 attempt 重新 drive 同 node → 本轮 header.tools 与收窄集
 *       一致，模型桩只见收窄后的 definitions，收窄原因随 header.snapshotBinding 落库；</li>
 *   <li><b>放宽拒绝</b>：原集为空、当前集多出工具 → 实际派发工具集仍为原集（以原集
 *       覆盖），模型桩收到的 toolDefinitions 与原集一致；</li>
 *   <li><b>撤权拒绝</b>：config.tools 的 contractDigest 与冻结快照不一致 → 结构化拒绝
 *       （TOOL_NOT_DECLARED），零出站、零登记。</li>
 * </ol>
 * 断言手段：模型桩 chatStream 收到的 body 中 toolDefinitions（ContextAssembler 装配产物），
 * 照 AgentD2RequestPersistTest 桩风格；夹具 h7_% 前缀 / tenant engine-test，双向清理照
 * ChatTurnEventLinkageTest。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class StepSnapshotBindingTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final String DIGEST_A = Digest.sha256("fixture-contract-a");
    static final String DIGEST_B = Digest.sha256("fixture-contract-b");

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

    /** 双向清理照 ChatTurnEventLinkageTest：@BeforeEach+@AfterEach 同一方法，h7_% 前缀隔离。 */
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
        db.update("delete from engine.session_event where session_id like 'h7_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h7_%'");
    }

    String session() {
        String id = "h7_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h7-app','{}')",
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

    /** 冻结快照词表基线：lookup@42(A) + transform@43(B)，带模型依赖与可选会话关联。 */
    long run(String sessionId) {
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
                                                        DIGEST_A),
                                                Map.of(
                                                        "code",
                                                        "transform",
                                                        "name",
                                                        "transform",
                                                        "skillVersionId",
                                                        43,
                                                        "contractDigest",
                                                        DIGEST_B))),
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
        return db.queryForObject(
                "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                    + " values(?,'h7-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}',?::jsonb,'{}','STEP')"
                    + " returning id",
                Long.class,
                "r_h7_" + UUID.randomUUID(),
                Json.write(snapshot));
    }

    private Map<String, Object> tool(String code, long version, String digest) {
        return Map.of(
                "code",
                code,
                "name",
                code,
                "skillVersionId",
                version,
                "contractDigest",
                digest,
                "definition",
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of("name", code, "parameters", Map.of("type", "object"))));
    }

    JsonNode config(List<Map<String, Object>> tools) {
        var root = Json.object();
        root.put("modelEntryId", 1);
        root.set("tools", Json.tree(tools));
        return root;
    }

    JsonNode inputs() {
        return Json.read("{\"message\":\"hello-h7\"}");
    }

    /** 终文本桩：每轮记录 chatStream body（toolDefinitions 断言面），技能路径计数即拒绝。 */
    RuntimeClients terminalStub(List<JsonNode> bodies, AtomicInteger skillOutbound) {
        return new RuntimeClients() {
            @Override
            public JsonNode chatStream(Object body, Actor a, Consumer<String> chunk) {
                return post("model", "/internal/model/chat/stream", body, a);
            }

            @Override
            public JsonNode post(String service, String path, Object body, Actor a) {
                if (path.equals("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":100,\"contextWindow\":10000,"
                                + "\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                if (path.equals("/internal/model/chat/stream")) {
                    bodies.add(Json.tree(body));
                    return Json.read(
                            "{\"text\":\"ok-h7\",\"toolCalls\":[],"
                                + "\"usage\":{\"promptTokens\":10,\"completionTokens\":3,\"totalTokens\":13},"
                                + "\"actualModel\":\"fixture\"}");
                }
                skillOutbound.incrementAndGet();
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    /** 二轮桩：第一次按 scriptedAnswers 依次回（toolCalls 或终文本），其后终文本。 */
    RuntimeClients scriptedStub(List<JsonNode> bodies, AtomicInteger skillOutbound, String... answers) {
        var chats = new AtomicInteger();
        var fallback = terminalStub(bodies, skillOutbound);
        return new RuntimeClients() {
            @Override
            public JsonNode chatStream(Object body, Actor a, Consumer<String> chunk) {
                int round = chats.getAndIncrement();
                if (round < answers.length) {
                    bodies.add(Json.tree(body));
                    return Json.read(answers[round]);
                }
                return fallback.chatStream(body, a, chunk);
            }

            @Override
            public JsonNode post(String service, String path, Object body, Actor a) {
                return fallback.post(service, path, body, a);
            }
        };
    }

    /** 同节点重新认领（模拟重启恢复的新 attempt）：先把上一轮租约过期。 */
    Map<String, Object> reclaim(long n, String owner) {
        db.update(
                "update engine.node_instance set lease_until=now()-interval '1 minute' where id=?",
                n);
        return store.claim(n, owner);
    }

    /**
     * 模拟"重启后需重新咨询模型"的窗口：清除该节点的模型调用行（响应与冻结请求）。
     * 引擎持久回放语义（ModelExchange.prepare/invoke）：已有响应的轮次直接复放、不再出流，
     * 模型桩自然收不到新请求；清行后恢复步骤重新解析并出流——这正是 H1-7 快照绑定生效的
     * 场景（request/header 原清单仍在会话事件里，恢复只收窄）。
     */
    void wipeModelInvocations(long n) {
        db.update("delete from engine.model_invocation where node_instance_id=?", n);
    }

    /** 某 attempt 首个 request/header 的 tools 采纳集（按 config.attemptId 定位）。 */
    JsonNode headerTools(String sid, long attemptId) {
        String data =
                db.queryForObject(
                        "select data::text from engine.session_event where session_id=?"
                            + " and type='request/header' and data->'config'->>'attemptId'=?"
                            + " order by seq limit 1",
                        String.class,
                        sid,
                        String.valueOf(attemptId));
        return Json.read(data, JsonNode.class).path("tools");
    }

    JsonNode headerBinding(String sid, long attemptId) {
        String data =
                db.queryForObject(
                        "select data::text from engine.session_event where session_id=?"
                            + " and type='request/header' and data->'config'->>'attemptId'=?"
                            + " order by seq limit 1",
                        String.class,
                        sid,
                        String.valueOf(attemptId));
        return Json.read(data, JsonNode.class).path("snapshotBinding");
    }

    @Test
    void resumedStepAppliesNarrowedSurfaceFromOriginalHeader() {
        String sid = session();
        long r = run(sid);
        long n = node(r);
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var bodies1 = new ArrayList<JsonNode>();
        var outbound1 = new AtomicInteger();
        var driver1 =
                new AgentDriver(store, terminalStub(bodies1, outbound1), new ContextAssembler());

        // attempt 1：双工具解析，落原采纳集 header
        var lease1 = store.claim(n, "w1");
        assertNotNull(lease1);
        driver1.drive(
                runRow,
                lease1,
                config(List.of(tool("lookup", 42, DIGEST_A), tool("transform", 43, DIGEST_B))),
                inputs(),
                actor,
                false);
        long attempt1 = TraceStore.number(lease1, "attemptId");
        assertEquals(2, headerTools(sid, attempt1).size(), "原采纳集两条随 D2 落库");
        assertEquals(2, bodies1.get(0).path("toolDefinitions").size());

        // 模拟撤权后收窄：config.tools 改小为仅 lookup，新 attempt 重启恢复同 node
        wipeModelInvocations(n);
        var lease2 = reclaim(n, "w2");
        assertNotNull(lease2, "重启恢复必须产生新 attempt");
        long attempt2 = TraceStore.number(lease2, "attemptId");
        assertNotEquals(attempt1, attempt2);
        var bodies2 = new ArrayList<JsonNode>();
        var outbound2 = new AtomicInteger();
        var driver2 =
                new AgentDriver(store, terminalStub(bodies2, outbound2), new ContextAssembler());
        driver2.drive(
                runRow,
                lease2,
                config(List.of(tool("lookup", 42, DIGEST_A))),
                inputs(),
                actor,
                false);

        // 只收窄生效：本轮 header.tools 与收窄集一致，模型只见收窄后的 definitions
        var tools2 = headerTools(sid, attempt2);
        assertEquals(1, tools2.size(), "本轮采纳集必须是收窄集");
        assertEquals("lookup", tools2.get(0).path("toolId").asText());
        assertEquals(42, tools2.get(0).path("versionRef").asInt());
        assertEquals(DIGEST_A, tools2.get(0).path("schemaDigest").asText());
        var definitions = bodies2.get(0).path("toolDefinitions");
        assertEquals(1, definitions.size(), "模型桩只见到收窄后的 definitions");
        assertEquals("lookup", definitions.get(0).path("function").path("name").asText());
        // 收窄原因随 header 落库（工具不可用）
        var binding = headerBinding(sid, attempt2);
        assertTrue(binding.path("recovery").asBoolean(), "恢复绑定必须被记录");
        assertEquals("request/header", binding.path("source").asText());
        assertEquals(attempt1, binding.path("originalAttempt").asLong());
        assertEquals("transform", binding.path("narrowed").get(0).asText(), "收窄原因记录被移除的工具");
    }

    @Test
    void resumedStepRejectsWideningAndKeepsOriginalSurface() {
        String sid = session();
        long r = run(sid);
        long n = node(r);
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var bodies1 = new ArrayList<JsonNode>();
        var driver1 =
                new AgentDriver(store, terminalStub(bodies1, new AtomicInteger()), new ContextAssembler());

        // attempt 1：config 无工具 → 原采纳集为空
        var lease1 = store.claim(n, "w1");
        driver1.drive(runRow, lease1, config(List.of()), inputs(), actor, false);
        long attempt1 = TraceStore.number(lease1, "attemptId");
        assertEquals(0, headerTools(sid, attempt1).size(), "原采纳集为空");

        // 反向：当前集多出工具（模拟放宽），重启恢复同 node
        wipeModelInvocations(n);
        var lease2 = reclaim(n, "w2");
        long attempt2 = TraceStore.number(lease2, "attemptId");
        var bodies2 = new ArrayList<JsonNode>();
        var driver2 =
                new AgentDriver(store, terminalStub(bodies2, new AtomicInteger()), new ContextAssembler());
        driver2.drive(
                runRow,
                lease2,
                config(List.of(tool("lookup", 42, DIGEST_A))),
                inputs(),
                actor,
                false);

        // 放宽不生效：以原集覆盖——派发工具集仍为原集（空）
        assertEquals(
                0,
                bodies2.get(0).path("toolDefinitions").size(),
                "模型桩收到的 toolDefinitions 必须与原集一致（空）");
        assertEquals(0, headerTools(sid, attempt2).size(), "本轮采纳集仍为原集");
        var binding = headerBinding(sid, attempt2);
        assertTrue(binding.path("recovery").asBoolean());
        assertEquals(
                "lookup",
                binding.path("restored").get(0).asText(),
                "超出原集的工具被剔除并记录");
    }

    @Test
    void revokedToolCallIsRejectedStructuredWithoutOutbound() {
        String sid = session();
        long r = run(sid);
        long n = node(r);
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var lease = store.claim(n, "w1");
        var bodies = new ArrayList<JsonNode>();
        var skillOutbound = new AtomicInteger();
        // config.tools 的 lookup 契约摘要与冻结快照不一致（解析后撤权/换契约）
        var rotated = config(List.of(tool("lookup", 42, Digest.sha256("rotated-contract"))));
        var clients =
                scriptedStub(
                        bodies,
                        skillOutbound,
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"lookup\","
                            + "\"arguments\":{\"x\":1}}],\"usage\":{},\"actualModel\":\"fixture\"}");
        var driver = new AgentDriver(store, clients, new ContextAssembler());

        // 撤权拒绝不终止 turn：错误作为工具结果交模型，模型改道终文本
        var result = driver.drive(runRow, lease, rotated, inputs(), actor, false);
        assertEquals("ok-h7", result.path("text").asText(), "结构化拒绝交模型后应正常收敛");
        assertEquals(0, skillOutbound.get(), "撤权工具不得出站");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.tool_call where run_id=?", Integer.class, r),
                "零登记（未分发即拒绝）");
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type='tool/call'",
                        Integer.class,
                        sid),
                "零调用事件");
        // 拒绝原因进入模型可见工具结果（下一轮请求消息内）
        var toolMessages = new ArrayList<JsonNode>();
        bodies.get(1)
                .path("messages")
                .forEach(
                        m -> {
                            if ("tool".equals(m.path("role").asText())) toolMessages.add(m);
                        });
        assertEquals(1, toolMessages.size());
        String content = toolMessages.get(0).path("content").asText();
        assertTrue(content.contains("TOOL_NOT_DECLARED"), "结构化拒绝必须带语义码");
        assertTrue(content.contains("已撤权"), "拒绝必须记录原因（工具不可用）");
    }
}
