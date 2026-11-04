package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.common.authority.MetadataSelectors;
import cn.agentplatform.engine.domain.RunAdmissionRejected;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * 受理协调：把"这次请求是否获准进入运行、是否已有同 requestId 的已受理结果"从 {@link RunService}
 * 中独立出来，门面只保留调度与持久化编排。
 *
 * <p>本类不持有任何 HTTP 客户端，也不在自身方法里发起业务 I/O：授权凭据来自已验签的
 * {@link AuthorityContext}，幂等判定只读 {@code engine.run}。真正的内容读取（app/skill/prompt）仍由
 * {@link RunService} 在受理判定之后进行，因此越权/缺授权总是先于模型或业务 I/O 失败。
 */
@Service
public class RunAdmissionService {
    private static final String REPLAY_METADATA =
            "select id,public_id,app_code,trigger_type,mode,tenant_id,principal_id from engine.run"
                    + " where tenant_id=? and principal_id=? and request_id=?";

    private final TraceStore store;
    private final RunScheduler scheduler;
    /**
     * 与 {@link RunService} 相同的 setter 装配：iam 生产由 {@code RunAuthorityConfiguration} 补齐，
     * 旧部署没有这两个 bean 时保持默认拒绝（IAM_ADMISSION_UNAVAILABLE）。
     */
    private RunAuthority runAuthority;
    private RunAuthorityStore admissionStore;

    public RunAdmissionService(TraceStore store, RunScheduler scheduler) {
        this.store = store;
        this.scheduler = scheduler;
    }

    @Autowired(required = false)
    public void runAuthority(RunAuthority authority) {
        this.runAuthority = authority;
    }

    @Autowired(required = false)
    public void admissionStore(RunAuthorityStore store) {
        this.admissionStore = store;
    }

    /**
     * REQUIRED 可以加入调用方事务；必须在任何刷新/业务 I/O 之前拒绝，否则引擎就不再是自己事务边界的
     * 所有者（Spring 6.2.7 TransactionTemplate 语义）。
     */
    void requireNoAmbientTransaction(boolean protectedAdmission) {
        if (protectedAdmission && TransactionSynchronizationManager.isActualTransactionActive())
            throw new PlatformException(403, "AUTHORITY_TRANSACTION_FORBIDDEN", "受理不能加入调用方事务");
    }

    /**
     * 受保护受理只接受"全新发布运行"的最小请求形状：任何草稿/重放/会话/物料入口都由别的受控通道
     * 负责，不能借 requestId 混入。
     */
    static void requirePublishedRequest(
            JsonNode request,
            String trigger,
            JsonNode trusted,
            Long controlled,
            boolean recorded,
            RunScheduler.Admission ticket,
            boolean reservation) {
        if (!"TASK".equals(trigger)
                || trusted != null
                || controlled != null
                || recorded
                || ticket != null
                || reservation
                || !"FULL".equals(request.path("mode").asText("FULL")))
            throw RunAuthorityStore.denied();
        for (String key :
                List.of(
                        "versionNo",
                        "candidateSha256",
                        "draftRev",
                        "frozenCandidate",
                        "breakpoints",
                        "mocks",
                        "controlledReplay",
                        "controlledContractDigest",
                        "debugControls",
                        "evalReportId",
                        "caseId",
                        "sessionId",
                        "chatSession",
                        "chatTurn",
                        "sessionHistory",
                        "history",
                        "historyRefs",
                        "materialRefs")) if (request.has(key)) throw RunAuthorityStore.denied();
        if (request.hasNonNull("requestId")
                && (!request.path("requestId").isTextual()
                        || request.path("requestId").textValue().isBlank()
                        || request.path("requestId").textValue().length() > 128))
            throw RunAuthorityStore.denied();
        if (request.path("inputs").hasNonNull("materialRefs")
                || request.path("inputs").hasNonNull("historyRefs"))
            throw RunAuthorityStore.denied();
    }

    /** 开票：校验网关代理入口的精确形状并换取本次运行的初始授权上限。 */
    RetainedAuthorization opening(String code, Actor actor, boolean protectedAdmission) {
        if (!protectedAdmission) return null;
        if (admissionStore == null || runAuthority == null)
            throw new PlatformException(403, "IAM_ADMISSION_UNAVAILABLE", "受理授权尚未装配");
        MetadataSelectors.code(code);
        var current = AuthorityContext.current();
        var root = current.call().resource();
        if (!"gateway".equals(current.caller())
                || !"engine".equals(current.call().target())
                || !"proxy.engine.post.RunController.run".equals(current.call().action())
                || !"PORTAL".equals(current.operation().sourceKind())
                || !"PORTAL".equals(current.operation().originKind())
                || !"app-version".equals(root.kind())
                || !root.equals(current.operation().resource())
                || !root.id().matches(java.util.regex.Pattern.quote(code) + "@[1-9][0-9]*"))
            throw RunAuthorityStore.denied();
        return AuthorityContext.requireInitialFor(actor, root);
    }

    /** 调度之前的幂等读取：同一 (tenant, principal, requestId) 已有受理结果时不再占新槽位。 */
    Map<String, Object> existing(Actor actor, JsonNode request, boolean protectedAdmission) {
        if (!request.hasNonNull("requestId")) return null;
        var rows =
                store.db()
                        .queryForList(
                                protectedAdmission
                                        ? REPLAY_METADATA
                                        : "select * from engine.run where tenant_id=? and"
                                              + " principal_id=? and request_id=?",
                                actor.tenantId(),
                                actor.principalId(),
                                request.path("requestId").asText());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 容量预约：容量满但同 requestId 已被并发请求受理时，返回可读的既有结果而不是让调用方重试。
     * 短事务只做 advisory lock 与读取，不等待 HTTP 或信号量。
     */
    Reservation reserve(
            Actor actor,
            JsonNode request,
            String code,
            String trigger,
            boolean protectedAdmission,
            RetainedAuthorization opening,
            RunScheduler.Admission suppliedTicket,
            boolean acceptedReservation) {
        RunScheduler.Admission admission = suppliedTicket;
        if (admission == null && !acceptedReservation && !"EVALUATION".equals(trigger)) {
            try {
                admission = scheduler.reserveInteractive();
            } catch (RunAdmissionRejected rejected) {
                // Same request lock as INSERT: accepted concurrent work remains readable at
                // capacity. No HTTP or semaphore wait is performed in this short transaction.
                Map<String, Object> accepted =
                        request.hasNonNull("requestId")
                                ? store.atomic(
                                        () -> {
                                            if (protectedAdmission) begin(opening);
                                            store.db()
                                                    .queryForList(
                                                            "select"
                                                                + " pg_advisory_xact_lock(hashtextextended(?,0))",
                                                            actor.tenantId()
                                                                    + ":"
                                                                    + actor.principalId()
                                                                    + ":"
                                                                    + request.path("requestId")
                                                                            .asText());
                                            if (protectedAdmission)
                                                AuthorityContext.requireBound(opening);
                                            var rows =
                                                    store.db()
                                                            .queryForList(
                                                                    protectedAdmission
                                                                            ? REPLAY_METADATA
                                                                            : "select * from"
                                                                                  + " engine.run"
                                                                                  + " where"
                                                                                  + " tenant_id=?"
                                                                                  + " and principal_id=?"
                                                                                  + " and request_id=?",
                                                                    actor.tenantId(),
                                                                    actor.principalId(),
                                                                    request.path("requestId")
                                                                            .asText());
                                            if (rows.isEmpty()) return null;
                                            if (!protectedAdmission)
                                                verifyReplay(
                                                        rows.getFirst(),
                                                        code,
                                                        request,
                                                        trigger);
                                            return rows.getFirst();
                                        })
                                : null;
                if (accepted != null) return new Reservation(null, accepted);
                throw rejected;
            }
        }
        return new Reservation(admission, null);
    }

    /** 入库前最终授权：重新开票并复核初始覆盖，防止长内容读取期间授权被收紧。 */
    RetainedAuthorization bind(String code, Actor actor, boolean protectedAdmission) {
        RetainedAuthorization proof = opening(code, actor, protectedAdmission);
        if (protectedAdmission) requireInitialCoverage(proof);
        return proof;
    }
    /** 受理事务的本地预算：只影响本事务的语句/锁等待，不改变锁顺序。 */
    void begin(RetainedAuthorization proof) {
        if (proof == null) return;
        AuthorityContext.requireBound(proof);
        store.db().execute("set local statement_timeout='2000ms'");
        store.db().execute("set local lock_timeout='1500ms'");
    }

    /**
     * 入库事务内的幂等复核：先取 request 级 advisory lock，再读已受理行；返回既有 run 的数字主键，
     * 由门面直接返回该运行状态，从而保证相同 requestId 不新增 run。
     */
    Long lockAndReuse(
            Actor actor,
            JsonNode request,
            String code,
            String trigger,
            boolean protectedAdmission,
            RetainedAuthorization proof) {
        if (request.hasNonNull("requestId"))
            store.db()
                    .queryForList(
                            "select pg_advisory_xact_lock(hashtextextended(?,0))",
                            actor.tenantId()
                                    + ":"
                                    + actor.principalId()
                                    + ":"
                                    + request.path("requestId").asText());
        if (protectedAdmission) AuthorityContext.requireBound(proof);
        if (!request.hasNonNull("requestId")) return null;
        var rows =
                store.db()
                        .queryForList(
                                protectedAdmission
                                        ? REPLAY_METADATA
                                        : "select * from engine.run where tenant_id=? and"
                                              + " principal_id=? and request_id=?",
                                actor.tenantId(),
                                actor.principalId(),
                                request.path("requestId").asText());
        if (rows.isEmpty()) return null;
        if (!protectedAdmission) verifyReplay(rows.getFirst(), code, request, trigger);
        return TraceStore.number(rows.getFirst(), "id");
    }

    /** 重复请求必须命中同版本/同输入语义；冲突不是幂等命中。 */
    void verifyReplay(Map<String, Object> run, String code, JsonNode req, String trigger) {
        boolean sameTrigger =
                run.get("trigger_type").equals(trigger)
                        || trigger.equals("TASK") && run.get("trigger_type").equals("CHAT");
        if (!run.get("app_code").equals(code)
                || !sameTrigger
                || req.has("controlledContractDigest")
                        && !req.path("controlledContractDigest")
                                .asText()
                                .equals(
                                        Objects.toString(
                                                run.get("controlled_skill_test_digest"),
                                                Objects.toString(
                                                        run.get("controlled_draft_skill_digest"),
                                                        "")))
                || !TraceStore.json(run.get("inputs")).equals(req.path("inputs"))
                || req.has("versionNo")
                        && TraceStore.json(run.get("snapshot")).path("versionNo").asInt()
                                != req.path("versionNo").asInt()
                || req.has("candidateSha256")
                        && !TraceStore.json(run.get("eval_candidate"))
                                .path("candidateSha256")
                                .asText()
                                .equals(req.path("candidateSha256").asText()))
            throw new PlatformException(409, "REQUEST_CONFLICT", "请求号已绑定其他版本或输入");
    }

    void requireInitialCoverage(RetainedAuthorization proof) {
        AuthorityContext.requireBound(proof);
        Actor initial = proof.requireInitialCeiling(), current = proof.current().actor();
        if (!initial.tenantId().equals(current.tenantId())
                || !initial.principalId().equals(current.principalId())
                || !current.roles().containsAll(initial.roles())) throw RunAuthorityStore.denied();
        initial.attributes()
                .forEach(
                        (key, values) -> {
                            if (!key.equals("dataLabels")
                                    && !current.attributes()
                                            .getOrDefault(key, Set.of())
                                            .containsAll(values)) throw RunAuthorityStore.denied();
                        });
    }

    /** 预约结果：要么拿到槽位，要么直接复用已受理的同 requestId 运行。 */
    public record Reservation(RunScheduler.Admission slot, Map<String, Object> replay) {}

    boolean wired() {
        return runAuthority != null && admissionStore != null;
    }
}
