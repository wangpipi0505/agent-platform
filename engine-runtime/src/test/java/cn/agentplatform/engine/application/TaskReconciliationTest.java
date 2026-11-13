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

/**
 * T2-12 定向测试（台账 §7.3）：对账提交端点——授权链+归属链+403 审计+证据复核 409。
 * 验收场景（评审十二轮 2）：同租户 OPERATOR 对未授权工作区提交对账→403 记审计；
 * 已授权工作区同操作→受理；证据不符 409。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TaskReconciliationTest {

    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static TaskReconciliation reconciliation;
    static final Actor owner = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final Actor operator = new Actor("engine-test", "op", Set.of("OPERATOR"), Map.of());

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
        reconciliation = new TaskReconciliation(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
        db.update("delete from engine.task where tenant_id='engine-test'");
    }

    /** 夹具：任务+entry 步骤+run+一个 REGISTERED 调用（未受理）与一个 SUBMITTED 调用（已受理）。 */
    Map<String, Object> fixture() {
        var t =
                tasks.create(
                        "t12-app",
                        Json.tree(Map.of("requestId", "r-" + UUID.randomUUID(), "mode", "AGENT", "goal", "对账演练")),
                        owner);
        long tid =
                db.queryForObject("select id from engine.task where public_id=?", Long.class, t.get("taskId"));
        long runId =
                db.queryForObject(
                        "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'t12-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}','{}','{}','STEP') returning id",
                        Long.class,
                        "r_t12_" + UUID.randomUUID());
        long nid =
                db.queryForObject(
                        "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,'n1',1,'CODE','RUNNING','{\"type\":\"CODE\"}') returning id",
                        Long.class,
                        runId);
        db.update("insert into engine.task_step(task_id,step_id,state,run_id) values(?,?,'RUNNING',?)", tid, "entry", runId);
        var claim = store.claim(nid, "w");
        long attemptId = TraceStore.number(claim, "attemptId");
        long registered =
                db.queryForObject(
                        "insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id)"
                            + " values(?,?,1,42,?,'pg:fixture',?,'REGISTERED','lookup','{}'::jsonb,?) returning id",
                        Long.class,
                        runId,
                        nid,
                        Digest.sha256("t12:" + nid + ":1:42"),
                        Digest.sha256("t12-reg:" + nid),
                        attemptId);
        long submitted =
                db.queryForObject(
                        "insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id)"
                            + " values(?,?,2,42,?,'pg:fixture',?,'SUBMITTED','lookup','{}'::jsonb,?) returning id",
                        Long.class,
                        runId,
                        nid,
                        Digest.sha256("t12:" + nid + ":2:42"),
                        Digest.sha256("t12-sub:" + nid),
                        attemptId);
        return Map.of("taskId", tid, "registered", registered, "submitted", submitted);
    }

    @Test
    void ownerCanRetryRegisteredCall() {
        var f = fixture();
        var out =
                reconciliation.reconcile(
                        (Long) f.get("taskId"),
                        "entry",
                        String.valueOf(f.get("registered")),
                        Json.tree(Map.of("decision", "RETRY")),
                        owner);
        assertEquals("RETRY", out.get("decision"));
    }

    @Test
    void retryOnAcceptedCallConflictsWithEvidence() {
        var f = fixture();
        var conflict =
                assertThrows(
                        PlatformException.class,
                        () ->
                                reconciliation.reconcile(
                                        (Long) f.get("taskId"),
                                        "entry",
                                        String.valueOf(f.get("submitted")),
                                        Json.tree(Map.of("decision", "RETRY")),
                                        owner));
        assertEquals(409, conflict.status());
        assertEquals("RECONCILIATION_EVIDENCE_CONFLICT", conflict.code());
    }

    @Test
    void adoptOnRegisteredCallConflictsButTrackPasses() {
        var f = fixture();
        var adoptConflict =
                assertThrows(
                        PlatformException.class,
                        () ->
                                reconciliation.reconcile(
                                        (Long) f.get("taskId"),
                                        "entry",
                                        String.valueOf(f.get("registered")),
                                        Json.tree(Map.of("decision", "ADOPT_EXTERNAL")),
                                        owner));
        assertEquals("RECONCILIATION_EVIDENCE_CONFLICT", adoptConflict.code());
        // 已受理调用 TRACK 通过
        var tracked =
                reconciliation.reconcile(
                        (Long) f.get("taskId"),
                        "entry",
                        String.valueOf(f.get("submitted")),
                        Json.tree(Map.of("decision", "TRACK_EXTERNAL")),
                        owner);
        assertEquals("TRACK_EXTERNAL", tracked.get("decision"));
    }

    @Test
    void callNotInStepReturns404() {
        var f = fixture();
        long tid = (Long) f.get("taskId");
        // 建一个无 run 归属的第二步骤
        db.update("insert into engine.task_step(task_id,step_id,state) values(?,?,'BLOCKED')", tid, "other");
        var mismatch =
                assertThrows(
                        PlatformException.class,
                        () ->
                                reconciliation.reconcile(
                                        tid,
                                        "other",
                                        String.valueOf(f.get("registered")),
                                        Json.tree(Map.of("decision", "TRACK_EXTERNAL")),
                                        owner));
        assertEquals(404, mismatch.status());
    }

    @Test
    void decisionEnumValidated() {
        var f = fixture();
        assertThrows(
                PlatformException.class,
                () ->
                        reconciliation.reconcile(
                                (Long) f.get("taskId"),
                                "entry",
                                String.valueOf(f.get("registered")),
                                Json.tree(Map.of("decision", "MAGIC")),
                                owner));
    }
}
