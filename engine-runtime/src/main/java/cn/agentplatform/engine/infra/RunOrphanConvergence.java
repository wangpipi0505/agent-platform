package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * R-A5：RUNNING run 的失联终态收敛（孤儿判定）。
 *
 * <p>与 ExecutionRecovery 的分工：那边对外部技能调用做事实对账；本服务只处理
 * "进程失联"——调度租约与节点执行租约同时失效且仍有未完成节点的 RUNNING run，
 * 已不可能有任何存活 worker 推进或按租约 CAS 提交，若不收敛将永久悬挂在 RUNNING。
 *
 * <p>孤儿判定三条件（缺一不收敛，防误杀）：
 * ①调度租约已死：dispatch_until 非空且过期超 10 秒宽限（调度心跳按 lease/3 续租，
 *   宽限足以避开心跳间隙；dispatch 为空的 run 等待调度认领，不是孤儿）；
 * ②无任何节点执行租约存活（lease_until 全部过期）；
 * ③仍存在非终态节点——全部节点已终态的 run 由聚合路径收尾，不属孤儿。
 * WAITING_* 状态的 run 在等待人工/外部事件唤醒，无运行 worker 属正常态，不在范围。
 *
 * <p>收敛动作与 RunService.stopForBudget 同构：run→FAILED(stop_reason=ORPHANED)、
 * 非终态节点→CANCELLED、RUN_FINISHED 事件留痕；被中断节点追加 ORPHAN_CONVERGED
 * 注记（含收敛前状态），供回放解释"此节点为何没有结果"。
 */
@Service
public class RunOrphanConvergence {
    private static final String TERMINAL_NODES =
            "('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')";

    private final TraceStore store;

    public RunOrphanConvergence(TraceStore store) {
        this.store = store;
    }

    /** 启动 15 秒后做首轮扫描（覆盖"重启即收敛崩溃遗留"），此后每 30 秒一轮。 */
    @Scheduled(initialDelay = 15000, fixedDelay = 30000)
    public void converge() {
        var orphans = orphans();
        for (var row : orphans) converge(TraceStore.number(row, "id"));
    }

    private List<Map<String, Object>> orphans() {
        return store.db()
                .queryForList(
                        "select r.id from engine.run r where r.status='RUNNING'"
                            + " and r.dispatch_until is not null"
                            + " and r.dispatch_until < now() - interval '10 seconds'"
                            + " and not exists (select 1 from engine.node_instance n where"
                            + " n.run_id=r.id and n.lease_until>now())"
                            + " and exists (select 1 from engine.node_instance n where"
                            + " n.run_id=r.id and n.status not in "
                            + TERMINAL_NODES
                            + ") order by r.id limit 16");
    }

    void converge(long rid) {
        store.atomic(
                () -> {
                    var run = store.lockRun(rid);
                    if (!"RUNNING".equals(run.get("status"))) return null;
                    // 持锁后按同一口径复判：与刚续租/刚认领的 worker 竞争、或扫描后
                    // 最后一节点已被正常提交（此刻应交给聚合收尾）时，放弃收敛。
                    if (Boolean.TRUE.equals(
                            store.db()
                                    .queryForObject(
                                            "select exists(select 1 from engine.node_instance"
                                                + " where run_id=? and lease_until>now()) or"
                                                + " exists(select 1 from engine.run where id=?"
                                                + " and dispatch_until>now())",
                                            Boolean.class,
                                            rid,
                                            rid))) return null;
                    if (!Boolean.TRUE.equals(
                            store.db()
                                    .queryForObject(
                                            "select exists(select 1 from engine.node_instance"
                                                + " where run_id=? and status not in "
                                                + TERMINAL_NODES
                                                + ")",
                                            Boolean.class,
                                            rid))) return null;
                    var interrupted =
                            store.db()
                                    .queryForList(
                                            "select id,status from engine.node_instance where"
                                                + " run_id=? and status not in "
                                                + TERMINAL_NODES
                                                + " order by id",
                                            rid);
                    store.db()
                            .update(
                                    "update engine.run set status='FAILED',stop_reason='ORPHANED'"
                                            + ",error=?::jsonb,ended_at=now() where id=?",
                                    Json.write(
                                            Map.of(
                                                    "semantic",
                                                    "ORPHANED",
                                                    "message",
                                                    "调度与执行租约双双失守，判定进程失联并收敛为终态")),
                                    rid);
                    for (var node : interrupted) {
                        long nid = TraceStore.number(node, "id");
                        store.db()
                                .update(
                                        "update engine.node_instance set status='CANCELLED'"
                                                + ",ended_at=now() where id=? and status not in "
                                                + TERMINAL_NODES,
                                        nid);
                        store.db()
                                .update(
                                        "insert into engine.attempt_note(node_instance_id"
                                                + ",attempt_id,kind,payload) select ?,(select"
                                                + " max(id) from engine.attempt a where"
                                                + " a.node_instance_id=?),'ORPHAN_CONVERGED'"
                                                + ",?::jsonb",
                                        nid,
                                        nid,
                                        Json.write(
                                                Map.of(
                                                        "statusBefore",
                                                        Objects.toString(node.get("status"), ""),
                                                        "runId",
                                                        rid)));
                    }
                    store.event(
                            rid,
                            "RUN_FINISHED",
                            Map.of("status", "FAILED", "stopReason", "ORPHANED"));
                    return null;
                });
    }
}
