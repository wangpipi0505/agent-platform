package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.SkillExecution;

import org.springframework.web.bind.annotation.*;

/**
 * PLAT-05 T05.2：任务结果只读端点（门户面，经网关 /admin/skills/** 代理）。
 * controller 只校验参数并转调应用层；不存在/未终态由应用层按既有语义抛可读 4xx。
 */
@RestController
public class TaskResultController {
    private final SkillExecution execution;

    public TaskResultController(SkillExecution execution) {
        this.execution = execution;
    }

    @GetMapping("/admin/skills/tasks/{callId}/result")
    public Object result(@PathVariable long callId) {
        return ApiResponse.ok(execution.taskResult(callId));
    }
}
