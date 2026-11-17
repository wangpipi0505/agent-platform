package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.ConfirmationCenterController;
import cn.agentplatform.engine.api.TaskController;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * B4-1（台账 §7.5）：确认中心四类计数/任务列表 confirmableBy=me/plan-import 画布桥。
 *
 * <p>验收映射（接口稿 §5.7+前端稿 §5.9 四类待办矩阵）：①四类计数投影与 total=Σ（含可见但
 * 不可办理的待审发布——APPROVER 只读计入 publishReview；高危与 InlineApproval 列表共用
 * 确认角色口径）；②appCode 过滤收窄四类计数；③越权 403 记审计（CONFIRMATION_CENTER_
 * FORBIDDEN 结构化日志行，引擎无独立审计表归口 monitor）；④confirmableBy=me 仅返回当前
 * 无有效凭据（未确认或最大 confirm_seq 过期）的 VALID 头修订——已确认且在有效期内不出
 * 现（刷新不回流），且 counts.planConfirmation 与列表未分页总数一致（角标契约）；⑤
 * plan-import 受限映射（AGENT/SKILL→CODE 节点+target_ref 标注）与不可表达如实降级
 * （KIND_NOT_EXPORTABLE 阻断/FIELD_NOT_EXPRESSIBLE 留痕/digest 不符 409）。
 *
 * <p>夹具一律 h41_% 前缀双向清理照 TasksApiTest（含 app.integration_config_release 最小
 * 兼容表：发布表归 app 服务持有，引擎测试库仅建计数 SQL 引用的列——生产为完整 V113 表，
 * 如实登记）；照 MemoryApiTest 的 standalone MockMvc 先例（真实 Controller+服务+库）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ConfirmationCenterTest {

    static JdbcTemplate db;
    static TraceStore store;
    static Tasks tasks;
    static Plans plans;
    static ApprovalService approvals;
    static ConfirmationCenterService center;
    static MockMvc mvc;

    static final String TENANT = "h41-t";
    static final Actor ADMIN = new Actor(TENANT, "h41-admin", Set.of("ADMIN"), Map.of());
    static final Actor APPROVER = new Actor(TENANT, "h41-approver", Set.of("APPROVER"), Map.of());
    static final Actor ALICE = new Actor(TENANT, "h41-alice", Set.of("BUILDER"), Map.of());
    static final Actor BOB = new Actor(TENANT, "h41-bob", Set.of("BUILDER"), Map.of());
    static final Actor USER = new Actor(TENANT, "h41-user", Set.of("USER"), Map.of());

    @BeforeAll
    static void connect() {
        var source =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure()
                .dataSource(source)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(source);
        store = new TraceStore(db, new DataSourceTransactionManager(source));
        // app.integration_config_release 最小兼容表（见类 javadoc 如实登记；幂等建表不清理）。
        db.execute("create schema if not exists app");
        db.execute(
                "create table if not exists app.integration_config_release ("
                    + "tenant_id varchar(128) not null, release_id varchar(64) not null,"
                    + "app_code varchar(64) not null, status varchar(16) not null,"
                    + "created_by varchar(128) not null, created_at timestamptz not null default now(),"
                    + "primary key(tenant_id, release_id))");
        tasks = new Tasks(store);
        plans = new Plans(store);
        approvals = new ApprovalService(store);
        center = new ConfirmationCenterService(store, approvals, tasks);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConfirmationCenterController(center),
                                new TaskController(tasks, plans, new TaskReconciliation(store)))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
    }

    /** 双向清理：仅 h41_% 夹具（tenant 前缀收口，engine 四域+app 发布计数表）。 */
    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        // FK 链与不可变表清理窗口照 PlansApiTest 先例（DISABLE→删→ENABLE 恢复守卫）。
        db.update("update engine.task set selected_plan_revision_id=null where tenant_id like 'h41-t%'");
        db.update("delete from engine.plan_confirmation where tenant_id like 'h41-t%'");
        db.execute("ALTER TABLE engine.plan_step DISABLE TRIGGER plan_step_immutable_guard");
        db.update("delete from engine.plan_step where plan_revision_id in"
                + "(select id from engine.plan_revision where tenant_id like 'h41-t%')");
        db.execute("ALTER TABLE engine.plan_step ENABLE TRIGGER plan_step_immutable_guard");
        db.execute("ALTER TABLE engine.plan_revision DISABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.plan_revision where tenant_id like 'h41-t%'");
        db.execute("ALTER TABLE engine.plan_revision ENABLE TRIGGER plan_revision_immutable_guard");
        db.update("delete from engine.node_instance where run_id in"
                + "(select id from engine.run where tenant_id like 'h41-t%')");
        db.update("delete from engine.run where tenant_id like 'h41-t%'");
        db.update("delete from engine.memory_entry where tenant_id like 'h41-t%'");
        db.update("delete from engine.task where tenant_id like 'h41-t%'");
        db.update("delete from app.integration_config_release where tenant_id like 'h41-t%'");
    }

    private void identity(Actor actor) {
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject(actor.principalId())
                        .claim("tenant_id", actor.tenantId())
                        .claim("roles", List.copyOf(actor.roles()))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private JsonNode call(String method, String url, String body, int expected) throws Exception {
        var builder =
                switch (method) {
                    case "GET" -> get(url);
                    case "POST" -> post(url).contentType(MediaType.APPLICATION_JSON)
                            .content(body == null ? "{}" : body);
                    default -> throw new IllegalArgumentException(method);
                };
        var response = mvc.perform(builder).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return Json.read(response.getContentAsString());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 任务夹具（列照 V230 NOT NULL 约束；digest 喂合法十六进制）。 */
    private String task(String principal, String code, String state, String goal) {
        String id = "h41_task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String digest = Digest.sha256("h41-" + id);
        db.update(
                "insert into engine.task(public_id,tenant_id,principal_id,app_code,"
                    + "config_release_id,config_digest,mode,goal,input_ref,input_digest,state,"
                    + "request_id,request_digest)"
                    + " values(?,?,?,?,'h41-rel',?,'PLAN',?,'h41-in',?,?,'h41-req-'||?,?)",
                id,
                TENANT,
                principal,
                code,
                digest,
                goal,
                digest,
                state,
                id,
                digest);
        return id;
    }

    private long planRevision(String planId, int revision, String taskPublicId, String status) {
        long taskId =
                db.queryForObject(
                        "select id from engine.task where public_id=?", Long.class, taskPublicId);
        String digest = Digest.sha256("h41-plan-" + planId + "-" + revision);
        db.update(
                "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,"
                    + "base_revision,schema_version,goal_spec,validation_status,digest,created_by)"
                    + " values(?,?,?,?,0,'1.0','{}'::jsonb,?,?,?)",
                TENANT,
                planId,
                revision,
                taskId,
                status,
                digest,
                principalOf(taskPublicId));
        return db.queryForObject(
                "select id from engine.plan_revision where tenant_id=? and plan_id=? and revision=?",
                Long.class,
                TENANT,
                planId,
                revision);
    }

    private String principalOf(String taskPublicId) {
        return db.queryForObject(
                "select principal_id from engine.task where public_id=?", String.class, taskPublicId);
    }

    private void planStep(
            long revisionId,
            String stepId,
            String kind,
            String targetRef,
            String dependsOnJson,
            String inputBindingsJson,
            String failurePolicyJson) {
        db.update(
                "insert into engine.plan_step(plan_revision_id,step_id,depends_on,kind,target_ref,"
                    + "input_bindings,required,failure_policy)"
                    + " values(?,?,?::jsonb,?,?,?::jsonb,true,?::jsonb)",
                revisionId,
                stepId,
                dependsOnJson,
                kind,
                targetRef,
                inputBindingsJson,
                failurePolicyJson);
    }

    private void confirmation(long revisionId, int seq, String by, OffsetDateTime expiresAt) {
        db.update(
                "insert into engine.plan_confirmation(plan_revision_id,confirm_seq,tenant_id,"
                    + "confirm_request_id,confirmed_by,confirmed_at,expires_at,confirmed_digest)"
                    + " values(?,?,?,?,?,now(),?,?)",
                revisionId,
                seq,
                TENANT,
                "h41-req-" + revisionId + "-" + seq,
                by,
                expiresAt,
                Digest.sha256("h41-confirm-" + revisionId + "-" + seq));
    }

    private void release(String appCode, String releaseId, String status, String createdBy) {
        db.update(
                "insert into app.integration_config_release(tenant_id,release_id,app_code,status,created_by)"
                    + " values(?,?,?,?,?)",
                TENANT,
                releaseId,
                appCode,
                status,
                createdBy);
    }

    /** InlineApproval 待审节点夹具（definition.confirmRoles 承载确认角色；无 attempt 快照行）。 */
    private void waitingApproval(String appCode, String confirmRole, String runOwner) {
        String runId = "h41_run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        db.update(
                "insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,"
                    + "principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,"
                    + "event_seq,request_id)"
                    + " values(?,?,42,'TASK','WAITING_APPROVAL',?,?,'h41-in','{}'::jsonb,'{}'::jsonb,"
                    + "'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,0,?)",
                runId,
                appCode,
                runOwner,
                TENANT,
                "h41-runreq-" + runId);
        long rid = db.queryForObject("select id from engine.run where public_id=?", Long.class, runId);
        db.update(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,"
                    + "definition,approval_context,started_at)"
                    + " values(?,?,1,'CONFIRM','WAITING_APPROVAL',?::jsonb,'{}'::jsonb,now())",
                rid,
                "h41_node_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "{\"confirmRoles\":[\"" + confirmRole + "\"],\"kind\":\"CONFIRM\"}");
    }

    private void memory(String appCode, String scope, String owner, String status) {
        db.update(
                "insert into engine.memory_entry(memory_id,tenant_id,workspace_code,scope,owner_id,"
                    + "kind,content,source_kind,version,status)"
                    + " values(?,?,?,?,?,'PREFERENCE',?,'USER_EXPLICIT',1,?)",
                "h41_mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                TENANT,
                appCode,
                scope,
                owner,
                "h41 记忆内容",
                status);
    }

    // ------------------------------------------------------------------
    // ① 四类计数投影 + total=Σ + ② appCode 过滤 + ③ 越权 403 审计
    // ------------------------------------------------------------------

    /** 标准四域夹具：发布 3（1 个在 app2）+待审节点 2+计划修订 4 态+记忆 3。 */
    private void fourDomainFixtures() {
        release("h41-app1", "h41-rel-v1", "VALIDATED", "h41-alice");
        release("h41-app1", "h41-rel-v2", "APPROVED", "h41-alice");
        release("h41-app1", "h41-rel-v3", "ACTIVE", "h41-alice");
        release("h41-app2", "h41-rel-v4", "VALIDATED", "h41-bob");
        waitingApproval("h41-app1", "ADMIN", "h41-alice");
        waitingApproval("h41-app1", "APPROVER", "h41-alice");
        waitingApproval("h41-app2", "ADMIN", "h41-bob");
        // 计划确认四态：A 未确认（计）/B 有效凭据（不计）/C 过期凭据（计）/D INVALID（不计）
        String taskA = task("h41-alice", "h41-app1", "PLAN_READY", "h41 目标 A");
        long revA = planRevision("h41-plan-a", 1, taskA, "VALID");
        String taskB = task("h41-bob", "h41-app2", "PLAN_READY", "h41 目标 B");
        long revB = planRevision("h41-plan-b", 1, taskB, "VALID");
        confirmation(revB, 1, "h41-bob", OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));
        String taskC = task("h41-bob", "h41-app2", "PLAN_READY", "h41 目标 C");
        long revC = planRevision("h41-plan-c", 1, taskC, "VALID");
        confirmation(revC, 1, "h41-bob", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
        String taskD = task("h41-alice", "h41-app1", "PLAN_READY", "h41 目标 D");
        planRevision("h41-plan-d", 1, taskD, "INVALID");
        // DRAFT 记忆：共享（ADMIN 可见）+bob 私有+admin 私有+1 条 ACTIVE（不计）
        memory("h41-app1", "SHARED", "h41-alice", "DRAFT");
        memory("h41-app2", "PRIVATE", "h41-bob", "DRAFT");
        memory("h41-app1", "PRIVATE", "h41-admin", "DRAFT");
        memory("h41-app1", "SHARED", "h41-alice", "ACTIVE");
    }

    @Test
    void fourClassCountsByRoleWithTotalSumAndReadOnlyPublishVisibility() throws Exception {
        fourDomainFixtures();
        // ADMIN：发布 3（VALIDATED+APPROVED 计、ACTIVE 不计）、高危 2、计划确认 2、DRAFT 记忆 2
        // （共享 1+本人私有 1；他人私有不计）
        identity(ADMIN);
        var counts = call("GET", "/admin/confirmation-center/counts", null, 200).path("data");
        assertEquals(3, counts.path("publishReview").asInt(), "VALIDATED+APPROVED 计数，ACTIVE 不计");
        assertEquals(2, counts.path("highRisk").asInt(), "仅 confirmRoles 含 ADMIN 的待审节点可见（被确认人口径）");
        assertEquals(2, counts.path("planConfirmation").asInt(), "A 未确认+C 过期；B 有效/D INVALID 不计");
        assertEquals(2, counts.path("draftMemory").asInt(), "共享层 ADMIN+本人私有");
        assertEquals(
                counts.path("publishReview").asInt()
                        + counts.path("highRisk").asInt()
                        + counts.path("planConfirmation").asInt()
                        + counts.path("draftMemory").asInt(),
                counts.path("total").asInt(),
                "total=四项之和");
        // APPROVER：待审发布只读可见计入（可见但不可办理——审批动作 ADMIN 门）；高危按
        // confirmRoles=[APPROVER] 仅 1；计划确认租户级 2；DRAFT 记忆共享层非 ADMIN 不计、
        // 本人无私有条目→0。
        identity(APPROVER);
        var approver = call("GET", "/admin/confirmation-center/counts", null, 200).path("data");
        assertEquals(3, approver.path("publishReview").asInt(), "APPROVER 只读可见的待审发布计入");
        assertEquals(1, approver.path("highRisk").asInt());
        assertEquals(2, approver.path("planConfirmation").asInt());
        assertEquals(0, approver.path("draftMemory").asInt(), "共享层=ADMIN 口径，APPROVER 零");
        assertEquals(
                approver.path("publishReview").asInt()
                        + approver.path("highRisk").asInt()
                        + approver.path("planConfirmation").asInt()
                        + approver.path("draftMemory").asInt(),
                approver.path("total").asInt());
        // BUILDER（提交人本人口径）：发布限本人 created_by（bob 的 app2 发布不可见）→2
        //（本人 VALIDATED v1+APPROVED v2；ACTIVE v3 不计）；计划确认限本人任务→1（A）；
        // DRAFT 记忆限本人→1（SHARED owner=alice）。
        identity(ALICE);
        var alice = call("GET", "/admin/confirmation-center/counts", null, 200).path("data");
        assertEquals(2, alice.path("publishReview").asInt(), "BUILDER 非提交人的发布不可见，本人未完结两条计入");
        assertEquals(0, alice.path("highRisk").asInt(), "confirmRoles 不含 BUILDER 不可见");
        assertEquals(1, alice.path("planConfirmation").asInt());
        assertEquals(1, alice.path("draftMemory").asInt());
        assertEquals(
                alice.path("publishReview").asInt()
                        + alice.path("highRisk").asInt()
                        + alice.path("planConfirmation").asInt()
                        + alice.path("draftMemory").asInt(),
                alice.path("total").asInt());
    }

    @Test
    void appCodeFilterNarrowsAllFourCounts() throws Exception {
        fourDomainFixtures();
        identity(ADMIN);
        var scoped = call("GET", "/admin/confirmation-center/counts?appCode=h41-app1", null, 200)
                .path("data");
        assertEquals(2, scoped.path("publishReview").asInt(), "app2 的 VALIDATED 发布被滤除");
        assertEquals(1, scoped.path("highRisk").asInt(), "app2 的 ADMIN 节点被滤除");
        assertEquals(1, scoped.path("planConfirmation").asInt(), "仅 A（app1）");
        assertEquals(2, scoped.path("draftMemory").asInt(), "app1 共享+admin 私有");
        assertEquals(
                scoped.path("publishReview").asInt()
                        + scoped.path("highRisk").asInt()
                        + scoped.path("planConfirmation").asInt()
                        + scoped.path("draftMemory").asInt(),
                scoped.path("total").asInt());
        assertEquals(400, mvc.perform(get("/admin/confirmation-center/counts?appCode=h41%2Fbad"))
                        .andReturn().getResponse().getStatus(), "appCode 形状非法 400");
    }

    @Test
    void unauthorizedRoleForbiddenWithAuditTrail() throws Exception {
        identity(USER);
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ConfirmationCenterService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = mvc.perform(get("/admin/confirmation-center/counts")).andReturn().getResponse();
            assertEquals(403, response.getStatus(), response.getContentAsString());
            assertEquals("CONFIRMATION_CENTER_FORBIDDEN", Json.read(response.getContentAsString()).path("code").asText());
        } finally {
            logger.detachAppender(appender);
        }
        assertTrue(
                appender.list.stream()
                        .anyMatch(line -> line.getFormattedMessage().contains("AUDIT CONFIRMATION_CENTER_FORBIDDEN")
                                && line.getFormattedMessage().contains("principal=h41-user")),
                "越权拒绝必须落结构化审计日志行");
        // OPERATOR 无管理台确认中心入口（§5.9 读取角色=ADMIN/APPROVER/BUILDER）→同 403。
        identity(new Actor(TENANT, "h41-operator", Set.of("OPERATOR"), Map.of()));
        assertEquals(403, mvc.perform(get("/admin/confirmation-center/counts"))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------
    // ④ confirmableBy=me 投影 + 任务列表
    // ------------------------------------------------------------------

    @Test
    void confirmableByMeReturnsOnlyRevisionsWithoutValidCredentialAndMatchesBadge() throws Exception {
        fourDomainFixtures();
        identity(ADMIN);
        var data = call("GET", "/v1/tasks?confirmableBy=me", null, 200).path("data");
        var items = data.path("items");
        assertEquals(2, items.size(), "仅 A（未确认）与 C（过期）两条头修订");
        var byPlan = new HashMap<String, JsonNode>();
        for (var item : items) byPlan.put(item.path("planId").asText(), item);
        assertTrue(byPlan.containsKey("h41-plan-a"), "UNCONFIRMED 修订在待办");
        assertEquals("UNCONFIRMED", byPlan.get("h41-plan-a").path("confirmState").asText());
        assertTrue(byPlan.containsKey("h41-plan-c"), "CONFIRMED_EXPIRED 修订回流待办");
        assertEquals("CONFIRMED_EXPIRED", byPlan.get("h41-plan-c").path("confirmState").asText());
        assertEquals("h41-bob", byPlan.get("h41-plan-c").path("confirmedBy").asText());
        assertFalse(byPlan.get("h41-plan-c").path("expiresAt").asText().isBlank());
        assertFalse(byPlan.containsKey("h41-plan-b"), "有效凭据修订不出现（刷新不回流）");
        assertFalse(byPlan.containsKey("h41-plan-d"), "INVALID 修订不可确认不入待办");
        assertTrue(byPlan.get("h41-plan-a").path("digest").asText().matches("[0-9a-f]{64}"));
        assertFalse(byPlan.get("h41-plan-a").path("goal").asText().isBlank(), "每项含 goal 摘要");
        // 角标契约：counts.planConfirmation 与列表未分页总数一致。
        var counts = call("GET", "/admin/confirmation-center/counts", null, 200).path("data");
        assertEquals(items.size(), counts.path("planConfirmation").asInt(), "角标与列表未分页总数一致");
        // appCode 过滤同源：仅 app1 → 1 条。
        var scoped = call("GET", "/v1/tasks?confirmableBy=me&appCode=h41-app1", null, 200).path("data");
        assertEquals(1, scoped.path("items").size());
        // BUILDER 本人口径：alice 仅 A。
        identity(ALICE);
        assertEquals(1, call("GET", "/v1/tasks?confirmableBy=me", null, 200).path("data").path("items").size());
        identity(BOB);
        var bob = call("GET", "/v1/tasks?confirmableBy=me", null, 200).path("data");
        assertEquals(1, bob.path("items").size(), "B（有效）不回流，C（过期）计入");
        assertEquals("h41-plan-c", bob.path("items").get(0).path("planId").asText());
        // confirmableBy 仅支持 me。
        identity(ALICE);
        assertEquals(400, mvc.perform(get("/v1/tasks?confirmableBy=other"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void taskListScopesAndFiltersBeforePaging() throws Exception {
        fourDomainFixtures();
        // state 过滤 + 范围：alice 仅本人任务（4 个 PLAN_READY 中本人 2 个）。
        identity(ALICE);
        var own = call("GET", "/v1/tasks?state=PLAN_READY", null, 200).path("data");
        assertEquals(2, own.path("total").asInt(), "BUILDER 范围=提交人本人，过滤先于计数");
        assertEquals(2, own.path("tasks").size());
        // principalId 自报范围：非 ADMIN 拒绝。
        assertEquals(403, mvc.perform(get("/v1/tasks?principalId=h41-bob"))
                .andReturn().getResponse().getStatus());
        // ADMIN 租户范围 + state 过滤：4 个 PLAN_READY。
        identity(ADMIN);
        var tenant = call("GET", "/v1/tasks?state=PLAN_READY&pageSize=2", null, 200).path("data");
        assertEquals(4, tenant.path("total").asInt(), "total=未分页总数（与角标一致性契约同源）");
        assertEquals(2, tenant.path("tasks").size(), "pageSize=2 分页但 total 不缩水");
        // USER 无确认中心入口但任务列表本人可见（0——本人无任务）。
        identity(USER);
        assertEquals(0, call("GET", "/v1/tasks", null, 200).path("data").path("total").asInt());
    }

    // ------------------------------------------------------------------
    // ⑤ plan-import 受限映射与不可表达降级
    // ------------------------------------------------------------------

    private String planImportBody(String planId, int revision, String taskPublicId) {
        String digest =
                db.queryForObject(
                        "select pr.digest from engine.plan_revision pr join engine.task t on t.id=pr.owner_task_id"
                            + " where t.public_id=? and pr.plan_id=? and pr.revision=?",
                        String.class,
                        taskPublicId,
                        planId,
                        revision);
        return "{\"planId\":\"" + planId + "\",\"revision\":" + revision
                + ",\"digest\":\"" + digest + "\",\"draftBaselineRev\":7}";
    }

    @Test
    void planImportMapsAgentAndSkillStepsToCodeNodesAndDegradesRestHonestly() throws Exception {
        String taskA = task("h41-alice", "h41-app1", "PLAN_READY", "h41 导入目标");
        long rev = planRevision("h41-import", 1, taskA, "VALID");
        planStep(rev, "s1", "AGENT", "agent:alpha@1", "[]", "{}", "{}");
        planStep(rev, "s2", "SKILL", "skill:beta@2", "[\"s1\"]", "{\"a\":1}", "{\"onFail\":\"TERMINATE\"}");
        planStep(rev, "s3", "FLOW", "flow:gamma@1", "[\"s2\"]", "{}", "{}");
        planStep(rev, "s4", "AGENT", "agent:delta@1", "[\"s3\"]", "{}", "{}");
        identity(ALICE);
        var data =
                call("POST", "/admin/apps/h41-app1/draft/plan-import", planImportBody("h41-import", 1, taskA), 200)
                        .path("data");
        var nodes = data.path("nodeMapping").path("nodes");
        assertEquals(3, nodes.size(), "AGENT/SKILL 三步映射为 CODE 节点（s3 FLOW 降级不入）");
        assertEquals("n_s1", nodes.get(0).path("nodeId").asText());
        assertEquals("CODE", nodes.get(0).path("type").asText(), "一期节点族无 AGENT 类型→受限映射 CODE");
        assertEquals("agent:alpha@1", nodes.get(0).path("targetRef").asText(), "target_ref 随节点标注");
        assertEquals("skill:beta@2", nodes.get(1).path("targetRef").asText());
        var edges = data.path("nodeMapping").path("edges");
        assertEquals(1, edges.size());
        assertEquals("n_s1", edges.get(0).path("from").asText());
        assertEquals("n_s2", edges.get(0).path("to").asText());
        var issues = data.path("validation").path("issues");
        assertEquals(false, data.path("validation").path("ok").asBoolean(), "存在不可表达步骤→ok=false");
        boolean kindIssue = false;
        boolean bindingIssue = false;
        boolean policyIssue = false;
        boolean dependencyIssue = false;
        for (var issue : issues) {
            if ("KIND_NOT_EXPORTABLE".equals(issue.path("code").asText())
                    && "s3".equals(issue.path("stepId").asText())
                    && "BLOCK".equals(issue.path("severity").asText())) kindIssue = true;
            if ("FIELD_NOT_EXPRESSIBLE".equals(issue.path("code").asText())
                    && "s2".equals(issue.path("stepId").asText())
                    && "inputBindings".equals(issue.path("field").asText())) bindingIssue = true;
            if ("FIELD_NOT_EXPRESSIBLE".equals(issue.path("code").asText())
                    && "s2".equals(issue.path("stepId").asText())
                    && "failurePolicy".equals(issue.path("field").asText())) policyIssue = true;
            if ("DEPENDENCY_DROPPED".equals(issue.path("code").asText())
                    && "WARN".equals(issue.path("severity").asText())) dependencyIssue = true;
        }
        assertTrue(kindIssue, "不可表达步骤返回 stepId+BLOCK 如实降级");
        assertTrue(bindingIssue, "不支持字段 inputBindings 留痕");
        assertTrue(policyIssue, "不支持字段 failurePolicy 留痕");
        assertTrue(dependencyIssue, "指向未导入步骤的连线丢弃留痕");
        assertEquals(7, data.path("draftRev").asInt(), "骨架预览不写草稿——draftRev 回显基线");
        // 全可表达计划→ok=true 且零 issue。
        String taskE = task("h41-alice", "h41-app1", "PLAN_READY", "h41 干净导入");
        long revE = planRevision("h41-clean", 1, taskE, "VALID");
        planStep(revE, "k1", "AGENT", "agent:clean@1", "[]", "{}", "{}");
        var clean =
                call("POST", "/admin/apps/h41-app1/draft/plan-import", planImportBody("h41-clean", 1, taskE), 200)
                        .path("data");
        assertTrue(clean.path("validation").path("ok").asBoolean());
        assertEquals(0, clean.path("validation").path("issues").size());
        assertEquals(1, clean.path("nodeMapping").path("nodes").size());
    }

    @Test
    void planImportGuardsDigestOwnershipAndRole() throws Exception {
        String taskA = task("h41-alice", "h41-app1", "PLAN_READY", "h41 守卫目标");
        planRevision("h41-guard", 1, taskA, "VALID");
        identity(ALICE);
        // digest 与固化版本不符 → 409（与 confirm/execute 同语义）。
        assertEquals(409, mvc.perform(post("/admin/apps/h41-app1/draft/plan-import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"h41-guard\",\"revision\":1,\"digest\":\""
                                + "0".repeat(64) + "\",\"draftBaselineRev\":1}"))
                .andReturn().getResponse().getStatus());
        // 修订不存在或非提交人 → 404（bob 读不到 alice 的修订）。
        String digest =
                db.queryForObject(
                        "select digest from engine.plan_revision where tenant_id=? and plan_id=? and revision=1",
                        String.class, TENANT, "h41-guard");
        identity(BOB);
        assertEquals(404, mvc.perform(post("/admin/apps/h41-app2/draft/plan-import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"h41-guard\",\"revision\":1,\"digest\":\"" + digest
                                + "\",\"draftBaselineRev\":1}"))
                .andReturn().getResponse().getStatus());
        // ADMIN 对 plan-import 只读（§6 矩阵）→403；USER 无角色 →403。
        identity(ADMIN);
        assertEquals(403, mvc.perform(post("/admin/apps/h41-app1/draft/plan-import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"h41-guard\",\"revision\":1,\"digest\":\"" + digest
                                + "\",\"draftBaselineRev\":1}"))
                .andReturn().getResponse().getStatus());
        identity(USER);
        assertEquals(403, mvc.perform(post("/admin/apps/h41-app1/draft/plan-import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"h41-guard\",\"revision\":1,\"digest\":\"" + digest
                                + "\",\"draftBaselineRev\":1}"))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------
    // ⑥ 网关/authority 登记（照 H1-9 目录行断言方式）
    // ------------------------------------------------------------------

    @Test
    void gatewayCatalogRegistersThreeB4Routes() throws Exception {
        try (var input = getClass().getResourceAsStream("/agent-service-authority.json")) {
            assertNotNull(input, "agent-service-authority.json 必须在测试类路径上（admin-common 依赖）");
            var catalog = Json.read(new String(input.readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
            assertCatalogRow(catalog, "GET", "/v1/tasks", "proxy.engine.get.TaskController.list",
                    "engine-runtime/src/main/java/cn/agentplatform/engine/api/TaskController.java:43");
            assertCatalogRow(catalog, "GET", "/admin/confirmation-center/counts",
                    "proxy.engine.get.ConfirmationCenterController.counts",
                    "engine-runtime/src/main/java/cn/agentplatform/engine/api/ConfirmationCenterController.java:23");
            assertCatalogRow(catalog, "POST", "/admin/apps/{code}/draft/plan-import",
                    "proxy.engine.post.TaskController.planImport",
                    "engine-runtime/src/main/java/cn/agentplatform/engine/api/TaskController.java:160");
        }
    }

    private static void assertCatalogRow(JsonNode catalog, String method, String template,
            String action, String source) {
        var matches = new ArrayList<JsonNode>();
        for (var line : catalog)
            if (method.equals(line.path("method").asText())
                    && template.equals(line.path("template").asText())
                    && "engine".equals(line.path("target").asText())
                    && "gateway".equals(line.path("caller").asText())
                    && "delegated".equals(line.path("mode").asText())) {
                matches.add(line);
                assertEquals(action, line.path("action").asText(), "委派动作必须指向本端点");
                assertEquals(source, line.path("source").asText(), "目录行 source 指向本卡 handler");
                assertFalse(line.path("integrationAllowed").asBoolean(false), "SERVICE 票据面不开放");
            }
        assertEquals(1, matches.size(), template + " 必须恰登记一行: " + matches);
    }
}
