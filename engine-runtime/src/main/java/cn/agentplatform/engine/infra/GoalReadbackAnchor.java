package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.goal.GoalContract;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 读回登记的事实前沿。调用方已持run锁，fact也持同一锁，故只锚登记前已提交的动作事实。
 * 复用attempt_note作审计证据，不建立另一份任务状态；同逻辑调用恢复时保留原锚，绝不补填。
 */
public final class GoalReadbackAnchor {
    private GoalReadbackAnchor() {}
    public static final String KIND = "GOAL_READBACK_ANCHOR";

    public static void capture(JdbcTemplate db, Map<String, Object> run, Map<String, Object> call) {
        JsonNode snapshot = TraceStore.json(run.get("snapshot"));
        for (JsonNode goal : snapshot.path("config").path("goals")) {
            JsonNode criterion = goal.path("criterion"), readback = criterion.path("readback");
            if (!GoalContract.bindingErrors(goal).isEmpty() || !criterion.has("action")) continue;
            JsonNode tool = tool(snapshot.path("config").path("tools"), readback.path("skillCode").asText());
            if (!matches(call, readback, tool)) continue;
            if (!db.queryForList("select id from engine.attempt_note where node_instance_id=? and kind=?"
                    + " and payload->>'callId'=? and payload->>'goalId'=?", call.get("node_instance_id"), KIND,
                    call.get("id").toString(), goal.path("goalId").asText()).isEmpty()) continue;
            JsonNode action = criterion.path("action");
            var actionCall = latest(db, ((Number)run.get("id")).longValue(), action,
                    tool(snapshot.path("config").path("tools"), action.path("skillCode").asText()));
            Map<String, Object> anchor = new LinkedHashMap<>();
            anchor.put("goalId", goal.path("goalId").asText());
            anchor.put("criterionDigest", GoalContract.digest(criterion));
            anchor.put("callId", call.get("id"));
            if (actionCall != null && "SUCCEEDED".equals(actionCall.get("status"))
                    && Boolean.FALSE.equals(actionCall.get("is_mock"))) {
                var facts = db.queryForList("select id from engine.attempt_note where node_instance_id=?"
                        + " and kind='TOOL_EXECUTION_FACT' and payload->>'callId'=?"
                        + " and payload->>'accepted'='true' and payload->>'status'='SUCCEEDED' order by id desc limit 1",
                        actionCall.get("node_instance_id"), actionCall.get("id").toString());
                if (!facts.isEmpty()) {
                    anchor.put("actionCallId", actionCall.get("id"));
                    anchor.put("actionFactId", facts.getFirst().get("id"));
                }
            }
            db.update("insert into engine.attempt_note(node_instance_id,attempt_id,kind,payload) values(?,?,?,?::jsonb)",
                    call.get("node_instance_id"), call.get("attempt_id"), KIND, Json.write(anchor));
        }
    }

    public static JsonNode tool(JsonNode tools, String code) {
        if (tools.isArray()) for (JsonNode tool : tools) if (code.equals(tool.path("code").asText())) return tool;
        return null;
    }

    public static Map<String, Object> latest(JdbcTemplate db, long runId, JsonNode operation, JsonNode tool) {
        if (!frozenTool(tool)) return null;
        var rows = db.queryForList("select id,node_instance_id,status,error_semantic,result::text as result_json,"
                + "args::text as args_json,args_digest,skill_code,skill_version_id,skill_contract_sha256,is_mock"
                + " from engine.tool_call where run_id=? and skill_code=? and skill_version_id=?"
                + " and skill_contract_sha256=? and args_digest=? and args=?::jsonb order by id desc limit 1",
                runId, operation.path("skillCode").asText(), tool.path("skillVersionId").asLong(),
                tool.path("contractDigest").asText(), GoalContract.digest(operation.path("arguments")),
                Json.write(operation.path("arguments")));
        // SQL与事实双核对：不会因测试替身/驱动返回了不满足where的行而采信错对象或旧版本。
        return rows.isEmpty() || !matches(rows.getFirst(), operation, tool) ? null : rows.getFirst();
    }

    public static boolean matches(Map<String, Object> call, JsonNode operation, JsonNode tool) {
        if (!frozenTool(tool)) return false;
        JsonNode arguments = call.containsKey("args_json") ? Json.read(String.valueOf(call.get("args_json")))
                : TraceStore.json(call.get("args"));
        return operation.path("skillCode").asText().equals(call.get("skill_code"))
                && call.get("skill_version_id") instanceof Number version && version.longValue() == tool.path("skillVersionId").asLong()
                && tool.path("contractDigest").asText().equals(call.get("skill_contract_sha256"))
                && GoalContract.digest(operation.path("arguments")).equals(call.get("args_digest"))
                && Json.canonical(arguments).equals(Json.canonical(operation.path("arguments")));
    }

    public static boolean frozenTool(JsonNode tool) {
        return tool != null && tool.path("skillVersionId").isIntegralNumber() && tool.path("skillVersionId").asLong() > 0
                && tool.path("contractDigest").asText().matches("[a-f0-9]{64}");
    }
}
