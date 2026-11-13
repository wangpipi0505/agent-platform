package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MNT-05：运行恢复协调。
 *
 * <p>集中承载调度器注册的三条恢复回调：认领后的恢复分发、节点租约续期、外部事实接续。
 * 执行驱动仍由 {@link RunService} 持有——正常受理与恢复共用同一 {@code drive}/{@code driveOwned}
 * 路径，本类只决定“恢复到哪条路径、租约何时续、外部事实何时唤醒”，不复制执行状态机。
 *
 * <p>事务与锁语义保持原样：每条租约续期仍是独立短事务、{@code skip locked}；外部事实只做
 * 一次短事务内的状态翻转与唤醒；恢复分发本身不开启事务，也不做网络调用。
 */
@Service
public class RunRecoveryCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(RunRecoveryCoordinator.class);

    private final TraceStore store;
    private final RunScheduler scheduler;

    public RunRecoveryCoordinator(TraceStore store, RunScheduler scheduler) {
        this.store = store;
        this.scheduler = scheduler;
    }

    /**
     * 恢复分发的受控回调面。实现方是 RunService：驱动仍走既有 {@code driveOwned}/{@code drive}，
     * 协调器只把“已认领的恢复任务”路由过去。
     */
    public interface Handler {
        void driveOwned(long id);

        void driveLegacy(long id, Actor actor);

        void guardRejected(long id);
    }

    /**
     * 调度器 {@code driveRecovered} 回调：受保护运行直接交回 owned 执行路径；legacy 运行从落库的
     * guard 快照重建 Actor，再交回普通 drive。guard 与 run 归属不一致时按预算原因停止，绝不
     * 用伪造身份继续执行。
     */
    public void recovered(long id, Handler handler) {
        if (store.protectedRun(id)) {
            handler.driveOwned(id);
            return;
        }
        var run = store.db().queryForMap("select * from engine.run where id=?", id);
        JsonNode guard = TraceStore.json(run.get("guard_ctx"));
        if (!guard.path("tenantId").asText().equals(run.get("tenant_id"))
                || !guard.path("principalId").asText().equals(run.get("principal_id"))) {
            handler.guardRejected(id);
            return;
        }
        Set<String> roles = new HashSet<>();
        guard.path("roles").forEach(role -> roles.add(role.asText()));
        Map<String, Set<String>> attributes = new HashMap<>();
        guard.path("attributes")
                .fields()
                .forEachRemaining(
                        entry -> {
                            Set<String> values = new HashSet<>();
                            entry.getValue().forEach(value -> values.add(value.asText()));
                            attributes.put(entry.getKey(), values);
                        });
        handler.driveLegacy(
                id,
                new Actor(
                        guard.path("tenantId").asText(),
                        guard.path("principalId").asText(),
                        roles,
                        attributes));
    }

    /**
     * 调度器 {@code renewLeases} 回调：为本进程正在执行的节点续租。owner 与活动节点集合仍由
     * RunService 持有（执行期登记），这里只做续期。
     */
    public void renewLeases(String owner, Set<Long> activeNodes) {
        for (Long node : activeNodes) {
            try {
                store.atomic(
                        () -> {
                            // Each item is a short transaction. Busy node/tool rows are skipped,
                            // never waited on while other live workers need their leases renewed.
                            int renewed =
                                    store.db()
                                            .update(
                                                    "with renewable as (select id from"
                                                        + " engine.node_instance where id=? and"
                                                        + " status='RUNNING' and lease_owner like ?"
                                                        + " and lease_until>clock_timestamp() for"
                                                        + " update skip locked) update"
                                                        + " engine.node_instance n set"
                                                        + " lease_until=clock_timestamp()+interval"
                                                        + " '90 seconds' from renewable r where"
                                                        + " n.id=r.id",
                                                    node,
                                                    owner + "_%");
                            if (renewed == 0) return null;
                            store.db()
                                    .update(
                                            "with renewable as (select t.id,n.lease_until from"
                                                + " engine.tool_call t join engine.node_instance n"
                                                + " on n.id=t.node_instance_id where n.id=? and"
                                                + " n.status='RUNNING' and n.lease_owner like ? and"
                                                + " n.lease_until>clock_timestamp() and"
                                                + " t.claimed_by=n.lease_owner and t.status in"
                                                + " ('EXECUTING','SUBMITTED') and"
                                                + " t.claimed_lease>now() for update of t skip"
                                                + " locked) update engine.tool_call t set"
                                                + " claimed_lease=r.lease_until from renewable r"
                                                + " where t.id=r.id",
                                            node,
                                            owner + "_%");
                            return null;
                        });
            } catch (RuntimeException failure) {
                LOG.warn("Node lease renewal failed: {}", failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 调度器 {@code resumeExternalFacts} 回调：把已有持久成功/失败事实的等待节点翻回 READY，
     * 唤醒对应运行；不重放技能调用。
     */
    public void resumeExternalFacts() {
        // W3 (§31.2): consume durable successful/failed execution facts without repeating the
        // skill.
        for (var node :
                store.db()
                        .queryForList(
                                "select n.id,n.run_id from engine.node_instance n join engine.run r"
                                    + " on r.id=n.run_id where n.status='WAITING_EXTERNAL' and"
                                    + " r.status in ('RUNNING','WAITING_EXTERNAL') and not"
                                    + " exists(select 1 from engine.run_authority a where"
                                    + " a.run_id=r.id) and exists(select 1 from engine.tool_call t"
                                    + " where t.node_instance_id=n.id) and not exists(select 1 from"
                                    + " engine.tool_call t where t.node_instance_id=n.id and"
                                    + " t.status not in ('SUCCEEDED','FAILED')) order by n.id limit"
                                    + " 32"))
            store.atomic(
                    () -> {
                        long rid = TraceStore.number(node, "run_id");
                        store.lockRun(rid);
                        int changed =
                                store.db()
                                        .update(
                                                "update engine.node_instance set"
                                                    + " status='READY',released=true,lease_owner=null,lease_until=null"
                                                    + " where id=? and status='WAITING_EXTERNAL'",
                                                node.get("id"));
                        if (changed > 0) {
                            store.db()
                                    .update(
                                            "update engine.run set status='RUNNING',error=null where"
                                            + " id=? and status='WAITING_EXTERNAL'",
                                            rid);
                            scheduler.wake(rid);
                            store.event(
                                    rid,
                                    "EXTERNAL_READY",
                                    Map.of("nodeInstanceId", node.get("id")));
                        }
                        return null;
                    });
    }

    /**
     * H1-8（契约桩 contracts/h1-6-h1-8-stubs.md §3）：只读恢复事实出口——按 run 事件事实推导
     * 恢复来源/原因/检查点/复用步骤/阻塞原因，可重复取得（重启后同一事实）。手动与自动恢复
     * 共用事件写入路径（TASK_RESUMED 语义在任务层，引擎层以 RESUMED/EXTERNAL_FACT_ADOPTED/
     * 接管事实为准）。角色投影：USER 仅来源/原因/摘要（{@code privileged=false}）。
     */
    public cn.agentplatform.engine.harness.loop.RecoveryFact recoveryFact(long runId, boolean privileged) {
        var run = store.db().queryForMap("select * from engine.run where id=?", runId);
        String publicId = String.valueOf(run.get("public_id"));
        var types =
                store.db()
                        .queryForList(
                                "select type from engine.run_event where run_id=? order by seq",
                                String.class,
                                runId);        String source = "AUTO";
        String reason = "EXECUTION_INTERRUPTED";
        for (String type : types) {
            if (type.equals("EXTERNAL_FACT_ADOPTED")) {
                source = "AUTO";
                reason = "EXTERNAL_RESULT_UNKNOWN";
            } else if (type.equals("RESUMED")) {
                source = "MANUAL";
                reason = "MANUAL_REQUEST";
            }
        }
        if (reason.equals("EXECUTION_INTERRUPTED") && !types.contains("EXTERNAL_FACT_ADOPTED")
                && run.get("status").toString().equals("RUNNING"))
            reason = "LEASE_EXPIRED";

        // 检查点：会话水位优先（H1-2 注入的 chatSession），无会话回退 run 事件水位
        long watermark =
                store.db()
                        .queryForObject(
                                "select coalesce((select max(se.seq) from engine.session_event se"
                                    + " join engine.chat_session cs on cs.public_id=se.session_id"
                                    + " where cs.app_code=? and cs.tenant_id=? and"
                                    + " cs.principal_id=? and"
                                    + " cs.public_id = (select (snapshot->'chatSession'->>'sessionId')"
                                    + " from engine.run where id=?)),"
                                    + " (select coalesce(max(seq),0) from engine.run_event"
                                    + " where run_id=?))",
                                Long.class,
                                run.get("app_code"),
                                run.get("tenant_id"),
                                run.get("principal_id"),
                                runId,
                                runId);

        // 复用步骤：已提交节点（result 事实）
        var reused =
                store.db()
                        .queryForList(
                                "select node_def_id,output_digest from engine.node_instance"
                                    + " where run_id=? and status='SUCCEEDED' and"
                                    + " committed_attempt_id is not null order by step_seq",
                                runId)
                        .stream()
                        .map(
                                row ->
                                        new cn.agentplatform.engine.harness.loop.RecoveryFact
                                                .ReusedStep(
                                                String.valueOf(row.get("node_def_id")),
                                                String.valueOf(row.get("output_digest"))))
                        .toList();

        // 阻塞原因：WAITING 类节点
        var blocked =
                store.db()
                        .queryForList(
                                "select distinct status from engine.node_instance where run_id=?"
                                    + " and status like 'WAITING%' order by status",
                                String.class,
                                runId);

        var fact =
                new cn.agentplatform.engine.harness.loop.RecoveryFact(
                        source,
                        reason,
                        summaryOf(reason),
                        new cn.agentplatform.engine.harness.loop.RecoveryFact.Checkpoint(
                                watermark, List.of(publicId)),
                        reused,
                        blocked);
        return privileged ? fact : onlyUserView(fact);
    }

    private cn.agentplatform.engine.harness.loop.RecoveryFact onlyUserView(
            cn.agentplatform.engine.harness.loop.RecoveryFact fact) {
        return new cn.agentplatform.engine.harness.loop.RecoveryFact(
                fact.recoverySource(),
                fact.reasonCode(),
                fact.reasonSummary(),
                new cn.agentplatform.engine.harness.loop.RecoveryFact.Checkpoint(0, List.of()),
                List.of(),
                List.of());
    }

    private String summaryOf(String reason) {
        return switch (reason) {
            case "EXTERNAL_RESULT_UNKNOWN" -> "外部结果未确认，已按持久事实接续";
            case "LEASE_EXPIRED" -> "执行租约到期，已自动接续";
            case "EXECUTION_INTERRUPTED" -> "执行被中断，已恢复";
            case "MANUAL_REQUEST" -> "按请求手动恢复";
            default -> "已恢复";
        };
    }
}
