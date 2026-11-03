package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ToolCalls {
    private final TraceStore store;
    private HighRiskApprovals highRisk;

    public ToolCalls(TraceStore s) {
        store = s;
        highRisk = new HighRiskApprovals(s, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void currentApprovers(cn.agentplatform.common.authority.HttpCurrentAuthorityClient client) {
        highRisk = new HighRiskApprovals(store, client);
    }

    public Map<String, Object> register(JsonNode req, Actor actor) {
        return store.atomic(
                () -> {
                    var run = store.run(req.path("runId").asText(), actor);
                    long rid = TraceStore.number(run, "id"),
                            nid = req.path("nodeInstanceId").asLong(),
                            version = req.path("skillVersionId").asLong();
                    int seq = req.path("callSeq").asInt();
                    if (seq < 1 || version < 1 || req.path("skillCode").asText().isBlank())
                        throw new PlatformException(400, "CALL_INVALID", "调用缺少技能版本或序号");
                    run = store.lockRun(rid);
                    store.db()
                            .queryForMap(
                                    "select id from engine.node_instance where id=? and run_id=?"
                                        + " for update",
                                    nid,
                                    rid);
                    validateLease(
                            nid,
                            rid,
                            req.path("attemptId").asLong(),
                            req.path("leaseOwner").asText());
                    String key = Digest.sha256(rid + ":" + nid + ":" + seq + ":" + version);
                    if (!key.equals(req.path("idempotencyKey").asText()))
                        throw new PlatformException(409, "CALL_KEY_MISMATCH", "幂等键与逻辑调用不匹配");
                    String digest = Digest.sha256(Json.canonical(req.path("arguments")));
                    // INT-02：受控连接引用随调用登记钉住，来源只有运行快照（载荷由引擎构造），
                    // 登记后不允许被重试/恢复改写——理由见 V226 迁移注释。
                    String refs = bindingRefs(req.path("bindingRefs"));
                    // 载荷没带就取运行快照里的冻结引用：引用是引擎的权威事实，
                    // 不依赖某个调用方"记得"把它塞进请求；两边都有时以载荷为准（仍由引擎构造）。
                    if (refs == null)
                        refs = bindingRefs(
                                TraceStore.json(run.get("snapshot")).path("config").path("bindingRefs"));
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id,binding_refs)"
                                        + " values(?,?,?,?,?,? ,?,'REGISTERED',?,?::jsonb,?,?::jsonb) on"
                                        + " conflict(idempotency_key) do nothing",
                                    rid,
                                    nid,
                                    seq,
                                    version,
                                    digest,
                                    "pg:call/" + key + "/args",
                                    key,
                                    req.path("skillCode").asText(),
                                    Json.write(req.path("arguments")),
                                    req.path("attemptId").asLong(),
                                    refs);
                    var call =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.tool_call where idempotency_key=?"
                                                + " for update",
                                            key);
                    if (!digest.equals(call.get("args_digest")))
                        throw new PlatformException(409, "CALL_ARGUMENTS_MISMATCH", "相同逻辑调用参数发生变化");
                    // 同一逻辑调用的连接引用一旦登记就固定：换了引用说明"这次调用要连别的东西"，
                    // 必须作为新调用重新登记，不能沿用旧记录偷换目标。
                    if (!sameRefs(call.get("binding_refs"), refs))
                        throw new PlatformException(409, "CALL_BINDING_CHANGED", "相同逻辑调用的连接引用发生变化");
                    String contractDigest = "";
                    for (JsonNode skill :
                            TraceStore.json(run.get("snapshot")).path("config").path("tools"))
                        if (skill.path("skillVersionId").asLong() == version
                                && skill.path("code")
                                        .asText()
                                        .equals(req.path("skillCode").asText()))
                            contractDigest = skill.path("contractDigest").asText();
                    if (contractDigest.isEmpty()
                            && Objects.equals(
                                    run.get("controlled_draft_skill_version_id"), version))
                        contractDigest =
                                Objects.toString(run.get("controlled_draft_skill_digest"), "");
                    if (contractDigest.matches("[a-f0-9]{64}")) {
                        if (call.get("skill_contract_sha256") != null
                                && !call.get("skill_contract_sha256").equals(contractDigest))
                            throw new PlatformException(
                                    409, "CALL_CONTRACT_CHANGED", "调用合同摘要与冻结版本不符");
                        store.db()
                                .update(
                                        "update engine.tool_call set skill_contract_sha256=? where"
                                            + " id=?",
                                        contractDigest,
                                        call.get("id"));
                        call.put("skill_contract_sha256", contractDigest);
                    }
                    if (!contractDigest.matches("[a-f0-9]{64}"))
                        throw new PlatformException(
                                409, "CALL_CONTRACT_REQUIRED", "调用不在冻结技能词表或缺少合同摘要");
                    if (call.get("mock_snapshot") == null) {
                        JsonNode mock = Json.read("{}");
                        if (run.get("trigger_type").equals("DEBUG"))
                            for (JsonNode candidate :
                                    TraceStore.json(run.get("snapshot"))
                                            .path("debugControls")
                                            .path("mocks"))
                                if (candidate
                                        .path("skillCode")
                                        .asText()
                                        .equals(req.path("skillCode").asText())) mock = candidate;
                        if (mock.path("mode").asText().equals("LAST_REAL"))
                            mock =
                                    new cn.agentplatform.engine.application.DebugControls(store)
                                            .lastReal(
                                                    version,
                                                    req.path("skillCode").asText(),
                                                    digest,
                                                    contractDigest,
                                                    mock.hasNonNull("sourceCallId")
                                                            ? mock.path("sourceCallId").asLong()
                                                            : null,
                                                    actor);
                        if (!mock.isEmpty() && !mock.path("mode").asText().equals("FIXED"))
                            throw new PlatformException(400, "MOCK_INVALID", "不支持的替身模式");
                        store.db()
                                .update(
                                        "update engine.tool_call set"
                                            + " mock_snapshot=?::jsonb,debug_controls_revision=?,mock_source_call_id=?,mock_source_digest=?"
                                            + " where id=?",
                                        Json.write(mock),
                                        run.get("debug_controls_revision"),
                                        mock.has("sourceCallId")
                                                ? mock.path("sourceCallId").asLong()
                                                : null,
                                        mock.has("sourceDigest")
                                                ? mock.path("sourceDigest").asText()
                                                : null,
                                        call.get("id"));
                        call.put("mock_snapshot", mock);
                        call.put("debug_controls_revision", run.get("debug_controls_revision"));
                    }
                    if (call.get("status").equals("REGISTERED")) {
                        store.db()
                                .update(
                                        "update engine.tool_call set attempt_id=? where id=? and"
                                            + " status='REGISTERED'",
                                        req.path("attemptId").asLong(),
                                        call.get("id"));
                        call.put("attempt_id", req.path("attemptId").asLong());
                    }
                    return view(call);
                });
    }

    /**
     * PLAT-15 EXECUTE 门（契约 §14.4）：两段调用的 EXECUTE（claim）必须携带引擎盖章的 executeKey，
     * 与持久化计划（V221）一致且未过期；高危计划（risk=HIGH）复查确认强度。任一不满足即抛可读
     * 4xx——claim 未授予，业务调用不发出，副作用不发生。SINGLE_STEP 旧调用无 actionPlanId，
     * 直接通过（零回归锚点：writeApproval 既有用例与 ActionPlanPostgresTest.legacySingleStep）。
     */
    private void requireExecutablePlan(Map<String, Object> call, JsonNode req, HighRiskApprovals.Checked checked) {
        var context = TraceStore.json(call.get("approval_context"));
        if (!context.has("actionPlanId")) return;
        var plans =
                store.db()
                        .queryForList(
                                "select * from engine.action_plan where id=? for update",
                                context.path("actionPlanId").asLong());
        if (plans.isEmpty())
            throw new PlatformException(
                    409, "ACTION_PLAN_DIGEST_MISMATCH", "审批计划记录缺失，请重新发起调用与确认");
        var plan = plans.getFirst();
        String executeKey = req.path("executeKey").asText("");
        if (executeKey.isBlank())
            throw new PlatformException(
                    409,
                    "EXECUTE_KEY_REQUIRED",
                    "两段执行的 EXECUTE 必须携带审批计划盖章的 executeKey（来自批准后的调用授权）");
        if (!executeKey.equals(Objects.toString(plan.get("execute_key"), ""))
                || !context.path("actionPlanDigest").asText().equals(plan.get("digest")))
            throw new PlatformException(
                    409,
                    "ACTION_PLAN_DIGEST_MISMATCH",
                    "EXECUTE 与批准的计划不一致（计划可能已被重新生成），副作用未发生，请重新走确认流程");
        if (!((java.sql.Timestamp) plan.get("expires_at")).toInstant().isAfter(java.time.Instant.now()))
            throw new PlatformException(
                    409,
                    "ACTION_PLAN_EXPIRED",
                    "批准的执行计划已过期，副作用未发生，请重新发起调用与确认");
        if ("HIGH".equals(plan.get("risk"))) {
            HighRiskApprovals.requirePolicy(plan);
            if (checked == null) throw new PlatformException(503, "APPROVAL_CURRENT_AUTHORITY_REQUIRED", "缺少本次双主体在线复核，执行未获授权");
            checked.require(call, plan);
        }
        BusinessIdempotency.requireAvailable(store, call, plan);
        // 首次授予才落 execute_granted_at：重复 EXECUTE 复用同一授权时间，审计上只有一次执行授权。
        store.db()
                .update(
                        "update engine.action_plan set"
                            + " execute_granted_at=coalesce(execute_granted_at, now()) where id=?",
                        plan.get("id"));
    }

    /**
     * PLAT-15（契约 §14.3）：两段审批的 actionPlan 引擎盖章与持久化。幂等键来源唯一是引擎：
     * executeKey = sha256("EXECUTE:" + callId + ":" + 去键核心摘要)；digest = 盖章后完整计划
     * （含 idempotencyKey/expiresAt）的 canonical SHA-256。技能侧提供的 idempotencyKey/expiresAt
     * 一律由引擎决定。STANDARD 保留原盖章行为；新 HIGH 的同键重复 PREPARE 只复用原冻结行，
     * 不刷新正文、有效期或投票。内容变化产生新行，旧行保留审计；确认人与结果经 approve/fact 回写。
     */
    private void stampActionPlan(
            long callId,
            long runId,
            long nodeId,
            com.fasterxml.jackson.databind.JsonNode planNode,
            java.time.Instant expiry,
            com.fasterxml.jackson.databind.node.ObjectNode context,
            String declaredRisk,
            String businessIntentInput) {
        if (!(planNode instanceof com.fasterxml.jackson.databind.node.ObjectNode source)
                || source.isEmpty()) return;
        if (!Set.of("STANDARD", "HIGH").contains(declaredRisk))
            throw new PlatformException(
                    400,
                    "APPROVAL_RISK_INVALID",
                    "actionPlan.risk 只允许 STANDARD 或 HIGH，收到: " + declaredRisk);
        if (source.has("risk") && !declaredRisk.equals(source.path("risk").asText()))
            throw new PlatformException(409, "APPROVAL_RISK_MISMATCH", "行动计划风险与冻结技能声明不一致");
        var core = source.deepCopy();
        core.remove("idempotencyKey");
        core.remove("expiresAt");
        core.remove("approvalPolicy");
        core.put("risk", declaredRisk);
        var business = BusinessIdempotency.prepare(store, callId, runId, businessIntentInput);
        if (business != null) core.set("businessIdempotency", Json.tree(business.material()));
        boolean twoPrincipals = "HIGH".equals(declaredRisk);
        if (twoPrincipals) core.put("approvalPolicy", HighRiskApprovals.POLICY);
        String coreDigest = Digest.sha256(Json.canonical(core));
        String executeKey = Digest.sha256("EXECUTE:" + callId + ":" + coreDigest);
        var stamped = core.deepCopy();
        stamped.put("idempotencyKey", business == null ? executeKey : business.key());
        stamped.put("expiresAt", expiry.toString());
        String digest = Digest.sha256(Json.canonical(stamped));
        store.db()
                .update(
                        "insert into"
                            + " engine.action_plan(run_id,call_id,node_instance_id,digest,plan,execute_key,risk,expires_at,approval_policy)"
                            + " values(?,?,?,?,?::jsonb,?,?,?,?) on conflict(execute_key) "
                            + (twoPrincipals || business != null ? "do nothing" : "do update set digest=excluded.digest,plan=excluded.plan,expires_at=excluded.expires_at,risk=excluded.risk"),
                        runId,
                        callId,
                        nodeId,
                        digest,
                        Json.write(stamped),
                        executeKey,
                        declaredRisk,
                        java.sql.Timestamp.from(expiry),
                        twoPrincipals ? HighRiskApprovals.POLICY : "LEGACY_DECLARATION");
        BusinessIdempotency.attach(store, executeKey, business);
        var persisted = store.db().queryForMap("select * from engine.action_plan where execute_key=?", executeKey);
        // 新 HIGH 同一键只复用原冻结计划；已有票据/过期时间不能因重复 PREPARE 被重盖章。
        if (twoPrincipals || business != null) {
            if (twoPrincipals) HighRiskApprovals.requirePolicy(persisted);
            stamped = (com.fasterxml.jackson.databind.node.ObjectNode) TraceStore.json(persisted.get("plan")).deepCopy();
            digest = persisted.get("digest").toString();
            context.put("expiresAt", ((java.sql.Timestamp) persisted.get("expires_at")).toInstant().toString());
        }
        long planId =
                store.db()
                        .queryForObject(
                                "select id from engine.action_plan where execute_key=?",
                                Long.class,
                                executeKey);
        context.put("actionPlanId", planId);
        context.put("actionPlanDigest", digest);
        context.put("risk", declaredRisk);
        context.set("actionPlan", stamped);
    }

    private void validateLease(long nid, long rid, long attempt, String owner) {        Boolean valid =
                store.db()
                        .queryForObject(
                                "select exists(select 1 from engine.node_instance n join engine.run"
                                    + " r on r.id=n.run_id join engine.attempt a on"
                                    + " a.node_instance_id=n.id where n.id=? and n.run_id=? and"
                                    + " a.id=? and n.status='RUNNING' and r.status='RUNNING' and"
                                    + " n.lease_owner=? and n.lease_until>now() and"
                                    + " a.status='RUNNING' and a.attempt_no=(select"
                                    + " max(current_attempt.attempt_no) from engine.attempt"
                                    + " current_attempt where"
                                    + " current_attempt.node_instance_id=n.id))",
                                Boolean.class,
                                nid,
                                rid,
                                attempt,
                                owner);
        if (!Boolean.TRUE.equals(valid)) throw new PlatformException(409, "LEASE_LOST", "调用租约已失效");
    }

    private Map<String, Object> locked(long id, Actor actor) {
        var ownership =
                store.db()
                        .queryForList(
                                "select t.run_id,t.node_instance_id from engine.tool_call t join"
                                    + " engine.run r on r.id=t.run_id where t.id=? and"
                                    + " r.tenant_id=? and r.principal_id=?",
                                id,
                                actor.tenantId(),
                                actor.principalId());
        if (ownership.isEmpty()) throw new PlatformException(404, "CALL_NOT_FOUND", "调用不存在");
        var authority = ownership.getFirst();
        // R4: every grant, fact, and cancellation shares run → node → call lock order.
        // A prior SELECT under READ COMMITTED alone cannot fence a concurrent cancel.
        store.lockRun(TraceStore.number(authority, "run_id"));
        store.db()
                .queryForMap(
                        "select id from engine.node_instance where id=? for update",
                        authority.get("node_instance_id"));
        var rows =
                store.db()
                        .queryForList(
                                "select t.* from engine.tool_call t join engine.run r on"
                                    + " r.id=t.run_id where t.id=? and r.tenant_id=? and"
                                    + " r.principal_id=? for update of t",
                                id,
                                actor.tenantId(),
                                actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "CALL_NOT_FOUND", "调用不存在");
        return rows.getFirst();
    }

    public Map<String, Object> claim(long id, JsonNode req, Actor actor) {
        // 双人在线核权必须在 PG 锁外；事务内只消费同一计划/投票版本的短期复核结果。
        var checked = highRisk.beforeClaim(id, actor);
        var result = store.atomic(
                () -> {
                    var call = locked(id, actor);
                    String owner = req.path("owner").asText();
                    long attempt = req.path("attemptId").asLong();
                    validateLease(
                            TraceStore.number(call, "node_instance_id"),
                            TraceStore.number(call, "run_id"),
                            attempt,
                            owner);
                    if (TraceStore.json(call.get("approval_context")).has("roles")
                            && !Boolean.TRUE.equals(approval(id, actor).get("approved")))
                        throw new PlatformException(403, "APPROVAL_REQUIRED", "调用尚未取得有效参数确认");
                    requireExecutablePlan(call, req, checked);
                    if (call.get("status").equals("SUCCEEDED")
                            || call.get("status").equals("FAILED"))
                        return withClaim(view(call), false);
                    int changed =
                            store.db()
                                    .update(
                                            "update engine.tool_call t set"
                                                + " status='EXECUTING',claimed_by=?,claimed_at=now(),claimed_lease=n.lease_until,attempt_id=?"
                                                + " from engine.node_instance n where t.id=? and"
                                                + " n.id=t.node_instance_id and"
                                                + " t.status='REGISTERED'",
                                            owner,
                                            attempt,
                                            id);
                    if (changed == 0) {
                        store.db()
                                .update(
                                        "update engine.tool_call set"
                                            + " status='UNKNOWN',error_semantic='RESULT_UNKNOWN'"
                                            + " where id=? and status in ('EXECUTING','SUBMITTED')"
                                            + " and claimed_lease<=now()",
                                        id);
                    }
                    return withClaim(
                            view(
                                    store.db()
                                            .queryForMap(
                                                    "select * from engine.tool_call where id=?",
                                                    id)),
                            changed == 1);
                });
        if (checked != null) checked.requireLive();
        return result;
    }

    public Map<String, Object> submitted(long id, JsonNode req, Actor actor) {
        return store.atomic(
                () -> {
                    var call = locked(id, actor);
                    validateLease(
                            TraceStore.number(call, "node_instance_id"),
                            TraceStore.number(call, "run_id"),
                            req.path("attemptId").asLong(),
                            req.path("owner").asText());
                    int n =
                            store.db()
                                    .update(
                                            "update engine.tool_call set status='SUBMITTED' where"
                                                + " id=? and status='EXECUTING' and claimed_by=?"
                                                + " and attempt_id=? and claimed_lease>now()",
                                            id,
                                            req.path("owner").asText(),
                                            req.path("attemptId").asLong());
                    if (n != 1) throw new PlatformException(409, "CALL_CLAIM_LOST", "外部提交权已失效");
                    return Map.of("submitted", true, "callId", id, "status", "SUBMITTED");
                });
    }

    public Map<String, Object> fact(long id, JsonNode req, Actor actor) {
        return store.atomic(
                () -> {
                    var call = locked(id, actor);
                    String status = req.path("status").asText();
                    if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status))
                        throw new PlatformException(400, "FACT_STATUS_INVALID", "执行事实状态无效");
                    // A signed late fact remains evidence. It never grants an expired worker
                    // logical commit
                    // authority.
                    if (!Boolean.TRUE.equals(
                            store.db()
                                    .queryForObject(
                                            "select exists(select 1 from engine.attempt where id=?"
                                                + " and node_instance_id=?)",
                                            Boolean.class,
                                            req.path("attemptId").asLong(),
                                            call.get("node_instance_id"))))
                        throw new PlatformException(409, "FACT_ATTEMPT_MISMATCH", "事实尝试不属于该调用节点");
                    String factDigest = Digest.sha256(id + ":" + Json.canonical(req));
                    if (!store.db()
                            .queryForList(
                                    "select id from engine.attempt_note where node_instance_id=?"
                                        + " and kind='TOOL_EXECUTION_FACT' and"
                                        + " payload->>'factDigest'=? and"
                                        + " payload->>'accepted'='true'",
                                    call.get("node_instance_id"),
                                    factDigest)
                            .isEmpty())
                        return Map.of(
                                "accepted",
                                true,
                                "late",
                                false,
                                "deduped",
                                true,
                                "callId",
                                id,
                                "status",
                                call.get("status"));
                    int duration = Math.max(0, req.path("durationMs").asInt());
                    int accounted =
                            store.db()
                                    .queryForObject(
                                            "select coalesce(max((payload->>'durationMs')::int),0)"
                                                + " from engine.attempt_note where"
                                                + " node_instance_id=? and"
                                                + " kind='TOOL_EXECUTION_FACT' and"
                                                + " payload->>'callId'=? and"
                                                + " payload->>'attemptId'=? and"
                                                + " payload->>'accepted'='true'",
                                            Integer.class,
                                            call.get("node_instance_id"),
                                            Long.toString(id),
                                            req.path("attemptId").asText());
                    int n =
                            store.db()
                                    .update(
                                            "update engine.tool_call t set"
                                                + " status=?,result=?::jsonb,result_ref=?,duration_ms=coalesce(duration_ms,0)+?,error_semantic=?,is_mock=?"
                                                + " where t.id=? and t.status in"
                                                + " ('EXECUTING','SUBMITTED','UNKNOWN') and"
                                                + " t.claimed_by=? and t.attempt_id=? and"
                                                + " t.claimed_lease>now() and exists(select 1 from"
                                                + " engine.node_instance n join engine.run r on"
                                                + " r.id=n.run_id where n.id=t.node_instance_id and"
                                                + " n.status in ('RUNNING','WAITING_EXTERNAL') and"
                                                + " r.status in ('RUNNING','WAITING_EXTERNAL') and"
                                                + " n.lease_owner=t.claimed_by and"
                                                + " n.lease_until>now())",
                                            status,
                                            Json.write(req.path("result")),
                                            "pg:call/" + id + "/result",
                                            Math.max(0, duration - accounted),
                                            req.path("errorSemantic").isMissingNode()
                                                    ? null
                                                    : req.path("errorSemantic").asText(),
                                            req.path("isMock").asBoolean(),
                                            id,
                                            req.path("owner").asText(),
                                            req.path("attemptId").asLong());
                    // PLAT-04：外部任务快照独立于事实终态保存。只接受被采纳的事实写入，
                    // 迟到的 /fact 投递不得覆盖已采纳的 externalJob；未绑定外部任务时保持 NULL，
                    // GET 对外仍返回显式 null，调用方用字段是否存在区分两者。
                    if (n == 1) {
                        var externalJob = req.path("result").path("externalJob");
                        if (!externalJob.isNull() && !externalJob.isMissingNode())
                            store.db()
                                    .update(
                                            "update engine.tool_call set external_job=?::jsonb where id=?",
                                            Json.write(externalJob),
                                            id);
                        // PLAT-15 审计（方案 §4.15 第5项）：执行结果回写计划行
                        // （runId/actionPlanId/confirmBy/confirmAt/result 全链在 engine.action_plan 与
                        // APPROVAL_DONE 事件上），只随被采纳的事实前进，迟到投递不覆盖。
                        var planContext = TraceStore.json(call.get("approval_context"));
                        if (planContext.has("actionPlanId"))
                            store.db()
                                    .update(
                                            "update engine.action_plan set status=?,executed_status=?,result_digest=?"
                                                + " where id=? and status in"
                                                + " ('APPROVED','EXECUTED','EXECUTE_FAILED')",
                                            "SUCCEEDED".equals(status)
                                                    ? "EXECUTED"
                                                    : "EXECUTE_FAILED",
                                            status,
                                            Digest.sha256(Json.canonical(req.path("result"))),
                                            planContext.path("actionPlanId").asLong());
                    }
                    // W01: sum actual execution per attempt, not outbox deliveries or
                    // reconciliation waiting.
                    var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) req.deepCopy();
                    evidence.put("callId", id);
                    evidence.put("durationMs", duration);
                    evidence.put("factDigest", factDigest);
                    evidence.put("accepted", n == 1);
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.attempt_note(node_instance_id,attempt_id,kind,payload)"
                                        + " values(?,?,'TOOL_EXECUTION_FACT',?::jsonb)",
                                    call.get("node_instance_id"),
                                    req.path("attemptId").asLong(),
                                    Json.write(evidence));
                    return Map.of(
                            "accepted",
                            n == 1,
                            "callId",
                            id,
                            "status",
                            n == 1 ? status : call.get("status"),
                            "late",
                            n == 0);
                });
    }

    public Map<String, Object> get(long id, Actor actor) {
        return store.atomic(() -> view(locked(id, actor)));
    }

    public Map<String, Object> approval(long id, Actor actor) {
        return store.atomic(
                () -> {
                    var call = locked(id, actor);
                    var context = TraceStore.json(call.get("approval_context"));
                    boolean live =
                            Boolean.TRUE.equals(
                                    store.db()
                                            .queryForObject(
                                                    "select exists(select 1 from"
                                                        + " engine.node_instance n join engine.run"
                                                        + " r on r.id=n.run_id join engine.attempt"
                                                        + " a on a.node_instance_id=n.id where"
                                                        + " n.id=? and n.status='RUNNING' and"
                                                        + " r.status='RUNNING' and"
                                                        + " n.lease_until>now() and a.id=? and"
                                                        + " a.status='RUNNING' and"
                                                        + " a.attempt_no=(select"
                                                        + " max(latest.attempt_no) from"
                                                        + " engine.attempt latest where"
                                                        + " latest.node_instance_id=n.id))",
                                                    Boolean.class,
                                                    call.get("node_instance_id"),
                                                    call.get("attempt_id")));
                    boolean approved =
                            live
                                    && context.path("status").asText().equals("APPROVED")
                                    && context.path("skillVersionId").asLong()
                                            == TraceStore.number(call, "skill_version_id")
                                    && context.path("parametersDigest")
                                            .asText()
                                            .equals(call.get("args_digest"))
                                    && java.time.Instant.parse(context.path("expiresAt").asText())
                                            .isAfter(java.time.Instant.now());
                    // PLAT-15：批准后的调用授权随行动计划下发——EXECUTE 幂等键与 digest 门槛的
                    // 唯一权威是 engine.action_plan（契约 §14.3），技能据此出站并回传 executeKey。
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("approved", approved);
                    result.put("callId", id);
                    result.put("skillVersionId", call.get("skill_version_id"));
                    result.put("parametersDigest", call.get("args_digest"));
                    result.put(
                            "roles",
                            context.path("roles").isArray() ? context.path("roles") : List.of());
                    if (approved && context.has("actionPlanId")) {
                        var plan =
                                store.db()
                                        .queryForMap(
                                                "select digest,plan,execute_key,risk,expires_at,business_key"
                                                    + " from engine.action_plan where id=?",
                                                context.path("actionPlanId").asLong());
                        var stamped = TraceStore.json(plan.get("plan"));
                        var issued = new LinkedHashMap<String, Object>();
                        issued.put("id", context.path("actionPlanId").asLong());
                        issued.put("idempotencyKey", plan.get("business_key") == null ? plan.get("execute_key") : plan.get("business_key"));
                        if (plan.get("business_key") != null) issued.put("executeKey", plan.get("execute_key"));
                        issued.put("digest", plan.get("digest"));
                        issued.put("risk", plan.get("risk"));
                        issued.put(
                                "expiresAt",
                                ((java.sql.Timestamp) plan.get("expires_at")).toInstant().toString());
                        issued.put("summary", stamped.path("summary").asText(null));
                        issued.put("target", stamped.path("target").asText(null));
                        result.put("actionPlan", issued);
                    }
                    return result;
                });
    }

    public void requestApproval(long id, JsonNode confirmation, Actor actor) {
        store.atomic(
                () -> {
                    var ownership =
                            store.db()
                                    .queryForList(
                                            "select t.run_id from engine.tool_call t join"
                                                + " engine.run r on r.id=t.run_id where t.id=? and"
                                                + " r.tenant_id=? and r.principal_id=?",
                                            id,
                                            actor.tenantId(),
                                            actor.principalId());
                    if (ownership.isEmpty())
                        throw new PlatformException(404, "CALL_NOT_FOUND", "调用不存在");
                    long rid = TraceStore.number(ownership.getFirst(), "run_id");
                    // Lock run before the call/node, matching approval/cancel transaction order.
                    store.lockRun(rid);
                    var call = locked(id, actor);
                    var node =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.node_instance where id=? for"
                                                + " update",
                                            call.get("node_instance_id"));
                    validateLease(
                            TraceStore.number(call, "node_instance_id"),
                            rid,
                            TraceStore.number(call, "attempt_id"),
                            Objects.toString(node.get("lease_owner"), ""));
                    if (!call.get("status").equals("REGISTERED"))
                        throw new PlatformException(409, "CALL_NOT_REGISTERED", "已执行调用不能重新申请确认");
                    if (!confirmation.path("roles").isArray()
                            || confirmation.path("roles").isEmpty())
                        throw new PlatformException(409, "CONFIRM_ROLES_REQUIRED", "写技能必须声明确认角色");
                    JsonNode frozen =
                            TraceStore.json(
                                    store.db()
                                            .queryForObject(
                                                    "select snapshot::text from engine.run where"
                                                        + " id=?",
                                                    String.class,
                                                    rid));
                    Set<String> declaredRoles = new HashSet<>(), returnedRoles = new HashSet<>();
                    String declaredRisk = "STANDARD";
                    String businessIntentInput = null;
                    for (JsonNode skill : frozen.path("config").path("tools"))
                        if (skill.path("skillVersionId").asLong()
                                == TraceStore.number(call, "skill_version_id"))
                        {
                            var approvalDeclaration = skill.path("execution").path("approval");
                            declaredRisk = approvalDeclaration.path("risk").asText("STANDARD");
                            if (approvalDeclaration.has("businessIntentInput")) {
                                if (!"PREPARE_EXECUTE".equals(approvalDeclaration.path("mode").asText())
                                        || !approvalDeclaration.path("businessIntentInput").isTextual()
                                        || !approvalDeclaration.path("businessIntentInput").asText().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                                    throw new PlatformException(400, "BUSINESS_INTENT_INPUT_INVALID", "业务意图必须来自冻结 run.inputs 的显式顶层字段");
                                businessIntentInput = approvalDeclaration.path("businessIntentInput").asText();
                            }
                            skill.path("execution")
                                    .path("confirmRoles")
                                    .forEach(role -> declaredRoles.add(role.asText()));
                        }
                    confirmation.path("roles").forEach(role -> returnedRoles.add(role.asText()));
                    if (declaredRoles.isEmpty() || !declaredRoles.equals(returnedRoles))
                        throw new PlatformException(
                                409, "CONFIRM_ROLES_MISMATCH", "确认角色与冻结技能声明不匹配");
                    if (!confirmation
                            .path("parametersDigest")
                            .asText()
                            .equals(call.get("args_digest")))
                        throw new PlatformException(
                                409, "APPROVAL_ARGUMENTS_MISMATCH", "确认参数摘要与登记不一致");
                    var context = Json.object();
                    context.put("callId", id);
                    context.put("skillVersionId", TraceStore.number(call, "skill_version_id"));
                    context.put("parametersDigest", call.get("args_digest").toString());
                    context.set("args", TraceStore.json(call.get("args")));
                    context.set("roles", confirmation.path("roles"));
                    java.time.Instant expiry = java.time.Instant.now().plusSeconds(3600);
                    context.put("expiresAt", expiry.toString());
                    context.put("status", "PENDING");
                    stampActionPlan(id, rid, TraceStore.number(call, "node_instance_id"),
                            confirmation.path("actionPlan"), expiry, context, declaredRisk, businessIntentInput);
                    store.db()
                            .update(
                                    "update engine.tool_call set approval_context=?::jsonb where"
                                        + " id=?",
                                    Json.write(context),
                                    id);
                    store.db()
                            .update(
                                    "update engine.node_instance set"
                                        + " status='WAITING_APPROVAL',approval_context=?::jsonb,lease_owner=null,lease_until=null"
                                        + " where id=? and status='RUNNING'",
                                    Json.write(context),
                                    call.get("node_instance_id"));
                    store.event(
                            rid,
                            "WAITING_APPROVAL",
                            Map.of(
                                    "nodeInstanceId",
                                    call.get("node_instance_id"),
                                    "callId",
                                    id,
                                    "snapshotDigest",
                                    Digest.sha256(Json.canonical(context)),
                                    "confirmRoles",
                                    confirmation.path("roles")));
                    return null;
                });
    }

    private static String bindingRefs(JsonNode refs) {
        return refs.isArray() && !refs.isEmpty() ? Json.write(refs) : null;
    }

    private static boolean sameRefs(Object stored, String incoming) {
        String existing = stored == null ? null : stored.toString();
        if (existing == null || incoming == null) return existing == null && incoming == null;
        return Digest.sha256(Json.canonical(Json.read(existing)))
                .equals(Digest.sha256(Json.canonical(Json.read(incoming))));
    }

    private Map<String, Object> view(Map<String, Object> call) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field :
                List.of(
                        "status",
                        "idempotency_key",
                        "mock_snapshot",
                        "debug_controls_revision",
                        "mock_source_call_id",
                        "mock_source_digest",
                        "claimed_by",
                        "claimed_lease",
                        "error_semantic",
                        "duration_ms",
                        "is_mock",
                        "skill_version_id",
                        "skill_code",
                        "skill_contract_sha256",
                        "args_digest",
                        "node_instance_id",
                        "attempt_id",
                        "run_id")) result.put(field, call.get(field));
        var run =
                store.db()
                        .queryForMap(
                                "select"
                                    + " public_id,status,trigger_type,controlled_draft_skill_version_id,controlled_draft_skill_digest,controlled_skill_test_version_id,controlled_skill_test_digest,controlled_skill_test_status,snapshot"
                                    + " from engine.run where id=?",
                                call.get("run_id"));
        result.put("runId", run.get("public_id"));
        result.put("runStatus", run.get("status"));
        result.put("trigger_type", run.get("trigger_type"));
        result.put(
                "controlled_draft_skill_version_id", run.get("controlled_draft_skill_version_id"));
        result.put("controlled_draft_skill_digest", run.get("controlled_draft_skill_digest"));
        result.put("controlled_skill_test_version_id", run.get("controlled_skill_test_version_id"));
        result.put("controlled_skill_test_digest", run.get("controlled_skill_test_digest"));
        result.put("controlled_skill_test_status", run.get("controlled_skill_test_status"));
        result.put(
                "controlled_draft_skill_contract",
                run.get("controlled_draft_skill_version_id") == null
                        ? null
                        : TraceStore.json(run.get("snapshot")).path("controlledSkillContract"));
        result.put("mock_snapshot", TraceStore.json(call.get("mock_snapshot")));
        result.put("callId", call.get("id"));
        result.put("result", TraceStore.json(call.get("result")));
        // PLAT-04：externalJob 是引擎对外的唯一任务态投影（远端 id / 状态 / 提交与最近轮询
        // 时刻）。没有外部任务时显式 null，调用方不会把它误读成字段缺失。
        // TraceStore.json(null) 返回 {} 而不是 null；这里显式构造 null 节点，保证
        // "没有外部任务"在协议上是 null，而不是一个空对象。
        result.put(
                "externalJob",
                call.get("external_job") == null
                        ? Json.read("null")
                        : TraceStore.json(call.get("external_job")));
        result.put("arguments", TraceStore.json(call.get("args")));
        // INT-02：调用记录上钉住的受控连接引用（未引用时为 null，不是空数组）——
        // 调用方读它就是引擎的权威引用，不需要也不允许自己再猜一次。
        result.put(
                "bindingRefs",
                call.get("binding_refs") == null
                        ? Json.read("null")
                        : TraceStore.json(call.get("binding_refs")));
        return result;
    }

    private Map<String, Object> withClaim(Map<String, Object> result, boolean claimed) {
        result.put("claimed", claimed);
        return result;
    }
}
