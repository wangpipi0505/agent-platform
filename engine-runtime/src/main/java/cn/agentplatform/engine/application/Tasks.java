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
 * <p>受理语义（接口稿 §5.2）：mode 必填（省略/空值按 PARAM 拒绝，不做隐式默认——评审十七轮）；
 * requestId 幂等 UNIQUE(tenant, principal, request_id)——同号同参（规范化请求摘要一致）重放原任务，
 * 异参 409 REQUEST_REPLAY；toolPolicy 受理即固定（省略=继承发布范围，显式空数组=禁用工具）；
 * mode=PLAN 且显式启用规划子代理且未传 sessionId→受理事务创建 origin=TASK_INTERNAL
 * 规划根会话（主方案 §5.5——重放复用根会话，不另建）。
 *
 * <p>T2-15 外部集成 caller 语义（台账 §7.3，接口稿 §3.6/§6 权限矩阵 SERVICE 列）：SERVICE
 * 票据面（/embed/ticket 出票与直连 ApiKey 两面）首期仅受理 AGENT 任务——外部 caller 提交
 * mode=PLAN → 403 {@code SERVICE_AGENT_ONLY} 并记一条拒绝审计。落点选择：网关对 POST 体
 * 是有界流转发（不缓存不解析请求体，{@code GatewayController.proxy}），且两面（出票/直连）
 * 在引擎侧收敛于同一服务方法，故引擎分支一处承接两面，审计与任务域语义同源。
 *
 * <p>RESUMABLE 不是状态（红线 4）：恢复资格由事实计算（运行/等待态+租约失效+可续检查点
 * →availableActions 返回 RESUME）；可用动作矩阵照 contracts/task-states.md §3 服务端计算。
 * 恢复事实出口复用 H1-8 RecoveryFact（RunRecoveryCoordinator.recoveryFact）。
 *
 * <p>T2-6（台账 §7.3，追加式）：任务创建登记依赖——{@code dependencies:[{upstreamTaskId,
 * inputBindings}]} 入参→task_dependency 行（同受理事务；库稿 §4.2：创建时校验上游已存在且
 * 调用方有权引用=同租户同主体）。任一上游未满足→受理后置 WAITING_DEPENDENCIES（只读等待，
 * availableActions=[VIEW_DEPENDENCIES,CANCEL]）；满足放行见 {@link TaskDispatcher
 * #dependencySatisfied}（上游 SUCCEEDED 且结果摘要匹配→satisfied_at→全满足后 QUEUED）。
 */
@Service
public class Tasks {

  private final TraceStore store;

  // T2-11：活动任务事件 SSE 观察流（每连接一个泵；closeTaskStreams/@PreDestroy 收口）。
  private final Set<TaskEventPump> taskStreams = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** :ping 心跳间隔（接口稿 §7：15s）；独立部署可调，测试经反射改短（照 ChatSessions 先例）。 */
  @org.springframework.beans.factory.annotation.Value("${agent.task.sse.heartbeat-millis:15000}")
  long taskHeartbeatMillis = 15000;

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
    // T2-15：SERVICE 票据面首期仅 AGENT（接口稿 §6 权限矩阵 SERVICE 列——评审十六/十七轮）。
    if (externalIntegrationCaller(actor) && !"AGENT".equals(mode)) {
      // 引擎无独立审计表（审计归口 monitor）；照 ChatSessions 先例落结构化拒绝审计日志行。
      org.slf4j.LoggerFactory.getLogger(Tasks.class)
              .warn(
                      "AUDIT TASK_SERVICE_AGENT_ONLY tenant={} principal={} appCode={} bizSystemCode={} requestId={} mode={}",
                      actor.tenantId(),
                      actor.principalId(),
                      code,
                      actor.attributes().getOrDefault("bizSystemCode", Set.of()).iterator().next(),
                      requestId,
                      mode);
      throw new PlatformException(
              403, "SERVICE_AGENT_ONLY", "外部集成（SERVICE 票据面）首期仅受理 AGENT 任务");
    }
    String goal = req.path("goal").asText("");
    if (goal.isBlank()) throw new PlatformException(400, "TASK_GOAL_REQUIRED", "任务必须提供 goal");
    JsonNode inputs = req.path("inputs").isObject() ? req.path("inputs") : Json.object();
    JsonNode toolPolicy =
            req.has("toolPolicy") ? req.path("toolPolicy") : null;

    String digest =
            Digest.canonicalRequestDigest(mode, goal, inputs, req.path("sessionId"), toolPolicy);
    // T2-6：依赖登记入参（受理事务内校验与落库——重放分支在上方，依赖只在首次受理登记一次）
    JsonNode dependencies = req.path("dependencies").isArray() ? req.path("dependencies") : null;

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
              // T2-6：依赖登记（库稿 §4.2——上游须已存在且调用方有权引用=同租户同主体；
              // 任一上游未满足→受理后置 WAITING_DEPENDENCIES，放行由 TaskDispatcher.dependencySatisfied 承接）
              boolean waiting = false;
              if (dependencies != null) {
                for (JsonNode dep : dependencies) {
                  String upstreamPublicId = dep.path("upstreamTaskId").asText("");
                  if (upstreamPublicId.isBlank())
                    throw new PlatformException(
                            400, "TASK_DEPENDENCY_INVALID", "dependencies[].upstreamTaskId 必填");
                  var upstream =
                          store.db()
                                  .queryForList(
                                          "select id,tenant_id,principal_id,state from engine.task"
                                              + " where public_id=?",
                                          upstreamPublicId);
                  if (upstream.isEmpty()
                          || !actor.tenantId()
                                  .equals(String.valueOf(upstream.getFirst().get("tenant_id"))))
                    throw new PlatformException(
                            404, "TASK_NOT_FOUND", "上游任务不存在或无权引用: " + upstreamPublicId);
                  if (!actor.principalId()
                          .equals(String.valueOf(upstream.getFirst().get("principal_id"))))
                    throw new PlatformException(
                            403, "TASK_DEPENDENCY_FORBIDDEN", "上游任务不属于当前主体，禁止引用");
                  JsonNode bindings =
                          dep.path("inputBindings").isObject()
                                  ? dep.path("inputBindings")
                                  : Json.object();
                  store.db()
                          .update(
                                  "insert into engine.task_dependency(task_id,upstream_task_id,"
                                      + "input_bindings) values(?,?,?::jsonb)",
                                  taskId,
                                  ((Number) upstream.getFirst().get("id")).longValue(),
                                  Json.write(bindings));
                  if (!"SUCCEEDED".equals(String.valueOf(upstream.getFirst().get("state"))))
                    waiting = true;
                }
                if (waiting)
                  store.db()
                          .update(
                                  "update engine.task set state='WAITING_DEPENDENCIES',"
                                      + "state_version=state_version+1,updated_at=now() where id=?",
                                  taskId);
              }
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
      view.put("recovery", recoveryProjection(payload, privileged(actor)));
    }
    // 步骤投影（task_step）
    view.put(
            "steps",
            store.db()
                    .queryForList(
                            "select step_id,state,attempt_no from engine.task_step where task_id=? order by step_id",
                            taskId));
    // T2-6：依赖登记投影（VIEW_DEPENDENCIES 数据源；satisfied=上游成功且结果摘要匹配后写入的事实）
    view.put(
            "dependencies",
            store.db()
                    .queryForList(
                            "select u.public_id as upstream_task_id,"
                                + " (d.satisfied_at is not null) as satisfied"
                                + " from engine.task_dependency d"
                                + " join engine.task u on u.id=d.upstream_task_id"
                                + " where d.task_id=? order by u.public_id",
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

  /** 事件增量：nextAfterSeq=本页最后扫描 seq（不 +1）；truncated 如实（照接口稿事件游标口径）。
   *  T2-11：TASK_RESUMED 事件 data 与详情/resume 共用角色裁剪——USER 不见检查点与复用清单。 */
  public Map<String, Object> events(long taskId, long afterSeq, int limit, Actor actor) {
    ownedTask(taskId, actor);
    boolean privileged = privileged(actor);
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
      String type = String.valueOf(row.get("type"));
      var payload = Json.read(String.valueOf(row.get("payload")), JsonNode.class);
      Object data =
              "TASK_RESUMED".equals(type) ? recoveryProjection(payload, privileged) : payload;
      events.add(
              Map.of(
                      "seq", seq,
                      "type", type,
                      "data", data,
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

  /**
   * T2-11（接口稿 §7）：任务事件 SSE——text/event-stream，帧 {@code id=<seq> event=<type>
   * data=<角色投影后 json>}。Last-Event-ID（头优先、缺省取同名 query 参数）排他续读
   * （{@code seq > id}，与 §5.2 afterSeq 游标同一语义）；TASK_RESUMED 帧按角色投影裁剪后出帧
   * （与详情/events 同一 {@link #recoveryProjection} 口径）。生命周期（接口稿 §7）：SSE 只是观察
   * 通道——客户端断开只停泵，<b>不取消任务</b>（取消必须显式 cancel）；15s 无事件补 {@code :ping}
   * 注释帧；PG（事实源）即缓冲，未读积压超限判慢客户端直接断开（客户端以 Last-Event-ID 重连，
   * 排他游标+seq 幂等去重保证补发不重不漏）。连接时固化调用者角色快照——泵线程无请求上下文。
   */
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter eventStream(
          long taskId, String headerLastEventId, String paramLastEventId, Actor actor) {
    ownedTask(taskId, actor);
    boolean privileged = privileged(actor);
    long after = parseStreamCursor(headerLastEventId, paramLastEventId);
    var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
    var pump = new TaskEventPump(taskId, after, privileged, emitter);
    taskStreams.add(pump);
    emitter.onCompletion(pump::close);
    emitter.onTimeout(pump::close);
    emitter.onError(failure -> pump.close());
    var thread = new Thread(pump, "task-events-stream-" + taskId);
    thread.setDaemon(true);
    thread.start();
    return emitter;
  }

  /** 关闭指定任务的全部事件观察流（任务收口/测试收口用）。返回关闭的流数量。 */
  public int closeTaskStreams(long taskId) {
    int closed = 0;
    for (var pump : taskStreams)
      if (pump.taskId == taskId) {
        pump.close();
        closed++;
      }
    return closed;
  }

  @jakarta.annotation.PreDestroy
  void shutdownTaskStreams() {
    for (var pump : new ArrayList<>(taskStreams)) pump.close();
  }

  /**
   * TASK_RESUMED 角色投影（接口稿 §5.2）：任务详情、resume 响应、事件增量与 SSE 共用同一口径——
   * USER（非 OPERATOR/ADMIN）仅 recoverySource/reasonCode/reasonSummary（不返回检查点和内部
   * 运行引用/复用清单/阻塞原因）；授权 OPERATOR/ADMIN 另见 checkpoint/reusedSteps/blockedReasons
   * 原样负载（事件的 data 从该条事件事实生成，原始 payload 不对外返回）。
   */
  Object recoveryProjection(JsonNode payload, boolean privileged) {
    if (privileged) return payload;
    var userView = new LinkedHashMap<String, Object>();
    userView.put("recoverySource", payload.path("recoverySource").asText());
    userView.put("reasonCode", payload.path("reasonCode").asText());
    userView.put("reasonSummary", payload.path("reasonSummary").asText());
    return userView;
  }

  /** Last-Event-ID 解析（照 ChatSessions.parseStreamCursor 先例）：头优先、缺省同名 query 参数；非法 400。 */
  private static long parseStreamCursor(String header, String param) {
    String selected = header != null && !header.isBlank() ? header : param;
    if (selected == null || selected.isBlank()) return 0;
    if (!selected.matches("[0-9]{1,19}"))
      throw new PlatformException(400, "EVENT_CURSOR", "Last-Event-ID 必须为非负整数");
    return Long.parseLong(selected);
  }

  /** 单连接泵：批量读 PG（事实源即缓冲）→按连接时角色快照投影出帧。断开只停泵不取消任务。 */
  final class TaskEventPump implements Runnable {
    private final long taskId;
    private final long after;
    private final boolean privileged;
    private final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter;
    private final java.util.concurrent.atomic.AtomicBoolean open =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    TaskEventPump(
            long taskId,
            long after,
            boolean privileged,
            org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
      this.taskId = taskId;
      this.after = after;
      this.privileged = privileged;
      this.emitter = emitter;
    }

    @Override
    public void run() {
      try {
        long cursor = after;
        long lastEmit = System.currentTimeMillis();
        while (open.get()) {
          var rows =
                  store.db()
                          .queryForList(
                                  "select seq,type,payload from engine.task_event where task_id=?"
                                      + " and seq>? order by seq limit 200",
                                  taskId,
                                  cursor);
          if (rows.isEmpty()) {
            if (System.currentTimeMillis() - lastEmit >= taskHeartbeatMillis) {
              send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                      .event()
                      .comment("ping"));
              lastEmit = System.currentTimeMillis();
            }
            Thread.sleep(200);
            continue;
          }
          for (var row : rows) {
            long seq = ((Number) row.get("seq")).longValue();
            cursor = seq;
            String type = String.valueOf(row.get("type"));
            var payload = Json.read(String.valueOf(row.get("payload")), JsonNode.class);
            Object data =
                    "TASK_RESUMED".equals(type) ? recoveryProjection(payload, privileged) : payload;
            send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                    .event()
                    .id(Long.toString(seq))
                    .name(type)
                    .data(Json.write(data), org.springframework.http.MediaType.APPLICATION_JSON));
            lastEmit = System.currentTimeMillis();
          }
          Long watermark =
                  store.db()
                          .queryForObject(
                                  "select coalesce(max(seq),0) from engine.task_event where task_id=?",
                                  Long.class,
                                  taskId);
          if (watermark != null && watermark - cursor > ChatSessions.STREAM_BACKLOG_LIMIT) {
            close(); // 慢客户端超限断开：重连以 Last-Event-ID 续读（接口稿 §7 心跳行）
            return;
          }
        }
      } catch (java.io.IOException | IllegalStateException disconnected) {
        // 客户端断开/容器回收：SSE 只是观察通道，停泵即止，不触碰任务状态。
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      } finally {
        close();
      }
    }

    private void send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder frame)
            throws java.io.IOException {
      if (!open.get()) return;
      synchronized (emitter) {
        emitter.send(frame);
      }
    }

    void close() {
      if (!open.compareAndSet(true, false)) return;
      taskStreams.remove(this);
      try {
        emitter.complete();
      } catch (Exception alreadyGone) {
        // 客户端已断开或响应已完成：仅收口本泵。
      }
    }
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

  /**
   * T2-15（台账 §7.3）：任务结果读取——GET /v1/tasks/{taskId}/result 首版形状（接口稿 §5.2
   * result 投影）。形状 {@code {taskId,state,result?,resultSummary?,artifacts?}}：
   *
   * <ul>
   *   <li><b>result</b>：任务最终输出读回。<b>T2-13 结果读回接线前的首版恒为 null</b>——T2-13
   *       验收口径（无附件结果返回完整输出）接通后由此字段承载：无附件任务返回完整输出，
   *       有附件任务返回 resultSummary + artifacts 引用；本首版如实返回 null，不伪造输出。</li>
   *   <li><b>resultSummary</b>：goal 摘要——首版取受理 goal（≤200 字截断），无输出时不缺省。</li>
   *   <li><b>artifacts</b>：产物引用——首版恒为空列表；产物下载复用既有运行级二进制路径
   *       （GET /v1/runs/{runId}/artifacts/{id}/download，integrationAllowed=true 行），
   *       产物→运行→任务归属沿同一 principal 口径核验（skill ArtifactStore.download 既有
   *       tenant/principal/run 复核），不在此端点重复暴露产物详情。</li>
   * </ul>
   *
   * <p>归属：与 detail 同一 {@link #ownedTask} 口径——非凭据绑定主体 404（他人任务不可探测，
   * 无 403 泄露存在性）。候选预检沿用 1109 §3.4 既有机制（裁剪清单：RunController 现状已满足）。
   */
  public Map<String, Object> result(long taskId, Actor actor) {
    var task = ownedTask(taskId, actor);
    String goal = String.valueOf(task.get("goal"));
    var view = new LinkedHashMap<String, Object>();
    view.put("taskId", task.get("public_id"));
    view.put("state", String.valueOf(task.get("state")));
    // T2-13 结果读回接线前恒为 null（见方法 javadoc——首版口径，不伪造输出）。
    view.put("result", null);
    view.put("resultSummary", goal.length() <= 200 ? goal : goal.substring(0, 200));
    view.put("artifacts", List.of());
    return view;
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

  /**
   * 外部集成 caller 判定（T2-15）：网关 {@code IntegrationPolicy.clip} 是
   * {@code attributes.bizSystemCode} 的唯一写入点（/embed/ticket 出票面与直连 ApiKey 面都必经
   * clip 后才换发票据转发），门户用户票不含该属性——与 RunStatistics 按
   * {@code guard_ctx->'attributes'->'bizSystemCode'} 识别外部集成是同一判别式。
   */
  static boolean externalIntegrationCaller(Actor actor) {
    return !actor.attributes().getOrDefault("bizSystemCode", Set.of()).isEmpty();
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
