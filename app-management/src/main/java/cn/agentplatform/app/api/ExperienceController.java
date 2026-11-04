package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IntegrationConfigReleases;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 对外应用体验投影（INT-03）。契约见 contracts/configuration-profile.md §6 schema v1。
 *
 * 这个端点是"给使用者看的"，因此只认当前作用域激活的配置发布：没有激活发布就返回
 * CONFIG_RELEASE_NOT_ACTIVE，而不是回落到草稿或空体验。返回内容已经脱敏：不含 secret_ref、
 * 不含物理服务地址、不含管理员配置，只保留标题、能力清单、提示词引用与展示形态。
 */
@RestController
@RequestMapping("/v1/apps/{code}/experience")
public class ExperienceController {

    private final IntegrationConfigReleases releases;

    public ExperienceController(IntegrationConfigReleases releases) {
        this.releases = releases;
    }

    /** 环境选择只能收缩已验票据范围；缺省优先使用票据环境，不能落入另一个生产作用域。 */
    @GetMapping
    public Object experience(@PathVariable String code,
            @RequestParam(name = "environment", required = false) String environment) {
        Actor actor = Actor.current();
        Set<String> allowed = actor.attributes().getOrDefault("environment", Set.of());
        if (allowed.size() > 1 && (environment == null || environment.isBlank()))
            throw new cn.agentplatform.common.PlatformException(400, "CONFIG_ENVIRONMENT_REQUIRED", "请选择已授权的环境");
        String scope = environment == null || environment.isBlank()
                ? allowed.stream().findFirst().orElse("production") : environment;
        if (!allowed.isEmpty() && !allowed.contains(scope))
            throw new cn.agentplatform.common.PlatformException(403, "CONFIG_ENVIRONMENT_DENIED", "体验环境不在当前票据授权范围内");
        return ApiResponse.ok(releases.experience(actor, code, scope));
    }
}
