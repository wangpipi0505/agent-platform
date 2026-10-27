package cn.agentplatform.skill.application;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.ErrorSemantics;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** 仅产生执行事实，不改引擎节点状态；事实持久化先于回调，回调失败只能重投事实而不能重做业务。 */
@Service
public class SkillExecution {
    private final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final SkillCatalog catalog;
    private final SkillAdapters adapters;
    private final SkillClients clients;
    private final String engineUrl;
    private final ReconciliationProofs reconciliationProofs;

    /**
     * PLAT-04 回滚开关。关闭时不向 envelope 暴露 externalJob，且 SUBMIT_NO_TASK_ID 仍按旧语义
     * 落 RESULT_UNKNOWN，使上游看到的行为与改造前完全一致（绑定行仍保留审计列）。
     */
    @Value("${agent.skill.external-job-enabled:true}")
    boolean externalJobEnabled = true;

    public SkillExecution(
            JdbcTemplate db,
            org.springframework.transaction.PlatformTransactionManager manager,
            SkillCatalog catalog,
            SkillAdapters adapters,
            SkillClients clients,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.db = db;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.catalog = catalog;
        this.adapters = adapters;
        this.clients = clients;
        this.engineUrl = engineUrl;
        this.reconciliationProofs = new ReconciliationProofs(db, manager);
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public Map<String, Object> execute(String code, Map<String, Object> request) {
        Actor.current().requireRole("SERVICE");
        long callId = number(request, "callId"),
                versionId = number(request, "skillVersionId"),
                attempt = number(request, "attemptId");
        String owner = text(request, "leaseOwner");
        var arguments = object(request.get("arguments"));
        String digest = Digest.sha256(Json.canonical(arguments));
        var contract = catalog.versionId(versionId);
        var registration = clients.get(engineUrl + "/internal/tool-calls/" + callId);
        boolean controlled = registration.path("controlled_draft_skill_version_id").asLong() > 0;
        if (controlled) {
            Actor.current().requireRole("EDITOR");
            if (!registration.path("trigger_type").asText().equals("DEBUG")
                    || registration.path("controlled_draft_skill_version_id").asLong() != versionId
                    || !registration
                            .path("controlled_draft_skill_digest")
                            .asText()
                            .equals(contract.get("sha256")))
                throw error(409, "CONTROLLED_DRAFT_CHANGED", "受控测试合同已改变，请重新创建测试并确认");
        }
        if (!code.equals(contract.get("code"))
                || !(Set.of("PUBLISHED", "ARCHIVED").contains(contract.get("status"))
                        || controlled && contract.get("status").equals("DRAFT"))
                || "DISABLED".equals(contract.get("skillStatus")))
            throw error(409, "SKILL_UNAVAILABLE", "技能精确版本不可执行");
        var existing = fact(callId);
        if (existing != null) {
            if (!digest.equals(existing.get("args_digest"))
                    || ((Number) existing.get("skill_version_id")).longValue() != versionId)
                throw error(409, "CALL_ARGUMENTS_MISMATCH", "已记录调用与请求不一致");
            boolean retry = false;
            var previousEnvelope = Json.read(existing.get("result").toString());
            var execution = object(contract.get("execution"));
            if (existing.get("status").equals("FAILED")
                    && previousEnvelope
                            .path("error")
                            .path("semantic")
                            .asText()
                            .equals("RETRYABLE_FAILURE")
                    && Boolean.TRUE.equals(execution.get("readonly"))
                    && !Boolean.TRUE.equals(execution.get("sideEffect"))
                    && attempt > ((Number) existing.get("attempt_id")).longValue()) {
                var permission = clients.get(engineUrl + "/internal/tool-calls/" + callId);
                retry =
                        permission.path("status").asText().equals("REGISTERED")
                                && permission.path("attempt_id").asLong() == attempt
                                && permission.path("skill_version_id").asLong() == versionId
                                && permission.path("args_digest").asText().equals(digest);
            }
            // 重试层只能在引擎。旧事实存在时，仅权威REGISTERED+新attempt显式授权只读重试；UNKNOWN永远不能走此分支。
            if (!retry) return deliver(existing);
        }
        String failure = null, reason = null;
        // PLAT-05：远端业务码与有界原文由适配器失败携带，终经 envelope 落成七字段错误对象。
        String errorCode = null, errorRaw = null;
        Map<String, Object> secured = arguments;
        try {
            SkillRules.validate(contract.get("inputSchema"), arguments);
        } catch (Exception ex) {
            failure = "PARAM_ERROR";
            reason = "参数不符合技能Schema";
        }
        if (failure == null)
            try {
                secured =
                        SkillRules.guard(Actor.current(), object(contract.get("guard")), arguments);
                SkillRules.validate(contract.get("inputSchema"), secured);
            } catch (Exception ex) {
                failure = "NO_PERMISSION";
                reason = "技能权限或授权范围不足";
            }

        var executionPolicy = object(contract.get("execution"));
        boolean needsApproval =
                Boolean.TRUE.equals(executionPolicy.get("sideEffect"))
                        || "CONFIRM_REQUIRED".equals(contract.get("riskLevel"));
        if (failure == null && needsApproval) {
            Object roles = executionPolicy.get("confirmRoles");
            if (!(roles instanceof Collection<?> declared) || declared.isEmpty())
                throw error(409, "CONFIRM_ROLES_REQUIRED", "写技能必须声明明确确认角色");
            var approval = clients.get(engineUrl + "/internal/tool-calls/" + callId + "/approval");
            if (!approval.path("approved").asBoolean())
                return Map.of(
                        "status",
                        "WAITING_APPROVAL",
                        "confirmation",
                        Map.of(
                                "roles",
                                roles,
                                "parametersDigest",
                                digest,
                                "args",
                                arguments,
                                "callId",
                                callId,
                                "skillVersionId",
                                versionId));
            if (approval.path("skillVersionId").asLong() != versionId
                    || !approval.path("parametersDigest").asText().equals(digest))
                throw error(409, "APPROVAL_MISMATCH", "批准快照与当前技能或参数不一致，请重新确认");
        }
        // 先完成参数/守卫判断，再认领失败事实或实际执行的交付权；校验失败绝不会发送SUBMITTED或触发外部调用。
        JsonNode authority =
                clients.post(
                        engineUrl + "/internal/tool-calls/" + callId + "/claim",
                        Map.of("owner", owner, "attemptId", attempt));
        if (authority.path("attempt_id").asLong() != attempt
                || authority.path("skill_version_id").asLong() != versionId
                || !authority.path("skill_code").asText().equals(code)
                || !authority.path("args_digest").asText().equals(digest)
                || authority.path("node_instance_id").asLong() != number(request, "nodeInstanceId")
                || !authority.path("runId").asText().equals(text(request, "runId"))
                || !authority
                        .path("idempotency_key")
                        .asText()
                        .equals(text(request, "idempotencyKey")))
            throw error(409, "CALL_REGISTRATION_MISMATCH", "请求与引擎登记的调用归属或参数不一致");
        if (!authority.path("claimed").asBoolean()) {
            if (Set.of("SUCCEEDED", "FAILED").contains(authority.path("status").asText()))
                return wrap(
                        authority.path("status").asText(),
                        authority.path("result"),
                        authority.path("error_semantic").asText(""));
            return wrap(
                    "UNKNOWN",
                    Json.tree(Map.of("modelSummary", "既有调用未取得新执行权，等待原执行事实或对账")),
                    "RESULT_UNKNOWN");
        }
        String authorityTrigger = authority.path("trigger_type").asText();
        String kind =
                switch (authorityTrigger) {
                    case "CHAT", "TASK", "FORMAL" -> "FORMAL";
                    case "DEBUG" -> "DEBUG";
                    case "EVALUATION" -> "EVALUATION";
                    default -> throw error(409, "CALL_KIND_INVALID", "引擎调用用途无效");
                };
        var execution = object(contract.get("execution"));
        boolean mock = false;
        String status = "FAILED";
        String semantic = failure;
        long duration = 0;
        SkillAdapters.Raw raw = null;
        Map<String, Object> algorithmFact = null;
        Map<String, Object> algorithmSource = Map.of();
        if (failure == null) {
            // 运行用途与替身均取引擎持久权威；即使服务请求已验签，body也不能临时发明或改写Mock。
            JsonNode mockConfig = authority.path("mock_snapshot");
            if (kind.equals("DEBUG") && mockConfig.isObject() && !mockConfig.isEmpty()) {
                mock = true;
                try {
                    if (!mockConfig.path("skillCode").asText().equals(code)
                            || !mockConfig.path("mode").asText().equals("FIXED"))
                        throw new IllegalArgumentException("MOCK_SNAPSHOT_INVALID");
                    if (mockConfig.path("sourceKind").asText().equals("LAST_REAL"))
                        raw = recordedMock(mockConfig, contract, request, digest, secured);
                    else {
                        Object response =
                                Json.read(
                                        mockConfig.path("fixedResponse").toString(), Object.class);
                        SkillRules.validate(contract.get("outputSchema"), response);
                        raw =
                                new SkillAdapters.Raw(
                                        Json.tree(response),
                                        List.of(),
                                        "调试Mock结果",
                                        Json.tree(null));
                    }
                    status = "SUCCEEDED";
                } catch (PlatformException ex) {
                    semantic = ex.status() == 403 ? "NO_PERMISSION" : "OUTPUT_INVALID";
                    reason = ex.getMessage();
                    errorCode = ex.code();
                } catch (Exception ex) {
                    semantic = "OUTPUT_INVALID";
                    reason = "Mock输出不符合Schema";
                }
            } else {
                if (adapters.persistentBinding(contract)) {
                    var pendingRequest = new LinkedHashMap<String, Object>(request);
                    pendingRequest.put("guardedArguments", secured);
                    pendingRequest.put("authorityTriggerType", authorityTrigger);
                    var pendingEnvelope =
                            envelope(
                                    request,
                                    contract,
                                    "UNKNOWN",
                                    "RESULT_UNKNOWN",
                                    "已登记异步调用，等待原任务事实",
                                    null,
                                    null,
                                    null,
                                    false,
                                    secured);
                    // PREPARING is durable registration, not a deliverable execution fact. The
                    // outbox must not turn engine EXECUTING into UNKNOWN before submitted CAS.
                    // No transaction/row lock spans either HTTP call; send_started fences dispatch.
                    tx.executeWithoutResult(
                            transaction -> {
                                if ("ALGORITHM".equals(contract.get("type")) && existing != null) {
                                    prepareAlgorithmGeneration(
                                            existing,
                                            contract,
                                            pendingRequest,
                                            pendingEnvelope,
                                            owner,
                                            kind);
                                    return;
                                }
                                adapters.bind(contract, pendingRequest);
                                db.update(
                                        "insert into"
                                            + " skill.execution_fact(tenant_id,call_id,skill_version_id,principal_id,run_id,attempt_id,execution_owner,args_digest,skill_contract_sha256,request_snapshot,status,result,duration_ms,attempt_duration_ms,is_mock,call_kind,callback_state)"
                                            + " values(?,?,?,?,?,?,?,?,?,?::jsonb,'UNKNOWN',?::jsonb,0,0,false,?,'PREPARING')"
                                            + " on conflict do nothing",
                                        tenant(),
                                        callId,
                                        versionId,
                                        Actor.current().principalId(),
                                        text(request, "runId"),
                                        attempt,
                                        owner,
                                        digest,
                                        contract.get("sha256"),
                                        Json.write(pendingRequest),
                                        Json.write(pendingEnvelope),
                                        kind);
                            });
                    if ("ALGORITHM".equals(contract.get("type"))) algorithmFact = fact(callId);
                }
                var submitted =
                        clients.post(
                                engineUrl + "/internal/tool-calls/" + callId + "/submitted",
                                Map.of("owner", owner, "attemptId", attempt));
                if (!submitted.path("submitted").asBoolean())
                    throw error(409, "CALL_CLAIM_LOST", "外部提交权已失效，未发送请求");
                long started = System.nanoTime();
                try {
                    var observed =
                            adapters.observe(
                                    contract,
                                    secured,
                                    text(request, "idempotencyKey"),
                                    callId,
                                    false,
                                    authority.path("runId").asText(),
                                    attempt);
                    raw = observed.raw();
                    algorithmSource = observed.source();
                    status = "SUCCEEDED";
                } catch (SkillAdapters.TerminalFailure ex) {
                    status = "FAILED";
                    semantic = ex.semantic;
                    reason = ex.getMessage();
                    errorCode = ex.code;
                    errorRaw = ex.raw;
                    algorithmSource = ex.source;
                } catch (SkillAdapters.Failure ex) {
                    // 回滚开关关闭时保持改造前语义：提交未拿到任务标识仍是 RESULT_UNKNOWN。
                    boolean legacy = !externalJobEnabled && "SUBMIT_NO_TASK_ID".equals(ex.semantic);
                    status = legacy || ex.unknown ? "UNKNOWN" : "FAILED";
                    semantic = legacy ? "RESULT_UNKNOWN" : ex.semantic;
                    reason = ex.getMessage();
                    errorCode = ex.code;
                    errorRaw = ex.raw;
                } catch (Exception ex) {
                    // 本地进程的失败是“本次调用为什么没跑成”的结论，不是“外部输出不合法”：403 开关关闭、
                    // 403 允许根越界、504 超时都必须原样透出，否则调用方会把配置错误当可重试的验收失败。
                    // 异步协议拒绝（T06.2）同理：ASYNC_* 400 在任何出站请求之前就是结论，折叠成
                    // OUTPUT_INVALID/UNKNOWN 会把契约错误伪装成远端故障或无限对账，重试也不会成功。
                    if (ex instanceof PlatformException pe
                            && (pe.code().startsWith("PROCESS_")
                                    || pe.code().startsWith("ASYNC_"))) throw pe;
                    boolean sideEffect = Boolean.TRUE.equals(execution.get("sideEffect"));
                    status = sideEffect ? "UNKNOWN" : "FAILED";
                    semantic = sideEffect ? "RESULT_UNKNOWN" : "OUTPUT_INVALID";
                    reason = "外部输出接收、转存或Schema验收失败";
                } finally {
                    duration = Math.max(0, (System.nanoTime() - started) / 1_000_000);
                }
            }
        }
        var envelope = envelope(request, contract, status, semantic, reason, errorCode, errorRaw, raw, mock, secured);
        var frozenRequest = new LinkedHashMap<String, Object>(request);
        frozenRequest.put("guardedArguments", secured);
        frozenRequest.put("authorityTriggerType", authorityTrigger);
        // callId为唯一统计与交付键。事务提交后的回调可失败，业务事实仍在PG，不能为修复回调再次执行适配器。
        String finalStatus = status;
        long actualDuration = duration;
        boolean actualMock = mock;
        if (algorithmFact != null) {
            adoptAlgorithm(
                    algorithmFact,
                    contract,
                    algorithmSource,
                    envelope,
                    finalStatus,
                    actualDuration);
            return deliver(Objects.requireNonNull(fact(callId)));
        }
        tx.executeWithoutResult(
                transaction -> {
                    if (existing == null) {
                        db.update(
                                "insert into"
                                    + " skill.execution_fact(tenant_id,call_id,skill_version_id,principal_id,run_id,attempt_id,execution_owner,args_digest,skill_contract_sha256,request_snapshot,status,result,duration_ms,attempt_duration_ms,is_mock,call_kind)"
                                    + " values(?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?,?) on"
                                    + " conflict do nothing",
                                tenant(),
                                callId,
                                versionId,
                                Actor.current().principalId(),
                                text(request, "runId"),
                                attempt,
                                owner,
                                digest,
                                contract.get("sha256"),
                                Json.write(frozenRequest),
                                finalStatus,
                                Json.write(envelope),
                                actualDuration,
                                actualDuration,
                                actualMock,
                                kind);
                        if (adapters.asynchronous(contract))
                            db.update(
                                    "update skill.execution_fact set"
                                        + " status=?,result=?::jsonb,duration_ms=?,attempt_duration_ms=?,callback_state='PENDING',updated_at=now()"
                                        + " where tenant_id=? and call_id=? and attempt_id=? and"
                                        + " status='UNKNOWN'",
                                    finalStatus,
                                    Json.write(envelope),
                                    actualDuration,
                                    actualDuration,
                                    tenant(),
                                    callId,
                                    attempt);
                    } else {
                        // 旧代完整归档与新代替换同事务；统计仍只有callId一行，执行时长累计真实段，回调仅报告本代时长。
                        db.update(
                                "insert into"
                                    + " skill.execution_fact_history(tenant_id,call_id,attempt_id,fact)"
                                    + " select tenant_id,call_id,attempt_id,to_jsonb(f) from"
                                    + " skill.execution_fact f where tenant_id=? and call_id=? on"
                                    + " conflict do nothing",
                                tenant(),
                                callId);
                        int changed =
                                db.update(
                                        "update skill.execution_fact set"
                                            + " attempt_id=?,execution_owner=?,request_snapshot=?::jsonb,status=?,result=?::jsonb,duration_ms=duration_ms+?,attempt_duration_ms=?,is_mock=?,callback_state='PENDING',callback_error=null,delivery_owner=null,delivery_lease_until=null,next_delivery_at=now(),delivery_attempts=0,updated_at=now()"
                                            + " where tenant_id=? and call_id=? and attempt_id=?",
                                        attempt,
                                        owner,
                                        Json.write(frozenRequest),
                                        finalStatus,
                                        Json.write(envelope),
                                        actualDuration,
                                        actualDuration,
                                        actualMock,
                                        tenant(),
                                        callId,
                                        existing.get("attempt_id"));
                        if (changed != 1)
                            throw error(409, "FACT_GENERATION_CHANGED", "执行事实代际已经改变，请由引擎重新核实");
                    }
                });
        return deliver(Objects.requireNonNull(fact(callId)));
    }

    private SkillAdapters.Raw recordedMock(
            JsonNode snapshot,
            Map<String, Object> contract,
            Map<String, Object> request,
            String argsDigest,
            Map<String, Object> guardedArguments) {
        var source = fact(snapshot.path("sourceCallId").asLong());
        if (source == null
                || !"SUCCEEDED".equals(source.get("status"))
                || Boolean.TRUE.equals(source.get("is_mock"))
                || !Objects.equals(
                        source.get("skill_version_id"),
                        ((Number) request.get("skillVersionId")).longValue())
                || !argsDigest.equals(source.get("args_digest"))
                || !Objects.equals(source.get("skill_contract_sha256"), contract.get("sha256"))
                || !Objects.equals(source.get("run_id"), snapshot.path("sourceRunId").asText())
                || !Actor.current().principalId().equals(snapshot.path("sourcePrincipal").asText()))
            throw new IllegalArgumentException("LAST_REAL_NOT_AUTHORIZED");
        JsonNode recorded = Json.read(source.get("result").toString());
        // 原始参数摘要相同也可能因当前权限收窄而产生不同交集；旧的宽范围事实不能替代本次受限执行。
        JsonNode sourceGuarded =
                Json.read(source.get("request_snapshot").toString()).path("guardedArguments");
        if (!sourceGuarded.isObject()
                || !Json.canonical(sourceGuarded).equals(Json.canonical(guardedArguments)))
            throw error(403, "MOCK_SOURCE_SCOPE_CHANGED", "当前权限范围与历史结果不同，请重新执行真实查询");
        if (!Digest.sha256(Json.canonical(recorded)).equals(snapshot.path("sourceDigest").asText())
                || !recorded.equals(snapshot.path("fixedResponse")))
            throw new IllegalArgumentException("LAST_REAL_CHANGED");
        // 原始Schema已由同合同的非Mock事实核实；structured可能是returnSpec投影，不能拿它再次冒充原始响应验Schema。
        return adapters.recorded(
                recorded,
                contract,
                number(request, "callId"),
                text(request, "runId"),
                source.get("run_id").toString());
    }

    /** 受控复现只复制已核实工件；不接受客户端提供结果/对象键，不执行技能业务适配器。 */
    public Map<String, Object> reproduceArtifacts(long sourceCallId, Map<String, Object> request) {
        Actor.current().requireRole("SERVICE");
        Actor.current().requireRole("EDITOR");
        long call = number(request, "callId"), attempt = number(request, "attemptId");
        String owner = text(request, "leaseOwner"), runId = text(request, "runId");
        // claim在引擎事务内验证当前run/node/attempt租约，即使已被同owner认领也重新检查有效性。
        var authority =
                clients.post(
                        engineUrl + "/internal/tool-calls/" + call + "/claim",
                        Map.of("owner", owner, "attemptId", attempt));
        if (!authority.path("status").asText().equals("EXECUTING")
                || !authority.path("trigger_type").asText().equals("DEBUG")
                || authority.path("mock_source_call_id").asLong() != sourceCallId
                || sourceCallId == call
                || !authority.path("claimed_by").asText().equals(owner)
                || authority.path("attempt_id").asLong() != attempt
                || authority.path("node_instance_id").asLong() != number(request, "nodeInstanceId")
                || !authority.path("runId").asText().equals(runId))
            throw error(403, "REPRODUCTION_BINDING_DENIED", "复现调用归属、来源或执行租约不匹配");
        var source = fact(sourceCallId);
        long version = authority.path("skill_version_id").asLong();
        if (source == null
                || !source.get("status").equals("SUCCEEDED")
                || ((Number) source.get("skill_version_id")).longValue() != version
                || !Objects.equals(
                        source.get("skill_contract_sha256"),
                        authority.path("skill_contract_sha256").asText())
                || !Objects.equals(
                        source.get("args_digest"), authority.path("args_digest").asText()))
            throw error(403, "REPRODUCTION_SOURCE_DENIED", "复现来源事实与当前技能或参数不匹配");
        var recorded = Json.read(source.get("result").toString());
        if (!Digest.sha256(Json.canonical(recorded))
                .equals(authority.path("mock_source_digest").asText()))
            throw error(409, "REPRODUCTION_SOURCE_CHANGED", "复现来源摘要不一致");
        var contract = catalog.versionId(version);
        var args = Json.read(authority.path("arguments").toString(), Map.class);
        if (!Digest.sha256(Json.canonical(args)).equals(authority.path("args_digest").asText()))
            throw error(409, "REPRODUCTION_ARGS_CHANGED", "权威参数与摘要不一致");
        SkillRules.validate(contract.get("inputSchema"), args);
        Map<String, Object> secured;
        try {
            secured = SkillRules.guard(Actor.current(), object(contract.get("guard")), args);
            SkillRules.validate(contract.get("inputSchema"), secured);
        } catch (Exception denied) {
            throw error(403, "REPRODUCTION_SCOPE_DENIED", "当前权限不足以复现此结果");
        }
        var sourceGuarded =
                Json.read(source.get("request_snapshot").toString()).path("guardedArguments");
        if (!sourceGuarded.isObject()
                || !Json.canonical(sourceGuarded).equals(Json.canonical(secured)))
            throw error(403, "REPRODUCTION_SCOPE_CHANGED", "当前权限范围与历史结果不同");
        return Map.of(
                "fileRefs",
                adapters.recorded(recorded, contract, call, runId, source.get("run_id").toString())
                        .fileRefs());
    }

    /**
     * 执行事实信封（PLAT-05）。错误对象统一为七字段结构（semantic/code/category/retryable/
     * userMessage/detail/traceId，另含兼容键 reason）；code/raw 来自适配器携带的远端业务码与
     * 有界原文，没有远端证据时为 null。traceId 取自请求上下文 MDC，与 API 错误信封同源。
     */
    Map<String, Object> envelope(
            Map<String, Object> request,
            Map<String, Object> contract,
            String status,
            String semantic,
            String reason,
            String code,
            String remoteRaw,
            SkillAdapters.Raw raw,
            boolean mock,
            Map<String, Object> guarded) {
        var owner = new LinkedHashMap<String, Object>();
        owner.put("tenantId", tenant());
        owner.put("principalId", Actor.current().principalId());
        for (String key :
                List.of("runId", "nodeInstanceId", "attemptId", "callId", "skillVersionId"))
            owner.put(key, request.get(key));
        owner.put("skillCode", contract.get("code"));
        var result = new LinkedHashMap<String, Object>();
        result.put("owner", owner);
        result.put("status", status);
        result.put(
                "error",
                semantic == null
                        ? null
                        : ErrorSemantics.error(
                                semantic,
                                code,
                                Objects.toString(reason, "技能调用失败"),
                                remoteRaw,
                                org.slf4j.MDC.get("traceId")));
        result.put(
                "modelSummary", raw == null ? Objects.toString(reason, "技能调用失败") : raw.summary());
        result.put("structured", raw == null ? null : raw.structured());
        result.put("fileRefs", raw == null ? List.of() : raw.fileRefs());
        result.put("cardPayloadJson", raw == null ? "" : raw.cardPayloadJson());
        // PLAT-07：校验结论跟随工具结果一起落 skill.execution_fact.result。DEGRADED 必须显式可见，
        // 否则调用方会把“只做了顶层校验”误读成严格通过；通过时省略 warning 键。
        if (raw != null && raw.validation() != null)
            result.put("validation", raw.validation().asMap());
        result.put("isMock", mock);
        // PLAT-04：外部任务态与执行事实同源落库。无绑定时显式 null，避免上游把"缺字段"
        // 误当成"没有外部任务风险"；关闭回滚开关时同样返回 null。
        result.put("externalJob", externalJob(request));
        result.put(
                "guardTrace",
                Map.of(
                        "roles",
                        Actor.current().roles(),
                        "argsDigest",
                        Digest.sha256(Json.canonical(guarded))));
        if (raw != null && raw.ontologyMeta() != null && raw.ontologyMeta().isObject()) {
            JsonNode meta = raw.ontologyMeta();
            boolean complete = true;
            for (String key : List.of("spaceCode", "releaseId", "version", "contentDigest"))
                if (meta.path(key).asText().isBlank()) complete = false;
            if (complete) {
                var actual = Json.read(meta.toString(), Map.class);
                actual.put("callId", request.get("callId"));
                result.put("ontologyMeta", actual);
            }
        }
        return result;
    }

    /**
     * 外部任务快照：远端任务标识、可核实状态、提交时刻与最后轮询时刻。
     *
     * <p>绑定行是唯一权威源，这里永远读 PG，不从请求参数或内存推断；这正是改造前丢失的
     * "远端任务 id"。没有绑定、开关关闭或调用并非异步技能时返回 null，调用方必须显式渲染
     * "无外部任务"，不得猜测。
     */
    Map<String, Object> externalJob(Map<String, Object> request) {
        if (!externalJobEnabled || request == null || request.get("callId") == null) return null;
        long callId;
        try {
            callId = number(request, "callId");
        } catch (RuntimeException ex) {
            return null;
        }
        var rows =
                db.queryForList(
                        "select external_job_id,status,external_state,submitted_at::text as"
                            + " submitted_at,last_polled_at::text as last_polled_at,"
                            + " remote_error_code,remote_error_message from"
                            + " skill.external_job_binding where tenant_id=? and call_id=?"
                            + " order by updated_at desc limit 1",
                        tenant(),
                        callId);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        var job = new LinkedHashMap<String, Object>();
        job.put("remoteJobId", row.get("external_job_id"));
        job.put("state", externalState(row));
        job.put("submittedAt", row.get("submitted_at"));
        job.put("lastPolledAt", row.get("last_polled_at"));
        job.put("remoteCode", row.get("remote_error_code"));
        job.put("remoteMessage", row.get("remote_error_message"));
        return job;
    }

    /** 远端状态原文优先；旧行没有 external_state 时按绑定状态映射，绝不返回空白状态。 */
    private static String externalState(Map<String, Object> row) {
        Object observed = row.get("external_state");
        if (observed != null && !observed.toString().isBlank()) return observed.toString();
        Object status = row.get("status");
        String value = status == null ? "UNKNOWN" : status.toString();
        return switch (value) {
            case "DONE" -> "SUCCEEDED";
            case "SENDING" -> "SUBMITTING";
            default -> value;
        };
    }

    /**
     * 把最新 externalJob 合并进未终结的执行事实，并返回合并后的 envelope。
     *
     * <p>恢复窗口：CAS 条件 status='UNKNOWN' 保证迟到的轮询永远不会覆盖已终结事实；
     * 这里不重投引擎事实（引擎侧由 ExecutionRecovery 的轮询回执刷新 tool_call.external_job），
     * 避免每次轮询都产生一条新的投递审计并放大事实行。
     */
    private Map<String, Object> refreshExternalJob(long callId) {
        var current = fact(callId);
        if (current == null) return Map.of();
        var envelope = Json.read(current.get("result").toString(), Map.class);
        var job = externalJob(Map.of("callId", callId));
        // 显式 null 而不是删键：上游据此区分"没有外部任务"与"字段缺失"。
        envelope.put("externalJob", job);
        db.update(
                "update skill.execution_fact set result=?::jsonb,updated_at=now()"
                    + " where tenant_id=? and call_id=? and principal_id=? and status='UNKNOWN'",
                Json.write(envelope),
                tenant(),
                callId,
                Actor.current().principalId());
        return envelope;
    }

    Map<String, Object> fact(long callId) {
        var rows =
                db.queryForList(
                        "select * from skill.execution_fact where tenant_id=? and call_id=? and"
                                + " principal_id=?",
                        tenant(),
                        callId,
                        Actor.current().principalId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * PLAT-05 T05.2：任务结果读取（GET /admin/skills/tasks/{callId}/result）。门户运行详情口径：
     * 租户内任意主体的事实可读（callId 是本仓任务身份，external_job_id 只是远端任务标识）。
     * 失败任务的 result.error 是完整七字段错误对象；不存在返回可读 404，未终态（UNKNOWN 或
     * 执行准备中）返回可读 409——端点永不伪造结果，也不把空白 output 当结论。
     */
    public Map<String, Object> taskResult(long callId) {
        var rows =
                db.queryForList(
                        "select * from skill.execution_fact where tenant_id=? and call_id=?"
                                + " order by updated_at desc limit 1",
                        tenant(),
                        callId);
        if (rows.isEmpty()) throw error(404, "FACT_NOT_FOUND", "任务不存在或不可见");
        var row = rows.getFirst();
        String status = row.get("status").toString();
        if ("UNKNOWN".equals(status) || "PREPARING".equals(row.get("callback_state")))
            throw error(
                    409,
                    "TASK_NOT_FINAL",
                    "PREPARING".equals(row.get("callback_state"))
                            ? "任务尚未开始外部执行，请稍后查询"
                            : "任务结果未知，正在对账核实，请稍后查询");
        var result = new LinkedHashMap<String, Object>();
        result.put("callId", callId);
        result.put("status", status);
        result.put("result", Json.read(row.get("result").toString(), Map.class));
        return result;
    }

    /** 恢复器只读取已落库证据；租户和原主体均由服务票据限定，读取绝不重发业务。 */
    public Map<String, Object> proof(long callId) {        Actor.current().requireRole("SERVICE");
        var row = fact(callId);
        if (row == null) throw error(404, "FACT_NOT_FOUND", "执行事实不存在");
        var request = Json.read(row.get("request_snapshot").toString());
        var result = Json.read(row.get("result").toString());
        var proof = new LinkedHashMap<String, Object>();
        proof.put("factId", "sf:" + callId + ":" + row.get("attempt_id"));
        proof.put("callId", callId);
        proof.put("runId", row.get("run_id"));
        proof.put("nodeInstanceId", request.path("nodeInstanceId").asLong());
        proof.put("skillVersionId", row.get("skill_version_id"));
        proof.put("argsDigest", row.get("args_digest"));
        proof.put("skillContractSha256", row.get("skill_contract_sha256"));
        proof.put("attemptId", row.get("attempt_id"));
        proof.put("status", row.get("status"));
        proof.put("result", result);
        proof.put("durationMs", row.get("attempt_duration_ms"));
        proof.put("isMock", row.get("is_mock"));
        proof.put("callKind", row.get("call_kind"));
        proof.put("factSha256", Digest.sha256(Json.canonical(proof)));
        return proof;
    }

    public Map<String, Object> retry(long callId) {
        Actor.current().requireRole("SERVICE");
        var recorded = fact(callId);
        if (recorded == null) throw error(404, "FACT_NOT_FOUND", "执行事实不存在");
        return deliver(recorded);
    }

    /** 对账只查询原始幂等键关联的外部事实，绝不把UNKNOWN当作重发业务的许可。 */
    public Map<String, Object> reconcile(long callId) {
        ReconciliationProofs.service();
        var recorded = fact(callId);
        if (recorded == null) throw error(404, "FACT_NOT_FOUND", "执行事实不存在");
        if (!recorded.get("status").equals("UNKNOWN")) return deliver(recorded);
        var authority = clients.get(engineUrl + "/internal/tool-calls/" + callId);
        if (!authority.path("status").asText().equals("UNKNOWN"))
            throw error(409, "RECONCILE_STATE", "请等待引擎确认原调用为UNKNOWN后对账");
        var contract = catalog.versionId(((Number) recorded.get("skill_version_id")).longValue());
        var original = Json.read(recorded.get("request_snapshot").toString(), Map.class);
        String key = text(original, "idempotencyKey");
        String outcome = "UNKNOWN";
        SkillAdapters.Raw raw = null;
        Map<String, Object> evidence = null;
        Map<String, Object> queriedBinding = null;
        Map<String, Object> algorithmSource = Map.of();
        SkillAdapters.TerminalFailure terminalFailure = null;
        SkillAdapters.Failure verifiedFailure = null;
        String algorithmReason = null;
        long started = System.nanoTime();
        try {
            Map<String, Object> secured;
            try {
                secured =
                        SkillRules.guard(
                                Actor.current(),
                                object(contract.get("guard")),
                                object(original.get("arguments")));
                SkillRules.validate(contract.get("inputSchema"), secured);
                requireHistoricalScope(
                        original.get("guardedArguments"), secured, object(contract.get("guard")));
            } catch (Exception denied) {
                outcome = "AUTHORIZATION_DENIED";
                throw error(403, "RECONCILE_SCOPE_DENIED", "当前授权不能覆盖原任务范围，拒绝查询或采纳结果");
            }
            boolean sent = true;
            if (adapters.persistentBinding(contract)) {
                var originalBinding = reconciliationProofs.bindingCurrent(recorded, contract);
                queriedBinding = originalBinding;
                if (!Boolean.TRUE.equals(originalBinding.get("send_started")))
                    proofAuthority(callId, recorded, contract);
                // The engine already declared UNKNOWN. This short CAS arbitrates with dispatch:
                // either send_started won (query only), or NOT_SENT fences even a delayed submitted
                // ACK from the old worker. No lock is held across either service's HTTP request.
                db.update(
                        "update skill.external_job_binding set status='NOT_SENT',updated_at=now()"
                                + " where tenant_id=? and call_id=? and principal_id=? and"
                                + " status='REGISTERED' and send_started=false and attempt_id=? and"
                                + " skill_version_id=? and run_id=? and idempotency_key=? and"
                                + " request_snapshot=?::jsonb",
                        tenant(),
                        callId,
                        Actor.current().principalId(),
                        recorded.get("attempt_id"),
                        recorded.get("skill_version_id"),
                        recorded.get("run_id"),
                        key,
                        recorded.get("request_snapshot").toString());
                sent =
                        Boolean.TRUE.equals(
                                db.queryForObject(
                                        "select send_started from skill.external_job_binding where"
                                                + " tenant_id=? and call_id=? and principal_id=?",
                                        Boolean.class,
                                        tenant(),
                                        callId,
                                        Actor.current().principalId()));
            }
            if ("PREPARING".equals(recorded.get("callback_state"))) {
                var unknown = Json.read(recorded.get("result").toString(), Map.class);
                unknown.put("modelSummary", sent ? "提交已开始，原任务结果待对账" : "未开始外部发送，等待引擎授权恢复");
                unknown.put(
                        "error",
                        ErrorSemantics.error(
                                "RESULT_UNKNOWN",
                                null,
                                unknown.get("modelSummary").toString(),
                                null,
                                org.slf4j.MDC.get("traceId")));
                db.update(
                        "update skill.execution_fact set"
                            + " result=?::jsonb,callback_state='PENDING',next_delivery_at=now(),updated_at=now()"
                            + " where tenant_id=? and call_id=? and callback_state='PREPARING' and"
                            + " status='UNKNOWN' and principal_id=? and run_id=? and attempt_id=?"
                            + " and skill_version_id=? and request_snapshot=?::jsonb",
                        Json.write(unknown),
                        tenant(),
                        callId,
                        Actor.current().principalId(),
                        recorded.get("run_id"),
                        recorded.get("attempt_id"),
                        recorded.get("skill_version_id"),
                        recorded.get("request_snapshot").toString());
            }
            if ("MCP".equals(contract.get("type"))
                    && Objects.toString(
                                    object(contract.getOrDefault("reconciliation", Map.of()))
                                            .get("endpoint"),
                                    "")
                            .isBlank()) {
                // MCP SSE has no reliable original-call query. Missing HTTP evidence is
                // uncertainty, never permission to call the business tool again. Preserve
                // the original fact and use the common UNKNOWN audit/response below.
                outcome = "UNKNOWN";
            } else if (!sent) {
                outcome = "NOT_SENT";
                evidence =
                        Map.of(
                                "outcome",
                                "NOT_SENT",
                                "sourceKind",
                                "LOCAL_SEND_FENCE",
                                "externalState",
                                "NOT_SENT",
                                "sendStarted",
                                false,
                                "sendStatus",
                                "NOT_SENT",
                                "sourceSha256",
                                Digest.sha256("REGISTERED_SEND_FALSE_TO_NOT_SENT"));
                if ("ALGORITHM".equals(contract.get("type"))) {
                    evidence = new LinkedHashMap<>(evidence);
                    evidence.put("externalJobId", queriedBinding.get("external_job_id"));
                    evidence.put("externalApiSha256", queriedBinding.get("external_api_sha256"));
                    evidence.put("externalNamespace", queriedBinding.get("external_namespace"));
                    evidence.put(
                            "externalIdentitySha256",
                            queriedBinding.get("external_identity_sha256"));
                }
            } else if (contract.get("type").equals("ALGORITHM")
                    || adapters.asynchronous(contract)) {
                var observed =
                        adapters.observe(
                                contract,
                                "ALGORITHM".equals(contract.get("type"))
                                        ? object(original.get("guardedArguments"))
                                        : secured,
                                key,
                                callId,
                                true,
                                recorded.get("run_id").toString(),
                                ((Number) recorded.get("attempt_id")).longValue());
                raw = observed.raw();
                algorithmSource = observed.source();
                outcome = "APPLIED";
            } else {
                var policy = object(contract.getOrDefault("reconciliation", Map.of()));
                String endpoint =
                        text(policy, "endpoint")
                                .replace(
                                        "{idempotencyKey}",
                                        java.net.URLEncoder.encode(
                                                key, java.nio.charset.StandardCharsets.UTF_8));
                var query = new LinkedHashMap<>(contract);
                query.put("type", "HTTP");
                query.put("endpoint", endpoint);
                query.put("httpMethod", "GET");
                query.put("outputSchema", Map.of("type", "object"));
                query.put("execution", Map.of("readonly", true, "sideEffect", false));
                query.remove("httpMapping");
                // Query paths describe protocol metadata, not the business result's projection.
                query.remove("returnSpec");
                var response =
                        adapters.invoke(
                                query,
                                Map.of("idempotencyKey", key),
                                key,
                                callId,
                                true,
                                recorded.get("run_id").toString(),
                                ((Number) recorded.get("attempt_id")).longValue());
                var actual = response.structured();
                if (!path(
                                actual,
                                Objects.toString(
                                        policy.getOrDefault(
                                                "idempotencyKeyPath", "$.idempotencyKey")))
                        .asText()
                        .equals(key)) throw error(409, "RECONCILE_OWNERSHIP", "对账响应未证明原幂等键归属");
                var status = path(actual, text(policy, "donePath"));
                if (status.equals(Json.tree(policy.get("doneValue")))) {
                    var result =
                            path(
                                    actual,
                                    Objects.toString(
                                            policy.getOrDefault("resultPath", "$.result")));
                    SkillRules.validate(
                            contract.get("outputSchema"),
                            Json.read(result.toString(), Object.class));
                    raw =
                            new SkillAdapters.Raw(
                                    result,
                                    List.of(),
                                    "已根据外部幂等键对账采纳结果",
                                    result.path("ontologyMeta"));
                    outcome = "APPLIED";
                } else if (policy.containsKey("notAppliedValue")
                        && policy.get("notAppliedValue") != null
                        && !Json.tree(policy.get("notAppliedValue"))
                                .equals(Json.tree(policy.get("doneValue")))
                        && status.equals(Json.tree(policy.get("notAppliedValue")))) {
                    outcome = "NOT_APPLIED";
                    evidence =
                            Map.of(
                                    "outcome",
                                    outcome,
                                    "sourceKind",
                                    "HTTP_QUERY",
                                    "externalState",
                                    status,
                                    "sourceSha256",
                                    Digest.sha256(Json.canonical(actual)),
                                    "queryEndpointSha256",
                                    Digest.sha256(endpoint),
                                    "sendStarted",
                                    true,
                                    "sendStatus",
                                    "SUBMITTED");
                }
            }
        } catch (SkillAdapters.TerminalFailure observed) {
            outcome = "FAILED";
            terminalFailure = observed;
            algorithmSource = observed.source;
        } catch (HttpAsyncAdapter.NotApplied observed) {
            outcome = "NOT_APPLIED";
            evidence = observed.source;
        } catch (SkillAdapters.Failure ex) {
            if (ex.unknown) {
                // 未完成的观测只是不确定证据，绝不因此获得重试许可。
                outcome = "UNKNOWN";
                if ("ALGORITHM".equals(contract.get("type"))) algorithmReason = ex.getMessage();
            } else {
                // 外部服务已给出可核实的终态失败：真实原因必须落成可读事实，不能永久UNKNOWN。
                outcome = "FAILED";
                verifiedFailure = ex;
            }
        } finally {
            // 对账查询是独立审计段，不累计进原业务执行时长；统计仍以同一个callId重算终态。
            var detail = new LinkedHashMap<String, Object>();
            detail.put("idempotencyKeyDigest", Digest.sha256(key));
            if (algorithmReason != null)
                detail.put(
                        "algorithmReason",
                        algorithmReason.substring(0, Math.min(256, algorithmReason.length())));
            db.update(
                    "insert into"
                        + " skill.reconciliation_note(tenant_id,call_id,principal_id,outcome,query_duration_ms,detail)"
                        + " values(?,?,?,?,?,?::jsonb)",
                    tenant(),
                    callId,
                    Actor.current().principalId(),
                    outcome,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000),
                    Json.write(detail));
        }
        if (evidence != null) {
            // Recheck engine authority after HTTP, then arbitrate with callback/fact replacement in
            // one short local transaction. The returned reference still grants no business retry.
            proofAuthority(callId, recorded, contract);
            var proof =
                    reconciliationProofs.store(
                            recorded, contract, evidence, adapters.persistentBinding(contract));
            return Map.of(
                    "status",
                    "UNKNOWN",
                    "outcome",
                    outcome,
                    "callId",
                    callId,
                    "proofRef",
                    proof.get("proofRef"),
                    "proofSha256",
                    proof.get("proofSha256"),
                    "retryBusinessAllowed",
                    false);
        }
        if (!algorithmSource.isEmpty()) {
            var requestSnapshot = Json.read(recorded.get("request_snapshot").toString(), Map.class);
            String terminalStatus = terminalFailure == null ? "SUCCEEDED" : "FAILED";
            var result =
                    envelope(
                            requestSnapshot,
                            contract,
                            terminalStatus,
                            terminalFailure == null ? null : terminalFailure.semantic,
                            terminalFailure == null ? null : terminalFailure.getMessage(),
                            terminalFailure == null ? null : terminalFailure.code,
                            terminalFailure == null ? null : terminalFailure.raw,
                            raw,
                            false,
                            object(requestSnapshot.get("guardedArguments")));
            adoptAlgorithm(
                    recorded,
                    contract,
                    algorithmSource,
                    result,
                    terminalStatus,
                    ((Number) recorded.get("attempt_duration_ms")).longValue());
            return deliver(Objects.requireNonNull(fact(callId)));
        }
        if (verifiedFailure != null) {
            // 终态失败同样是一条已核实事实：写入可读原因并投递，不允许再次重发业务请求。
            var failedSnapshot = Json.read(recorded.get("request_snapshot").toString(), Map.class);
            var failed =
                    envelope(
                            failedSnapshot,
                            contract,
                            "FAILED",
                            verifiedFailure.semantic,
                            verifiedFailure.getMessage(),
                            verifiedFailure.code,
                            verifiedFailure.raw,
                            null,
                            false,
                            object(failedSnapshot.get("guardedArguments")));
            String failedFence =
                    queriedBinding == null
                            ? ""
                            : " and exists(select 1 from skill.external_job_binding b where"
                                    + " b.tenant_id=f.tenant_id and b.call_id=f.call_id and"
                                    + " b.principal_id=f.principal_id and"
                                    + " b.skill_version_id=f.skill_version_id and"
                                    + " b.idempotency_key=? and b.request_snapshot=?::jsonb)";
            var failedValues =
                    new ArrayList<Object>(
                            List.of(
                                    Json.write(failed),
                                    tenant(),
                                    callId,
                                    Actor.current().principalId(),
                                    recorded.get("run_id"),
                                    recorded.get("attempt_id"),
                                    recorded.get("skill_version_id"),
                                    recorded.get("args_digest"),
                                    recorded.get("skill_contract_sha256"),
                                    recorded.get("request_snapshot").toString()));
            if (queriedBinding != null) {
                failedValues.add(key);
                failedValues.add(queriedBinding.get("request_snapshot").toString());
            }
            int failedChanged =
                    db.update(
                            "update skill.execution_fact f set"
                                + " status='FAILED',result=?::jsonb,callback_state='PENDING',next_delivery_at=now(),updated_at=now()"
                                + " where f.tenant_id=? and f.call_id=? and f.status='UNKNOWN' and"
                                + " f.principal_id=? and f.run_id=? and f.attempt_id=? and"
                                + " f.skill_version_id=? and f.args_digest=? and"
                                + " f.skill_contract_sha256=? and f.request_snapshot=?::jsonb"
                                + failedFence,
                            failedValues.toArray());
            if (failedChanged != 1)
                throw error(409, "RECONCILE_FACT_STALE", "原代事实已改变，未采纳或投递旧查询结果");
            return deliver(Objects.requireNonNull(fact(callId)));
        }
        if (raw == null) {
            // 远端尚未终结：轮询已把 external_state/last_polled_at 写回绑定行。这里把最新
            // externalJob 注入原事实（状态仍为 UNKNOWN），使引擎与 GET /internal/tool-calls
            // 能展示"已提交/处理中"而不需要新的业务请求；不回写事实状态，避免把等待态
            // 伪装成终态。
            var refreshed = refreshExternalJob(callId);
            var waiting = new LinkedHashMap<String, Object>();
            waiting.put("status", "UNKNOWN");
            waiting.put("outcome", outcome);
            waiting.put("callId", callId);
            waiting.put("result", refreshed);
            waiting.put("retryBusinessAllowed", false);
            return waiting;
        }
        var envelope = Json.read(recorded.get("result").toString(), Map.class);
        envelope.put("status", "SUCCEEDED");
        envelope.put("error", null);
        envelope.put("structured", raw.structured());
        envelope.put("fileRefs", raw.fileRefs());
        envelope.put("modelSummary", raw.summary());
        envelope.put("cardPayloadJson", raw.cardPayloadJson());
        if (raw.ontologyMeta() != null && raw.ontologyMeta().isObject())
            envelope.put("ontologyMeta", raw.ontologyMeta());
        String bindingFence =
                queriedBinding == null
                        ? ""
                        : " and exists(select 1 from skill.external_job_binding b where"
                              + " b.tenant_id=f.tenant_id and b.call_id=f.call_id and"
                              + " b.principal_id=f.principal_id and b.run_id=f.run_id and"
                              + " b.attempt_id=f.attempt_id and"
                              + " b.skill_version_id=f.skill_version_id and b.idempotency_key=? and"
                              + " b.request_snapshot=?::jsonb and b.status='DONE')";
        var values =
                new ArrayList<Object>(
                        List.of(
                                Json.write(envelope),
                                tenant(),
                                callId,
                                Actor.current().principalId(),
                                recorded.get("run_id"),
                                recorded.get("attempt_id"),
                                recorded.get("skill_version_id"),
                                recorded.get("args_digest"),
                                recorded.get("skill_contract_sha256"),
                                recorded.get("request_snapshot").toString()));
        if (queriedBinding != null) {
            values.add(key);
            values.add(queriedBinding.get("request_snapshot").toString());
        }
        int changed =
                db.update(
                        "update skill.execution_fact f set"
                            + " status='SUCCEEDED',result=?::jsonb,callback_state='PENDING',updated_at=now()"
                            + " where f.tenant_id=? and f.call_id=? and f.status='UNKNOWN' and"
                            + " f.principal_id=? and f.run_id=? and f.attempt_id=? and"
                            + " f.skill_version_id=? and f.args_digest=? and"
                            + " f.skill_contract_sha256=? and f.request_snapshot=?::jsonb"
                                + bindingFence,
                        values.toArray());
        // The old query is only an observation. A replacement generation or callback final has
        // already won; never deliver the stale envelope under that current generation's identity.
        if (changed != 1) throw error(409, "RECONCILE_FACT_STALE", "原代事实已改变，未采纳或投递旧查询结果");
        // 旧租约可能无法被引擎采纳，此时保留已核实事实并返回UNKNOWN，交给引擎受控人工采纳，不伪造逻辑成功。
        return deliver(Objects.requireNonNull(fact(callId)));
    }

    /**
     * Network observations cannot mutate binding/DONE independently of their original fact. Lock
     * fact then binding and adopt both within one short PG transaction.
     */
    private void adoptAlgorithm(
            Map<String, Object> original,
            Map<String, Object> contract,
            Map<String, Object> source,
            Map<String, Object> envelope,
            String status,
            long duration) {
        tx.executeWithoutResult(
                t -> {
                    var rows =
                            db.queryForList(
                                    "select * from skill.execution_fact where tenant_id=? and"
                                            + " principal_id=? and call_id=? for update",
                                    tenant(),
                                    Actor.current().principalId(),
                                    original.get("call_id"));
                    if (rows.isEmpty()) throw error(409, "ALGORITHM_FACT_STALE", "原算法事实已改变");
                    var current = rows.getFirst();
                    for (String field :
                            List.of(
                                    "attempt_id",
                                    "skill_version_id",
                                    "run_id",
                                    "args_digest",
                                    "skill_contract_sha256"))
                        if (!Objects.equals(original.get(field), current.get(field)))
                            throw error(409, "ALGORITHM_FACT_STALE", "原算法事实代际已改变");
                    if (!"UNKNOWN".equals(current.get("status"))
                            || !Json.canonical(
                                            Json.read(original.get("request_snapshot").toString()))
                                    .equals(
                                            Json.canonical(
                                                    Json.read(
                                                            current.get("request_snapshot")
                                                                    .toString()))))
                        throw error(409, "ALGORITHM_FACT_STALE", "原算法事实已终结或快照已改变");
                    if (!source.isEmpty()) adapters.completeAlgorithm(current, contract, source);
                    else reconciliationProofs.bindingCurrent(current, contract);
                    long totalDuration =
                            ((Number) original.get("duration_ms")).longValue()
                                    - ((Number) original.get("attempt_duration_ms")).longValue()
                                    + duration;
                    int changed =
                            db.update(
                                    "update skill.execution_fact set"
                                        + " status=?,result=?::jsonb,duration_ms=?,attempt_duration_ms=?,callback_state='PENDING',next_delivery_at=now(),updated_at=now()"
                                        + " where tenant_id=? and principal_id=? and call_id=? and"
                                        + " attempt_id=? and status='UNKNOWN' and"
                                        + " request_snapshot=?::jsonb",
                                    status,
                                    Json.write(envelope),
                                    totalDuration,
                                    duration,
                                    tenant(),
                                    Actor.current().principalId(),
                                    original.get("call_id"),
                                    original.get("attempt_id"),
                                    original.get("request_snapshot").toString());
                    if (changed != 1) throw error(409, "ALGORITHM_FACT_STALE", "原算法终态CAS失效");
                });
    }

    private void prepareAlgorithmGeneration(
            Map<String, Object> original,
            Map<String, Object> contract,
            Map<String, Object> request,
            Map<String, Object> envelope,
            String owner,
            String kind) {
        var rows =
                db.queryForList(
                        "select * from skill.execution_fact where tenant_id=? and principal_id=?"
                                + " and call_id=? for update",
                        tenant(),
                        Actor.current().principalId(),
                        original.get("call_id"));
        if (rows.isEmpty()) throw error(409, "ALGORITHM_RETRY_STALE", "原只读失败事实已改变");
        var current = rows.getFirst();
        for (String field :
                List.of(
                        "attempt_id",
                        "skill_version_id",
                        "run_id",
                        "args_digest",
                        "skill_contract_sha256",
                        "status"))
            if (!Objects.equals(current.get(field), original.get(field)))
                throw error(409, "ALGORITHM_RETRY_STALE", "原只读失败代际已改变");
        if (!Json.canonical(Json.read(current.get("result").toString()))
                        .equals(Json.canonical(Json.read(original.get("result").toString())))
                || !Json.canonical(Json.read(current.get("request_snapshot").toString()))
                        .equals(
                                Json.canonical(
                                        Json.read(original.get("request_snapshot").toString()))))
            throw error(409, "ALGORITHM_RETRY_STALE", "原只读失败快照已改变");
        adapters.prepareAlgorithmRetry(contract, current, request);
        db.update(
                "insert into skill.execution_fact_history(tenant_id,call_id,attempt_id,fact) select"
                    + " tenant_id,call_id,attempt_id,to_jsonb(f) from skill.execution_fact f where"
                    + " tenant_id=? and call_id=? and attempt_id=?",
                tenant(),
                current.get("call_id"),
                current.get("attempt_id"));
        int changed =
                db.update(
                        "update skill.execution_fact set"
                            + " attempt_id=?,execution_owner=?,request_snapshot=?::jsonb,status='UNKNOWN',result=?::jsonb,attempt_duration_ms=0,is_mock=false,call_kind=?,callback_state='PREPARING',callback_error=null,delivery_owner=null,delivery_lease_until=null,next_delivery_at=now(),delivery_attempts=0,updated_at=now()"
                            + " where tenant_id=? and principal_id=? and call_id=? and attempt_id=?"
                            + " and status='FAILED'",
                        number(request, "attemptId"),
                        owner,
                        Json.write(request),
                        Json.write(envelope),
                        kind,
                        tenant(),
                        Actor.current().principalId(),
                        current.get("call_id"),
                        current.get("attempt_id"));
        if (changed != 1) throw error(409, "ALGORITHM_RETRY_STALE", "原只读失败移交CAS失效");
    }

    private void proofAuthority(
            long callId, Map<String, Object> fact, Map<String, Object> contract) {
        ReconciliationProofs.requireProofTicket();
        ReconciliationProofs.authority(
                clients.get(engineUrl + "/internal/tool-calls/" + callId), fact, contract);
        var run =
                clients.get(
                        engineUrl
                                + "/admin/runs/"
                                + java.net.URLEncoder.encode(
                                        fact.get("run_id").toString(),
                                        java.nio.charset.StandardCharsets.UTF_8));
        if (!run.path("runId").asText().equals(fact.get("run_id"))
                || !Set.of("RUNNING", "WAITING_EXTERNAL").contains(run.path("status").asText()))
            throw ReconciliationProofs.stale();
        long node =
                Json.read(fact.get("request_snapshot").toString()).path("nodeInstanceId").asLong();
        for (JsonNode n : run.path("nodes"))
            if (n.path("nodeInstanceId").asLong() == node
                    && Set.of("RUNNING", "WAITING_EXTERNAL").contains(n.path("status").asText()))
                return;
        throw ReconciliationProofs.stale();
    }

    /**
     * A job result still covers its original input; a narrowed request cannot redact that result.
     */
    static void requireHistoricalScope(
            Object frozen, Map<String, Object> secured, Map<String, Object> guard) {
        if (!(frozen instanceof Map<?, ?> historical)
                || !historical.keySet().equals(secured.keySet()))
            throw new IllegalArgumentException("缺少原任务授权证据");
        var attributes = object(guard.get("attributes"));
        for (var entry : historical.entrySet()) {
            Object current = secured.get(entry.getKey());
            if (attributes.containsKey(entry.getKey())
                    && entry.getValue() instanceof Collection<?> scope) {
                if (scope.isEmpty()
                        || !(current instanceof Collection<?> allowed)
                        || !new HashSet<>(allowed).containsAll(scope))
                    throw new IllegalArgumentException("原任务范围不再获准");
            } else if (!Json.tree(entry.getValue()).equals(Json.tree(current))) {
                throw new IllegalArgumentException("原任务参数缺少当前授权证明");
            }
        }
    }

    private JsonNode path(JsonNode root, String path) {
        if (path.equals("$")) return root;
        if (!path.matches("\\$\\.[A-Za-z_][A-Za-z0-9_.]*"))
            throw error(400, "RECONCILE_PATH_INVALID", "对账路径仅支持点路径");
        JsonNode value = root;
        for (String key : path.substring(2).split("\\.")) value = value.path(key);
        return value;
    }

    /** 自动投递只接受PG可信事实中的主体，不接受外部body提供的tenant或principal。 */
    public Map<String, Object> retry(long callId, Actor actor) {
        actor.requireRole("SERVICE");
        var rows =
                db.queryForList(
                        "select * from skill.execution_fact where tenant_id=? and call_id=? and"
                                + " principal_id=?",
                        actor.tenantId(),
                        callId,
                        actor.principalId());
        if (rows.isEmpty()) throw error(404, "FACT_NOT_FOUND", "执行事实不存在");
        return deliver(rows.getFirst(), actor);
    }

    private Map<String, Object> deliver(Map<String, Object> fact) {
        return deliver(fact, null);
    }

    private Map<String, Object> deliver(Map<String, Object> fact, Actor workerActor) {
        long call = ((Number) fact.get("call_id")).longValue();
        String scopeTenant = fact.get("tenant_id").toString();
        JsonNode envelope = Json.read(fact.get("result").toString());
        String semantic = envelope.path("error").path("semantic").asText("");
        String status = fact.get("status").toString();
        if ("PREPARING".equals(fact.get("callback_state")))
            return wrap("UNKNOWN", envelope, "RESULT_UNKNOWN");
        if (!"DELIVERED".equals(fact.get("callback_state")))
            try {
                var body = new LinkedHashMap<String, Object>();
                body.put("owner", fact.get("execution_owner"));
                body.put("attemptId", fact.get("attempt_id"));
                body.put("status", status);
                body.put("result", envelope);
                body.put("durationMs", fact.get("attempt_duration_ms"));
                body.put("isMock", fact.get("is_mock"));
                if (!semantic.isEmpty()) body.put("errorSemantic", semantic);
                String url = engineUrl + "/internal/tool-calls/" + call + "/fact";
                var delivered =
                        workerActor == null
                                ? clients.post(url, body)
                                : clients.post(url, body, workerActor);
                String state =
                        delivered.path("accepted").asBoolean()
                                ? "DELIVERED"
                                : delivered.path("late").asBoolean() ? "LATE" : "PENDING";
                // A delayed UNKNOWN acknowledgement must not consume a newer terminal fact.
                int changed =
                        db.update(
                                "update skill.execution_fact set"
                                    + " callback_state=?,callback_error=null,updated_at=now() where"
                                    + " tenant_id=? and call_id=? and attempt_id=? and status=? and"
                                    + " result=?::jsonb",
                                state,
                                scopeTenant,
                                call,
                                fact.get("attempt_id"),
                                status,
                                Json.write(envelope));
                if (changed == 0) return wrap("UNKNOWN", envelope, "RESULT_UNKNOWN");
                if (!state.equals("DELIVERED")) return wrap("UNKNOWN", envelope, "RESULT_UNKNOWN");
            } catch (Exception ex) {
                db.update(
                        "update skill.execution_fact set"
                            + " callback_error='引擎事实回调失败，待重投',updated_at=now() where tenant_id=?"
                            + " and call_id=? and attempt_id=? and status=? and result=?::jsonb",
                        scopeTenant,
                        call,
                        fact.get("attempt_id"),
                        status,
                        Json.write(envelope));
                return wrap("UNKNOWN", envelope, "RESULT_UNKNOWN");
            }
        return wrap(status, envelope, semantic);
    }

    private Map<String, Object> wrap(String status, JsonNode result, String semantic) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", status);
        response.put("result", result);
        if (!semantic.isEmpty()) response.put("errorSemantic", semantic);
        return response;
    }

    public List<Map<String, Object>> statistics(String code) {
        String filter = code == null ? "" : " and v.skill_code=?";
        String sql =
                "select v.skill_code as \"skillCode\",v.version_no as \"versionNo\",f.call_kind as"
                        + " \"callKind\",f.is_mock as \"isMock\",f.status,count(*) as"
                        + " calls,avg(f.duration_ms) as \"averageDurationMs\",percentile_cont(0.95)"
                        + " within group(order by f.duration_ms) as \"p95DurationMs\" from"
                        + " skill.execution_fact f join skill.skill_version v on"
                        + " v.tenant_id=f.tenant_id and v.id=f.skill_version_id where f.tenant_id=?"
                        + filter
                        + " group by v.skill_code,v.version_no,f.call_kind,f.is_mock,f.status order"
                        + " by v.skill_code,v.version_no";
        return code == null ? db.queryForList(sql, tenant()) : db.queryForList(sql, tenant(), code);
    }

    public Map<String, Object> statistics(
            String code, String from, String to, String groupBy, Integer version) {
        return new SkillStatistics(db, tx.getTransactionManager())
                .query(code, from, to, groupBy, version);
    }
}
