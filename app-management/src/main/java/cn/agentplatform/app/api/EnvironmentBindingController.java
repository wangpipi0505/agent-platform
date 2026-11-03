package cn.agentplatform.app.api;

import cn.agentplatform.app.application.EnvironmentBindings;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ADMIN 环境绑定目录（INT-02）。契约见 contracts/configuration-profile.md §2。
 *
 * 绑定把"连接档案的某一不可变修订"钉到具体环境的地址与凭据引用上，暴露它等于暴露内网地址与密钥位置，
 * 因此与连接档案一样只对 ADMIN 开放；修订只能追加，没有就地修改入口。
 */
@RestController
@RequestMapping("/admin/environment-bindings")
public class EnvironmentBindingController {

    private final EnvironmentBindings bindings;

    public EnvironmentBindingController(EnvironmentBindings bindings) {
        this.bindings = bindings;
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list() {
        admin();
        return ApiResponse.ok(bindings.list());
    }

    @PostMapping
    public Object create(@RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.create(body));
    }

    @GetMapping("/{bindingId}")
    public Object get(@PathVariable String bindingId) {
        admin();
        return ApiResponse.ok(bindings.get(bindingId));
    }

    @PostMapping("/{bindingId}/versions")
    public Object revise(@PathVariable String bindingId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.revise(bindingId, body));
    }

    @PostMapping("/{bindingId}/approve")
    public Object approve(@PathVariable String bindingId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.approve(bindingId, body));
    }

    @PostMapping("/{bindingId}/disable")
    public Object disable(@PathVariable String bindingId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.disable(bindingId, body));
    }
}
