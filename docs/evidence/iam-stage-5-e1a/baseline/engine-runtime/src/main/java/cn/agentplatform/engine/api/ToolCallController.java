package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.ToolCalls;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/tool-calls")
public class ToolCallController {
    private final ToolCalls calls;

    public ToolCallController(ToolCalls c) {
        calls = c;
    }

    @PostMapping("/register")
    public Object register(@RequestBody JsonNode req) {
        return ApiResponse.ok(calls.register(req, Actor.current()));
    }

    @PostMapping("/{id}/claim")
    public Object claim(@PathVariable long id, @RequestBody JsonNode req) {
        return ApiResponse.ok(calls.claim(id, req, Actor.current()));
    }

    @PostMapping("/{id}/submitted")
    public Object submitted(@PathVariable long id, @RequestBody JsonNode req) {
        return ApiResponse.ok(calls.submitted(id, req, Actor.current()));
    }

    @PostMapping("/{id}/fact")
    public Object fact(@PathVariable long id, @RequestBody JsonNode req) {
        return ApiResponse.ok(calls.fact(id, req, Actor.current()));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        return ApiResponse.ok(calls.get(id, Actor.current()));
    }

    @GetMapping("/{id}/approval")
    public Object approval(@PathVariable long id) {
        return ApiResponse.ok(calls.approval(id, Actor.current()));
    }
}
