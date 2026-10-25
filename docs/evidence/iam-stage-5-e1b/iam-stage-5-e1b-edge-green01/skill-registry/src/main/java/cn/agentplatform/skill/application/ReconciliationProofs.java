package cn.agentplatform.skill.application;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.*;

/** Immutable external/local facts, never an authorization to dispatch or commit engine state. */
@Service
public class ReconciliationProofs {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public ReconciliationProofs(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
    }

    static void service() {
        // ADMIN is not an internal-service capability.
        if (!Actor.current().roles().contains("SERVICE"))
            throw error(403, "RECONCILE_SERVICE_REQUIRED", "仅内部服务可核实或读取对账证明");
    }

    static void requireProofTicket() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // A portal token may carry a SERVICE-named role. Proof operations require the verified
        // internal ticket's purpose and audience as well, matching the callback trust boundary.
        if (auth == null
                || !(auth.getPrincipal() instanceof Jwt jwt)
                || !"service".equals(jwt.getClaimAsString("token_use"))
                || !jwt.getAudience().contains("agent-platform-internal"))
            throw error(403, "RECONCILE_SERVICE_REQUIRED", "对账证明需要可信内部服务票据");
        service();
    }

    public Map<String, Object> get(long call, long attempt) {
        requireProofTicket();
        var row = find(call, attempt);
        if (row == null) throw error(404, "RECONCILIATION_PROOF_NOT_FOUND", "原代对账证明不存在");
        var result = new LinkedHashMap<>(Json.read(row.get("evidence").toString(), Map.class));
        result.put("proofSha256", row.get("evidence_sha256"));
        result.put("retryBusinessAllowed", false);
        return result;
    }

    private Map<String, Object> find(long call, long attempt) {
        var a = Actor.current();
        var rows =
                db.queryForList(
                        "select * from skill.reconciliation_proof where tenant_id=? and"
                                + " principal_id=? and call_id=? and attempt_id=?",
                        a.tenantId(),
                        a.principalId(),
                        call,
                        attempt);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> bindingCurrent(Map<String, Object> fact, Map<String, Object> contract) {
        var a = Actor.current();
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and"
                                + " principal_id=? and call_id=?",
                        a.tenantId(),
                        a.principalId(),
                        fact.get("call_id"));
        if (rows.isEmpty()) {
            if ("ALGORITHM".equals(contract.get("type")))
                throw new cn.agentplatform.skill.infra.SkillAdapters.Failure(
                        "RESULT_UNKNOWN", true, "LEGACY_BINDING: 缺少当前主体的可信算法原绑定，禁止补建或猜回填");
            throw stale();
        }
        var b = rows.getFirst();
        checkBinding(b, fact, contract);
        return b;
    }

    private static void checkBinding(
            Map<String, Object> b, Map<String, Object> fact, Map<String, Object> contract) {
        if ("ALGORITHM".equals(contract.get("type")))
            cn.agentplatform.skill.infra.AlgorithmAdapter.validateBinding(b, contract);
        var original = Json.read(fact.get("request_snapshot").toString());
        if (!Objects.equals(b.get("attempt_id"), fact.get("attempt_id"))
                || !Objects.equals(b.get("skill_version_id"), fact.get("skill_version_id"))
                || !Objects.equals(b.get("run_id"), fact.get("run_id"))
                || !Objects.equals(
                        b.get("idempotency_key"), original.path("idempotencyKey").asText())
                || !Objects.equals(
                        Json.read(b.get("metadata").toString()).path("contractSha256").asText(),
                        contract.get("sha256"))
                || !sameSnapshot(
                        b.get("request_snapshot").toString(),
                        fact.get("request_snapshot").toString(),
                        contract)) throw stale();
    }

    private static boolean sameSnapshot(String left, String right, Map<String, Object> contract) {
        var a = new LinkedHashMap<>(Json.read(left, Map.class));
        var b = new LinkedHashMap<>(Json.read(right, Map.class));
        var ag = a.remove("guardedArguments");
        var bg = b.remove("guardedArguments");
        if (!Json.canonical(a).equals(Json.canonical(b))) return false;
        try {
            // Attribute scopes are sets: a reordered frozen scope remains the same original job.
            SkillExecution.requireHistoricalScope(ag, object(bg), object(contract.get("guard")));
            SkillExecution.requireHistoricalScope(bg, object(ag), object(contract.get("guard")));
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * External HTTP and engine checks finish before this short, generation-fenced transaction.
     * Compared with Spring 6.2.7 TransactionTemplate.execute: exceptions roll the callback back;
     * JdbcTemplate participates through DataSourceUtils. Only local PG operations belong here.
     */
    Map<String, Object> store(
            Map<String, Object> originalFact,
            Map<String, Object> contract,
            Map<String, Object> source,
            boolean asynchronous) {
        requireProofTicket();
        long call = ((Number) originalFact.get("call_id")).longValue();
        long attempt = ((Number) originalFact.get("attempt_id")).longValue();
        var original = Json.read(originalFact.get("request_snapshot").toString());
        if (original.path("nodeInstanceId").asLong() < 1
                || !Objects.equals(
                        originalFact.get("args_digest"),
                        Digest.sha256(Json.canonical(original.path("arguments"))))) throw stale();
        var actor = Actor.current();
        return tx.execute(
                t -> {
                    var facts =
                            db.queryForList(
                                    "select * from skill.execution_fact where tenant_id=? and"
                                            + " principal_id=? and call_id=? for update",
                                    actor.tenantId(),
                                    actor.principalId(),
                                    call);
                    if (facts.isEmpty()) throw stale();
                    var current = facts.getFirst();
                    for (String field :
                            List.of(
                                    "attempt_id",
                                    "skill_version_id",
                                    "args_digest",
                                    "skill_contract_sha256",
                                    "run_id"))
                        if (!Objects.equals(originalFact.get(field), current.get(field)))
                            throw stale();
                    if (!"UNKNOWN".equals(current.get("status"))
                            || Boolean.TRUE.equals(current.get("is_mock"))
                            || !Json.canonical(original)
                                    .equals(
                                            Json.canonical(
                                                    Json.read(
                                                            current.get("request_snapshot")
                                                                    .toString())))
                            || !Objects.equals(
                                    current.get("skill_contract_sha256"), contract.get("sha256")))
                        throw stale();
                    if (asynchronous) {
                        var bindings =
                                db.queryForList(
                                        "select * from skill.external_job_binding where tenant_id=?"
                                                + " and principal_id=? and call_id=? for update",
                                        actor.tenantId(),
                                        actor.principalId(),
                                        call);
                        if (bindings.isEmpty()) throw stale();
                        var b = bindings.getFirst();
                        checkBinding(b, current, contract);
                        // A K8s 404 or Failed condition never proves absence of business effects.
                        if ("ALGORITHM".equals(contract.get("type"))
                                && !"NOT_SENT".equals(source.get("outcome"))) throw stale();
                        if ("ALGORITHM".equals(contract.get("type"))) {
                            if (b.get("external_job_uid") != null
                                    || !Objects.equals(
                                            b.get("external_job_id"), source.get("externalJobId"))
                                    || !Objects.equals(
                                            b.get("external_api_sha256"),
                                            source.get("externalApiSha256"))
                                    || !Objects.equals(
                                            b.get("external_namespace"),
                                            source.get("externalNamespace"))
                                    || !Objects.equals(
                                            b.get("external_identity_sha256"),
                                            source.get("externalIdentitySha256"))) throw stale();
                        }
                        if ("NOT_SENT".equals(source.get("outcome"))) {
                            if (!"NOT_SENT".equals(b.get("status"))
                                    || !Boolean.FALSE.equals(b.get("send_started"))) throw stale();
                        } else if (!Boolean.TRUE.equals(b.get("send_started"))
                                || !Set.of("SENDING", "SUBMITTED")
                                        .contains(Objects.toString(b.get("status")))
                                || !Objects.equals(
                                        b.get("external_job_id"), source.get("externalJobId")))
                            throw stale();
                    }
                    var identity = new LinkedHashMap<String, Object>();
                    identity.put("tenantId", actor.tenantId());
                    identity.put("principalId", actor.principalId());
                    identity.put("runId", current.get("run_id"));
                    identity.put("nodeInstanceId", original.path("nodeInstanceId").asLong());
                    identity.put("callId", call);
                    identity.put("attemptId", attempt);
                    identity.put("skillVersionId", current.get("skill_version_id"));
                    identity.put("skillContractSha256", current.get("skill_contract_sha256"));
                    identity.put("argsDigest", current.get("args_digest"));
                    identity.put(
                            "guardedArgsDigest",
                            Digest.sha256(Json.canonical(original.path("guardedArguments"))));
                    identity.put("idempotencyKey", original.path("idempotencyKey").asText());
                    identity.put("originalFactStatus", "UNKNOWN");
                    identity.putAll(source);
                    var old = find(call, attempt);
                    if (old != null) {
                        var frozen =
                                new LinkedHashMap<>(
                                        Json.read(old.get("evidence").toString(), Map.class));
                        for (String key : List.of("proofId", "proofRef", "verifiedAt"))
                            frozen.remove(key);
                        var observed = new LinkedHashMap<>(identity);
                        // An ACK-lost lookup by key may establish jobId; later reads then use the
                        // frozen
                        // job endpoint from the same immutable contract. Keep the first URI
                        // provenance,
                        // while the exact key/job, external state and response digest must still
                        // agree.
                        frozen.remove("queryEndpointSha256");
                        observed.remove("queryEndpointSha256");
                        if (!Json.canonical(frozen).equals(Json.canonical(observed)))
                            throw error(409, "RECONCILIATION_PROOF_CONFLICT", "原代已有不同不可变证明，请人工核实");
                        return get(call, attempt);
                    }
                    var generated =
                            db.queryForMap(
                                    "select"
                                        + " nextval(pg_get_serial_sequence('skill.reconciliation_proof','id'))"
                                        + " as id, clock_timestamp() as verified_at");
                    long id = ((Number) generated.get("id")).longValue();
                    var at = (Timestamp) generated.get("verified_at");
                    identity.put("proofId", id);
                    identity.put("proofRef", "srp:" + id);
                    identity.put("verifiedAt", at.toInstant().toString());
                    String sha = Digest.sha256(Json.canonical(identity));
                    db.update(
                            "insert into"
                                + " skill.reconciliation_proof(id,tenant_id,principal_id,call_id,attempt_id,outcome,evidence,evidence_sha256,created_at)"
                                + " overriding system value values(?,?,?,?,?,?,?::jsonb,?,?)",
                            id,
                            actor.tenantId(),
                            actor.principalId(),
                            call,
                            attempt,
                            source.get("outcome"),
                            Json.write(identity),
                            sha,
                            at);
                    return get(call, attempt);
                });
    }

    static PlatformException stale() {
        return error(409, "RECONCILIATION_PROOF_STALE", "原代事实或外部绑定已改变，拒绝生成重试证明");
    }

    static void authority(JsonNode a, Map<String, Object> fact, Map<String, Object> contract) {
        var req = Json.read(fact.get("request_snapshot").toString());
        var expected = new LinkedHashMap<String, Object>();
        expected.put("callId", fact.get("call_id"));
        expected.put("runId", fact.get("run_id"));
        expected.put("node_instance_id", req.path("nodeInstanceId"));
        expected.put("attempt_id", fact.get("attempt_id"));
        expected.put("skill_version_id", fact.get("skill_version_id"));
        expected.put("args_digest", fact.get("args_digest"));
        expected.put("skill_contract_sha256", contract.get("sha256"));
        expected.put("idempotency_key", req.path("idempotencyKey"));
        for (var e : expected.entrySet())
            if (!a.path(e.getKey()).asText().equals(Json.tree(e.getValue()).asText()))
                throw stale();
        if (!"UNKNOWN".equals(a.path("status").asText())) throw stale();
    }
}
