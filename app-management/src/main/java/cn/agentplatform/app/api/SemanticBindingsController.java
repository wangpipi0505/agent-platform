package cn.agentplatform.app.api;

import cn.agentplatform.app.application.SemanticBindings;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ADMIN 语义绑定目录（SEM-02.2 阶段1）。
 *
 * <p>绑定是"应用 -> READY release 上已批准语义扩展 -> 技能精确版本"的引用声明；
 * 修订只追加，停用保留历史。运行时生效仍要等阶段2把它组合进 ConfigRelease，
 * 因此本目录的 ACTIVE 不等于已对外可用。
 */
@RestController
@RequestMapping("/admin/semantic-bindings")
public class SemanticBindingsController {

    private final SemanticBindings bindings;

    public SemanticBindingsController(SemanticBindings bindings) {
        this.bindings = bindings;
    }

    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list(@RequestParam String appCode) {
        admin();
        return ApiResponse.ok(bindings.list(appCode));
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

    @PostMapping("/{bindingId}/revisions")
    public Object revise(@PathVariable String bindingId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.revise(bindingId, body));
    }

    @PostMapping("/{bindingId}/disable")
    public Object disable(@PathVariable String bindingId, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(bindings.disable(bindingId, body));
    }
}
