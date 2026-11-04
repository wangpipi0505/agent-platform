package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.goal.GoalContract;
import cn.agentplatform.engine.application.GoalVerificationService;
import cn.agentplatform.engine.domain.GoalOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 逻辑检查替身：只验证SQL约束/冻结归属/动作锚；不作为PG落库或真实执行证明。 */
class GoalVerificationBindingTest {
    private static final String DIGEST = "a".repeat(64);
    private static final JsonNode TOOLS = Json.tree(List.of(tool("verify"), tool("update")));

    @Test void twoObjectsUsingSameSkillCannotShareReceipts() {
        Fixture f = new Fixture(false);
        f.readback = call(41L, "verify", "order-A", 1L);
        assertThat(f.state()).isNotEqualTo("ACHIEVED");
        assertThat(f.readbackSql).contains("skill_version_id=?", "skill_contract_sha256=?", "args_digest=?", "args=?::jsonb");
        assertThat(f.readbackParameters).contains(1L, "verify", DIGEST, GoalContract.digest(Json.tree(Map.of("id", "order-B"))));
        f.readback = call(42L, "verify", "order-B", 1L);
        assertThat(f.state()).isEqualTo("ACHIEVED");
    }

    @Test void wrongResponseObjectOldVersionAndUnknownCannotAchieve() {
        Fixture f = new Fixture(false);
        f.readback.put("result_json", "{\"id\":\"order-A\",\"status\":\"CONFIRMED\"}");
        assertThat(f.state()).isEqualTo("UNKNOWN");
        f.readback = call(42L, "verify", "order-B", 2L);
        assertThat(f.state()).isNotEqualTo("ACHIEVED");
        f.readback = call(42L, "verify", "order-B", 1L);
        f.readback.put("status", "UNKNOWN");
        assertThat(f.state()).isEqualTo("UNKNOWN");
        f.readback.put("status", "SUCCEEDED");
        f.readback.put("is_mock", true);
        assertThat(f.state()).isEqualTo("UNKNOWN");
    }

    @Test void actionBeforeReadbackRequiresFrozenAcceptedFactAnchor() {
        Fixture f = new Fixture(true);
        assertThat(f.state()).isEqualTo("UNKNOWN");
        f.anchor = Json.write(Map.of("goalId", "g", "criterionDigest", GoalContract.digest(f.goal.path("criterion")),
                "callId", 42L, "actionCallId", 40L, "actionFactId", 100L));
        assertThat(f.state()).isEqualTo("ACHIEVED");
        f.action = call(43L, "update", "order-B", 1L);
        assertThat(f.state()).isEqualTo("UNKNOWN");
        f.action = call(40L, "update", "order-A", 1L);
        assertThat(f.state()).isEqualTo("UNKNOWN");
    }

    @Test void registrationDoesNotRetroactivelyFillPreActionReadback() {
        Fixture f = new Fixture(true);
        f.action.put("status", "UNKNOWN");
        GoalReadbackAnchor.capture(f.db, f.run, f.readback);
        assertThat(f.savedAnchor).doesNotContain("actionCallId");
        f.action.put("status", "SUCCEEDED");
        GoalReadbackAnchor.capture(f.db, f.run, f.readback);
        assertThat(f.anchorWrites).isEqualTo(1);
        assertThat(f.savedAnchor).doesNotContain("actionCallId");
    }

    @Test void repeatVerificationAddsNoToolOrBusinessWrite() {
        Fixture f = new Fixture(false);
        assertThat(f.state()).isEqualTo("ACHIEVED");
        assertThat(f.state()).isEqualTo("ACHIEVED");
        assertThat(f.writes).isEmpty();
    }

    @Test void missingFrozenBindingIsUnknownAndNewAdmissionRejectsIt() {
        Fixture f = new Fixture(false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) f.goal.path("criterion")).remove("target");
        f.refreshRun();
        assertThat(f.state()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> GoalContract.requireBindings(Json.tree(Map.of("goals", List.of(f.goal)))))
                .hasMessageContaining("冻结");
    }

    private static Map<String, Object> tool(String code) {
        return Map.of("code", code, "skillVersionId", 1L, "contractDigest", DIGEST,
                "execution", Map.of("readonly", true, "sideEffect", false));
    }

    private static JsonNode operation(String code) {
        return Json.tree(Map.of("skillCode", code, "arguments", Map.of("id", "order-B"), "objectIdPath", "$.id"));
    }

    private static Map<String, Object> call(long id, String code, String objectId, long version) {
        var call = new LinkedHashMap<String, Object>();
        call.put("id", id); call.put("node_instance_id", id + 10); call.put("attempt_id", 500L);
        call.put("status", "SUCCEEDED"); call.put("skill_code", code); call.put("skill_version_id", version);
        call.put("skill_contract_sha256", DIGEST); call.put("is_mock", false);
        call.put("args_json", Json.write(Map.of("id", objectId)));
        call.put("args_digest", GoalContract.digest(Json.tree(Map.of("id", objectId))));
        call.put("result_json", Json.write(Map.of("id", objectId, "status", "CONFIRMED")));
        return call;
    }

    private static class Fixture {
        JsonNode goal;
        Map<String, Object> run;
        Map<String, Object> readback = call(42L, "verify", "order-B", 1L);
        Map<String, Object> action = call(40L, "update", "order-B", 1L);
        String anchor, savedAnchor, readbackSql;
        List<Object> readbackParameters;
        int anchorWrites;
        List<String> writes = new ArrayList<>();
        JdbcTemplate db = new JdbcTemplate() {
            @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.contains("from engine.run")) return List.of(run);
                if (sql.contains("from engine.tool_call")) {
                    if ("verify".equals(args[1])) { readbackSql = sql; readbackParameters = List.of(args); return List.of(readback); }
                    return List.of(action);
                }
                if (sql.contains("anchor_json")) return anchor == null ? List.of() : List.of(Map.of("anchor_json", anchor));
                if (sql.contains("payload->>'goalId'")) return anchorWrites > 0 ? List.of(Map.of("id", 1L)) : List.of();
                if (sql.contains("TOOL_EXECUTION_FACT")) return List.of(Map.of("id", 100L));
                throw new AssertionError(sql);
            }
            @Override public int update(String sql, Object... args) {
                writes.add(sql);
                if (sql.contains("attempt_note")) { savedAnchor = args[3].toString(); anchorWrites++; }
                return 1;
            }
        };
        GoalVerificationService service;
        Fixture(boolean withAction) {
            var criterion = Json.object();
            criterion.set("target", Json.tree(Map.of("objectType", "Order", "objectId", "order-B")));
            var readback = (com.fasterxml.jackson.databind.node.ObjectNode) operation("verify");
            readback.put("resultObjectIdPath", "$.id"); criterion.set("readback", readback);
            criterion.set("predicate", Json.tree(Map.of("path", "status", "equals", "CONFIRMED")));
            if (withAction) criterion.set("action", operation("update"));
            goal = Json.tree(Map.of("goalId", "g", "criterion", criterion)); refreshRun();
            var store = mock(TraceStore.class); when(store.db()).thenReturn(db);
            var outcomes = mock(GoalOutcomeStore.class);
            when(outcomes.upsert(anyLong(), any(GoalOutcome.class))).thenAnswer(i -> ((GoalOutcome)i.getArgument(1)).toMap());
            service = new GoalVerificationService(store, outcomes);
        }
        void refreshRun() { run = Map.of("id", 1L, "status", "SUCCEEDED", "snapshot",
                Json.write(Map.of("config", Map.of("tools", TOOLS, "goals", List.of(goal))))); }
        @SuppressWarnings("unchecked") String state() {
            return ((List<Map<String,Object>>)service.verify(1L).get("goals")).getFirst().get("outcomeState").toString();
        }
    }
}
