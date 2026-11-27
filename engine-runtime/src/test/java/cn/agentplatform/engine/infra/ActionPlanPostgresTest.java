package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PLAT-15（方案 §4.15 / 契约 §14）：写操作两段的引擎侧验收——actionPlan 盖章与 V221 唯一约束、
 * EXECUTE 的 digest/过期/确认强度三重门（全部"拒绝即无副作用"）、高危双主体审批、审计回写，
 * 以及旧技能（无 approval 声明）缺省 SINGLE_STEP 的零回归锚点。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ActionPlanPostgresTest {
    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final Actor approver = new Actor("engine-test", "bob", Set.of("APPROVER"), Map.of());
    static final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

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
                .outOfOrder(true) // 一次性引导：共享测试库已应用 V223（配额窗口），V221 需按序补齐
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() {
        db.update(
                "delete from engine.action_plan where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update(
                "delete from engine.model_invocation where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                    + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.run_event where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update(
                "delete from engine.attempt_note where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                    + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.tool_call where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update(
                "delete from engine.attempt where node_instance_id in(select n.id from"
                    + " engine.node_instance n join engine.run r on r.id=n.run_id where"
                    + " r.tenant_id='engine-test')");
        db.update(
                "delete from engine.node_instance where run_id in(select id from engine.run where"
                    + " tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    /** 冻结写技能的角色与风险声明。 */
    long run(List<String> confirmRoles) {
        return run(confirmRoles, "STANDARD");
    }

    long run(List<String> confirmRoles, String risk) {
        long id =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'test','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}','{}','{}','STEP')"
                            + " returning id",
                        Long.class,
                        "r_ap_" + seq.incrementAndGet());
        var tool = new LinkedHashMap<String, Object>();
        tool.put("code", "lookup");
        tool.put("skillVersionId", 42);
        tool.put("contractDigest", Digest.sha256("fixture-contract"));
        tool.put("execution", Map.of("confirmRoles", confirmRoles, "approval", Map.of("risk", risk)));
        db.update(
                "update engine.run set snapshot=?::jsonb where id=?",
                Json.write(Map.of("config", Map.of("tools", List.of(tool)))),
                id);
        return id;
    }

    long node(long r) {
        return db.queryForObject(
                "insert into"
                    + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE','READY','{\"type\":\"CODE\",\"expression\":\"1\"}')"
                    + " returning id",
                Long.class,
                r);
    }

    record Call(
            com.fasterxml.jackson.databind.JsonNode register,
            com.fasterxml.jackson.databind.JsonNode claim,
            Map<String, Object> claimBody) {}

    Call call(long r, long n, Map<String, Object> lease) {
        String id =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var register =
                Json.tree(
                        Map.of(
                                "runId", id,
                                "nodeInstanceId", n,
                                "attemptId", lease.get("attemptId"),
                                "callSeq", 1,
                                "skillVersionId", 42,
                                "skillCode", "lookup",
                                "arguments", Map.of("x", 1),
                                "idempotencyKey", Digest.sha256(r + ":" + n + ":1:42"),
                                "leaseOwner", lease.get("lease_owner")));
        var claimBody =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "owner", lease.get("lease_owner"),
                                "attemptId", lease.get("attemptId")));
        return new Call(register, Json.tree(claimBody), claimBody);
    }

    static Map<String, Object> plan(String risk) {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("summary", "创建用户 user-42 并启用");
        plan.put("target", "user-42");
        plan.put("diff", Map.of("enable", true));
        plan.put("risk", risk);
        return plan;
    }

    /** 申请确认：confirmation 携带 roles/参数摘要/actionPlan（两段）或省略 actionPlan（旧一段）。 */
    void requestApproval(long r, long n, Map<String, Object> lease, Map<String, Object> actionPlan) {
        var calls = new ToolCalls(store);
        var req = call(r, n, lease);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        var confirmation = new LinkedHashMap<String, Object>();
        confirmation.put(
                "roles", List.of("APPROVER"));
        confirmation.put("parametersDigest", Digest.sha256(Json.canonical(Map.of("x", 1))));
        if (actionPlan != null) confirmation.put("actionPlan", actionPlan);
        calls.requestApproval(cid, Json.tree(confirmation), actor);
    }

    Map<String, Object> pending(String publicId, long nid) {
        return new ApprovalService(store)
                .list(approver)
                .stream()
                .filter(item -> item.get("runId").equals(publicId))
                .filter(item -> TraceStore.number(item, "nodeInstanceId") == nid)
                .findFirst()
                .orElseThrow();
    }

    String stampKey(String callId, String core) {
        return Digest.sha256("EXECUTE:" + callId + ":" + core);
    }

    @Test
    void prepareApprovalPersistsStampedPlanUnderUniqueExecuteKey() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, plan("STANDARD"));
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        assertEquals(1, db.queryForObject("select count(*) from engine.action_plan", Integer.class));
        var row = db.queryForMap("select * from engine.action_plan where call_id=?", cid);
        var stamped = TraceStore.json(row.get("plan"));
        // 盖章：幂等键=EXECUTE:callId:核心摘要（去 idempotencyKey/expiresAt 的计划核心），
        // digest=盖章后完整计划的 canonical SHA-256。
        String core =
                Digest.sha256(
                        Json.canonical(
                                Json.tree(
                                        Map.of(
                                                "summary", "创建用户 user-42 并启用",
                                                "target", "user-42",
                                                "diff", Map.of("enable", true),
                                                "risk", "STANDARD"))));
        assertEquals(stampKey(Long.toString(cid), core), stamped.path("idempotencyKey").asText());
        assertEquals(stampKey(Long.toString(cid), core), row.get("execute_key"));
        assertEquals(Digest.sha256(Json.canonical(stamped)), row.get("digest"));
        assertEquals("PREPARED", row.get("status"));
        assertNotNull(row.get("expires_at"));
        var context =
                TraceStore.json(
                        db.queryForObject(
                                "select approval_context from engine.tool_call where id=?",
                                String.class,
                                cid));
        assertEquals(row.get("digest"), context.path("actionPlanDigest").asText());
        assertEquals(((Number) row.get("id")).longValue(), context.path("actionPlanId").asLong());
        // V221 真实 PG：唯一约束——同 execute_key 的裸第二条插入必须被数据库拒绝。
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "insert into"
                                    + " engine.action_plan(run_id,call_id,node_instance_id,digest,plan,execute_key,expires_at)"
                                    + " values(?,?,?,?,?,?::char(64),now())",
                                r,
                                cid,
                                n,
                                "f".repeat(64),
                                "{}",
                                row.get("execute_key")));
        // 崩溃重试的重复 PREPARE（相同计划内容）→ 复用同一行且刷新盖章，不产生第二条。
        db.update(
                "update engine.node_instance set status='RUNNING',lease_owner='A',lease_until=now()+interval"
                    + " '5 minutes' where id=?",
                n);
        requestApproval(
                r,
                n,
                Map.of("attemptId", lease.get("attemptId"), "lease_owner", "A"),
                plan("STANDARD"));
        assertEquals(1, db.queryForObject("select count(*) from engine.action_plan", Integer.class));
        var refreshed = db.queryForMap("select * from engine.action_plan where call_id=?", cid);
        var refreshedContext =
                TraceStore.json(
                        db.queryForObject(
                                "select approval_context from engine.tool_call where id=?",
                                String.class,
                                cid));
        assertEquals(refreshed.get("digest"), refreshedContext.path("actionPlanDigest").asText());
        assertEquals(
                Digest.sha256(Json.canonical(TraceStore.json(refreshed.get("plan")))),
                refreshed.get("digest"));
    }

    @Test
    void executeWithMismatchedKeyIsRejectedWithoutSideEffect() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, plan("STANDARD"));
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var item = pending(publicId, n);
        new ApprovalService(store)
                .approve(
                        publicId,
                        n,
                        Json.tree(
                                Map.of(
                                        "approved", true,
                                        "requestId", "ok-1",
                                        "snapshotDigest", item.get("snapshotDigest"))),
                        approver);
        var executeLease = requeue(n);
        var calls = new ToolCalls(store);
        var req = call(r, n, executeLease);
        calls.register(req.register(), actor);
        var wrongKey = new LinkedHashMap<>(req.claimBody());
        wrongKey.put("executeKey", Digest.sha256("forged"));
        var mismatch =
                assertThrows(
                        PlatformException.class, () -> calls.claim(cid, Json.tree(wrongKey), actor));
        assertEquals("ACTION_PLAN_DIGEST_MISMATCH", mismatch.code());
        // 副作用未发生：调用仍 REGISTERED，没有外部提交痕迹，计划也没有 EXECUTE 授权时间。
        assertEquals("REGISTERED", db.queryForObject("select status from engine.tool_call where id=?", String.class, cid));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.attempt_note where node_instance_id=? and kind='TOOL_EXECUTION_FACT'",
                        Integer.class,
                        n));
        assertNull(
                db.queryForObject(
                        "select execute_granted_at from engine.action_plan where call_id=?",
                        java.sql.Timestamp.class,
                        cid));
    }

    @Test
    void expiredPlanRejectsExecuteEvenAfterApproval() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, plan("STANDARD"));
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var item = pending(publicId, n);
        new ApprovalService(store)
                .approve(
                        publicId,
                        n,
                        Json.tree(
                                Map.of(
                                        "approved", true,
                                        "requestId", "ok-2",
                                        "snapshotDigest", item.get("snapshotDigest"))),
                        approver);
        db.update(
                "update engine.action_plan set expires_at=now()-interval '1 minute' where call_id=?",
                cid);
        var executeLease = requeue(n);
        var calls = new ToolCalls(store);
        var req = call(r, n, executeLease);
        calls.register(req.register(), actor);
        String executeKey =
                db.queryForObject(
                        "select execute_key from engine.action_plan where call_id=?", String.class, cid);
        var withKey = new LinkedHashMap<>(req.claimBody());
        withKey.put("executeKey", executeKey);
        var expired =
                assertThrows(
                        PlatformException.class, () -> calls.claim(cid, Json.tree(withKey), actor));
        assertEquals("ACTION_PLAN_EXPIRED", expired.code());
        assertEquals("REGISTERED", db.queryForObject("select status from engine.tool_call where id=?", String.class, cid));
    }

    @Test
    void duplicateExecuteReturnsFirstResultWithSinglePlanRow() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, plan("STANDARD"));
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var item = pending(publicId, n);
        new ApprovalService(store)
                .approve(
                        publicId,
                        n,
                        Json.tree(
                                Map.of(
                                        "approved", true,
                                        "requestId", "ok-3",
                                        "snapshotDigest", item.get("snapshotDigest"))),
                        approver);
        var executeLease = requeue(n);
        var calls = new ToolCalls(store);
        var req = call(r, n, executeLease);
        calls.register(req.register(), actor);
        String executeKey =
                db.queryForObject(
                        "select execute_key from engine.action_plan where call_id=?", String.class, cid);
        var withKey = new LinkedHashMap<>(req.claimBody());
        withKey.put("executeKey", executeKey);
        assertEquals(true, calls.claim(cid, Json.tree(withKey), actor).get("claimed"));
        java.sql.Timestamp granted =
                db.queryForObject(
                        "select execute_granted_at from engine.action_plan where call_id=?",
                        java.sql.Timestamp.class,
                        cid);
        calls.submitted(cid, Json.tree(withKey), actor);
        var fact = (ObjectNode) Json.tree(withKey).deepCopy();
        fact.put("status", "SUCCEEDED");
        fact.set("result", Json.tree(Map.of("userId", 42)));
        assertEquals(true, calls.fact(cid, fact, actor).get("accepted"));
        // 重复 EXECUTE：同一幂等键再次到达，返回首次结果、不再授予执行权，授权时间不变，计划行只有一条。
        var again = calls.claim(cid, Json.tree(withKey), actor);
        assertEquals(false, again.get("claimed"));
        assertEquals("SUCCEEDED", again.get("status"));
        assertEquals(42, Json.tree(again.get("result")).path("userId").asInt());
        assertEquals(
                granted,
                db.queryForObject(
                        "select execute_granted_at from engine.action_plan where call_id=?",
                        java.sql.Timestamp.class,
                        cid));
        assertEquals(1, db.queryForObject("select count(*) from engine.action_plan", Integer.class));
        // 审计：执行结果回写计划行（方案 §4.15 第5项 result 落审计结构）。
        var plan = db.queryForMap("select * from engine.action_plan where call_id=?", cid);
        assertEquals("EXECUTED", plan.get("status"));
        assertEquals("SUCCEEDED", plan.get("executed_status"));
        assertEquals(Digest.sha256(Json.canonical(Json.tree(Map.of("userId", 42)))), plan.get("result_digest"));
        assertEquals("bob", plan.get("confirmed_by"));
        assertNotNull(plan.get("confirmed_at"));
    }

    @Test
    void unauthorizedConfirmLeavesPlanAndCallUntouched() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, plan("STANDARD"));
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var item = pending(publicId, n);
        var decision =
                Json.tree(
                        Map.of(
                                "approved", true,
                                "requestId", "intrude",
                                "snapshotDigest", item.get("snapshotDigest")));
        var denied =
                assertThrows(
                        PlatformException.class,
                        () -> new ApprovalService(store).approve(publicId, n, decision, actor));
        assertEquals("APPROVAL_ROLE_REQUIRED", denied.code());
        assertEquals(403, denied.status());
        assertEquals(
                "PENDING",
                TraceStore.json(
                                db.queryForObject(
                                        "select approval_context from engine.tool_call where id=?",
                                        String.class,
                                        cid))
                        .path("status")
                        .asText());
        assertEquals(
                "PREPARED",
                db.queryForObject("select status from engine.action_plan where call_id=?", String.class, cid));
        assertNull(
                db.queryForObject(
                        "select confirmed_by from engine.action_plan where call_id=?",
                        String.class,
                        cid));
    }

    /** 批准把节点重排为 READY；EXECUTE 段先按既有调度重新取得租约（新 attempt），再 claim 调用。 */
    Map<String, Object> requeue(long n) {
        return store.claim(n, "B");
    }

    @Test
    void singlePrincipalAndDoubleConfirmationKeepHighRiskPlanPending() {
        long r = run(List.of("APPROVER"), "HIGH"), n = node(r);
        requestApproval(r, n, store.claim(n, "A"), plan("HIGH"));
        String publicId = db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        Object digest = pending(publicId, n).get("snapshotDigest");

        assertEquals("WAITING_APPROVAL", vote(publicId, n, digest, approver, "first", false).get("status"));
        assertEquals("WAITING_APPROVAL", vote(publicId, n, digest, approver, "same-person", true).get("status"));
        var plan = db.queryForMap("select * from engine.action_plan where run_id=?", r);
        assertEquals("PREPARED", plan.get("status"));
        assertEquals(1, TraceStore.json(plan.get("approvals")).size());
        assertNull(plan.get("execute_granted_at"));
    }

    @Test
    void twoVerifiedPrincipalsApproveHighRiskAndExecutionStillRequiresCurrentAuthority() {
        long r = run(List.of("APPROVER", "SEC_AUDITOR"), "HIGH"), n = node(r);
        var lease = store.claim(n, "A");
        var calls = new ToolCalls(store);
        var req = call(r, n, lease);
        long cid = TraceStore.number(calls.register(req.register(), actor), "callId");
        calls.requestApproval(
                cid,
                Json.tree(Map.of(
                        "roles", List.of("APPROVER", "SEC_AUDITOR"),
                        "parametersDigest", Digest.sha256(Json.canonical(Map.of("x", 1))),
                        "actionPlan", plan("HIGH"))),
                actor);
        String publicId = db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        Object digest = pending(publicId, n).get("snapshotDigest");
        var auditor = new Actor("engine-test", "carol", Set.of("SEC_AUDITOR"), Map.of());

        assertEquals("WAITING_APPROVAL", vote(publicId, n, digest, approver, "first", false).get("status"));
        assertEquals("RUNNING", vote(publicId, n, digest, auditor, "second", false).get("status"));
        var plan = db.queryForMap("select * from engine.action_plan where call_id=?", cid);
        assertEquals("APPROVED", plan.get("status"));
        assertEquals(false, plan.get("double_confirmed"));
        assertEquals(2, TraceStore.json(plan.get("approvals")).size());

        var execution = call(r, n, requeue(n));
        calls.register(execution.register(), actor);
        var withKey = new LinkedHashMap<>(execution.claimBody());
        withKey.put("executeKey", plan.get("execute_key"));
        var denied = assertThrows(PlatformException.class, () -> calls.claim(cid, Json.tree(withKey), actor));
        assertEquals("APPROVAL_CURRENT_AUTHORITY_REQUIRED", denied.code());
        assertEquals("REGISTERED", db.queryForObject("select status from engine.tool_call where id=?", String.class, cid));
        assertTrue(db.queryForList(
                        "select payload from engine.run_event where run_id=? and type='APPROVAL_DONE'", r)
                .stream()
                .map(event -> TraceStore.json(event.get("payload")))
                .anyMatch(payload -> payload.path("actionPlanId").asLong() == ((Number) plan.get("id")).longValue()
                        && "HIGH".equals(payload.path("risk").asText())));
    }

    private Map<String, Object> vote(
            String publicId, long node, Object digest, Actor voter, String requestId, boolean doubleConfirmed) {
        HighRiskTwoPrincipalTest.requestIdentity(voter);
        return new ApprovalService(store).approve(
                publicId,
                node,
                Json.tree(Map.of(
                        "approved", true,
                        "requestId", requestId,
                        "snapshotDigest", digest,
                        "doubleConfirmed", doubleConfirmed)),
                voter);
    }

    @Test
    void legacySingleStepCallIgnoresExecuteKeyGate() {
        long r = run(List.of("APPROVER")), n = node(r);
        var lease = store.claim(n, "A");
        requestApproval(r, n, lease, null);
        long cid =
                db.queryForObject(
                        "select id from engine.tool_call where run_id=?", Long.class, r);
        assertEquals(0, db.queryForObject("select count(*) from engine.action_plan", Integer.class));
        String publicId =
                db.queryForObject("select public_id from engine.run where id=?", String.class, r);
        var item = pending(publicId, n);
        new ApprovalService(store)
                .approve(
                        publicId,
                        n,
                        Json.tree(
                                Map.of(
                                        "approved", true,
                                        "requestId", "legacy",
                                        "snapshotDigest", item.get("snapshotDigest"))),
                        approver);
        var executeLease = requeue(n);
        var calls = new ToolCalls(store);
        var req = call(r, n, executeLease);
        calls.register(req.register(), actor);
        // 旧技能不携带 executeKey，claim 不做计划校验——SINGLE_STEP 零回归锚点。
        assertEquals(true, calls.claim(cid, req.claim(), actor).get("claimed"));
    }
}
