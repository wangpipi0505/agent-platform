package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
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

    /** 远端失败原文进入 varchar(512)：超出即截断，避免一条脏响应撑爆事实行。 */
    private static final int MAX_ERROR_MESSAGE = 512;

    private final JdbcTemplate db;

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
                    throw new SkillAdapters.Failure("SUBMIT_NO_TASK_ID", false, noTaskIdMessage());
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
        String endpoint = text(policy, "endpoint");
        if (job.isBlank() && endpoint.contains("{jobId}"))
            endpoint = Objects.toString(policy.getOrDefault("lookupEndpoint", ""));
        if (endpoint.isBlank()) throw unknown("提交响应未取得jobId，需配置原幂等键查询或人工对账");
        endpoint =
                endpoint.replace("{jobId}", segment(job)).replace("{idempotencyKey}", segment(key));
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
                if (failedState(policy, state)) {
                    String message = failureMessage(policy, response);
                    // 远端终态失败是可核实事实：错误码与有界原文落在绑定行上，
                    // 引擎/网关无需再猜"未知"，也不再无限轮询。
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
                            observedJob);
                    throw new SkillAdapters.Failure("REMOTE_FAILED", false, message);
                }
                throw unknown("原任务尚未确认完成，保持UNKNOWN等待查询");
            }
            SkillAdapters.Raw raw;
            String resultEndpoint = Objects.toString(policy.get("resultEndpoint"), "");
            if (!resultEndpoint.isBlank()) {
                // Some services split status and result. The result endpoint is derived from the
                // job id already proven above and never widens the binding.
                String resultSource = resultEndpoint.replace("{jobId}", segment(observedJob));
                // 状态响应与结果响应常是两套结构，结果侧独立声明读取路径，默认取整个响应体。
                String resultReadPath = Objects.toString(policy.get("resultEndpointPath"), "");
                try (var finalResponse =
                        exchangeStream(
                                contract,
                                resultSource,
                                key,
                                resultReadPath.isBlank() ? "$" : resultReadPath)) {
                    try (var result = finalResponse.result()) {
                        raw = outputs.read(result, contract, callId, runId);
                    }
                }
            } else {
                try (var result = streamed.result()) {
                    raw = outputs.read(result, contract, callId, runId);
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
            // 只有"服务端已核实终态失败"这一显式协议结论可以穿透；本地验收异常仍是不确定证据。
            if ("REMOTE_FAILED".equals(failure.semantic)) throw failure;
            throw unknown("原任务查询或结果验收尚未完成，保留绑定等待对账");
        } catch (Exception ex) {
            throw unknown("原任务查询或结果验收尚未完成，保留绑定等待对账");
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
                            if (!response.getStatusCode().is2xxSuccessful())
                                throw unknown("异步查询未返回确认");
                            return AsyncHttpResponse.read(response.getBody(), resultPath);
                        });
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
                            if (status >= 400 && status < 500)
                                throw new SkillAdapters.Failure(
                                        status == 403 || status == 401
                                                ? "NO_PERMISSION"
                                                : status == 429 ? "QUOTA" : "PARAM_ERROR",
                                        false,
                                        "外部提交被明确拒绝 HTTP " + status);
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
        if (body != null)
            request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body);
        return request;
    }

    private static String segment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
}
