package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.SubagentCatalogController;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * T2-7 定向测试（台账 §7.3，A03 验收口径）：规划子代理/失败回执/一次合并/目录查询。
 *
 * <p>验收映射（主方案 §5.5、库稿 §4.1/§4.2、接口稿 §5.3、contracts/task-states.md §5）：
 * 无用户会话 PLAN 任务启用规划子代理→受理创建 TASK_INTERNAL 根会话，同请求重放只建一个
 * （A03 主断言）；startPlannerSubagents 受理事务幂等（重放不重复创建）；双成功→合并恰一次
 * （重复 merge 幂等返回原结果）；单失败按有效候选合并；全失败→父任务 FAILED 原因码
 * SUBAGENT_NO_VALID_CANDIDATE；迟到/重复回执保留审计不覆盖已提交候选与 merged_decision；
 * 目录分角色可见（USER 本人父会话+停因展示摘要、OPERATOR/ADMIN 终态原因码、他人/他租 404）。
 *
 * <p>夹具：agent_platform_engine_test 共享库，{@code h27_%} 前缀（tenant=h27/应用 h27-app/
 * 请求 h27-r-*），双向清理照 TaskDispatchTest FK 反序惯例（session_event 触发器禁用照
 * SessionEventsEndpointTest），不触碰其他库与其他夹具。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class SubagentPlanningTest {

    static final String TENANT = "h27";
    static final Actor actor = new Actor(TENANT, "h27-alice", Set.of("USER"), Map.of());
    static final JsonNode SCHEMA = Json.tree(Map.of("type", "object"));
    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static SubagentPlanning planning;

    @BeforeAll
    static void connect() {
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
        planning = new SubagentPlanning(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        // h27_% 双向清理：先子表后父表（FK 反序），session_event 追加触发器按先例禁用后清理。
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update(
                "delete from engine.session_event where session_id in"
                    + " (select public_id from engine.chat_session where tenant_id='h27')");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update(
                "delete from engine.subagent_catalog where parent_session_id in"
                    + " (select public_id from engine.chat_session where tenant_id='h27')");
        db.update(
                "delete from engine.subtask_receipt where child_task_id in"
                    + " (select id from engine.task where tenant_id='h27')");
        db.update("delete from engine.task_event where task_id in(select id from engine.task where tenant_id='h27')");
        db.update("delete from engine.task_step where task_id in(select id from engine.task where tenant_id='h27')");
        db.update(
                "delete from engine.task_dependency where task_id in(select id from engine.task where tenant_id='h27')"
                    + " or upstream_task_id in(select id from engine.task where tenant_id='h27')");
        db.update(
                "delete from engine.task_dispatch where task_id in(select id from engine.task where tenant_id='h27')");
        db.execute("ALTER TABLE engine.plan_step DISABLE TRIGGER plan_step_immutable_guard");
        db.update(
                "delete from engine.plan_step where plan_revision_id in"
                    + " (select id from engine.plan_revision where tenant_id='h27')");
        db.execute("ALTER TABLE engine.plan_step ENABLE TRIGGER plan_step_immutable_guard");
        db.execute("ALTER TABLE engine.plan_revision DISABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.plan_revision where tenant_id='h27'");
        db.execute("ALTER TABLE engine.plan_revision ENABLE TRIGGER plan_revision_immutable_guard");
        // task（session_id FK→chat_session）先于会话删除。
        db.update("delete from engine.task where tenant_id='h27'");
        db.update("delete from engine.chat_turn where session_id in(select public_id from engine.chat_session where tenant_id='h27')");
        db.update("delete from engine.chat_session where tenant_id='h27'");
        SecurityContextHolder.clearContext();
    }

    // ---------- 夹具 ----------

    /** 无用户会话 PLAN 任务（subagentPolicy.enabled=true）→受理建 TASK_INTERNAL 根会话。 */
    long planTask(String requestId) {
        var t = tasks.create("h27-app", planBody(requestId), actor);
        return db.queryForObject("select id from engine.task where public_id=?", Long.class, t.get("taskId"));
    }

    JsonNode planBody(String requestId) {
        return Json.tree(
                Map.of(
                        "requestId", requestId,
                        "mode", "PLAN",
                        "goal", "规划差异报告",
                        "subagentPolicy", Map.of("enabled", true)));
    }

    String rootSession(long taskId) {
        return db.queryForObject("select session_id from engine.task where id=?", String.class, taskId);
    }

    /** 子任务候选修订（owner=子任务，VALID——库稿"候选=通过结构校验的候选版本"）。 */
    long candidate(long childTaskId, int revision) {
        return db.queryForObject(
                "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,"
                    + "schema_version,goal_spec,validation_status,digest,created_by)"
                    + " values('h27',?,?,?,'1.0','{}'::jsonb,'VALID',?,'h27-alice') returning id",
                Long.class,
                "h27-plan-" + childTaskId,
                revision,
                childTaskId,
                Digest.sha256("h27-cand:" + childTaskId + ":" + revision));
    }

    long childTaskId(long parentTaskId, int seq) {
        return db.queryForObject(
                "select id from (select id, row_number() over (order by id) rn from engine.task"
                    + " where parent_task_id=?) t where rn=?",
                Long.class,
                parentTaskId,
                seq);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> children(Map<String, Object> start) {
        return (List<Map<String, Object>>) start.get("children");
    }

    Map<String, Object> receipt(long childTaskId) {
        return db.queryForMap("select * from engine.subtask_receipt where child_task_id=?", childTaskId);
    }

    // ---------------------------------------------------------------- A03 主断言

    /** A03：无用户会话 PLAN 任务启用规划子代理→受理创建 TASK_INTERNAL 根会话；同请求重放只建一个。 */
    @Test
    void planTaskWithoutUserSessionCreatesSingleInternalRootSessionOnReplay() {
        var first = tasks.create("h27-app", planBody("h27-r-a03"), actor);
        assertEquals("PLAN", first.get("purpose"));
        var roots =
                db.queryForList(
                        "select * from engine.chat_session where tenant_id='h27' and origin='TASK_INTERNAL'");
        assertEquals(1, roots.size(), "受理事务创建恰一个 TASK_INTERNAL 根会话");
        assertEquals("h27-alice", roots.getFirst().get("principal_id"), "根会话绑定任务提交主体");
        assertEquals(0, ((Number) roots.getFirst().get("delegation_depth")).intValue(), "根会话深度 0");
        String rootId = String.valueOf(roots.getFirst().get("public_id"));
        assertEquals(
                rootId,
                db.queryForObject("select session_id from engine.task where public_id=?", String.class, first.get("taskId")),
                "任务绑定根会话");
        // 同请求重放：返回原任务，不另建根会话（T2-2 requestId 幂等分支先行）。
        var replay = tasks.create("h27-app", planBody("h27-r-a03"), actor);
        assertEquals(first.get("taskId"), replay.get("taskId"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.chat_session where tenant_id='h27' and origin='TASK_INTERNAL'",
                        Integer.class),
                "重放后仍只有一个根会话");
    }

    // ---------------------------------------------------------------- R-2：真实受理路径派发

    /**
     * R-2（返工卡）：经真实受理路径（{@code Tasks.create} 公共入口，不直调
     * startPlannerSubagents）受理 subagentPolicy.enabled 的 PLAN 任务→受理事务内派发规划
     * 子代理；同 requestId 重放不重复建根会话、不重复派发（A03 幂等经真实路径复验）；
     * 子代理目录接口可查到派发的子代理。
     */
    @Test
    void realAcceptancePathStartsPlannerSubagentsIdempotentlyAndCatalogListsThem() throws Exception {
        var first = tasks.create("h27-app", planBody("h27-r-real"), actor);
        long tid =
                db.queryForObject(
                        "select id from engine.task where public_id=?", Long.class, first.get("taskId"));
        String root = rootSession(tid);
        // 经真实受理路径派发：缺省 PLANNER_ALGO×2（不直调 SubagentPlanning）。
        var kids =
                db.queryForList(
                        "select role,state,parent_task_id from engine.task where parent_task_id=? order by id",
                        tid);
        assertEquals(2, kids.size(), "受理事务派发 2 个规划子代理");
        assertEquals("PLANNER_ALGO", kids.get(0).get("role"));
        assertEquals("PLANNING", kids.get(0).get("state"));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.subagent_catalog where parent_session_id=?",
                        Integer.class,
                        root),
                "目录行随受理事务落库");
        // 子代理目录接口可查到（接口稿 §5.3——提交人本人视图）。
        identity(TENANT, "h27-alice", List.of("USER"));
        var data = callGet(mvc(), "/v1/sessions/" + root + "/subagents", 200).path("data");
        assertEquals(2, data.path("children").size(), "目录接口返回受理事派发的子代理");
        assertTrue(data.path("children").get(0).path("childTaskId").asLong() > 0, "目录携带子任务标识（任务卡关联）");
        // 同 requestId 重放：返回原任务；根会话不另建、子代理不重复派发、事件不重复。
        var replay = tasks.create("h27-app", planBody("h27-r-real"), actor);
        assertEquals(first.get("taskId"), replay.get("taskId"));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.chat_session where tenant_id='h27' and origin='TASK_INTERNAL'",
                        Integer.class),
                "重放不另建根会话（A03）");
        assertEquals(2, db.queryForObject("select count(*) from engine.task where parent_task_id=?", Integer.class, tid),
                "重放不重复派发子代理");
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type='subagent/registered'",
                        Integer.class,
                        root),
                "登记事件仍为 2 条（不重复）");
    }

    // ---------------------------------------------------------------- 派发幂等与登记

    /**
     * startPlannerSubagents 受理事务幂等（R-2 后受理即派发——经 planTask 真实受理路径先建，
     * 直调=同 (parent_task_id, role) 重放键的重放）：重放返回既有子任务集，不重复创建；
     * 目录/子任务/事件齐备。真实受理路径的主断言另见 realAcceptancePath... 用例。
     */
    @Test
    void startPlannerSubagentsIsIdempotentAndRegistersCatalogAndSubtasks() {
        long tid = planTask("h27-r-start"); // 受理事务内已派发 PLANNER_ALGO×2（真实受理路径）
        String root = rootSession(tid);
        var first = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        assertFalse((Boolean) first.get("created"), "受理已派发——直调按重放键返回既有集合");
        assertEquals(2, children(first).size());
        assertEquals("PLANNER_ALGO-1", children(first).get(0).get("label"));
        assertEquals("PLANNER_ALGO-2", children(first).get(1).get("label"));
        // 重放：返回既有（created=false），子会话/子任务数量不增。
        var replay = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        assertFalse((Boolean) replay.get("created"));
        assertEquals(children(first), children(replay), "重放返回既有子任务集");
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.chat_session where origin='SUBAGENT'"
                            + " and parent_session_id=? and delegation_depth=1",
                        Integer.class,
                        root),
                "子会话 origin=SUBAGENT/parent=根会话/depth=1");
        // 目录行：label=role+seq；start_seq=父会话 subagent/registered 事件 seq（溯源）；未终态。
        var catalog =
                db.queryForList(
                        "select label,start_seq,started_at,ended_at,stop_reason from engine.subagent_catalog"
                            + " where parent_session_id=? order by label",
                        root);
        assertEquals(2, catalog.size());
        var c1 = catalog.get(0);
        assertEquals("PLANNER_ALGO-1", c1.get("label"));
        assertNotNull(c1.get("started_at"));
        assertNull(c1.get("ended_at"));
        assertNull(c1.get("stop_reason"));
        Long registeredSeq =
                db.queryForObject(
                        "select seq from engine.session_event where session_id=? and type='subagent/registered'"
                            + " and data->>'label'='PLANNER_ALGO-1'",
                        Long.class,
                        root);
        assertEquals(registeredSeq, ((Number) c1.get("start_seq")).longValue(), "start_seq=登记事件 seq");
        // 子任务行：parent_task_id+role 必填（CHECK 兜底）、PLANNING、继承父钉住与预算。
        var sub = db.queryForMap("select * from engine.task where id=?", childTaskId(tid, 1));
        assertEquals(tid, ((Number) sub.get("parent_task_id")).longValue());
        assertEquals("PLANNER_ALGO", sub.get("role"));
        assertEquals("PLANNING", sub.get("state"));
        assertEquals("PLAN", sub.get("purpose"));
        assertEquals(String.valueOf(db.queryForMap("select budget from engine.task where id=?", tid).get("budget")),
                String.valueOf(sub.get("budget")), "子任务预算继承父预留");
        // 事件：父会话 subagent/registered ×2；父任务 SUBTASK_REGISTERED ×2。
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type='subagent/registered'",
                        Integer.class,
                        root));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.task_event where task_id=? and type='SUBTASK_REGISTERED'",
                        Integer.class,
                        tid));
        // 任务详情 subtasks 投影（接口稿 §5.2——任务卡消费）。
        var detail = tasks.detail(tid, actor);
        var subtasks = (java.util.List<?>) detail.get("subtasks");
        assertEquals(2, subtasks.size());
        var row = (Map<?, ?>) subtasks.get(0);
        assertEquals("PLANNER_ALGO", row.get("role"));
        assertEquals("PLANNING", row.get("state"));
        assertNull(row.get("merged_decision"), "未合并前 merged_decision 为空（无回执）");
    }

    // ---------------------------------------------------------------- 双成功一次合并

    /** 双成功：两子代理候选回执→合并恰一次（PLAN_READY+全 ADOPTED）；重复 merge 幂等返回原结果。 */
    @Test
    void doubleSuccessMergesExactlyOnceAndReplayIsIdempotent() {
        long tid = planTask("h27-r-double");
        var start = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        long c1 = childTaskId(tid, 1), c2 = childTaskId(tid, 2);
        long rev1 = candidate(c1, 1), rev2 = candidate(c2, 1);
        String s1 = String.valueOf(children(start).get(0).get("childSessionId"));
        String s2 = String.valueOf(children(start).get(1).get("childSessionId"));
        var r1 = planning.settle(s1, "COMPLETED", rev1, null);
        assertFalse((Boolean) r1.get("duplicate"));
        assertEquals("SUCCEEDED", r1.get("state"), "COMPLETED 回执同事务置子任务 SUCCEEDED");
        var r2 = planning.settle(s2, "COMPLETED", rev2, null);
        assertEquals("SUCCEEDED", r2.get("state"));
        // 回执单条落库：候选指向修订；result 对=plan-revision 引用+修订摘要（成功对非空）。
        var rec1 = receipt(c1);
        assertEquals(rev1, ((Number) rec1.get("candidate_revision_id")).longValue());
        assertEquals("PENDING", rec1.get("merged_decision"));
        assertEquals("plan-revision/" + rev1, rec1.get("result_ref"));
        String revDigest =
                db.queryForObject("select digest from engine.plan_revision where id=?", String.class, rev1);
        assertEquals(revDigest, rec1.get("result_digest"));
        // catalog 终态回填。
        var ended =
                db.queryForList(
                        "select stop_reason,ended_at from engine.subagent_catalog where parent_session_id=?"
                            + " order by label",
                        rootSession(tid));
        assertEquals("COMPLETED", ended.get(0).get("stop_reason"));
        assertNotNull(ended.get(0).get("ended_at"));
        // 合并恰一次：PLAN_READY+全 ADOPTED。
        var merged = planning.merge(tid);
        assertEquals("PLAN_READY", merged.get("parentState"));
        assertFalse((Boolean) merged.get("replay"));
        assertEquals(2, ((java.util.List<?>) merged.get("adopted")).size());
        assertEquals(0, ((java.util.List<?>) merged.get("discarded")).size());
        assertEquals("ADOPTED", receipt(c1).get("merged_decision"));
        assertEquals("ADOPTED", receipt(c2).get("merged_decision"));
        assertEquals("PLAN_READY", db.queryForObject("select state from engine.task where id=?", String.class, tid));
        // 重复 merge：幂等返回原结果，事实不重算（合并事实锁在 receipt 行）。
        var again = planning.merge(tid);
        assertTrue((Boolean) again.get("replay"));
        assertEquals("PLAN_READY", again.get("parentState"));
        assertEquals(2, ((java.util.List<?>) again.get("adopted")).size());
        assertEquals("ADOPTED", receipt(c1).get("merged_decision"), "重复合并不改写合并决定");
    }

    // ---------------------------------------------------------------- 单失败

    /** 单失败：按有效候选合并（ADOPTED×1），failed 的 error_code 落 receipt 且候选为空。 */
    @Test
    void singleFailureMergesByValidCandidateAndKeepsErrorCode() {
        long tid = planTask("h27-r-single");
        var start = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        long c1 = childTaskId(tid, 1), c2 = childTaskId(tid, 2);
        long rev1 = candidate(c1, 1);
        planning.settle(String.valueOf(children(start).get(0).get("childSessionId")), "COMPLETED", rev1, null);
        planning.settle(
                String.valueOf(children(start).get(1).get("childSessionId")), "ERROR", null, "MODEL_ERROR");
        var rec2 = receipt(c2);
        assertNull(rec2.get("candidate_revision_id"), "失败回执无候选");
        assertEquals("MODEL_ERROR", rec2.get("error_code"));
        assertNull(rec2.get("result_ref"));
        assertEquals("FAILED", db.queryForObject("select state from engine.task where id=?", String.class, c2));
        var merged = planning.merge(tid);
        assertEquals("PLAN_READY", merged.get("parentState"), "存在有效候选→PLAN_READY");
        assertEquals(1, ((java.util.List<?>) merged.get("adopted")).size());
        var discarded = (java.util.List<?>) merged.get("discarded");
        assertEquals(1, discarded.size());
        assertEquals("MODEL_ERROR", ((Map<?, ?>) discarded.get(0)).get("errorCode"));
        assertEquals("ADOPTED", receipt(c1).get("merged_decision"));
        assertEquals("DISCARDED", receipt(c2).get("merged_decision"));
    }

    // ---------------------------------------------------------------- 全失败

    /** 全失败：无有效候选→父任务 FAILED 原因码 SUBAGENT_NO_VALID_CANDIDATE（TASK_FINISHED 承载）。 */
    @Test
    void allFailuresMergeFailsParentWithNoValidCandidateReason() {
        long tid = planTask("h27-r-allfail");
        var start = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        long c1 = childTaskId(tid, 1), c2 = childTaskId(tid, 2);
        planning.settle(String.valueOf(children(start).get(0).get("childSessionId")), "ERROR", null, "MODEL_ERROR");
        planning.settle(
                String.valueOf(children(start).get(1).get("childSessionId")), "MAX_TOKENS", null, "TOKEN_BUDGET");
        var merged = planning.merge(tid);
        assertEquals("FAILED", merged.get("parentState"));
        assertEquals("SUBAGENT_NO_VALID_CANDIDATE", merged.get("reasonCode"));
        assertEquals(0, ((java.util.List<?>) merged.get("adopted")).size());
        assertEquals("FAILED", db.queryForObject("select state from engine.task where id=?", String.class, tid));
        assertEquals("DISCARDED", receipt(c1).get("merged_decision"));
        assertEquals("DISCARDED", receipt(c2).get("merged_decision"));
        // 原因码经 TASK_FINISHED 事件暴露（错误码已转正登记 contracts/error-codes.md §2）。
        var payload =
                db.queryForMap(
                        "select payload from engine.task_event where task_id=? and type='TASK_FINISHED'"
                            + " order by seq desc limit 1",
                        tid);
        var data = Json.read(String.valueOf(payload.get("payload")), JsonNode.class);
        assertEquals("FAILED", data.path("state").asText());
        assertEquals("SUBAGENT_NO_VALID_CANDIDATE", data.path("reasonCode").asText());
        // 终态后重复 merge：幂等返回原结果（父 FAILED 且合并事实已在 receipt 行）。
        var again = planning.merge(tid);
        assertTrue((Boolean) again.get("replay"));
        assertEquals("FAILED", again.get("parentState"));
        assertEquals("SUBAGENT_NO_VALID_CANDIDATE", again.get("reasonCode"));
    }

    // ---------------------------------------------------------------- 迟到/重复回执

    /** 迟到/重复回执：返回原记录+审计保留；已提交候选与 merged_decision 不被覆盖。 */
    @Test
    void duplicateAndLateReceiptsKeepAuditAndNeverOverwriteMergeDecision() {
        long tid = planTask("h27-r-late");
        var start = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        long c1 = childTaskId(tid, 1), c2 = childTaskId(tid, 2);
        long rev1 = candidate(c1, 1);
        String s1 = String.valueOf(children(start).get(0).get("childSessionId"));
        planning.settle(s1, "COMPLETED", rev1, null);
        planning.settle(
                String.valueOf(children(start).get(1).get("childSessionId")), "ERROR", null, "MODEL_ERROR");
        planning.merge(tid);
        var before = receipt(c1);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SubagentPlanning.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        appender.start();
        logger.addAppender(appender);
        try {
            // 重复回执（同子任务同结果，父已合并=迟到）：返回原记录，审计保留，不覆盖。
            var dup = planning.settle(s1, "COMPLETED", rev1, null);
            assertTrue((Boolean) dup.get("duplicate"));
            assertTrue((Boolean) dup.get("late"), "父任务已合并后到达→late 标记");
            // 冲突回执（同子任务异结果）：审计后仍返回原记录。
            long revOther = candidate(c1, 2);
            var conflict = planning.settle(s1, "COMPLETED", revOther, null);
            assertTrue((Boolean) conflict.get("conflict"));
            assertFalse((Boolean) conflict.get("duplicate"));
            assertTrue((Boolean) conflict.get("late"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        // 已提交候选与合并决定原样（库稿"重复回执返回原记录；迟到/冲突保留审计不覆盖"）。
        var after = receipt(c1);
        assertEquals(before.get("candidate_revision_id"), after.get("candidate_revision_id"));
        assertEquals("ADOPTED", after.get("merged_decision"));
        assertEquals(before.get("created_at"), after.get("created_at"), "回执行未被改写");
        var audited =
                appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertTrue(audited.stream().anyMatch(l -> l.contains("AUDIT SUBTASK_RECEIPT_LATE")), "迟到审计行: " + audited);
        assertTrue(audited.stream().anyMatch(l -> l.contains("AUDIT SUBTASK_RECEIPT_CONFLICT")), "冲突审计行: " + audited);
    }

    // ---------------------------------------------------------------- 目录分角色（接口稿 §5.3）

    private void identity(String tenant, String principal, List<String> roles) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new SubagentCatalogController(planning))
                .setControllerAdvice(new PlatformErrors())
                .build();
    }

    private JsonNode callGet(MockMvc mvc, String url, int expected) throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andReturn()
                .getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    /** 目录分角色：USER 本人父会话见展示摘要不回原始码；OPERATOR 见原因码；他人/他租 404；分页稳定。 */
    @Test
    void catalogIsRoleScopedAndStableSorted() throws Exception {
        long tid = planTask("h27-r-catalog");
        String root = rootSession(tid);
        var start = planning.startPlannerSubagents(tid, "PLANNER_ALGO", 2, SCHEMA);
        long c1 = childTaskId(tid, 1), c2 = childTaskId(tid, 2);
        String s1 = String.valueOf(children(start).get(0).get("childSessionId"));
        String s2 = String.valueOf(children(start).get(1).get("childSessionId"));
        planning.settle(s1, "COMPLETED", candidate(c1, 1), null);
        planning.settle(s2, "ERROR", null, "MODEL_ERROR");
        var mvc = mvc();
        String url = "/v1/sessions/" + root + "/subagents";
        // 本人（USER）：200，两子代理，status=终因；不回原始 stopReason，见展示摘要。
        identity(TENANT, "h27-alice", List.of("USER"));
        var data = callGet(mvc, url, 200).path("data");
        var kids = data.path("children");
        assertEquals(2, kids.size());
        var bySession = new HashMap<String, JsonNode>();
        kids.forEach(k -> bySession.put(k.path("childSessionId").asText(), k));
        assertEquals("COMPLETED", bySession.get(s1).path("status").asText());
        assertEquals("ERROR", bySession.get(s2).path("status").asText());
        assertTrue(bySession.get(s1).has("stopDisplay"), "USER 见停因展示摘要");
        assertEquals("已完成", bySession.get(s1).path("stopDisplay").asText());
        assertFalse(bySession.get(s1).has("stopReason"), "USER 不回原始原因码");
        assertFalse(bySession.get(s1).path("childTaskId").asText().isEmpty(), "目录携带子任务号（任务卡关联）");
        assertTrue(bySession.get(s1).path("durationMs").asLong() >= 0, "终态子代理回 durationMs");
        // OPERATOR（本租户）：可见终态原因码。
        identity(TENANT, "op-1", List.of("OPERATOR"));
        var opView = callGet(mvc, url, 200).path("data");
        var opKids = opView.path("children");
        boolean rawSeen = false;
        for (var k : opKids) if (k.has("stopReason") && "COMPLETED".equals(k.path("stopReason").asText())) rawSeen = true;
        assertTrue(rawSeen, "OPERATOR 见终态原因码");
        // 他人父会话：404（不泄露存在性）。
        identity(TENANT, "bob", List.of("USER"));
        assertEquals("SESSION_NOT_FOUND", callGet(mvc, url, 404).path("code").asText());
        // 他租 OPERATOR：404。
        identity("h27x", "op-2", List.of("OPERATOR"));
        assertEquals("SESSION_NOT_FOUND", callGet(mvc, url, 404).path("code").asText());
        // 分页：after/limit 稳定（startedAt,childSessionId 排序），nextAfter 取尽为 null。
        identity(TENANT, "h27-alice", List.of("USER"));
        var page1 = callGet(mvc, url + "?limit=1", 200).path("data");
        assertEquals(1, page1.path("children").size());
        assertEquals(1, page1.path("nextAfter").asInt());
        var page2 = callGet(mvc, url + "?after=1&limit=1", 200).path("data");
        assertEquals(1, page2.path("children").size());
        assertTrue(page2.path("nextAfter").isNull(), "取尽 nextAfter=null");
        assertNotEquals(
                page1.path("children").get(0).path("childSessionId").asText(),
                page2.path("children").get(0).path("childSessionId").asText(),
                "两页不重不漏");
        // 非法游标/limit 400。
        assertEquals("SUBAGENTS_CURSOR_INVALID", callGet(mvc, url + "?after=x", 400).path("code").asText());
        assertEquals("SUBAGENTS_LIMIT_INVALID", callGet(mvc, url + "?limit=0", 400).path("code").asText());
        assertEquals("SUBAGENTS_LIMIT_INVALID", callGet(mvc, url + "?limit=201", 400).path("code").asText());
    }

    /** 网关/authority 登记（照 H1-9 目录行断言方式）：subagents 查询恰登记一行且指向本卡端点。 */
    @Test
    void gatewayCatalogRegistersSubagentsQueryRoute() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog = Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            var matches = new ArrayList<JsonNode>();
            for (var line : catalog)
                if ("GET".equals(line.path("method").asText())
                        && "/v1/sessions/{id}/subagents".equals(line.path("template").asText())
                        && "engine".equals(line.path("target").asText())
                        && "gateway".equals(line.path("caller").asText())
                        && "delegated".equals(line.path("mode").asText())) {
                    matches.add(line);
                    assertEquals(
                            "proxy.engine.get.SubagentCatalogController.subagents",
                            line.path("action").asText(),
                            "委派动作必须指向本端点");
                    assertEquals(
                            "engine-runtime/src/main/java/cn/agentplatform/engine/api/SubagentCatalogController.java:25",
                            line.path("source").asText(),
                            "目录行 source 指向本卡 handler");
                    assertFalse(line.path("integrationAllowed").asBoolean(false), "SERVICE 票据面不开放目录查询");
                }
            assertEquals(1, matches.size(), "GET /v1/sessions/{id}/subagents 必须恰登记一行: " + matches);
        }
    }
}
