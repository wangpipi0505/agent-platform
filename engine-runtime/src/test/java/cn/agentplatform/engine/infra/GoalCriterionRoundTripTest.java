package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.goal.GoalContract;
import cn.agentplatform.engine.application.GoalVerificationService;
import cn.agentplatform.engine.domain.GoalOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 表单产物原样进入真实目标核验器；JDBC替身只提供固定调用事实，不证明真实PG持久化。 */
class GoalCriterionRoundTripTest {
    @Test void actualFormOutputExecutesAllOperatorsAndPreservesJsonTypes() throws Exception {
        Path script = Path.of(System.getProperty("user.dir"), "../web/tests/goal-form-roundtrip.cjs").normalize();
        var process = new ProcessBuilder("node", script.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        JsonNode scenarios = Json.read(output);
        assertThat(scenarios.size()).isEqualTo(10);
        com.networknt.schema.JsonSchema summarySchema;
        try (var input = GoalContract.class.getResourceAsStream("/semantic-runtime-goal-v1.schema.json")) {
            summarySchema = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                    .getSchema(Json.read(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        for (JsonNode scenario : scenarios) {
            JsonNode goal = scenario.path("goal");
            assertThat(GoalContract.declarationErrors(goal)).isEmpty();
            GoalContract.requireBindings(Json.tree(Map.of("goals", List.of(goal))));
            var call = new LinkedHashMap<String, Object>();
            call.put("id", 41L); call.put("node_instance_id", 51L); call.put("status", "SUCCEEDED");
            call.put("skill_code", "verify"); call.put("skill_version_id", 1L); call.put("skill_contract_sha256", "a".repeat(64));
            call.put("args_json", "{\"id\":\"order-B\"}");
            call.put("args_digest", GoalContract.digest(goal.path("criterion").path("readback").path("arguments")));
            call.put("is_mock", false); call.put("result_json", scenario.path("result").toString());
            Map<String, Object> run = Map.of("id", 1L, "status", "SUCCEEDED", "snapshot", Json.write(Map.of("config", Map.of(
                    "goals", List.of(goal), "tools", List.of(Map.of("code", "verify", "skillVersionId", 1L,
                            "contractDigest", "a".repeat(64), "execution", Map.of("readonly", true, "sideEffect", false)))))));
            var db = new JdbcTemplate() {
                @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
                    return List.of(sql.contains("from engine.run") ? run : call);
                }
            };
            var store = mock(TraceStore.class); when(store.db()).thenReturn(db);
            var outcomes = mock(GoalOutcomeStore.class);
            when(outcomes.upsert(anyLong(), any())).thenAnswer(i -> ((GoalOutcome)i.getArgument(1)).toMap());
            var summary = new GoalVerificationService(store, outcomes).verify(1L);
            @SuppressWarnings("unchecked") var row = ((List<Map<String,Object>>)summary.get("goals")).getFirst();
            assertThat(row.get("outcomeState")).as(goal.path("criterion").path("predicate").toString())
                    .isEqualTo(scenario.path("achieved").asBoolean() ? "ACHIEVED" : "NOT_ACHIEVED");
            @SuppressWarnings("unchecked") var detail = (Map<String,Object>) row.get("detail");
            assertThat(detail.containsKey("readbackValue")).isEqualTo(scenario.path("present").asBoolean());
            if (scenario.path("present").asBoolean())
                assertThat(Json.tree(detail.get("readbackValue"))).isEqualTo(scenario.path("value"));
            assertThat(summary).containsEntry("profile", "goal").containsEntry("schemaVersion", "1.0");
            assertThat(summarySchema.validate(Json.tree(summary))).isEmpty();
        }
    }

    @Test void invalidPathAndOldDialectCannotBePublished() {
        var goal = Json.read("{\"goalId\":\"g\",\"criterion\":{\"target\":{\"objectType\":\"Order\",\"objectId\":\"B\"},"
                + "\"readback\":{\"skillCode\":\"verify\",\"arguments\":{\"id\":\"B\"},\"objectIdPath\":\"$.id\",\"resultObjectIdPath\":\"$.id\"},"
                + "\"predicate\":{\"path\":\"$.rows[0]oops\",\"operator\":\"exists\"}}}");
        assertThatThrownBy(() -> GoalContract.requireBindings(Json.tree(Map.of("goals", List.of(goal)))))
                .hasMessageContaining("goal@1.0");
        var predicate = (com.fasterxml.jackson.databind.node.ObjectNode) goal.path("criterion").path("predicate");
        predicate.remove("operator"); predicate.put("path", "$.status"); predicate.put("equals", "CONFIRMED");
        assertThat(GoalContract.declarationErrors(goal)).isNotEmpty();
    }
}
