package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunQuota;

import org.springframework.web.bind.annotation.*;

/** PLAT-19 T19.1：token 计量聚合读路径（按应用/用户/模型），供运行中心消费。 */
@RestController
public final class UsageController {
    private final RunQuota quota;

    public UsageController(RunQuota quota) {
        this.quota = quota;
    }

    /**
     * tenant 内按维度汇总窗口内已计量 token；tenant 来自已验 JWT，不接受请求参数指定身份。
     * 只读查询，无持久化副作用，不受 agent.engine.quota-enabled 开关控制。
     */
    @GetMapping("/admin/usage/summary")
    public Object summary(
            @RequestParam(defaultValue = "app") String by,
            @RequestParam(defaultValue = "3600") long windowSeconds) {
        return ApiResponse.ok(quota.aggregate(Actor.current(), by, windowSeconds));
    }
}
