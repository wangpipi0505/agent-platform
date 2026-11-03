package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.ErrorSemantics;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * HTTP ACK is binding evidence, never the skill result. Only the original external job is queried.
 */
@Component
public class HttpAsyncAdapter {
    /** A verified query observation, not a business failure or permission to dispatch. */
    public static final class NotApplied extends SkillAdapters.Failure {
        public final Map<String, Object> source;

        NotApplied(Map<String, Object> source) {
            super("RESULT_UNKNOWN", true, "原任务明确报告未生效，等待引擎核实证明");
            this.source = Map.copyOf(source);
        }
    }

    /**
     * PLAT-05 实现点3：结果端点对已终结任务返回 409/4xx 是“去查状态端点”的信号而不是失败结论。
     * 该信号必须先回流到状态端点复核，能确定终态失败才产出 REMOTE_FAILED；因此它必须与普通
     * RESULT_UNKNOWN 区分——本类型只在独立结果端点的响应上抛出，invoke 捕获后复查。
     */
    private static final class ResultRejected extends SkillAdapters.Failure {
        final int status;

        ResultRejected(int status) {
            super("RESULT_UNKNOWN", true, "结果端点拒绝 HTTP " + status);
            this.status = status;
        }
    }

    /**
     * T06.2：状态端点对查询的明确拒绝（401/403）是“本次调用为何无法继续”的已核实结论——凭证
     * 问题不会因轮询消失，折叠成 RESULT_UNKNOWN 只会无限对账。它与 REMOTE_FAILED 一样必须
     * 穿透查询路径的兜底 catch；但查询失败不是外部任务的终态，绑定行保持原状，由调用方按
     * 可读错误处置。远端业务码/原文按 T05 默认语义随错误对象携带。
     */
    private static final class QueryRejected extends SkillAdapters.Failure {
        QueryRejected(int status, String code, String raw) {
            super(
                    "NO_PERMISSION",
                    false,
                    "外部任务查询被拒绝 HTTP "
                            + status
                            + "；请检查该技能的出站凭据与权限"
                            + (code == null || code.isBlank() ? "" : "，远端业务码=" + code),
                    code,
                    raw);
        }
    }

    /** 远端失败原文进入 varchar(512)：超出即截断，避免一条脏响应撑爆事实行。 */
    private static final int MAX_ERROR_MESSAGE = 512;

    private final JdbcTemplate db;
    private IdentityMappingClient identityMapping;

    @org.springframework.beans.factory.annotation.Autowired
    void identityMapping(IdentityMappingClient client) { this.identityMapping = client; }

    public HttpAsyncAdapter(JdbcTemplate db) {
        this.db = db;
    }

    public void bind(Map<String, Object> contract, Map<String, Object> request) {
        var actor = Actor.current();
        SkillAdapters.requireAttempt(number(request, "attemptId"));
        if (number(request, "skillVersionId") != number(contract, "skillVersionId")
                || !(request.get("arguments") instanceof Map)
                || !(request.get("guardedArguments") instanceof Map))
            throw error(409, "ASYNC_BINDING_MISMATCH", "原调用版本或冻结参数缺失");
        db.update(
                "insert into"
                    + " skill.external_job_binding(tenant_id,call_id,status,idempotency_key,principal_id,run_id,attempt_id,skill_version_id,request_snapshot,metadata)"
                    + " values(?,?,'REGISTERED',?,?,?,?,?,?::jsonb,?::jsonb) on conflict do"
                    + " nothing",
                actor.tenantId(),
                number(request, "callId"),
                text(request, "idempotencyKey"),
                actor.principalId(),
                text(request, "runId"),
                number(request, "attemptId"),
                number(request, "skillVersionId"),
                Json.write(request),
                Json.write(Map.of("protocol", "HTTP", "contractSha256", contract.get("sha256"))));
        var binding = binding(number(request, "callId"));
        if (!text(request, "idempotencyKey").equals(binding.get("idempotency_key"))
                || !text(request, "runId").equals(binding.get("run_id"))
                || number(request, "attemptId") != ((Number) binding.get("attempt_id")).longValue()
                || number(request, "skillVersionId")
                        != ((Number) binding.get("skill_version_id")).longValue()
                || !Json.canonical(request)
                        .equals(
                                Json.canonical(
                                        Json.read(binding.get("request_snapshot").toString())))
                || !Json.canonical(
                                Map.of(
                                        "protocol",
                                        "HTTP",
                                        "contractSha256",
                                        text(contract, "sha256")))
                        .equals(Json.canonical(Json.read(binding.get("metadata").toString()))))
            throw error(409, "ASYNC_BINDING_MISMATCH", "外部绑定与已登记逻辑调用不一致");
    }

    private Map<String, Object> binding(long callId) {
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?"
                                + " and principal_id=?",
                        Actor.current().tenantId(),
                        callId,
                        Actor.current().principalId());
        if (rows.isEmpty()) throw unknown("外部调用尚无可信绑定，请人工核实");
        return rows.getFirst();
    }

    public SkillAdapters.Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt,
            SkillAdapters outputs) {
        // 异步契约没有可重放的请求体：multipart 若被降级成 JSON 体发出，会得到一个形态错误的
        // 请求，再由下面的兜底 catch 折叠成“结果未知”，既不可重试也不可诊断。故在读绑定/落库
        // 之前显式拒绝；放到 try 之内会被吞成 RESULT_UNKNOWN，所以必须是 invoke 的第一条语句。
        if (MultipartFormMapping.declared(contract))
            throw new PlatformException(
                    400,
                    "ASYNC_MULTIPART_UNSUPPORTED",
                    "异步调用不支持multipart表单，请改用同步技能，或先上传产物并以引用传入");
        SkillAdapters.requireAttempt(expectedAttempt);
        var b = binding(callId);
        var generation = b;
        var snapshot = Json.read(b.get("request_snapshot").toString());
        if (expectedAttempt != ((Number) b.get("attempt_id")).longValue()
                || !key.equals(b.get("idempotency_key"))
                || !Objects.equals(runId, b.get("run_id"))
                || number(contract, "skillVersionId")
                        != ((Number) b.get("skill_version_id")).longValue()
                || !Objects.equals(
                        contract.get("sha256"),
                        Json.read(b.get("metadata").toString()).path("contractSha256").asText())
                || !"HTTP".equals(Json.read(b.get("metadata").toString()).path("protocol").asText())
                || snapshot.path("attemptId").asLong() != expectedAttempt
                || snapshot.path("callId").asLong() != callId
                || snapshot.path("skillVersionId").asLong() != number(contract, "skillVersionId")
                || !snapshot.path("runId").asText().equals(runId)
                || !snapshot.path("idempotencyKey").asText().equals(key)
                || !snapshot.path("arguments").isObject()
                || !snapshot.path("guardedArguments").isObject()
                || (!queryOnly
                        && !Json.canonical(arguments)
                                .equals(Json.canonical(snapshot.path("guardedArguments")))))
            throw unknown("外部绑定归属不匹配");
        // This durable CAS is the submitted-before-send fence. A process crash after it can only
        // query. Every identity comes from this invocation's original verified attempt and frozen
        // binding; a late worker must never adopt a newer REGISTERED row after reading it.
        if (!queryOnly
                && db.update(
                                "update skill.external_job_binding set"
                                    + " send_started=true,status='SENDING',updated_at=now() where"
                                    + " tenant_id=? and call_id=? and send_started=false and"
                                    + " status='REGISTERED' and principal_id=? and run_id=? and"
                                    + " attempt_id=? and skill_version_id=? and idempotency_key=?"
                                    + " and request_snapshot=?::jsonb and metadata=?::jsonb",
                                Actor.current().tenantId(),
                                callId,
                                Actor.current().principalId(),
                                runId,
                                expectedAttempt,
                                b.get("skill_version_id"),
                                key,
                                b.get("request_snapshot").toString(),
                                b.get("metadata").toString())
                        == 1) {
            try {
                var ack =
                        exchange(
                                contract,
                                text(contract, "endpoint"),
                                "POST",
                                arguments,
                                key,
                                65536);
                var policy = object(contract.getOrDefault("reconciliation", Map.of()));
                String job =
                        path(
                                        ack,
                                        Objects.toString(
                                                policy.getOrDefault(
                                                        "jobIdPath",
                                                        contract.getOrDefault(
                                                                "jobIdPath", "$.jobId"))))
                                .asText();
                if (job.isBlank()) {
                    // 远端接受了请求却没有给出任务标识：平台再也无法按原任务对账。
                    // 这不是"结果未知"（UNKNOWN 会被无限轮询且永远无法收敛），而是一次
                    // 可证明的提交失败，必须落 FAILED 并保留稳定错误码供界面直接展示。
                    db.update(
                            "update skill.external_job_binding set status='FAILED',"
                                + " external_state='SUBMIT_NO_TASK_ID',updated_at=now(),"
                                + " remote_error_code='SUBMIT_NO_TASK_ID',remote_error_message=?"
                                + " where tenant_id=? and call_id=? and external_job_id is null"
                                + " and principal_id=? and run_id=? and attempt_id=?"
                                + " and skill_version_id=? and idempotency_key=?"
                                + " and send_started=true",
                            noTaskIdMessage(),
                            Actor.current().tenantId(),
                            callId,
                            Actor.current().principalId(),
                            runId,
                            expectedAttempt,
                            b.get("skill_version_id"),
                            key);
                    throw new SkillAdapters.Failure(
                            "SUBMIT_NO_TASK_ID", false, noTaskIdMessage(), null, noTaskIdMessage());
                }
                if (job.length() > 128
                        || (echoRequired(contract)
                                && !path(
                                                ack,
                                                Objects.toString(
                                                        policy.getOrDefault(
                                                                "idempotencyKeyPath",
                                                                "$.idempotencyKey")))
                                        .asText()
                                        .equals(key)))
                    throw unknown("提交响应未证明原幂等键与任务归属");
                db.update(
                        "update skill.external_job_binding set"
                            + " external_job_id=?,status='SUBMITTED',external_state='SUBMITTED',"
                            + " submitted_at=coalesce(submitted_at,now()),last_polled_at=now(),"
                            + " updated_at=now() where"
                            + " tenant_id=? and call_id=? and external_job_id is null and"
                            + " principal_id=? and run_id=? and attempt_id=? and skill_version_id=?"
                            + " and idempotency_key=? and request_snapshot=?::jsonb and"
                            + " metadata=?::jsonb and send_started=true",
                        job,
                        Actor.current().tenantId(),
                        callId,
                        Actor.current().principalId(),
                        runId,
                        expectedAttempt,
                        b.get("skill_version_id"),
                        key,
                        b.get("request_snapshot").toString(),
                        b.get("metadata").toString());
            } catch (SkillAdapters.Failure ex) {
                throw ex;
            } catch (Exception ex) {
                throw unknown("提交结果不确定；保留原绑定，仅允许查询原幂等键");
            }
        }
        b = binding(callId);
        sameGeneration(generation, b);
        if (!Boolean.TRUE.equals(b.get("send_started"))) throw unknown("原调用尚未发送或发送栅栏已失效，禁止查询或重发");
        // UNKNOWN recovery may have fenced a sender whose submitted ACK arrived after lease loss.
        // That original worker has no authority to send or to invent a query for an unsent job.
        if ("NOT_SENT".equals(b.get("status"))) throw unknown("原调用未发送且已被恢复栅栏阻止，等待引擎授权恢复");
        if ("CALLBACK".equals(contract.get("invocationKind"))
                && (!queryOnly
                        || object(contract.getOrDefault("reconciliation", Map.of())).isEmpty()))
            throw unknown("原任务已提交，等待可信回调；不重新发送业务请求");
        var policy = object(contract.getOrDefault("reconciliation", Map.of()));
        String job = Objects.toString(b.get("external_job_id"), "");
        var pathParams = declaredPathParams(policy);
        var pathValues = pathParamValues(pathParams, job, key);
        String endpoint = text(policy, "endpoint");
        if (job.isBlank()
                && (endpoint.contains("{jobId}") || mapsExternalJobId(endpoint, pathParams)))
            endpoint = Objects.toString(policy.getOrDefault("lookupEndpoint", ""));
        if (endpoint.isBlank()) throw unknown("提交响应未取得jobId，需配置原幂等键查询或人工对账");
        // 任务标识未知时，声明式映射构造不出可寻址的查询：宁可不发请求等待对账，也不用空串拼出
        // 可能命中列表类端点的URL（响应归属校验依赖幂等键回显，契约禁用echo时没有第二道防线）。
        // 旧 {jobId} 不受此保护，保持既有空串替换行为不变。
        if (job.isBlank() && mapsExternalJobId(endpoint, pathParams))
            throw unknown(
                    "提交响应未取得jobId且查询端点需要任务标识，请配置原幂等键查询(lookupEndpoint)或人工对账");
        for (String name : placeholders(endpoint))
            if (!legacyPlaceholder(name) && !pathParams.containsKey(name))
                throw unknown(
                        "查询端点占位符 {"
                                + name
                                + "} 缺少映射（reconciliation.pathParams."
                                + name
                                + "），无法构造查询；请修正契约");
        endpoint = substitute(endpoint, pathValues);
        try (var streamed =
                exchangeStream(
                        contract,
                        endpoint,
                        key,
                        Objects.toString(policy.getOrDefault("resultPath", "$.result")))) {
            var response = streamed.metadata;
            String observedJob =
                    path(
                                    response,
                                    Objects.toString(
                                            policy.getOrDefault(
                                                    "jobIdPath",
                                                    contract.getOrDefault("jobIdPath", "$.jobId"))))
                            .asText();
            if (observedJob.isBlank()
                    || observedJob.length() > 128
                    || (!job.isBlank() && !job.equals(observedJob))
                    || (echoRequired(contract)
                            && !path(
                                            response,
                                            Objects.toString(
                                                    policy.getOrDefault(
                                                            "idempotencyKeyPath",
                                                            "$.idempotencyKey")))
                                    .asText()
                                    .equals(key)))
                throw unknown("查询响应的jobId或幂等键不属于原调用");
            if (job.isBlank())
                db.update(
                        "update skill.external_job_binding set"
                            + " external_job_id=?,status='SUBMITTED',external_state='SUBMITTED',"
                            + " submitted_at=coalesce(submitted_at,now()),last_polled_at=now(),"
                            + " updated_at=now() where"
                            + " tenant_id=? and call_id=? and external_job_id is null and"
                            + " principal_id=? and run_id=? and attempt_id=? and skill_version_id=?"
                            + " and idempotency_key=? and request_snapshot=?::jsonb and"
                            + " metadata=?::jsonb and send_started=true",
                        observedJob,
                        Actor.current().tenantId(),
                        callId,
                        Actor.current().principalId(),
                        runId,
                        expectedAttempt,
                        b.get("skill_version_id"),
                        key,
                        b.get("request_snapshot").toString(),
                        b.get("metadata").toString());
            // Concurrent key lookups arbitrate on PG, not on their stale pre-request snapshots.
            // A losing response may be evidence but must never become a result for another job.
            var resolvedBinding = binding(callId);
            sameGeneration(b, resolvedBinding);
            if (!observedJob.equals(resolvedBinding.get("external_job_id")))
                throw unknown("查询期间原任务绑定已由其他事实确定，拒绝采纳不同job结果");
            JsonNode state = path(response, text(policy, "donePath"));
            String remoteState = boundedState(state);
            // 轮询只刷新可观测活性（last_polled_at / external_state），不改变事实状态；
            // 已终结的绑定（DONE/FAILED）不允许被迟到的 RUNNING 覆盖，否则界面会把
            // 已收敛的任务重新显示为"处理中"。
            db.update(
                    "update skill.external_job_binding set last_polled_at=now(),"
                        + " external_state=?,updated_at=now() where tenant_id=? and call_id=?"
                        + " and principal_id=? and external_job_id=?"
                        + " and status in ('SENDING','SUBMITTED')",
                    remoteState,
                    Actor.current().tenantId(),
                    callId,
                    Actor.current().principalId(),
                    observedJob);
            if (queryOnly
                    && policy.containsKey("notAppliedValue")
                    && policy.get("notAppliedValue") != null
                    && !Json.tree(policy.get("notAppliedValue"))
                            .equals(Json.tree(policy.get("doneValue")))
                    && state.equals(Json.tree(policy.get("notAppliedValue")))) {
                throw new NotApplied(
                        Map.of(
                                "outcome",
                                "NOT_APPLIED",
                                "sourceKind",
                                "HTTP_QUERY",
                                "externalJobId",
                                observedJob,
                                "externalState",
                                state,
                                "sourceSha256",
                                Digest.sha256(Json.canonical(response)),
                                "queryEndpointSha256",
                                Digest.sha256(endpoint),
                                "sendStarted",
                                true,
                                "sendStatus",
                                resolvedBinding.get("status")));
            }
            if (!state.equals(Json.tree(policy.get("doneValue")))) {
                // A trustworthy terminal failure is not uncertainty: it must surface the service's
                // own reason instead of parking the call in UNKNOWN forever.
                if (failedState(policy, state))
                    throw remoteFailed(contract, policy, response, remoteState, callId, observedJob);
                throw unknown("原任务尚未确认完成，保持UNKNOWN等待查询");
            }
            SkillAdapters.Raw raw;
            String resultEndpoint = Objects.toString(policy.get("resultEndpoint"), "");
            if (!resultEndpoint.isBlank()) {
                // Some services split status and result. The result endpoint is derived from the
                // job id already proven above and never widens the binding.
                for (String name : placeholders(resultEndpoint))
                    if (!legacyPlaceholder(name) && !pathParams.containsKey(name))
                        throw unknown(
                                "结果端点占位符 {"
                                        + name
                                        + "} 缺少映射（reconciliation.pathParams."
                                        + name
                                        + "），无法构造查询；请修正契约");
                String resultSource =
                        substitute(resultEndpoint, pathParamValues(pathParams, observedJob, key));
                // 状态响应与结果响应常是两套结构，结果侧独立声明读取路径，默认取整个响应体。
                String resultReadPath = Objects.toString(policy.get("resultEndpointPath"), "");
                try (var finalResponse =
                        resultEndpointStream(
                                contract,
                                resultSource,
                                key,
                                resultReadPath.isBlank() ? "$" : resultReadPath)) {
                    try (var result = finalResponse.result()) {
                        raw = outputs.read(result, contract, callId, runId, expectedAttempt, 0);
                    }
                } catch (ResultRejected rejected) {
                    // PLAT-05 实现点3：结果端点 409/4xx 常见于“结果已清理/任务已失败，请查状态”。
                    // 复查同一状态端点：能确定终态失败才改判 REMOTE_FAILED；仍不能确定就保持
                    // RESULT_UNKNOWN 走对账，绝不把 409 直接抛给用户或当成业务结果。
                    var rechecked = statusResponse(contract, endpoint, key);
                    if (rechecked != null
                            && failedState(policy, path(rechecked, text(policy, "donePath"))))
                        throw remoteFailed(
                                contract,
                                policy,
                                rechecked,
                                boundedState(path(rechecked, text(policy, "donePath"))),
                                callId,
                                observedJob);
                    throw unknown(
                            "结果端点拒绝 HTTP "
                                    + rejected.status
                                    + " 且状态端点未能核实终态失败，保留绑定等待对账");
                }
            } else {
                try (var result = streamed.result()) {
                    raw = outputs.read(result, contract, callId, runId, expectedAttempt, 0);
                }
            }
            int changed =
                    db.update(
                            "update skill.external_job_binding set status='DONE',"
                            + " external_state='SUCCEEDED',last_polled_at=now(),updated_at=now()"
                                + " where tenant_id=? and call_id=? and principal_id=? and run_id=?"
                                + " and attempt_id=? and skill_version_id=? and idempotency_key=?"
                                + " and external_job_id=? and request_snapshot=?::jsonb and"
                                + " metadata=?::jsonb and send_started=true",
                            Actor.current().tenantId(),
                            callId,
                            Actor.current().principalId(),
                            runId,
                            expectedAttempt,
                            b.get("skill_version_id"),
                            key,
                            observedJob,
                            b.get("request_snapshot").toString(),
                            b.get("metadata").toString());
            if (changed != 1) throw unknown("原任务代次或绑定已改变，拒绝更新旧查询完成状态");
            return raw;
        } catch (NotApplied evidence) {
            throw evidence;
        } catch (SkillAdapters.Failure failure) {
            // T06.2：只有“已核实结论”（服务端终态失败、查询被401/403明确拒绝）可以穿透；
            // 本地验收异常与“任务暂不可见”仍是不确定证据——折叠保留绑定等待对账，但消息必须
            // 携带原始语义与原因，cause 保留，不能把一次404折叠成无法定位的“尚未完成”。
            if ("REMOTE_FAILED".equals(failure.semantic) || failure instanceof QueryRejected
                    || failure instanceof IdentityMappingClient.BeforeDispatchFailure)
                throw failure;
            throw unknown(
                    "原任务查询或结果验收尚未完成（"
                            + failure.semantic
                            + "："
                            + failure.getMessage()
                            + "）；保留绑定等待对账",
                    failure);
        } catch (PlatformException ex) {
            throw unknown(
                    "原任务查询或结果验收尚未完成（"
                            + ex.code()
                            + "："
                            + ex.getMessage()
                            + "）；保留绑定等待对账",
                    ex);
        } catch (Exception ex) {
            throw unknown(
                    "原任务查询或结果验收尚未完成（"
                            + ex.getClass().getSimpleName()
                            + "："
                            + ex.getMessage()
                            + "）；保留绑定等待对账",
                    ex);
        }
    }

    private static void sameGeneration(Map<String, Object> original, Map<String, Object> current) {
        for (String field :
                List.of(
                        "attempt_id",
                        "skill_version_id",
                        "principal_id",
                        "run_id",
                        "idempotency_key"))
            if (!Objects.equals(original.get(field), current.get(field)))
                throw unknown("原任务代次已经改变，拒绝采纳旧查询");
        if (!Json.canonical(Json.read(original.get("request_snapshot").toString()))
                .equals(Json.canonical(Json.read(current.get("request_snapshot").toString()))))
            throw unknown("原任务快照已经改变，拒绝采纳旧查询");
        if (!Json.canonical(Json.read(original.get("metadata").toString()))
                .equals(Json.canonical(Json.read(current.get("metadata").toString()))))
            throw unknown("原任务合同身份已经改变，拒绝采纳旧查询");
    }

    private AsyncHttpResponse exchangeStream(
            Map<String, Object> contract, String endpoint, String key, String resultPath) {
        return request(contract, endpoint, "GET", null, key)
                .exchange(
                        (sent, response) -> {
                            int status = response.getStatusCode().value();
                            if (status >= 400 && status < 500) {
                                // T06.2：查询被明确拒绝时区分两类结论——401/403 是“凭证无法通过”，
                                // 轮询永远解决不了，按 T05 默认语义改判 NO_PERMISSION；其余4xx
                                // （404/409…）只是“任务此刻不可见”，仍属结果未知继续对账。两类都
                                // 携带远端业务码与原文，不再折叠成一句无法定位的“未返回确认”。
                                var remote =
                                        SkillAdapters.remoteRejection(
                                                contract, response.getBody());
                                if (status == 401 || status == 403)
                                    throw new QueryRejected(
                                            status, remote.code(), remote.message());
                                throw unknown(
                                        "异步查询未返回确认 HTTP " + status + remoteHint(remote));
                            }
                            if (!response.getStatusCode().is2xxSuccessful())
                                throw unknown("异步查询未返回确认 HTTP " + status);
                            return AsyncHttpResponse.read(response.getBody(), resultPath);
                        });
    }

    /** 远端业务码/原文的短摘要，用于不确定类错误的可读化；远端未提供时为空串。 */
    private static String remoteHint(ErrorSemantics.Remote remote) {
        String code = ErrorSemantics.bound(remote.code());
        String message = ErrorSemantics.bound(remote.message());
        if (code == null && message == null) return "";
        return "（code=" + Objects.toString(code, "")
                + (message == null ? "" : " message=" + message) + "）";
    }

    /**
     * PLAT-05 实现点3：独立结果端点与状态端点的错误处理不同。状态端点的 4xx（如任务尚未可见）
     * 仍是“结果未知”；结果端点的 4xx 是需要复查状态端点的信号（{@link ResultRejected}）。
     */
    private AsyncHttpResponse resultEndpointStream(
            Map<String, Object> contract, String endpoint, String key, String resultPath) {
        return request(contract, endpoint, "GET", null, key)
                .exchange(
                        (sent, response) -> {
                            int status = response.getStatusCode().value();
                            if (status >= 400 && status < 500) throw new ResultRejected(status);
                            if (!response.getStatusCode().is2xxSuccessful())
                                throw unknown("异步查询未返回确认");
                            return AsyncHttpResponse.read(response.getBody(), resultPath);
                        });
    }

    /** 结果端点被拒后的状态端点复核；任何不确定（非2xx/解析失败）都返回 null 表示无法核实。 */
    private JsonNode statusResponse(Map<String, Object> contract, String endpoint, String key) {
        try {
            return exchange(contract, endpoint, "GET", null, key, 65536);
        } catch (IdentityMappingClient.BeforeDispatchFailure blocked) {
            throw blocked;
        } catch (Exception rejected) {
            return null;
        }
    }

    private JsonNode exchange(
            Map<String, Object> contract,
            String endpoint,
            String method,
            Object body,
            String key,
            int limit) {
        return request(contract, endpoint, method, body, key)
                .exchange(
                        (sent, response) -> {
                            int status = response.getStatusCode().value();
                            if (status >= 400 && status < 500) {
                                // PLAT-05：提交被明确拒绝时读取远端业务码与原文（如路线三桥
                                // ok:false envelope 的 code/message），不再只留下裸 HTTP 状态。
                                var remote =
                                        SkillAdapters.remoteRejection(
                                                contract, response.getBody());
                                throw new SkillAdapters.Failure(
                                        status == 403 || status == 401
                                                ? "NO_PERMISSION"
                                                : status == 429 ? "QUOTA" : "PARAM_ERROR",
                                        false,
                                        "外部提交被明确拒绝 HTTP " + status,
                                        remote.code(),
                                        remote.message());
                            }
                            if (!response.getStatusCode().is2xxSuccessful())
                                throw unknown("异步端点未返回确认");
                            byte[] bytes = response.getBody().readNBytes(limit + 1);
                            if (bytes.length > limit) throw unknown("异步协议响应超出限制");
                            return ArtifactStore.readJson(new ByteArrayInputStream(bytes));
                        });
    }

    /**
     * 异步提交/查询共用的请求构造。包内可见是为了让测试直接断言真实出站头：鉴权方式属于安全相关
     * 行为，同步与异步必须由同一份契约映射驱动，而 invoke() 依赖 external_job_binding 表，无法在
     * 纯单测里轻量到达这里。
     */
    RestClient.RequestBodySpec request(
            Map<String, Object> contract, String endpoint, String method, Object body, String key) {
        SkillRules.endpoint(endpoint);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(((Number) contract.get("timeoutMs")).intValue());
        var request =
                RestClient.builder()
                        .requestFactory(factory)
                        .build()
                        .method(org.springframework.http.HttpMethod.valueOf(method))
                        .uri(java.net.URI.create(endpoint))
                        .header("Idempotency-Key", key)
                        .header("Accept", "application/json");
        // 与同步适配器共用同一份契约→头的映射：异步只多了提交/查询两步，鉴权方式不能有两套实现，
        // 否则同一技能在 SYNC 与 SUBMIT_QUERY 之间会发出不同凭据。凭据解析失败同样在发请求前抛出。
        AuthScheme.headers(
                        contract.get(AuthScheme.FIELD),
                        Objects.toString(contract.get("authRef"), ""))
                .forEach(request::header);
        // 身份头与鉴权头叠加（PLAT-12 §4.12）：与同步适配器共用 SkillAdapters.identityHeaders，
        // 声明了 identity 的技能在提交与查询请求上都带同一组 X-User-*；无可用主体时跳过不伪造。
        if (IdentityMappingClient.declared(contract)) {
            if (identityMapping == null) throw IdentityMappingClient.missingClient();
            identityMapping.headers(contract).forEach(request::header);
        }
        if (body != null)
            request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return request;
    }

    private static String segment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 占位符语法：{name}，name 为字母开头的标识符。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    /** pathParams.from 允许的平台字段；远端命名自由度只存在于占位符一侧（PLAT-06）。 */
    private static final Set<String> PATH_PARAM_SOURCES = Set.of("externalJobId", "idempotencyKey");

    /**
     * 旧契约占位符：等价于内置默认映射（jobId→外部任务标识、idempotencyKey→原幂等键），永远可用、
     * 语义固定。新契约应改用 pathParams 声明远端自己的占位符名，避免“远端叫 taskId 却必须写
     * jobId”的平台内部知识泄漏。
     */
    public static boolean legacyPlaceholder(String name) {
        return name.equals("jobId") || name.equals("idempotencyKey");
    }

    /** 端点里的占位符集合（按出现顺序去重）。 */
    public static LinkedHashSet<String> placeholders(String url) {
        var names = new LinkedHashSet<String>();
        var matcher = PLACEHOLDER.matcher(Objects.toString(url, ""));
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    /**
     * PLAT-06：校验 reconciliation.pathParams 与查询端点占位符一一对应，返回 占位符名→平台字段。
     * 必须在端点 URI 形状检查之前调用：否则 {remoteTaskId} 会先在 URI.create 报出无法定位的
     * 参数错误，接入方无从得知需要声明映射。端点里的非旧占位符缺少声明 → 400
     * ASYNC_PATH_PARAM_MISSING（带字段路径与修复建议）；声明了端点没有的占位符 → 400
     * ASYNC_PATH_PARAM_UNMATCHED；from 非法或重定义旧占位符 → 400 INVALID_ASYNC_PROTOCOL。
     */
    public static Map<String, String> validatedPathParams(
            Map<String, Object> policy, String... endpoints) {
        var holes = new LinkedHashSet<String>();
        for (String endpoint : endpoints)
            if (endpoint != null && !endpoint.isBlank()) holes.addAll(placeholders(endpoint));
        var mappings = new LinkedHashMap<String, String>();
        Object declared = policy.get("pathParams");
        if (declared != null) {
            if (!(declared instanceof Map<?, ?> raw) || raw.isEmpty())
                throw error(
                        400,
                        "INVALID_ASYNC_PROTOCOL",
                        "reconciliation.pathParams必须为非空对象，形如 {\"占位符\":{\"from\":\"externalJobId\"}}");
            for (var entry : raw.entrySet()) {
                String name = Objects.toString(entry.getKey(), "");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw error(
                            400,
                            "INVALID_ASYNC_PROTOCOL",
                            "reconciliation.pathParams键必须为字母开头的占位符名，收到: " + name);
                if (legacyPlaceholder(name))
                    throw error(
                            400,
                            "INVALID_ASYNC_PROTOCOL",
                            "reconciliation.pathParams不得重定义旧占位符 {"
                                    + name
                                    + "}（其默认映射已内置且语义固定）");
                if (!(entry.getValue() instanceof Map<?, ?> binding)
                        || binding.isEmpty()
                        || !(binding.get("from") instanceof String from)
                        || from.isBlank())
                    throw error(
                            400,
                            "INVALID_ASYNC_PROTOCOL",
                            "reconciliation.pathParams."
                                    + name
                                    + " 必须为对象且携带from字段，形如 {\"from\":\"externalJobId\"}");
                if (!PATH_PARAM_SOURCES.contains(from))
                    throw error(
                            400,
                            "INVALID_ASYNC_PROTOCOL",
                            "reconciliation.pathParams."
                                    + name
                                    + ".from 只能为 externalJobId 或 idempotencyKey，收到: "
                                    + from);
                if (!holes.contains(name))
                    throw error(
                            400,
                            "ASYNC_PATH_PARAM_UNMATCHED",
                            "reconciliation.pathParams."
                                    + name
                                    + " 未出现在任何查询端点中：请在 reconciliation.endpoint 或"
                                    + " resultEndpoint 使用 {"
                                    + name
                                    + "}，或删除该映射");
                mappings.put(name, from);
            }
        }
        // 缺映射校验独立于是否声明了pathParams：只要端点用了远端自己的占位符就必须补映射。
        for (String name : holes) {
            if (legacyPlaceholder(name) || mappings.containsKey(name)) continue;
            throw error(
                    400,
                    "ASYNC_PATH_PARAM_MISSING",
                    "查询端点占位符 {"
                            + name
                            + "} 缺少映射：请声明 reconciliation.pathParams."
                            + name
                            + "（例如 {\"from\":\"externalJobId\"}），或改用旧占位符"
                            + " {jobId}/{idempotencyKey}");
        }
        return mappings;
    }

    /** 运行期容忍读取：畸形声明按空映射处理；保存期已拒绝，这里只为历史脏数据保底。 */
    private static Map<String, String> declaredPathParams(Map<String, Object> policy) {
        if (!(policy.get("pathParams") instanceof Map<?, ?> raw)) return Map.of();
        var mappings = new LinkedHashMap<String, String>();
        for (var entry : raw.entrySet()) {
            String from =
                    entry.getValue() instanceof Map<?, ?> binding
                            ? Objects.toString(binding.get("from"), "")
                            : "";
            if (!from.isBlank()) mappings.put(Objects.toString(entry.getKey(), ""), from);
        }
        return mappings;
    }

    /** 旧占位符 + 声明式 pathParams 的取值表（值已URL段编码）；旧占位符永远存在且语义固定。 */
    private static Map<String, String> pathParamValues(
            Map<String, String> pathParams, String job, String key) {
        var values = new LinkedHashMap<String, String>();
        values.put("jobId", segment(job));
        values.put("idempotencyKey", segment(key));
        for (var entry : pathParams.entrySet()) {
            if (legacyPlaceholder(entry.getKey())) continue;
            values.put(
                    entry.getKey(),
                    segment("idempotencyKey".equals(entry.getValue()) ? key : job));
        }
        return values;
    }

    /** 保存期URL形状校验用：把全部占位符替换为哑段，之后 URI.create 才有意义。 */
    public static String dummyUrl(String url, Map<String, String> mappings) {
        var values = new LinkedHashMap<String, String>();
        values.put("jobId", "job");
        values.put("idempotencyKey", "key");
        for (String name : placeholders(url)) if (mappings.containsKey(name)) values.put(name, "p");
        return substitute(url, values);
    }

    /** 按 values 替换 {name}；未知的名字替换为空串（调用方必须先做缺映射检查）。 */
    static String substitute(String url, Map<String, String> values) {
        var matcher = PLACEHOLDER.matcher(Objects.toString(url, ""));
        var result = new StringBuilder();
        while (matcher.find())
            matcher.appendReplacement(
                    result,
                    java.util.regex.Matcher.quoteReplacement(
                            Objects.toString(values.get(matcher.group(1)), "")));
        matcher.appendTail(result);
        return result.toString();
    }

    /** 端点是否依赖“外部任务标识”占位符（旧 {jobId} 由调用点单独判断，保持旧分支行为不变）。 */
    private static boolean mapsExternalJobId(String url, Map<String, String> pathParams) {
        for (String name : placeholders(url))
            if ("externalJobId".equals(pathParams.get(name))) return true;
        return false;
    }

    /** 终态失败只认显式配置的标记，绝不把普通状态文本当成失败。 */
    private static boolean failedState(Map<String, Object> policy, JsonNode state) {
        Object raw = policy.containsKey("failedValues")
                ? policy.get("failedValues")
                : policy.get("failedValue");
        if (raw == null) return false;
        JsonNode failed = Json.tree(raw);
        if (!failed.isArray()) failed = Json.mapper().createArrayNode().add(failed);
        for (JsonNode value : failed) if (state.equals(value)) return true;
        return false;
    }

    private static String failureMessage(Map<String, Object> policy, JsonNode response) {
        String messagePath = Objects.toString(policy.get("failureMessagePath"), "");
        String reason = messagePath.isBlank() ? "" : path(response, messagePath).asText("");
        return bound(
                reason.isBlank() ? "外部任务终态失败，服务未提供失败原因" : "外部任务终态失败：" + reason);
    }

    /**
     * 远端已核实终态失败（PLAT-05）：绑定行收敛为 FAILED，失败对象携带契约声明的业务码
     * （errorSemantics.codePath）与有界原文（reconciliation.failureMessagePath 优先，其次
     * errorSemantics.messagePath）。引擎、运行详情与模型看到的都是同一份可读原因。
     */
    private SkillAdapters.Failure remoteFailed(
            Map<String, Object> contract,
            Map<String, Object> policy,
            JsonNode response,
            String remoteState,
            long callId,
            String job) {
        var declared = SkillAdapters.declaredMapping(contract);
        String policyPath = Objects.toString(policy.get("failureMessagePath"), "");
        String raw = policyPath.isBlank() ? "" : path(response, policyPath).asText("");
        if ((raw.isBlank() || "null".equals(raw)) && declared.messagePath() != null) {
            String declaredMessage = path(response, declared.messagePath()).asText("");
            if (!declaredMessage.isBlank() && !"null".equals(declaredMessage)) raw = declaredMessage;
        }
        String code = declared.codePath() == null ? null : path(response, declared.codePath()).asText("");
        String message = failureMessage(policy, response);
        db.update(
                "update skill.external_job_binding set status='FAILED',"
                    + " external_state=?,remote_error_code='REMOTE_FAILED',"
                    + " remote_error_message=?,last_polled_at=now(),updated_at=now()"
                    + " where tenant_id=? and call_id=? and principal_id=?"
                    + " and external_job_id=? and status in ('SENDING','SUBMITTED')",
                remoteState,
                message,
                Actor.current().tenantId(),
                callId,
                Actor.current().principalId(),
                job);
        return new SkillAdapters.Failure(
                "REMOTE_FAILED",
                false,
                message,
                ErrorSemantics.bound(code),
                ErrorSemantics.bound(raw));
    }

    private static String noTaskIdMessage() {
        return "外部异步提交未返回任务标识，无法证明提交成功";
    }

    /** 远端状态原文进入 varchar(32)；超长即截断，保证绑定行永远可写。 */
    private static String boundedState(JsonNode state) {
        String value = state.isTextual() ? state.asText() : state.toString();
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    /** 远端原文有界（含省略号仍不超过 varchar(512)），避免一条脏响应撑爆事实行。 */
    private static String bound(String message) {
        return message.length() <= MAX_ERROR_MESSAGE
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE - 1) + "…";
    }

    public static JsonNode path(JsonNode value, String path) {
        if (!path.equals("$") && !path.matches("\\$\\.[A-Za-z_][A-Za-z0-9_.]*"))
            throw error(400, "ASYNC_PATH_INVALID", "异步协议路径仅支持根路径或点路径");
        if (path.equals("$")) return value;
        for (String part : path.substring(2).split("\\.")) value = value.path(part);
        return value;
    }

    /**
     * Most services echo the caller's idempotency key. A service that instead returns only its own
     * task id can opt out explicitly; job id, immutable binding and the idempotency header still
     * bind the exchange to this call, but the weaker acknowledgement contract must be visible.
     */
    private static boolean echoRequired(Map<String, Object> contract) {
        Object reconciliation = contract.get("reconciliation");
        if (!(reconciliation instanceof Map<?, ?> policy)) return true;
        return !Boolean.FALSE.equals(policy.get("idempotencyKeyEcho"));
    }

    private static SkillAdapters.Failure unknown(String message) {
        return new SkillAdapters.Failure("RESULT_UNKNOWN", true, message);
    }

    /** 折叠为结果未知时保留被吞掉的异常为 cause，保证诊断链不中断（T06.2）。 */
    private static SkillAdapters.Failure unknown(String message, Throwable cause) {
        var failure = unknown(message);
        failure.initCause(cause);
        return failure;
    }
}
