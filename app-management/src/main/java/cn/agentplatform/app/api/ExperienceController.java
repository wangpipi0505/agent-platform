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

    /** environment 缺省 production：与 app.publish_pin 的默认环境保持同一取值域。 */
    @GetMapping
    public Object experience(@PathVariable String code,
            @RequestParam(name = "environment", required = false) String environment) {
        String scope = environment == null || environment.isBlank() ? "production" : environment;
        return ApiResponse.ok(releases.experience(Actor.current(), code, scope));
    }
}
