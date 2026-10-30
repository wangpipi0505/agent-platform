package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HIGH 的本地事实与在线复核桥；不为旧工具路径构造原运行委托，也不扩大 IAM 有限域。 */
public final class HighRiskApprovals {
    public static final String POLICY = "TWO_PRINCIPALS_V1";
    private final TraceStore store;
    private final CurrentAuthorityClient client;
    private final Clock clock;

    public HighRiskApprovals(TraceStore store, CurrentAuthorityClient client) {
        this.store = store; this.client = client; this.clock = Clock.systemUTC();
    }

    public static void requirePolicy(Map<String, Object> plan) {
        if (!POLICY.equals(plan.get("approval_policy"))
                || !POLICY.equals(TraceStore.json(plan.get("plan")).path("approvalPolicy").asText()))
            throw new PlatformException(409, "ACTION_PLAN_REPREPARE_REQUIRED", "旧高危计划未采用双主体政策，请重新发起调用与 PREPARE；原计划摘要及幂等键保留");
        var body = TraceStore.json(plan.get("plan"));
        if (!"HIGH".equals(body.path("risk").asText())
                || !Objects.equals(plan.get("digest"), Digest.sha256(Json.canonical(body))))
            throw new PlatformException(409, "ACTION_PLAN_DIGEST_MISMATCH", "高危计划正文与冻结摘要不一致");
    }

    public static JsonNode votes(Map<String, Object> plan, String tenant) {
        var votes = TraceStore.json(plan.get("approvals"));
        if (!votes.isArray() || votes.size() > 2) throw invalid();
        var distinct = new HashSet<String>();
        for (var vote : votes)
            if (!vote.isObject() || !tenant.equals(vote.path("tenantId").asText())
                    || vote.path("principalId").asText().isBlank() || !distinct.add(vote.path("principalId").asText())
                    || !Objects.equals(plan.get("digest"), vote.path("planDigest").asText())
                    || !vote.path("approved").isBoolean() || !vote.path("approved").asBoolean()) throw invalid();
        return votes;
    }

    /** 只认过滤链已验的当前身份，不把传入 Actor、双勾或 body 里的另一个名字当第二人。 */
    public static Actor verifiedVoter(Actor supplied) {
        Actor current = Actor.current();
        if (!current.tenantId().equals(supplied.tenantId()) || !current.principalId().equals(supplied.principalId())
                || !current.roles().containsAll(supplied.roles())) throw invalid();
        return current;
    }

    public Checked beforeClaim(long callId, Actor owner) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("APPROVER_NETWORK_REQUIRES_NO_TRANSACTION");
        var rows = store.db().queryForList("select p.*,r.tenant_id,r.principal_id,t.approval_context from engine.tool_call t join engine.run r on r.id=t.run_id join engine.action_plan p on p.id=(t.approval_context->>'actionPlanId')::bigint where t.id=? and r.tenant_id=? and r.principal_id=?",
                callId, owner.tenantId(), owner.principalId());
        if (rows.isEmpty() || !"HIGH".equals(rows.getFirst().get("risk"))) return null;
        var plan = rows.getFirst(); requirePolicy(plan);
        if (!((java.sql.Timestamp) plan.get("expires_at")).toInstant().isAfter(clock.instant()))
            throw new PlatformException(409, "ACTION_PLAN_EXPIRED", "批准的执行计划已过期，副作用未发生");
        var context = TraceStore.json(plan.get("approval_context"));
        if (TraceStore.number(plan, "call_id") != callId
                || !Objects.equals(plan.get("digest"), context.path("actionPlanDigest").asText())) throw invalid();
        var votes = votes(plan, owner.tenantId());
        if (!"APPROVED".equals(plan.get("status")) || votes.size() != 2)
            throw new PlatformException(403, "CONFIRM_STRENGTH_INSUFFICIENT", "高危计划尚未取得两名不同确认人的批准");
        if (client == null || store.db().queryForObject("select count(*) from engine.run_authority where run_id=?", Integer.class, plan.get("run_id")) != 1)
            throw unavailable();
        // 原委托必须经既有强校验加载（含资源、owner 和材料摘要），不能从审批请求补入。
        var original = new RunAuthorityStore(store.db().getDataSource(), clock).load(TraceStore.number(plan, "run_id"));
        if (!original.owner().equals(new SubjectKey(owner.tenantId(), owner.principalId()))
                || original.blockedReason() != null || !original.delegation().expiresAt().isAfter(clock.instant())) throw invalid();
        var resource = new ResourceBinding("action-plan", plan.get("id").toString(), plan.get("digest").toString());
        Instant started = clock.instant(); long startedNanos = System.nanoTime();
        var proofs = new ArrayList<VerifiedAuthority>();
        for (var vote : votes) {
            var subject = new SubjectKey(owner.tenantId(), vote.path("principalId").asText());
            var proof = client.approver(original.delegation(), subject, resource);
            if (!subject.equals(new SubjectKey(proof.actor().tenantId(), proof.actor().principalId()))
                    || !original.delegation().value().equals(proof.delegation().value())
                    || !resource.equals(proof.call().resource()) || !"engine".equals(proof.call().target())
                    || !"approval.current".equals(proof.call().action())) throw invalid();
            boolean allowed = false;
            for (var role : context.path("roles")) if (proof.actor().roles().contains(role.asText())) allowed = true;
            if (!allowed) throw new PlatformException(403, "APPROVAL_ROLE_REQUIRED", "确认人当前权限已不足，执行未获授权");
            proofs.add(proof);
        }
        var checked = new Checked(TraceStore.number(plan, "id"), TraceStore.number(plan, "run_id"), callId,
                plan.get("digest").toString(), Digest.sha256(Json.canonical(votes)), original.delegation().expiresAt(),
                List.copyOf(proofs), started, startedNanos);
        checked.live(); return checked;
    }

    /** 构造入口私有：只有上述在线复核可产生此短期结果；锁内仍需比较原计划与投票版本。 */
    public static final class Checked {
        private final long planId, runId, callId, startedNanos;
        private final String digest, votesSha;
        private final Instant grantExpiry, started;
        private final List<VerifiedAuthority> proofs;
        private Checked(long planId,long runId,long callId,String digest,String votesSha,Instant grantExpiry,
                List<VerifiedAuthority> proofs,Instant started,long startedNanos) {
            this.planId=planId;this.runId=runId;this.callId=callId;this.digest=digest;this.votesSha=votesSha;
            this.grantExpiry=grantExpiry;this.proofs=proofs;this.started=started;this.startedNanos=startedNanos;
        }
        public void require(Map<String,Object> call,Map<String,Object> plan) {
            live(); requirePolicy(plan);
            if (TraceStore.number(plan,"id")!=planId || TraceStore.number(call,"id")!=callId
                    || TraceStore.number(call,"run_id")!=runId || !digest.equals(plan.get("digest"))
                    || TraceStore.number(plan,"call_id")!=callId || TraceStore.number(plan,"node_instance_id")!=TraceStore.number(call,"node_instance_id")
                    || !"APPROVED".equals(plan.get("status"))
                    || !votesSha.equals(Digest.sha256(Json.canonical(TraceStore.json(plan.get("approvals")))))) throw invalid();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) { live(); }
                    });
        }
        public void requireLive() { live(); }
        private void live() {
            Instant now=Instant.now(); long elapsed=System.nanoTime()-startedNanos;
            if (!grantExpiry.isAfter(now) || elapsed<0) throw invalid();
            for (var proof:proofs) {
                long budget;
                try { budget=Duration.between(started,proof.expiresAt()).toNanos(); }
                catch (ArithmeticException excessive) { throw invalid(); }
                if (!proof.expiresAt().isAfter(now) || budget<=0 || elapsed>=budget) throw invalid();
            }
        }
    }
    private static PlatformException invalid() { return new PlatformException(403,"APPROVAL_CURRENT_DENIED","高危审批的身份、计划或当前授权不一致"); }
    private static PlatformException unavailable() { return new PlatformException(503,"APPROVAL_CURRENT_AUTHORITY_REQUIRED","高危执行缺少可复核的原运行委托或在线核权能力，副作用未发生"); }
}
