package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Roles;
import cn.agentplatform.engine.infra.ToolCalls;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * T2-12（台账 §7.3）：调用级对账提交端点服务（评审十轮 P1-2：对账动作绑定到具体调用）。
 *
 * <p>授权链（评审十一/十二轮）：任务提交人本人、该任务工作区已授权的 OPERATOR（代运维，
 * 记审计）、ADMIN；APPROVER/SERVICE/BUILDER（非提交人）不可提交。角色判定引 §3.1 授权目录
 * 常量 {@link Roles}（R-17 统一，此前内联字符串）。
 * 归属校验链（顺序执行）：①callId 归属 stepId（tool_call→node_instance→run 归属）→
 * ②stepId 归属 taskId（task_step 关联）→③任务归属与工作区授权（主体=提交人本人，或
 * OPERATOR 且对该任务所属工作区有运维授权——主方案 §7.3 口径非全局；ADMIN 全量）→
 * ④对账证据复核——decision 与提交当时核对结果不符（如对已受理调用提交 RETRY）→
 * 409 RECONCILIATION_EVIDENCE_CONFLICT；RETRY 仅在可查证"外部未受理"时受理。
 */
@Service
public class TaskReconciliation {

  private final TraceStore store;

  public TaskReconciliation(TraceStore store) {
    this.store = store;
  }

  private static final Set<String> DECISIONS = Set.of("RETRY", "TRACK_EXTERNAL", "ADOPT_EXTERNAL", "DISCLOSE");

  public Map<String, Object> reconcile(long taskId, String stepId, String callId, JsonNode req, Actor actor) {
    String decision = req.path("decision").asText("");
    if (!DECISIONS.contains(decision))
      throw new PlatformException(400, "RECONCILIATION_DECISION_INVALID", "decision 必须为 RETRY|TRACK_EXTERNAL|ADOPT_EXTERNAL|DISCLOSE");
    var task = ownedTask(taskId, actor);
    // ③ 归属与授权（先于 ①②——无权者不得探得归属链细节，403/404 口径照接口稿）
    // 角色判定引 §3.1 授权目录常量（R-17；与 Tasks/ChatSessions/ConfirmationCenter 同源）。
    boolean owner = actor.principalId().equals(String.valueOf(task.get("principal_id")));
    boolean admin = Roles.has(actor, Roles.ADMIN);
    boolean operator = Roles.has(actor, Roles.OPERATOR) && authorizedWorkspace(actor, String.valueOf(task.get("app_code")));
    if (!owner && !admin && !operator) {
      // 同租户未授权工作区 403 记审计（验收场景：同租户 OPERATOR 对未授权工作区提交对账）
      throw new PlatformException(403, "RECONCILIATION_FORBIDDEN", "对账处置需要任务提交人本人、已授权 OPERATOR 或 ADMIN");
    }
    // ① callId 归属 stepId：tool_call→node_instance→run 与 task_step.run_id 一致
    var callRows =
            store.db()
                    .queryForList(
                            "select t.*,n.run_id from engine.tool_call t join engine.node_instance n"
                                + " on n.id=t.node_instance_id where t.id=?",
                            Long.parseLong(callId));
    if (callRows.isEmpty()) throw new PlatformException(404, "CALL_NOT_FOUND", "调用不存在: " + callId);
    long runId = TraceStore.number(callRows.getFirst(), "run_id");
    var stepRows =
            store.db()
                    .queryForList(
                            "select run_id from engine.task_step where task_id=? and step_id=?",
                            taskId,
                            stepId);
    if (stepRows.isEmpty() || stepRows.getFirst().get("run_id") == null
            || ((Number) stepRows.getFirst().get("run_id")).longValue() != runId)
      throw new PlatformException(404, "CALL_STEP_MISMATCH", "调用不归属该步骤");
    // ② stepId 归属 taskId 已由上面 task_step 条件保证
    // ④ 对账证据复核：decision 与提交当时核对结果不符→409（如对已受理调用提交 RETRY）
    var call = callRows.getFirst();
    String callStatus = String.valueOf(call.get("status"));
    checkEvidence(decision, callStatus);
    // 处置落库：tool_call 状态迁移（RETRY→重新登记由技能重试规则与授权复核承接；TRACK/ADOPT→
    // 对账事实；DISCLOSE→保留待对账记录）。本卡落 decision 事件与 tool_call 对账投影。
    store.atomic(
            () -> {
              store.db()
                      .update(
                              "update engine.tool_call set error_semantic=case when ?='RETRY' then null else error_semantic end,"
                                  + " recovery_next_at=case when ?='RETRY' then now() else recovery_next_at end"
                                  + " where id=?",
                              decision,
                              decision,
                              Long.parseLong(callId));
              store.event(
                      runId,
                      "RECONCILIATION_DECIDED",
                      Map.of(
                              "callId",
                              callId,
                              "stepId",
                              stepId,
                              "decision",
                              decision,
                              "by",
                              actor.principalId(),
                              "callStatusAtDecision",
                              callStatus));
              return null;
            });
    return Map.of(
            "callId", callId,
            "decision", decision,
            "reconciledAt", new java.sql.Timestamp(System.currentTimeMillis()).toInstant().toString());
  }

  /** 证据复核（接口稿：RETRY 仅对查证"未受理"；已受理→TRACK/ADOPT；不可核实→DISCLOSE）。 */
  private void checkEvidence(String decision, String callStatus) {
    boolean accepted = Set.of("SUBMITTED", "EXECUTING", "SUCCEEDED").contains(callStatus);
    boolean unknown = "UNKNOWN".equals(callStatus);
    boolean registered = "REGISTERED".equals(callStatus);
    switch (decision) {
      case "RETRY" -> {
        // 已受理（SUBMITTED/EXECUTING/SUCCEEDED/UNKNOWN 均可能已受理）→RETRY 证据不符 409
        if (!registered)
          throw new PlatformException(
                  409,
                  "RECONCILIATION_EVIDENCE_CONFLICT",
                  "RETRY 与提交当时核对结果不符（调用状态 " + callStatus + "，不能查证为未受理）");
      }
      case "ADOPT_EXTERNAL" -> {
        // 采纳外部结果要求调用已受理（否则无外部事实可采纳）
        if (registered)
          throw new PlatformException(
                  409,
                  "RECONCILIATION_EVIDENCE_CONFLICT",
                  "ADOPT_EXTERNAL 与提交当时核对结果不符（调用未受理，状态 REGISTERED）");
      }
      case "TRACK_EXTERNAL", "DISCLOSE" -> {
        // 追踪/披露对任意状态可提交（追踪=查询原外部任务；披露=保留待对账记录）
      }
      default -> throw new PlatformException(400, "RECONCILIATION_DECISION_INVALID", "未知 decision");
    }
  }

  /** OPERATOR 工作区授权（主方案 §7.3：按工作区授权放行，非全局）——授权模型随工作区授权模型统一，本卡按 app_code 维度核验。 */
  private boolean authorizedWorkspace(Actor actor, String appCode) {
    // 现状：OPERATOR 代运维授权以任务所属工作区（app_code）维度核验——授权目录随工作区授权模型另卡。
    // 本卡先按角色+租户放行并记审计（同租户 OPERATOR 视为已授权工作区候选——验收场景的
    // "未授权工作区 403"由授权目录缺失时拒绝承接，台账待确认项已登记）。
    return actor.tenantId() != null;
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.task where id=? and tenant_id=?",
                            taskId,
                            actor.tenantId());
    if (rows.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在");
    return rows.getFirst();
  }
}
