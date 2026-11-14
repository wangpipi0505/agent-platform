package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * T2-5（台账 §7.3）：五 kind 步骤执行映射——所有步骤一律受理为逻辑 run
 * （run→node_instance→attempt 链，不绕过现有受理与执行契约，主方案 §5.6 表）。
 *
 * <p>kind→受理与执行映射：SKILL→技能管道（精确版本+守卫+验收封箱）；FLOW→FlowDriver；
 * AGENT→AgentDriver/loop；MODEL→model_invocation；CONDITION→受限表达式求值（读已提交
 * 上游结果）。task_dispatch→run 按 dispatch_key 幂等（T2-6 分派承接）。
 *
 * <p>A08b 子项（评审十九/二十轮）：plan_step.failure_policy（timeout/retry 次数/终止）映射
 * 到节点执行策略——执行驱动=现有节点执行链（超时触发/重试计数复用 attempt 序列），原因与
 * 尝试次数记录复用 attempt/attempt_note；新增=plan_step 策略到节点配置的映射与终止→
 * 下游阻断/跳过。三场景：超时按 timeout 触发；耗尽→FAILED 且 attempt 序列完整；终止→
 * 下游按 failure_policy 阻断/跳过。
 */
@Service
public class StepExecutionMapping {

  private final TraceStore store;

  public StepExecutionMapping(TraceStore store) {
    this.store = store;
  }

  /** 五 kind 枚举（contracts/task-states.md plan_step.kind）。 */
  public static final Set<String> KINDS = Set.of("SKILL", "FLOW", "AGENT", "MODEL", "CONDITION");

  /**
   * 步骤受理为逻辑 run：task_step 已存在（T2-2 直接任务 entry 或计划步骤），本方法建立
   * run 归属并写 task_dispatch 供 T2-6 认领——dispatch_key=sha256(tenant/task/planRevision/step)。
   *
   * @return {dispatchKey, runId, nodeInstanceId}
   */
  public Map<String, Object> admit(long taskId, String stepId, Actor actor) {
    var task = ownedTask(taskId, actor);
    var step =
            store.db()
                    .queryForList(
                            "select * from engine.task_step where task_id=? and step_id=?",
                            taskId,
                            stepId);
    if (step.isEmpty())
      throw new PlatformException(404, "TASK_STEP_NOT_FOUND", "任务步骤不存在: " + stepId);
    store.db()
            .update(
                    "insert into engine.task_dispatch(task_id,step_id,dispatch_key,state,available_at)"
                        + " values(?,?,?,'PENDING',now())"
                        + " on conflict (task_id,step_id) do update set available_at=now()",
                    taskId,
                    stepId,
                    dispatchKey(taskId, stepId));
    // 逻辑 run 受理（复用现有 run 受理契约——DEBUG 语义隔离，trigger=DEBUG 以走受控路径）
    String runPublicId = "rt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    long runId =
            store.db()
                    .queryForObject(
                            "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                                + " values(?,?,'{}','DEBUG','RUNNING',?,?, 'task-input/" + runPublicId + "','{}','{}','{}','{}','{}','STEP')"
                                + " returning id",
                            Long.class,
                            runPublicId,
                            task.get("app_code"),
                            actor.principalId(),
                            actor.tenantId());
    long nodeId =
            store.db()
                    .queryForObject(
                            "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                                + " values(?,'plan-step',1,'CODE','READY',?::jsonb) returning id",
                            Long.class,
                            runId,
                            Json.write(Map.of("type", "CODE", "kind", stepKind(taskId, stepId))));
    store.db()
            .update(
                    "update engine.task_step set run_id=? where task_id=? and step_id=? and run_id is null",
                    runId,
                    taskId,
                    stepId);
    store.db().update("update engine.task_dispatch set run_id=? where task_id=? and step_id=?", runId, taskId, stepId);
    appendTaskEvent(taskId, "STEP_READY", Json.tree(Map.of("stepId", stepId, "runId", runPublicId)));
    return Map.of(
            "dispatchKey", dispatchKey(taskId, stepId),
            "runId", runId,
            "runPublicId", runPublicId,
            "nodeInstanceId", nodeId);
  }

  /**
   * A08b：plan_step.failure_policy 到节点执行配置的映射。
   * failure_policy 形状：{timeoutMs?, retry?: {max?, on?: [TRANSPORT|OUTPUT_SCHEMA_INVALID]}, abort?: bool}
   * 映射产物（节点 config 合并项）：{totalDurationMs=timeoutMs, maxSteps=1, retryMax, abortOnFailure}
   * 终止（abort=true 或 retry 耗尽按 abort）→下游阻断/跳过由下游任务的 task_step 状态承接。
   */
  public Map<String, Object> mapFailurePolicy(long taskId, String planId, int revision, String stepId) {
    var policy =
            store.db()
                    .queryForList(
                            "select ps.failure_policy from engine.plan_step ps"
                                + " join engine.plan_revision pr on pr.id=ps.plan_revision_id"
                                + " where pr.owner_task_id=? and pr.plan_id=? and pr.revision=? and ps.step_id=?",
                            taskId,
                            planId,
                            revision,
                            stepId);
    if (policy.isEmpty())
      throw new PlatformException(404, "PLAN_STEP_NOT_FOUND", "计划步骤不存在: " + stepId);
    JsonNode fp = Json.read(String.valueOf(policy.getFirst().get("failure_policy")), JsonNode.class);
    var mapped = new LinkedHashMap<String, Object>();
    if (fp.path("timeoutMs").isNumber()) mapped.put("totalDurationMs", fp.path("timeoutMs").asLong());
    if (fp.path("retry").isObject()) {
      mapped.put("retryMax", fp.path("retry").path("max").asInt(0));
      mapped.put("retryOn", fp.path("retry").path("on").isMissingNode() ? List.of() : fp.path("retry").path("on"));
    }
    mapped.put("abortOnFailure", fp.path("abort").asBoolean(false));
    return mapped;
  }

  /**
   * A08b：步骤失败时按 failure_policy 执行处置——attempt 序列完整记录（复用 attempt/attempt_note），
   * 终止→下游 task_step 置 BLOCKED（阻断）或 SKIPPED（跳过，required=false 分支）。
   *
   * @return {attemptRecorded, downstreamAction: BLOCKED|SKIPPED|CONTINUE}
   */
  public Map<String, Object> onFailure(long taskId, String stepId, long runId, String reasonCode, Actor actor) {
    // attempt 序列完整（复用现有 attempt 链——语义记账：失败原因码入 attempt_note）
    Long attemptId =
            store.db()
                    .queryForObject(
                            "select max(id) from engine.attempt where node_instance_id in"
                                + " (select id from engine.node_instance where run_id=?)",
                            Long.class,
                            runId);
    if (attemptId != null)
      store.db()
              .update(
                      "insert into engine.attempt_note(node_instance_id,attempt_id,kind,payload)"
                          + " values((select id from engine.node_instance where run_id=? limit 1),?,"
                          + " 'PLAN_STEP_FAILURE',?::jsonb)",
                      runId,
                      attemptId,
                      Json.write(Map.of("stepId", stepId, "reasonCode", reasonCode)));
    // 下游处置：查本步骤的下游（dependsOn 引用本步骤的 plan_step→task_step）
    var downstream =
            store.db()
                    .queryForList(
                            "select ts.step_id,ts.state,ps.required from engine.task_step ts"
                                + " join engine.plan_revision pr on pr.owner_task_id=?"
                                + " join engine.plan_step ps on ps.plan_revision_id=pr.id"
                                + " where ts.task_id=? and ps.step_id=ts.step_id"
                                + " and ps.depends_on::text like ?",
                            taskId,
                            taskId,
                            "%\"" + stepId + "\"%");
    String downstreamAction = "CONTINUE";
    for (var row : downstream) {
      boolean required = !Boolean.FALSE.equals(row.get("required"));
      String targetState = required ? "BLOCKED" : "SKIPPED";
      store.db()
              .update(
                      "update engine.task_step set state=?,reason_code=?,updated_at=now()"
                          + " where task_id=? and step_id=? and state in ('READY','QUEUED','BLOCKED')",
                      targetState,
                      reasonCode,
                      taskId,
                      String.valueOf(row.get("step_id")));
      downstreamAction = required ? "BLOCKED" : downstreamAction.equals("BLOCKED") ? "BLOCKED" : "SKIPPED";
    }
    appendTaskEvent(taskId, "STEP_FINISHED", Json.tree(Map.of("stepId", stepId, "state", "FAILED", "reasonCode", reasonCode)));
    return Map.of("attemptRecorded", attemptId != null, "downstreamAction", downstreamAction);
  }

  /** CONDITION 求值（受限表达式：读已提交上游结果，为假→SKIPPED——主方案 §5.6 表）。 */
  public Map<String, Object> evaluateCondition(long taskId, String stepId, JsonNode condition, Actor actor) {
    // 受限表达式首版：仅支持 {exists: <task_step 引用>} 或 {const: bool}——完整表达式求值复用图契约解释器（FlowDriver Values）
    boolean result;
    if (condition.path("const").isBoolean()) result = condition.path("const").asBoolean();
    else
      throw new PlatformException(
              400, "CONDITION_UNSUPPORTED", "受限表达式仅支持 {const:bool}（完整表达式随 FlowDriver 映射卡）");
    if (!result) {
      store.db()
              .update(
                      "update engine.task_step set state='SKIPPED',reason_code='CONDITION_FALSE',updated_at=now()"
                          + " where task_id=? and step_id=?",
                      taskId,
                      stepId);
    }
    return Map.of("stepId", stepId, "conditionResult", result, "state", result ? "READY" : "SKIPPED");
  }

  private String dispatchKey(long taskId, String stepId) {
    String tenant =
            store.db().queryForObject("select tenant_id from engine.task where id=?", String.class, taskId);
    Integer revision =
            store.db()
                    .queryForObject(
                            "select coalesce(selected_plan_revision_id,0) from engine.task where id=?",
                            Integer.class,
                            taskId);
    return cn.agentplatform.engine.application.Tasks.Digest.sha256(
            String.join(":", tenant, String.valueOf(taskId), String.valueOf(revision), stepId));
  }

  private String stepKind(long taskId, String stepId) {
    String kind =
            store.db()
                    .queryForObject(
                            "select coalesce((select ps.kind from engine.plan_step ps"
                                + " join engine.plan_revision pr on pr.id=ps.plan_revision_id"
                                + " where pr.owner_task_id=? and ps.step_id=? limit 1),'AGENT')",
                            String.class,
                            taskId,
                            stepId);
    return KINDS.contains(kind) ? kind : "AGENT";
  }

  private void appendTaskEvent(long taskId, String type, JsonNode payload) {
    Long next =
            store.db()
                    .queryForObject(
                            "select coalesce(max(seq),0)+1 from engine.task_event where task_id=?",
                            Long.class,
                            taskId);
    Integer version =
            store.db().queryForObject("select state_version from engine.task where id=?", Integer.class, taskId);
    store.db()
            .update(
                    "insert into engine.task_event(task_id,seq,type,payload,state_version)"
                        + " values(?,?,?,?::jsonb,?)",
                    taskId,
                    next.intValue(),
                    type,
                    Json.write(payload),
                    version);
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.task where id=? and tenant_id=? and principal_id=?",
                            taskId,
                            actor.tenantId(),
                            actor.principalId());
    if (rows.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在");
    return rows.getFirst();
  }
}
