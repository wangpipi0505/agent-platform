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

  public TaskController(cn.agentplatform.engine.application.Tasks tasks) {
    this.tasks = tasks;
  }

  /** POST /v1/apps/{code}/tasks：受理（mode 必填；requestId 幂等；TASK_INTERNAL 根会话）。 */
  @PostMapping("/v1/apps/{code}/tasks")
  public Object create(
          @PathVariable("code") String code, @RequestBody JsonNode req) {
    return ApiResponse.ok(tasks.create(code, req, Actor.current()));
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
}
