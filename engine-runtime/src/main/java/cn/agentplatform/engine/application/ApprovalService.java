package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;
import cn.agentplatform.engine.infra.HighRiskApprovals;

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
            if (context.has("actionPlanId")) {
                var plans = store.db().queryForList("select * from engine.action_plan where id=? and run_id=(select id from engine.run where public_id=? and tenant_id=?)",
                        context.path("actionPlanId").asLong(), row.get("runId"), actor.tenantId());
                if (!plans.isEmpty() && "HIGH".equals(plans.getFirst().get("risk"))) {
                    var plan = plans.getFirst();
                    boolean current = HighRiskApprovals.POLICY.equals(plan.get("approval_policy"));
                    var votes = current ? HighRiskApprovals.votes(plan, actor.tenantId()) : Json.tree(List.of());
                    row.put("approvalProgress", Map.of("policy", plan.get("approval_policy"), "required", 2,
                            "confirmedCount", votes.size(), "principals", votes.findValuesAsText("principalId"), "requiresReprepare", !current));
                }
            }
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
                    // PLAT-15（契约 §14.5）：两段计划的审批门。先校验计划与审批节点一致，再校验高危
                    // 确认强度——任何拒绝都发生在任何状态写入之前，副作用不发生。plan 为 null 表示
                    // 旧一段审批（SINGLE_STEP / CONFIRM 节点），沿用既有语义。
                    Map<String, Object> plan = null;
                    if (context.has("actionPlanId")) {
                        var plans =
                                store.db()
                                        .queryForList(
                                                "select * from engine.action_plan where id=? for"
                                                    + " update",
                                                context.path("actionPlanId").asLong());
                        if (plans.isEmpty()
                                || !context
                                        .path("actionPlanDigest")
                                        .asText()
                                        .equals(plans.getFirst().get("digest")))
                            throw new PlatformException(
                                    409,
                                    "ACTION_PLAN_DIGEST_MISMATCH",
                                    "确认计划与审批节点不一致，请刷新确认列表后重试");
                        plan = plans.getFirst();
                        if (TraceStore.number(plan, "run_id") != rid || TraceStore.number(plan, "node_instance_id") != nid
                                || tool && TraceStore.number(plan, "call_id") != context.path("callId").asLong())
                            throw new PlatformException(409, "ACTION_PLAN_DIGEST_MISMATCH", "确认计划归属不一致");
                    }
                    if (plan != null && "HIGH".equals(plan.get("risk"))) {
                        if (request.path("requestId").asText().length() > 128)
                            throw new PlatformException(400, "APPROVAL_INVALID", "确认请求号过长");
                        request.fieldNames().forEachRemaining(key -> {
                            if (!Set.of("requestId", "approved", "snapshotDigest", "doubleConfirmed").contains(key))
                                throw new PlatformException(400, "APPROVAL_INVALID", "高危审批不得自报确认人、票据或审批材料");
                        });
                        Actor voter = HighRiskApprovals.verifiedVoter(actor);
                        if (approved) {
                            HighRiskApprovals.requirePolicy(plan);
                            var votes = (com.fasterxml.jackson.databind.node.ArrayNode) HighRiskApprovals.votes(plan, actor.tenantId()).deepCopy();
                            boolean existingVoter = false;
                            for (var vote : votes) if (voter.principalId().equals(vote.path("principalId").asText())) existingVoter = true;
                            if (existingVoter)
                                return Map.of("runId", publicId, "status", "WAITING_APPROVAL", "approvalCount", votes.size(), "requiredApprovals", 2, "deduped", true);
                            votes.add(Json.tree(Map.of("tenantId", voter.tenantId(), "principalId", voter.principalId(),
                                    "approved", true, "planDigest", plan.get("digest"), "requestId", request.path("requestId").asText(),
                                    "approvedAt", Instant.now().toString())));
                            // 审批事实只由当前已验请求身份产生；原行锁与 JSON 版本 CAS 一起避免重复计人。
                            int recorded = store.db().update("update engine.action_plan set approvals=?::jsonb where id=? and digest=? and approvals=?::jsonb and status='PREPARED'",
                                    Json.write(votes), plan.get("id"), plan.get("digest"), Json.write(TraceStore.json(plan.get("approvals"))));
                            if (recorded != 1) throw new PlatformException(409, "APPROVAL_SNAPSHOT_CHANGED", "审批事实已变化，请刷新后重试");
                            plan.put("approvals", votes);
                            if (votes.size() < 2) {
                                store.event(rid, "APPROVAL_RECORDED", Map.of("nodeInstanceId", nid, "actionPlanId", plan.get("id"),
                                        "approvedBy", voter.principalId(), "requestId", request.path("requestId").asText(), "approvalCount", votes.size(), "requiredApprovals", 2));
                                return Map.of("runId", publicId, "status", "WAITING_APPROVAL", "approvalCount", votes.size(), "requiredApprovals", 2);
                            }
                        }
                    }
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
                    // PLAT-15 审计：confirmBy/confirmAt 落计划行，actionPlanId/risk 随 APPROVAL_DONE
                    // 事件进运行事件流（方案 §4.15 第5项 runId/actionPlanId/confirmBy/confirmAt/result）。
                    if (plan != null) {
                        store.db()
                                .update(
                                        "update engine.action_plan set status=?,confirmed_by=?,confirmed_at=now(),double_confirmed=?"
                                            + " where id=?",
                                        approved ? "APPROVED" : "REJECTED",
                                        actor.principalId(),
                                        !"HIGH".equals(plan.get("risk")) && request.path("doubleConfirmed").asBoolean(false),
                                        plan.get("id"));
                        event.put("actionPlanId", TraceStore.number(plan, "id"));
                        event.put("risk", plan.get("risk"));
                        if ("HIGH".equals(plan.get("risk"))) {
                            event.put("approvalPolicy", plan.get("approval_policy"));
                            event.put("approvalCount", TraceStore.json(plan.get("approvals")).size());
                        }
                    }
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
