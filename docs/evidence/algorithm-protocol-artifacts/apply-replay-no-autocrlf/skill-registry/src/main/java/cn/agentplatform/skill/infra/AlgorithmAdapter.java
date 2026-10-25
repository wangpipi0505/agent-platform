package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;
import static cn.agentplatform.skill.infra.SkillAdapters.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.*;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;

import okhttp3.*;

import okio.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One durable original job: no replacement name, automatic POST retry, reset or DELETE. */
@Component
public class AlgorithmAdapter {
    static final String IDENTITY = "agent-platform.cn/identity-sha256";
    private final JdbcTemplate db;
    private final Map<String, String> configuration;
    private final int metadataLimit;
    private final long logLimit;

    @org.springframework.beans.factory.annotation.Autowired
    public AlgorithmAdapter(JdbcTemplate db) {
        this(db, System.getenv(), 1024 * 1024, ArtifactStore.MAX_ARTIFACT_BYTES);
    }

    AlgorithmAdapter(
            JdbcTemplate db, Map<String, String> configuration, int metadataLimit, long logLimit) {
        if (metadataLimit < 1
                || metadataLimit > 1024 * 1024
                || logLimit < 1
                || logLimit > ArtifactStore.MAX_ARTIFACT_BYTES)
            throw new IllegalArgumentException("Algorithm I/O budgets");
        this.db = db;
        this.configuration = Map.copyOf(configuration);
        this.metadataLimit = metadataLimit;
        this.logLimit = logLimit;
    }

    private record Settings(String api, String namespace, String account, String image) {}

    private Settings settings(Map<String, Object> contract) {
        try {
            var uri = URI.create(Objects.toString(configuration.get("AGENT_K8S_API"), ""));
            String namespace = configuration.get("AGENT_ALGORITHM_NAMESPACE"),
                    account = configuration.get("AGENT_ALGORITHM_SERVICE_ACCOUNT");
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() < -1
                    || !Objects.toString(uri.getPath(), "").matches("(?:/[A-Za-z0-9_-]+)*/?"))
                throw new IllegalArgumentException();
            boolean loopback = Set.of("127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !("http".equalsIgnoreCase(uri.getScheme())
                            && loopback
                            && "true"
                                    .equals(
                                            configuration.get(
                                                    "AGENT_ALGORITHM_ALLOW_LOOPBACK_HTTP"))))
                throw new IllegalArgumentException();
            if (namespace == null
                    || namespace.length() > 63
                    || !namespace.matches("[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
                    || account == null
                    || account.length() > 253
                    || !account.matches("[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?"))
                throw new IllegalArgumentException();
            String image = text(object(contract.get("jobTemplate")), "image");
            if (!Arrays.stream(
                            Objects.toString(
                                            configuration.get("AGENT_ALGORITHM_ALLOWED_IMAGES"), "")
                                    .split(","))
                    .map(String::trim)
                    .toList()
                    .contains(image)) throw new Failure("NO_PERMISSION", false, "算法镜像未在部署允许清单中");
            String path = Objects.toString(uri.getPath(), "").replaceAll("/$", "");
            int port = uri.getPort();
            if (port == 443 && "https".equalsIgnoreCase(uri.getScheme())
                    || port == 80 && "http".equalsIgnoreCase(uri.getScheme())) port = -1;
            String api =
                    new URI(
                                    uri.getScheme().toLowerCase(Locale.ROOT),
                                    null,
                                    uri.getHost().toLowerCase(Locale.ROOT),
                                    port,
                                    path,
                                    null,
                                    null)
                            .toASCIIString();
            return new Settings(api, namespace, account, image);
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            throw unknown("ALGORITHM_ENV_UNAVAILABLE: 必须显式配置可信专用API/namespace/serviceAccount");
        }
    }

    public void bind(Map<String, Object> contract, Map<String, Object> request) {
        requireAttempt(number(request, "attemptId"));
        if (number(request, "skillVersionId") != number(contract, "skillVersionId")
                || number(request, "nodeInstanceId") < 1
                || !(request.get("arguments") instanceof Map)
                || !(request.get("guardedArguments") instanceof Map)) throw unknown("算法登记缺少完整原代快照");
        var s = settings(contract);
        var a = Actor.current();
        var metadata =
                Map.of(
                        "protocol",
                        "ALGORITHM",
                        "contractSha256",
                        text(contract, "sha256"),
                        "image",
                        s.image,
                        "serviceAccount",
                        s.account);
        var identity = new LinkedHashMap<String, Object>();
        identity.put("tenant_id", a.tenantId());
        identity.put("principal_id", a.principalId());
        identity.put("call_id", number(request, "callId"));
        identity.put("run_id", text(request, "runId"));
        identity.put("attempt_id", number(request, "attemptId"));
        identity.put("skill_version_id", number(request, "skillVersionId"));
        identity.put("request_snapshot", Json.write(request));
        identity.put("metadata", Json.write(metadata));
        identity.put(
                "external_job_id",
                "alg-" + Digest.sha256(text(request, "idempotencyKey")).substring(0, 40));
        identity.put("idempotency_key", text(request, "idempotencyKey"));
        identity.put("external_api_sha256", Digest.sha256(s.api));
        identity.put("external_namespace", s.namespace);
        String digest = identityDigest(identity, contract);
        try {
            // Only duplicate logical registration is recoverable. A distinct job/UID unique
            // conflict
            // is never swallowed as if it were the same call.
            db.update(
                    "insert into"
                        + " skill.external_job_binding(tenant_id,call_id,external_job_id,status,idempotency_key,principal_id,run_id,attempt_id,skill_version_id,request_snapshot,metadata,external_namespace,external_api_sha256,external_identity_sha256)"
                        + " values(?,?,?,'REGISTERED',?,?,?,?,?,?::jsonb,?::jsonb,?,?,?) on"
                        + " conflict(tenant_id,call_id) do nothing",
                    a.tenantId(),
                    number(request, "callId"),
                    identity.get("external_job_id"),
                    text(request, "idempotencyKey"),
                    a.principalId(),
                    text(request, "runId"),
                    number(request, "attemptId"),
                    number(request, "skillVersionId"),
                    Json.write(request),
                    Json.write(metadata),
                    s.namespace,
                    Digest.sha256(s.api),
                    digest);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw unknown("算法外部任务身份已被其他调用占有");
        }
        var b = binding(number(request, "callId"));
        validateBinding(b, contract);
        if (!digest.equals(b.get("external_identity_sha256"))
                || !Json.canonical(request)
                        .equals(Json.canonical(Json.read(b.get("request_snapshot").toString()))))
            throw unknown("算法登记与既有原调用不一致");
    }

    private Map<String, Object> binding(long call) {
        var a = Actor.current();
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and"
                                + " principal_id=? and call_id=?",
                        a.tenantId(),
                        a.principalId(),
                        call);
        if (rows.isEmpty()) throw unknown("LEGACY_BINDING: 缺少可信算法原绑定，禁止补建或猜回填");
        return rows.getFirst();
    }

    /**
     * Recompute from the frozen full identity; the annotation is an ownership mark, not a
     * signature. Trust additionally requires dedicated namespace/RBAC and a trusted API origin.
     */
    public static String identityDigest(Map<String, Object> b, Map<String, Object> contract) {
        try {
            var r = Json.read(b.get("request_snapshot").toString());
            var m = Json.read(b.get("metadata").toString());
            var identity = new LinkedHashMap<String, Object>();
            for (String field :
                    List.of(
                            "tenant_id",
                            "principal_id",
                            "call_id",
                            "run_id",
                            "attempt_id",
                            "skill_version_id",
                            "idempotency_key",
                            "external_job_id",
                            "external_namespace",
                            "external_api_sha256")) {
                if (b.get(field) == null) throw new IllegalArgumentException();
                identity.put(field, b.get(field));
            }
            identity.put("nodeInstanceId", r.path("nodeInstanceId").asLong());
            identity.put("contractSha256", contract.get("sha256"));
            identity.put("argsDigest", Digest.sha256(Json.canonical(r.path("arguments"))));
            identity.put(
                    "guardedArgsDigest", Digest.sha256(Json.canonical(r.path("guardedArguments"))));
            identity.put("image", m.path("image").asText());
            identity.put("serviceAccount", m.path("serviceAccount").asText());
            return Digest.sha256(Json.canonical(identity));
        } catch (Exception e) {
            throw unknown("LEGACY_BINDING: 算法身份或原始快照缺失");
        }
    }

    public static void validateBinding(Map<String, Object> b, Map<String, Object> contract) {
        try {
            var r = Json.read(b.get("request_snapshot").toString());
            var m = Json.read(b.get("metadata").toString());
            if (!"ALGORITHM".equals(m.path("protocol").asText())
                    || !Objects.equals(contract.get("sha256"), m.path("contractSha256").asText())
                    || !Objects.equals(
                            b.get("external_identity_sha256"), identityDigest(b, contract))
                    || r.path("attemptId").asLong() < 1
                    || r.path("nodeInstanceId").asLong() < 1
                    || r.path("attemptId").asLong() != ((Number) b.get("attempt_id")).longValue()
                    || r.path("callId").asLong() != ((Number) b.get("call_id")).longValue()
                    || r.path("skillVersionId").asLong() != number(contract, "skillVersionId")
                    || r.path("skillVersionId").asLong()
                            != ((Number) b.get("skill_version_id")).longValue()
                    || !r.path("runId").asText().equals(b.get("run_id"))
                    || !r.path("idempotencyKey").asText().equals(b.get("idempotency_key"))
                    || !r.path("arguments").isObject()
                    || !r.path("guardedArguments").isObject()
                    || !Objects.equals(
                            "alg-"
                                    + Digest.sha256(b.get("idempotency_key").toString())
                                            .substring(0, 40),
                            b.get("external_job_id"))
                    || m.path("image").asText().isBlank()
                    || m.path("serviceAccount").asText().isBlank())
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw unknown("LEGACY_BINDING_OR_IDENTITY_MISMATCH: 算法原代身份不完整或不一致");
        }
    }

    public Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            SkillAdapters outputs,
            String runId,
            long expectedAttempt) {
        return observe(contract, arguments, key, callId, queryOnly, outputs, runId, expectedAttempt)
                .raw();
    }

    public Observation observe(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            SkillAdapters outputs,
            String runId,
            long expectedAttempt) {
        requireAttempt(expectedAttempt);
        var b = binding(callId);
        validateBinding(b, contract);
        var s = settings(contract);
        if (((Number) b.get("attempt_id")).longValue() != expectedAttempt
                || !key.equals(b.get("idempotency_key"))
                || !runId.equals(b.get("run_id"))
                || !Digest.sha256(s.api).equals(b.get("external_api_sha256"))
                || !s.namespace.equals(b.get("external_namespace"))
                || !s.image.equals(Json.read(b.get("metadata").toString()).path("image").asText())
                || !s.account.equals(
                        Json.read(b.get("metadata").toString()).path("serviceAccount").asText())
                || !Json.canonical(arguments)
                        .equals(
                                Json.canonical(
                                        Json.read(b.get("request_snapshot").toString())
                                                .path("guardedArguments"))))
            throw unknown("算法原调用、参数、API或namespace与绑定不一致");
        if ("NOT_SENT".equals(b.get("status"))
                || queryOnly && !Boolean.TRUE.equals(b.get("send_started")))
            throw unknown("原算法未发送或已被恢复栅栏禁止发送");
        var frozenArguments =
                Json.read(
                        Json.read(b.get("request_snapshot").toString())
                                .path("guardedArguments")
                                .toString(),
                        Map.class);
        long timeout = ((Number) contract.get("timeoutMs")).longValue();
        if (timeout < 1 || timeout > 300000) throw unknown("算法时限无效");
        // SDK23 buildCall/handleResponse retain SDK URL/auth/JSON semantics. Its deserialize/error
        // paths materialize body.string(), so the OkHttp source is bounded BEFORE those paths.
        var client = new ApiClient().setBasePath(s.api).setVerifyingSsl(true);
        try {
            client.setApiKey(SecretVault.resolve(text(contract, "authRef")));
        } catch (PlatformException denied) {
            throw new Failure("NO_PERMISSION", false, "算法凭据不属于当前租户");
        } catch (RuntimeException unavailable) {
            throw unknown("算法凭据暂不可用；未开始外部查询或创建");
        }
        client.setApiKeyPrefix("Bearer");
        var transport =
                client.getHttpClient()
                        .newBuilder()
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .connectTimeout(Duration.ofMillis(timeout))
                        .readTimeout(Duration.ofMillis(timeout))
                        .writeTimeout(Duration.ofMillis(timeout))
                        .callTimeout(Duration.ofMillis(timeout))
                        // Application interceptor runs after BridgeInterceptor transparent gzip
                        // decoding;
                        // a network interceptor would cap compressed bytes and let decoded JSON
                        // escape.
                        .addInterceptor(
                                chain -> {
                                    var response = chain.proceed(chain.request());
                                    var body = response.body();
                                    if (body == null) return response;
                                    boolean log =
                                            chain.request().url().encodedPath().endsWith("/log");
                                    long limit = log ? logLimit : metadataLimit;
                                    if (body.contentLength() > limit) {
                                        response.close();
                                        throw new IOException("Algorithm response byte budget");
                                    }
                                    return response.newBuilder().body(bounded(body, limit)).build();
                                })
                        .build();
        client.setHttpClient(transport);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        try (var schemaDeadline = cn.agentplatform.common.schema.SchemaDeadline.until(deadline)) {
            var jobs = new BatchV1Api(client);
            String name = b.get("external_job_id").toString();
            V1Job job;
            try {
                job =
                        execute(
                                client,
                                jobs.readNamespacedJob(name, s.namespace).buildCall(null),
                                V1Job.class,
                                deadline);
            } catch (ApiException absent) {
                if (absent.getCode() != 404) {
                    if (absent.getCode() == 0 || absent.getCode() >= 500)
                        throw fenceUnsentReadonly(b, contract);
                    throw absent;
                }
                if (queryOnly || !startSend(b)) throw unknown("原Job未查得；发送已仲裁或结果未知，禁止重发");
                remaining(deadline); // A timed-out GET must never leave a delayed business POST.
                try {
                    job =
                            execute(
                                    client,
                                    jobs.createNamespacedJob(
                                                    s.namespace,
                                                    job(
                                                            contract,
                                                            frozenArguments,
                                                            key,
                                                            callId,
                                                            b,
                                                            s))
                                            .buildCall(null),
                                    V1Job.class,
                                    deadline);
                } catch (ApiException conflict) {
                    if (conflict.getCode() != 409) throw conflict;
                    job =
                            execute(
                                    client,
                                    jobs.readNamespacedJob(name, s.namespace).buildCall(null),
                                    V1Job.class,
                                    deadline);
                }
            } catch (IOException unavailable) {
                throw fenceUnsentReadonly(b, contract);
            }
            while (true) {
                remaining(deadline);
                b = bindUid(b, job, contract);
                remaining(deadline);
                var status = job.getStatus();
                boolean complete = false, failed = false;
                if (status != null && status.getConditions() != null)
                    for (var condition : status.getConditions())
                        if ("True".equals(condition.getStatus())) {
                            complete |= "Complete".equals(condition.getType());
                            failed |= "Failed".equals(condition.getType());
                        }
                if (complete && failed) throw unknown("Job存在矛盾终态，拒绝采纳");
                var evidence = evidence(b, failed ? "Failed" : "Complete");
                if (failed) throw new TerminalFailure(evidence);
                if (complete) {
                    var core = new CoreV1Api(client);
                    var pods =
                            execute(
                                    client,
                                    core.listNamespacedPod(s.namespace)
                                            .labelSelector("job-name=" + name)
                                            .limit(2)
                                            .watch(false)
                                            .timeoutSeconds(
                                                    (int)
                                                            Math.max(
                                                                    1,
                                                                    TimeUnit.NANOSECONDS.toSeconds(
                                                                            remaining(deadline))))
                                            .buildCall(null),
                                    V1PodList.class,
                                    deadline);
                    var pod = outputPod(pods, b);
                    var call =
                            core.readNamespacedPodLog(pod, s.namespace)
                                    .container("algorithm")
                                    .follow(false)
                                    .buildCall(null);
                    call.timeout().timeout(remaining(deadline), TimeUnit.NANOSECONDS);
                    try (var response = call.execute()) {
                        if (!response.isSuccessful() || response.body() == null)
                            throw unknown("算法完整stdout暂不可核实");
                        Raw raw;
                        try (var completeJson =
                                new CompleteJson(
                                        response.body().byteStream(), deadline, logLimit)) {
                            try {
                                raw =
                                        outputs.readAlgorithm(
                                                completeJson, contract, callId, runId, deadline);
                            } catch (Failure invalid) {
                                remaining(deadline);
                                if (!invalid.unknown)
                                    throw new TerminalFailure(
                                            invalid.semantic, invalid.getMessage(), evidence);
                                throw invalid;
                            } catch (IllegalStateException offload) {
                                remaining(deadline);
                                if (completeJson.eof
                                        && completeJson.depth == 0
                                        && completeJson.roots == 1
                                        && offload.getCause() instanceof IllegalArgumentException)
                                    throw new TerminalFailure(
                                            "OUTPUT_INVALID", "已完整取得算法输出；原始输出Schema不符", evidence);
                                throw offload;
                            }
                            completeJson.requireComplete();
                        }
                        remaining(deadline);
                        return new Observation(raw, evidence);
                    } finally {
                        call.cancel();
                    }
                }
                TimeUnit.NANOSECONDS.sleep(
                        Math.min(TimeUnit.MILLISECONDS.toNanos(25), remaining(deadline)));
                job =
                        execute(
                                client,
                                jobs.readNamespacedJob(name, s.namespace).buildCall(null),
                                V1Job.class,
                                deadline);
            }
        } catch (Failure e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unknown("K8s协议、字节或总时限未通过；保留原Job仅对账");
        } finally {
            transport.dispatcher().cancelAll();
            transport.connectionPool().evictAll();
            transport.dispatcher().executorService().shutdown();
        }
    }

    private boolean startSend(Map<String, Object> b) {
        return db.update(
                        "update skill.external_job_binding set"
                                + " send_started=true,status='SENDING',updated_at=now() where "
                                + fence()
                                + " and status='REGISTERED' and send_started=false",
                        fenceValues(b))
                == 1;
    }

    private Failure fenceUnsentReadonly(Map<String, Object> b, Map<String, Object> contract) {
        var policy = object(contract.getOrDefault("execution", Map.of()));
        if (Boolean.TRUE.equals(policy.get("readonly"))
                && !Boolean.TRUE.equals(policy.get("sideEffect"))
                && db.update(
                                "update skill.external_job_binding set"
                                        + " status='NOT_SENT',updated_at=now() where "
                                        + fence()
                                        + " and status='REGISTERED' and send_started=false and"
                                        + " external_job_uid is null",
                                fenceValues(b))
                        == 1)
            return new Failure("RETRYABLE_FAILURE", false, "首次算法查询暂不可用；完整PG发送栅栏已证明本代未开始创建");
        return unknown("首次算法查询暂不可用；未取得可靠未发送证明，禁止重发");
    }

    /**
     * Called only after new engine REGISTERED/claim/current guards, with original fact locked. The
     * old NOT_SENT image and new REGISTERED image change in one local transaction.
     */
    public void prepareRetry(
            Map<String, Object> contract,
            Map<String, Object> original,
            Map<String, Object> request) {
        var a = Actor.current();
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and"
                                + " principal_id=? and call_id=? for update",
                        a.tenantId(),
                        a.principalId(),
                        original.get("call_id"));
        if (rows.isEmpty()) throw unknown("只读重试缺少原绑定");
        var b = rows.getFirst();
        validateBinding(b, contract);
        var policy = object(contract.get("execution"));
        var oldRequest = Json.read(original.get("request_snapshot").toString());
        if (!Boolean.TRUE.equals(policy.get("readonly"))
                || Boolean.TRUE.equals(policy.get("sideEffect"))
                || !"FAILED".equals(original.get("status"))
                || !"RETRYABLE_FAILURE"
                        .equals(
                                Json.read(original.get("result").toString())
                                        .path("error")
                                        .path("semantic")
                                        .asText())
                || Boolean.TRUE.equals(original.get("is_mock"))
                || !Objects.equals(original.get("skill_contract_sha256"), contract.get("sha256"))
                || !Objects.equals(
                        original.get("args_digest"),
                        Digest.sha256(Json.canonical(oldRequest.path("arguments"))))
                || !Objects.equals(original.get("run_id"), b.get("run_id"))
                || !Objects.equals(original.get("principal_id"), a.principalId())
                || number(request, "callId") != ((Number) b.get("call_id")).longValue()
                || number(request, "nodeInstanceId") != oldRequest.path("nodeInstanceId").asLong()
                || !"NOT_SENT".equals(b.get("status"))
                || !Boolean.FALSE.equals(b.get("send_started"))
                || b.get("external_job_uid") != null
                || !Objects.equals(original.get("attempt_id"), b.get("attempt_id"))
                || !Objects.equals(original.get("skill_version_id"), b.get("skill_version_id"))
                || number(request, "attemptId") <= ((Number) b.get("attempt_id")).longValue()
                || number(request, "skillVersionId")
                        != ((Number) b.get("skill_version_id")).longValue()
                || !Objects.equals(text(request, "runId"), b.get("run_id"))
                || !Objects.equals(text(request, "idempotencyKey"), b.get("idempotency_key"))
                || !Json.canonical(request.get("arguments"))
                        .equals(Json.canonical(oldRequest.path("arguments")))
                || !Json.canonical(oldRequest)
                        .equals(Json.canonical(Json.read(b.get("request_snapshot").toString()))))
            throw unknown("只读新代仅允许可靠NOT_SENT的已知暂时失败");
        db.update(
                "insert into"
                    + " skill.external_job_binding_history(tenant_id,call_id,attempt_id,binding)"
                    + " select tenant_id,call_id,attempt_id,to_jsonb(b) from"
                    + " skill.external_job_binding b where tenant_id=? and call_id=? and"
                    + " attempt_id=?",
                a.tenantId(),
                b.get("call_id"),
                b.get("attempt_id"));
        var next = new LinkedHashMap<>(b);
        next.put("attempt_id", number(request, "attemptId"));
        next.put("request_snapshot", Json.write(request));
        String digest = identityDigest(next, contract);
        var values = new ArrayList<Object>();
        values.add(number(request, "attemptId"));
        values.add(Json.write(request));
        values.add(digest);
        values.addAll(Arrays.asList(fenceValues(b)));
        if (db.update(
                        "update skill.external_job_binding set"
                            + " attempt_id=?,request_snapshot=?::jsonb,external_identity_sha256=?,status='REGISTERED',send_started=false,external_job_uid=null,updated_at=now()"
                            + " where "
                                + fence()
                                + " and status='NOT_SENT' and send_started=false and"
                                + " external_job_uid is null",
                        values.toArray())
                != 1) throw unknown("只读未发送新代仲裁已失效");
        bind(contract, request); // recheck current API/account/image and the complete new snapshot
    }

    private Map<String, Object> bindUid(
            Map<String, Object> b, V1Job job, Map<String, Object> contract) {
        if (job == null
                || !"batch/v1".equals(job.getApiVersion())
                || !"Job".equals(job.getKind())
                || job.getMetadata() == null) throw unknown("Job缺少可核实身份");
        var m = job.getMetadata();
        String uid = m.getUid();
        if (!Objects.equals(b.get("external_namespace"), m.getNamespace())
                || !Objects.equals(b.get("external_job_id"), m.getName())
                || uid == null
                || uid.isBlank()
                || uid.length() > 256
                || m.getAnnotations() == null
                || !Objects.equals(
                        b.get("external_identity_sha256"), m.getAnnotations().get(IDENTITY))
                || b.get("external_job_uid") != null && !uid.equals(b.get("external_job_uid")))
            throw unknown("Job归属或UID与原任务不一致");
        var values = new ArrayList<Object>();
        values.add(uid);
        values.addAll(Arrays.asList(fenceValues(b)));
        values.add(uid);
        int changed =
                db.update(
                        "update skill.external_job_binding set"
                            + " external_job_uid=?,send_started=true,status=case when status='DONE'"
                            + " then status else 'SUBMITTED' end,updated_at=now() where "
                                + fence()
                                + " and status<>'NOT_SENT' and (external_job_uid is null or"
                                + " external_job_uid=?)",
                        values.toArray());
        if (changed != 1) throw unknown("原Job绑定或UID仲裁已改变");
        var resolved = binding(((Number) b.get("call_id")).longValue());
        validateBinding(resolved, contract);
        if (!uid.equals(resolved.get("external_job_uid"))
                || !Objects.equals(
                        b.get("external_identity_sha256"), resolved.get("external_identity_sha256"))
                || !Json.canonical(Json.read(b.get("request_snapshot").toString()))
                        .equals(
                                Json.canonical(
                                        Json.read(resolved.get("request_snapshot").toString()))))
            throw unknown("Job UID或原代绑定已被替换");
        return resolved;
    }

    private static Map<String, Object> evidence(Map<String, Object> b, String condition) {
        var source =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "externalJobId",
                                b.get("external_job_id"),
                                "externalJobUid",
                                b.get("external_job_uid"),
                                "identitySha256",
                                b.get("external_identity_sha256"),
                                "apiSha256",
                                b.get("external_api_sha256"),
                                "namespace",
                                b.get("external_namespace"),
                                "condition",
                                condition,
                                "sourceKind",
                                "K8S_JOB"));
        source.put("sourceSha256", Digest.sha256(Json.canonical(source)));
        return Map.copyOf(source);
    }

    /**
     * Caller already holds original fact lock; keep fact -> binding lock order, no network here.
     */
    public void complete(
            Map<String, Object> fact, Map<String, Object> contract, Map<String, Object> source) {
        var a = Actor.current();
        var rows =
                db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and"
                                + " principal_id=? and call_id=? for update",
                        a.tenantId(),
                        a.principalId(),
                        fact.get("call_id"));
        if (rows.isEmpty()) throw unknown("原算法绑定已失效");
        var b = rows.getFirst();
        validateBinding(b, contract);
        var r = Json.read(fact.get("request_snapshot").toString());
        if (!Set.of("Complete", "Failed").contains(Objects.toString(source.get("condition")))
                || !Json.canonical(source)
                        .equals(Json.canonical(evidence(b, source.get("condition").toString())))
                || !Objects.equals(fact.get("attempt_id"), b.get("attempt_id"))
                || !Objects.equals(fact.get("skill_version_id"), b.get("skill_version_id"))
                || !Objects.equals(fact.get("run_id"), b.get("run_id"))
                || !Objects.equals(fact.get("principal_id"), b.get("principal_id"))
                || !Objects.equals(fact.get("skill_contract_sha256"), contract.get("sha256"))
                || !Objects.equals(
                        fact.get("args_digest"), Digest.sha256(Json.canonical(r.path("arguments"))))
                || !Json.canonical(r)
                        .equals(Json.canonical(Json.read(b.get("request_snapshot").toString())))
                || !Boolean.TRUE.equals(b.get("send_started"))) throw unknown("终态观察与原代事实或绑定不一致");
        var terminal = new ArrayList<Object>();
        terminal.add(Json.write(source));
        terminal.addAll(Arrays.asList(fenceValues(b)));
        terminal.add(b.get("external_job_uid"));
        if (db.update(
                        "update skill.external_job_binding set"
                            + " status='DONE',metadata=jsonb_set(metadata,'{terminalEvidence}',?::jsonb),updated_at=now()"
                            + " where "
                                + fence()
                                + " and external_job_uid=? and send_started=true",
                        terminal.toArray())
                != 1) throw unknown("终态绑定CAS失效");
    }

    private static String fence() {
        return "tenant_id=? and principal_id=? and call_id=? and run_id=? and attempt_id=? and"
                + " skill_version_id=? and idempotency_key=? and external_job_id=? and"
                + " external_namespace=? and external_api_sha256=? and"
                + " external_identity_sha256=? and request_snapshot=?::jsonb and"
                + " metadata=?::jsonb";
    }

    private static Object[] fenceValues(Map<String, Object> b) {
        return new Object[] {
            b.get("tenant_id"),
            b.get("principal_id"),
            b.get("call_id"),
            b.get("run_id"),
            b.get("attempt_id"),
            b.get("skill_version_id"),
            b.get("idempotency_key"),
            b.get("external_job_id"),
            b.get("external_namespace"),
            b.get("external_api_sha256"),
            b.get("external_identity_sha256"),
            b.get("request_snapshot").toString(),
            b.get("metadata").toString()
        };
    }

    private static String outputPod(V1PodList pods, Map<String, Object> b) {
        if (pods == null
                || pods.getMetadata() != null
                        && pods.getMetadata().getContinue() != null
                        && !pods.getMetadata().getContinue().isBlank()
                || pods.getItems() == null
                || pods.getItems().size() != 1) throw unknown("Pod列表缺失、分页或候选不唯一");
        var p = pods.getItems().getFirst();
        var m = p.getMetadata();
        if (m == null
                || !Objects.equals(b.get("external_namespace"), m.getNamespace())
                || m.getUid() == null
                || m.getUid().isBlank()
                || m.getName() == null
                || m.getOwnerReferences() == null
                || m.getOwnerReferences().stream()
                                .filter(o -> Boolean.TRUE.equals(o.getController()))
                                .count()
                        != 1
                || m.getOwnerReferences().stream()
                        .noneMatch(
                                o ->
                                        Boolean.TRUE.equals(o.getController())
                                                && "Job".equals(o.getKind())
                                                && "batch/v1".equals(o.getApiVersion())
                                                && Objects.equals(
                                                        b.get("external_job_id"), o.getName())
                                                && Objects.equals(
                                                        b.get("external_job_uid"), o.getUid())))
            throw unknown("Pod controller owner不属于原Job UID");
        if (p.getStatus() == null || p.getStatus().getContainerStatuses() == null)
            throw unknown("算法容器终态缺失");
        var containers =
                p.getStatus().getContainerStatuses().stream()
                        .filter(c -> "algorithm".equals(c.getName()))
                        .toList();
        if (containers.size() != 1
                || containers.getFirst().getState() == null
                || containers.getFirst().getState().getTerminated() == null
                || !Integer.valueOf(0)
                        .equals(containers.getFirst().getState().getTerminated().getExitCode()))
            throw unknown("算法容器未核实成功退出");
        return m.getName();
    }

    private static V1Job job(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long call,
            Map<String, Object> b,
            Settings s) {
        var container =
                new V1Container()
                        .name("algorithm")
                        .image(s.image)
                        .env(
                                List.of(
                                        new V1EnvVar()
                                                .name("AGENT_INPUTS_JSON")
                                                .value(Json.write(arguments)),
                                        new V1EnvVar().name("AGENT_IDEMPOTENCY_KEY").value(key)))
                        .resources(
                                new V1ResourceRequirements()
                                        .requests(
                                                Map.of(
                                                        "cpu",
                                                        new Quantity("100m"),
                                                        "memory",
                                                        new Quantity("128Mi")))
                                        .limits(
                                                Map.of(
                                                        "cpu",
                                                        new Quantity("1"),
                                                        "memory",
                                                        new Quantity("512Mi"))))
                        .securityContext(
                                new V1SecurityContext()
                                        .allowPrivilegeEscalation(false)
                                        .runAsNonRoot(true)
                                        .capabilities(new V1Capabilities().drop(List.of("ALL"))));
        if (object(contract.get("jobTemplate")).get("command") instanceof List<?> command)
            container.command(command.stream().map(Object::toString).toList());
        return new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                        new V1ObjectMeta()
                                .namespace(s.namespace)
                                .name(b.get("external_job_id").toString())
                                .labels(Map.of("agent-platform-call", Long.toString(call)))
                                .annotations(
                                        Map.of(
                                                IDENTITY,
                                                b.get("external_identity_sha256").toString())))
                .spec(
                        new V1JobSpec()
                                .completions(1)
                                .parallelism(1)
                                .backoffLimit(0)
                                .activeDeadlineSeconds(
                                        Math.max(
                                                1,
                                                ((Number) contract.get("timeoutMs")).longValue()
                                                        / 1000))
                                .template(
                                        new V1PodTemplateSpec()
                                                .metadata(new V1ObjectMeta())
                                                .spec(
                                                        new V1PodSpec()
                                                                .containers(List.of(container))
                                                                .restartPolicy("Never")
                                                                .serviceAccountName(s.account)
                                                                .automountServiceAccountToken(
                                                                        false))));
    }

    private static <T> T execute(ApiClient client, Call call, Class<T> type, long deadline)
            throws Exception {
        call.timeout().timeout(remaining(deadline), TimeUnit.NANOSECONDS);
        try (var response = call.execute()) {
            return client.handleResponse(response, type);
        } finally {
            call.cancel();
        }
    }

    private static long remaining(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw unknown("算法本地查询已中断；未证明外部停止");
        long n = deadline - System.nanoTime();
        if (n <= 0) throw unknown("算法统一总时限已到，保留原Job对账");
        return n;
    }

    static LimitedBody bounded(ResponseBody body, long limit) {
        return new LimitedBody(body, limit);
    }

    static final class LimitedBody extends ResponseBody {
        private final ResponseBody delegate;
        private final BufferedSource source;
        long consumed;

        LimitedBody(ResponseBody delegate, long limit) {
            this.delegate = delegate;
            // The SDK sees at most limit+1 bytes. OkHttp's existing fixed transport buffer may
            // prefetch separately; this is an application byte budget, not a TCP receive quota.
            source =
                    Okio.buffer(
                            new ForwardingSource(delegate.source()) {
                                @Override
                                public long read(Buffer sink, long count) throws IOException {
                                    if (count == 0) return 0;
                                    long n =
                                            super.read(sink, Math.min(count, limit - consumed + 1));
                                    if (n > 0) {
                                        consumed += n;
                                        if (consumed > limit) {
                                            delegate.close();
                                            throw new IOException("Algorithm response byte budget");
                                        }
                                    }
                                    return n;
                                }
                            });
        }

        public MediaType contentType() {
            return delegate.contentType();
        }

        public long contentLength() {
            return delegate.contentLength();
        }

        public BufferedSource source() {
            return source;
        }
    }

    /**
     * Jackson's nonblocking parser validates exactly one whole JSON document while downstream
     * consumes the original byte stream. No tree, temporary file, worker or unbounded tee is used;
     * offload cannot expose READY before EOF has passed this same validation.
     */
    private static final class CompleteJson extends FilterInputStream {
        final JsonParser parser;
        final ByteArrayFeeder feeder;
        final long deadline;
        int depth, roots;
        boolean eof;

        CompleteJson(InputStream input, long deadline, long limit) throws IOException {
            super(input);
            this.deadline = deadline;
            parser =
                    JsonFactory.builder()
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxDocumentLength(limit)
                                            .maxTokenCount(250000)
                                            .maxNestingDepth(100)
                                            .maxStringLength(8 * 1024 * 1024)
                                            .maxNameLength(1024)
                                            .maxNumberLength(1000)
                                            .build())
                            .build()
                            .createNonBlockingByteArrayParser();
            feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            remaining(deadline);
            int n = in.read(bytes, offset, length);
            if (n > 0) {
                feeder.feedInput(bytes, offset, offset + n);
                tokens();
            } else if (n < 0 && !eof) {
                eof = true;
                feeder.endOfInput();
                tokens();
                requireComplete();
            }
            return n;
        }

        void tokens() throws IOException {
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++;
                else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                    if (depth == 0) roots++;
                } else if (token.isScalarValue() && depth == 0) roots++;
                if (roots > 1)
                    throw new IOException("Algorithm stdout contains multiple JSON documents");
            }
        }

        void requireComplete() throws IOException {
            if (!eof || depth != 0 || roots != 1)
                throw new IOException("Algorithm stdout is not one complete JSON document");
        }

        @Override
        public void close() throws IOException {
            try {
                parser.close();
            } finally {
                super.close();
            }
        }
    }

    private static Failure unknown(String reason) {
        return new Failure("RESULT_UNKNOWN", true, reason);
    }
}
