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

    /** H1-9（接口稿 §5.1）：事件增量——afterSeq 排他游标+limit（默认200上限1000）+surfaceOnly 视图门。 */
    @GetMapping("/v1/apps/{code}/sessions/{id}/events")
    public Object events(
            @PathVariable String code,
            @PathVariable String id,
            @RequestParam(required = false) String afterSeq,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String surfaceOnly) {
        return ApiResponse.ok(
                sessions.events(code, id, afterSeq, limit, surfaceOnly, Actor.current()));
    }

    /** H1-9（接口稿 §5.1）：会话控制——CANCEL_TURN/STEER/SET_PLAN_MODE/COMPACT，{requestId,action,payload}。 */
    @PostMapping("/v1/apps/{code}/sessions/{id}/control")
    public Object control(
            @PathVariable String code, @PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(sessions.control(code, id, req, Actor.current()));
    }

    /** H1-9（接口稿 §5.1/§7）：会话事件 SSE——Last-Event-ID 排他续读，15s :ping，断开不取消 turn。 */
    @GetMapping("/v1/apps/{code}/sessions/{id}/events/stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @PathVariable String code,
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "Last-Event-ID", required = false) String lastEventIdParam) {
        return sessions.eventStream(code, id, lastEventId, lastEventIdParam, Actor.current());
    }
}
