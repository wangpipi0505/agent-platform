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

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * T2-6 定向测试（台账 §7.3）：依赖调度+分派三要素。验收口径（主方案 §5.5 末段）：
 * dispatch_key 稳定键=重复受理去重（同 (task,step) 幂等）；state_version CAS=拒绝过期状态更新；
 * lease+generation=当前写入权——<b>旧执行者超时→接管→旧回执到达：409 TASK_DISPATCH_STALE 留痕、
 * DONE 恰一次提交</b>；task_dependency 满足放行；并发认领恰一人成功。
 *
 * <p>夹具：agent_platform_engine_test 共享库，{@code h26_%} 前缀（tenant=h26/应用 h26-app/
 * 请求 h26-r-*），双向清理按 task_event→task_dependency→task_dispatch→task_step→运行链→
 * plan→task 顺序（照 TasksApiTest/StepExecutionMappingTest FK 反序惯例），不触碰其他库。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class TaskDispatchTest {

    static final String TENANT = "h26";
    static final Actor actor = new Actor(TENANT, "h26-alice", Set.of("EDITOR"), Map.of());
    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static TaskDispatcher dispatcher;
    static StepExecutionMapping mapping;

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
        dispatcher = new TaskDispatcher(store);
        mapping = new StepExecutionMapping(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        // h26_% 双向清理：先子表后 task（FK 反序；与共享库其他夹具互不触碰）
        db.update("update engine.task set selected_plan_revision_id=null where tenant_id='h26'");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='h26')");
        db.update("delete from engine.task_dependency where task_id in(select id from engine.task where tenant_id='h26')");
        db.update("delete from engine.task_dependency where upstream_task_id in(select id from engine.task where tenant_id='h26')");
        db.update("delete from engine.task_dispatch where task_id in(select id from engine.task where tenant_id='h26')");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='h26')");
        // 运行链（仅 dispatchKey 一致性用例经 mapping.admit 产生）
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='h26')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='h26')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='h26')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='h26')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='h26')");
        db.update("delete from engine.run where tenant_id='h26'");
        db.execute("ALTER TABLE engine.plan_step DISABLE TRIGGER plan_step_immutable_guard");
        db.update("delete from engine.plan_step where plan_revision_id in(select id from engine.plan_revision where tenant_id='h26')");
        db.execute("ALTER TABLE engine.plan_step ENABLE TRIGGER plan_step_immutable_guard");
        db.execute("ALTER TABLE engine.plan_revision DISABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.plan_revision where tenant_id='h26'");
        db.execute("ALTER TABLE engine.plan_revision ENABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.task where tenant_id='h26'");
    }

    // ---------- 夹具 ----------

    long agentTask(String requestId) {
        var t =
                tasks.create(
                        "h26-app",
                        Json.tree(Map.of("requestId", requestId, "mode", "AGENT", "goal", "分派演练")),
                        actor);
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, t.get("taskId"));
    }

    void seedStep(long tid, String stepId, String state) {
        db.update("insert into engine.task_step(task_id,step_id,state) values(?,?,?)", tid, stepId, state);
    }

    /** 同任务下游依赖（plan_step.depends_on）——complete 下游就绪放行的数据面。 */
    void seedDependentPlanStep(long tid, String upstreamStep, String downstreamStep) {
        Integer existing =
                db.queryForObject(
                        "select count(*) from engine.plan_revision where tenant_id='h26' and plan_id=?",
                        Integer.class,
                        "h26-plan-" + tid);
        int rev;
        if (existing != null && existing > 0) {
            rev =
                    db.queryForObject(
                            "select id from engine.plan_revision where tenant_id='h26' and plan_id=?",
                            Integer.class,
                            "h26-plan-" + tid);
        } else {
            rev =
                    db.queryForObject(
                            "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,"
                                + "schema_version,goal_spec,validation_status,digest,created_by)"
                                + " values('h26',?,1,?,'1.0','{}'::jsonb,'VALID',?,'h26-alice') returning id",
                            Integer.class,
                            "h26-plan-" + tid,
                            tid,
                            Digest.sha256("h26-plan-" + tid));
        }
        db.update(
                "insert into engine.plan_step(plan_revision_id,step_id,depends_on,kind,target_ref,"
                    + "input_bindings,required,failure_policy) values(?,?,?::jsonb,?,?,?::jsonb,?,?::jsonb)",
                rev,
                downstreamStep,
                Json.write(List.of(upstreamStep)),
                "AGENT",
                "agent:default@1",
                "{}",
                true,
                Json.write(Map.of("timeoutMs", 60000, "retry", Map.of("max", 2), "abort", false)));
    }

    String digest(String seed) {
        return Digest.sha256(seed);
    }

    // ---------- 用例 ----------

    @Test
    void dispatchKeyIsSha256StableFormulaAndDispatchIsIdempotent() throws Exception {
        long tid = agentTask("h26-r-k1");
        seedStep(tid, "entry", "READY");
        // 独立 oracle：MessageDigest 直算 sha256(tenant:task:planRevision:step)（库稿口径；直接任务 planRevision=0）
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest((TENANT + ":" + tid + ":0:entry").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), dispatcher.dispatchKey(tid, "entry"));
        assertEquals(64, dispatcher.dispatchKey(tid, "entry").length());
        // dispatch 幂等：同 (task,step) 两次 dispatch→同一行同键；已存在返回既有（不重复落行）
        var first = dispatcher.dispatch(tid, "entry", null);
        var second = dispatcher.dispatch(tid, "entry", null);
        assertEquals("PENDING", first.get("state"));
        assertEquals(first.get("dispatchKey"), second.get("dispatchKey"));
        assertEquals(true, first.get("created"));
        assertEquals(false, second.get("created"));
        assertEquals(
                1,
                db.queryForObject("select count(*) from engine.task_dispatch where task_id=?", Integer.class, tid));
        // 未知步骤/未知任务→404
        assertThrows(PlatformException.class, () -> dispatcher.dispatch(tid, "ghost", null));
        assertThrows(PlatformException.class, () -> dispatcher.dispatch(99999999, "entry", null));
    }

    @Test
    void dispatchKeyMatchesStepExecutionMappingAnchor() {
        long tid = agentTask("h26-r-k2");
        seedStep(tid, "entry", "READY");
        // T2-5 admit 已落的分派行——T2-6 dispatch 幂等返回既有且同键（单点公式不漂移）
        var admitted = mapping.admit(tid, "entry", actor);
        var dispatched = dispatcher.dispatch(tid, "entry", null);
        assertEquals(admitted.get("dispatchKey"), dispatched.get("dispatchKey"));
        assertEquals(false, dispatched.get("created"));
        assertEquals(
                admitted.get("dispatchKey"),
                db.queryForObject(
                        "select dispatch_key from engine.task_dispatch where task_id=? and step_id='entry'",
                        String.class,
                        tid));
    }

    @Test
    void claimAndCompleteCommitsOnceWithDownstreamRelease() {
        long tid = agentTask("h26-r-c1");
        seedStep(tid, "s1", "READY");
        seedStep(tid, "s2", "QUEUED");
        seedDependentPlanStep(tid, "s1", "s2");
        dispatcher.dispatch(tid, "s1", null);
        var lease = dispatcher.claim(tid, "s1", "h26-worker-a", 60);
        assertEquals(1, ((Number) lease.get("generation")).intValue());
        assertEquals("h26-worker-a", lease.get("leaseOwner"));
        assertEquals("LEASED", db.queryForObject("select state from engine.task_dispatch where task_id=? and step_id='s1'", String.class, tid));
        assertEquals("RUNNING", db.queryForObject("select state from engine.task_step where task_id=? and step_id='s1'", String.class, tid));
        // 三要素提交：owner+generation 匹配当前行→DONE 一次提交（task_step SUCCEEDED+结果摘要+下游就绪）
        String dg = digest("result-s1");
        var done = dispatcher.complete(tid, "s1", "h26-worker-a", 1, dg);
        assertEquals(true, done.get("committed"));
        assertEquals("DONE", db.queryForObject("select state from engine.task_dispatch where task_id=? and step_id='s1'", String.class, tid));
        var step = db.queryForMap("select state,result_digest,state_version from engine.task_step where task_id=? and step_id='s1'", tid);
        assertEquals("SUCCEEDED", step.get("state"));
        assertEquals(dg, step.get("result_digest"));
        // 下游就绪（同事务）：depends_on 下游 QUEUED→READY
        assertEquals("READY", db.queryForObject("select state from engine.task_step where task_id=? and step_id='s2'", String.class, tid));
        // 恰一次：重复回执幂等返回原结果，STEP_FINISHED 只有一条
        var replay = dispatcher.complete(tid, "s1", "h26-worker-a", 1, dg);
        assertEquals(false, replay.get("committed"));
        assertEquals(true, replay.get("alreadyDone"));
        assertEquals(dg, replay.get("resultDigest"));
        assertEquals(
                1,
                db.queryForObject("select count(*) from engine.task_event where task_id=? and type='STEP_FINISHED'", Integer.class, tid));
        // 摘要形状校验：非 64 位十六进制→400
        assertThrows(PlatformException.class, () -> dispatcher.complete(tid, "s1", "h26-worker-a", 1, "nothex"));
    }

    /** 验收核心（台账 T2-6）：旧执行者超时→新执行者接管→旧回执随后到达——回执可追溯、成功结果与下游就绪只提交一次。 */
    @Test
    void takeoverRejectsStaleReceiptThenNewGenerationCommitsOnce() {
        long tid = agentTask("h26-r-t1");
        seedStep(tid, "s1", "READY");
        dispatcher.dispatch(tid, "s1", null);
        var first = dispatcher.claim(tid, "s1", "h26-worker-old", 60); // gen=1
        assertEquals(1, ((Number) first.get("generation")).intValue());
        // 旧执行者超时（租约过期）→ 进入接管候选
        db.update("update engine.task_dispatch set lease_until=now()-interval '2 seconds' where task_id=? and step_id='s1'", tid);
        assertEquals(1, dispatcher.takeoverScan().size());
        var takeover = dispatcher.claim(tid, "s1", "h26-worker-new", 60); // 新执行者接管 gen=2
        assertEquals(2, ((Number) takeover.get("generation")).intValue());
        assertEquals("h26-worker-new", takeover.get("leaseOwner"));
        // 旧回执随后到达：409 TASK_DISPATCH_STALE，DONE 不落，RECONCILIATION_REQUIRED 留痕（可追溯不覆盖）
        var stale =
                assertThrows(
                        PlatformException.class,
                        () -> dispatcher.complete(tid, "s1", "h26-worker-old", 1, digest("late")));
        assertEquals(409, stale.status());
        assertEquals("TASK_DISPATCH_STALE", stale.code());
        assertEquals("LEASED", db.queryForObject("select state from engine.task_dispatch where task_id=? and step_id='s1'", String.class, tid));
        assertEquals("RUNNING", db.queryForObject("select state from engine.task_step where task_id=? and step_id='s1'", String.class, tid));
        assertEquals(
                1,
                db.queryForObject("select count(*) from engine.task_event where task_id=? and type='RECONCILIATION_REQUIRED'", Integer.class, tid),
                "晚到回执按代次拒绝入对账");
        JsonNode note =
                Json.read(
                        db.queryForObject(
                                "select payload from engine.task_event where task_id=? and type='RECONCILIATION_REQUIRED' order by seq desc limit 1",
                                String.class,
                                tid),
                        JsonNode.class);
        assertEquals(1, note.path("receiptGeneration").asInt());
        assertEquals(2, note.path("currentGeneration").asInt());
        assertEquals("h26-worker-old", note.path("receiptOwner").asText());
        assertEquals("h26-worker-new", note.path("currentOwner").asText());
        // 新代次写入权已归属：他人 owner 冒名提交→同拒绝（同 409 同码）
        var impostor =
                assertThrows(
                        PlatformException.class,
                        () -> dispatcher.complete(tid, "s1", "h26-worker-other", 2, digest("fresh")));
        assertEquals(409, impostor.status());
        assertEquals("TASK_DISPATCH_STALE", impostor.code());
        // 新执行者 gen=2 提交：DONE 恰一次提交
        String dg = digest("fresh");
        var done = dispatcher.complete(tid, "s1", "h26-worker-new", 2, dg);
        assertEquals(true, done.get("committed"));
        assertEquals("DONE", db.queryForObject("select state from engine.task_dispatch where task_id=? and step_id='s1'", String.class, tid));
        assertEquals(dg, db.queryForObject("select result_digest from engine.task_step where task_id=? and step_id='s1'", String.class, tid));
        assertEquals(
                1,
                db.queryForObject("select count(*) from engine.task_event where task_id=? and type='STEP_FINISHED'", Integer.class, tid));
    }

    @Test
    void dependencySatisfiedReleasesDownstreamTaskWhenAllSatisfied() {
        long upId = agentTask("h26-r-u1");
        long up2Id = agentTask("h26-r-u2");
        var down =
                tasks.create(
                        "h26-app",
                        Json.tree(
                                Map.of(
                                        "requestId", "h26-r-d1",
                                        "mode", "AGENT",
                                        "goal", "下游任务",
                                        "dependencies",
                                        List.of(
                                                Map.of("upstreamTaskId", taskIdOf(upId), "inputBindings", Map.of("result", "up.result")),
                                                Map.of("upstreamTaskId", taskIdOf(up2Id))))),
                        actor);
        // 上游未满足→受理后置 WAITING_DEPENDENCIES；availableActions=[VIEW_DEPENDENCIES,CANCEL]；依赖投影可读
        assertEquals("WAITING_DEPENDENCIES", down.get("state"));
        var actions = (List<?>) down.get("availableActions");
        assertTrue(actions.contains("VIEW_DEPENDENCIES"));
        assertTrue(actions.contains("CANCEL"));
        assertEquals(2, ((List<?>) down.get("dependencies")).size());
        long downId = db.queryForObject("select id from engine.task where public_id=?", Long.class, down.get("taskId"));
        String dg = digest("upstream-result");
        // 上游未成功→409 且不写 satisfied_at
        var notYet =
                assertThrows(PlatformException.class, () -> dispatcher.dependencySatisfied(downId, upId, dg));
        assertEquals("TASK_DEPENDENCY_UPSTREAM_NOT_SUCCEEDED", notYet.code());
        assertEquals(
                0,
                db.queryForObject("select count(*) from engine.task_dependency where task_id=? and satisfied_at is not null", Integer.class, downId));
        // 上游成功但摘要不匹配→409（不静默、不覆盖）
        db.update("update engine.task set state='SUCCEEDED',result_digest=? where id=?", dg, upId);
        var mismatch =
                assertThrows(PlatformException.class, () -> dispatcher.dependencySatisfied(downId, upId, digest("wrong")));
        assertEquals("TASK_DEPENDENCY_DIGEST_MISMATCH", mismatch.code());
        assertEquals(
                0,
                db.queryForObject("select count(*) from engine.task_dependency where task_id=? and satisfied_at is not null", Integer.class, downId));
        // 匹配→写 satisfied_at；另一依赖未满足→不整体放行
        var partial = dispatcher.dependencySatisfied(downId, upId, dg);
        assertNotNull(partial.get("satisfiedAt"));
        assertEquals(false, partial.get("released"));
        assertEquals("WAITING_DEPENDENCIES", db.queryForObject("select state from engine.task where id=?", String.class, downId));
        // 全部依赖满足→WAITING_DEPENDENCIES→QUEUED 放行（task.state_version CAS）
        String dg2 = digest("upstream-result-2");
        db.update("update engine.task set state='SUCCEEDED',result_digest=? where id=?", dg2, up2Id);
        var released = dispatcher.dependencySatisfied(downId, up2Id, dg2);
        assertEquals(true, released.get("released"));
        assertEquals("QUEUED", db.queryForObject("select state from engine.task where id=?", String.class, downId));
        int version = db.queryForObject("select state_version from engine.task where id=?", Integer.class, downId);
        assertTrue(version >= 3, "放行经 state_version CAS 递增");
    }

    @Test
    void concurrentClaimExactlyOneWinner() throws Exception {
        long tid = agentTask("h26-r-x1");
        seedStep(tid, "s1", "READY");
        dispatcher.dispatch(tid, "s1", null);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        Future<Map<String, Object>> f1;
        Future<Map<String, Object>> f2;
        Map<String, Object> r1;
        Map<String, Object> r2;
        try {
            f1 = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return dispatcher.claim(tid, "s1", "h26-worker-a", 60); });
            f2 = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return dispatcher.claim(tid, "s1", "h26-worker-b", 60); });
            r1 = f1.get(15, TimeUnit.SECONDS);
            r2 = f2.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        int winners = (r1 != null ? 1 : 0) + (r2 != null ? 1 : 0);
        assertEquals(1, winners, "并发认领恰一人成功（条件更新，行锁+谓词重查）");
        assertEquals(
                1,
                db.queryForObject("select generation from engine.task_dispatch where task_id=? and step_id='s1'", Integer.class, tid));
        String owner =
                db.queryForObject("select lease_owner from engine.task_dispatch where task_id=? and step_id='s1'", String.class, tid);
        var winner = r1 != null ? r1 : r2;
        assertEquals(owner, winner.get("leaseOwner"));
        assertEquals("LEASED", winner.get("state"));
    }

    @Test
    void claimReturnsNullWhenLeaseHeldOrAbsent() {
        long tid = agentTask("h26-r-n1");
        seedStep(tid, "s1", "READY");
        // 无分派行→无可认领
        assertNull(dispatcher.claim(tid, "s1", "h26-worker-a", 60));
        dispatcher.dispatch(tid, "s1", null);
        assertNotNull(dispatcher.claim(tid, "s1", "h26-worker-a", 60));
        // 有效租约期内他人认领→null
        assertNull(dispatcher.claim(tid, "s1", "h26-worker-b", 60));
        // 租约过期→可接管（claim 认领条件已含过期 LEASED；generation 递增）
        db.update("update engine.task_dispatch set lease_until=now()-interval '2 seconds' where task_id=? and step_id='s1'", tid);
        var takeover = dispatcher.claim(tid, "s1", "h26-worker-b", 60);
        assertEquals(2, ((Number) takeover.get("generation")).intValue());
        assertEquals("h26-worker-b", takeover.get("leaseOwner"));
    }

    private String taskIdOf(long id) {
        return db.queryForObject("select public_id from engine.task where id=?", String.class, id);
    }
}
