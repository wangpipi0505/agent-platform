package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Prior turn facts are reconstructed by owner and sequence; input JSON cannot supply this history.
 */
public final class ChatHistory {
    private ChatHistory() {}

    /** 新轮受理前核对旧回答的权限快照，权限收窄时不能把旧宽数据重新交给模型。 */
    public static void checkScope(TraceStore store, String sessionId, String code, Actor actor) {
        var rows =
                store.db()
                        .queryForList(
                                "select r.guard_ctx from engine.chat_turn t join"
                                    + " engine.chat_session s on s.public_id=t.session_id join"
                                    + " engine.run r on r.public_id=t.run_public_id where"
                                    + " s.public_id=? and s.tenant_id=? and s.principal_id=? and"
                                    + " s.app_code=?",
                                sessionId,
                                actor.tenantId(),
                                actor.principalId(),
                                code);
        var current = Json.tree(Map.of("roles", actor.roles(), "attributes", actor.attributes()));
        for (var row : rows) requireScope(TraceStore.json(row.get("guard_ctx")), current);
    }

    private static void requireScope(JsonNode source, JsonNode current) {
        if (!source.path("roles").isArray()
                || !source.path("attributes").isObject()
                || !current.path("roles").isArray()
                || !current.path("attributes").isObject()) throw changed();
        Set<String> roles = new HashSet<>();
        for (JsonNode role : current.path("roles")) roles.add(role.asText());
        for (JsonNode role : source.path("roles"))
            if (!role.isTextual() || !roles.contains(role.asText())) throw changed();
        var fields = source.path("attributes").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isArray()) throw changed();
            Set<String> allowed = new HashSet<>();
            for (JsonNode value : current.path("attributes").path(field.getKey()))
                allowed.add(value.asText());
            for (JsonNode value : field.getValue())
                if (!value.isTextual() || !allowed.contains(value.asText())) throw changed();
        }
    }

    private static PlatformException changed() {
        return new PlatformException(403, "CHAT_HISTORY_SCOPE_CHANGED", "会话授权范围已改变，请新建会话后继续");
    }

    public static List<Map<String, Object>> load(TraceStore store, Map<String, Object> run) {
        var session = TraceStore.json(run.get("snapshot")).path("chatSession");
        if (!session.has("sessionId")) return List.of();
        var rows =
                store.db()
                        .queryForList(
                                "select t.inputs,r.status,r.output,r.error,r.guard_ctx from"
                                    + " engine.chat_turn t join engine.chat_session s on"
                                    + " s.public_id=t.session_id join engine.run r on"
                                    + " r.public_id=t.run_public_id where s.public_id=? and"
                                    + " s.tenant_id=? and s.principal_id=? and s.app_code=? and"
                                    + " t.turn_no<? order by t.turn_no",
                                session.path("sessionId").asText(),
                                run.get("tenant_id"),
                                run.get("principal_id"),
                                run.get("app_code"),
                                session.path("turnNo").asInt());
        List<Map<String, Object>> history = new ArrayList<>();
        for (var row : rows) {
            // 执行/恢复入口再次校验，不能只依赖前端清空会话或创建前的一次检查。
            requireScope(
                    TraceStore.json(row.get("guard_ctx")), TraceStore.json(run.get("guard_ctx")));
            var input = TraceStore.json(row.get("inputs"));
            history.add(
                    Map.of(
                            "role",
                            "user",
                            "content",
                            input.has("message")
                                    ? input.path("message").asText()
                                    : Json.write(input)));
            var output = TraceStore.json(row.get("output"));
            String text =
                    row.get("status").equals("SUCCEEDED")
                            ? (output.has("text")
                                    ? output.path("text").asText()
                                    : Json.write(output))
                            : "[Previous run "
                                    + row.get("status")
                                    + "] "
                                    + Json.write(TraceStore.json(row.get("error")));
            history.add(Map.of("role", "assistant", "content", text));
        }
        return history;
    }
}
