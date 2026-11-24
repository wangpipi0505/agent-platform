package cn.agentplatform.engine.application;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * T2-6（台账 §7.3）：依赖调度+分派三要素——task_dispatch 认领/提交/接管与 task_dependency 满足放行。
 *
 * <p><b>包归属（application，而非 harness.loop）</b>：harness.loop 是会话内 turn 机器
 * （AgentEngine/Inbox/PreStepChain——turn 生命周期与 inbox 消费）；本服务操作任务域聚合表
 * （task_dispatch/task_dependency/task_step/task），承载任务步骤分派生命周期
 * （PENDING→LEASED→DONE）与跨任务 DAG 放行，与 {@link Tasks}/{@link StepExecutionMapping}/
 * {@link TaskReconciliation} 同属任务域应用服务——故落 application 包。
 *
 * <p><b>分派三要素职责划分（主方案 §5.5 末段，不混用）</b>：
 *
 * <ul>
 *   <li><b>dispatch_key（稳定键）=重复受理去重</b>——{@link #dispatch} 同 (task,step) 幂等（已存在返回
 *       既有）；已 DONE 的重复回执经 {@link #complete} 返回原结果不重复提交；分派中断后新认领者按
 *       原键关联既有分派行（run 关联见 T2-5 admit/库稿 run_id 列）。</li>
 *   <li><b>state_version（CAS）=拒绝过期状态更新</b>——步骤结果按事务内当前 state_version 条件更新
 *       单次提交（不一致 409 {@code TASK_STEP_STALE}，晚到更新不覆盖）；task 级 CAS 承接于
 *       {@link #dependencySatisfied} 的下游放行（WAITING_DEPENDENCIES→QUEUED）与 cancel/resume
 *       （T2-2 既有）。</li>
 *   <li><b>lease_owner/lease_until + generation=当前写入权</b>——{@link #claim} 认领（PENDING 或
 *       租约过期的 LEASED→LEASED，generation+1，owner/租约成对）→ {@link #complete} 提交
 *       （owner+generation+未过期租约三者齐备）→超时接管：<b>claim 的认领条件已含"租约过期的
 *       LEASED"</b>——旧执行者超时后新认领者直接 claim 即完成接管，{@link #takeoverScan()} 只做
 *       只读候选盘点，不重复接管逻辑。旧代次提交 409 {@code TASK_DISPATCH_STALE}，先以
 *       RECONCILIATION_REQUIRED 事件落对账留痕（可追溯不覆盖，台账"晚到回执按代次拒绝入对账"）
 *       再拒绝。</li>
 * </ul>
 *
 * <p><b>提交顺序（主方案 §5.5）</b>：先持久化 run 事实（执行体经 T2-5 逻辑 run 链与
 * TraceStore.commit 落库），再以条件更新单次提交步骤结果与下游就绪——{@link #complete} 单事务内：
 * task_step SUCCEEDED+result_digest → task_dispatch DONE → depends_on 下游 QUEUED→READY →
 * STEP_FINISHED 事件。验证口径（台账）：旧执行者超时→新执行者接管→旧回执随后到达——回执可追溯、
 * 成功结果与下游就绪只提交一次。
 *
 * <p>错误码（TASK_DISPATCH_STALE/TASK_STEP_STALE 已登记 contracts/error-codes.md §2，R-3 2026-11-18）：
 * 400 TASK_DISPATCH_DIGEST_INVALID /
 * 404 TASK_DISPATCH_NOT_FOUND / 409 TASK_DISPATCH_STALE（代次或租约或 owner 不匹配，同码同拒绝）/
 * 409 TASK_STEP_STALE（步骤状态版本条件未命中）/ 409 TASK_DEPENDENCY_UPSTREAM_NOT_SUCCEEDED /
 * 409 TASK_DEPENDENCY_DIGEST_MISMATCH / 404 TASK_DEPENDENCY_NOT_FOUND。
 */
@Service
public class TaskDispatcher {

  private static final int ENTRY_BATCH_SIZE = 32;
  private static final int ENTRY_LEASE_SECONDS = 30;
  private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
  private final TraceStore store;
  private final String entryOwner = "task-" + UUID.randomUUID();

  public TaskDispatcher(TraceStore store) {
    this.store = store;
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${agent.task.dispatch.poll-millis:500}")
  public void pollEntries() {
    var entries = store.db().queryForList(
        "select d.task_id from engine.task_dispatch d join engine.task t on t.id=d.task_id"
            + " join engine.run r on r.id=d.run_id"
            + " where d.step_id=? and t.state in ('QUEUED','RUNNING') and r.trigger_type in ('CHAT','TASK')"
            + " and d.available_at<=now() and (d.state='PENDING' or (d.state='LEASED' and"
            + " (d.lease_until<=now() or (d.lease_owner=? and r.status in ('SUCCEEDED','FAILED','CANCELLED')))))"
            + " order by d.available_at,d.task_id limit ?",
        Long.class, StepExecutionMapping.ENTRY, entryOwner, ENTRY_BATCH_SIZE);
    for (long taskId : entries) {
      try {
        store.atomic(() -> {
          settleEntry(taskId);
          return null;
        });
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(TaskDispatcher.class)
            .warn("Task dispatch retry taskId={} cause={}", taskId, failure.getClass().getSimpleName());
      }
    }
  }

  private void settleEntry(long taskId) {
    var tasks = store.db().queryForList("select state from engine.task where id=? for update",
        String.class, taskId);
    if (tasks.isEmpty() || TERMINAL.contains(tasks.getFirst())) return;
    var row = store.db().queryForMap(
        "select d.state,d.lease_owner,d.generation,d.lease_until>now() as live,"
            + " r.status,r.public_id,r.output::text as output from engine.task_dispatch d"
            + " join engine.run r on r.id=d.run_id where d.task_id=? and d.step_id=?",
        taskId, StepExecutionMapping.ENTRY);
    if ("DONE".equals(row.get("state"))) return;
    int generation;
    if (entryOwner.equals(row.get("lease_owner")) && Boolean.TRUE.equals(row.get("live"))) {
      generation = ((Number) row.get("generation")).intValue();
    } else {
      var receipt = claim(taskId, StepExecutionMapping.ENTRY, entryOwner, ENTRY_LEASE_SECONDS);
      if (receipt == null) return;
      generation = ((Number) receipt.get("generation")).intValue();
      store.db().update("update engine.task set state='RUNNING',state_version=state_version+1,"
          + " updated_at=now() where id=? and state='QUEUED'", taskId);
    }
    String terminal = row.get("status").toString();
    if (!TERMINAL.contains(terminal)) return;

    String resultDigest = null;
    String resultRef = null;
    if ("SUCCEEDED".equals(terminal)) {
      resultDigest = Digest.sha256(Json.canonical(TraceStore.json(row.get("output"))));
      resultRef = "pg:run/" + row.get("public_id") + "/output";
      complete(taskId, StepExecutionMapping.ENTRY, entryOwner, generation, resultDigest);
    } else {
      int finished = store.db().update("update engine.task_dispatch set state='DONE',updated_at=now()"
              + " where task_id=? and step_id=? and state='LEASED' and generation=? and lease_owner=?"
              + " and lease_until>now()", taskId, StepExecutionMapping.ENTRY, generation, entryOwner);
      if (finished == 0) return;
      store.db().update("update engine.task_step set state=?, state_version=state_version+1,"
              + " updated_at=now() where task_id=? and step_id=?", terminal, taskId, StepExecutionMapping.ENTRY);
      appendTaskEvent(taskId, "STEP_FINISHED", Json.tree(Map.of("stepId", StepExecutionMapping.ENTRY, "state", terminal)));
    }
    store.db().update("update engine.task set state=?,state_version=state_version+1,"
        + " result_ref=?,result_digest=?,updated_at=now() where id=?", terminal, resultRef, resultDigest, taskId);
    appendTaskEvent(taskId, "TASK_FINISHED", Json.tree(Map.of("state", terminal, "runId", row.get("public_id"))));
  }

  void rejectEntry(long taskId, String reason) {
    store.atomic(() -> {
      int changed = store.db().update("update engine.task set state='FAILED',state_version=state_version+1,"
          + " updated_at=now() where id=? and state='ACCEPTED'", taskId);
      if (changed == 1) appendTaskEvent(taskId, "TASK_FINISHED",
          Json.tree(Map.of("state", "FAILED", "reasonCode", reason)));
      return null;
    });
  }

  boolean cancelEntry(long taskId) {
    var linked = store.db().queryForList(
        "select d.run_id from engine.task_dispatch d join engine.run r on r.id=d.run_id"
            + " where d.task_id=? and d.step_id=? and r.trigger_type in ('CHAT','TASK')",
        Long.class, taskId, StepExecutionMapping.ENTRY);
    if (linked.isEmpty()) return true;
    long runId = linked.getFirst();
    int cancelled = store.db().update("update engine.run set status='CANCELLED',stop_reason='USER_CANCEL',"
            + " ended_at=now() where id=? and status not in ('SUCCEEDED','FAILED','CANCELLED')", runId);
    if (cancelled == 0) return false;
    // The run row serializes cancellation with new tool admission and dispatch.
    store.db().update("update engine.node_instance set status='CANCELLED',ended_at=now() where run_id=?"
        + " and status not in ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')", runId);
    store.db().update("update engine.tool_call set status='ABANDONED' where run_id=?"
        + " and status in ('REGISTERED','EXECUTING')", runId);
    store.event(runId, "RUN_FINISHED", Map.of("status", "CANCELLED"));
    store.db().update("update engine.task_step set state='CANCELLED',state_version=state_version+1,"
        + " updated_at=now() where task_id=? and step_id=? and state not in ('SUCCEEDED','FAILED','CANCELLED')",
        taskId, StepExecutionMapping.ENTRY);
    store.db().update("update engine.task_dispatch set state='DONE',updated_at=now()"
        + " where task_id=? and step_id=? and state<>'DONE'", taskId, StepExecutionMapping.ENTRY);
    return true;
  }

  /**
   * 稳定键 sha256(tenant/task/planRevision/step)（库稿 §4.2 口径）。planRevision 取
   * task.selected_plan_revision_id（直接任务无计划时按 0 参与）——与 StepExecutionMapping
   * （T2-5 admit 锚点）共用单点公式 {@link #dispatchKeyOf}，两侧同键。
   */
  public String dispatchKey(long taskId, String stepId) {
    var rows =
            store.db()
                    .queryForList(
                            "select tenant_id,coalesce(selected_plan_revision_id,0) as revision from"
                                + " engine.task where id=?",
                            taskId);
    if (rows.isEmpty())
      throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在: " + taskId);
    var t = rows.getFirst();
    return dispatchKeyOf(
            String.valueOf(t.get("tenant_id")),
            taskId,
            ((Number) t.get("revision")).longValue(),
            stepId);
  }

  /** 单点公式：sha256("tenant:taskId:planRevision:stepId")——T2-5 admit 与本服务共用，防止口径漂移。 */
  static String dispatchKeyOf(String tenant, long taskId, long planRevision, String stepId) {
    return Digest.sha256(
            String.join(":", tenant, String.valueOf(taskId), String.valueOf(planRevision), stepId));
  }

  /**
   * 落 task_dispatch 行（state=PENDING）——同 (task,step) 幂等：已存在返回既有行（不触碰
   * available_at/state，重复受理去重）。dispatch_key UNIQUE 由库稿兜底（sha256 碰撞即冲突显式抛出）。
   * available_at 缺省取当前时刻（最早可执行排序）。
   *
   * @return {taskId, stepId, dispatchKey, state, availableAt, created}
   */
  public Map<String, Object> dispatch(long taskId, String stepId, Instant availableAt) {
    requireTask(taskId);
    requireStep(taskId, stepId);
    String key = dispatchKey(taskId, stepId);
    Timestamp at = availableAt == null ? Timestamp.from(Instant.now()) : Timestamp.from(availableAt);
    int inserted =
            store.atomic(
                    () ->
                            store
                                    .db()
                                    .update(
                                            "insert into"
                                                + " engine.task_dispatch(task_id,step_id,dispatch_key,state,available_at)"
                                                + " values(?,?,?,'PENDING',?) on conflict"
                                                + " (task_id,step_id) do nothing",
                                            taskId,
                                            stepId,
                                            key,
                                            at));
    boolean created = inserted == 1;
    var row =
            store
                    .db()
                    .queryForMap(
                            "select * from engine.task_dispatch where task_id=? and step_id=?",
                            taskId,
                            stepId);
    var out = new LinkedHashMap<String, Object>();
    out.put("taskId", taskId);
    out.put("stepId", stepId);
    out.put("dispatchKey", String.valueOf(row.get("dispatch_key")));
    out.put("state", String.valueOf(row.get("state")));
    out.put("availableAt", String.valueOf(row.get("available_at")));
    out.put("created", created);
    return out;
  }

  /**
   * 原子认领：PENDING 或租约已过期的 LEASED → LEASED，generation+1，lease_owner/lease_until 成对写入
   * （三要素之"当前写入权"）。并发认领由行锁+条件更新谓词重查保证恰一人成功（READ COMMITTED 下
   * 后到者按新行版本重判谓词，无需 SKIP LOCKED）。无可认领返回 null。
   *
   * <p>同事务顺带：task_step READY/QUEUED→RUNNING + STEP_STARTED 事件（认领即开始的步骤生命周期事实；
   * 接管代次的重复 STEP_STARTED 是事实流上的新事实，不覆盖）。终态步骤（SUCCEEDED/FAILED/CANCELLED/
   * SKIPPED）拒绝再认领（逻辑步骤只执行一次的兜底，最终一致由对账承接）。
   *
   * @return {taskId, stepId, dispatchKey, state=LEASED, generation, leaseOwner, leaseUntil, runId} 或 null
   */
  public Map<String, Object> claim(long taskId, String stepId, String owner, int leaseSeconds) {
    if (owner == null || owner.isBlank() || owner.length() > 64)
      throw new PlatformException(400, "TASK_DISPATCH_OWNER_INVALID", "owner 必填且 ≤64 字");
    if (leaseSeconds <= 0 || leaseSeconds > 86400)
      throw new PlatformException(400, "TASK_DISPATCH_LEASE_INVALID", "leaseSeconds 必须为正（≤86400）");
    return store.atomic(
            () -> {
              // 步骤非终态守卫（行锁顺带串行化同一步骤的并发认领）
              var steps =
                      store.db()
                              .queryForList(
                                      "select state from engine.task_step where task_id=? and"
                                          + " step_id=? for update",
                                      taskId,
                                      stepId);
              if (steps.isEmpty())
                throw new PlatformException(404, "TASK_STEP_NOT_FOUND", "任务步骤不存在: " + stepId);
              if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "SKIPPED")
                      .contains(String.valueOf(steps.getFirst().get("state"))))
                return null;
              int changed =
                      store
                              .db()
                              .update(
                                      "update engine.task_dispatch set state='LEASED',lease_owner=?,"
                                          + "lease_until=now()+make_interval(secs=>(?::double"
                                          + " precision)),generation=generation+1,updated_at=now()"
                                          + " where task_id=? and step_id=? and (state='PENDING' or"
                                          + " (state='LEASED' and lease_until<=now()))",
                                      owner,
                                      leaseSeconds,
                                      taskId,
                                      stepId);
              if (changed == 0) return null; // 无可认领：无分派行或他人持有效租约
              store.db()
                      .update(
                              "update engine.task_step set state='RUNNING',state_version=state_version+1,"
                                  + "updated_at=now() where task_id=? and step_id=? and state in"
                                  + " ('READY','QUEUED')",
                              taskId,
                              stepId);
              var row =
                      store.db()
                              .queryForMap(
                                      "select * from engine.task_dispatch where task_id=? and step_id=?",
                                      taskId,
                                      stepId);
              appendTaskEvent(
                      taskId,
                      "STEP_STARTED",
                      Json.tree(
                              Map.of(
                                      "stepId",
                                      stepId,
                                      "owner",
                                      owner,
                                      "generation",
                                      ((Number) row.get("generation")).intValue())));
              var out = new LinkedHashMap<String, Object>();
              out.put("taskId", taskId);
              out.put("stepId", stepId);
              out.put("dispatchKey", String.valueOf(row.get("dispatch_key")));
              out.put("state", "LEASED");
              out.put("generation", ((Number) row.get("generation")).intValue());
              out.put("leaseOwner", String.valueOf(row.get("lease_owner")));
              out.put("leaseUntil", String.valueOf(row.get("lease_until")));
              out.put("runId", row.get("run_id"));
              return out;
            });
  }

  /**
   * 三要素齐验的回执提交（验收核心）：owner+generation 匹配当前行且租约未过期才提交——单事务内
   * task_step SUCCEEDED+结果摘要 → task_dispatch DONE → depends_on 下游 QUEUED→READY（下游就绪）→
   * STEP_FINISHED 事件。不匹配（generation 过期/owner 不符/租约已过）→ <b>409
   * TASK_DISPATCH_STALE</b>：先以 RECONCILIATION_REQUIRED 事件落对账留痕（独立事务，晚到回执可追溯
   * 不覆盖），再拒绝——成功结果与下游就绪只提交一次。
   *
   * <p>幂等分支（dispatch_key 去重）：行已 DONE 的重复回执返回原结果（committed=false，
   * alreadyDone=true），不产生第二次提交。
   *
   * @return {taskId, stepId, dispatchKey, state=DONE, committed, alreadyDone?, resultDigest, generation?, releasedSteps?}
   */
  public Map<String, Object> complete(
          long taskId, String stepId, String owner, int generation, String resultDigest) {
    if (resultDigest == null || !resultDigest.matches("^[a-f0-9]{64}$"))
      throw new PlatformException(
              400, "TASK_DISPATCH_DIGEST_INVALID", "resultDigest 必须为 64 位十六进制摘要");
    // holder：atomic 内填充提交结果（成功路径）或留痕信息（拒绝路径，随事件落库）
    var holder = new LinkedHashMap<String, Object>();
    String[] outcome = new String[1];
    outcome[0] =
            store.atomic(
                    () -> {
                      var rows =
                              store.db()
                                      .queryForList(
                                              "select * from engine.task_dispatch where task_id=? and"
                                                  + " step_id=? for update",
                                              taskId,
                                              stepId);
                      if (rows.isEmpty())
                        throw new PlatformException(
                                404, "TASK_DISPATCH_NOT_FOUND", "分派行不存在: step=" + stepId);
                      var row = rows.getFirst();
                      String dispatchKey = String.valueOf(row.get("dispatch_key"));
                      String state = String.valueOf(row.get("state"));
                      int currentGeneration = ((Number) row.get("generation")).intValue();
                      holder.put("taskId", taskId);
                      holder.put("stepId", stepId);
                      holder.put("dispatchKey", dispatchKey);
                      if ("DONE".equals(state)) {
                        // dispatch_key（重复受理去重）：重复回执返回原结果，不重复提交
                        // （结果摘要在 task_step.result_digest——task_dispatch 无该列）
                        var committed =
                                store.db()
                                        .queryForList(
                                                "select result_digest from engine.task_step where"
                                                    + " task_id=? and step_id=?",
                                                taskId,
                                                stepId);
                        holder.put("state", "DONE");
                        holder.put("committed", false);
                        holder.put("alreadyDone", true);
                        holder.put(
                                "resultDigest",
                                committed.isEmpty() || committed.getFirst().get("result_digest") == null
                                        ? null
                                        : String.valueOf(committed.getFirst().get("result_digest")));
                        return "DONE";
                      }
                      Timestamp leaseUntil = (Timestamp) row.get("lease_until");
                      boolean liveLease =
                              leaseUntil != null && leaseUntil.after(Timestamp.from(Instant.now()));
                      boolean authority =
                              "LEASED".equals(state)
                                      && owner != null
                                      && owner.equals(String.valueOf(row.get("lease_owner")))
                                      && generation == currentGeneration
                                      && liveLease;
                      if (!authority) {
                        holder.put("receiptOwner", owner);
                        holder.put("receiptGeneration", generation);
                        holder.put(
                                "currentOwner",
                                row.get("lease_owner") == null
                                        ? null
                                        : String.valueOf(row.get("lease_owner")));
                        holder.put("currentGeneration", currentGeneration);
                        holder.put("currentState", state);
                        holder.put("reasonCode", "TASK_DISPATCH_STALE");
                        holder.put(
                                "message",
                                "回执代次/租约不匹配（回执 gen="
                                        + generation
                                        + "，当前 gen="
                                        + currentGeneration
                                        + " 状态="
                                        + state
                                        + "）——晚到回执已入对账留痕");
                        return "STALE";
                      }
                      // state_version（CAS）：步骤结果按事务内当前版本条件更新单次提交
                      var steps =
                              store.db()
                                      .queryForList(
                                              "select state,state_version from engine.task_step"
                                                  + " where task_id=? and step_id=? for update",
                                              taskId,
                                              stepId);
                      if (steps.isEmpty())
                        throw new PlatformException(
                                404, "TASK_STEP_NOT_FOUND", "任务步骤不存在: " + stepId);
                      int stepVersion =
                              ((Number) steps.getFirst().get("state_version")).intValue();
                      int stepChanged =
                              store
                                      .db()
                                      .update(
                                              "update engine.task_step set"
                                                  + " state='SUCCEEDED',result_digest=?,state_version="
                                                  + "state_version+1,updated_at=now() where"
                                                  + " task_id=? and step_id=? and state_version=? and"
                                                  + " state not in ('SUCCEEDED','SKIPPED','CANCELLED')",
                                              resultDigest,
                                              taskId,
                                              stepId,
                                              stepVersion);
                      if (stepChanged == 0) {
                        holder.put("receiptOwner", owner);
                        holder.put("receiptGeneration", generation);
                        holder.put("currentGeneration", currentGeneration);
                        holder.put("currentState", state);
                        holder.put("reasonCode", "TASK_STEP_STALE");
                        holder.put("message", "步骤状态版本已变化——条件提交未命中，回执已入对账留痕");
                        return "STALE";
                      }
                      int doneChanged =
                              store
                                      .db()
                                      .update(
                                              "update engine.task_dispatch set state='DONE',updated_at=now()"
                                                  + " where task_id=? and step_id=? and state='LEASED'"
                                                  + " and lease_owner=? and generation=?",
                                              taskId,
                                              stepId,
                                              owner,
                                              generation);
                      if (doneChanged != 1) throw new IllegalStateException("TASK_DISPATCH_COMMIT_LOST");
                      var released = releaseDownstreamSteps(taskId, stepId);
                      appendTaskEvent(
                              taskId,
                              "STEP_FINISHED",
                              Json.tree(
                                      Map.of(
                                              "stepId",
                                              stepId,
                                              "state",
                                              "SUCCEEDED",
                                              "resultDigest",
                                              resultDigest,
                                              "generation",
                                              generation)));
                      holder.put("state", "DONE");
                      holder.put("committed", true);
                      holder.put("resultDigest", resultDigest);
                      holder.put("generation", currentGeneration);
                      holder.put("releasedSteps", released);
                      return "COMMITTED";
                    });
    if ("STALE".equals(outcome[0])) {
      // 晚到回执可追溯不覆盖：RECONCILIATION_REQUIRED 落对账留痕（独立事务持久化）后再 409
      store.atomic(
              () -> {
                appendTaskEvent(
                        taskId, "RECONCILIATION_REQUIRED", Json.tree(new LinkedHashMap<>(holder)));
                return null;
              });
      throw new PlatformException(
              409, String.valueOf(holder.get("reasonCode")), String.valueOf(holder.get("message")));
    }
    return holder;
  }

  /**
   * 接管候选扫描（<b>只读</b>）：租约已过期的 LEASED 行。<b>接管本身不在此实现</b>——{@link #claim}
   * 的认领条件已含"租约过期的 LEASED"（旧执行者超时后新认领者直接 claim 即完成接管，generation+1），
   * 本方法供编排/运维侧盘点过期租约，不重复接管逻辑（台账 T2-6 交付口径）。
   */
  public List<Map<String, Object>> takeoverScan() {
    return store.db()
            .queryForList(
                    "select task_id,step_id,dispatch_key,lease_owner,lease_until,generation from"
                        + " engine.task_dispatch where state='LEASED' and lease_until<=now()"
                        + " order by lease_until,task_id,step_id");
  }

  /**
   * 依赖满足放行（库稿 §4.2）：上游任务 SUCCEEDED 且结果摘要匹配 → 写 task_dependency.satisfied_at
   * （放行下游）；该任务全部依赖满足后，经 task.state_version CAS 将 WAITING_DEPENDENCIES→QUEUED
   * （拒绝过期状态更新——版本不一致不覆盖，released=false 如实返回）。上游未成功/摘要不匹配 409
   * 显式暴露（不静默、不留 satisfied_at）。
   *
   * @return {taskId, upstreamTaskId, satisfiedAt, released}
   */
  public Map<String, Object> dependencySatisfied(long taskId, long upstreamTaskId, String resultDigest) {
    if (resultDigest == null || !resultDigest.matches("^[a-f0-9]{64}$"))
      throw new PlatformException(
              400, "TASK_DEPENDENCY_DIGEST_INVALID", "resultDigest 必须为 64 位十六进制摘要");
    return store.atomic(
            () -> {
              var deps =
                      store.db()
                              .queryForList(
                                      "select * from engine.task_dependency where task_id=? and"
                                          + " upstream_task_id=? for update",
                                      taskId,
                                      upstreamTaskId);
              if (deps.isEmpty())
                throw new PlatformException(404, "TASK_DEPENDENCY_NOT_FOUND", "依赖关系不存在");
              var ups =
                      store.db()
                              .queryForList(
                                      "select state,result_digest from engine.task where id=?",
                                      upstreamTaskId);
              if (ups.isEmpty())
                throw new PlatformException(404, "TASK_NOT_FOUND", "上游任务不存在: " + upstreamTaskId);
              var up = ups.getFirst();
              String upstreamState = String.valueOf(up.get("state"));
              String upstreamDigest =
                      up.get("result_digest") == null ? null : String.valueOf(up.get("result_digest"));
              if (!"SUCCEEDED".equals(upstreamState))
                throw new PlatformException(
                        409,
                        "TASK_DEPENDENCY_UPSTREAM_NOT_SUCCEEDED",
                        "上游任务尚未成功（state=" + upstreamState + "）");
              if (!resultDigest.equals(upstreamDigest))
                throw new PlatformException(409, "TASK_DEPENDENCY_DIGEST_MISMATCH", "上游结果摘要不匹配");
              store.db()
                      .update(
                              "update engine.task_dependency set satisfied_at=now(),updated_at=now()"
                                  + " where task_id=? and upstream_task_id=?",
                              taskId,
                              upstreamTaskId);
              Integer unsatisfied =
                      store.db()
                              .queryForObject(
                                      "select count(*) from engine.task_dependency where task_id=?"
                                          + " and satisfied_at is null",
                                      Integer.class,
                                      taskId);
              boolean released = false;
              if (unsatisfied != null && unsatisfied == 0) {
                var t =
                        store.db()
                                .queryForList(
                                        "select state,state_version from engine.task where id=? for update",
                                        taskId);
                if (!t.isEmpty()
                        && "WAITING_DEPENDENCIES".equals(String.valueOf(t.getFirst().get("state")))) {
                  released =
                          store.db()
                                          .update(
                                                  "update engine.task set state='QUEUED',state_version="
                                                      + "state_version+1,updated_at=now() where id=?"
                                                      + " and state='WAITING_DEPENDENCIES' and"
                                                      + " state_version=?",
                                                  taskId,
                                                  ((Number) t.getFirst().get("state_version"))
                                                          .intValue())
                                  == 1;
                }
              }
              var after =
                      store.db()
                              .queryForMap(
                                      "select satisfied_at from engine.task_dependency where"
                                          + " task_id=? and upstream_task_id=?",
                                      taskId,
                                      upstreamTaskId);
              var out = new LinkedHashMap<String, Object>();
              out.put("taskId", taskId);
              out.put("upstreamTaskId", upstreamTaskId);
              out.put("satisfiedAt", String.valueOf(after.get("satisfied_at")));
              out.put("released", released);
              return out;
            });
  }

  /**
   * 同事务下游就绪（主方案 §5.5 提交顺序的"下游就绪"半边）：plan_step.depends_on 引用本步骤的
   * 同任务下游 task_step QUEUED→READY（条件更新，不触碰 BLOCKED/SKIPPED——失败处置归 T2-5 A08b）。
   */
  private List<String> releaseDownstreamSteps(long taskId, String stepId) {
    var downstream =
            store.db()
                    .queryForList(
                            "select ts.step_id from engine.task_step ts"
                                + " join engine.plan_revision pr on pr.owner_task_id=?"
                                + " join engine.plan_step ps on ps.plan_revision_id=pr.id and"
                                + " ps.step_id=ts.step_id"
                                + " where ts.task_id=? and ps.depends_on::text like ?",
                            taskId,
                            taskId,
                            "%\"" + stepId + "\"%");
    var released = new ArrayList<String>();
    for (var d : downstream) {
      String sid = String.valueOf(d.get("step_id"));
      int n =
              store.db()
                      .update(
                              "update engine.task_step set state='READY',state_version="
                                  + "state_version+1,updated_at=now() where task_id=? and step_id=?"
                                  + " and state='QUEUED'",
                              taskId,
                              sid);
      if (n == 1) released.add(sid);
    }
    return released;
  }

  private void requireTask(long taskId) {
    Integer n =
            store.db()
                    .queryForObject(
                            "select count(*) from engine.task where id=?", Integer.class, taskId);
    if (n == null || n == 0)
      throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在: " + taskId);
  }

  private void requireStep(long taskId, String stepId) {
    Integer n =
            store.db()
                    .queryForObject(
                            "select count(*) from engine.task_step where task_id=? and step_id=?",
                            Integer.class,
                            taskId,
                            stepId);
    if (n == null || n == 0)
      throw new PlatformException(404, "TASK_STEP_NOT_FOUND", "任务步骤不存在: " + stepId);
  }

  /** 任务事件追加（照 Tasks/StepExecutionMapping 同款：seq=max+1，state_version 取任务当前值）。 */
  private void appendTaskEvent(long taskId, String type, JsonNode payload) {
    Long next =
            store.db()
                    .queryForObject(
                            "select coalesce(max(seq),0)+1 from engine.task_event where task_id=?",
                            Long.class,
                            taskId);
    Integer version =
            store.db()
                    .queryForObject(
                            "select state_version from engine.task where id=?", Integer.class, taskId);
    store.db()
            .update(
                    "insert into engine.task_event(task_id,seq,type,payload,state_version)"
                        + " values(?,?,?,?::jsonb,?)",
                    taskId,
                    next == null ? 1 : next.intValue(),
                    type,
                    Json.write(payload),
                    version);
  }
}
