package cn.agentplatform.app.api;

import cn.agentplatform.app.application.ApprovedConnections;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

/**
 * 运行期只读解析入口（INT-02 子步2）：{@code GET /internal/connection-profiles/{id}/versions/{rev}/resolve}。
 *
 * 这是 app 对外唯一的"把配置交给运行期"的通道，因此：
 * - 只读且幂等，不产生任何修订、授权或状态变化；
 * - 只有内部调用方（skill/engine 的服务票据）能到达，路由守卫由 ServiceAuthorization 统一注册，
 *   本控制器不额外做 ADMIN 判定——它不是给人用的管理接口；
 * - 不做跨仓授权决策：返回的是"声明 + 已批准绑定 + 有效授权"的事实，是否允许某次调用由调用方判定。
 */
@RestController
public class InternalConnectionProfilesController {

    private final ApprovedConnections connections;

    public InternalConnectionProfilesController(ApprovedConnections connections) {
        this.connections = connections;
    }

    @GetMapping("/internal/connection-profiles/{profileId}/versions/{profileRev}/resolve")
    public Object resolve(@PathVariable String profileId, @PathVariable int profileRev,
            @RequestParam(name = "environment", required = false) String environment) {
        return ApiResponse.ok(connections.resolve(profileId, profileRev, environment));
    }
}
