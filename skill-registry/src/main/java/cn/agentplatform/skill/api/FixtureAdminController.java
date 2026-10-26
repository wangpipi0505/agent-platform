package cn.agentplatform.skill.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.FixtureSkills;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AP-S06 技能 fixture 隔离管理面。路径挂在 /admin/skills 前缀下，与既有 admin API 风格一致
 * （platform-gateway 按前缀路由到 skill 服务）；字面路径优先于 {code} 模板，不改变现有技能
 * 路由语义。汇总只读；清理要求 ADMIN 且写入 skill.fixture_audit。
 */
@RestController
public class FixtureAdminController {
    private final FixtureSkills fixtures;

    public FixtureAdminController(FixtureSkills fixtures) {
        this.fixtures = fixtures;
    }

    @GetMapping("/admin/skills/fixture")
    public Object fixtureSummary() {
        return ApiResponse.ok(fixtures.summary(Actor.current()));
    }

    @PostMapping("/admin/skills/fixture/purge")
    public Object fixturePurge() {
        var result = fixtures.purge(Actor.current());
        return ApiResponse.ok(Map.of("purged", true, "result", result));
    }
}
