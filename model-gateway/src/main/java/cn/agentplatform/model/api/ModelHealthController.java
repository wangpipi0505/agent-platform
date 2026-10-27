package cn.agentplatform.model.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.model.application.ModelHealthService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AP-S02: operator-facing model depth health; separate from the shallow process check. */
@RestController
public class ModelHealthController {
    private final ModelHealthService health;

    public ModelHealthController(ModelHealthService health) {
        this.health = health;
    }

    @GetMapping("/admin/model-health")
    public Object summary(
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(health.summary(Actor.current(), refresh, force));
    }

    @GetMapping("/admin/model-health/{entryId}")
    public Object detail(@PathVariable long entryId) {
        return ApiResponse.ok(health.detail(Actor.current(), entryId));
    }

    @PostMapping("/admin/model-health/{entryId}/probe")
    public Object probe(@PathVariable long entryId) {
        return ApiResponse.ok(health.probe(Actor.current(), entryId, true));
    }
}
