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

    /** 固定候选的显式顺序轮次；会话归属和EVALUATION限制由同一服务端边界复核。 */
    @PostMapping("/internal/evaluations/turn")
    public Object evaluationTurn(@RequestBody JsonNode request) {
        return ApiResponse.ok(sessions.evaluationTurn(request,Actor.current()));
    }

    @PostMapping({"/v1/apps/{code}/sessions", "/admin/apps/{code}/sessions"})
    public Object create(
            @PathVariable String code, @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(sessions.create(code, body, Actor.current()));
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

    /** S2-1（接口稿 §5.1）：本人会话列表——mine=true 首期固定，跨用户查询不开放。 */
    @GetMapping("/v1/apps/{code}/sessions")
    public Object list(
            @PathVariable String code,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String before) {
        return ApiResponse.ok(sessions.list(code, mine, limit, before, Actor.current()));
    }
}
