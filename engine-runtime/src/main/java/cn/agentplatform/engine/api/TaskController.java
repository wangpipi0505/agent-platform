package cn.agentplatform.engine.api;




import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

/**
 * T2-2（台账 §7.3）：任务域 HTTP 端点（接口稿 §5.2）。
 * 网关/authority 登记照 S2-1 目录行方式（admin-common agent-service-authority.json）。
 */
@RestController
public class TaskController {

  private final cn.agentplatform.engine.application.Tasks tasks;
  private final cn.agentplatform.engine.application.Plans plans;
  private final cn.agentplatform.engine.application.TaskReconciliation reconciliation;

  public TaskController(cn.agentplatform.engine.application.Tasks tasks,
          cn.agentplatform.engine.application.Plans plans,
          cn.agentplatform.engine.application.TaskReconciliation reconciliation) {
    this.tasks = tasks;
    this.plans = plans;
    this.reconciliation = reconciliation;
  }

  /** POST /v1/apps/{code}/tasks：受理（mode 必填；requestId 幂等；TASK_INTERNAL 根会话）。 */
  @PostMapping("/v1/apps/{code}/tasks")
  public Object create(
          @PathVariable("code") String code, @RequestBody JsonNode req) {
    return ApiResponse.ok(tasks.create(code, req, Actor.current()));
  }

  /**
   * B4-1（台账 §7.5）：GET /v1/tasks 任务列表（接口稿 §5.7）——state/appCode/from/to/principalId
   * 过滤+分页；confirmableBy=me 时切换为确认中心"计划确认"投影（以修订为单位，仅无有效凭据
   * 修订，照 §5.7 形状）。角色范围过滤先于分页（Tasks.list/confirmableRevisions javadoc）。
   */
  @GetMapping("/v1/tasks")
  public Object list(
          @RequestParam(value = "state", required = false) String state,
          @RequestParam(value = "appCode", required = false) String appCode,
          @RequestParam(value = "from", required = false) String from,
          @RequestParam(value = "to", required = false) String to,
          @RequestParam(value = "principalId", required = false) String principalId,
          @RequestParam(value = "page", required = false) String page,
          @RequestParam(value = "pageSize", required = false) String pageSize,
          @RequestParam(value = "confirmableBy", required = false) String confirmableBy) {
    Actor actor = Actor.current();
    if (confirmableBy != null && !confirmableBy.isBlank()) {
      if (!"me".equals(confirmableBy))
        throw new cn.agentplatform.common.PlatformException(
                400, "TASK_CONFIRMABLE_INVALID", "confirmableBy 仅支持 me");
      return ApiResponse.ok(
              java.util.Map.of(
                      "items", tasks.confirmableRevisions(appCode, actor),
                      "appCode", appCode == null ? "" : appCode));
    }
    return ApiResponse.ok(
            tasks.list(state, appCode, from, to, principalId, page, pageSize, actor));
  }

  /** GET /v1/tasks/{taskId}：详情（服务端计算 availableActions + recovery 角色投影）。 */
  @GetMapping("/v1/tasks/{taskId}")
  public Object detail(@PathVariable("taskId") long taskId) {
    return ApiResponse.ok(tasks.detail(taskId, Actor.current()));
  }

  /** GET /v1/tasks/{taskId}/events?afterSeq=：事件增量（排他游标，不 +1）。 */
  @GetMapping("/v1/tasks/{taskId}/events")
  public Object events(
          @PathVariable("taskId") long taskId,
          @RequestParam(value = "afterSeq", defaultValue = "0") long afterSeq,
          @RequestParam(value = "limit", defaultValue = "200") int limit) {
    return ApiResponse.ok(tasks.events(taskId, afterSeq, limit, Actor.current()));
  }

  /** T2-11（接口稿 §7）：任务事件 SSE——Last-Event-ID 排他续读，15s :ping，断开不取消任务。 */
  @GetMapping("/v1/tasks/{taskId}/events/stream")
  public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
          @PathVariable("taskId") long taskId,
          @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
          @RequestParam(value = "Last-Event-ID", required = false) String lastEventIdParam) {
    return tasks.eventStream(taskId, lastEventId, lastEventIdParam, Actor.current());
  }

  /** POST /v1/tasks/{taskId}/cancel：取消（终态幂等）。 */
  @PostMapping("/v1/tasks/{taskId}/cancel")
  public Object cancel(@PathVariable("taskId") long taskId, @RequestBody(required = false) JsonNode req) {
    return ApiResponse.ok(tasks.cancel(taskId, req, Actor.current()));
  }

  /** POST /v1/tasks/{taskId}/resume：恢复（写 TASK_RESUMED；终态无恢复）。 */
  @PostMapping("/v1/tasks/{taskId}/resume")
  public Object resume(@PathVariable("taskId") long taskId, @RequestBody(required = false) JsonNode req) {
    return ApiResponse.ok(tasks.resume(taskId, req, Actor.current()));
  }
  /** POST /v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile：对账处置提交（T2-12）。 */
  @PostMapping("/v1/tasks/{taskId}/steps/{stepId}/calls/{callId}/reconcile")
  public Object reconcile(
          @PathVariable("taskId") long taskId,
          @PathVariable("stepId") String stepId,
          @PathVariable("callId") String callId,
          @RequestBody JsonNode req) {
    return ApiResponse.ok(reconciliation.reconcile(taskId, stepId, callId, req, Actor.current()));
  }

  /** GET /v1/tasks/{taskId}/steps/{stepId}/calls：步骤调用列表（锚点定位，接口稿 §5.7）。 */
  @GetMapping("/v1/tasks/{taskId}/steps/{stepId}/calls")
  public Object stepCalls(
          @PathVariable("taskId") long taskId,
          @PathVariable("stepId") String stepId,
          @RequestParam(value = "page", defaultValue = "1") int page,
          @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return ApiResponse.ok(tasks.stepCalls(taskId, stepId, page, pageSize, Actor.current()));
  }

  // T2-3：计划 API——修订追加/确认/execute/修订读（主方案 §5.6 三要素；Plans 服务）。
  /** POST /v1/tasks/{taskId}/plans：追加计划修订（baseRevision CAS）。 */
  @PostMapping("/v1/tasks/{taskId}/plans")
  public Object appendPlan(@PathVariable("taskId") long taskId, @RequestBody JsonNode req) {
    return ApiResponse.ok(plans.appendRevision(taskId, req, Actor.current()));
  }

  /** GET /v1/tasks/{taskId}/plans/{planId}/revisions/{revision}：修订读（含确认状态投影）。 */
  @GetMapping("/v1/tasks/{taskId}/plans/{planId}/revisions/{revision}")
  public Object planRevision(
          @PathVariable("taskId") long taskId,
          @PathVariable("planId") String planId,
          @PathVariable("revision") int revision) {
    return ApiResponse.ok(plans.revisionDetail(taskId, planId, revision, Actor.current()));
  }

  /** POST .../plans/{planId}/revisions/{revision}/confirm：计划版本级确认。 */
  @PostMapping("/v1/tasks/{taskId}/plans/{planId}/revisions/{revision}/confirm")
  public Object confirmPlan(
          @PathVariable("taskId") long taskId,
          @PathVariable("planId") String planId,
          @PathVariable("revision") int revision,
          @RequestBody JsonNode req) {
    return ApiResponse.ok(plans.confirm(taskId, planId, revision, req, Actor.current()));
  }

  /** POST /v1/tasks/{taskId}/execute：选定计划执行（五项受理校验）。 */
  @PostMapping("/v1/tasks/{taskId}/execute")
  public Object executePlan(@PathVariable("taskId") long taskId, @RequestBody JsonNode req) {
    return ApiResponse.ok(plans.execute(taskId, req, Actor.current()));
  }

  /**
   * B4-1（台账 §7.5）：POST /admin/apps/{code}/draft/plan-import——计划导入草稿画布桥
   * （接口稿 §6 权限矩阵 plan-import 行）。{planId,revision,digest,draftBaselineRev}→
   * {validation{ok,issues},nodeMapping,draftRev}；plan_step→图节点受限映射与不可表达降级
   * 见 {@link cn.agentplatform.engine.application.Plans#importToCanvas}。
   */
  @PostMapping("/admin/apps/{code}/draft/plan-import")
  public Object planImport(@PathVariable("code") String code, @RequestBody JsonNode req) {
    return ApiResponse.ok(plans.importToCanvas(code, req, Actor.current()));
  }

  /**
   * T2-15（台账 §7.3）：GET /v1/tasks/{taskId}/result——任务结果读取（SERVICE 四端点白名单之一）。
   * 首版形状 {taskId,state,result=null,resultSummary=goal 摘要,artifacts=[]}；T2-13 结果读回
   * 接线后 result 承载无附件完整输出（口径见 Tasks.result javadoc）。
   */
  @GetMapping("/v1/tasks/{taskId}/result")
  public Object result(@PathVariable("taskId") long taskId) {
    return ApiResponse.ok(tasks.result(taskId, Actor.current()));
  }
}
