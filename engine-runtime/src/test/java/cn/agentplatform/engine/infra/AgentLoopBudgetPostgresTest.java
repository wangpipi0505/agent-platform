package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

/**
 * R-A8 台账项回归：Agent 循环的"无进展指纹"与"深度预算"两个契约口径。
 *
 * <p>契约依据 contracts/trace-model.md：终止规则情形6——无进展 = 连续 N=3 轮
 * (skillId+参数hash) 指纹重复才熔断；预算——深度超限抛 BUDGET_DEPTH。此前实现
 * "一次重复即熔断"且 depth 无消费点，均与契约不符。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class AgentLoopBudgetPostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    Map<String, Object> skill() {
        return Map.of(
                "code", "lookup",
                "name", "lookup",
                "skillVersionId", 42,
                "contractDigest", Digest.sha256("fixture-contract"),
                "definition",
                        Map.of(
                                "type", "function",
                                "function", Map.of("name", "lookup", "parameters", Map.of("type", "object"))));
    }

    /** 构造可通过 FrozenModels 校验的最小运行快照，并返回可驱动的 run/node 视图。 */
    DrivenRun agentRun() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.tree(
                        Map.of(
                                "modelEntryId", 1,
                                "guard", Map.of("roles", java.util.List.of("EDITOR")),
                                "tools", java.util.List.of(skill())));
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode", "test",
                                "type", "CHAT",
                                "config", config,
                                "definition", Map.of("nodes", java.util.List.of(), "edges", java.util.List.of()),
                                "dependencyVersions",
                                        Map.of(
                                                "modelRefs",
                                                java.util.List.of(
                                                        Map.of(
                                                                "modelEntryId", 1,
                                                                "entry", entry,
                                                                "modelBindingRef",
                                                                        Map.of(
                                                                                "bindingId", 1,
                                                                                "sha256",
                                                                                        Digest.sha256(
                                                                                                Json.canonical(
                                                                                                        entry))))))));
        TracePostgresTest.db.update(
                "update engine.run set snapshot=?::jsonb where id=?", Json.write(snapshot), r);
        var node = TracePostgresTest.store.claim(n, "replica");
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", r);
        return new DrivenRun(run, node, config);
    }

    record DrivenRun(Map<String, Object> run, Map<String, Object> node, JsonNode config) {}

    /** 按脚本逐轮返回模型应答；技能执行恒定成功，度量按固定小值放行。 */
    RuntimeClients scripted(java.util.List<String> answers, java.util.concurrent.atomic.AtomicInteger used) {
        return new RuntimeClients() {
            @Override
            public JsonNode post(String service, String path, Object body, Actor actor) {
                if (path.endsWith("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":10,\"contextWindow\":100000,\"maxOutputTokens\":4096"
                                    + ",\"tokenizer\":\"fixture\",\"exact\":true}");
                return Json.read("{\"status\":\"SUCCEEDED\",\"result\":{\"value\":1}}");
            }

            @Override
            public JsonNode chatStream(
                    Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                return Json.read(answers.get(used.getAndIncrement()));
            }
        };
    }

    @Test
    void noProgressMeltsOnlyOnThirdConsecutiveIdenticalRound() {
        var driven = agentRun();
        // 三轮同技能同参数但调用 id 不同：指纹必须忽略 id，第三轮才熔断。
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}",
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc2\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}",
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc3\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}");
        var clients = scripted(answers, new java.util.concurrent.atomic.AtomicInteger());
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new AgentDriver(
                                                TracePostgresTest.store,
                                                clients,
                                                new ContextAssembler())
                                        .drive(
                                                driven.run(),
                                                driven.node(),
                                                driven.config(),
                                                Json.read("{}"),
                                                TracePostgresTest.actor,
                                                false));
        assertEquals("NO_PROGRESS", error.getMessage());
        // 前两轮已真实执行并被记账，熔断发生在第三轮指纹比对处。
        assertEquals(
                3,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.model_invocation where node_instance_id=?"
                                + " and kind='MAIN'",
                        Integer.class,
                        driven.node().get("id")));
    }

    @Test
    void twoConsecutiveRepeatsAndChangeRecoverToTerminalAnswer() {
        var driven = agentRun();
        // 两轮重复（合法自查）→ 参数变化重置计数 → 纯文本收口，全程不得熔断。
        var answers =
                java.util.List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}",
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc2\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}",
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc3\",\"name\":\"lookup\",\"arguments\":{\"x\":2}}],\"usage\":{}}",
                        "{\"text\":\"done\",\"usage\":{}}");
        var clients = scripted(answers, new java.util.concurrent.atomic.AtomicInteger());
        var terminal =
                new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler())
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        assertEquals("done", terminal.path("text").asText());
    }

    @Test
    void depthBudgetBlocksThirdAgentLoopEntry() {
        // FlowDriver 的 AGENT 分支按"进入子循环计 1"消费 depth（默认上限 2）；
        // 本用例直接验证该计账通道：第三次进入必须抛 BUDGET_DEPTH 且账面停在 2。
        long r = fixture.run();
        TracePostgresTest.store.consume(r, "depth", 1, 2);
        TracePostgresTest.store.consume(r, "depth", 1, 2);
        var error =
                assertThrows(
                        PlatformException.class,
                        () -> TracePostgresTest.store.consume(r, "depth", 1, 2));
        assertEquals("BUDGET_DEPTH", error.code());
        assertEquals(
                2,
                TracePostgresTest.db.queryForObject(
                        "select (budget->'consumed'->>'depth')::int from engine.run where id=?",
                        Integer.class,
                        r));
    }
}
