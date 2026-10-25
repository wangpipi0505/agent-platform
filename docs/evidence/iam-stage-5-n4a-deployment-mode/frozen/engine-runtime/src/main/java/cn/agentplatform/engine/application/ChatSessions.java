package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.InputDeclarations;
import cn.agentplatform.engine.domain.RunAdmissionRejected;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatSessions {
    private final TraceStore store;
    private final RuntimeClients clients;
    private final RunService runs;

    public ChatSessions(TraceStore store, RuntimeClients clients, RunService runs) {
        this.store = store;
        this.clients = clients;
        this.runs = runs;
    }

    public Map<String, Object> create(String code, Actor actor) {
        JsonNode snapshot = clients.get("app", "/internal/apps/" + code + "/published", actor);
        if (!snapshot.path("type").asText().equals("CHAT"))
            throw new PlatformException(409, "CHAT_REQUIRED", "只有已发布对话应用可创建会话");
        String id = "s_" + UUID.randomUUID().toString().replace("-", "");
        store.db()
                .update(
                        "insert into"
                            + " engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                            + " values(?,?,?,?,?::jsonb)",
                        id,
                        actor.tenantId(),
                        actor.principalId(),
                        code,
                        Json.write(snapshot));
        return get(code, id, actor);
    }

    public Map<String, Object> get(String code, String id, Actor actor) {
        var session = owned(code, id, actor, false);
        return Map.of(
                "sessionId",
                id,
                "appCode",
                code,
                "versionNo",
                TraceStore.json(session.get("snapshot")).path("versionNo"),
                "nextTurn",
                session.get("next_turn"),
                "turns",
                store.db()
                        .queryForList(
                                "select turn_no,request_id,run_public_id,created_at from"
                                        + " engine.chat_turn where session_id=? order by turn_no",
                                id));
    }

    public Map<String, Object> turn(String code, String id, JsonNode req, Actor actor) {
        String requestId = req.path("requestId").asText();
        if (requestId.isBlank() || requestId.length() > 48 || !req.path("inputs").isObject())
            throw new PlatformException(400, "TURN_REQUEST_INVALID", "会话轮次必须提供requestId与inputs");
        var pinnedSession = owned(code, id, actor, false);
        boolean known =
                !store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty();
        // Keep the published version pinned, but never let an old session bypass current app
        // disablement.
        // Existing reservations are accepted work and must remain idempotently recoverable.
        if (!known) {
            ChatHistory.checkScope(store, id, code, actor);
            clients.get("app", "/internal/apps/" + code + "/published", actor);
        }
        RunScheduler.Admission ticket = null;
        if (!known) {
            InputDeclarations.validate(
                    TraceStore.json(pinnedSession.get("snapshot")), req.path("inputs"));
            try {
                ticket = runs.reserveSessionTurn();
            } catch (RunAdmissionRejected rejected) {
                // Another request may have accepted this exact turn since the first lookup.
                // A durable reservation is accepted work; its normal locked branch below
                // verifies input equality and can reconstruct a missing run without re-admission.
                if (store.db()
                        .queryForList(
                                "select turn_no from engine.chat_turn where session_id=? and"
                                        + " request_id=?",
                                id,
                                requestId)
                        .isEmpty()) throw rejected;
            }
        }
        try {
            java.util.function.Supplier<Map<String, Object>> reserve =
                    () ->
                            store.atomic(
                                    () -> {
                                        var session = owned(code, id, actor, true);
                                        var existing =
                                                store.db()
                                                        .queryForList(
                                                                "select * from engine.chat_turn"
                                                                        + " where session_id=? and"
                                                                        + " request_id=?",
                                                                id,
                                                                requestId);
                                        if (!existing.isEmpty()) {
                                            if (!TraceStore.json(existing.getFirst().get("inputs"))
                                                    .equals(req.path("inputs")))
                                                throw new PlatformException(
                                                        409, "REQUEST_CONFLICT", "轮次请求号已绑定其他输入");
                                            return existing.getFirst();
                                        }
                                        var pending =
                                                store.db()
                                                        .queryForList(
                                                                "select t.run_public_id,r.status"
                                                                    + " from engine.chat_turn t"
                                                                    + " left join engine.run r on"
                                                                    + " r.public_id=t.run_public_id"
                                                                    + " where t.session_id=? order"
                                                                    + " by t.turn_no desc limit 1",
                                                                id);
                                        if (!pending.isEmpty()
                                                && (pending.getFirst().get("status") == null
                                                        || !Set.of(
                                                                        "SUCCEEDED",
                                                                        "FAILED",
                                                                        "CANCELLED")
                                                                .contains(
                                                                        pending.getFirst()
                                                                                .get("status"))))
                                            throw new PlatformException(
                                                    409, "CHAT_TURN_ACTIVE", "上一轮仍在执行或恢复");
                                        // Validate locally under the session lock.
                                        // Accepted requestIds retain their original inputs.
                                        // Schema loading cannot perform HTTP here.
                                        InputDeclarations.validate(
                                                TraceStore.json(session.get("snapshot")),
                                                req.path("inputs"));
                                        int turn = ((Number) session.get("next_turn")).intValue();
                                        store.db()
                                                .update(
                                                        "insert into"
                                                            + " engine.chat_turn(session_id,turn_no,request_id,inputs)"
                                                            + " values(?,?,?,?::jsonb)",
                                                        id,
                                                        turn,
                                                        requestId,
                                                        Json.write(req.path("inputs")));
                                        store.db()
                                                .update(
                                                        "update engine.chat_session set"
                                                                + " next_turn=next_turn+1 where"
                                                                + " public_id=?",
                                                        id);
                                        return store.db()
                                                .queryForMap(
                                                        "select * from engine.chat_turn where"
                                                                + " session_id=? and turn_no=?",
                                                        id,
                                                        turn);
                                    });
            // The first durable reservation is the admission point, before consuming next_turn.
            // Subsequent run preparation may perform HTTP outside this short transaction. Once
            // accepted, drain cannot reject the same ticket's completion and orphan its turn.
            var reservation = ticket == null ? reserve.get() : ticket.persist(reserve);
            if (reservation.get("run_public_id") != null)
                return runs.status(reservation.get("run_public_id").toString(), actor);
            var session = owned(code, id, actor, false);
            var source =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            TraceStore.json(session.get("snapshot")).deepCopy();
            source.set(
                    "chatSession",
                    Json.tree(Map.of("sessionId", id, "turnNo", reservation.get("turn_no"))));
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) req.deepCopy();
            input.remove("sessionId");
            input.put("requestId", "session:" + id + ":" + requestId);
            var result = runs.createReservedSessionTurn(code, input, source, actor, ticket);
            store.db()
                    .update(
                            "update engine.chat_turn set run_public_id=? where session_id=? and"
                                    + " turn_no=? and run_public_id is null",
                            result.get("runId"),
                            id,
                            reservation.get("turn_no"));
            return result;
        } finally {
            if (ticket != null) ticket.close();
        }
    }

    private Map<String, Object> owned(String code, String id, Actor actor, boolean lock) {
        var rows =
                store.db()
                        .queryForList(
                                "select * from engine.chat_session where public_id=? and app_code=?"
                                        + " and tenant_id=? and principal_id=?"
                                        + (lock ? " for update" : ""),
                                id,
                                code,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "SESSION_NOT_FOUND", "会话不存在");
        return rows.getFirst();
    }
}
