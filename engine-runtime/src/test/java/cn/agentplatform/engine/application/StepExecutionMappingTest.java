package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.UUID;

/**
 * T2-5 定向测试（台账 §7.3）：五 kind 步骤受理为逻辑 run+dispatch_key 幂等+A08b
 * failure_policy 映射与下游处置。验收（主方案 §5.6）：双分支计划端到端；CONDITION 为假→SKIPPED；
 * A08b 三场景（超时/耗尽/终止）——attempt 序列完整+下游阻断/跳过。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class StepExecutionMappingTest {

    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static StepExecutionMapping mapping;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        tasks = new Tasks(store);
        mapping = new StepExecutionMapping(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("update engine.task set selected_plan_revision_id=null where tenant_id='engine-test'");
        db.update("delete from engine.plan_confirmation where plan_revision_id in(select id from engine.plan_revision where tenant_id='engine-test')");
        db.execute("ALTER TABLE engine.plan_step DISABLE TRIGGER plan_step_immutable_guard");
        db.update("delete from engine.plan_step where plan_revision_id in(select id from engine.plan_revision where tenant_id='engine-test')");
        db.execute("ALTER TABLE engine.plan_step ENABLE TRIGGER plan_step_immutable_guard");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_dispatch where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.plan_revision DISABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.plan_revision where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.plan_revision ENABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.task where tenant_id='engine-test'");
        db.update("delete from engine.session_event where session_id like 'ti-%'");
        db.update("delete from engine.chat_session where public_id like 'ti-%'");
    }

    long agentTask() {
        var t =
                tasks.create(
                        "t5-app",
                        Json.tree(Map.of("requestId", "r-" + UUID.randomUUID(), "mode", "AGENT", "goal", "映射演练")),
                        actor);
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, t.get("taskId"));
    }

    void seedStep(long tid, String stepId, boolean required) {
        db.update("insert into engine.task_step(task_id,step_id,state,run_id) values(?,?,'READY',null)", tid, stepId);
        db.update(
                "insert into engine.task_step(task_id,step_id,state,run_id) values(?,?,'READY',null)",
                tid,
                stepId + "-down");
    }

    void seedPlanStep(long tid, String stepId, boolean required, String dependsOn) {
        Integer existing =
                db.queryForObject(
                        "select count(*) from engine.plan_revision where tenant_id='engine-test' and plan_id=?",
                        Integer.class,
                        "plan-t5-" + tid);
        int rev;
        if (existing != null && existing > 0) {
            rev =
                    db.queryForObject(
                            "select id from engine.plan_revision where tenant_id='engine-test' and plan_id=?",
                            Integer.class,
                            "plan-t5-" + tid);
        } else {
            rev =
                    db.queryForObject(
                            "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,schema_version,goal_spec,validation_status,digest,created_by)"
                                + " values('engine-test',?,1,?,'1.0','{}'::jsonb,'VALID',?,'alice') returning id",
                            Integer.class,
                            "plan-t5-" + tid,
                            tid,
                            Digest.sha256("plan-t5-" + tid));
        }
        db.update(
                "insert into engine.plan_step(plan_revision_id,step_id,depends_on,kind,target_ref,input_bindings,required,failure_policy)"
                    + " values(?,?,?::jsonb,?,?,?::jsonb,?,?::jsonb)",
                rev,
                stepId,
                Json.write(dependsOn == null ? java.util.List.of() : java.util.List.of(dependsOn)),
                "AGENT",
                "agent:default@1",
                "{}",
                required,
                Json.write(Map.of("timeoutMs", 60000, "retry", Map.of("max", 2), "abort", !required)));
        db.update("update engine.task set selected_plan_revision_id=? where id=?", rev, tid);
    }

    @Test
    void admitCreatesLogicalRunWithDispatchKey() {
        long tid = agentTask();
        seedStep(tid, "entry", true);
        var admitted = mapping.admit(tid, "entry", actor);
        assertNotNull(admitted.get("runId"));
        assertNotNull(admitted.get("nodeInstanceId"));
        assertNotNull(admitted.get("dispatchKey"));
        // task_step.run_id 归属（运行归属先持久化再执行）
        Long runId =
                db.queryForObject(
                        "select run_id from engine.task_step where task_id=? and step_id='entry'",
                        Long.class,
                        tid);
        assertEquals(((Number) admitted.get("runId")).longValue(), runId);
        // dispatch_key 幂等：重复 admit 同键
        var again = mapping.admit(tid, "entry", actor);
        assertEquals(admitted.get("dispatchKey"), again.get("dispatchKey"));
    }

    @Test
    void failurePolicyMapsToNodeExecutionConfig() {
        long tid = agentTask();
        seedPlanStep(tid, "s1", true, null);
        var mapped = mapping.mapFailurePolicy(tid, "plan-t5-" + tid, 1, "s1");
        assertEquals(60000L, ((Number) mapped.get("totalDurationMs")).longValue());
        assertEquals(2, ((Number) mapped.get("retryMax")).intValue());
        assertEquals(false, mapped.get("abortOnFailure")); // required=true → abort=false（retry 优先，不终止）
    }

    @Test
    void onFailureRecordsAttemptAndBlocksRequiredDownstream() {
        long tid = agentTask();
        seedStep(tid, "s1", true);
        seedPlanStep(tid, "s1", true, null);
        seedPlanStep(tid, "s1-down", true, "s1");
        db.update("update engine.task_step set run_id=null where task_id=? and step_id='s1-down'", tid);
        var admitted = mapping.admit(tid, "s1", actor);
        long runId = ((Number) admitted.get("runId")).longValue();
        // A08b attempt 序列完整：先 claim 产生 attempt（复用现有节点执行链）再注失败
        long nodeId = ((Number) admitted.get("nodeInstanceId")).longValue();
        var claim = store.claim(nodeId, "w");
        assertNotNull(claim);
        var out = mapping.onFailure(tid, "s1", runId, "SKILL_TIMEOUT", actor);
        // attempt 序列完整（PLAN_STEP_FAILURE 记账）
        assertEquals(true, out.get("attemptRecorded"));
        // 必需下游→阻断
        assertEquals("BLOCKED", out.get("downstreamAction"));
        assertEquals(
                "BLOCKED",
                db.queryForObject(
                        "select state from engine.task_step where task_id=? and step_id='s1-down'",
                        String.class,
                        tid));
        // 事件落库
        int events =
                db.queryForObject(
                        "select count(*) from engine.task_event where task_id=? and type='STEP_FINISHED'",
                        Integer.class,
                        tid);
        assertEquals(1, events);
    }

    @Test
    void optionalDownstreamSkipsOnAbortFailure() {
        long tid = agentTask();
        seedStep(tid, "s1", true);
        seedPlanStep(tid, "s1", true, null);
        seedPlanStep(tid, "s1-down", false, "s1"); // 可选分支
        var admitted = mapping.admit(tid, "s1", actor);
        var out = mapping.onFailure(tid, "s1", ((Number) admitted.get("runId")).longValue(), "ABORT", actor);
        assertEquals("SKIPPED", out.get("downstreamAction"));
        assertEquals(
                "SKIPPED",
                db.queryForObject(
                        "select state from engine.task_step where task_id=? and step_id='s1-down'",
                        String.class,
                        tid));
    }

    @Test
    void conditionFalseMarksStepSkipped() {
        long tid = agentTask();
        seedStep(tid, "cond", true);
        var out = mapping.evaluateCondition(tid, "cond", Json.tree(Map.of("const", false)), actor);
        assertEquals("SKIPPED", out.get("state"));
        assertEquals(
                "SKIPPED",
                db.queryForObject(
                        "select state from engine.task_step where task_id=? and step_id='cond'",
                        String.class,
                        tid));
        // 为真→READY 保持
        var truthy = mapping.evaluateCondition(tid, "cond", Json.tree(Map.of("const", true)), actor);
        assertEquals("READY", truthy.get("state"));
    }
}
