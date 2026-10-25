package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;

/**
 * R-A5 台账项回归：RUNNING run 的失联终态收敛。
 *
 * <p>孤儿判定三条件（调度租约已死 + 无节点租约存活 + 存在非终态节点）与防误杀
 * 边界（健康 run、WAITING 态、全终态节点待聚合）逐一验证；收敛留痕含
 * ORPHANED stop_reason、CANCELLED 节点、ORPHAN_CONVERGED 注记与 RUN_FINISHED 事件。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunOrphanConvergencePostgresTest {
    final TracePostgresTest fixture = new TracePostgresTest();
    RunOrphanConvergence convergence;

    @BeforeAll
    static void connect() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
        convergence = new RunOrphanConvergence(TracePostgresTest.store);
    }

    /** 构造一个"调度租约与节点租约均已过期"的失联 run，返回 (runId, nodeId)。 */
    long[] crashedRun() {
        long r = fixture.run(), n = fixture.node(r, "READY");
        TracePostgresTest.store.claim(n, "dead-worker");
        TracePostgresTest.db.update(
                "update engine.run set dispatch_owner='dead-worker'"
                        + ",dispatch_until=now()-interval '60 seconds' where id=?",
                r);
        TracePostgresTest.db.update(
                "update engine.node_instance set lease_until=now()-interval '60 seconds'"
                        + " where id=?",
                n);
        return new long[] {r, n};
    }

    private String runStatus(long r) {
        return TracePostgresTest.db.queryForObject(
                "select status from engine.run where id=?", String.class, r);
    }

    @Test
    void crashedRunConvergesToFailedWithEvidence() {
        var ids = crashedRun();
        convergence.converge(ids[0]);
        var run =
                TracePostgresTest.db.queryForMap(
                        "select status,stop_reason from engine.run where id=?", ids[0]);
        assertEquals("FAILED", run.get("status"));
        assertEquals("ORPHANED", run.get("stop_reason"));
        assertEquals(
                "CANCELLED",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?",
                        String.class,
                        ids[1]));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.attempt_note where node_instance_id=?"
                                + " and kind='ORPHAN_CONVERGED'",
                        Integer.class,
                        ids[1]));
        assertEquals(
                1,
                TracePostgresTest.db.queryForObject(
                        "select count(*) from engine.run_event where run_id=? and"
                                + " type='RUN_FINISHED' and payload->>'stopReason'='ORPHANED'",
                        Integer.class,
                        ids[0]));
    }

    @Test
    void healthyDispatchLeaseIsNeverConverged() {
        var ids = crashedRun();
        // worker 心跳仍在：调度租约未过期，即便节点租约恰好过期也必须跳过。
        TracePostgresTest.db.update(
                "update engine.run set dispatch_until=now()+interval '30 seconds' where id=?",
                ids[0]);
        convergence.converge(ids[0]);
        assertEquals("RUNNING", runStatus(ids[0]));
        assertEquals(
                "RUNNING",
                TracePostgresTest.db.queryForObject(
                        "select status from engine.node_instance where id=?",
                        String.class,
                        ids[1]));
    }

    @Test
    void waitingRunIsOutsideConvergence() {
        var ids = crashedRun();
        TracePostgresTest.db.update(
                "update engine.run set status='WAITING_APPROVAL' where id=?", ids[0]);
        convergence.converge(ids[0]);
        assertEquals("WAITING_APPROVAL", runStatus(ids[0]));
    }

    @Test
    void runWithAllTerminalNodesIsLeftToAggregation() {
        var ids = crashedRun();
        TracePostgresTest.db.update(
                "update engine.node_instance set status='SUCCEEDED',lease_until=null,"
                        + "lease_owner=null where id=?",
                ids[1]);
        convergence.converge(ids[0]);
        assertEquals("RUNNING", runStatus(ids[0]));
    }
}
