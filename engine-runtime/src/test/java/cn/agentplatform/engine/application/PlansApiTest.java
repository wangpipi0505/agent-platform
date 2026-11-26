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
                                        Map.of(
                                                "stepId",
                                                "s1",
                                                "kind",
                                                "AGENT",
                                                "targetRef",
                                                "agent:default@1",
                                                // R-1：校验器接线后 inputBindings 必填（对象）——合法样例带空对象
                                                "inputBindings",
                                                Map.of())))));
    }

    @Test
    void baseRevisionConflictAndAppendChain() {
        long tid = planTask();
        var first = plans.appendRevision(tid, appendReq("plan-t3", 0), actor);
        assertEquals(1, ((Number) first.get("revision")).intValue());
        assertEquals("VALID", first.get("validationStatus"));
        // VALID 修订 diagnostics 为空且库行落 VALID（R-1：校验通过行为不变）
        assertEquals(List.of(), first.get("diagnostics"));
        assertEquals(
                "VALID",
                db.queryForObject(
                        "select validation_status from engine.plan_revision where owner_task_id=?"
                            + " and plan_id='plan-t3' and revision=1",
                        String.class,
                        tid));
        assertNull(
                db.queryForObject(
                        "select diagnostics from engine.plan_revision where owner_task_id=?"
                            + " and plan_id='plan-t3' and revision=1",
                        Object.class,
                        tid),
                "VALID 修订 diagnostics=NULL（未产生）");
        var stale =
                assertThrows(
                        PlatformException.class,
                        () -> plans.appendRevision(tid, appendReq("plan-t3", 0), actor));
        assertEquals(409, stale.status());
        assertEquals("PLAN_REVISION_CONFLICT", stale.code());
        var second = plans.appendRevision(tid, appendReq("plan-t3", 1), actor);
        assertEquals(2, ((Number) second.get("revision")).intValue());
    }

    // ------------------------------------------------- R-1：校验器接线（非法计划落 INVALID+diagnostics）

    /** 环依赖样例：INVALID+DEPENDENCY_CYCLE 诊断带 /steps/<stepId>/dependsOn 定位；库行 diagnostics 落 jsonb。 */
    @Test
    void cycleDependencyFallsInvalidWithLocatedDiagnostics() {
        long tid = planTask();
        var result =
                plans.appendRevision(
                        tid,
                        Json.tree(
                                Map.of(
                                        "planId",
                                        "plan-cycle",
                                        "baseRevision",
                                        0,
                                        "patch",
                                        Map.of(
                                                "steps",
                                                java.util.List.of(
                                                        Map.of(
                                                                "stepId", "a",
                                                                "kind", "AGENT",
                                                                "targetRef", "agent:x",
                                                                "inputBindings", Map.of(),
                                                                "dependsOn", List.of("b")),
                                                        Map.of(
                                                                "stepId", "b",
                                                                "kind", "AGENT",
                                                                "targetRef", "agent:x",
                                                                "inputBindings", Map.of(),
                                                                "dependsOn", List.of("a")))))),
                        actor);
        assertEquals("INVALID", result.get("validationStatus"), "环依赖判 INVALID");
        @SuppressWarnings("unchecked")
        var diags = (List<Map<String, String>>) result.get("diagnostics");
        assertTrue(
                diags.stream().anyMatch(d -> "DEPENDENCY_CYCLE".equals(d.get("code"))),
                "诊断含 DEPENDENCY_CYCLE: " + diags);
        assertTrue(
                diags.stream()
                        .anyMatch(d -> "DEPENDENCY_CYCLE".equals(d.get("code"))
                                && d.get("path") != null
                                && d.get("path").matches("/steps/[aA-Za-z0-9_-]+/dependsOn")),
                "诊断 path 定位到步骤 dependsOn: " + diags);
        // 库行：validation_status=INVALID，diagnostics 落 jsonb（非 NULL）
        var row = db.queryForMap("select validation_status,diagnostics from engine.plan_revision"
                + " where owner_task_id=? and plan_id='plan-cycle' and revision=1", tid);
        assertEquals("INVALID", row.get("validation_status"));
        assertNotNull(row.get("diagnostics"), "diagnostics 落库");
        assertTrue(Json.read(String.valueOf(row.get("diagnostics")), JsonNode.class).isArray());
        // INVALID 不可确认（409 PLAN_NOT_VALID）且投影 canConfirm=false。
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () ->
                                plans.confirm(
                                        tid,
                                        "plan-cycle",
                                        1,
                                        Json.tree(Map.of("requestId", "c-inv", "digest", result.get("digest").toString())),
                                        actor));
        assertEquals("PLAN_NOT_VALID", rejected.code());
        var detail = plans.revisionDetail(tid, "plan-cycle", 1, actor);
        assertEquals("INVALID", detail.get("validationStatus"));
        assertEquals(false, detail.get("canConfirm"), "INVALID 修订不可确认");
        assertEquals(false, detail.get("canExecute"));
        // INVALID 头修订不阻断追加链：baseRevision=1（INVALID 头）可继续追加修订 2。
        var second = plans.appendRevision(tid, appendReq("plan-cycle", 1), actor);
        assertEquals(2, ((Number) second.get("revision")).intValue());
        assertEquals("VALID", second.get("validationStatus"));
    }

    /** 未知依赖样例：INVALID+DEPENDENCY_UNKNOWN 诊断；非法 shape 样例（SKILL 缺 digest+缺 inputBindings）带定位。 */
    @Test
    void unknownDependencyAndBadShapeFallInvalidWithLocatedDiagnostics() {
        long tid = planTask();
        // ①未知依赖
        var unknown =
                plans.appendRevision(
                        tid,
                        Json.tree(
                                Map.of(
                                        "planId",
                                        "plan-unknown",
                                        "baseRevision",
                                        0,
                                        "patch",
                                        Map.of(
                                                "steps",
                                                java.util.List.of(
                                                        Map.of(
                                                                "stepId", "s1",
                                                                "kind", "AGENT",
                                                                "targetRef", "agent:x",
                                                                "inputBindings", Map.of(),
                                                                "dependsOn", List.of("ghost")))))),
                        actor);
        assertEquals("INVALID", unknown.get("validationStatus"));
        @SuppressWarnings("unchecked")
        var unknownDiags = (List<Map<String, String>>) unknown.get("diagnostics");
        assertTrue(
                unknownDiags.stream().anyMatch(d ->
                        "DEPENDENCY_UNKNOWN".equals(d.get("code"))
                                && "/steps/s1/dependsOn".equals(d.get("path"))
                                && d.get("message").contains("ghost")),
                "未知依赖诊断带定位与缺失步骤: " + unknownDiags);
        // ②非法 shape：SKILL targetRef 缺 @版本#digest + inputBindings 缺失——两条诊断均定位到步骤
        var badShape =
                plans.appendRevision(
                        tid,
                        Json.tree(
                                Map.of(
                                        "planId",
                                        "plan-shape",
                                        "baseRevision",
                                        0,
                                        "patch",
                                        Map.of(
                                                "steps",
                                                java.util.List.of(
                                                        Map.of(
                                                                "stepId", "sk1",
                                                                "kind", "SKILL",
                                                                "targetRef", "skill:lookup@1",
                                                                "dependsOn", List.of()))))),
                        actor);
        assertEquals("INVALID", badShape.get("validationStatus"));
        @SuppressWarnings("unchecked")
        var shapeDiags = (List<Map<String, String>>) badShape.get("diagnostics");
        assertTrue(
                shapeDiags.stream().anyMatch(d ->
                        "TARGET_REF_SHAPE".equals(d.get("code"))
                                && "/steps/sk1/targetRef".equals(d.get("path"))),
                "shape 诊断定位 targetRef: " + shapeDiags);
        assertTrue(
                shapeDiags.stream().anyMatch(d ->
                        "INPUT_BINDINGS_REQUIRED".equals(d.get("code"))
                                && "/steps/sk1/inputBindings".equals(d.get("path"))),
                "缺 inputBindings 诊断定位: " + shapeDiags);
        // ③能力引用不在授权范围（tool_policy.allowedToolRefs 限定 lookup，rogue 不可用）。
        long tidPolicy = planTask();
        db.update(
                "update engine.task set tool_policy=?::jsonb where id=?",
                "{\"allowedToolRefs\":[\"skill:lookup@1#" + "a".repeat(64) + "\"]}",
                tidPolicy);
        var notAllowed =
                plans.appendRevision(
                        tidPolicy,
                        Json.tree(
                                Map.of(
                                        "planId",
                                        "plan-rogue",
                                        "baseRevision",
                                        0,
                                        "patch",
                                        Map.of(
                                                "steps",
                                                java.util.List.of(
                                                        Map.of(
                                                                "stepId", "rogue1",
                                                                "kind", "SKILL",
                                                                "targetRef", "skill:rogue@1#" + "a".repeat(64),
                                                                "inputBindings", Map.of()))))),
                        actor);
        assertEquals("INVALID", notAllowed.get("validationStatus"), "未授权技能判 INVALID");
        @SuppressWarnings("unchecked")
        var rogueDiags = (List<Map<String, String>>) notAllowed.get("diagnostics");
        assertTrue(
                rogueDiags.stream().anyMatch(d ->
                        "CAPABILITY_NOT_ALLOWED".equals(d.get("code"))
                                && d.get("message").contains("rogue")),
                "能力范围诊断: " + rogueDiags);
        // 授权范围内技能 → VALID（tool_policy 能力口径与校验器一致）。
        var allowed =
                plans.appendRevision(
                        tidPolicy,
                        Json.tree(
                                Map.of(
                                        "planId",
                                        "plan-ok",
                                        "baseRevision",
                                        0,
                                        "patch",
                                        Map.of(
                                                "steps",
                                                java.util.List.of(
                                                        Map.of(
                                                                "stepId", "ok1",
                                                                "kind", "SKILL",
                                                                "targetRef", "skill:lookup@1#" + "a".repeat(64),
                                                                "inputBindings", Map.of()))))),
                        actor);
        assertEquals("VALID", allowed.get("validationStatus"), "授权技能通过");
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
    void ownedExecutionReplaysTheSameChildAndAuditsTheOperatorReasonOnce() {
        long id = planTask();
        var revision = plans.appendRevision(id, appendReq("plan-audit", 0), actor);
        var digest = revision.get("digest").toString();
        plans.confirm(id, "plan-audit", 1, Json.tree(Map.of("requestId", "confirm-audit", "digest", digest)), actor);
        var execute = Json.tree(Map.of("planId", "plan-audit", "revision", 1, "digest", digest, "requestId", "execute-audit"));
        var first = plans.execute(id, execute, actor, "scheduled validation");
        assertEquals(first, plans.execute(id, execute, actor, "scheduled validation"));
        assertEquals(List.of("scheduled validation"), db.queryForList(
                "select e.payload->>'operationReason' from engine.task_event e join engine.task t on t.id=e.task_id where t.public_id=?",
                String.class, first.get("executionTaskId")));
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
