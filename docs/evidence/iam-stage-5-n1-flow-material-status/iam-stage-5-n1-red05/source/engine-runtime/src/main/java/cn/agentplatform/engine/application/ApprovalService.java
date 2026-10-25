package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Engine-owned approval authority; a builder role never implies permission to approve (§31.1.8).
 */
@Service
public class ApprovalService {
    private final TraceStore store;

    public ApprovalService(TraceStore store) {
        this.store = store;
    }

    public List<Map<String, Object>> list(Actor actor) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row :
                store.db()
                        .queryForList(
                                "select r.public_id as \"runId\",r.app_code as \"appCode\",n.id as"
                                    + " \"nodeInstanceId\",n.node_def_id as"
                                    + " \"nodeDefId\",n.definition,n.approval_context,n.started_at"
                                    + " as \"createdAt\",coalesce((n.approval_context->>'expiresAt')::timestamptz,n.started_at+make_interval(mins=>coalesce((n.definition->>'timeoutMin')::int,60)))"
                                    + " as \"expiresAt\",n.status,(select a.request_snapshot from"
                                    + " engine.attempt a where a.node_instance_id=n.id order by"
                                    + " a.attempt_no desc limit 1) as snapshot from"
                                    + " engine.node_instance n join engine.run r on r.id=n.run_id"
                                    + " where r.tenant_id=? and n.status='WAITING_APPROVAL' order"
                                    + " by n.id",
                                actor.tenantId())) {
            JsonNode definition = TraceStore.json(row.remove("definition")),
                    context = TraceStore.json(row.remove("approval_context"));
            JsonNode roles =
                    context.has("callId") ? context.path("roles") : definition.path("confirmRoles");
            if (!permitted(roles, actor)) continue;
            JsonNode snapshot =
                    context.has("callId") ? context : TraceStore.json(row.get("snapshot"));
            row.remove("snapshot");
            row.put("confirmRoles", roles);
            row.put("parameterSnapshot", snapshot);
            row.put("snapshotDigest", Digest.sha256(Json.canonical(snapshot)));
            if (context.has("callId")) row.put("callId", context.path("callId").asLong());
            result.add(row);
        }
        return result;
    }

    private boolean permitted(JsonNode roles, Actor actor) {
        for (JsonNode role : roles) if (actor.roles().contains(role.asText())) return true;
        return false;
    }

    public Map<String, Object> approve(String publicId, long nid, JsonNode request, Actor actor) {
        if (request.path("requestId").asText().isBlank()
                || !request.path("approved").isBoolean()
                || request.path("snapshotDigest").asText().isBlank())
            throw new PlatformException(
                    400, "APPROVAL_INVALID", "确认需要requestId、snapshotDigest与approved");
        return store.atomic(
                () -> {
                    var runs =
                            store.db()
                                    .queryForList(
                                            "select * from engine.run where public_id=? and"
                                                    + " tenant_id=? for update",
                                            publicId,
                                            actor.tenantId());
                    if (runs.isEmpty()) throw new PlatformException(404, "RUN_NOT_FOUND", "确认不存在");
                    var run = runs.getFirst();
                    long rid = TraceStore.number(run, "id");
                    var rows =
                            store.db()
                                    .queryForList(
                                            "select * from engine.node_instance where id=? and"
                                                    + " run_id=? for update",
                                            nid,
                                            rid);
                    if (rows.isEmpty())
                        throw new PlatformException(404, "NODE_NOT_FOUND", "确认节点不存在");
                    var node = rows.getFirst();
                    JsonNode definition = TraceStore.json(node.get("definition")),
                            context = TraceStore.json(node.get("approval_context"));
                    boolean tool = context.has("callId");
                    if (!permitted(
                            tool ? context.path("roles") : definition.path("confirmRoles"), actor))
                        throw new PlatformException(403, "APPROVAL_ROLE_REQUIRED", "当前角色无确认权限");
                    if (!node.get("status").equals("WAITING_APPROVAL"))
                        return Map.of("runId", publicId, "deduped", true);
                    if (Set.of("CANCELLED", "FAILED", "SUCCEEDED").contains(run.get("status")))
                        throw new PlatformException(409, "RUN_TERMINAL", "运行已经结束");
                    boolean expired =
                            tool
                                    ? !Instant.parse(context.path("expiresAt").asText())
                                            .isAfter(Instant.now())
                                    : Boolean.TRUE.equals(
                                            store.db()
                                                    .queryForObject(
                                                            "select"
                                                                + " started_at+make_interval(mins=>coalesce((definition->>'timeoutMin')::int,60))<=now()"
                                                                + " from engine.node_instance where"
                                                                + " id=?",
                                                            Boolean.class,
                                                            nid));
                    if (expired) throw new PlatformException(409, "APPROVAL_EXPIRED", "确认已过期");
                    JsonNode snapshot =
                            tool
                                    ? context
                                    : Json.read(
                                            store.db()
                                                    .queryForObject(
                                                            "select request_snapshot::text from"
                                                                    + " engine.attempt where"
                                                                    + " node_instance_id=? order by"
                                                                    + " attempt_no desc limit 1",
                                                            String.class,
                                                            nid));
                    if (!Digest.sha256(Json.canonical(snapshot))
                            .equals(request.path("snapshotDigest").asText()))
                        throw new PlatformException(409, "APPROVAL_SNAPSHOT_CHANGED", "确认参数摘要不匹配");
                    boolean approved = request.path("approved").asBoolean();
                    if (tool) {
                        ObjectNode decision = (ObjectNode) context.deepCopy();
                        decision.put("status", approved ? "APPROVED" : "REJECTED");
                        decision.put("approvedBy", actor.principalId());
                        decision.put("approvedAt", Instant.now().toString());
                        decision.put("requestId", request.path("requestId").asText());
                        int changed =
                                store.db()
                                        .update(
                                                "update engine.tool_call set"
                                                    + " approval_context=?::jsonb,status=case when"
                                                    + " ? then status else 'FAILED'"
                                                    + " end,error_semantic=case when ? then"
                                                    + " error_semantic else 'NO_PERMISSION' end"
                                                    + " where id=? and run_id=? and"
                                                    + " node_instance_id=? and skill_version_id=?"
                                                    + " and args_digest=? and status='REGISTERED'",
                                                Json.write(decision),
                                                approved,
                                                approved,
                                                context.path("callId").asLong(),
                                                rid,
                                                nid,
                                                context.path("skillVersionId").asLong(),
                                                context.path("parametersDigest").asText());
                        if (changed != 1)
                            throw new PlatformException(
                                    409, "APPROVAL_CALL_CHANGED", "确认调用版本或参数发生变化");
                    }
                    // Approval only requeues work. The new owner still performs the standard
                    // logical-result
                    // CAS.
                    store.db()
                            .update(
                                    "update engine.node_instance set"
                                            + " status=?,released=true,error=?::jsonb,ended_at=case"
                                            + " when ? then null else now() end where id=?",
                                    approved ? "READY" : "FAILED",
                                    approved
                                            ? null
                                            : Json.write(
                                                    Map.of(
                                                            "semantic",
                                                            "APPROVAL_REJECTED",
                                                            "message",
                                                            "确认人拒绝")),
                                    approved,
                                    nid);
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("nodeInstanceId", nid);
                    event.put("approved", approved);
                    event.put("requestId", request.path("requestId").asText());
                    event.put("approvedBy", actor.principalId());
                    if (tool) event.put("callId", context.path("callId").asLong());
                    store.event(rid, "APPROVAL_DONE", event);
                    store.db()
                            .update(
                                    "update engine.run set status='RUNNING',dispatch_next_at=now()"
                                            + " where id=? and status='WAITING_APPROVAL'",
                                    rid);
                    return Map.of("runId", publicId, "status", "RUNNING");
                });
    }

    @Scheduled(fixedDelay = 60000)
    public void expire() {
        for (var node :
                store.db()
                        .queryForList(
                                "select n.id,n.run_id from engine.node_instance n join engine.run r"
                                    + " on r.id=n.run_id where n.status='WAITING_APPROVAL' and"
                                    + " r.status not in ('SUCCEEDED','FAILED','CANCELLED') and"
                                    + " coalesce((n.approval_context->>'expiresAt')::timestamptz,n.started_at+make_interval(mins=>coalesce((n.definition->>'timeoutMin')::int,60)))<=now()"))
            store.atomic(
                    () -> {
                        long rid = TraceStore.number(node, "run_id");
                        store.lockRun(rid);
                        int changed =
                                store.db()
                                        .update(
                                                "update engine.node_instance set"
                                                    + " status='FAILED',error='{\"semantic\":\"APPROVAL_TIMEOUT\",\"message\":\"确认超时\"}'::jsonb,ended_at=now()"
                                                    + " where id=? and status='WAITING_APPROVAL'",
                                                node.get("id"));
                        if (changed > 0) {
                            store.event(
                                    rid,
                                    "APPROVAL_DONE",
                                    Map.of(
                                            "nodeInstanceId",
                                            node.get("id"),
                                            "approved",
                                            false,
                                            "reason",
                                            "APPROVAL_TIMEOUT"));
                            store.db()
                                    .update(
                                            "update engine.run set"
                                                + " status='RUNNING',dispatch_next_at=now() where"
                                                + " id=? and status='WAITING_APPROVAL'",
                                            rid);
                        }
                        return null;
                    });
    }
}
