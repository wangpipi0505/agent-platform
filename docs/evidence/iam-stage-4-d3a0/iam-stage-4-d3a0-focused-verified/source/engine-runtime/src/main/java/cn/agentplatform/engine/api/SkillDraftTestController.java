package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunService;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
public class SkillDraftTestController {
    private final RunService service;

    public SkillDraftTestController(RunService service) {
        this.service = service;
    }

    @PostMapping("/internal/skill-draft-tests")
    public Object create(@RequestBody JsonNode request) {
        return ApiResponse.ok(service.createDraftTest(request, Actor.current()));
    }
}
