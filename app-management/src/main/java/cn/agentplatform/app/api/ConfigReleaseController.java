package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IntegrationConfigReleases;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ADMIN 配置发布目录（INT-03）。契约见 contracts/configuration-profile.md §2/§4。
 *
 * 发布把"应用版本 + 体验修订 + 接入档案修订 + 连接引用"冻结成不可变快照，再由 activate 切换作用域指针。
 * 校验（validate）会重新核对引用是否仍处于 APPROVED，因此草稿发布不会因为有人后来批准了档案就自动生效。
 */
@RestController
@RequestMapping("/admin/config-releases")
public class ConfigReleaseController {

    private final IntegrationConfigReleases releases;

    public ConfigReleaseController(IntegrationConfigReleases releases) {
        this.releases = releases;
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list(@RequestParam String appCode) {
        admin();
        return ApiResponse.ok(releases.list(appCode));
    }

    @PostMapping
    public Object create(@RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(releases.create(body));
    }

    @GetMapping("/{releaseId}")
    public Object get(@PathVariable String releaseId) {
        admin();
        return ApiResponse.ok(releases.get(releaseId));
    }

    @PostMapping("/{releaseId}/validate")
    public Object validate(@PathVariable String releaseId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(releases.validate(releaseId, body));
    }

    @PostMapping("/{releaseId}/approve")
    public Object approve(@PathVariable String releaseId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(releases.approve(releaseId, body));
    }

    @PostMapping("/{releaseId}/activate")
    public Object activate(@PathVariable String releaseId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(releases.activate(releaseId, body));
    }

    @PostMapping("/{releaseId}/disable")
    public Object disable(@PathVariable String releaseId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(releases.disable(releaseId, body));
    }
}
