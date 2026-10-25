package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ExecutionRecoveryTest {
    final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void database() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    record Fault(long run, long node, long call, JsonNode proof) {}

    Fault interrupted() {
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
        p.put("status", "SUCCEEDED");
        p.set("result", Json.tree(Map.of("structured", Map.of("value", 42))));
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

    @Test
    void scheduledPullAdoptsExpiredLeaseProofWithoutBusinessReplay() {
        var fault = interrupted();
        var reads = new AtomicInteger();
        var service =
                new ExecutionRecovery(
                        TracePostgresTest.store,
                        new ToolCalls(TracePostgresTest.store),
                        new RuntimeClients() {
                            @Override
                            public JsonNode get(String service, String path, Actor actor) {
                                reads.incrementAndGet();
                                assertTrue(
                                        actor.roles().isEmpty(),
                                        "Recovery may not reconstruct business privileges");
                                assertEquals("alice", actor.principalId());
                                return fault.proof();
                            }
                        });
        service.recover();
        assertEquals(
                "SUCCEEDED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        fault.call()));
        assertEquals(
                "READY",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?",
                        String.class,
                        fault.node()));
        assertEquals(
                0,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='LOGIC_RESULT_COMMITTED'",
                        Integer.class,
                        fault.run()));
        service.recover();
        assertEquals(1, reads.get());
        assertEquals(
                13,
                TracePostgresTest.db.queryForObject(
                        "select duration_ms from engine.tool_call where id=?",
                        Integer.class,
                        fault.call()));
    }

    @Test
    void mismatchedPersistentProofIsRejectedAndProbeIsDurable() {
        var fault = interrupted();
        var proof = (com.fasterxml.jackson.databind.node.ObjectNode) fault.proof().deepCopy();
        proof.put("argsDigest", Digest.sha256("different"));
        proof.remove("factSha256");
        proof.put("factSha256", Digest.sha256(Json.canonical(proof)));
        var service =
                new ExecutionRecovery(
                        TracePostgresTest.store,
                        new ToolCalls(TracePostgresTest.store),
                        new RuntimeClients() {
                            @Override
                            public JsonNode get(String service, String path, Actor actor) {
                                return proof;
                            }
                        });
        service.recover();
        assertEquals(
                "SUBMITTED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        fault.call()));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select recovery_attempts from engine.tool_call where id=?",
                        Integer.class,
                        fault.call()));
        assertNotNull(
                TracePostgresTest.db.queryForObject(
                        "select recovery_error from engine.tool_call where id=?",
                        String.class,
                        fault.call()));
    }

    @Test
    void adoptedFailedProofTerminatesThroughDriverWithoutRedispatch() {
        var fault = interrupted();
        var proof = (com.fasterxml.jackson.databind.node.ObjectNode) fault.proof().deepCopy();
        proof.put("status", "FAILED");
        proof.set(
                "result",
                Json.tree(
                        Map.of(
                                "error",
                                Map.of(
                                        "semantic",
                                        "PARAM_ERROR",
                                        "message",
                                        "invalid parameter"))));
        proof.remove("factSha256");
        proof.put("factSha256", Digest.sha256(Json.canonical(proof)));
        var external = new AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return proof;
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
                                "skillRef",
                                Map.of(
                                        "code",
                                        "lookup",
                                        "skillVersionId",
                                        42,
                                        "execution",
                                        Map.of("readonly", false)))),
                fault.node());
        var agent =
                new cn.agentplatform.engine.application.AgentDriver(
                        TracePostgresTest.store,
                        clients,
                        new cn.agentplatform.engine.application.ContextAssembler());
        var runs =
                new cn.agentplatform.engine.application.RunService(
                        TracePostgresTest.store,
                        clients,
                        new cn.agentplatform.engine.application.FlowDriver(
                                TracePostgresTest.store, agent),
                        agent);
        runs.drive(fault.run(), TracePostgresTest.actor);
        assertEquals(
                0, external.get(), "Known failed execution facts must not be sent to skill again");
        assertEquals(
                "PARAM_ERROR",
                TracePostgresTest.db.queryForObject(
                        "select error_semantic from engine.tool_call where id=?",
                        String.class,
                        fault.call()));
        assertEquals(
                "FAILED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, fault.run()));
    }

    @Test
    void adoptedRetryableReadonlyFailureGetsOneExplicitNewExecutionAttempt() {
        var fault = interrupted();
        var proof = (com.fasterxml.jackson.databind.node.ObjectNode) fault.proof().deepCopy();
        proof.put("status", "FAILED");
        proof.set("result", Json.tree(Map.of("error", Map.of("semantic", "RETRYABLE_FAILURE"))));
        proof.remove("factSha256");
        proof.put("factSha256", Digest.sha256(Json.canonical(proof)));
        var external = new AtomicInteger();
        var clients =
                new RuntimeClients() {
                    @Override
                    public JsonNode get(String service, String path, Actor actor) {
                        return proof;
                    }

                    @Override
                    public JsonNode post(String service, String path, Object body, Actor actor) {
                        external.incrementAndGet();
                        var req = Json.tree(body);
                        long id = req.path("callId").asLong();
                        var claim =
                                Json.tree(
                                        Map.of(
                                                "owner",
                                                req.path("leaseOwner").asText(),
                                                "attemptId",
                                                req.path("attemptId").asLong()));
                        var calls = new ToolCalls(TracePostgresTest.store);
                        assertEquals(true, calls.claim(id, claim, actor).get("claimed"));
                        calls.submitted(id, claim, actor);
                        var fact =
                                (com.fasterxml.jackson.databind.node.ObjectNode) claim.deepCopy();
                        fact.put("status", "SUCCEEDED");
                        fact.set("result", Json.tree(Map.of("structured", Map.of("value", 42))));
                        calls.fact(id, fact, actor);
                        return Json.tree(
                                Map.of(
                                        "status",
                                        "SUCCEEDED",
                                        "result",
                                        Map.of("structured", Map.of("value", 42))));
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
                                Map.of("retries", 1),
                                "skillRef",
                                Map.of(
                                        "code",
                                        "lookup",
                                        "skillVersionId",
                                        42,
                                        "execution",
                                        Map.of("readonly", true)))),
                fault.node());
        var agent =
                new cn.agentplatform.engine.application.AgentDriver(
                        TracePostgresTest.store,
                        clients,
                        new cn.agentplatform.engine.application.ContextAssembler());
        var runs =
                new cn.agentplatform.engine.application.RunService(
                        TracePostgresTest.store,
                        clients,
                        new cn.agentplatform.engine.application.FlowDriver(
                                TracePostgresTest.store, agent),
                        agent);
        runs.drive(fault.run(), TracePostgresTest.actor);
        assertEquals(0, external.get());
        assertEquals(
                "REGISTERED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.tool_call where id=?",
                        String.class,
                        fault.call()));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select readonly_retry_count from engine.tool_call where id=?",
                        Integer.class,
                        fault.call()));
        runs.drive(fault.run(), TracePostgresTest.actor);
        assertEquals(1, external.get());
        assertEquals(
                "SUCCEEDED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.run where id=?", String.class, fault.run()));
    }
}
