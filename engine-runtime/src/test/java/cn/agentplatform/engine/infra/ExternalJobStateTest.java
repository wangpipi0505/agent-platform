package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

/**
 * PLAT-04：外部任务态在引擎侧的可观测行为。
 *
 * <p>只验证 PG 事实上发生的变化，不 mock 存储层：external_job 落库、GET 投影、等待态
 * run.error 结构、采纳终态与回调唤醒时快照的清理。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ExternalJobStateTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void database() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    record Submitted(
            long run, long node, long call, long attempt, Map<String, Object> lease) {}

    static Map<String, Object> snapshot(String lastPolledAt) {
        var job = new LinkedHashMap<String, Object>();
        job.put("remoteJobId", "job-77");
        job.put("state", "RUNNING");
        job.put("submittedAt", "2026-10-27T09:00:00Z");
        job.put("lastPolledAt", lastPolledAt);
        return job;
    }

    /**
     * 已提交但未终结的异步调用：工具调用已被 /fact 采纳为 UNKNOWN，run 与节点仍持活租约。
     */
    Submitted submitted() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var lease = TracePostgresTest.store.claim(n, "A");
        long attempt = TraceStore.number(lease, "attemptId");
        var req = fixture.call(r, n, lease);
        var calls = new ToolCalls(TracePostgresTest.store);
        long id =
                TraceStore.number(
                        calls.register(req.register(), TracePostgresTest.actor), "callId");
        calls.claim(id, req.claim(), TracePostgresTest.actor);
        calls.submitted(id, req.claim(), TracePostgresTest.actor);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "UNKNOWN");
        result.put("structured", Map.of("value", 1));
        result.put("externalJob", snapshot("2026-10-27T09:00:05Z"));
        calls.fact(
                id,
                Json.tree(
                        Map.of(
                                "owner",
                                req.claim().path("owner").asText(),
                                "attemptId",
                                attempt,
                                "status",
                                "UNKNOWN",
                                "result",
                                result,
                                "durationMs",
                                5,
                                "isMock",
                                false)),
                TracePostgresTest.actor);
        assertEquals(
                "UNKNOWN",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?", String.class, id),
                "外部事实必须被采纳（租约仍活）");
        assertNotNull(
                TracePostgresTest.db.queryForObject(
                        "select external_job::text from engine.tool_call where id=?",
                        String.class,
                        id),
                "事实里的 externalJob 必须落库");
        return new Submitted(r, n, id, attempt, lease);
    }

    /** 构造与真实事实摘要一致的恢复证明，供 adopt 采纳。 */
    static JsonNode proof(Submitted call, String status, Object externalJob) {
        var row =
                TracePostgresTest.db.queryForMap(
                        "select * from engine.tool_call where id=?", call.call());
        var p = Json.object();
        p.put("factId", "sf:" + call.call() + ":" + call.attempt());
        p.put("callId", call.call());
        p.put(
                "runId",
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?",
                        String.class,
                        call.run()));
        p.put("nodeInstanceId", call.node());
        p.put("skillVersionId", 42);
        p.put("argsDigest", row.get("args_digest").toString().trim());
        p.put("skillContractSha256", row.get("skill_contract_sha256").toString().trim());
        p.put("attemptId", call.attempt());
        p.put("status", status);
        p.set(
                "result",
                Json.tree(
                        Map.of(
                                "structured",
                                Map.of("value", 42),
                                "externalJob",
                                externalJob == null ? Map.of() : externalJob)));
        p.put("durationMs", 13);
        p.put("isMock", false);
        p.put("callKind", "DEBUG");
        p.put("factSha256", Digest.sha256(Json.canonical(p)));
        return p;
    }

    @Test
    void toolCallViewExposesRemoteJobIdStateAndPollTimes() {
        var call = submitted();
        var view = new ToolCalls(TracePostgresTest.store).get(call.call(), TracePostgresTest.actor);
        assertTrue(view.containsKey("externalJob"), "字段必须存在，缺失不能当作没有外部任务");
        JsonNode job = (JsonNode) view.get("externalJob");
        assertEquals("job-77", job.path("remoteJobId").asText());
        assertEquals("RUNNING", job.path("state").asText());
        assertEquals("2026-10-27T09:00:00Z", job.path("submittedAt").asText());
        assertEquals("2026-10-27T09:00:05Z", job.path("lastPolledAt").asText());
    }

    @Test
    void toolCallWithoutExternalJobIsExplicitNull() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var lease = TracePostgresTest.store.claim(n, "A");
        var req = fixture.call(r, n, lease);
        var calls = new ToolCalls(TracePostgresTest.store);
        long id =
                TraceStore.number(
                        calls.register(req.register(), TracePostgresTest.actor), "callId");
        calls.claim(id, req.claim(), TracePostgresTest.actor);
        calls.submitted(id, req.claim(), TracePostgresTest.actor);
        calls.fact(
                id,
                Json.tree(
                        Map.of(
                                "owner",
                                req.claim().path("owner").asText(),
                                "attemptId",
                                TraceStore.number(lease, "attemptId"),
                                "status",
                                "SUCCEEDED",
                                "result",
                                Map.of("structured", Map.of("value", 3)))),
                TracePostgresTest.actor);
        var view = calls.get(id, TracePostgresTest.actor);
        assertTrue(view.containsKey("externalJob"));
        assertTrue(((JsonNode) view.get("externalJob")).isNull());
    }

    @Test
    void waitingErrorCarriesSnapshotIntoRunNodeAndAttempt() {
        var call = submitted();
        var node = new HashMap<String, Object>(call.lease());
        node.put("id", call.node());
        node.put("attemptId", call.attempt());
        TracePostgresTest.runs.markWaitingExternal(
                call.run(),
                node,
                Map.of("semantic", "RESULT_UNKNOWN", "message", "技能执行结果未知，等待对账"));

        assertEquals(
                "WAITING_EXTERNAL",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?",
                        String.class,
                        call.node()));
        JsonNode runError =
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select error::text from engine.run where id=?",
                                String.class,
                                call.run()));
        assertEquals("RESULT_UNKNOWN", runError.path("semantic").asText());
        assertEquals(
                "外部任务未终结，等待远端终态", runError.path("message").asText(), "等待态不得伪装成失败原因");
        assertEquals("job-77", runError.path("externalJob").path("remoteJobId").asText());
        assertEquals("RUNNING", runError.path("externalJob").path("state").asText());
        assertEquals(
                "2026-10-27T09:00:05Z",
                runError.path("externalJob").path("lastPolledAt").asText());
        JsonNode nodeError =
                Json.read(
                        TracePostgresTest.db.queryForObject(
                                "select error::text from engine.node_instance where id=?",
                                String.class,
                                call.node()));
        assertEquals("job-77", nodeError.path("externalJob").path("remoteJobId").asText());
        assertEquals(
                "UNKNOWN",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.attempt where id=?",
                        String.class,
                        call.attempt()));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='WAITING_EXTERNAL'",
                        Integer.class,
                        call.run()));
    }

    @Test
    void adoptionClearsWaitingSnapshotAndProjectsRemoteResult() {
        var call = submitted();
        // SUBMITTED 表示已提交待采纳：恢复只做 GET 证明，不触发对账（对账是 UNKNOWN 的路径）。
        TracePostgresTest.db.update(
                "update engine.tool_call set status='SUBMITTED' where id=?", call.call());
        TracePostgresTest.db.update(
                "update engine.node_instance set"
                    + " status='WAITING_EXTERNAL',lease_until=now()-interval '1 minute' where id=?",
                call.node());
        TracePostgresTest.db.update(
                "update engine.tool_call set claimed_lease=now()-interval '1 minute' where id=?",
                call.call());
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_EXTERNAL',error=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "semantic",
                                "RESULT_UNKNOWN",
                                "message",
                                "外部任务未终结，等待远端终态",
                                "externalJob",
                                snapshot("2026-10-27T09:00:05Z"))),
                call.run());
        var proof = proof(call, "SUCCEEDED", snapshot("2026-10-27T09:05:00Z"));
        var recovery =
                new ExecutionRecovery(
                        TracePostgresTest.store,
                        new ToolCalls(TracePostgresTest.store),
                        new RuntimeClients() {
                            @Override
                            public JsonNode get(String service, String path, Actor actor) {
                                return proof;
                            }
                        });
        recovery.recover();
        assertEquals(
                "RUNNING",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, call.run()));
        assertNull(
                TracePostgresTest.db.queryForObject(
                        "select error::text from engine.run where id=?", String.class, call.run()),
                "终态采纳后等待态快照必须清空");
        assertEquals(
                "SUCCEEDED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        call.call()));
        assertEquals(
                "2026-10-27T09:05:00Z",
                TracePostgresTest.db.queryForObject(
                        "select external_job->>'lastPolledAt' from engine.tool_call where id=?",
                        String.class,
                        call.call()));
    }

    @Test
    void reconcileReplyRefreshesSnapshotWithoutLiveLease() {
        var call = submitted();
        TracePostgresTest.db.update(
                "update engine.node_instance set"
                    + " status='WAITING_EXTERNAL',lease_until=now()-interval '1 minute' where id=?",
                call.node());
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_EXTERNAL' where id=?", call.run());
        TracePostgresTest.db.update(
                "update engine.tool_call set status='UNKNOWN',"
                    + " recovery_next_at=now()-interval '1 second' where id=?",
                call.call());
        var recovery =
                new ExecutionRecovery(
                        TracePostgresTest.store,
                        new ToolCalls(TracePostgresTest.store),
                        new RuntimeClients() {
                            @Override
                            public JsonNode get(String service, String path, Actor actor) {
                                return Json.read("{\"status\":\"UNKNOWN\"}");
                            }

                            @Override
                            public JsonNode post(
                                    String service, String path, Object body, Actor actor) {
                                return Json.tree(
                                        Map.of(
                                                "status",
                                                "UNKNOWN",
                                                "result",
                                                Map.of(
                                                        "externalJob",
                                                        snapshot("2026-10-27T09:10:00Z"))));
                            }
                        });
        recovery.recover();
        assertEquals(
                "2026-10-27T09:10:00Z",
                TracePostgresTest.db.queryForObject(
                        "select external_job->>'lastPolledAt' from engine.tool_call where id=?",
                        String.class,
                        call.call()),
                "未终结的对账回执必须刷新 lastPolledAt，否则等待态没有可观测活性");
        assertEquals(
                "UNKNOWN",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        call.call()),
                "刷新快照不得伪造终态");
    }

    @Test
    void resumeExternalFactsClearsWaitingSnapshot() {
        var call = submitted();
        TracePostgresTest.db.update(
                "update engine.node_instance set status='WAITING_EXTERNAL' where id=?",
                call.node());
        TracePostgresTest.db.update(
                "update engine.tool_call set status='SUCCEEDED' where id=?", call.call());
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_EXTERNAL',error=?::jsonb where id=?",
                Json.write(
                        Map.of(
                                "semantic",
                                "RESULT_UNKNOWN",
                                "externalJob",
                                snapshot("2026-10-27T09:00:05Z"))),
                call.run());
        TracePostgresTest.runs.resumeExternalFacts();
        assertEquals(
                "RUNNING",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, call.run()));
        assertNull(
                TracePostgresTest.db.queryForObject(
                        "select error::text from engine.run where id=?", String.class, call.run()));
        assertEquals(
                "READY",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?",
                        String.class,
                        call.node()));
    }
}
