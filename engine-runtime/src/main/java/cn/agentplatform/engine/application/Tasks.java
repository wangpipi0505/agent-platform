package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Roles;
import cn.agentplatform.common.authority.IntegrationRuntimeRoutes.TaskScope;
import cn.agentplatform.common.authority.IntegrationTaskRoutes;
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
 * 规划根会话并在同一受理事务派发规划子代理（主方案 §5.5——重放复用根会话，不另建；
 * R-2 接线 {@link SubagentPlanning#startPlannerSubagents}，重放键幂等见其 javadoc）。
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
  /**
   * R-2（返工卡）：规划子代理派发——本服务内持有（SubagentPlanning 无状态仅依赖 TraceStore，
   * 与 {@code new Tasks(store)} 测试构造兼容）。规划受理路径（T2-7 原交付无调用点，A03 幂等
   * 仅测试内成立——本卡接通真实受理入口后经 {@link #create} 受理事务派发）。
   */
  private final SubagentPlanning planning;
  /**
   * R-19（T2-2 收尾）：发布快照只读来源（app-management 发布链，{@code /internal/apps/{code}
   * /published}）——受理钉住真实 config_release 快照 id 与摘要。测试/降级组装可为 null
   * （受理回退占位降级口径，与 T2-2 交付说明一致）。
   */
  private final cn.agentplatform.engine.infra.RuntimeClients clients;
  private final TaskAgentExecution agentExecution;

  // T2-11：活动任务事件 SSE 观察流（每连接一个泵；closeTaskStreams/@PreDestroy 收口）。
  private final Set<TaskEventPump> taskStreams = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** :ping 心跳间隔（接口稿 §7：15s）；独立部署可调，测试经反射改短（照 ChatSessions 先例）。 */
  @org.springframework.beans.factory.annotation.Value("${agent.task.sse.heartbeat-millis:15000}")
  long taskHeartbeatMillis = 15000;
  /**
   * R-16（台账 §7.6b）：任务 SSE 慢消费者未读积压上限——与会话 SSE 共用同一配置键
   * {@code agent.sse.max-buffered-events}（T2-11 积压 2000 先例原值，R-16 配置化+审计化）。
   * 超限主动断开并记审计行（客户端以 Last-Event-ID 重连续读，断开不取消任务）。
   */
  @org.springframework.beans.factory.annotation.Value("${agent.sse.max-buffered-events:2000}")
  int maxBufferedEvents = 2000;

  /** 全上下文组装主构造器：@Autowired 指定唯一候选（照 Memories 双构造器先例，R-9 缺陷①）。 */
  @org.springframework.beans.factory.annotation.Autowired
  public Tasks(
      TraceStore store,
      SubagentPlanning planning,
      cn.agentplatform.engine.infra.RuntimeClients clients,
      TaskAgentExecution agentExecution) {
    this.store = store;
    this.planning = planning;
    this.clients = clients;
    this.agentExecution = agentExecution;
  }

  public Tasks(TraceStore store, SubagentPlanning planning,
               cn.agentplatform.engine.infra.RuntimeClients clients) {
    this(store, planning, clients, null);
  }

  /** 测试/降级组装：无发布快照来源时受理按占位降级口径钉住（R-19 如实登记）。 */
  public Tasks(TraceStore store) {
    this(store, new SubagentPlanning(store), null);
  }

  /** The task, credential provenance and dispatch commit in the real run admission transaction. */
  public Map<String, Object> createIntegration(String code, JsonNode request, Actor actor, TaskScope scope) {
    if (!code.equals(scope.appCode()) || !IntegrationTaskRoutes.submitRoles(actor.roles()) || agentExecution == null)
      throw new PlatformException(403, "APP_BINDING_DENIED", "集成票无权提交此应用任务");
    if (!request.isObject())
      throw new PlatformException(400, "TASK_REQUEST_INVALID", "任务请求必须是 JSON 对象");
    if (!"AGENT".equals(request.path("mode").textValue()))
      throw new PlatformException(403, "SERVICE_AGENT_ONLY", "集成方仅可提交 AGENT 任务");
    request.fieldNames().forEachRemaining(field -> {
      if (!Set.of("mode", "requestId", "goal", "inputs", "toolPolicy").contains(field))
        throw new PlatformException(400, "TASK_REQUEST_INVALID", "集成任务包含未登记字段");
    });
    var tools = request.path("toolPolicy");
    if (!tools.isMissingNode() && !(tools.isObject() && tools.size() == 1
            && tools.path("allowedToolRefs").isArray() && tools.path("allowedToolRefs").isEmpty())) {
      org.slf4j.LoggerFactory.getLogger("agent.integration.audit").warn(
              "AUDIT INTEGRATION_PATH_FORBIDDEN credential={} app={} traceId={}", scope.credentialId(), code,
              cn.agentplatform.common.TraceContextFilter.current());
      throw new PlatformException(403, "INTEGRATION_PATH_FORBIDDEN", "集成任务暂不接受工具授权");
    }
    String requestId = request.path("requestId").textValue();
    String goal = request.path("goal").textValue();
    var requestedInputs = request.path("inputs");
    if (requestId == null || requestId.isBlank() || requestId.length() > 128 || goal == null || goal.isBlank()
            || (!requestedInputs.isMissingNode() && !requestedInputs.isObject()))
      throw new PlatformException(400, "TASK_REQUEST_INVALID", "任务需要 requestId、goal 和对象形式的 inputs");
    var inputs = requestedInputs.isEmpty() ? Json.tree(Map.of("message", goal)) : requestedInputs;
    String key = "integration-" + Digest.sha256(Json.canonical(List.of(scope.bizSystemCode(), code, requestId)));
    String digest = Digest.sha256(Json.canonical(request));
    var existing = integrationReplay(actor, scope, key, digest);
    if (existing != null) return existing;
    try {
      agentExecution.startIntegration(code, inputs, actor,
              runId -> insertIntegrationTask(runId, actor, scope, key, digest, goal, inputs));
    } catch (IntegrationTaskReplay concurrent) {
      // A competing insert won. The losing run and its scopes rolled back with the task insert.
    }
    existing = integrationReplay(actor, scope, key, digest);
    if (existing == null) throw new IllegalStateException("INTEGRATION_TASK_ADMISSION_MISSING");
    return existing;
  }

  private Map<String, Object> integrationReplay(Actor actor, TaskScope scope, String key, String digest) {
    var matches = store.db().queryForList("""
            select public_id,request_digest from engine.task
             where tenant_id=? and principal_id=? and request_id=?
            """, actor.tenantId(), actor.principalId(), key);
    if (matches.isEmpty()) return null;
    var prior = matches.getFirst();
    long id = resolveIntegrationTask((String) prior.get("public_id"), actor, scope);
    if (!digest.equals(prior.get("request_digest")))
      throw new PlatformException(409, "REQUEST_REPLAY", "同一 requestId 已用于不同参数的任务");
    return detail(id, actor);
  }

  private long insertIntegrationTask(long runId, Actor actor, TaskScope scope, String key, String digest,
          String goal, JsonNode inputs) {
    String publicId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var inserted = store.db().queryForList("""
            insert into engine.task(public_id,tenant_id,principal_id,app_code,config_release_id,config_digest,
                mode,purpose,goal,input_ref,input_digest,state,request_id,request_digest)
            select ?,r.tenant_id,r.principal_id,r.app_code,'appver-'||r.app_version_id,r.snapshot->>'candidateSha256',
                'AGENT','EXECUTE',?,?,?,'ACCEPTED',?,?
              from engine.run r where r.id=? and r.tenant_id=? and r.principal_id=? and r.app_code=?
            on conflict (tenant_id,principal_id,request_id) do nothing returning id
            """, Long.class, publicId, goal, "task-input/" + publicId, Digest.sha256(Json.canonical(inputs)), key,
            digest, runId, actor.tenantId(), actor.principalId(), scope.appCode());
    if (inserted.isEmpty()) throw new IntegrationTaskReplay();
    long id = inserted.getFirst();
    store.db().update("""
            insert into engine.task_integration_scope(task_id,credential_id,biz_system_code,data_labels)
            values(?,?,?,?::jsonb)
            """, id, scope.credentialId(), scope.bizSystemCode(), Json.write(scope.dataLabels()));
    appendTaskEvent(id, "TASK_ACCEPTED", Json.tree(Map.of("mode", "AGENT", "goal", goal)));
    return id;
  }

  private static final class IntegrationTaskReplay extends RuntimeException {}

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
    boolean direct = agentExecution != null && "AGENT".equals(mode)
            && req.path("sessionId").asText("").isBlank()
            && (dependencies == null || dependencies.isEmpty());
    Set<String> allowedTools = direct ? allowedTaskTools(toolPolicy) : null;
    JsonNode executionInputs = direct && inputs.isEmpty()
            ? Json.tree(Map.of("message", goal)) : inputs;

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
      if (!code.equals(task.get("app_code"))
              || !Digest.sha256(digest).equals(String.valueOf(task.get("request_digest"))))
        throw new PlatformException(409, "REQUEST_REPLAY", "同一 requestId 已用于不同参数的任务");
      long id = TraceStore.number(task, "id");
      if (direct) agentExecution.start(id, code, executionInputs, allowedTools, actor);
      return detail(id, actor);
    }

    // TASK_INTERNAL 根会话（主方案 §5.5）：无用户会话的 PLAN 任务显式启用规划子代理时，
    // 受理事务创建 origin=TASK_INTERNAL 规划根会话（重放复用——上方幂等分支已返回）。
    // R-2：同口径捕获规划受理路径（根会话存在的唯一场景），受理事务内派发规划子代理。
    boolean plannerAcceptance =
            "PLAN".equals(mode)
                    && req.path("subagentPolicy").path("enabled").asBoolean(false)
                    && req.path("sessionId").asText("").isBlank();
    String sessionId =
            plannerAcceptance
                    ? createInternalRootSession(code, actor)
                    : req.path("sessionId").asText("").isBlank() ? null : req.path("sessionId").asText();

    String publicId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    // R-19（T2-2 收尾）：受理钉住（接口稿 §3.1）——发布快照经 app-management 只读接口取得，
    // 任务创建事务内钉住当次 config_release 真实快照：releaseId=appver-<app_version 行 id>
    // （发布链返回的真实快照行身份），digest=发布时内容摘要 candidateSha256（缺形状时按
    // 发布快照规范 JSON 现算，同版本确定一致）。发布不可得（未发布/停用/服务不可达/测试
    // 无来源组装）保持 T2-2 占位降级口径落库（unpinned-…/release-pin:…，如实登记）；
    // 钉住值随受理事务写入 task 行（重放分支在上方先行返回，不重复取快照）。
    String pinnedReleaseId;
    String pinnedDigest;
    var pin = pinRelease(code, actor);
    if (pin != null) {
      pinnedReleaseId = pin.releaseId();
      pinnedDigest = pin.digest();
    } else {
      pinnedReleaseId =
              "unpinned-"
                      + Integer.toHexString(
                              Digest.sha256("release-pin:" + actor.tenantId() + ":" + code + ":v1")
                                      .substring(0, 8)
                                      .hashCode());
      pinnedDigest = Digest.sha256("release-pin:" + actor.tenantId() + ":" + code + ":v1");
    }
    store.atomic(
            () -> {
              int inserted = store.db()
                      .update(
                              "insert into engine.task(public_id,tenant_id,principal_id,app_code,"
                                  + "config_release_id,config_digest,mode,purpose,goal,input_ref,input_digest,"
                                  + "session_id,session_cursor,state,request_id,request_digest,tool_policy)"
                                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)"
                                  + (direct ? " on conflict (tenant_id,principal_id,request_id) do nothing" : ""),
                              publicId,
                              actor.tenantId(),
                              actor.principalId(),
                              code,
                              pinnedReleaseId,
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
              if (inserted == 0) return null;
              long taskId =
                      store.db()
                              .queryForObject(
                                      "select id from engine.task where public_id=?", Long.class, publicId);
              // 任务事件目录 10 类（contracts/task-states.md §4）：受理落 TASK_ACCEPTED
              appendTaskEvent(taskId, "TASK_ACCEPTED", Json.tree(Map.of("mode", mode, "goal", goal)));
              // R-2（返工卡）：规划子代理受理派发（T2-7 startPlannerSubagents 原无调用点）——
              // 与 TASK_INTERNAL 根会话同一受理事务：锁父任务行→重放键 (parent_task_id, role)
              // 查重→建三件套（子会话+子任务+目录行）。requestId 重放在上方幂等分支先行返回，
              // 同请求重放不会二次派发（A03 主断言经真实受理路径成立）。锁序安全：父任务行是
              // 本事务新建行，先锁后插子行，与 Plans.execute 的执行任务插入（PLANNED_EXEC 19 列，
              // 只触达既有父任务）无锁序倒置。subagentPolicy 可选 role/count/outputSchema
              // （缺省 PLANNER_ALGO×2，主方案 §5.5 并行规划量级）；派发参数非法（400
              // SUBAGENT_REQUEST_INVALID）随受理整体回滚——任务与子代理同生同灭（单受理事务）。
              if (plannerAcceptance) {
                planning.startPlannerSubagents(
                        taskId,
                        req.path("subagentPolicy").path("role").asText("PLANNER_ALGO"),
                        req.path("subagentPolicy").path("count").asInt(2),
                        req.path("subagentPolicy").has("outputSchema")
                                ? req.path("subagentPolicy").path("outputSchema")
                                : null);
              }
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
    var accepted = store.db().queryForMap(
            "select id,app_code,request_digest from engine.task where tenant_id=? and principal_id=? and request_id=?",
            actor.tenantId(), actor.principalId(), requestId);
    if (!code.equals(accepted.get("app_code")) || !Digest.sha256(digest).equals(accepted.get("request_digest")))
      throw new PlatformException(409, "REQUEST_REPLAY", "同一 requestId 已用于不同参数的任务");
    long id = TraceStore.number(accepted, "id");
    if (direct) agentExecution.start(id, code, executionInputs, allowedTools, actor);
    return detail(id, actor);
  }

  private static Set<String> allowedTaskTools(JsonNode policy) {
    if (policy == null || policy.isNull()) return null;
    JsonNode refs = policy.path("allowedToolRefs");
    if (!refs.isArray())
      throw new PlatformException(400, "TASK_REQUEST_INVALID", "toolPolicy.allowedToolRefs 必须是数组");
    var allowed = new HashSet<String>();
    for (JsonNode ref : refs) {
      if (!ref.isTextual() || ref.asText().isBlank())
        throw new PlatformException(400, "TASK_REQUEST_INVALID", "工具引用必须为非空字符串");
      allowed.add(ref.asText());
    }
    return Set.copyOf(allowed);
  }

  // ------------------------------------------------------------------
  // B4-1（台账 §7.5）：GET /v1/tasks 任务列表 + confirmableBy=me 计划确认投影（接口稿 §5.7）
  // ------------------------------------------------------------------

  private static final Set<String> TASK_STATES =
          Set.of(
                  "ACCEPTED", "PLANNING", "PLAN_READY", "NEEDS_INPUT", "WAITING_DEPENDENCIES",
                  "QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_EXTERNAL", "WAITING_RECONCILIATION",
                  "SUCCEEDED", "FAILED", "CANCELLED");

  /**
   * 任务列表（接口稿 §5.7）：state/appCode/from/to/principalId 过滤，授权范围过滤先于分页，
   * 计数=未经分页的未分页总数（与角标一致性契约）。资源范围：ADMIN 限本租户；其余角色
   * 限提交人本人——引擎侧无工作区成员数据源（工作区授权归口 app-management），OPERATOR/
   * BUILDER 的"已授权工作区/其工作区"暂按提交人本人口径收窄（不放大），待工作区目录
   * 接线后替换；APPROVER 无本人提交面，列表恒空——其确认面走 confirmableBy=me 投影。
   */
  public Map<String, Object> list(
          String state,
          String appCode,
          String from,
          String to,
          String principalId,
          String page,
          String pageSize,
          Actor actor) {
    if (state != null && !state.isBlank() && !TASK_STATES.contains(state))
      throw new PlatformException(400, "TASK_STATE_INVALID", "state 不在 13 值任务状态机内");
    if (from != null && !from.isBlank() && !from.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?"))
      throw new PlatformException(400, "INVALID_INPUT", "开始时间格式无效");
    if (to != null && !to.isBlank() && !to.matches("\\d{4}-\\d{2}-\\d{2}([T ].*)?"))
      throw new PlatformException(400, "INVALID_INPUT", "结束时间格式无效");
    boolean admin = actor.roles().contains("ADMIN");
    // principalId 过滤仅 ADMIN 可用（范围只来自当前身份，照 RunService.page 先例拒绝自报范围）
    if (principalId != null && !principalId.isBlank()) {
      if (!admin && !principalId.equals(actor.principalId()))
        throw new PlatformException(403, "TASK_SCOPE_FORBIDDEN", "任务查询范围仅来自当前身份");
      if (principalId.length() > 128) throw new PlatformException(400, "INVALID_INPUT", "principalId 过长");
    }
    var where =
            new StringBuilder(" from engine.task t where t.tenant_id=?"
                    + (admin ? "" : " and t.principal_id=?"));
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (!admin) args.add(actor.principalId());
    if (principalId != null && !principalId.isBlank()) {
      where.append(" and t.principal_id=?");
      args.add(principalId);
    }
    if (state != null && !state.isBlank()) {
      where.append(" and t.state=?");
      args.add(state);
    }
    if (appCode != null && !appCode.isBlank()) {
      if (appCode.length() > 128) throw new PlatformException(400, "INVALID_INPUT", "appCode 过长");
      where.append(" and t.app_code=?");
      args.add(appCode);
    }
    if (from != null && !from.isBlank()) {
      where.append(" and t.created_at >= ?::timestamptz");
      args.add(from);
    }
    if (to != null && !to.isBlank()) {
      where.append(" and t.created_at < (?::date + interval '1 day')");
      args.add(to);
    }
    int pageNo = parsePage(page, "page");
    int size = parsePage(pageSize, "pageSize");
    if (size > 100) size = 100;
    Long total = store.db().queryForObject("select count(*)" + where, Long.class, args.toArray());
    var pageArgs = new ArrayList<Object>(args);
    pageArgs.add(size);
    pageArgs.add((pageNo - 1) * size);
    var rows =
            store.db()
                    .queryForList(
                            "select t.public_id as \"taskId\",t.state,t.mode,t.purpose,"
                                + "t.app_code as \"appCode\",t.goal,t.principal_id as \"principalId\","
                                + "t.created_at as \"createdAt\",t.updated_at as \"updatedAt\""
                                + where
                                + " order by t.id desc limit ? offset ?",
                            pageArgs.toArray());
    var data = new LinkedHashMap<String, Object>();
    data.put("tasks", rows);
    data.put("page", pageNo);
    data.put("pageSize", size);
    data.put("total", total);
    return data;
  }

  /**
   * confirmableBy=me（评审八轮 P1-1，九轮补状态字段）：确认中心"计划确认"待办来源——以修订为
   * 单位返回 {@code {taskId,planId,revision,digest,goal 摘要,confirmState,confirmedBy?,expiresAt?}}。
   * 每任务每计划取头修订；仅返回当前无有效凭据（未确认或最大 confirm_seq 凭据已过期）且
   * validation_status=VALID 的修订——已确认且在有效期内的修订不出现（刷新不回流）。
   * 资源范围（§5.9 计划确认可见范围=提交人本人/APPROVER/ADMIN）：ADMIN/APPROVER 覆盖本租户；
   * 其余角色限提交人本人（同 {@link #list} 口径）。计数与四类列表共用本投影（角标一致性契约）。
   */
  public List<Map<String, Object>> confirmableRevisions(String appCode, Actor actor) {
    boolean tenantWide = actor.roles().contains("ADMIN") || actor.roles().contains("APPROVER");
    var condition =
            new StringBuilder(
                    " join engine.task t on t.id=pr.owner_task_id"
                            + " where t.tenant_id=? and t.state='PLAN_READY'"
                            + (tenantWide ? "" : " and t.principal_id=?"));
    var args = new ArrayList<Object>();
    args.add(actor.tenantId());
    if (!tenantWide) args.add(actor.principalId());
    if (appCode != null && !appCode.isBlank()) {
      condition.append(" and t.app_code=?");
      args.add(appCode);
    }
    // 头修订（每任务每计划一条）+ VALID + 最大 confirm_seq 凭据过期或不存在
    var heads =
            store.db()
                    .queryForList(
                            "with head as (select pr.owner_task_id,pr.plan_id,max(pr.revision) as revision"
                                + " from engine.plan_revision pr"
                                + condition
                                + " group by pr.owner_task_id,pr.plan_id)"
                                + " select pr.id as revision_id,pr.plan_id as \"planId\",pr.revision,"
                                + "pr.digest,t.public_id as \"taskId\",t.goal"
                                + " from head h"
                                + " join engine.plan_revision pr on pr.owner_task_id=h.owner_task_id"
                                + " and pr.plan_id=h.plan_id and pr.revision=h.revision"
                                + " join engine.task t on t.id=h.owner_task_id"
                                + " where pr.validation_status='VALID'"
                                + " and not exists (select 1 from engine.plan_confirmation pc"
                                + "   where pc.plan_revision_id=pr.id and pc.confirm_seq="
                                + "     (select max(confirm_seq) from engine.plan_confirmation"
                                + "      where plan_revision_id=pr.id)"
                                + "   and pc.expires_at>now())"
                                + " order by t.id desc, pr.plan_id",
                            args.toArray());
    var items = new ArrayList<Map<String, Object>>();
    for (var head : heads) {
      var item = new LinkedHashMap<String, Object>();
      item.put("taskId", head.get("taskId"));
      item.put("planId", head.get("planId"));
      item.put("revision", head.get("revision"));
      item.put("digest", head.get("digest"));
      item.put("goal", goalSummary(String.valueOf(head.get("goal"))));
      // 最新（最大 confirm_seq）凭据存在但过期→CONFIRMED_EXPIRED（附 confirmedBy/expiresAt）；
      // 无凭据→UNCONFIRMED。有效凭据已在上游 SQL 排除（刷新不回流）。
      var credentials =
              store.db()
                      .queryForList(
                              "select * from engine.plan_confirmation where plan_revision_id=?"
                                  + " order by confirm_seq desc limit 1",
                              head.get("revision_id"));
      if (credentials.isEmpty()) {
        item.put("confirmState", "UNCONFIRMED");
      } else {
        var credential = credentials.getFirst();
        item.put("confirmState", "CONFIRMED_EXPIRED");
        item.put("confirmedBy", credential.get("confirmed_by"));
        item.put("expiresAt", String.valueOf(credential.get("expires_at")));
      }
      items.add(item);
    }
    return items;
  }

  /** goal 摘要（列表项不携带全量目标文本；≤200 字截断）。 */
  private static String goalSummary(String goal) {
    if (goal == null) return "";
    return goal.length() <= 200 ? goal : goal.substring(0, 200);
  }

  /**
   * R-19：受理钉住的发布快照身份（app-management 发布链）。releaseId=真实快照行 id
   * （{@code appver-<app_version.id>}，varchar(64) 内）；digest 优先发布时内容摘要
   * candidateSha256（64-hex 形状校验），缺失/越形按发布快照规范 JSON 现算（同版本确定
   * 一致、跨版本必然不同）。来源缺失或发布不可得返回 null（受理回退占位降级口径，
   * 不阻塞受理——发布状态由运行面依赖的既有链路另行核验）。
   */
  record ReleasePin(String releaseId, String digest) {}

  private ReleasePin pinRelease(String code, Actor actor) {
    if (clients == null) return null;
    JsonNode published;
    try {
      published = clients.publishedForRuntime(code, actor);
    } catch (RuntimeException unavailable) {
      // 未发布/停用/版本切换竞态/服务不可达：占位降级口径（T2-2 交付说明），不阻塞受理。
      return null;
    }
    long versionId =
            published.path("appVersionId").asLong(published.path("versionId").asLong(0));
    if (versionId <= 0) return null;
    String releaseId = "appver-" + versionId;
    String candidate = published.path("candidateSha256").asText("");
    String digest =
            candidate.matches("^[0-9a-f]{64}$")
                    ? candidate
                    : Digest.sha256(Json.canonical(published));
    return new ReleasePin(releaseId, digest);
  }

  private static int parsePage(String raw, String label) {
    if (raw == null || raw.isBlank()) return label.equals("pageSize") ? 20 : 1;
    if (!raw.matches("[0-9]{1,9}") || Integer.parseInt(raw) < 1)
      throw new PlatformException(400, "TASK_PAGE_INVALID", label + " 必须为正整数");
    return Integer.parseInt(raw);
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
    // T2-7：父子任务关联投影（接口稿 §5.2 detail 形状 parentTaskId/role/subtasks——任务卡消费
    // subtasks 保留任务视角的执行与合并状态；合并决定读 subtask_receipt）。
    view.put(
            "parentTaskId",
            task.get("parent_task_id") == null
                    ? null
                    : store
                            .db()
                            .queryForObject(
                                    "select public_id from engine.task where id=?",
                                    String.class,
                                    task.get("parent_task_id")));
    view.put("role", task.get("role"));
    view.put(
            "subtasks",
            store.db()
                    .queryForList(
                            "select c.public_id as task_id, c.role, c.state,"
                                + " r.merged_decision as merged_decision"
                                + " from engine.task c"
                                + " left join engine.subtask_receipt r on r.child_task_id=c.id"
                                + " where c.parent_task_id=? order by c.id",
                            taskId));
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
          if (watermark != null && watermark - cursor > Math.max(1, maxBufferedEvents)) {
            // R-16：慢消费者超限断开记审计行（未读积压与上限可追溯）；重连以 Last-Event-ID
            // 续读（接口稿 §7 心跳行），SSE 只是观察通道——断开不取消任务。
            org.slf4j.LoggerFactory.getLogger(Tasks.class)
                    .warn(
                            "AUDIT TASK_SSE_SLOW_CONSUMER_DISCONNECT taskId={} unreadBacklog={} limit={} resume=Last-Event-ID",
                            taskId,
                            watermark - cursor,
                            Math.max(1, maxBufferedEvents));
            close();
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
    store.atomic(() -> {
      var task = ownedTask(taskId, actor, true);
      String state = String.valueOf(task.get("state"));
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)) return null;
      if (agentExecution != null && !agentExecution.cancel(taskId)) return null;
      markCancelled(taskId, state, ((Number) task.get("state_version")).intValue(), actor);
      return null;
    });
    return detail(taskId, actor);
  }

  /** Control is scoped to the same credential application and business system, with a metadata-only reply. */
  public Map<String, Object> cancelIntegration(String publicId, JsonNode request, Actor actor,
          cn.agentplatform.common.authority.IntegrationRuntimeRoutes.TaskScope scope) {
    if (!actor.roles().contains("AGENT_ADMIN"))
      throw new PlatformException(403, "APP_BINDING_DENIED", "集成票无权中断任务");
    if (request != null && (!request.isObject() || !request.isEmpty()))
      throw new PlatformException(400, "TASK_REQUEST_INVALID", "集成任务中断不接受额外字段");
    var result = store.<Map<String, Object>>atomic(() -> {
      var rows = store.db().queryForList("""
              select t.id,t.state,t.state_version,t.app_code,s.biz_system_code,r.public_id as run_public_id
                from engine.task t
                left join engine.task_integration_scope s on s.task_id=t.id
                left join engine.task_dispatch d on d.task_id=t.id and d.step_id=?
                left join engine.run r on r.id=d.run_id and r.tenant_id=t.tenant_id
                  and r.principal_id=t.principal_id and r.app_code=t.app_code and r.trigger_type in ('CHAT','TASK')
               where t.public_id=? and t.tenant_id=?
               for update of t
              """, StepExecutionMapping.ENTRY, publicId, actor.tenantId());
      var task = rows.isEmpty() ? Map.<String, Object>of() : rows.getFirst();
      String targetSoftware = (String) task.get("biz_system_code");
      if (!scope.appCode().equals(task.get("app_code")) || !scope.bizSystemCode().equals(targetSoftware)) {
        recordIntegrationControl(actor, scope, publicId, targetSoftware, null, "DENIED");
        return null;
      }
      String state = (String) task.get("state");
      if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state))
        return Map.of("taskId", publicId, "state", state);
      String run = (String) task.get("run_public_id");
      if (run == null || agentExecution == null)
        throw new PlatformException(409, "TASK_NOT_RUNNING", "任务尚无可中断的运行");
      long taskId = ((Number) task.get("id")).longValue();
      if (!agentExecution.cancel(taskId))
        throw new PlatformException(409, "TASK_NOT_RUNNING", "关联运行已结束");
      if (!markCancelled(taskId, state, ((Number) task.get("state_version")).intValue(), actor))
        throw new IllegalStateException("TASK_CANCEL_STATE_CHANGED");
      recordIntegrationControl(actor, scope, publicId, targetSoftware, run, "CANCELLED");
      return Map.of("taskId", publicId, "state", "CANCELLED");
    });
    if (result == null)
      throw new PlatformException(403, "APP_BINDING_DENIED", "集成票无权中断此任务");
    return result;
  }

  private boolean markCancelled(long taskId, String state, int version, Actor actor) {
    int changed = store.db().update("""
            update engine.task set state='CANCELLED',state_version=state_version+1,
              cancel_requested_at=now(),updated_at=now() where id=? and state=? and state_version=?
            """, taskId, state, version);
    if (changed == 1)
      appendTaskEvent(taskId, "TASK_FINISHED", Json.tree(Map.of("state", "CANCELLED", "by", actor.principalId())));
    return changed == 1;
  }

  private void recordIntegrationControl(Actor actor,
          cn.agentplatform.common.authority.IntegrationRuntimeRoutes.TaskScope scope,
          String publicId, String targetSoftware, String run, String outcome) {
    store.db().update("""
            insert into engine.integration_task_control_audit
              (tenant_id,principal_id,credential_id,app_code,source_software,target_software,
               task_public_id,run_public_id,outcome,trace_id)
            values(?,?,?,?,?,?,?,?,?,?)
            """, actor.tenantId(), actor.principalId(), scope.credentialId(), scope.appCode(),
            scope.bizSystemCode(), targetSoftware, publicId, run, outcome,
            cn.agentplatform.common.TraceContextFilter.current());
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
   *   <li><b>result</b>：单步 AGENT 任务成功后返回已提交的入口运行输出，未形成输出时返回 null。</li>
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
    JsonNode output = null;
    if ("SUCCEEDED".equals(task.get("state")) && task.get("result_ref") != null) {
      var rows = store.db().queryForList(
          "select r.output::text from engine.task_step s join engine.run r on r.id=s.run_id"
              + " where s.task_id=? and s.step_id=? and r.tenant_id=? and r.principal_id=?"
              + " and r.status='SUCCEEDED' and r.output is not null",
          String.class, taskId, StepExecutionMapping.ENTRY, actor.tenantId(), actor.principalId());
      if (!rows.isEmpty()) output = Json.read(rows.getFirst());
    }
    view.put("result", output);
    view.put("resultSummary", goal.length() <= 200 ? goal : goal.substring(0, 200));
    view.put("artifacts", List.of());
    return view;
  }

  /**
   * R-9⑤（M5-2 缺陷登记②收口）：{taskId} 路径变量解析——契约形状为对外业务号。
   *
   * <p>契约依据：contracts/error-codes.md §1"对外标识一律 public_id/taskId 等业务号，
   * 不暴露内部 bigint" + 接口稿《04-接口设计-20261112》§5.2（taskId=task-N 形状；
   * 受理/列表/详情响应均回业务号）。受理/列表返回 public_id，后续端点此前仅收内部
   * bigint（400 MethodArgumentTypeMismatch，浏览器面无桥）。解析规则：
   * 纯数字=内部 id 直传（存量 caller 与既有测试兼容，不扩大暴露面——形状本就经网关
   * 与日志外的同一通道）；其余按 public_id 在本租户内查内部 id；查无→404
   * TASK_NOT_FOUND（与 {@link #ownedTask} 同口径，不区分"不存在"与"他租户"）。
   * 归属核验仍由 ownedTask 以 id+tenant+principal 承接，本方法只做形状解析。
   */
  public long resolveTaskId(String raw, Actor actor) {
    if (raw.matches("[0-9]{1,18}")) return Long.parseLong(raw);
    if (raw.length() > 100) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
    var rows =
            store.db()
                    .queryForList(
                            "select id from engine.task where public_id=? and tenant_id=?",
                            raw,
                            actor.tenantId());
    if (rows.isEmpty() || rows.getFirst().get("id") == null)
      throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
    return ((Number) rows.getFirst().get("id")).longValue();
  }

  /** Only rows with credential-backed admission provenance can be read using an IAM integration ticket. */
  public long resolveIntegrationTask(String publicId, Actor actor,
          cn.agentplatform.common.authority.IntegrationRuntimeRoutes.TaskScope scope) {
    var ids = store.db().queryForList("""
            select t.id from engine.task t join engine.task_integration_scope a on a.task_id=t.id
             where t.public_id=? and t.tenant_id=? and t.principal_id=? and t.app_code=?
               and a.biz_system_code=? and a.data_labels <@ ?::jsonb
            """, Long.class, publicId, actor.tenantId(), actor.principalId(), scope.appCode(),
            scope.bizSystemCode(), Json.write(scope.dataLabels()));
    if (ids.isEmpty()) {
      org.slf4j.LoggerFactory.getLogger("agent.integration.audit").warn(
              "AUDIT INTEGRATION_PATH_FORBIDDEN tenant={} software={} app={} task={} traceId={}",
              actor.tenantId(), scope.bizSystemCode(), scope.appCode(), publicId,
              cn.agentplatform.common.TraceContextFilter.current());
      throw new PlatformException(403, "APP_BINDING_DENIED", "集成票无权读取此任务");
    }
    return ids.getFirst();
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor) {
    return ownedTask(taskId, actor, false);
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor, boolean lock) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.task where id=? and tenant_id=? and principal_id=?"
                                + (lock ? " for update" : ""),
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

  /** 特权门（R-17：角色判定引 §3.1 授权目录常量，与对账端点/诊断视图门同源）。 */
  private boolean privileged(Actor actor) {
    return Roles.hasAny(actor, Roles.OPERATOR, Roles.ADMIN);
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
