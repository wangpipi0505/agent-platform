package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
public class ReplayController {
    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @GetMapping("/admin/runs/{id}/replay")
    public Object log(@PathVariable String id) {
        return ApiResponse.ok(replay.log(id, Actor.current()));
    }

    @PostMapping("/admin/runs/{id}/reproduce")
    public Object reproduce(@PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(replay.reproduce(id, req, Actor.current()));
    }

    @PostMapping("/admin/runs/{id}/reexecute")
    public Object reexecute(@PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(replay.reexecute(id, req, Actor.current()));
    }

    @GetMapping("/admin/reproductions/{id}")
    public Object get(@PathVariable String id) {
        return ApiResponse.ok(replay.get(id, Actor.current()));
    }
}
