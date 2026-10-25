package cn.agentplatform.skill.application;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * External receipts and output acceptance are durable; only engine.fact can commit logical state.
 */
@Service
public class SkillCallbacks {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SkillCatalog catalog;
    private final SkillAdapters adapters;
    private final SkillClients clients;
    private final SkillExecution execution;
    private final String engineUrl;

    public SkillCallbacks(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            SkillCatalog catalog,
            SkillAdapters adapters,
            SkillClients clients,
            SkillExecution execution,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.catalog = catalog;
        this.adapters = adapters;
        this.clients = clients;
        this.execution = execution;
        this.engineUrl = engineUrl;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    private Map<String, Object> binding(long call) {
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?"
                                + " and principal_id=?",
                        tenant(),
                        call,
                        Actor.current().principalId());
        if (rows.isEmpty()) throw error(404, "CALLBACK_BINDING_NOT_FOUND", "原调用绑定不存在");
        return rows.getFirst();
    }

    private Map<String, Object> fact(long call, boolean lock) {
        return db.queryForMap(
                "select * from skill.execution_fact where tenant_id=? and call_id=? and"
                        + " principal_id=?"
                        + (lock ? " for update" : ""),
                tenant(),
                call,
                Actor.current().principalId());
    }

    private Map<String, Object> receipt(long call, String event) {
        return db.queryForMap(
                "select * from skill.callback_receipt where tenant_id=? and call_id=? and"
                        + " event_id=?",
                tenant(),
                call,
                event);
    }

    public Map<String, Object> receive(
            long call, InputStream source, String timestamp, String signature) throws IOException {
        if (!Actor.current().roles().contains("SERVICE"))
            throw error(403, "CALLBACK_SERVICE_REQUIRED", "回调需要可信服务身份");
        var bound = binding(call);
        var contract = catalog.versionId(((Number) bound.get("skill_version_id")).longValue());
        if (!"CALLBACK".equals(contract.get("invocationKind"))
                || !"HTTP".equals(contract.get("type"))
                || !Objects.equals(
                        Json.read(bound.get("metadata").toString()).path("contractSha256").asText(),
                        contract.get("sha256")))
            throw error(409, "CALLBACK_CONTRACT_MISMATCH", "回调契约不匹配");
        try (var signed =
                        SignedCallback.read(
                                source,
                                timestamp,
                                signature,
                                SecretVault.resolve(
                                        text(object(contract.get("callback")), "secretRef")));
                var input = signed.open();
                var payload = AsyncHttpResponse.read(input, "$.result")) {
            JsonNode body = payload.metadata();
            String event = required(body, "eventId"), job = required(body, "jobId");
            match(body, "callId", call);
            match(body, "attemptId", bound.get("attempt_id"));
            match(body, "skillVersionId", bound.get("skill_version_id"));
            match(body, "runId", bound.get("run_id"));
            match(body, "idempotencyKey", bound.get("idempotency_key"));
            if (!Boolean.TRUE.equals(bound.get("send_started"))
                    || "NOT_SENT".equals(bound.get("status")))
                throw error(409, "CALLBACK_NOT_SENT", "原调用没有外发许可");
            if (bound.get("external_job_id") != null && !job.equals(bound.get("external_job_id")))
                throw error(409, "CALLBACK_BINDING_MISMATCH", "回调任务不属于原调用");
            ensureReceipt(call, event, job, bound, signed);
            var original = Json.read(bound.get("request_snapshot").toString(), Map.class);
            Map<String, Object> secured;
            try {
                secured =
                        SkillRules.guard(
                                Actor.current(),
                                object(contract.get("guard")),
                                object(original.get("arguments")));
                SkillRules.validate(contract.get("inputSchema"), secured);
                SkillExecution.requireHistoricalScope(
                        original.get("guardedArguments"), secured, object(contract.get("guard")));
            } catch (Exception denied) {
                note(call, event, "AUTHORIZATION_DENIED");
                throw error(403, "CALLBACK_SCOPE_DENIED", "当前授权不能覆盖原任务范围");
            }
            String prior = receipt(call, event).get("outcome").toString();
            if (Set.of("STORED", "LATE", "SUPERSEDED").contains(prior))
                return response(call, event);
            if (prior.equals("OUTPUT_REJECTED"))
                throw error(422, "CALLBACK_OUTPUT_INVALID", "回调结果验收失败");
            // Both authority requests are outside transactions. Engine's atomic commit closes
            // TOCTOU.
            if (!current(call, bound)) {
                note(call, event, "LATE");
                return response(call, event);
            }
            if (bound.get("external_job_id") == null) {
                note(call, event, "WAITING_BINDING");
                throw error(409, "CALLBACK_BINDING_PENDING", "提交ACK尚未绑定job，请重投相同eventId和原始正文");
            }
            String status = body.path("status").asText();
            String semantic = null;
            if (status.equals("FAILED")) {
                semantic = body.path("errorSemantic").asText();
                if (!Set.of("PARAM_ERROR", "NO_PERMISSION", "QUOTA", "RETRYABLE_FAILURE")
                        .contains(semantic))
                    throw error(422, "CALLBACK_STATUS_INVALID", "失败回调需明确错误语义");
            } else if (!status.equals("SUCCEEDED"))
                throw error(422, "CALLBACK_STATUS_INVALID", "回调必须提供确定终态");
            String owner = UUID.randomUUID().toString();
            if (!claim(call, event, owner)) return response(call, event);
            try {
                SkillAdapters.Raw raw = null;
                // The frozen failure branch produces an empty failure envelope, as synchronous
                // HTTP does. Provider errors need not satisfy the successful output Schema.
                // A supplied failure result was bounded/spooled but is never materialized/exposed.
                if (status.equals("SUCCEEDED")) {
                    try (var result = payload.result()) {
                        raw = adapters.read(result, contract, call, bound.get("run_id").toString());
                    }
                }
                var envelope =
                        execution.envelope(
                                original,
                                contract,
                                status,
                                semantic,
                                status.equals("FAILED") ? "外部任务报告有效失败" : null,
                                raw,
                                false,
                                secured);
                boolean active = current(call, bound);
                store(call, event, owner, bound, status, envelope, active);
            } catch (SkillAdapters.Failure | IllegalArgumentException ex) {
                finishOwned(call, event, owner, "OUTPUT_REJECTED");
                throw error(422, "CALLBACK_OUTPUT_INVALID", "回调结果不符合Schema或返回映射");
            } catch (IOException ex) {
                finishOwned(call, event, owner, "OUTPUT_REJECTED");
                throw error(422, "CALLBACK_OUTPUT_INVALID", "回调缺少有效结果");
            } catch (RuntimeException ex) {
                finishOwned(call, event, owner, "RECEIVED");
                throw ex;
            }
            return response(call, event);
        } catch (IOException ex) {
            throw error(400, "CALLBACK_JSON_INVALID", "回调正文无法完整读取或JSON无效");
        }
    }

    private void ensureReceipt(
            long call, String event, String job, Map<String, Object> b, SignedCallback signed) {
        tx.executeWithoutResult(
                t -> {
                    db.update(
                            "insert into"
                                + " skill.callback_receipt(tenant_id,call_id,event_id,principal_id,run_id,attempt_id,skill_version_id,external_job_id,payload_digest,signed_at,outcome)"
                                + " values(?,?,?,?,?,?,?,?,?,?,'RECEIVED') on conflict do nothing",
                            tenant(),
                            call,
                            event,
                            Actor.current().principalId(),
                            b.get("run_id"),
                            b.get("attempt_id"),
                            b.get("skill_version_id"),
                            job,
                            signed.digest,
                            Timestamp.from(Instant.ofEpochSecond(signed.timestamp)));
                    var r = receipt(call, event);
                    if (!signed.digest.equals(r.get("payload_digest")))
                        throw error(409, "CALLBACK_EVENT_CONFLICT", "同一eventId不能对应不同正文");
                });
    }

    private boolean current(long call, Map<String, Object> b) {
        var authority = clients.get(engineUrl + "/internal/tool-calls/" + call);
        match(authority, "callId", call);
        match(authority, "runId", b.get("run_id"));
        match(authority, "skill_version_id", b.get("skill_version_id"));
        match(authority, "idempotency_key", b.get("idempotency_key"));
        var frozen = Json.read(b.get("request_snapshot").toString());
        match(authority, "args_digest", Digest.sha256(Json.canonical(frozen.path("arguments"))));
        match(
                authority,
                "skill_contract_sha256",
                Json.read(b.get("metadata").toString()).path("contractSha256").asText());
        match(authority, "node_instance_id", frozen.path("nodeInstanceId").asLong());
        if (authority.path("attempt_id").asLong() != ((Number) b.get("attempt_id")).longValue()
                || !Set.of("SUBMITTED", "UNKNOWN").contains(authority.path("status").asText()))
            return false;
        var run =
                clients.get(
                        engineUrl
                                + "/admin/runs/"
                                + java.net.URLEncoder.encode(
                                        b.get("run_id").toString(),
                                        java.nio.charset.StandardCharsets.UTF_8));
        match(run, "runId", b.get("run_id"));
        if (!Set.of("RUNNING", "WAITING_EXTERNAL").contains(run.path("status").asText()))
            return false;
        for (JsonNode node : run.path("nodes"))
            if (node.path("nodeInstanceId").asLong() == frozen.path("nodeInstanceId").asLong())
                return Set.of("RUNNING", "WAITING_EXTERNAL").contains(node.path("status").asText());
        throw error(409, "CALLBACK_AUTHORITY_MISMATCH", "当前运行没有原调用节点");
    }

    private boolean claim(long call, String event, String owner) {
        return Boolean.TRUE.equals(
                tx.execute(
                        t -> {
                            var f = fact(call, true);
                            // Recovery may already have stored a final fact while this receiver
                            // crashed. Reap its expired claim before classifying that receipt.
                            db.update(
                                    "update skill.callback_receipt set"
                                        + " outcome='RECEIVED',processing_owner=null,processing_until=null"
                                        + " where tenant_id=? and call_id=? and"
                                        + " outcome='PROCESSING' and processing_until<now()",
                                    tenant(),
                                    call);
                            var r = receipt(call, event);
                            if (Set.of("STORED", "LATE", "SUPERSEDED").contains(r.get("outcome")))
                                return false;
                            if (!f.get("status").equals("UNKNOWN")) {
                                note(call, event, "SUPERSEDED");
                                return false;
                            }
                            // Lease is durable, but no database lock survives the short claim
                            // transaction.
                            if (db.queryForObject(
                                            "select count(*) from skill.callback_receipt where"
                                                    + " tenant_id=? and call_id=? and"
                                                    + " outcome='PROCESSING'",
                                            Integer.class,
                                            tenant(),
                                            call)
                                    > 0) throw error(409, "CALLBACK_PROCESSING", "原调用结果正在验收，请稍后重投");
                            db.update(
                                    "update skill.callback_receipt set"
                                        + " outcome='PROCESSING',processing_owner=?,processing_until=now()+interval"
                                        + " '5 minutes',updated_at=now() where tenant_id=? and"
                                        + " call_id=? and event_id=?",
                                    owner,
                                    tenant(),
                                    call,
                                    event);
                            return true;
                        }));
    }

    private void store(
            long call,
            String event,
            String owner,
            Map<String, Object> original,
            String status,
            Map<String, Object> envelope,
            boolean active) {
        tx.executeWithoutResult(
                t -> {
                    var f = fact(call, true);
                    var r = receipt(call, event);
                    if (!owner.equals(r.get("processing_owner"))
                            || !"PROCESSING".equals(r.get("outcome"))
                            || !((Timestamp) r.get("processing_until"))
                                    .toInstant()
                                    .isAfter(Instant.now()))
                        throw error(409, "CALLBACK_LEASE_LOST", "结果验收租约已失效，请重投");
                    var b = binding(call);
                    if (!Objects.equals(b.get("external_job_id"), original.get("external_job_id"))
                            || !Objects.equals(b.get("attempt_id"), original.get("attempt_id"))
                            || !Boolean.TRUE.equals(b.get("send_started")))
                        throw error(409, "CALLBACK_BINDING_MISMATCH", "验收期间任务绑定改变");
                    String outcome =
                            !active || !Objects.equals(f.get("attempt_id"), b.get("attempt_id"))
                                    ? "LATE"
                                    : !f.get("status").equals("UNKNOWN") ? "SUPERSEDED" : "STORED";
                    if (outcome.equals("STORED"))
                        db.update(
                                "update skill.execution_fact set"
                                    + " status=?,result=?::jsonb,callback_state='PENDING',callback_error=null,next_delivery_at=now(),updated_at=now()"
                                    + " where tenant_id=? and call_id=? and attempt_id=? and"
                                    + " status='UNKNOWN'",
                                status,
                                Json.write(envelope),
                                tenant(),
                                call,
                                b.get("attempt_id"));
                    finishOwned(call, event, owner, outcome);
                });
    }

    private void finishOwned(long call, String event, String owner, String outcome) {
        db.update(
                "update skill.callback_receipt set"
                    + " outcome=?,processing_owner=null,processing_until=null,updated_at=now()"
                    + " where tenant_id=? and call_id=? and event_id=? and outcome='PROCESSING' and"
                    + " processing_owner=?",
                outcome,
                tenant(),
                call,
                event,
                owner);
    }

    private void note(long call, String event, String outcome) {
        db.update(
                "update skill.callback_receipt set outcome=?,updated_at=now() where tenant_id=? and"
                        + " call_id=? and event_id=? and outcome in"
                        + " ('RECEIVED','WAITING_BINDING','AUTHORIZATION_DENIED')",
                outcome,
                tenant(),
                call,
                event);
    }

    private Map<String, Object> response(long call, String event) {
        var r = receipt(call, event);
        var f = fact(call, false);
        String delivery = f.get("callback_state").toString();
        return Map.of(
                "callId",
                call,
                "eventId",
                event,
                "outcome",
                r.get("outcome"),
                "deliveryState",
                delivery,
                // Recovery may already have adopted the fact before this delivery was late.
                // This flag describes only the outbox ACK; engine owns the logical result.
                "deliveryAccepted",
                r.get("outcome").equals("STORED") && delivery.equals("DELIVERED"));
    }

    private String required(JsonNode body, String field) {
        var value = body.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128)
            throw error(400, "CALLBACK_INPUT_INVALID", "回调标识缺失或过长");
        return value.asText();
    }

    private void match(JsonNode body, String field, Object expected) {
        JsonNode value = body.path(field);
        boolean same =
                expected instanceof Number n
                        ? value.isIntegralNumber()
                                && value.canConvertToLong()
                                && value.longValue() == n.longValue()
                        : value.isTextual() && value.asText().equals(expected);
        if (!same) throw error(409, "CALLBACK_BINDING_MISMATCH", "回调与原调用归属不一致: " + field);
    }
}
