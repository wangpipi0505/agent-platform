package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.Digest;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.databind.JsonNode;

import io.minio.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.security.*;
import java.util.*;

/** 原始响应越过上下文硬闸后直接流式上传；绝不把超限响应转成无界String。 */
@Component
public class ArtifactStore implements AutoCloseable {
    public static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    private static final com.fasterxml.jackson.databind.ObjectMapper BOUNDED_JSON = boundedMapper();

    private static com.fasterxml.jackson.databind.ObjectMapper boundedMapper() {
        var mapper = Json.mapper().copy();
        mapper.getFactory()
                .setStreamReadConstraints(
                        com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                .maxDocumentLength(MAX_ARTIFACT_BYTES)
                                .maxTokenCount(250000)
                                .maxNestingDepth(100)
                                .maxStringLength(8 * 1024 * 1024)
                                .maxNameLength(1024)
                                .maxNumberLength(1000)
                                .build());
        return mapper;
    }

    public static JsonNode readJson(InputStream source) throws IOException {
        return BOUNDED_JSON.readTree(source);
    }

    final Map<String, String> configuration;
    private final JdbcTemplate db;
    private final okhttp3.OkHttpClient transport;
    private final long algorithmDeadline;
    private final AlgorithmIo algorithmIo;
    private MinioClient minio;
    private MultipartApi asyncMinio;

    @Autowired
    public ArtifactStore(JdbcTemplate db) {
        this(db, System.getenv());
    }

    public ArtifactStore() {
        this(null, System.getenv());
    }

    ArtifactStore(Map<String, String> configuration) {
        this(null, configuration);
    }

    public ArtifactStore(JdbcTemplate db, Map<String, String> configuration) {
        this(db, configuration, 0);
    }

    private ArtifactStore(
            JdbcTemplate db, Map<String, String> configuration, long algorithmDeadline) {
        this.db = db;
        this.configuration = Map.copyOf(configuration);
        this.algorithmDeadline = algorithmDeadline;
        this.algorithmIo = algorithmDeadline == 0 ? null : new AlgorithmIo();
        var builder =
                new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(java.time.Duration.ofSeconds(3))
                        .readTimeout(java.time.Duration.ofSeconds(30))
                        .writeTimeout(java.time.Duration.ofSeconds(30))
                        .callTimeout(java.time.Duration.ofSeconds(45));
        if (algorithmDeadline != 0) {
            // MinIO 8.5.17 S3Base.executeAsync uses enqueue for every request, including its
            // retry-disabled PUT/POST child builders. OkHttp 4.12 callStart runs BEFORE the async
            // timeout enters, and newBuilder preserves this listener. Thus one absolute nanoTime
            // deadline cancels actual current I/O across region lookup/upload/parts/readback;
            // resetting a relative timeout at each HTTP request would not establish that bound.
            builder.retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .eventListener(algorithmIo);
        }
        transport = builder.build();
    }

    /** Algorithm owns this fresh SDK client/pool/dispatcher; the shared store is never closed. */
    public ArtifactStore forAlgorithmDeadline(long deadline) {
        if (deadline - System.nanoTime() <= 0) throw new IllegalStateException("算法转存总时限已到");
        return new ArtifactStore(db, configuration, deadline);
    }

    // Package-visible lifecycle assertion: actual original futures + Call end + owned executor
    // exit.
    boolean algorithmIoQuiescent() {
        return algorithmIo != null
                && algorithmIo.settled()
                && transport.dispatcher().executorService().isTerminated();
    }

    boolean algorithmIoRetained() {
        return algorithmIo != null && algorithmIo.stopping && !algorithmIo.settled();
    }

    private final class AlgorithmIo extends okhttp3.EventListener {
        final Set<okhttp3.Call> calls = new HashSet<>();
        int stages;
        long cancelUntil;
        volatile boolean stopping, interrupted, stopNotConfirmed;
        volatile InputStream source;

        @Override
        public synchronized void callStart(okhttp3.Call call) {
            calls.add(call);
            call.timeout().deadlineNanoTime(algorithmDeadline);
            if (stopping || algorithmDeadline - System.nanoTime() <= 0) call.cancel();
        }

        @Override
        public void callEnd(okhttp3.Call call) {
            finished(call);
        }

        @Override
        public void callFailed(okhttp3.Call call, IOException ex) {
            finished(call);
        }

        private synchronized void finished(okhttp3.Call call) {
            calls.remove(call);
            disposeIfSettled();
            notifyAll();
        }

        synchronized boolean settled() {
            return stages == 0 && calls.isEmpty();
        }

        private void disposeIfSettled() {
            if (stopping && settled()) {
                transport.connectionPool().evictAll();
                transport.dispatcher().executorService().shutdown();
            }
        }

        <T> T await(java.util.concurrent.CompletableFuture<T> original) throws Exception {
            synchronized (this) {
                stages++;
            }
            var responseClosed = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<T> closeOnce =
                    value -> {
                        if (value instanceof AutoCloseable response
                                && responseClosed.compareAndSet(false, true))
                            try {
                                response.close();
                            } catch (Exception ignored) {
                            }
                    };
            // Do not cancel this future: cancellation would mark only the wrapper done, without
            // joining PartReader/HTTP chains. Its real completion retains lifecycle ownership.
            original.whenComplete(
                    (value, error) -> {
                        try {
                            if (stopping) closeOnce.accept(value);
                        } finally {
                            synchronized (this) {
                                stages--;
                                disposeIfSettled();
                                notifyAll();
                            }
                        }
                    });
            try {
                T value = original.get();
                try {
                    checkActive();
                    return value;
                } catch (Exception stopped) {
                    closeOnce.accept(value);
                    throw stopped;
                }
            } catch (InterruptedException ex) {
                interrupted = true;
                // 中断后原future仍可能成功交回响应，早于stopping也必须接管；三条关闭路径共用
                // 一次所有权，Call真正结束和原执行器退出仍由既有生命周期确认。
                original.whenComplete((value, error) -> closeOnce.accept(value));
                throw ex;
            } catch (java.util.concurrent.ExecutionException ex) {
                if (ex.getCause() instanceof Exception cause) throw cause;
                throw ex;
            }
        }

        private IllegalStateException unconfirmed() {
            stopNotConfirmed = true;
            return new IllegalStateException("ALGORITHM_IO_STOP_NOT_CONFIRMED");
        }

        void checkActive() throws InterruptedException, java.net.SocketTimeoutException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (algorithmDeadline - System.nanoTime() <= 0)
                throw new java.net.SocketTimeoutException("algorithm deadline");
        }

        void stopAndAwait() {
            interrupted |= Thread.interrupted();
            synchronized (this) {
                if (!stopping)
                    cancelUntil =
                            System.nanoTime()
                                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
                stopping = true;
            }
            // SDK PartReader owns this input. Closing it and cancelling actual HTTP unblocks its
            // finite stages. New calls triggered by completion chains are also immediately fenced.
            synchronized (this) {
                for (var call : List.copyOf(calls)) call.cancel();
                disposeIfSettled();
            }
            var input = source;
            if (input != null)
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            long until = cancelUntil;
            synchronized (this) {
                while (!settled() && until - System.nanoTime() > 0) {
                    try {
                        java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(
                                this, until - System.nanoTime());
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (!settled()) throw unconfirmed();
            }
            // Future completion can precede OkHttp's final dispatcher bookkeeping; join that own
            // executor too. If a platform cannot stop within the finite drain, callbacks still own
            // this lifecycle and dispose only after real completion; never label it released.
            while (!transport.dispatcher().executorService().isTerminated()) {
                long left = until - System.nanoTime();
                if (left <= 0) throw unconfirmed();
                try {
                    transport
                            .dispatcher()
                            .executorService()
                            .awaitTermination(left, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        }
    }

    private synchronized MinioClient client() {
        if (minio != null) return minio;
        String endpoint = configuration.get("AGENT_MINIO_ENDPOINT"),
                access = configuration.get("AGENT_MINIO_ACCESS_KEY"),
                secret = configuration.get("AGENT_MINIO_SECRET_KEY"),
                bucket = configuration.get("AGENT_MINIO_BUCKET");
        if (endpoint == null || access == null || secret == null || bucket == null)
            throw new IllegalStateException("OUTPUT_ARTIFACT_UNAVAILABLE: 必须配置真实MinIO");
        return minio =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(access, secret)
                        .httpClient(transport)
                        .build();
    }

    private synchronized MultipartApi asyncClient() {
        if (asyncMinio == null) {
            client();
            asyncMinio =
                    new MultipartApi(
                            MinioAsyncClient.builder()
                                    .endpoint(configuration.get("AGENT_MINIO_ENDPOINT"))
                                    .credentials(
                                            configuration.get("AGENT_MINIO_ACCESS_KEY"),
                                            configuration.get("AGENT_MINIO_SECRET_KEY"))
                                    .httpClient(transport)
                                    .build());
        }
        return asyncMinio;
    }

    private static final class MultipartApi extends MinioAsyncClient {
        MultipartApi(MinioAsyncClient client) {
            super(client);
        }

        okhttp3.Response listExact(String bucket, String key) throws Exception {
            var query = com.google.common.collect.HashMultimap.<String, String>create();
            query.put("uploads", "");
            query.put("prefix", key);
            query.put("max-uploads", "8");
            return executeAsync(
                            io.minio.http.Method.GET,
                            bucket,
                            null,
                            getRegionAsync(bucket, null)
                                    .get(45, java.util.concurrent.TimeUnit.SECONDS),
                            null,
                            query,
                            null,
                            0)
                    .get(45, java.util.concurrent.TimeUnit.SECONDS);
        }

        java.util.concurrent.CompletableFuture<okhttp3.Response> listExactAsync(
                String bucket, String key) throws Exception {
            var query = com.google.common.collect.HashMultimap.<String, String>create();
            query.put("uploads", "");
            query.put("prefix", key);
            query.put("max-uploads", "8");
            return getRegionAsync(bucket, null)
                    .thenCompose(
                            region -> {
                                try {
                                    return executeAsync(
                                            io.minio.http.Method.GET,
                                            bucket,
                                            null,
                                            region,
                                            null,
                                            query,
                                            null,
                                            0);
                                } catch (Exception ex) {
                                    return java.util.concurrent.CompletableFuture.failedFuture(ex);
                                }
                            });
        }
    }

    @jakarta.annotation.PreDestroy
    @Override
    public void close() {
        if (algorithmIo != null) {
            boolean interrupted = Thread.interrupted();
            try {
                algorithmIo.stopAndAwait();
            } finally {
                if (interrupted || algorithmIo.interrupted) Thread.currentThread().interrupt();
            }
        } else {
            transport.connectionPool().evictAll();
            transport.dispatcher().executorService().shutdown();
        }
    }

    /** 无已核实run的探活不能制造脱离归属链的产物。 */
    public Map<String, Object> offload(
            byte[] prefix, InputStream remainder, Object outputSchema, long callId) {
        client();
        throw new IllegalStateException("OUTPUT_ARTIFACT_UNAVAILABLE: 产物必须绑定已核实的运行");
    }

    public Map<String, Object> offload(
            byte[] prefix, InputStream remainder, Object outputSchema, long callId, String runId) {
        var client = client();
        var actor = Actor.current();
        if (db == null || callId <= 0 || runId == null || !runId.matches("r_[A-Za-z0-9_-]{1,125}"))
            throw new IllegalStateException("OUTPUT_ARTIFACT_UNAVAILABLE: 产物必须绑定已核实的运行");
        String id = "art_" + UUID.randomUUID().toString().replace("-", "");
        String bucket = configuration.get("AGENT_MINIO_BUCKET");
        String key = Digest.sha256(actor.tenantId()) + "/artifacts/" + id + ".json";
        // 先提交精确对象登记，再进行非事务性对象上传。进程崩溃后只清理这些登记键，不扫描桶。
        db.update(
                "insert into"
                    + " skill.artifact(artifact_id,tenant_id,principal_id,run_id,call_id,bucket,object_key,state,content_type,expires_at,cleanup_after)"
                    + " values(?,?,?,?,?,?,?,'UPLOADING','application/json',now()+interval '7"
                    + " days',now()+interval '1 hour')",
                id,
                actor.tenantId(),
                actor.principalId(),
                runId,
                callId,
                bucket,
                key);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bounded =
                    new BoundedStream(
                            new SequenceInputStream(new ByteArrayInputStream(prefix), remainder),
                            MAX_ARTIFACT_BYTES);
            try (var source = new DigestInputStream(bounded, digest)) {
                if (algorithmIo != null) algorithmIo.source = source;
                var put =
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(key)
                                .contentType("application/json")
                                .stream(source, -1, 5 * 1024 * 1024)
                                .build();
                if (algorithmIo == null) client.putObject(put);
                else algorithmIo.await(asyncClient().putObject(put));
            }
            // 从已上传对象验证原始输出，而不是对摘要对象冒充原始Schema验收；结构树有64MiB明确上限。
            try (var stored =
                            algorithmIo == null
                                    ? client.getObject(
                                            GetObjectArgs.builder()
                                                    .bucket(bucket)
                                                    .object(key)
                                                    .build())
                                    : algorithmIo.await(
                                            asyncClient()
                                                    .getObject(
                                                            GetObjectArgs.builder()
                                                                    .bucket(bucket)
                                                                    .object(key)
                                                                    .build()));
                    var checked = new BoundedStream(stored, MAX_ARTIFACT_BYTES)) {
                JsonNode value = readJson(checked);
                SkillRules.validate(outputSchema, Json.read(value.toString(), Object.class));
            }
            if (algorithmIo != null) algorithmIo.checkActive();
            int changed =
                    db.update(
                            "update skill.artifact set"
                                    + " state='READY',sha256=?,size_bytes=?,updated_at=now() where"
                                    + " tenant_id=? and artifact_id=? and state='UPLOADING'",
                            HexFormat.of().formatHex(digest.digest()),
                            bounded.count,
                            actor.tenantId(),
                            id);
            if (changed != 1) throw new IllegalStateException("ARTIFACT_STATE_CHANGED");
            return reference(owned(id, runId));
        } catch (Exception ex) {
            if (algorithmIo != null) {
                // A cancelled local upload is not remote rollback. Stop/join our original SDK work
                // before persisting uncertainty; never DELETE while an accepted write may finish.
                try {
                    algorithmIo.stopAndAwait();
                } catch (IllegalStateException notStopped) {
                    ex.addSuppressed(notStopped);
                }
            }
            // Schema/字节/连接/登记失败均不公开引用；清理失败持久保留，由有界恢复任务重试同一键。
            try {
                db.update(
                        "update skill.artifact set"
                                + " state='CLEANUP_PENDING',last_error_code=?,cleanup_after="
                                + (algorithmIo == null ? "now()" : "now()+interval '1 hour'")
                                + ",updated_at=now() where tenant_id=? and artifact_id=?",
                        algorithmIo != null && algorithmIo.stopNotConfirmed
                                ? "ALGORITHM_IO_STOP_NOT_CONFIRMED"
                                : "OFFLOAD_FAILED",
                        actor.tenantId(),
                        id);
                if (algorithmIo == null) deleteObject(actor.tenantId(), id, bucket, key);
            } catch (Exception cleanupFailure) {
                /* UPLOADING登记仍可在恢复窗口到期后清理。 */
            }
            throw new IllegalStateException("OUTPUT_ARTIFACT_UNAVAILABLE: 大结果转存或原始Schema验证失败", ex);
        } finally {
            if (algorithmIo != null && algorithmIo.interrupted) Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> owned(String id, String runId) {
        var actor = Actor.current();
        if (id == null || !id.matches("art_[a-f0-9]{32}") || db == null) throw denied();
        var rows =
                db.queryForList(
                        "select * from skill.artifact where artifact_id=? and tenant_id=? and"
                                + " principal_id=? and state='READY' and expires_at>now()",
                        id,
                        actor.tenantId(),
                        actor.principalId());
        if (rows.isEmpty() || runId != null && !runId.equals(rows.getFirst().get("run_id")))
            throw denied();
        return rows.getFirst();
    }

    private PlatformException denied() {
        return new PlatformException(404, "ARTIFACT_NOT_FOUND", "产物不存在、已过期或无权访问");
    }

    private Map<String, Object> reference(Map<String, Object> row) {
        String id = row.get("artifact_id").toString(), run = row.get("run_id").toString();
        return Map.of(
                "artifactId",
                id,
                "downloadUrl",
                "/admin/artifacts/" + id + "/download",
                "externalDownloadUrl",
                "/v1/runs/" + run + "/artifacts/" + id + "/download",
                "sha256",
                row.get("sha256"),
                "bytes",
                row.get("size_bytes"),
                "contentType",
                row.get("content_type"),
                "summary",
                "结果已转存，请使用受控下载查看");
    }

    /**
     * 上传的是调用输入，不是某次运行的结果。使用独立的 r_upload_* 归属域，避免把输入伪装成执行产物；
     * download/cleanup 仍复用同一张表，保证租户、principal、有效期和物理键只有一条权威记录。
     */
    public Map<String, Object> upload(String contentType, long size, InputStream source) {
        var client = client();
        var actor = Actor.current();
        if (db == null) throw new IllegalStateException("ARTIFACT_UPLOAD_UNAVAILABLE");
        if (size < 0 || size > MAX_ARTIFACT_BYTES)
            throw new PlatformException(413, "ARTIFACT_TOO_LARGE", "文件超过 64MB 上限");
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        if (type.length() > 128)
            throw new PlatformException(400, "ARTIFACT_CONTENT_TYPE_INVALID", "文件类型过长");
        String id = "art_" + UUID.randomUUID().toString().replace("-", "");
        String runId = "r_upload_" + UUID.randomUUID().toString().replace("-", "");
        String bucket = configuration.get("AGENT_MINIO_BUCKET");
        String key = Digest.sha256(actor.tenantId()) + "/artifacts/" + id + ".bin";
        db.update(
                "insert into"
                    + " skill.artifact(artifact_id,tenant_id,principal_id,run_id,call_id,bucket,object_key,state,content_type,expires_at,cleanup_after)"
                    + " values(?,?,?,?,?,?,?,'UPLOADING',?,now()+interval '7"
                    + " days',now()+interval '7 days')",
                id,
                actor.tenantId(),
                actor.principalId(),
                runId,
                1L,
                bucket,
                key,
                type);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bounded = new BoundedStream(source, MAX_ARTIFACT_BYTES);
            try (var checked = new DigestInputStream(bounded, digest)) {
                client.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucket)
                                .object(key)
                                .contentType(type)
                                .stream(checked, size, 5 * 1024 * 1024)
                                .build());
                // SDK 可能预读或在声明长度处停止；READY 前必须确认完整长度，不能发布截断文件。
                // 尾探测只读源的一个字节且不再上传；64MiB 正确 EOF 不消耗额外额度，额外字节一律拒绝。
                if (bounded.count != size || source.read() != -1)
                    throw new PlatformException(400, "ARTIFACT_SIZE_MISMATCH", "文件实际字节数与声明不一致");
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            int changed =
                    db.update(
                            "update skill.artifact set state='READY',sha256=?,size_bytes=?,updated_at=now()"
                                    + " where tenant_id=? and artifact_id=? and state='UPLOADING'",
                            sha,
                            bounded.count,
                            actor.tenantId(),
                            id);
            if (changed != 1) throw new IllegalStateException("ARTIFACT_STATE_CHANGED");
            return Map.of(
                    "artifactId", id,
                    "downloadUrl", "/admin/artifacts/" + id + "/download",
                    "sha256", sha,
                    "bytes", bounded.count,
                    "contentType", type);
        } catch (Exception ex) {
            try {
                db.update(
                        "update skill.artifact set state='CLEANUP_PENDING',last_error_code='UPLOAD_FAILED',"
                                + "cleanup_after=now(),updated_at=now() where tenant_id=? and artifact_id=?",
                        actor.tenantId(),
                        id);
                deleteObject(actor.tenantId(), id, bucket, key);
            } catch (Exception cleanupFailure) {
                // 清理失败保留 CLEANUP_PENDING，由有界恢复任务重试同一键。
            }
            if (ex instanceof PlatformException platform) throw platform;
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "文件暂不可上传，请稍后重试");
        }
    }

    /**
     * Binary PROCESS result, using the run/call/attempt already authorized by SkillExecution.
     * It never enters the independent r_upload/call1 input namespace. Cross-service result
     * acceptance remains the engine's fence; this method does not invent a new authority.
     */
    Map<String, Object> storeProcessOutput(
            String contentType, long size, String expectedSha256, InputStream source,
            String runId, long callId, long attempt) {
        var actor = Actor.current();
        if (db == null) throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "输出存储未装配");
        if (runId == null || !runId.matches("r_[A-Za-z0-9_-]{1,125}") || callId < 1 || attempt < 1
                || size < 0 || size > MAX_ARTIFACT_BYTES || contentType == null
                || contentType.isBlank() || contentType.length() > 128 || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}"))
            throw new PlatformException(502, "PROCESS_OUTPUT_INVALID", "输出文件缺少有效执行归属或声明");
        String id = "art_" + UUID.randomUUID().toString().replace("-", "");
        String bucket = configuration.get("AGENT_MINIO_BUCKET");
        String key = processOutputPrefix(actor.tenantId(), runId, callId, attempt) + id + ".bin";
        boolean registered = false;
        try (InputStream ownedSource = source) {
            if (algorithmIo != null) algorithmIo.checkActive();
            var client = algorithmIo == null ? client() : null;
            db.update(
                    "insert into skill.artifact"
                            + "(artifact_id,tenant_id,principal_id,run_id,call_id,bucket,object_key,state,"
                            + "content_type,expires_at,cleanup_after)"
                            + " values(?,?,?,?,?,?,?,'UPLOADING',?,now()+interval '7 days',now()+interval '1 hour')",
                    id, actor.tenantId(), actor.principalId(), runId, callId, bucket, key, contentType);
            registered = true;
            var digest = MessageDigest.getInstance("SHA-256");
            var bounded = new BoundedStream(ownedSource, MAX_ARTIFACT_BYTES);
            var checked = new DigestInputStream(bounded, digest);
            if (algorithmIo != null) algorithmIo.source = checked;
            var put =
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .contentType(contentType)
                            .stream(checked, size, 5 * 1024 * 1024)
                            .build();
            if (algorithmIo == null) client.putObject(put);
            else algorithmIo.await(asyncClient().putObject(put));
            if (bounded.count != size || ownedSource.read() != -1
                    || !expectedSha256.equals(HexFormat.of().formatHex(digest.digest())))
                throw new PlatformException(502, "PROCESS_OUTPUT_INVALID", "输出文件在转存时长度或摘要不一致");
            if (algorithmIo != null) algorithmIo.checkActive();
            int changed = db.update(
                    "update skill.artifact set state='READY',sha256=?,size_bytes=?,updated_at=now()"
                            + " where tenant_id=? and principal_id=? and artifact_id=? and run_id=?"
                            + " and call_id=? and object_key=? and state='UPLOADING'",
                    expectedSha256, bounded.count, actor.tenantId(), actor.principalId(),
                    id, runId, callId, key);
            if (changed != 1) throw new IllegalStateException("ARTIFACT_STATE_CHANGED");
            return reference(owned(id, runId));
        } catch (Exception failure) {
            if (algorithmIo != null) {
                try {
                    algorithmIo.stopAndAwait();
                } catch (IllegalStateException notStopped) {
                    failure.addSuppressed(notStopped);
                }
            }
            if (registered) {
                try {
                    db.update(
                            "update skill.artifact set state='CLEANUP_PENDING',last_error_code=?,"
                                    + "cleanup_after="
                                    + (algorithmIo == null ? "now()" : "now()+interval '1 hour'")
                                    + ",updated_at=now() where tenant_id=? and principal_id=?"
                                    + " and artifact_id=? and run_id=? and call_id=? and object_key=?",
                            algorithmIo != null && algorithmIo.stopNotConfirmed
                                    ? "ALGORITHM_IO_STOP_NOT_CONFIRMED"
                                    : "PROCESS_OUTPUT_FAILED",
                            actor.tenantId(), actor.principalId(), id, runId, callId, key);
                    if (algorithmIo == null) deleteObject(actor.tenantId(), id, bucket, key);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof PlatformException platform) throw platform;
            var unavailable = new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "输出文件暂不可保存");
            unavailable.addSuppressed(failure);
            throw unavailable;
        } finally {
            if (algorithmIo != null && algorithmIo.interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Compensate only artifacts returned by this exact batch; no tenant scan or queue cleanup. */
    void discardProcessOutputs(
            List<Map<String, Object>> refs, String runId, long callId, long attempt, RuntimeException failure) {
        discardProcessOutputs(refs, runId, callId, attempt, algorithmDeadline, failure);
    }

    void discardProcessOutputs(
            List<Map<String, Object>> refs,
            String runId,
            long callId,
            long attempt,
            long deadline,
            RuntimeException failure) {
        var actor = Actor.current();
        var pendingIds = new ArrayList<String>();
        var pendingKeys = new ArrayList<String>();
        var pendingBuckets = new ArrayList<String>();
        for (var ref : refs) {
            String id = Objects.toString(ref.get("artifactId"), "");
            String key = processOutputPrefix(actor.tenantId(), runId, callId, attempt) + id + ".bin";
            try {
                var rows = db.queryForList(
                        "select bucket from skill.artifact where artifact_id=? and tenant_id=?"
                                + " and principal_id=? and run_id=? and call_id=? and object_key=?",
                        id, actor.tenantId(), actor.principalId(), runId, callId, key);
                if (rows.size() != 1) throw new IllegalStateException("PROCESS_OUTPUT_COMPENSATION_IDENTITY");
                int changed = db.update(
                        "update skill.artifact set state='CLEANUP_PENDING',last_error_code='PROCESS_OUTPUT_BATCH_FAILED',"
                                + "cleanup_after=now(),updated_at=now() where artifact_id=? and tenant_id=?"
                                + " and principal_id=? and run_id=? and call_id=? and object_key=?",
                        id, actor.tenantId(), actor.principalId(), runId, callId, key);
                if (changed != 1) throw new IllegalStateException("PROCESS_OUTPUT_COMPENSATION_STATE");
                pendingIds.add(id);
                pendingKeys.add(key);
                pendingBuckets.add(rows.getFirst().get("bucket").toString());
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (pendingIds.isEmpty()) return;
        if (deadline != 0 && deadline - System.nanoTime() <= 0) {
            // 期限已耗尽：所有已归档产物已转为 CLEANUP_PENDING (cleanup_after=now())，
            // 立即剥夺 READY 可见性并交由可恢复清理状态机制回收，不再阻塞等待远程对象删除。
            return;
        }
        if (deadline != 0 && algorithmIo == null) {
            try (var scoped = forAlgorithmDeadline(deadline)) {
                for (int i = 0; i < pendingIds.size(); i++) {
                    if (deadline - System.nanoTime() <= 0) break;
                    try {
                        scoped.deleteObject(
                                actor.tenantId(),
                                pendingIds.get(i),
                                pendingBuckets.get(i),
                                pendingKeys.get(i));
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                        if (deadline - System.nanoTime() <= 0) break;
                    }
                }
            } catch (Exception scopedFailure) {
                failure.addSuppressed(scopedFailure);
            }
            return;
        }
        for (int i = 0; i < pendingIds.size(); i++) {
            if (deadline != 0 && deadline - System.nanoTime() <= 0) break;
            try {
                deleteObject(
                        actor.tenantId(),
                        pendingIds.get(i),
                        pendingBuckets.get(i),
                        pendingKeys.get(i));
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                if (deadline != 0 && deadline - System.nanoTime() <= 0) break;
            }
        }
    }

    private static String processOutputPrefix(String tenant, String runId, long callId, long attempt) {
        return Digest.sha256(tenant) + "/artifacts/process/" + Digest.sha256(runId)
                + "/call-" + callId + "/attempt-" + attempt + "/";
    }

    /** 外部结果只可引用同tenant/principal/run的既有opaque产物；URL/物理键均不是授权凭据。 */
    public Map<String, Object> controlledReference(Map<String, Object> value, String runId) {
        if (!value.keySet().equals(Set.of("artifactId")) || runId == null)
            throw new PlatformException(400, "ARTIFACT_REFERENCE_REJECTED", "仅接受本次运行归属内的受控产物标识");
        return reference(owned(Objects.toString(value.get("artifactId"), ""), runId));
    }

    public record Download(InputStream source, long bytes, String contentType, String sha256)
            implements AutoCloseable {
        public void close() throws IOException {
            source.close();
        }
    }

    public Download download(String id, String runId) {
        var row = owned(id, runId);
        try {
            return new Download(
                    client().getObject(
                                    GetObjectArgs.builder()
                                            .bucket(row.get("bucket").toString())
                                            .object(row.get("object_key").toString())
                                            .build()),
                    ((Number) row.get("size_bytes")).longValue(),
                    row.get("content_type").toString(),
                    row.get("sha256").toString());
        } catch (Exception ex) {
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "产物暂不可下载，请稍后重试");
        }
    }

    public Map<String, Object> copyToRun(
            String id, String sourceRunId, String runId, long callId, Object outputSchema) {
        // download重新核对当前tenant/principal、原run与有效期；流式再登记/校验，绝不共享旧run的授权地址。
        try (var source = download(id, sourceRunId)) {
            return offload(new byte[0], source.source(), outputSchema, callId, runId);
        } catch (IOException ex) {
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "复用附件暂不可读取");
        }
    }

    /** Binary PROCESS replay keeps the source owner/run check and creates an independent attempt key. */
    public Map<String, Object> copyProcessToRun(
            String id, String sourceRunId, String runId, long callId, long attempt) {
        if (sourceRunId == null || sourceRunId.isBlank())
            throw new PlatformException(404, "ARTIFACT_NOT_FOUND", "原产物运行归属不可用");
        try (var source = download(id, sourceRunId)) {
            // The Download owns the real response stream; storeProcessOutput closes this wrapper.
            var bytes = new FilterInputStream(source.source()) {
                @Override public void close() {}
            };
            return storeProcessOutput(source.contentType(), source.bytes(), source.sha256(),
                    bytes, runId, callId, attempt);
        } catch (IOException failure) {
            throw new PlatformException(503, "ARTIFACT_STORAGE_UNAVAILABLE", "二进制附件暂不可复制");
        }
    }

    private void deleteObject(String tenant, String id, String bucket, String key)
            throws Exception {
        if (algorithmIo != null) algorithmIo.checkActive();
        abortIncompleteUploads(bucket, key);
        if (algorithmIo == null) {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } else {
            algorithmIo.await(
                    asyncClient()
                            .removeObject(
                                    RemoveObjectArgs.builder()
                                            .bucket(bucket)
                                            .object(key)
                                            .build()));
            algorithmIo.checkActive();
        }
        db.update(
                "update skill.artifact set"
                    + " state='DELETED',deleted_at=now(),cleanup_owner=null,cleanup_lease_until=null,updated_at=now()"
                    + " where tenant_id=? and artifact_id=?",
                tenant,
                id);
    }

    private void abortIncompleteUploads(String bucket, String key) throws Exception {
        // 对照SDK8.5.17 S3Base：常规异常会abort，但进程崩溃不会执行catch。只查询登记的完整键前缀，最多8个upload。
        // 该MinIO版本在列表返回空StorageClass，SDK的必填XML映射会失败；这里用JDK安全XML解析最小S3列表响应。
        try (var response =
                algorithmIo == null
                        ? asyncClient().listExact(bucket, key)
                        : algorithmIo.await(asyncClient().listExactAsync(bucket, key))) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("MULTIPART_CLEANUP_UNAVAILABLE");
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document =
                    factory.newDocumentBuilder()
                            .parse(new BoundedStream(response.body().byteStream(), 65536));
            var uploads = document.getElementsByTagName("Upload");
            if (uploads.getLength() > 8) throw new IOException("MULTIPART_CLEANUP_LIMIT");
            for (int i = 0; i < uploads.getLength(); i++) {
                var item = (org.w3c.dom.Element) uploads.item(i);
                if (!key.equals(item.getElementsByTagName("Key").item(0).getTextContent()))
                    continue;
                String uploadId = item.getElementsByTagName("UploadId").item(0).getTextContent();
                var abortFuture =
                        asyncClient()
                                .abortMultipartUploadAsync(bucket, null, key, uploadId, null, null);
                if (algorithmIo == null)
                    abortFuture.get(45, java.util.concurrent.TimeUnit.SECONDS);
                else algorithmIo.await(abortFuture);
            }
            var truncated = document.getElementsByTagName("IsTruncated");
            if (truncated.getLength() > 0 && "true".equals(truncated.item(0).getTextContent()))
                throw new IOException("MULTIPART_CLEANUP_REMAINS");
        }
    }

    @Scheduled(fixedDelayString = "${agent.artifact.cleanup-delay-ms:60000}")
    public void cleanupPulse() {
        // 系统任务仅发现到期tenant键；后续原子认领与对象删除都使用持久tenant+artifact归属，最多8项。
        var tenants =
                db.queryForList(
                        "select distinct tenant_id from skill.artifact where ((state='READY' and"
                            + " expires_at<=now()) or (state in ('UPLOADING','CLEANUP_PENDING') and"
                            + " cleanup_after<=now())) and (cleanup_lease_until is null or"
                            + " cleanup_lease_until<now()) limit 8");
        int remaining = 8;
        for (var row : tenants) {
            if (remaining <= 0) break;
            remaining -= cleanTenant(row.get("tenant_id").toString(), remaining);
        }
    }

    public int cleanTenant(String tenant, int maximum) {
        int processed = 0;
        String owner = "cleanup-" + UUID.randomUUID();
        for (int i = 0; i < Math.min(8, Math.max(0, maximum)); i++) {
            var rows =
                    db.queryForList(
                            "with candidate as (select artifact_id from skill.artifact where"
                                + " tenant_id=? and ((state='READY' and expires_at<=now()) or"
                                + " (state in ('UPLOADING','CLEANUP_PENDING') and"
                                + " cleanup_after<=now())) and (cleanup_lease_until is null or"
                                + " cleanup_lease_until<now()) order by cleanup_after for update"
                                + " skip locked limit 1) update skill.artifact a set"
                                + " state='CLEANUP_PENDING',cleanup_owner=?,cleanup_lease_until=now()+interval"
                                + " '90 seconds',cleanup_attempts=cleanup_attempts+1,last_error_code=coalesce(last_error_code,'RETENTION_OR_INTERRUPTED_UPLOAD'),updated_at=now()"
                                + " from candidate c where a.tenant_id=? and"
                                + " a.artifact_id=c.artifact_id returning"
                                + " a.artifact_id,a.bucket,a.object_key",
                            tenant,
                            owner,
                            tenant);
            if (rows.isEmpty()) break;
            var row = rows.getFirst();
            String id = row.get("artifact_id").toString();
            try {
                deleteObject(
                        tenant, id, row.get("bucket").toString(), row.get("object_key").toString());
            } catch (Exception ex) {
                db.update(
                        "update skill.artifact set last_error_code=?,cleanup_after=now()+interval"
                            + " '5 minutes',cleanup_owner=null,cleanup_lease_until=null,updated_at=now()"
                            + " where tenant_id=? and artifact_id=? and cleanup_owner=?",
                        "OBJECT_DELETE_FAILED_" + ex.getClass().getSimpleName(),
                        tenant,
                        id,
                        owner);
            }
            processed++;
        }
        return processed;
    }

    public static final class BoundedStream extends FilterInputStream {
        private final long maximum;
        public long count;

        public BoundedStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) received(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n =
                    in.read(
                            buffer,
                            offset,
                            (int) Math.min(length, Math.max(1, maximum - count + 1)));
            if (n > 0) received(n);
            return n;
        }

        private void received(int n) throws IOException {
            count += n;
            if (count > maximum) throw new IOException("OUTPUT_HARD_LIMIT: 响应超出64MiB绝对上限");
        }
    }
}
