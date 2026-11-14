package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * T2-7（台账 §7.3）：规划子代理——TASK_INTERNAL 根会话派生五态子代理、失败回执、一次合并与
 * 目录查询（主方案 §5.5，库稿 §4.1 subagent_catalog / §4.2 subtask_receipt）。
 *
 * <p><b>本地子代理＝普通会话</b>：子会话 {@code origin='SUBAGENT'、parent_session_id=父会话、
 * delegation_depth=父+1}（本卡规划子代理固定父=TASK_INTERNAL 根会话、depth=1）。目录三件套
 * （chat_session 扩列/subagent_catalog/五态 stop_reason）由 H1-1 V229 迁移交付，本服务首次消费。
 *
 * <p><b>受理事务幂等</b>：{@link #startPlannerSubagents} 以 (parent_task_id, role) 为重放键——
 * 同父任务同角色重复调用返回既有子任务集（事务内先锁父任务行 FOR UPDATE 再查再插，重放与并发
 * 双调用都只建一批）；与 T2-2 受理根会话（requestId 幂等分支先行返回）衔接成 A03 主断言：
 * 无用户会话 PLAN 任务启用规划子代理→受理建一个 TASK_INTERNAL 根会话，同请求重放不另建。
 *
 * <p><b>回执（settlement）与一次合并</b>（库稿 §4.2 subtask_receipt 行注释为字段权威）：
 * <ul>
 *   <li>子代理终态五值 {@code COMPLETED/ABORTED/ERROR/MAX_TOKENS/REFUSAL}（V229 CHECK）；
 *       COMPLETED 必须携带候选 {@code candidateRevisionId}（子任务名下通过结构校验的
 *       plan_revision），其余四态必须携带 error_code——"终态无产物仍提交失败回执"落为
 *       COMPLETED 无候选 400，由调用方改提交失败回执。</li>
 *   <li>{@link #settle} 单事务：锁子任务行→终态 CAS（SUCCEEDED/FAILED）→回执单条落库（写入时
 *       验证 role 非空+终态同事务成立）→catalog ended_at/stop_reason 回填→settlement 注入
 *       父会话（{@code subagent/settled} 事件）+ 父任务 {@code SUBTASK_SETTLED} 事件。</li>
 *   <li><b>重复/迟到回执保留审计不覆盖</b>：同子任务同结果返回原记录（duplicate）；同子任务
 *       异结果（冲突）记 {@code AUDIT SUBTASK_RECEIPT_CONFLICT} 审计行后仍返回原记录；父任务
 *       已合并（任一回执 merged_decision≠PENDING）后到达的回执（duplicate/conflict 皆然）再记
 *       {@code AUDIT SUBTASK_RECEIPT_LATE}——一律不触碰已提交候选与 merged_decision。引擎无独立
 *       审计表（审计归口 monitor），照 ChatSessions/Tasks 先例落结构化日志行。</li>
 *   <li><b>合并事实锁在 receipt 行</b>（contracts/task-states.md §5）：{@link #merge} 在全部子任务
 *       终态后只合并一次——以"存在 merged_decision≠PENDING 的回执"为已合并事实，重复 merge 幂等
 *       返回原结果（replay=true）不重算；有有效候选→候选回执 ADOPTED、其余 DISCARDED、父任务
 *       PLAN_READY（结构校验在回执提交前完成——库稿"候选=通过结构校验的候选版本"；NEEDS_INPUT
 *       分支随 T2-4 校验器接线）；无有效候选→父任务 FAILED + TASK_FINISHED 原因码
 *       {@code SUBAGENT_NO_VALID_CANDIDATE}（本卡自待登记区转正登记，contracts/error-codes.md §2）。
 *       预算截止强制合并（对未终态子任务先弃用再合并）随 T2-5 预算扣减接线，本卡合并前置=
 *       全部子任务终态（409 {@code SUBTASKS_NOT_SETTLED}）。</li>
 * </ul>
 *
 * <p><b>目录查询</b> {@link #catalog}（接口稿 §5.3）：按父会话返回
 * {@code {children:[{childSessionId,childTaskId,label,status,stopReason?,stopDisplay,startedAt,
 * endedAt,durationMs}],nextAfter?}}，按 {@code startedAt,childSessionId} 稳定排序，after/limit
 * 分页（nextAfter=下一页偏移，取尽为 null）。分角色：OPERATOR/ADMIN 限本租户、可见终态原因码；
 * 门户主体（USER/EDITOR 等）限本人父会话（停因映射为展示摘要，不回原始码）；SERVICE 票据面
 * 不开放（authority 行 integrationAllowed=false，网关 IntegrationPolicy 拒绝）。无权/不存在
 * 一律 404（照 ChatSessions.owned 口径，不泄露存在性）。目录不返回提示词、调用参数与输出正文。
 *
 * <p>错误码（contracts/error-codes.md 之外新增，随卡登记，照 T2-6 先例）：400
 * SUBAGENT_REQUEST_INVALID / SUBAGENT_STOP_REASON_INVALID / SUBAGENT_RECEIPT_INVALID /
 * SUBAGENTS_CURSOR_INVALID / SUBAGENTS_LIMIT_INVALID；404 SUBTASK_NOT_FOUND；
 * 409 SUBAGENT_CANDIDATE_INVALID / SUBTASKS_NOT_SETTLED / STATE_VERSION_CONFLICT（沿用）；
 * 403 SUBAGENTS_ROLE_FORBIDDEN；SUBAGENT_NO_VALID_CANDIDATE 为任务终态原因码（已转正登记）。
 */
@Service
public class SubagentPlanning {

  /** 子代理终态五值（V229 subagent_catalog_stop_reason_check——与库稿 §4.1 一致）。 */
  static final Set<String> STOP_REASONS = Set.of("COMPLETED", "ABORTED", "ERROR", "MAX_TOKENS", "REFUSAL");
  private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
  /** 单父任务单角色规划子代理上限（性能②并行规划量级；maxChildren 策略由受理入参约束）。 */
  private static final int MAX_CHILDREN = 8;

  private final TraceStore store;
  /** 会话事件日志（H1-1 契约四条）——与本服务共用 TraceStore 事务边界（append 加入外层 atomic）。 */
  private final SessionStore sessions;

  public SubagentPlanning(TraceStore store) {
    this.store = store;
    this.sessions = new SessionStore(store);
  }

  /**
   * 为 PLAN 任务创建 {@code count} 个规划子代理（子会话+目录行+子任务行，一个受理事务）。
   *
   * <p>前置：任务存在且 mode=PLAN（规划子任务，1109 §4.2）；父会话（task.session_id——无用户
   * 会话任务为 T2-2 受理创建的 TASK_INTERNAL 根会话）已绑定。子任务继承父任务的发布快照钉住、
   * 预算（父预留）与工具上界；requestId 派生自父任务受理号（同租户同主体下唯一，UNIQUE 兜底）。
   * outputSchema（PlanRevision 候选 Schema）随 {@code subagent/registered} 事件留痕供运行时
   * 校验消费（log-only 持久，重启可重放），不落结构列。
   *
   * @return {parentTaskId, parentSessionId, role, created, children:[{childTaskId, childSessionId, label, seq}]}
   */
  public Map<String, Object> startPlannerSubagents(
          long parentTaskId, String role, int count, JsonNode outputSchema) {
    if (role == null || role.isBlank() || role.length() > 48)
      throw new PlatformException(400, "SUBAGENT_REQUEST_INVALID", "规划子代理必须提供 role（≤48 字）");
    if (count < 1 || count > MAX_CHILDREN)
      throw new PlatformException(
              400, "SUBAGENT_REQUEST_INVALID", "规划子代理数量须为 1 至 " + MAX_CHILDREN);
    if (outputSchema != null && !outputSchema.isNull()) {
      if (!outputSchema.isObject() || outputSchema.toString().length() > 65536)
        throw new PlatformException(400, "SUBAGENT_REQUEST_INVALID", "outputSchema 必须为对象且 ≤64KB");
    }
    var parents = store.db().queryForList("select * from engine.task where id=?", parentTaskId);
    if (parents.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在: " + parentTaskId);
    var parent = parents.getFirst();
    if (!"PLAN".equals(String.valueOf(parent.get("mode"))))
      throw new PlatformException(409, "SUBAGENT_REQUEST_INVALID", "规划子代理仅受理 PLAN 任务");

    return store.atomic(
            () -> {
              // 受理事务幂等：锁父任务行串行化并发 start，再查重放键 (parent_task_id, role)。
              store.db()
                      .queryForObject("select 1 from engine.task where id=? for update", Long.class, parentTaskId);
              var existing = childrenRows(parentTaskId, role);
              String parentSessionId = String.valueOf(parent.get("session_id"));
              if (parent.get("session_id") == null)
                throw new PlatformException(
                        400, "SUBAGENT_REQUEST_INVALID", "任务未绑定父会话，禁止派发规划子代理");
              if (!existing.isEmpty()) return view(parentTaskId, parentSessionId, role, existing, false);
              int parentDepth =
                      ((Number)
                                      store.db()
                                              .queryForMap(
                                                      "select delegation_depth from engine.chat_session where public_id=?",
                                                      parentSessionId)
                                              .get("delegation_depth"))
                              .intValue();
              for (int i = 1; i <= count; i++) {
                createChild(parent, parentTaskId, parentSessionId, parentDepth, role, i, outputSchema);
              }
              return view(parentTaskId, parentSessionId, role, childrenRows(parentTaskId, role), true);
            });
  }

  /** 单个子代理三件套：SUBAGENT 子会话 + 子任务行 + 目录行（start_seq=父会话登记事件 seq）。 */
  private Map<String, Object> createChild(
          Map<String, Object> parent,
          long parentTaskId,
          String parentSessionId,
          int parentDepth,
          String role,
          int seq,
          JsonNode outputSchema) {
    String childSessionId = "sa-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    String childPublicId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    String tenant = String.valueOf(parent.get("tenant_id"));
    // 子任务受理号：父受理号派生（同租户同主体唯一）；超长截父段保角色与序号后缀（可定位）。
    String suffix = ":" + role + ":" + seq;
    String parentRequestId = String.valueOf(parent.get("request_id"));
    String subRequestId =
            (parentRequestId.length() + suffix.length() <= 128)
                    ? parentRequestId + suffix
                    : parentRequestId.substring(0, 128 - suffix.length()) + suffix;
    String inputDigest =
            Tasks.Digest.sha256(
                    Json.canonical(
                            Map.of(
                                    "v", "SUBTASK_INPUT_V1",
                                    "parentTask", parentTaskId,
                                    "role", role,
                                    "seq", seq,
                                    "outputSchema", outputSchema == null ? "" : outputSchema)));
    store.db()
            .update(
                    "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,"
                        + "origin,parent_session_id,delegation_depth,event_watermark)"
                        + " values(?,?,?,?,'{}'::jsonb,'SUBAGENT',?,?,0)",
                    childSessionId,
                    tenant,
                    String.valueOf(parent.get("principal_id")),
                    String.valueOf(parent.get("app_code")),
                    parentSessionId,
                    parentDepth + 1);
    store.db()
            .update(
                    "insert into engine.task(public_id,tenant_id,principal_id,app_code,config_release_id,"
                        + "config_digest,mode,purpose,goal,input_ref,input_digest,parent_task_id,role,"
                        + "session_id,state,request_id,request_digest,tool_policy,budget)"
                        + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'PLANNING',?,?,?::jsonb,?::jsonb)",
                    childPublicId,
                    tenant,
                    String.valueOf(parent.get("principal_id")),
                    String.valueOf(parent.get("app_code")),
                    String.valueOf(parent.get("config_release_id")),
                    String.valueOf(parent.get("config_digest")),
                    "PLAN",
                    "PLAN",
                    String.valueOf(parent.get("goal")),
                    "subtask-input/" + childPublicId,
                    inputDigest,
                    parentTaskId,
                    role,
                    childSessionId,
                    subRequestId,
                    Tasks.Digest.sha256(
                            Json.canonical(
                                    Map.of(
                                            "v", "SUBTASK_ACCEPT_V1",
                                            "requestId", subRequestId,
                                            "parentTask", parentTaskId,
                                            "role", role,
                                            "seq", seq))),
                    parent.get("tool_policy") == null
                            ? null
                            : String.valueOf(parent.get("tool_policy")),
                    String.valueOf(parent.get("budget")));
    long childTaskId =
            store.db().queryForObject("select id from engine.task where public_id=?", Long.class, childPublicId);
    // 目录与登记事件同事务：start_seq=父会话 subagent/registered 事件 seq（溯源，库稿 §4.1）。
    long startSeq =
            sessions.append(
                    parentSessionId,
                    null,
                    SessionEvents.SUBAGENT_REGISTERED,
                    Json.tree(
                            Map.of(
                                    "childSessionId", childSessionId,
                                    "childTaskId", childPublicId,
                                    "label", label(role, seq),
                                    "role", role,
                                    "depth", parentDepth + 1,
                                    "outputSchema", outputSchema == null ? Json.object() : outputSchema)),
                    null);
    store.db()
            .update(
                    "insert into engine.subagent_catalog(parent_session_id,child_session_id,label,start_seq,started_at)"
                        + " values(?,?,?,?,now())",
                    parentSessionId,
                    childSessionId,
                    label(role, seq),
                    (int) startSeq);
    appendTaskEvent(
            parentTaskId,
            "SUBTASK_REGISTERED",
            Map.of(
                    "subtaskId", childPublicId,
                    "childSessionId", childSessionId,
                    "role", role,
                    "label", label(role, seq)));
    return Map.of("childTaskId", childPublicId, "childSessionId", childSessionId, "label", label(role, seq), "seq", seq);
  }

  /**
   * 子代理终态回执：单条落库 + catalog 终态回填 + settlement 注入父会话（主方案 §5.5）。
   * 重复/迟到/冲突回执保留审计返回原记录，不覆盖已提交候选与合并决定（库稿 §4.2 行注释）。
   *
   * @param childSessionId      子代理会话号（定位唯一子任务）
   * @param stopReason          五态终因 COMPLETED|ABORTED|ERROR|MAX_TOKENS|REFUSAL
   * @param candidateRevisionId COMPLETED 必填——子任务名下通过结构校验的候选修订；其余四态必须为空
   * @param errorCode           非 COMPLETED 必填（≤32 字，落 receipt.error_code）；COMPLETED 必须为空
   * @return {childTaskId, childSessionId, stopReason, state, candidateRevisionId, errorCode,
   *         duplicate, conflict, late}——duplicate=同结果重放（返回原记录）；conflict=同子任务异结果
   *         （审计后返回原记录）；late=父任务已合并后到达（审计，merged_decision 不覆盖）
   */
  public Map<String, Object> settle(
          String childSessionId, String stopReason, Long candidateRevisionId, String errorCode) {
    if (childSessionId == null || childSessionId.isBlank())
      throw new PlatformException(400, "SUBAGENT_REQUEST_INVALID", "回执必须提供 childSessionId");
    if (stopReason == null || !STOP_REASONS.contains(stopReason))
      throw new PlatformException(
              400, "SUBAGENT_STOP_REASON_INVALID", "停因必须为五态: " + String.join("|", STOP_REASONS));
    boolean success = "COMPLETED".equals(stopReason);
    if (success && candidateRevisionId == null)
      throw new PlatformException(
              400, "SUBAGENT_RECEIPT_INVALID", "COMPLETED 回执必须携带候选修订（终态无产物应提交失败回执）");
    if (success && errorCode != null && !errorCode.isBlank())
      throw new PlatformException(400, "SUBAGENT_RECEIPT_INVALID", "成功回执不携带 error_code");
    if (!success) {
      if (candidateRevisionId != null)
        throw new PlatformException(400, "SUBAGENT_RECEIPT_INVALID", "失败回执不携带候选修订");
      if (errorCode == null || errorCode.isBlank() || errorCode.length() > 32)
        throw new PlatformException(400, "SUBAGENT_RECEIPT_INVALID", "失败回执必须提供 error_code（≤32 字）");
    }
    var found =
            store.db()
                    .queryForList(
                            "select id from engine.task where session_id=? and parent_task_id is not null"
                                + " order by id limit 1",
                            childSessionId);
    if (found.isEmpty())
      throw new PlatformException(404, "SUBTASK_NOT_FOUND", "子任务不存在: " + childSessionId);
    long childTaskId = ((Number) found.getFirst().get("id")).longValue();
    return store.atomic(
            () -> {
              var rows = store.db().queryForList("select * from engine.task where id=? for update", childTaskId);
              var child = rows.getFirst();
              if (child.get("role") == null)
                throw new PlatformException(409, "SUBTASK_NOT_FOUND", "子任务缺少 role（库稿 CHECK 兜底失守）");
              long parentTaskId = ((Number) child.get("parent_task_id")).longValue();
              boolean parentMerged = parentMerged(parentTaskId);
              var existingReceipts =
                      store.db()
                              .queryForList(
                                      "select candidate_revision_id,error_code,merged_decision"
                                          + " from engine.subtask_receipt where child_task_id=?",
                                      childTaskId);
              if (!existingReceipts.isEmpty()) {
                // 重复/冲突回执：保留审计返回原记录，不覆盖（同结果=duplicate，异结果=conflict）。
                var prior = existingReceipts.getFirst();
                Long priorCandidate =
                        prior.get("candidate_revision_id") == null
                                ? null
                                : ((Number) prior.get("candidate_revision_id")).longValue();
                String priorError = prior.get("error_code") == null ? null : String.valueOf(prior.get("error_code"));
                boolean same =
                        Objects.equals(priorCandidate, candidateRevisionId)
                                && Objects.equals(priorError, errorCode);
                var logger = org.slf4j.LoggerFactory.getLogger(SubagentPlanning.class);
                if (same) {
                  if (parentMerged)
                    logger.warn(
                            "AUDIT SUBTASK_RECEIPT_LATE childTask={} result=duplicate merged_decision kept", childTaskId);
                  return receiptView(child, childSessionId, stopReason, priorCandidate, priorError,
                          true, false, parentMerged);
                }
                logger.warn(
                        "AUDIT SUBTASK_RECEIPT_CONFLICT childTask={} prior(candidate={},error={})"
                            + " incoming(candidate={},error={}) kept=true",
                        childTaskId,
                        priorCandidate,
                        priorError,
                        candidateRevisionId,
                        errorCode);
                if (parentMerged)
                  logger.warn(
                          "AUDIT SUBTASK_RECEIPT_LATE childTask={} result=conflict merged_decision kept", childTaskId);
                return receiptView(child, childSessionId, stopReason, priorCandidate, priorError,
                        false, true, parentMerged);
              }
              // 首条回执：终态 CAS（非终态→终态；已终态校验形状一致）+ 回执 + catalog + 事件。
              String state = String.valueOf(child.get("state"));
              if (TERMINAL.contains(state)) {
                boolean stateMatches = success ? "SUCCEEDED".equals(state) : !"SUCCEEDED".equals(state);
                if (!stateMatches)
                  throw new PlatformException(
                          409, "STATE_VERSION_CONFLICT", "子任务已终态 " + state + "，与回执停因 " + stopReason + " 矛盾");
              } else {
                int changed =
                        store.db()
                                .update(
                                        "update engine.task set state=?,state_version=state_version+1,updated_at=now()"
                                            + " where id=? and state=?",
                                        success ? "SUCCEEDED" : "FAILED",
                                        childTaskId,
                                        state);
                if (changed != 1)
                  throw new PlatformException(409, "STATE_VERSION_CONFLICT", "子任务状态已变化，回执拒绝（重读后重试）");
                child.put("state", success ? "SUCCEEDED" : "FAILED");
              }
              Long candidateId = null;
              String resultDigest = null;
              String resultRef = null;
              if (success) {
                candidateId = validateCandidate(candidateRevisionId, childTaskId);
                // 回执结果=候选修订本体：result_ref/digest 指向 plan_revision（成功对非空，满足 CHECK）。
                resultRef = "plan-revision/" + candidateId;
                resultDigest =
                        store.db()
                                .queryForObject(
                                        "select digest from engine.plan_revision where id=?", String.class, candidateId);
              }
              store.db()
                      .update(
                              "insert into engine.subtask_receipt(child_task_id,candidate_revision_id,input_digest,"
                                  + "result_digest,result_ref,error_code,merged_decision)"
                                  + " values(?,?,?,?,?,?, ?)",
                              childTaskId,
                              candidateId,
                              String.valueOf(child.get("input_digest")),
                              resultDigest,
                              resultRef,
                              success ? null : errorCode,
                              parentMerged ? "DISCARDED" : "PENDING");
              if (parentMerged)
                // 迟到首条回执（预算截止合并后到达）：照常落子任务终态事实与回执（合并决定置
                // DISCARDED——合并已发生不重开），审计保留，父任务 merged_decision 不被触碰。
                org.slf4j.LoggerFactory.getLogger(SubagentPlanning.class)
                        .warn("AUDIT SUBTASK_RECEIPT_LATE childTask={} result=fresh-after-merge decision=DISCARDED", childTaskId);
              store.db()
                      .update(
                              "update engine.subagent_catalog set ended_at=now(),stop_reason=?"
                                  + " where child_session_id=? and ended_at is null",
                              stopReason,
                              childSessionId);
              appendTaskEvent(
                      parentTaskId,
                      "SUBTASK_SETTLED",
                      Map.of(
                              "subtaskId", String.valueOf(child.get("public_id")),
                              "childSessionId", childSessionId,
                              "stopReason", stopReason,
                              "errorCode", success ? "" : errorCode));
              var settled =
                      Json.tree(
                              Map.of(
                                      "childSessionId", childSessionId,
                                      "childTaskId", String.valueOf(child.get("public_id")),
                                      "stopReason", stopReason,
                                      "errorCode", success ? "" : errorCode));
              sessions.append(childSessionId, null, SessionEvents.SUBAGENT_SETTLED, settled, null);
              sessions.append(
                      String.valueOf(
                              store.db()
                                      .queryForObject(
                                              "select parent_session_id from engine.chat_session where public_id=?",
                                              String.class,
                                              childSessionId)),
                      null,
                      SessionEvents.SUBAGENT_SETTLED,
                      settled,
                      null);
              return receiptView(child, childSessionId, stopReason, candidateId, success ? null : errorCode,
                      false, false, parentMerged);
            });
  }

  /**
   * 父任务合并：全部子任务终态后只合并一次（merged_decision 事实锁）。重复 merge 幂等返回原结果。
   * 有有效候选→ADOPTED/DISCARDED+父任务 PLAN_READY；无有效候选→父任务 FAILED 原因码
   * {@code SUBAGENT_NO_VALID_CANDIDATE}（TASK_FINISHED 事件承载，contracts/error-codes.md §2 已登记）。
   *
   * @return {taskId, merged:true, replay, parentState, reasonCode?, adopted:[{taskId,candidateRevisionId}],
   *         discarded:[{taskId,errorCode}]}
   */
  public Map<String, Object> merge(long parentTaskId) {
    var parents = store.db().queryForList("select * from engine.task where id=?", parentTaskId);
    if (parents.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在: " + parentTaskId);
    return store.atomic(
            () -> {
              store.db()
                      .queryForObject("select 1 from engine.task where id=? for update", Long.class, parentTaskId);
              var children =
                      store.db()
                              .queryForList(
                                      "select c.id,c.public_id,c.state,r.candidate_revision_id,r.error_code,"
                                          + "r.merged_decision,r.child_task_id"
                                          + " from engine.task c"
                                          + " left join engine.subtask_receipt r on r.child_task_id=c.id"
                                          + " where c.parent_task_id=? order by c.id",
                                      parentTaskId);
              if (children.isEmpty())
                throw new PlatformException(409, "SUBTASKS_NOT_SETTLED", "任务无子任务，无可合并回执");
              // 合并事实锁：任一回执已有合并决定→已合并，幂等返回原结果（不重算、不二次改写）。
              if (children.stream()
                      .anyMatch(c -> c.get("merged_decision") != null
                              && !"PENDING".equals(String.valueOf(c.get("merged_decision")))))
                return mergeView(parentTaskId, children, true);
              // 合并前置：全部子任务终态且回执齐备（预算截止强制合并随 T2-5 预算扣减接线）。
              boolean allSettled =
                      children.stream()
                              .allMatch(c -> TERMINAL.contains(String.valueOf(c.get("state")))
                                      && c.get("child_task_id") != null);
              if (!allSettled)
                throw new PlatformException(
                        409, "SUBTASKS_NOT_SETTLED", "子任务未全部终态（或回执未齐），禁止合并");
              var parent = parents.getFirst();
              String parentState = String.valueOf(parent.get("state"));
              if (TERMINAL.contains(parentState))
                throw new PlatformException(409, "STATE_VERSION_CONFLICT", "父任务已终态且无合并事实，拒绝合并");
              var adopted = new ArrayList<Map<String, Object>>();
              var discarded = new ArrayList<Map<String, Object>>();
              for (var c : children) {
                long childId = ((Number) c.get("id")).longValue();
                boolean validCandidate =
                        "SUCCEEDED".equals(String.valueOf(c.get("state")))
                                && c.get("candidate_revision_id") != null;
                store.db()
                        .update(
                                "update engine.subtask_receipt set merged_decision=?,updated_at=now()"
                                    + " where child_task_id=? and merged_decision='PENDING'",
                                validCandidate ? "ADOPTED" : "DISCARDED",
                                childId);
                if (validCandidate)
                  adopted.add(
                          Map.of(
                                  "taskId", String.valueOf(c.get("public_id")),
                                  "candidateRevisionId", ((Number) c.get("candidate_revision_id")).longValue()));
                else
                  discarded.add(
                          Map.of(
                                  "taskId", String.valueOf(c.get("public_id")),
                                  "errorCode",
                                  c.get("error_code") == null ? "" : String.valueOf(c.get("error_code"))));
              }
              boolean noCandidate = adopted.isEmpty();
              String next = noCandidate ? "FAILED" : "PLAN_READY";
              int changed =
                      store.db()
                              .update(
                                      "update engine.task set state=?,state_version=state_version+1,updated_at=now()"
                                          + " where id=? and state=?",
                                      next,
                                      parentTaskId,
                                      parentState);
              if (changed != 1) throw new PlatformException(409, "STATE_VERSION_CONFLICT", "父任务状态已变化，合并拒绝");
              if (noCandidate) {
                // 无有效候选：父任务 FAILED+原因码（错误码经待登记区转正，契约 §5"无有效候选记该码并结束规划"）。
                appendTaskEvent(
                        parentTaskId,
                        "TASK_FINISHED",
                        Map.of("state", "FAILED", "reasonCode", "SUBAGENT_NO_VALID_CANDIDATE"));
              }
              var view = new LinkedHashMap<String, Object>();
              view.put("taskId", String.valueOf(parent.get("public_id")));
              view.put("merged", true);
              view.put("replay", false);
              view.put("parentState", next);
              if (noCandidate) view.put("reasonCode", "SUBAGENT_NO_VALID_CANDIDATE");
              view.put("adopted", adopted);
              view.put("discarded", discarded);
              return view;
            });
  }

  // ---------------------------------------------------------------- 目录查询（接口稿 §5.3）

  /**
   * GET /v1/sessions/{id}/subagents：按父会话列子代理目录。分角色口径见类 javadoc；USER 视图停因
   * 映射展示摘要不回原始码，OPERATOR/ADMIN 另见 stopReason。目录不返回提示词/调用参数/输出正文。
   *
   * @return {sessionId, children:[...], nextAfter?}——按 startedAt,childSessionId 稳定排序
   */
  public Map<String, Object> catalog(String sessionId, String after, String limit, Actor actor) {
    int offset = 0;
    if (after != null && !after.isBlank()) {
      if (!after.matches("[0-9]{1,9}"))
        throw new PlatformException(400, "SUBAGENTS_CURSOR_INVALID", "after 必须为非负整数");
      offset = Integer.parseInt(after);
    }
    int size = 50;
    if (limit != null && !limit.isBlank()) {
      if (!limit.matches("[0-9]{1,4}"))
        throw new PlatformException(400, "SUBAGENTS_LIMIT_INVALID", "limit 必须为 1 至 200 的整数");
      size = Integer.parseInt(limit);
      if (size < 1 || size > 200)
        throw new PlatformException(400, "SUBAGENTS_LIMIT_INVALID", "limit 必须为 1 至 200 的整数");
    }
    var parents =
            store.db()
                    .queryForList(
                            "select * from engine.chat_session where public_id=? and tenant_id=?",
                            sessionId,
                            actor.tenantId());
    if (parents.isEmpty()) throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
    var parent = parents.getFirst();
    boolean privileged = actor.roles().contains("OPERATOR") || actor.roles().contains("ADMIN");
    if (!privileged
            && !actor.principalId().equals(String.valueOf(parent.get("principal_id"))))
      throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
    // 取 size+1 探测有无下一页；稳定排序 startedAt,childSessionId（库稿 INDEX (parent,started_at)）。
    var rows =
            store.db()
                    .queryForList(
                            "select g.child_session_id,g.label,g.start_seq,g.started_at,g.ended_at,g.stop_reason,"
                                + " c.id as child_task_id"
                                + " from engine.subagent_catalog g"
                                + " join engine.chat_session cs on cs.public_id=g.child_session_id"
                                + " left join engine.task c on c.session_id=g.child_session_id"
                                + " where g.parent_session_id=?"
                                + " order by g.started_at, g.child_session_id"
                                + " limit ? offset ?",
                            sessionId,
                            size + 1,
                            offset);
    boolean hasMore = rows.size() > size;
    var children = new ArrayList<Map<String, Object>>();
    for (var row : rows.subList(0, Math.min(rows.size(), size))) {
      String stopReason = row.get("stop_reason") == null ? null : String.valueOf(row.get("stop_reason"));
      var item = new LinkedHashMap<String, Object>();
      item.put("childSessionId", String.valueOf(row.get("child_session_id")));
      if (row.get("child_task_id") != null) item.put("childTaskId", row.get("child_task_id"));
      item.put("label", String.valueOf(row.get("label")));
      item.put("status", stopReason == null ? "RUNNING" : stopReason);
      if (privileged) item.put("stopReason", stopReason);
      item.put("stopDisplay", stopReason == null ? null : displayOf(stopReason));
      item.put("startedAt", String.valueOf(row.get("started_at")));
      item.put("endedAt", row.get("ended_at") == null ? null : String.valueOf(row.get("ended_at")));
      if (row.get("started_at") != null && row.get("ended_at") != null)
        item.put(
                "durationMs",
                java.time.Duration
                        .between(
                                ((java.sql.Timestamp) row.get("started_at")).toInstant(),
                                ((java.sql.Timestamp) row.get("ended_at")).toInstant())
                        .toMillis());
      children.add(item);
    }
    var view = new LinkedHashMap<String, Object>();
    view.put("sessionId", sessionId);
    view.put("children", children);
    if (hasMore) view.put("nextAfter", offset + size);
    else view.put("nextAfter", null);
    return view;
  }

  // ---------------------------------------------------------------- 内部

  /** 候选归属校验：存在且 owner=该子任务（规划子代理只合并自己名下通过校验的候选，库稿 §4.2）。 */
  private long validateCandidate(Long candidateRevisionId, long childTaskId) {
    var revisions =
            store.db()
                    .queryForList(
                            "select id,owner_task_id from engine.plan_revision where id=?",
                            candidateRevisionId);
    if (revisions.isEmpty())
      throw new PlatformException(404, "SUBAGENT_CANDIDATE_INVALID", "候选修订不存在: " + candidateRevisionId);
    var revision = revisions.getFirst();
    if (((Number) revision.get("owner_task_id")).longValue() != childTaskId)
      throw new PlatformException(409, "SUBAGENT_CANDIDATE_INVALID", "候选修订不属于该子任务");
    return ((Number) revision.get("id")).longValue();
  }

  /** 父任务合并事实：任一子任务回执 merged_decision≠PENDING（契约 §5 事实锁的读出口）。 */
  private boolean parentMerged(long parentTaskId) {
    Integer merged =
            store.db()
                    .queryForObject(
                            "select count(*) from engine.subtask_receipt r"
                                + " join engine.task c on c.id=r.child_task_id"
                                + " where c.parent_task_id=? and r.merged_decision<>'PENDING'",
                            Integer.class,
                            parentTaskId);
    return merged != null && merged > 0;
  }

  private Map<String, Object> receiptView(
          Map<String, Object> child,
          String childSessionId,
          String stopReason,
          Long candidateRevisionId,
          String errorCode,
          boolean duplicate,
          boolean conflict,
          boolean late) {
    var view = new LinkedHashMap<String, Object>();
    view.put("childTaskId", String.valueOf(child.get("public_id")));
    view.put("childSessionId", childSessionId);
    view.put("stopReason", stopReason);
    view.put("state", String.valueOf(child.get("state")));
    view.put("candidateRevisionId", candidateRevisionId);
    view.put("errorCode", errorCode);
    view.put("duplicate", duplicate);
    view.put("conflict", conflict);
    view.put("late", late);
    return view;
  }

  private Map<String, Object> mergeView(
          long parentTaskId, List<Map<String, Object>> children, boolean replay) {
    var parent =
            store.db().queryForMap("select public_id,state from engine.task where id=?", parentTaskId);
    var adopted = new ArrayList<Map<String, Object>>();
    var discarded = new ArrayList<Map<String, Object>>();
    String reasonCode = null;
    for (var c : children) {
      String decision = c.get("merged_decision") == null ? null : String.valueOf(c.get("merged_decision"));
      if ("ADOPTED".equals(decision))
        adopted.add(
                Map.of(
                        "taskId", String.valueOf(c.get("public_id")),
                        "candidateRevisionId", ((Number) c.get("candidate_revision_id")).longValue()));
      else if ("DISCARDED".equals(decision))
        discarded.add(
                Map.of(
                        "taskId", String.valueOf(c.get("public_id")),
                        "errorCode",
                        c.get("error_code") == null ? "" : String.valueOf(c.get("error_code"))));
    }
    if (adopted.isEmpty() && "FAILED".equals(String.valueOf(parent.get("state"))))
      reasonCode = "SUBAGENT_NO_VALID_CANDIDATE";
    var view = new LinkedHashMap<String, Object>();
    view.put("taskId", String.valueOf(parent.get("public_id")));
    view.put("merged", true);
    view.put("replay", replay);
    view.put("parentState", String.valueOf(parent.get("state")));
    if (reasonCode != null) view.put("reasonCode", reasonCode);
    view.put("adopted", adopted);
    view.put("discarded", discarded);
    return view;
  }

  private List<Map<String, Object>> childrenRows(long parentTaskId, String role) {
    return store.db()
            .queryForList(
                    "select public_id as child_task_id, session_id as child_session_id, role,"
                        + " row_number() over (order by id) as seq"
                        + " from engine.task where parent_task_id=? and role=? order by id",
                    parentTaskId,
                    role);
  }

  private Map<String, Object> view(
          long parentTaskId,
          String parentSessionId,
          String role,
          List<Map<String, Object>> children,
          boolean created) {
    var parent = store.db().queryForMap("select public_id from engine.task where id=?", parentTaskId);
    var items = new ArrayList<Map<String, Object>>();
    for (var row : children) {
      int seq = row.get("seq") == null ? items.size() + 1 : ((Number) row.get("seq")).intValue();
      items.add(
              Map.of(
                      "childTaskId", String.valueOf(row.get("child_task_id")),
                      "childSessionId", String.valueOf(row.get("child_session_id")),
                      "label", label(role, seq),
                      "seq", seq));
    }
    return Map.of(
            "parentTaskId", String.valueOf(parent.get("public_id")),
            "parentSessionId", parentSessionId,
            "role", role,
            "created", created,
            "children", items);
  }

  private static String label(String role, int seq) {
    return role + "-" + seq;
  }

  /** 停因展示摘要（接口稿 §5.3"停因映射为展示摘要"——USER 视图唯一停因出口）。 */
  private static String displayOf(String stopReason) {
    return switch (stopReason) {
      case "COMPLETED" -> "已完成";
      case "ABORTED" -> "已中止";
      case "ERROR" -> "执行出错";
      case "MAX_TOKENS" -> "达到长度上限";
      case "REFUSAL" -> "拒绝执行";
      default -> stopReason;
    };
  }

  /** 任务事件追加（照 Tasks.appendTaskEvent 口径：水位+1，state_version 快照入列）。 */
  private void appendTaskEvent(long taskId, String type, Map<String, Object> payload) {
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
                    next.intValue(),
                    type,
                    Json.write(payload),
                    version);
  }
}
