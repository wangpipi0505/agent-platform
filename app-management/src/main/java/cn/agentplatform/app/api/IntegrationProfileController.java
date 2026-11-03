package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IntegrationProfiles;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ADMIN 接入档案目录（INT-03）。契约见 contracts/configuration-profile.md §2、assistant-integration.md §8。
 *
 * 接入档案回答"哪个业务软件以哪个接入等级暴露给哪个应用"：环境、来源白名单、上下文声明与凭据策略引用
 * 都在这里声明。它不携带凭据本体，也不允许在配置里写任意回调地址，因此对所有读写统一要求 ADMIN。
 */
@RestController
@RequestMapping("/admin/integration-profiles")
public class IntegrationProfileController {

    private final IntegrationProfiles profiles;

    public IntegrationProfileController(IntegrationProfiles profiles) {
        this.profiles = profiles;
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list(@RequestParam(required = false) String appCode) {
        admin();
        return ApiResponse.ok(profiles.list(appCode));
    }

    @PostMapping
    public Object create(@RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(profiles.create(body));
    }

    @GetMapping("/{profileId}")
    public Object get(@PathVariable String profileId) {
        admin();
        return ApiResponse.ok(profiles.get(profileId));
    }

    @PostMapping("/{profileId}/versions")
    public Object revise(@PathVariable String profileId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(profiles.revise(profileId, body));
    }

    @PostMapping("/{profileId}/approve")
    public Object approve(@PathVariable String profileId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(profiles.approve(profileId, body));
    }

    @PostMapping("/{profileId}/disable")
    public Object disable(@PathVariable String profileId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(profiles.disable(profileId, body));
    }
}
