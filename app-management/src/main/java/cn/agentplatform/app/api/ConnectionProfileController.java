package cn.agentplatform.app.api;

import cn.agentplatform.app.application.ConnectionProfiles;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ADMIN 连接档案目录（INT-02）。契约见 contracts/configuration-profile.md §2。
 *
 * 鉴权放在最前：这些接口决定"Agent 能连到哪个系统、以什么身份、用哪些操作"，任何一条被普通用户读到
 * 都等于泄露拓扑，因此四个动作统一要求 ADMIN，且不提供任何匿名或集成票据入口。
 * 修订一旦生成不可修改，只能追加 profile_rev+1，因此这里没有 PUT/PATCH。
 */
@RestController
@RequestMapping("/admin/connection-profiles")
public class ConnectionProfileController {

    private final ConnectionProfiles profiles;

    public ConnectionProfileController(ConnectionProfiles profiles) {
        this.profiles = profiles;
    }

    /** requireRole("ADMIN")：非 ADMIN 一律 403，早于任何 JSON 校验。 */
    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list() {
        admin();
        return ApiResponse.ok(profiles.list());
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
