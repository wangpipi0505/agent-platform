package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/** Recovery reads durable skill evidence; it never invokes the business executor again. */
@Service
public class ExecutionRecovery {
    private final TraceStore store;
    private final RuntimeClients clients;

    public ExecutionRecovery(TraceStore store, ToolCalls calls, RuntimeClients clients) {
        this.store = store;
        this.clients = clients;
    }

    @Scheduled(fixedDelay = 3000)
    public void recover() {
        var candidates =
                store.db()
                        .queryForList(
                                "select t.id,r.tenant_id,r.principal_id from engine.tool_call t"
                                    + " join engine.run r on r.id=t.run_id join"
                                    + " engine.node_instance n on n.id=t.node_instance_id where"
                                    + " r.status='WAITING_EXTERNAL' and n.status='WAITING_EXTERNAL'"
                                    + " and t.status in ('SUBMITTED','EXECUTING','UNKNOWN') and"
                                    + " (t.recovery_next_at is null or t.recovery_next_at<=now())"
                                    + " order by t.recovery_next_at nulls first,t.id limit 16");
        for (var row : candidates) {
            long id = TraceStore.number(row, "id");
            // Retain the original ownership, but reconstruct no business approval privileges.
            Actor actor =
                    new Actor(
                            row.get("tenant_id").toString(),
                            row.get("principal_id").toString(),
                            Set.of(),
                            Map.of());
            try {
                adopt(id, null, actor);
            } catch (Exception failure) {
                store.db()
                        .update(
                                "update engine.tool_call set"
                                    + " recovery_attempts=recovery_attempts+1,recovery_next_at=now()+interval"
                                    + " '30 seconds',recovery_error=? where id=? and status in"
                                    + " ('SUBMITTED','EXECUTING','UNKNOWN')",
                                failure.getClass().getSimpleName(),
                                id);
            }
        }
    }

    public Map<String, Object> adopt(long id, String factId, Actor actor) {
        JsonNode proof = clients.get("skill", "/internal/skill-facts/" + id, actor);
        ObjectNode body = (ObjectNode) proof.deepCopy();
        String hash = body.path("factSha256").asText();
        body.remove("factSha256");
        require(Digest.sha256(Json.canonical(body)).equals(hash), "事实摘要不匹配");
        if (factId != null) require(factId.equals(proof.path("factId").asText()), "事实标识不匹配");
        return store.atomic(
                () -> {
                    var owned =
                            store.db()
                                    .queryForMap(
                                            "select t.run_id,t.node_instance_id from"
                                                + " engine.tool_call t join engine.run r on"
                                                + " r.id=t.run_id where t.id=? and r.tenant_id=?"
                                                + " and r.principal_id=?",
                                            id,
                                            actor.tenantId(),
                                            actor.principalId());
                    long rid = TraceStore.number(owned, "run_id"),
                            nid = TraceStore.number(owned, "node_instance_id");
                    // Same run → node → call order as cancel and submission grants (runtime
                    // security R4).
                    var run = store.lockRun(rid);
                    var node =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.node_instance where id=? for"
                                                    + " update",
                                            nid);
                    var call =
                            store.db()
                                    .queryForMap(
                                            "select * from engine.tool_call where id=? for update",
                                            id);
                    if (Set.of("SUCCEEDED", "FAILED").contains(call.get("status")))
                        return Map.of("adopted", false, "status", call.get("status"));
                    require(
                            proof.path("callId").asLong() == id
                                    && proof.path("nodeInstanceId").asLong() == nid
                                    && proof.path("runId").asText().equals(run.get("public_id")),
                            "事实运行归属不匹配");
                    require(
                            proof.path("skillVersionId").asLong()
                                            == TraceStore.number(call, "skill_version_id")
                                    && proof.path("argsDigest")
                                            .asText()
                                            .equals(call.get("args_digest")),
                            "事实参数或版本不匹配");
                    require(
                            proof.path("skillContractSha256").asText().matches("[a-f0-9]{64}")
                                    && proof.path("skillContractSha256")
                                            .asText()
                                            .equals(call.get("skill_contract_sha256")),
                            "事实合同不匹配");
                    require(
                            proof.path("attemptId").asLong()
                                    == TraceStore.number(call, "attempt_id"),
                            "事实调用代次不匹配");
                    String trigger = run.get("trigger_type").toString();
                    String kind = Set.of("CHAT", "TASK").contains(trigger) ? "FORMAL" : trigger;
                    require(
                            kind.equals(proof.path("callKind").asText())
                                    && (!proof.path("isMock").asBoolean()
                                            || trigger.equals("DEBUG")),
                            "事实触发类型不匹配");
                    String status = proof.path("status").asText();
                    require(Set.of("SUCCEEDED", "FAILED").contains(status), "事实尚未确定");
                    if (!run.get("status").equals("WAITING_EXTERNAL")
                            || !node.get("status").equals("WAITING_EXTERNAL"))
                        return Map.of("adopted", false, "status", run.get("status"));
                    store.db()
                            .update(
                                    "update engine.run set status='RUNNING',dispatch_next_at=now()"
                                            + " where id=?",
                                    rid);
                    store.db()
                            .update(
                                    "update engine.node_instance set"
                                        + " status='READY',lease_owner=null,lease_until=null,released=true"
                                        + " where id=?",
                                    nid);
                    var lease = store.claim(nid, "proof_recovery");
                    require(lease != null, "无法取得恢复租约");
                    long attempt = TraceStore.number(lease, "attemptId");
                    String errorSemantic =
                            status.equals("FAILED")
                                    ? proof.path("errorSemantic")
                                            .asText(
                                                    proof.path("result")
                                                            .path("error")
                                                            .path("semantic")
                                                            .asText(
                                                                    proof.path("result")
                                                                            .path("error")
                                                                            .path("code")
                                                                            .asText(
                                                                                    "NON_RETRYABLE_FAILURE")))
                                    : null;
                    if (errorSemantic != null && !errorSemantic.matches("[A-Z_]{1,32}"))
                        errorSemantic = "NON_RETRYABLE_FAILURE";
                    // The proof establishes an execution fact only. Logical output still passes the
                    // driver
                    // CAS.
                    store.db()
                            .update(
                                    "update engine.tool_call set"
                                        + " status=?,result=?::jsonb,result_ref=?,error_semantic=?,duration_ms=greatest(coalesce(duration_ms,0),?),is_mock=?,attempt_id=?,claimed_by=?,claimed_lease=?,recovery_error=null,recovery_next_at=null"
                                        + " where id=?",
                                    status,
                                    Json.write(proof.path("result")),
                                    "skill-proof:" + proof.path("factId").asText(),
                                    errorSemantic,
                                    Math.max(0, proof.path("durationMs").asInt()),
                                    proof.path("isMock").asBoolean(),
                                    attempt,
                                    lease.get("lease_owner"),
                                    lease.get("lease_until"),
                                    id);
                    store.db()
                            .update(
                                    "insert into"
                                        + " engine.attempt_note(node_instance_id,attempt_id,kind,payload)"
                                        + " values(?,?,'VERIFIED_FACT_ADOPTED',?::jsonb)",
                                    nid,
                                    attempt,
                                    Json.write(proof));
                    store.event(
                            rid,
                            "EXTERNAL_FACT_ADOPTED",
                            Map.of(
                                    "callId",
                                    id,
                                    "factId",
                                    proof.path("factId").asText(),
                                    "attemptId",
                                    attempt));
                    store.db()
                            .update(
                                    "update engine.attempt set status='SUPERSEDED',ended_at=now()"
                                            + " where id=?",
                                    attempt);
                    store.db()
                            .update(
                                    "update engine.node_instance set"
                                        + " status='READY',lease_owner=null,lease_until=null,released=true"
                                        + " where id=?",
                                    nid);
                    return Map.of("adopted", true, "status", status);
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new PlatformException(409, "FACT_PROOF_INVALID", message);
    }
}
