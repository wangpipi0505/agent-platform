package cn.agentplatform.app.api;

import cn.agentplatform.app.application.DataHealthService;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAT-20 / T20.1：数据面健康详情，仅 ADMIN 可读。复用既有鉴权模式（同 PublishWaiverController）：
 * 匿名请求被安全链以 401（SYS-AUTH-401）拒绝，已认证非 ADMIN 由 requireRole 以 403（SYS-APP-4031）
 * 拒绝——两种拒绝都是 ApiResponse 可读 JSON 信封，不留 404 盲区。
 * 公开的 /actuator/health 保持 show-details=never，库名/行数等明细只出现在本端点。
 */
@RestController
public class DataHealthController {
    private final DataHealthService data;

    public DataHealthController(DataHealthService data) {
        this.data = data;
    }

    @GetMapping("/health/data")
    public Object data() {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(data.snapshot());
    }
}
