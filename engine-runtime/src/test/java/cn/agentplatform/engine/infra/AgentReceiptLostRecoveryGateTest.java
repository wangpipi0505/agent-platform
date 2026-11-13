package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.AgentDriver;
import cn.agentplatform.engine.application.ContextAssembler;
import cn.agentplatform.engine.domain.RuntimeFault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClientException;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H1-0（门 M1-①，台账 §2.2/§6 阶段 1）：现有 AgentDriver 不改架构跑通"可恢复会话+工具调用"，
 * 注入"外部受理后回执丢失"故障，恢复后<b>外部受理次数恒 1</b>。
 *
 * <p>场景契约：contracts/oracle-replay-adapter.md §4.4 的 P0 fixture
 * {@code example-recover-tool-call.json}（外部受理后回执丢失→重启恢复，期望 externalAcceptCount=1、
 * 补写/采纳结果不表述失败）。判据与主方案 §5.8 四态对账一致：已受理→只查询不重发。
 *
 * <p>全链组成（复用既有机制，零架构改动）：
 * <ol>
 *   <li>模型桩第 1 次返回工具调用；AgentDriver.skill 登记（REGISTERED）→ 技能服务桩认领+提交
 *       （SUBMITTED）→ 外部系统受理（受理计数=1）→ 注入传输中断（回执丢失）；
 *   <li>skill 捕获 RestClientException 后按权威状态判定 SUBMITTED → RuntimeFault(RESULT_UNKNOWN)；
 *   <li>与调度器同语义地把 run/node 置 WAITING_EXTERNAL 并令租约过期
 *       （同 ExecutionRecoveryTest.interrupted 的故障收口）；</li>
 *   <li>ExecutionRecovery.recover() 候选命中，GET /internal/skill-facts/{id} 受控查询采纳事实
 *       （只查询，不出站重发）→ tool_call SUCCEEDED + EXTERNAL_FACT_ADOPTED；</li>
 *   <li>恢复驱动：round0 的模型响应由 model_invocation 持久化重放（D2 语义，零重复模型请求），
 *       其工具调用经 register 幂等命中 SUCCEEDED 既有事实直接消费（出站桩若再被触达即失败）；
 *       round1 续接轮模型返回终文本 → 驱动正常终态。</li>
 * </ol>
 * 门判定：{@code accepts == 1}（受理次数恒 1）、{@code factQueries == 1}（恢复只查询）、
 * 驱动终态文本与事件证据齐备。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class AgentReceiptLostRecoveryGateTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final String CONTRACT = Digest.sha256("fixture-contract");
    static final String FINAL_TEXT = "done-after-recovered-tool";

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
    void clean() {
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                    + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                    + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
    }

    /** 运行夹具：冻结词表（lookup@42+合同摘要）+ 模型绑定依赖 + DEBUG 触发。 */
    long run() {
        long id =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'test','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}','{}','{}','STEP')"
                            + " returning id",
                        Long.class,
                        "r_h10_" + UUID.randomUUID());
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
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
                                                                        Json.canonical(
                                                                                modelEntry)))))))),
                id);
        return id;
    }

    long node(long r) {
        return db.queryForObject(
                "insert into"
                    + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE',?,'{\"type\":\"CODE\",\"expression\":\"1\"}')"
                    + " returning id",
                Long.class,
                r,
                "READY");
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

    /** 技能事实桩：按 adopt() 的校验链构造 proof（摘要覆盖去掉 factSha256 后的规范体）。 */
    ObjectNode proof(long callId, long nid, String runPublicId, long attemptId) {
        var body = Json.object();
        body.put("factId", "ext-9001");
        body.put("callId", callId);
        body.put("nodeInstanceId", nid);
        body.put("runId", runPublicId);
        body.put("skillVersionId", 42);
        body.put("argsDigest", Digest.sha256(Json.canonical(Map.of("x", 1))));
        body.put("skillContractSha256", CONTRACT);
        body.put("attemptId", attemptId);
        body.put("callKind", "DEBUG");
        body.put("isMock", false);
        body.put("status", "SUCCEEDED");
        body.put("durationMs", 13);
        body.set("result", Json.tree(Map.of("structured", Map.of("value", 42))));
        body.put("factSha256", Digest.sha256(Json.canonical(body)));
        return body;
    }

    @Test
    void receiptLostAfterExternalAcceptRecoversWithSingleAcceptance() {
        long r = run(), n = node(r);
        var first = store.claim(n, "workerA");
        Map<String, Object> runRow = db.queryForMap("select * from engine.run where id=?", r);
        String runPublicId =
                db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);

        AtomicInteger accepts = new AtomicInteger(); // 外部受理计数——门判据
        AtomicInteger factQueries = new AtomicInteger(); // 恢复期受控查询计数
        AtomicInteger modelChats = new AtomicInteger(); // 模型请求序（第1次=工具调用，其后=终文本）
        Queue<Long> dispatched = new ConcurrentLinkedQueue<>();

        RuntimeClients clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode chatStream(
                            Object body, Actor a, java.util.function.Consumer<String> chunk) {
                        return post("model", "/internal/model/chat/stream", body, a);
                    }

                    @Override
                    public JsonNode post(String service, String path, Object body, Actor a) {
                        if (path.equals("/internal/model/measure"))
                            return Json.read(
                                    "{\"inputTokens\":100,\"contextWindow\":10000,"
                                        + "\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                        if (path.equals("/internal/model/chat/stream")) {
                            if (modelChats.incrementAndGet() == 1)
                                return Json.read(
                                        "{\"text\":\"需要查询数据\",\"toolCalls\":[{\"id\":\"call-1\","
                                            + "\"name\":\"lookup\",\"arguments\":{\"x\":1}}],"
                                            + "\"usage\":{\"promptTokens\":10,\"completionTokens\":5,\"totalTokens\":15},"
                                            + "\"actualModel\":\"fixture\"}");
                            return Json.read(
                                    "{\"text\":\""
                                        + FINAL_TEXT
                                        + "\",\"toolCalls\":[],"
                                        + "\"usage\":{\"promptTokens\":10,\"completionTokens\":3,\"totalTokens\":13},"
                                        + "\"actualModel\":\"fixture\"}");
                        }
                        if (path.equals("/internal/skills/lookup/execute")) {
                            // 技能服务缝：认领+提交（SUBMITTED）→ 外部受理恰好一次 → 回执丢失。
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
                            assertEquals(1, accepts.incrementAndGet(), "外部受理必须恰好一次");
                            dispatched.add(callId);
                            throw new RestClientException("receipt lost after external accept");
                        }
                        throw new AssertionError("意外出站: " + service + path);
                    }

                    @Override
                    public JsonNode get(String service, String path, Actor a) {
                        // 恢复受控查询：GET /internal/skill-facts/{id}——只读原外部任务，不重发。
                        if (path.startsWith("/internal/skill-facts/")) {
                            factQueries.incrementAndGet();
                            long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                            long attemptId =
                                    db.queryForObject(
                                            "select attempt_id from engine.tool_call where id=?",
                                            Long.class,
                                            id);
                            return proof(id, n, runPublicId, attemptId);
                        }
                        throw new AssertionError("意外读取: " + service + path);
                    }
                };

        var driver = new AgentDriver(store, clients, new ContextAssembler());

        // 第一次驱动：模型回工具调用 → 受理 → 回执丢失 → RESULT_UNKNOWN 状态机信号。
        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () ->
                                driver.drive(
                                        runRow,
                                        first,
                                        config(),
                                        Json.read("{\"message\":\"hello\"}"),
                                        actor,
                                        false));
        assertEquals("RESULT_UNKNOWN", fault.semantic);
        long callId = dispatched.poll();
        assertEquals(
                "SUBMITTED",
                db.queryForObject("select status from engine.tool_call where id=?", String.class, callId));

        // 故障收口（与调度器同语义，ExecutionRecoveryTest.interrupted 同款）：等待外部 + 租约过期。
        db.update(
                "update engine.node_instance set"
                    + " status='WAITING_EXTERNAL',lease_until=now()-interval '1 minute' where id=?",
                n);
        db.update(
                "update engine.tool_call set claimed_lease=now()-interval '1 minute' where id=?",
                callId);
        db.update("update engine.run set status='WAITING_EXTERNAL' where id=?", r);

        // 恢复：只查询采纳事实，不出站重发。
        new ExecutionRecovery(store, new ToolCalls(store), clients).recover();
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "select status from engine.tool_call where id=?", String.class, callId));
        assertEquals(1, factQueries.get(), "恢复必须只经受控查询");
        assertEquals(1, accepts.get(), "受理次数恒 1（恢复不得重发）");
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                            + " type='EXTERNAL_FACT_ADOPTED'",
                        Integer.class,
                        r));

        // 恢复驱动：round0 模型响应由 model_invocation 持久化重放（D2：崩溃后从日志重建模型
        // 所见，不重复模型请求）→ 其中工具调用经 register 幂等命中 SUCCEEDED 既有事实，
        // 零出站消费 → round1 才是真正的新模型请求（桩返回终文本）→ 驱动终态。
        var second = store.claim(n, "workerB");
        Map<String, Object> recoveredRun =
                db.queryForMap("select * from engine.run where id=?", r);
        var result =
                driver.drive(
                        recoveredRun,
                        second,
                        config(),
                        Json.read("{\"message\":\"hello\"}"),
                        actor,
                        false);
        assertEquals(FINAL_TEXT, result.path("text").asText());
        assertEquals(2, modelChats.get(), "模型请求=首轮1次+恢复续接轮1次（round0 重放零请求）");
        assertEquals(1, accepts.get(), "终判：外部受理次数恒 1");
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.tool_call where run_id=?", Integer.class, r));
    }
}
