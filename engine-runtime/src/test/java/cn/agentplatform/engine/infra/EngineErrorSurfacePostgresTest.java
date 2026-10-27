package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAT-05 引擎侧：技能 envelope 错误在运行链路的统一（contracts/skill-invocation.md §11）。
 *
 * <p>七字段错误对象（+reason 兼容键）必须在 run.error（含节点/事实回退）、SSE（NODE_FAILED
 * 事件负载）与管理台运行详情（effectiveError/normalizedError）三处同构且含 traceId；
 * 显式 retryable=false 的失败不得进入引擎既有的只读重试；模型工具结果对"可解释拒绝"
 * （category=AUTH 等 retryable=false 且带 userMessage）必须携带 userMessage，而不是让
 * 运行带着裸语义失败。旧 {semantic,reason} 形态的事实向后兼容，不丢信息、不降级成
 * RUNTIME_ERROR。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class EngineErrorSurfacePostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void database() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    /** 契约 §11.1 的完整七字段错误对象（+reason），作为技能 envelope 的失败样例。 */
    static Map<String, Object> sevenFieldError(String traceId) {
        var error = new LinkedHashMap<String, Object>();
        error.put("semantic", "REMOTE_FAILED");
        error.put("code", "TLE_CHECKSUM_INVALID");
        error.put("category", "BUSINESS");
        error.put("retryable", false);
        error.put("userMessage", "外部任务失败：TLE 校验和错误");
        error.put("detail", Map.of("raw", "wrong checksum"));
        error.put("traceId", traceId);
        error.put("reason", "外部任务失败：TLE 校验和错误");
        return error;
    }

    record Fault(long run, long node, long call, ObjectNode proof) {}

    /** 与 ExecutionRecoveryTest 相同的"交付中断"夹具；proof 的终态与错误对象可参数化。 */
    Fault interrupted(Object result, String status) {
        long r = fixture.run(), n = fixture.node(r, "READY");
        String contract = Digest.sha256("frozen-contract");
        TracePostgresTest.db.update(
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
                                                        contract))))),
                r);
        var lease = TracePostgresTest.store.claim(n, "old");
        var req = fixture.call(r, n, lease);
        var calls = new ToolCalls(TracePostgresTest.store);
        long id =
                TraceStore.number(
                        calls.register(req.register(), TracePostgresTest.actor), "callId");
        calls.claim(id, req.claim(), TracePostgresTest.actor);
        calls.submitted(id, req.claim(), TracePostgresTest.actor);
        var p = Json.object();
        p.put("factId", "sf:" + id + ":" + lease.get("attemptId"));
        p.put("callId", id);
        p.put(
                "runId",
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r));
        p.put("nodeInstanceId", n);
        p.put("skillVersionId", 42);
        p.put("argsDigest", Digest.sha256(Json.canonical(Map.of("x", 1))));
        p.put("skillContractSha256", contract);
        p.put("attemptId", TraceStore.number(lease, "attemptId"));
        p.put("status", status);
        p.set("result", Json.tree(result));
        p.put("durationMs", 13);
        p.put("isMock", false);
        p.put("callKind", "DEBUG");
        p.put("factSha256", Digest.sha256(Json.canonical(p)));
        TracePostgresTest.db.update(
                "update engine.node_instance set"
                    + " status='WAITING_EXTERNAL',lease_until=now()-interval '1 minute' where id=?",
                n);
        TracePostgresTest.db.update(
                "update engine.tool_call set claimed_lease=now()-interval '1 minute' where id=?",
                id);
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_EXTERNAL' where id=?", r);
        return new Fault(r, n, id, p);
    }

    /** 采纳失败事实并把节点设为 SKILL 节点，返回可驱动的 RunService 与外部调用计数。 */
    record Adopted(long run, long node, long call, RunService runs, AtomicInteger external) {}

    Adopted adoptAndBuildDriver(Fault fault, boolean readonly, int retries) {
        var external = new AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return fault.proof();
                    }

                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        external.incrementAndGet();
                        return Json.read("{\"status\":\"UNKNOWN\"}");
                    }
                };
        new ExecutionRecovery(
                        TracePostgresTest.store, new ToolCalls(TracePostgresTest.store), clients)
                .recover();
        TracePostgresTest.db.update(
                "update engine.node_instance set node_type='SKILL',definition=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "type",
                                "SKILL",
                                "args",
                                Map.of("x", 1),
                                "errorPolicy",
                                Map.of("retries", retries),
                                "skillRef",
                                Map.of(
                                        "code",
                                        "lookup",
                                        "skillVersionId",
                                        42,
                                        "execution",
                                        Map.of("readonly", readonly)))),
                fault.node());
        var agent =
                new AgentDriver(
                        TracePostgresTest.store, clients, new ContextAssembler());
        var runs =
                new RunService(
                        TracePostgresTest.store,
                        clients,
                        new FlowDriver(TracePostgresTest.store, agent),
                        agent);
        return new Adopted(fault.run(), fault.node(), fault.call(), runs, external);
    }

    static void assertSevenFields(JsonNode error, String traceId) {
        assertEquals("REMOTE_FAILED", error.path("semantic").asText());
        assertEquals("TLE_CHECKSUM_INVALID", error.path("code").asText());
        assertEquals("BUSINESS", error.path("category").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertEquals("外部任务失败：TLE 校验和错误", error.path("userMessage").asText());
        assertEquals("wrong checksum", error.path("detail").path("raw").asText());
        assertEquals(traceId, error.path("traceId").asText());
        assertEquals("外部任务失败：TLE 校验和错误", error.path("reason").asText());
    }

    @Test
    void sevenFieldErrorIsIdenticalAcrossNodeAttemptEventAndRunDetail() {
        var adopted =
                adoptAndBuildDriver(
                        interrupted(
                                Map.of("error", sevenFieldError("trace-e2e-1")), "FAILED"),
                        false,
                        0);
        adopted.runs().drive(adopted.run(), TracePostgresTest.actor);
        assertEquals(
                0, adopted.external().get(), "已知失败的执行事实不得再次外发执行");
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, adopted.run()));

        // 表面一：节点错误（run.error 经 effectiveError 回退到它）。
        assertSevenFields(
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select error::text from engine.node_instance where id=?",
                                String.class,
                                adopted.node())),
                "trace-e2e-1");
        // 表面二：执行代次错误。
        assertSevenFields(
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select error::text from engine.attempt where"
                                    + " node_instance_id=? order by id desc limit 1",
                                String.class,
                                adopted.node())),
                "trace-e2e-1");
        // 表面三：SSE 事件负载（NODE_FAILED 原样进 SSE）。
        var failedEvents =
                TracePostgresTest.db.queryForList(
                        "select payload::text from engine.run_event where run_id=? and"
                            + " type='NODE_FAILED'",
                        String.class,
                        adopted.run());
        assertEquals(1, failedEvents.size());
        assertSevenFields(Json.read(failedEvents.getFirst()).path("error"), "trace-e2e-1");

        // 表面四：管理台运行详情（effectiveError → normalizedError）。
        var run = TracePostgresTest.db.queryForMap("select * from engine.run where id=?", adopted.run());
        var nodes = TracePostgresTest.store.nodes(adopted.run());
        var events = TracePostgresTest.store.events(adopted.run(), 0);
        var error = RunService.effectiveError(run, nodes, events);
        assertSevenFields(error, "trace-e2e-1");
        var normalized = RunService.normalizedError(run, nodes, List.of(), error);
        assertNotNull(normalized);
        assertEquals("REMOTE_FAILED", normalized.get("semantic"));
        assertEquals("TLE_CHECKSUM_INVALID", normalized.get("errorCode"));
        assertEquals("BUSINESS", normalized.get("category"));
        assertEquals("外部任务失败：TLE 校验和错误", normalized.get("userMessage"));
        assertEquals("trace-e2e-1", normalized.get("traceId"));
        assertEquals(false, normalized.get("retryable"));
    }

    @Test
    void explicitRetryableFalseBlocksReadonlyRetry() {
        var error = new LinkedHashMap<String, Object>();
        error.put("semantic", "RETRYABLE_FAILURE");
        error.put("retryable", false);
        error.put("userMessage", "外部任务失败：参数校验不通过");
        error.put("traceId", "trace-nr-1");
        error.put("reason", "远端声明不可重试");
        var adopted =
                adoptAndBuildDriver(
                        interrupted(Map.of("error", error), "FAILED"), true, 1);
        adopted.runs().drive(adopted.run(), TracePostgresTest.actor);
        assertEquals(
                0, adopted.external().get(), "不可重试的失败事实不得再次外发执行");
        // 既有重试判据（semantic=RETRYABLE_FAILURE + readonly + 次数未满）必须同时尊重显式
        // retryable=false：工具调用保持 FAILED，不回到 REGISTERED，也不累计重试次数。
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        adopted.call()));
        assertEquals(
                0,
                TracePostgresTest.db.queryForObject(
                        "select readonly_retry_count from engine.tool_call where id=?",
                        Integer.class,
                        adopted.call()));
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, adopted.run()));
        assertEquals(
                "外部任务失败：参数校验不通过",
                Json.read(
                                TracePostgresTest.db.queryForObject(
                                        "select error::text from engine.node_instance where id=?",
                                        String.class,
                                        adopted.node()))
                        .path("userMessage")
                        .asText());
    }

    @Test
    void legacySemanticReasonFactKeepsSemanticAndReasonWithoutDegradation() {
        var adopted =
                adoptAndBuildDriver(
                        interrupted(
                                Map.of(
                                        "error",
                                        Map.of(
                                                "semantic",
                                                "NO_PERMISSION",
                                                "reason",
                                                "无权访问其他部门数据")),
                                "FAILED"),
                        false,
                        0);
        adopted.runs().drive(adopted.run(), TracePostgresTest.actor);
        assertEquals(
                0, adopted.external().get(), "已知失败的执行事实不得再次外发执行");
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, adopted.run()));
        var nodeError =
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select error::text from engine.node_instance where id=?",
                                String.class,
                                adopted.node()));
        // 旧形态不得被降级成 RUNTIME_ERROR，诊断原文 reason 必须原样保留。
        assertEquals("NO_PERMISSION", nodeError.path("semantic").asText());
        assertEquals("无权访问其他部门数据", nodeError.path("reason").asText());
    }

    @Test
    void modelToolResultCarriesUserMessageForExplainableRejection() {
        var driven = agentRun();
        var answers =
                List.of(
                        "{\"text\":\"\",\"toolCalls\":[{\"id\":\"tc1\",\"name\":\"lookup\",\"arguments\":{\"x\":1}}],\"usage\":{}}",
                        "{\"text\":\"已拒绝：无权访问其他部门数据\",\"usage\":{}}");
        var used = new AtomicInteger();
        var executes = new AtomicInteger();
        var error = new LinkedHashMap<String, Object>();
        error.put("semantic", "NO_PERMISSION");
        error.put("code", "DATA_SCOPE_DENIED");
        error.put("category", "AUTH");
        error.put("retryable", false);
        error.put("userMessage", "无权访问其他部门数据");
        error.put("detail", Map.of("raw", "403"));
        error.put("traceId", "trace-chat-1");
        error.put("reason", "无权访问其他部门数据");
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        if (path.endsWith("/internal/model/measure"))
                            return Json.read(
                                    "{\"inputTokens\":10,\"contextWindow\":100000,\"maxOutputTokens\":4096"
                                        + ",\"tokenizer\":\"fixture\",\"exact\":true}");
                        if (path.endsWith("/internal/skills/lookup/execute")
                                && executes.incrementAndGet() == 1)
                            return Json.tree(
                                    Map.of(
                                            "status",
                                            "FAILED",
                                            "result",
                                            Map.of("error", error),
                                            "modelSummary",
                                            "无权访问其他部门数据"));
                        return Json.read("{\"status\":\"SUCCEEDED\",\"result\":{\"value\":1}}");
                    }

                    @Override
                    public JsonNode chatStream(
                            Object body, Actor actor, java.util.function.Consumer<String> chunk) {
                        return Json.read(answers.get(used.getAndIncrement()));
                    }
                };
        var terminal =
                new AgentDriver(TracePostgresTest.store, clients, new ContextAssembler())
                        .drive(
                                driven.run(),
                                driven.node(),
                                driven.config(),
                                Json.read("{}"),
                                TracePostgresTest.actor,
                                false);
        // 可解释拒绝进入模型上下文后，模型能收口成解释性回答，而不是让运行带着裸 403 崩溃。
        assertEquals("已拒绝：无权访问其他部门数据", terminal.path("text").asText());
        assertEquals(1, executes.get());
        var toolMessages =
                TracePostgresTest.db.queryForList(
                        "select source_request::text from engine.model_invocation where"
                            + " node_instance_id=? and kind='MAIN' order by id",
                        String.class,
                        driven.node().get("id"));
        assertEquals(2, toolMessages.size());
        JsonNode toolMessage = null;
        for (JsonNode message : Json.read(toolMessages.get(1)).path("messages"))
            if (message.path("role").asText().equals("tool")
                    && message.path("tool_call_id").asText().equals("tc1"))
                toolMessage = message;
        assertNotNull(toolMessage, "第二轮模型请求必须携带第一轮的工具结果");
        String content = toolMessage.path("content").asText();
        assertTrue(content.contains("无权访问其他部门数据"), "工具结果必须携带 userMessage：" + content);
        assertTrue(content.contains("DATA_SCOPE_DENIED"), "工具结果必须携带远端业务码：" + content);
        assertTrue(content.contains("trace-chat-1"), "工具结果必须携带 traceId：" + content);
    }

    // ---- 以下为 AgentLoopBudgetPostgresTest 的最小可驱动 Agent 夹具复制 ----

    record DrivenRun(Map<String, Object> run, Map<String, Object> node, JsonNode config) {}

    DrivenRun agentRun() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var entry = Json.read("{\"modelEntryId\":1}");
        var config =
                Json.tree(
                        Map.of(
                                "modelEntryId", 1,
                                "guard", Map.of("roles", List.of("EDITOR")),
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
                                                        Digest.sha256("fixture-contract"),
                                                        "definition",
                                                        Map.of(
                                                                "type",
                                                                "function",
                                                                "function",
                                                                Map.of(
                                                                        "name",
                                                                        "lookup",
                                                                        "parameters",
                                                                        Map.of("type", "object")))))));
        var snapshot =
                Json.tree(
                        Map.of(
                                "appCode", "test",
                                "type", "CHAT",
                                "config", config,
                                "definition", Map.of("nodes", List.of(), "edges", List.of()),
                                "dependencyVersions",
                                        Map.of(
                                                "modelRefs",
                                                List.of(
                                                        Map.of(
                                                                "modelEntryId",
                                                                1,
                                                                "entry",
                                                                entry,
                                                                "modelBindingRef",
                                                                Map.of(
                                                                        "bindingId",
                                                                        1,
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
}
