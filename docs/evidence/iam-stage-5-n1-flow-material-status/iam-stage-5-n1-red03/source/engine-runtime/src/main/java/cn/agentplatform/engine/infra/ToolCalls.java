package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ToolCalls {
    private final TraceStore store;

    public ToolCalls(TraceStore s) {
        store = s;
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
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id)"
                                        + " values(?,?,?,?,?,? ,?,'REGISTERED',?,?::jsonb,?) on"
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
                                    req.path("attemptId").asLong());
                    var call =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.tool_call where idempotency_key=?"
                                                + " for update",
                                            key);
                    if (!digest.equals(call.get("args_digest")))
                        throw new PlatformException(409, "CALL_ARGUMENTS_MISMATCH", "相同逻辑调用参数发生变化");
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

    private void validateLease(long nid, long rid, long attempt, String owner) {
        Boolean valid =
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
        return store.atomic(
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
                    return Map.of(
                            "approved",
                            approved,
                            "callId",
                            id,
                            "skillVersionId",
                            call.get("skill_version_id"),
                            "parametersDigest",
                            call.get("args_digest"),
                            "roles",
                            context.path("roles").isArray() ? context.path("roles") : List.of());
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
                    for (JsonNode skill : frozen.path("config").path("tools"))
                        if (skill.path("skillVersionId").asLong()
                                == TraceStore.number(call, "skill_version_id"))
                            skill.path("execution")
                                    .path("confirmRoles")
                                    .forEach(role -> declaredRoles.add(role.asText()));
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
                    context.put("expiresAt", java.time.Instant.now().plusSeconds(3600).toString());
                    context.put("status", "PENDING");
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
                                    + " public_id,trigger_type,controlled_draft_skill_version_id,controlled_draft_skill_digest,controlled_skill_test_version_id,controlled_skill_test_digest,controlled_skill_test_status,snapshot"
                                    + " from engine.run where id=?",
                                call.get("run_id"));
        result.put("runId", run.get("public_id"));
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
        result.put("arguments", TraceStore.json(call.get("args")));
        return result;
    }

    private Map<String, Object> withClaim(Map<String, Object> result, boolean claimed) {
        result.put("claimed", claimed);
        return result;
    }
}
