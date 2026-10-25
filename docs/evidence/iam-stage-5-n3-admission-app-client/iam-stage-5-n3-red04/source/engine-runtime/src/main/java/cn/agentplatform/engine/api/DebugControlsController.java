package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/debug-runs/{id}/controls")
public class DebugControlsController {
    private final DebugControls controls;

    public DebugControlsController(DebugControls controls) {
        this.controls = controls;
    }

    @GetMapping
    public Object get(@PathVariable String id) {
        return ApiResponse.ok(controls.get(id, Actor.current()));
    }

    @PutMapping
    public Object update(@PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(controls.update(id, req, Actor.current()));
    }
}
