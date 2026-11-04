package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.engine.application.GoalVerificationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * SEM-08：真实 PG 的目标读回核验。只读既有 {@code engine.tool_call} 事实，成功回执但业务未落库不得标完成，
 * 重复核验不新增调用/不改写运行终态。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class GoalVerificationServiceTest {

    static JdbcTemplate db;
    static TraceStore store;
    static GoalOutcomeStore outcomes;
    static GoalVerificationService service;
    static String tenant;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(), EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        outcomes = new GoalOutcomeStore(db);
        service = new GoalVerificationService(store, outcomes);
        tenant = "sem08-" + UUID.randomUUID();
    }

    @BeforeEach
    void clean() {
        db.update("delete from engine.goal_outcome");
    }

    @Test
    void achievedOnlyWhenReadbackMatches() {
        long run = run("SUCCEEDED", List.of(goal("g-ok", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        long call = call(run, "verify", "SUCCEEDED", Map.of("id", "order-B", "rows", List.of(Map.of("status", "CONFIRMED"))), null);

        Map<String, Object> summary = service.verify(run);
        List<Map<String, Object>> goals = goals(summary);
        assertThat(goals).hasSize(1);
        assertThat(goals.getFirst().get("outcomeState")).isEqualTo("ACHIEVED");
        assertReadback(goals.getFirst().get("readbackRef"), call);
        assertThat(summary.get("complete")).isEqualTo(true);
    }

    @Test
    void acceptedButNotPersistedIsNotAchieved() {
        long run = run("SUCCEEDED", List.of(goal("g-empty", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        call(run, "verify", "SUCCEEDED", Map.of("id", "order-B", "rows", List.of()), null);

        List<Map<String, Object>> goals = goals(service.verify(run));
        assertThat(goals.getFirst().get("outcomeState")).isEqualTo("NOT_ACHIEVED");
        assertThat(goals.getFirst().get("readbackRef")).isNull();
        @SuppressWarnings("unchecked")
        var detail = (Map<String, Object>) goals.getFirst().get("detail");
        assertThat(detail.get("reason")).isEqualTo("VALUE_MISSING");
    }

    @Test
    void rejectedCallIsNotMarkedUnknown() {
        long rejected = run("SUCCEEDED", List.of(goal("g-denied", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        call(rejected, "verify", "FAILED", null, "NO_PERMISSION");
        assertThat(goals(service.verify(rejected)).getFirst().get("outcomeState")).isEqualTo("NOT_ACHIEVED");

        long pendingRun = run("RUNNING", List.of(goal("g-pending", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        call(pendingRun, "verify", "REGISTERED", null, null);
        assertThat(goals(service.verify(pendingRun)).getFirst().get("outcomeState")).isEqualTo("PENDING");

        long rejectedPending = run("RUNNING", List.of(goal("g-denied-pending", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        call(rejectedPending, "verify", "FAILED", null, "NO_PERMISSION");
        assertThat(goals(service.verify(rejectedPending)).getFirst().get("outcomeState")).isEqualTo("NOT_ACHIEVED");
    }

    @Test
    void cancelledRunWithoutReadbackIsNotAchieved() {
        long run = run("CANCELLED", List.of(goal("g-cancelled", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        List<Map<String, Object>> goals = goals(service.verify(run));
        assertThat(goals.getFirst().get("outcomeState")).isEqualTo("NOT_ACHIEVED");
        @SuppressWarnings("unchecked")
        var detail = (Map<String, Object>) goals.getFirst().get("detail");
        assertThat(detail.get("reason")).isEqualTo("READBACK_NOT_STARTED");
    }

    @Test
    void unapprovedReadbackOperationIsUnknown() {
        long run = run("SUCCEEDED", List.of(goal("g-noop", "not-approved", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        List<Map<String, Object>> goals = goals(service.verify(run));
        assertThat(goals.getFirst().get("outcomeState")).isEqualTo("UNKNOWN");
        @SuppressWarnings("unchecked")
        var detail = (Map<String, Object>) goals.getFirst().get("detail");
        assertThat(detail.get("reason")).isEqualTo("READBACK_NOT_APPROVED");
    }

    @Test
    void mixedOutcomesCountsStayConsistent() {
        long run = run(
                "SUCCEEDED",
                List.of(
                        goal("g1", "verify1", "rows[0].status", "CONFIRMED"),
                        goal("g2", "verify2", "rows[0].status", "CONFIRMED"),
                        goal("g3", "verify3", "rows[0].status", "CONFIRMED")),
                List.of(tool("verify1"), tool("verify2"), tool("verify3")));
        call(run, "verify1", "SUCCEEDED", Map.of("id", "order-B", "rows", List.of(Map.of("status", "CONFIRMED"))), null);
        call(run, "verify2", "SUCCEEDED", Map.of("id", "order-B", "rows", List.of(Map.of("status", "OTHER"))), null);
        call(run, "verify3", "FAILED", null, "CONNECTION_RESET");

        Map<String, Object> summary = service.verify(run);
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) summary.get("counts");
        assertThat(counts.get("ACHIEVED")).isEqualTo(1);
        assertThat(counts.get("NOT_ACHIEVED")).isEqualTo(1);
        assertThat(counts.get("FAILED")).isEqualTo(1);
        assertThat(summary.get("complete")).isEqualTo(false);
        assertThat(goals(summary)).hasSize(3);
    }

    @Test
    void repeatVerificationIsIdempotentAndCreatesNoCall() {
        long run = run("SUCCEEDED", List.of(goal("g-once", "verify", "rows[0].status", "CONFIRMED")), List.of(tool("verify")));
        long call = call(run, "verify", "SUCCEEDED", Map.of("id", "order-B", "rows", List.of(Map.of("status", "CONFIRMED"))), null);

        service.verify(run);
        service.verify(run);

        assertThat(db.queryForObject("select count(*) from engine.goal_outcome where run_id=?", Integer.class, run)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from engine.tool_call where run_id=?", Integer.class, run)).isEqualTo(1);
        assertThat(db.queryForObject("select revision from engine.goal_outcome where run_id=?", Integer.class, run)).isEqualTo(1);
        assertThat(db.queryForObject("select status from engine.run where id=?", String.class, run)).isEqualTo("SUCCEEDED");
        assertReadback(outcomes.find(run, "g-once").get("readbackRef"), call);
    }

    private static void assertReadback(Object ref, long callId) {
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) ref;
        assertThat(map.get("evidenceKind")).isEqualTo("call");
        assertThat(((Number) map.get("callId")).longValue()).isEqualTo(callId);
    }

    // ---- fixtures -----------------------------------------------------------

    private static List<Map<String, Object>> goals(Map<String, Object> summary) {
        @SuppressWarnings("unchecked")
        var goals = (List<Map<String, Object>>) summary.get("goals");
        return goals;
    }

    private static long run(String status, List<Map<String, Object>> goals, List<Map<String, Object>> tools) {
        String snapshot = Json.write(Map.of("config", Map.of("goals", goals, "tools", tools)));
        String publicId = "r_" + UUID.randomUUID().toString().replace("-", "");
        return db.queryForObject(
                "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,"
                        + "input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                        + " values(?,'sem08','{}','DEBUG',?,'alice',?,'in','{}','{}','{}',?::jsonb,'{}','FULL') returning id",
                Long.class, publicId, status, tenant, snapshot);
    }

    private static long call(long run, String skillCode, String status, Map<String, Object> result, String errorSemantic) {
        long seq = db.queryForObject("select coalesce(max(call_seq),0)+1 from engine.tool_call where run_id=?", Integer.class, run);
        long node = db.queryForObject(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                        + " values(?,?,?,'CODE','SUCCEEDED','{\"type\":\"CODE\"}'::jsonb) returning id",
                Long.class, run, "n" + seq, seq);
        long attempt = db.queryForObject(
                "insert into engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot)"
                        + " values(?,1,'SUCCEEDED',?, 'ref','{}'::jsonb) returning id",
                Long.class, node, Digest.sha256(UUID.randomUUID().toString()));
        return db.queryForObject(
                "insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,"
                        + "status,skill_code,args,result,attempt_id,error_semantic,skill_contract_sha256)"
                        + " values(?,?,?,1,?,?,?,?, '{\"id\":\"order-B\"}'::jsonb, ?::jsonb, ?, ?, ?) returning id",
                Long.class,
                run,
                node,
                seq,
                Digest.sha256(Json.canonical(Map.of("id", "order-B"))),
                Digest.sha256(UUID.randomUUID().toString()),
                status,
                skillCode,
                result == null ? null : Json.write(result),
                attempt,
                errorSemantic,
                Digest.sha256("sem08-" + skillCode));
    }

    private static Map<String, Object> goal(String id, String skillCode, String path, Object expected) {
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("skillCode", skillCode);
        readback.put("arguments", Map.of("id", "order-B"));
        readback.put("objectIdPath", "$.id");
        readback.put("resultObjectIdPath", "$.id");
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("path", "$." + path);
        predicate.put("operator", "equals");
        predicate.put("value", expected);
        Map<String, Object> criterion = new LinkedHashMap<>();
        criterion.put("target", Map.of("objectType", "Order", "objectId", "order-B"));
        criterion.put("readback", readback);
        criterion.put("predicate", predicate);
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("goalId", id);
        goal.put("criterion", criterion);
        return goal;
    }

    private static Map<String, Object> tool(String code) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("code", code);
        tool.put("skillVersionId", 1);
        tool.put("contractDigest", Digest.sha256("sem08-" + code));
        tool.put("execution", Map.of("readonly", true, "sideEffect", false));
        return tool;
    }
}
