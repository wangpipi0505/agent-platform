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

    private final Map<String, String> configuration;
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
            // Do not cancel this future: cancellation would mark only the wrapper done, without
            // joining PartReader/HTTP chains. Its real completion retains lifecycle ownership.
            original.whenComplete(
                    (value, error) -> {
                        try {
                            if (stopping && value instanceof AutoCloseable response) {
                                try {
                                    response.close();
                                } catch (Exception ignored) {
                                }
                            }
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
                    if (value instanceof AutoCloseable response)
                        try {
                            response.close();
                        } catch (Exception ignored) {
                        }
                    throw stopped;
                }
            } catch (InterruptedException ex) {
                interrupted = true;
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

    private void deleteObject(String tenant, String id, String bucket, String key)
            throws Exception {
        abortIncompleteUploads(bucket, key);
        client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
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
        try (var response = asyncClient().listExact(bucket, key)) {
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
                asyncClient()
                        .abortMultipartUploadAsync(bucket, null, key, uploadId, null, null)
                        .get(45, java.util.concurrent.TimeUnit.SECONDS);
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
