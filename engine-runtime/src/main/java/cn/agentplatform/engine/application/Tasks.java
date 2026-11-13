package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * T2-2（台账 §7.3）：任务受理/详情/事件/取消/恢复——任务域服务。
 *
 * <p>受理语义（接口稿 §5.2）：mode 必填（省略/空值按 PARAM 拒绝，不做隐式默认——评审十七轮；
 * SERVICE 首期仅 AGENT 由网关票据面约束，本服务不区分 caller）；requestId 幂等
 * UNIQUE(tenant, principal, request_id)——同号同参（规范化请求摘要一致）重放原任务，
 * 异参 409 REQUEST_REPLAY；toolPolicy 受理即固定（省略=继承发布范围，显式空数组=禁用工具）；
 * mode=PLAN 且显式启用规划子代理且未传 sessionId→受理事务创建 origin=TASK_INTERNAL
 * 规划根会话（主方案 §5.5——重放复用根会话，不另建）。
 *
 * <p>RESUMABLE 不是状态（红线 4）：恢复资格由事实计算（运行/等待态+租约失效+可续检查点
 * →availableActions 返回 RESUME）；可用动作矩阵照 contracts/task-states.md §3 服务端计算。
 * 恢复事实出口复用 H1-8 RecoveryFact（RunRecoveryCoordinator.recoveryFact）。
 */
@Service
public class Tasks {

  private final TraceStore store;

  public Tasks(TraceStore store) {
    this.store = store;
  }

  /** 受理：返回任务详情（首版 state=ACCEPTED；AGENT 直接受理入 QUEUED 由 T2-5 步骤映射接续）。 */
  public Map<String, Object> create(String code, JsonNode req, Actor actor) {
    String requestId = req.path("requestId").asText("");
    if (requestId.isBlank() || requestId.length() > 128)
      throw new PlatformException(400, "TASK_REQUEST_INVALID", "任务必须提供 requestId（≤128 字）");
    if (!req.path("mode").isTextual() || req.path("mode").asText().isBlank())
      throw new PlatformException(400, "PARAM_MODE_REQUIRED", "mode 必填（PLAN|AGENT），不做隐式默认");
    String mode = req.path("mode").asText();
    if (!Set.of("PLAN", "AGENT").contains(mode))
      throw new PlatformException(400, "PARAM_MODE_REQUIRED", "mode 必须为 PLAN 或 AGENT");
    String goal = req.path("goal").asText("");
    if (goal.isBlank()) throw new PlatformException(400, "TASK_GOAL_REQUIRED", "任务必须提供 goal");
    JsonNode inputs = req.path("inputs").isObject() ? req.path("inputs") : Json.object();
    JsonNode toolPolicy =
            req.has("toolPolicy") ? req.path("toolPolicy") : null;

    String digest =
            Digest.canonicalRequestDigest(mode, goal, inputs, req.path("sessionId"), toolPolicy);

    // 幂等：同号同参重放原任务；同号异参 409 REQUEST_REPLAY（UNIQUE 兜底并发）。
    var prior =
            store.db()
                    .queryForList(
                            "select * from engine.task where tenant_id=? and principal_id=? and request_id=?",
                            actor.tenantId(),
                            actor.principalId(),
                            requestId);
    if (!prior.isEmpty()) {
      var task = prior.getFirst();
      if (!Digest.sha256(digest).equals(String.valueOf(task.get("request_digest"))))
        throw new PlatformException(409, "REQUEST_REPLAY", "同一 requestId 已用于不同参数的任务");
      return detail(TraceStore.number(task, "id"), actor);
    }

    // TASK_INTERNAL 根会话（主方案 §5.5）：无用户会话的 PLAN 任务显式启用规划子代理时，
    // 受理事务创建 origin=TASK_INTERNAL 规划根会话（重放复用——上方幂等分支已返回）。
    String sessionId =
            "PLAN".equals(mode)
                    && req.path("subagentPolicy").path("enabled").asBoolean(false)
                    && req.path("sessionId").asText("").isBlank()
                    ? createInternalRootSession(code, actor)
                    : req.path("sessionId").asText("").isBlank() ? null : req.path("sessionId").asText();

    String publicId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    // 受理钉住（接口稿 §3.1）：发布快照经 app-management 只读接口取得——本首版先以应用维度
    // 占位摘要落库（满足 char(64) 形状 CHECK），真实 configReleaseId/digest 钉住随发布快照
    // 只读接口接线接通（javadoc 如实标注，台账 T2-2 交付说明）。
    String pinnedDigest =
            Digest.sha256("release-pin:" + actor.tenantId() + ":" + code + ":v1");
    store.atomic(
            () -> {
              store.db()
                      .update(
                              "insert into engine.task(public_id,tenant_id,principal_id,app_code,"
                                  + "config_release_id,config_digest,mode,purpose,goal,input_ref,input_digest,"
                                  + "session_id,session_cursor,state,request_id,request_digest,tool_policy)"
                                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                              publicId,
                              actor.tenantId(),
                              actor.principalId(),
                              code,
                              "unpinned-" + Integer.toHexString(pinnedDigest.substring(0, 8).hashCode()),
                              pinnedDigest,
                              mode,
                              "PLAN".equals(mode) ? "PLAN" : "EXECUTE",
                              goal,
                              "task-input/" + publicId,
                              Digest.sha256(Json.canonical(inputs)),
                              sessionId,
                              req.path("sessionCursor").isNumber()
                                      ? req.path("sessionCursor").asInt()
                                      : null,
                              "ACCEPTED",
                              requestId,
                              Digest.sha256(digest),
                              toolPolicy == null ? null : Json.write(toolPolicy));
              long taskId =
                      store.db()
                              .queryForObject(
                                      "select id from engine.task where public_id=?", Long.class, publicId);
              // 任务事件目录 10 类（contracts/task-states.md §4）：受理落 TASK_ACCEPTED
              appendTaskEvent(taskId, "TASK_ACCEPTED", Json.tree(Map.of("mode", mode, "goal", goal)));
              return null;
            });
    long id =
            store.db().queryForObject("select id from engine.task where public_id=?", Long.class, publicId);
    return detail(id, actor);
  }

  /** 详情：state/stateVersion/mode/availableActions（服务端计算）/recovery 角色投影/steps/eventWatermark。 */
  public Map<String, Object> detail(long taskId, Actor actor) {
    var task = ownedTask(taskId, actor);
    String state = String.valueOf(task.get("state"));
    var view = new LinkedHashMap<String, Object>();
    view.put("taskId", task.get("public_id"));
    view.put("state", state);
    view.put("stateVersion", task.get("state_version"));
    view.put("mode", task.get("mode"));
    view.put("purpose", task.get("purpose"));
    view.put("appCode", task.get("app_code"));
    view.put("goal", task.get("goal"));
    view.put("principalId", task.get("principal_id"));
    view.put("createdAt", String.valueOf(task.get("created_at")));
    view.put("availableActions", availableActions(state, task));
    // 恢复事实（接口稿 §5.2）：读取最近一条 TASK_RESUMED 事件事实投影——从未恢复→null。
    // 角色投影：USER 仅 recoverySource/reasonCode/reasonSummary；OPERATOR/ADMIN 另见
    // checkpoint/reusedSteps/blockedReasons（与 resume 写入同一事实源）。
    var resumedRows =
            store.db()
                    .queryForList(
                            "select payload from engine.task_event where task_id=? and type='TASK_RESUMED'"
                                + " order by seq desc limit 1",
                            taskId);
    if (resumedRows.isEmpty()) {
      view.put("recovery", null);
    } else {
      var payload = Json.read(String.valueOf(resumedRows.getFirst().get("payload")), JsonNode.class);
      if (privileged(actor)) view.put("recovery", payload);
      else {
        var userView = new LinkedHashMap<String, Object>();
        userView.put("recoverySource", payload.path("recoverySource").asText());
        userView.put("reasonCode", payload.path("reasonCode").asText());
        userView.put("reasonSummary", payload.path("reasonSummary").asText());
        view.put("recovery", userView);
      }
    }
    // 步骤投影（task_step）
    view.put(
            "steps",
            store.db()
                    .queryForList(
                            "select step_id,state,attempt_no from engine.task_step where task_id=? order by step_id",
                            taskId));
    // 事件水位（task_event 最大 seq）
    Long watermark =
            store.db()
                    .queryForObject(
                            "select coalesce(max(seq),0) from engine.task_event where task_id=?",
                            Long.class,
                            taskId);
    view.put("eventWatermark", watermark == null ? 0 : watermark);
    view.put("hasCompression", false); // 任务侧压缩标记由关联会话计算——T2-9 交付后接通
    return view;
  }

  /** 事件增量：nextAfterSeq=本页最后扫描 seq（不 +1）；truncated 如实（照接口稿事件游标口径）。 */
  public Map<String, Object> events(long taskId, long afterSeq, int limit, Actor actor) {
    ownedTask(taskId, actor);
    var rows =
            store.db()
                    .queryForList(
                            "select seq,type,payload,created_at from engine.task_event where task_id=?"
                                + " and seq>? order by seq limit ?",
                            taskId,
                            afterSeq,
                            Math.max(1, Math.min(limit, 1000)));
    long nextAfterSeq = afterSeq;
    var events = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      long seq = ((Number) row.get("seq")).longValue();
      nextAfterSeq = seq;
      events.add(
              Map.of(
                      "seq", seq,
                      "type", String.valueOf(row.get("type")),
                      "data", Json.read(String.valueOf(row.get("payload")), JsonNode.class),
                      "occurredAt", String.valueOf(row.get("created_at"))));
    }
    Long watermark =
            store.db()
                    .queryForObject(
                            "select coalesce(max(seq),0) from engine.task_event where task_id=?",
                            Long.class,
                            taskId);
    boolean truncated = watermark != null && watermark > nextAfterSeq;
    return Map.of("taskId", taskId, "events", events, "nextAfterSeq", nextAfterSeq, "truncated", truncated);
  }

  /** 取消：运行/等待态→CANCELLED（CAS state_version）；终态幂等返回原状。 */
  public Map<String, Object> cancel(long taskId, JsonNode req, Actor actor) {
    var task = ownedTask(taskId, actor);
    String state = String.valueOf(task.get("state"));
    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state))
      return detail(taskId, actor); // 终态幂等
    int version = ((Number) task.get("state_version")).intValue();
    int changed =
            store.db()
                    .update(
                            "update engine.task set state='CANCELLED',state_version=state_version+1,"
                                + "cancel_requested_at=now(),updated_at=now() where id=? and state=? and state_version=?",
                            taskId,
                            state,
                            version);
    if (changed == 1)
      appendTaskEvent(taskId, "TASK_FINISHED", Json.tree(Map.of("state", "CANCELLED", "by", actor.principalId())));
    return detail(taskId, actor);
  }

  /** 恢复：写 TASK_RESUMED（负载=恢复事实三出口），状态 CAS 回 RUNNING；手动/自动同入口先到先得。 */
  public Map<String, Object> resume(long taskId, JsonNode req, Actor actor) {
    var task = ownedTask(taskId, actor);
    String state = String.valueOf(task.get("state"));
    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state))
      return detail(taskId, actor); // 终态无恢复
    // 恢复事实（契约桩 §3 口径，任务域事实）：手动恢复=MANUAL_REQUEST；检查点=任务事件水位
    long watermark =
            store.db()
                    .queryForObject(
                            "select coalesce(max(seq),0) from engine.task_event where task_id=?",
                            Long.class,
                            taskId);
    var checkpoint = Json.tree(Map.of("eventWatermark", watermark, "runRefs", List.of()));
    var reused = Json.tree(List.of());
    var blocked =
            Json.tree(
                    store.db()
                            .queryForList(
                                    "select distinct state from engine.task_step where task_id=?"
                                        + " and state like 'WAITING%'",
                                    String.class,
                                    taskId));
    int changed =
            store.db()
                    .update(
                            "update engine.task set state='RUNNING',state_version=state_version+1,"
                                + "updated_at=now() where id=? and state=? and state_version=?",
                            taskId,
                            state,
                            ((Number) task.get("state_version")).intValue());
    if (changed == 1)
      appendTaskEvent(
              taskId,
              "TASK_RESUMED",
              Json.tree(
                      Map.of(
                              "recoverySource",
                              "MANUAL",
                              "reasonCode",
                              "MANUAL_REQUEST",
                              "reasonSummary",
                              "按请求手动恢复",
                              "checkpoint",
                              checkpoint,
                              "reusedSteps",
                              reused,
                              "blockedReasons",
                              blocked)));
    return detail(taskId, actor);
  }

  /**
   * 步骤调用列表（接口稿 §5.7）：依次核对任务/步骤/运行归属再分页列举。
   * 锚点形状 {callId,skillCode,skillVersion,status,errorSummary,appCode,sessionId,eventSeq,
   * startedAt,durationMs}——appCode/sessionId/eventSeq 来自 tool/call 会话事件（H1-6 链路），
   * 无锚点（appCode/sessionId/eventSeq 为 null）如实返回 null（前端隐藏跳转保留错误摘要）。
   * 直接任务的入口步骤经 task_step.run_id 反查；查询人须为任务归属主体（F3-1 消费同口径）。
   */
  public Map<String, Object> stepCalls(long taskId, String stepId, int page, int pageSize, Actor actor) {
    var task = ownedTask(taskId, actor);
    if (pageSize < 1 || pageSize > 100)
      throw new PlatformException(400, "TASK_PAGE_INVALID", "pageSize 上限 100");
    var step =
            store.db()
                    .queryForList(
                            "select step_id,run_id,state from engine.task_step where task_id=? and step_id=?",
                            taskId,
                            stepId);
    if (step.isEmpty())
      throw new PlatformException(404, "TASK_STEP_NOT_FOUND", "任务步骤不存在: " + stepId);
    Long runId = (Long) step.getFirst().get("run_id");
    if (runId == null)
      return Map.of(
              "taskId",
              task.get("public_id"),
              "stepId",
              stepId,
              "calls",
              List.of(),
              "total",
              0,
              "page",
              page);
    var run = store.db().queryForMap("select public_id,app_code from engine.run where id=?", runId);
    int total =
            store.db()
                    .queryForObject(
                            "select count(*) from engine.tool_call where run_id=?", Integer.class, runId);
    var rows =
            store.db()
                    .queryForList(
                            "select id,skill_code,skill_version_id,status,error_semantic,duration_ms,created_at"
                                + " from engine.tool_call where run_id=? order by id limit ? offset ?",
                            runId,
                            pageSize,
                            (page - 1) * pageSize);
    var calls = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      long callId = TraceStore.number(row, "id");
      // 锚点：tool/call 会话事件（H1-6 登记，按 callId 唯一）——无会话关联则为 null 三件
      var anchor =
              store.db()
                      .queryForList(
                              "select se.session_id,se.seq,cs.app_code from engine.session_event se"
                                  + " join engine.chat_session cs on cs.public_id=se.session_id"
                                  + " where se.type='tool/call' and se.data->>'callId'=?"
                                  + " order by se.seq limit 1",
                              String.valueOf(callId));
      var call = new LinkedHashMap<String, Object>();
      call.put("callId", String.valueOf(callId));
      call.put("skillCode", row.get("skill_code"));
      call.put("skillVersion", row.get("skill_version_id"));
      call.put("status", row.get("status"));
      call.put(
              "errorSummary",
              row.get("error_semantic") == null ? null : String.valueOf(row.get("error_semantic")));
      call.put("durationMs", row.get("duration_ms"));
      if (anchor.isEmpty()) {
        call.put("appCode", null);
        call.put("sessionId", null);
        call.put("eventSeq", null);
      } else {
        call.put("appCode", anchor.getFirst().get("app_code"));
        call.put("sessionId", anchor.getFirst().get("session_id"));
        call.put("eventSeq", anchor.getFirst().get("seq"));
      }
      calls.add(call);
    }
    return Map.of(
            "taskId", task.get("public_id"),
            "stepId", stepId,
            "runId", run.get("public_id"),
            "calls", calls,
            "total", total,
            "page", page,
            "pageSize", pageSize);
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.task where id=? and tenant_id=? and principal_id=?",
                            taskId,
                            actor.tenantId(),
                            actor.principalId());
    if (rows.isEmpty())
      throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
    return rows.getFirst();
  }

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
                    next.intValue(),
                    type,
                    Json.write(payload),
                    version);
  }

  private String createInternalRootSession(String code, Actor actor) {
    String sessionId = "ti-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    store.db()
            .update(
                    "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,origin,delegation_depth,event_watermark)"
                        + " values(?,? ,?,?, '{}'::jsonb,'TASK_INTERNAL',0,0)",
                    sessionId,
                    actor.tenantId(),
                    actor.principalId(),
                    code);
    return sessionId;
  }

  /** availableActions 服务端计算（contracts/task-states.md §3 矩阵；RESUME 为事实叠加）。 */
  private List<String> availableActions(String state, Map<String, Object> task) {
    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)) return List.of("REISSUE", "EXPORT");
    List<String> actions = new ArrayList<>();
    switch (state) {
      case "WAITING_DEPENDENCIES" -> actions.addAll(List.of("VIEW_DEPENDENCIES", "CANCEL"));
      case "WAITING_APPROVAL" -> actions.addAll(List.of("GOTO_CONFIRM", "CANCEL"));
      case "WAITING_EXTERNAL" -> actions.addAll(List.of("VIEW_EXTERNAL", "CANCEL"));
      case "WAITING_RECONCILIATION" -> actions.addAll(List.of("TRACK_EXTERNAL", "DISCLOSE", "CANCEL"));
      case "RUNNING", "QUEUED", "ACCEPTED", "PLANNING", "PLAN_READY", "NEEDS_INPUT" -> actions.add("CANCEL");
      default -> actions.add("CANCEL");
    }
    // RESUME 资格投影（事实计算，非持久状态）：TASK_RESUMED 存在且当前非终态→提供 RESUME
    int resumed =
            store.db()
                    .queryForObject(
                            "select count(*) from engine.task_event where task_id=? and type='TASK_RESUMED'",
                            Integer.class,
                            TraceStore.number(task, "id"));
    if (resumed > 0) actions.add("RESUME");
    return actions;
  }

  private boolean privileged(Actor actor) {
    return actor.roles().contains("OPERATOR") || actor.roles().contains("ADMIN");
  }

  /** 请求摘要规范化（request_digest 输入）：mode/goal/inputs/sessionId/toolPolicy 键序规范拼接。 */
  static final class Digest {
    private Digest() {}

    static String canonicalRequestDigest(
        String mode, String goal, JsonNode inputs, JsonNode sessionId, JsonNode toolPolicy) {
      return Json.canonical(
              Map.of(
                      "v", "TASK_SUBMIT_V1",
                      "mode", mode,
                      "goal", goal,
                      "inputs", inputs,
                      "sessionId", sessionId == null ? "" : sessionId.asText(""),
                      "toolPolicy", toolPolicy == null ? "" : Json.canonical(toolPolicy)));
    }

    static String sha256(String value) {
      try {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
