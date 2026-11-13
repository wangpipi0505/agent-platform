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
 * T2-2 定向测试（台账 §7.3）：任务受理/幂等/详情投影/事件游标/取消恢复。
 * 验收口径（接口稿 §5.2）：mode 必填、requestId 幂等同参重放/异参 REQUEST_REPLAY、
 * availableActions 服务端计算、恢复投影 USER 三字段、TASK_INTERNAL 根会话受理事务创建、
 * 事件游标 nextAfterSeq 不 +1。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TasksApiTest {

    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
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
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task where tenant_id='engine-test'");
        db.update("delete from engine.session_event where session_id like 'ti-%'");
        db.update("delete from engine.chat_session where public_id like 'ti-%'");
    }

    JsonNode req(String requestId, String mode) {
        return Json.tree(
                Map.of("requestId", requestId, "mode", mode, "goal", "差异报告", "inputs", Map.of("a", 1)));
    }

    @Test
    void modeIsRequiredAndEnumChecked() {
        assertThrows(
                PlatformException.class,
                () -> tasks.create("t2-app", Json.tree(Map.of("requestId", "r-m0", "goal", "g")), actor));
        assertThrows(
                PlatformException.class,
                () -> tasks.create("t2-app", Json.tree(Map.of("requestId", "r-m1", "mode", "FLOW", "goal", "g")), actor));
    }

    @Test
    void idempotentReplayReturnsSameTaskAndConflictOnDifferentPayload() {
        var first = tasks.create("t2-app", req("r-1", "AGENT"), actor);
        var replay = tasks.create("t2-app", req("r-1", "AGENT"), actor);
        assertEquals(first.get("taskId"), replay.get("taskId"));
        var conflict =
                assertThrows(
                        PlatformException.class,
                        () -> tasks.create("t2-app", Json.tree(Map.of("requestId", "r-1", "mode", "AGENT", "goal", "别的目标")), actor));
        assertEquals(409, conflict.status());
        assertEquals("REQUEST_REPLAY", conflict.code());
    }

    @Test
    void acceptedTaskCarriesServerComputedActionsAndEventWatermark() {
        var t = tasks.create("t2-app", req("r-2", "AGENT"), actor);
        assertEquals("ACCEPTED", t.get("state"));
        assertTrue(((java.util.List<?>) t.get("availableActions")).contains("CANCEL"));
        assertEquals(1, ((Number) t.get("eventWatermark")).intValue());
        // 事件游标：nextAfterSeq=最后扫描 seq 不 +1；末页 truncated=false
        var page = tasks.events(taskId(t), 0, 200, actor);
        assertEquals(1L, page.get("nextAfterSeq"));
        assertEquals(false, page.get("truncated"));
    }

    @Test
    void cancelIsTerminalAndWaitingTaskResumesWithFactAndUserRoleProjection() {
        var t = tasks.create("t2-app", req("r-3", "AGENT"), actor);
        var cancelled = tasks.cancel(taskId(t), Json.object(), actor);
        assertEquals("CANCELLED", cancelled.get("state"));
        // 终态幂等：重复 cancel 返回原状；终态无恢复（RESUMABLE 是事实投影非状态，红线 4）
        assertEquals("CANCELLED", tasks.cancel(taskId(t), Json.object(), actor).get("state"));
        assertEquals("CANCELLED", tasks.resume(taskId(t), Json.object(), actor).get("state"));
        // 等待类任务恢复：置 WAITING_EXTERNAL（可续检查点语义由 T2-5/恢复协调接续）→ resume
        db.update("update engine.task set state='WAITING_EXTERNAL' where id=?", taskId(t));
        var resumed = tasks.resume(taskId(t), Json.object(), actor);
        assertEquals("RUNNING", resumed.get("state"));
        // USER 三字段投影（EDITOR 无 OPERATOR/ADMIN）
        var recovery = (Map<?, ?>) resumed.get("recovery");
        assertEquals("MANUAL", recovery.get("recoverySource"));
        assertEquals("MANUAL_REQUEST", recovery.get("reasonCode"));
        assertNotNull(recovery.get("reasonSummary"));
        assertFalse(recovery.containsKey("checkpoint"), "非特权角色不得见 checkpoint");
        // RESUME 资格叠加（事实计算）
        assertTrue(((java.util.List<?>) resumed.get("availableActions")).contains("RESUME"));
    }

    @Test
    void planTaskWithSubagentPolicyCreatesTaskInternalRootSession() {
        var t =
                tasks.create(
                        "t2-app",
                        Json.tree(
                                Map.of(
                                        "requestId", "r-4",
                                        "mode", "PLAN",
                                        "goal", "规划",
                                        "subagentPolicy", Map.of("enabled", true))),
                        actor);
        assertEquals("PLAN", t.get("purpose"));
        int internal =
                db.queryForObject(
                        "select count(*) from engine.chat_session where origin='TASK_INTERNAL'"
                            + " and tenant_id='engine-test'",
                        Integer.class);
        assertEquals(1, internal, "受理事务创建 TASK_INTERNAL 根会话");
    }

    private long taskId(Map<String, Object> detail) {
        String publicId = detail.get("taskId").toString();
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, publicId);
    }

    @Test
    void stepCallsAnchorFromToolCallSessionEvent() {
        var t = tasks.create("t2-app", req("r-5", "AGENT"), actor);
        long tid = taskId(t);
        // 运行（直接任务 entry 归属）
        long runId =
                db.queryForObject(
                        "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'t2-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}','{}','{}','STEP') returning id",
                        Long.class,
                        "r_h_t2_" + UUID.randomUUID());
        long nid =
                db.queryForObject(
                        "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,'n1',1,'CODE','RUNNING','{\"type\":\"CODE\"}') returning id",
                        Long.class,
                        runId);
        db.update("insert into engine.task_step(task_id,step_id,state,run_id) values(?,?,'RUNNING',?)", tid, "entry", runId);
        // 一个 SUCCEEDED 调用（无会话关联——独立任务锚点三件为 null）
        var claim = store.claim(nid, "w");
        db.update(
                "insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id)"
                    + " values(?,?,1,42,?,'pg:fixture',?,'SUCCEEDED','lookup','{}'::jsonb,?)",
                runId,
                nid,
                Digest.sha256("t2:" + nid + ":1:42"),
                Digest.sha256(nid + ":fixture"),
                TraceStore.number(claim, "attemptId"));
        // 查询 calls 列表
        var out = tasks.stepCalls(tid, "entry", 1, 20, actor);
        assertEquals(1, ((Number) out.get("total")).intValue());
        var calls = (java.util.List<?>) out.get("calls");
        assertEquals(1, calls.size());
        var call = (Map<?, ?>) calls.get(0);
        assertEquals("lookup", call.get("skillCode"));
        assertEquals("SUCCEEDED", call.get("status"));
        assertNull(call.get("appCode"), "独立任务无会话锚点三件为 null");
        assertNull(call.get("sessionId"));
        assertNull(call.get("eventSeq"));
    }
}
