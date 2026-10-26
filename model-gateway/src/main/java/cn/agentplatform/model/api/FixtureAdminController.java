package cn.agentplatform.model.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.model.application.ModelFixtureService;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AP-S06 fixture 隔离的管理面入口。路径挂在 /admin/model-providers 前缀下，
 * 与既有 admin API 风格一致（platform-gateway 按前缀路由到 model 服务，无需改动路由表）。
 * 汇总只读；清理要求 ADMIN 且动作写入 model.fixture_audit。
 */
@RestController
public class FixtureAdminController {
    private final ModelFixtureService fixtures;

    public FixtureAdminController(ModelFixtureService fixtures) {
        this.fixtures = fixtures;
    }

    @GetMapping("/admin/model-providers/fixture")
    public Object fixtureSummary() {
        return ApiResponse.ok(fixtures.summary(Actor.current()));
    }

    @PostMapping("/admin/model-providers/fixture/purge")
    public Object fixturePurge() {
        var result = fixtures.purge(Actor.current());
        return ApiResponse.ok(
                Map.of("purged", true, "result", result));
    }
}
