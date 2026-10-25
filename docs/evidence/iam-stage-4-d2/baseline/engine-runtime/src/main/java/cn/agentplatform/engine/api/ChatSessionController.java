package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
public class ChatSessionController {
    private final ChatSessions sessions;

    public ChatSessionController(ChatSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping({"/v1/apps/{code}/sessions", "/admin/apps/{code}/sessions"})
    public Object create(@PathVariable String code) {
        return ApiResponse.ok(sessions.create(code, Actor.current()));
    }

    @GetMapping({"/v1/apps/{code}/sessions/{id}", "/admin/apps/{code}/sessions/{id}"})
    public Object get(@PathVariable String code, @PathVariable String id) {
        return ApiResponse.ok(sessions.get(code, id, Actor.current()));
    }

    @PostMapping({"/v1/apps/{code}/sessions/{id}/turns", "/admin/apps/{code}/sessions/{id}/turns"})
    public Object turn(
            @PathVariable String code, @PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(sessions.turn(code, id, req, Actor.current()));
    }
}
