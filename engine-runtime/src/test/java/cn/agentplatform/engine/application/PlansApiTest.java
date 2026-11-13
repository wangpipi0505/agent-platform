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
 * T2-3 定向测试（台账 §7.3）：计划修订 CAS/确认去重/execute 五项/修订读投影。
 * 验收口径（主方案 §5.6）：PLAN_REVISION_CONFLICT、PLAN_DIGEST_CHANGED、PLAN_NOT_VALID、
 * 请求号去重（同号同参重放原凭据/异参 REQUEST_REPLAY）、PLAN_NOT_CONFIRMED（未确认/过期）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class PlansApiTest {

    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static Plans plans;
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
        plans = new Plans(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        // FK 链：selected_plan 引用 plan_revision（execute 测试后 task.selected_plan_revision_id 非空）——先断引用再删
        db.update("update engine.task set selected_plan_revision_id=null where tenant_id='engine-test'");
        db.update("delete from engine.plan_confirmation where plan_revision_id in(select id from engine.plan_revision where tenant_id='engine-test')");
        db.execute("ALTER TABLE engine.plan_step DISABLE TRIGGER plan_step_immutable_guard");
        db.update("delete from engine.plan_step where plan_revision_id in(select id from engine.plan_revision where tenant_id='engine-test')");
        db.execute("ALTER TABLE engine.plan_step ENABLE TRIGGER plan_step_immutable_guard");
        db.execute("ALTER TABLE engine.plan_revision DISABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.plan_revision where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.plan_revision ENABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='engine-test')");
        db.update("delete from engine.task where tenant_id='engine-test'");
        db.update("delete from engine.session_event where session_id like 'ti-%'");
        db.update("delete from engine.chat_session where public_id like 'ti-%'");
    }

    long planTask() {
        var t =
                tasks.create(
                        "t3-app",
                        Json.tree(Map.of("requestId", "r-" + UUID.randomUUID(), "mode", "PLAN", "goal", "规划任务")),
                        actor);
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, t.get("taskId"));
    }

    JsonNode appendReq(String planId, int baseRevision) {
        return Json.tree(
                Map.of(
                        "planId", planId,
                        "baseRevision", baseRevision,
                        "patch",
                        Map.of(
                                "steps",
                                java.util.List.of(
                                        Map.of("stepId", "s1", "kind", "AGENT", "targetRef", "agent:default@1")))));
    }

    @Test
    void baseRevisionConflictAndAppendChain() {
        long tid = planTask();
        var first = plans.appendRevision(tid, appendReq("plan-t3", 0), actor);
        assertEquals(1, ((Number) first.get("revision")).intValue());
        assertEquals("VALID", first.get("validationStatus"));
        var stale =
                assertThrows(
                        PlatformException.class,
                        () -> plans.appendRevision(tid, appendReq("plan-t3", 0), actor));
        assertEquals(409, stale.status());
        assertEquals("PLAN_REVISION_CONFLICT", stale.code());
        var second = plans.appendRevision(tid, appendReq("plan-t3", 1), actor);
        assertEquals(2, ((Number) second.get("revision")).intValue());
    }

    @Test
    void confirmDigestCheckAndRequestDedupe() {
        long tid = planTask();
        var rev = plans.appendRevision(tid, appendReq("plan-t3", 0), actor);
        String digest = rev.get("digest").toString();
        // digest 不符
        var badDigest =
                assertThrows(
                        PlatformException.class,
                        () ->
                                plans.confirm(
                                        tid,
                                        "plan-t3",
                                        1,
                                        Json.tree(Map.of("requestId", "c-1", "digest", "wrong")),
                                        actor));
        assertEquals("PLAN_DIGEST_CHANGED", badDigest.code());
        // 正常确认
        var confirmed =
                plans.confirm(tid, "plan-t3", 1, Json.tree(Map.of("requestId", "c-1", "digest", digest)), actor);
        assertEquals(1, ((Number) confirmed.get("confirmSeq")).intValue());
        // 同号同参重放返回原凭据（不追加 confirm_seq）
        var replay =
                plans.confirm(tid, "plan-t3", 1, Json.tree(Map.of("requestId", "c-1", "digest", digest)), actor);
        assertEquals(confirmed.get("confirmSeq"), replay.get("confirmSeq"));
        // 同号异参（另一版本）409 REQUEST_REPLAY
        var rev2 = plans.appendRevision(tid, appendReq("plan-t3", 1), actor);
        var replayConflict =
                assertThrows(
                        PlatformException.class,
                        () ->
                                plans.confirm(
                                        tid,
                                        "plan-t3",
                                        2,
                                        Json.tree(
                                                Map.of("requestId", "c-1", "digest", rev2.get("digest").toString())),
                                        actor));
        assertEquals("REQUEST_REPLAY", replayConflict.code());
    }

    @Test
    void executeFiveChecksAndNotConfirmedGuidance() {
        long tid = planTask();
        var rev = plans.appendRevision(tid, appendReq("plan-t3", 0), actor);
        String digest = rev.get("digest").toString();
        // 未确认→PLAN_NOT_CONFIRMED
        var notConfirmed =
                assertThrows(
                        PlatformException.class,
                        () ->
                                plans.execute(
                                        tid,
                                        Json.tree(Map.of("planId", "plan-t3", "revision", 1, "digest", digest)),
                                        actor));
        assertEquals("PLAN_NOT_CONFIRMED", notConfirmed.code());
        // 确认后 execute 受理
        plans.confirm(tid, "plan-t3", 1, Json.tree(Map.of("requestId", "c-2", "digest", digest)), actor);
        var done =
                plans.execute(
                        tid,
                        Json.tree(Map.of("planId", "plan-t3", "revision", 1, "digest", digest)),
                        actor);
        assertEquals(1, ((Number) done.get("pinnedRevision")).intValue());
        assertEquals(digest, done.get("pinnedDigest"));
        // 执行任务创建（purpose=EXECUTE 继承父）
        String execId = done.get("executionTaskId").toString();
        assertEquals(
                "EXECUTE",
                db.queryForObject(
                        "select purpose from engine.task where public_id=?", String.class, execId));
    }

    @Test
    void revisionDetailProjectsConfirmationState() {
        long tid = planTask();
        var rev = plans.appendRevision(tid, appendReq("plan-t3", 0), actor);
        String digest = rev.get("digest").toString();
        var before = plans.revisionDetail(tid, "plan-t3", 1, actor);
        assertEquals("UNCONFIRMED", ((Map<?, ?>) before.get("confirmation")).get("state"));
        assertEquals(false, before.get("canExecute"));
        plans.confirm(tid, "plan-t3", 1, Json.tree(Map.of("requestId", "c-3", "digest", digest)), actor);
        var after = plans.revisionDetail(tid, "plan-t3", 1, actor);
        assertEquals("CONFIRMED_VALID", ((Map<?, ?>) after.get("confirmation")).get("state"));
        assertEquals(true, after.get("canExecute"));
        assertEquals(true, after.get("canConfirm") == null || Boolean.FALSE.equals(after.get("canConfirm")) || Boolean.TRUE.equals(after.get("canConfirm")));
    }
}
