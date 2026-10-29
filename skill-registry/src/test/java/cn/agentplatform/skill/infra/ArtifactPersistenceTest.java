package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import io.minio.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")
class ArtifactPersistenceTest {
    static JdbcTemplate db;
    ArtifactStore store;
    String tenant;
    long call;

    @BeforeAll
    static void migrate() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
    }

    @BeforeEach
    void prepare() {
        tenant = "artifact-test-" + UUID.randomUUID();
        call = System.nanoTime();
        identity(tenant, "owner");
        store = new ArtifactStore(db, System.getenv());
    }

    @AfterEach
    void cleanup() {
        db.update(
                "update skill.artifact set expires_at=now()-interval '1"
                        + " second',cleanup_after=now()-interval '1 second' where tenant_id=? and"
                        + " state<>'DELETED'",
                tenant);
        try {
            store.cleanTenant(tenant, 8);
        } finally {
            store.close();
            SecurityContextHolder.clearContext();
        }
    }

    void identity(String t, String p) {
        var jwt =
                Jwt.withTokenValue("verified-fixture")
                        .header("alg", "HS256")
                        .subject(p)
                        .claim("tenant_id", t)
                        .claim("roles", List.of("SERVICE"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> upload(int size) {
        byte[] json = Json.write(Map.of("data", "x".repeat(size))).getBytes(StandardCharsets.UTF_8);
        return store.offload(
                new byte[0],
                new ByteArrayInputStream(json),
                Map.of("type", "object"),
                call,
                "r_artifact");
    }

    @Test
    @Timeout(60)
    void processInputReadsOwnedReadyUploadAndClosesRealDownload(@TempDir Path temporary)
            throws Exception {
        byte[] payload = new byte[] {0, 1, 2, 10, 13, 127, (byte) 255};
        var ref = store.upload("application/octet-stream", payload.length,
                new ByteArrayInputStream(payload));
        String id = ref.get("artifactId").toString();
        var original = inputRow(id);
        assertEquals("READY", original.get("state"));
        assertTrue(original.get("run_id").toString().startsWith("r_upload_"));
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("business", List.of("keep", "order"));
        arguments.put("files", Map.of("trajectory", Map.of("artifactId", id)));
        String frozenArguments = Json.canonical(arguments);
        String runId = "r_process_input_" + UUID.randomUUID().toString().replace("-", "");

        try (var downloads = new ProcessInputStore()) {
            var raw = runProcessInput(downloads, arguments, temporary, runId, call, 7L);
            var received = raw.structured().path("received");
            assertAll(
                    () -> assertEquals(payload.length, raw.structured().path("inputBytes").asLong()),
                    () -> assertEquals(ref.get("sha256"), raw.structured().path("inputSha256").asText()),
                    () -> assertTrue(received.path("files").path("trajectory").asText()
                            .matches("input/[0-9]+\\.bin")),
                    () -> assertFalse(received.path("parameters").has("files")),
                    () -> assertEquals(Json.tree(arguments.get("business")),
                            received.path("parameters").path("business")),
                    () -> assertEquals(Long.toString(call), received.path("requestId").asText()),
                    () -> assertEquals(frozenArguments, Json.canonical(arguments)),
                    () -> assertEquals(1, downloads.opened.get()),
                    () -> assertEquals(1, downloads.closed.get()),
                    () -> assertTrue(raw.fileRefs().isEmpty(), "input-only call creates no output artifact"),
                    () -> assertEquals(original, inputRow(id)),
                    () -> assertEquals(1, inputRowCount()));
        }
    }

    @Test
    @Timeout(120)
    void processInputRejectsOtherPrincipalAndTenantBeforeWorker(@TempDir Path temporary)
            throws Exception {
        byte[] payload = "owner-only".getBytes(StandardCharsets.UTF_8);
        var ref = store.upload("application/octet-stream", payload.length,
                new ByteArrayInputStream(payload));
        String id = ref.get("artifactId").toString();
        var original = inputRow(id);
        try (var downloads = new ProcessInputStore()) {
            int ordinal = 0;
            for (var owner : List.of(
                    List.of(tenant, "other-principal"),
                    List.of(tenant + "-unrelated", "owner"))) {
                identity(owner.get(0), owner.get(1));
                Path own = Files.createDirectory(temporary.resolve("denied-" + ordinal++));
                assertProcessInputNotFound(downloads, id, own, call + ordinal);
            }
            assertEquals(0, downloads.opened.get(), "authorization precedes object download");
            assertEquals(0, downloads.closed.get(), "no stream was acquired");
        } finally {
            identity(tenant, "owner");
        }
        assertEquals(original, inputRow(id));
        assertEquals(1, inputRowCount());
    }

    @Test
    @Timeout(60)
    void processInputRejectsExpiredUploadBeforeWorker(@TempDir Path temporary) throws Exception {
        byte[] payload = "expired-input".getBytes(StandardCharsets.UTF_8);
        var ref = store.upload("application/octet-stream", payload.length,
                new ByteArrayInputStream(payload));
        String id = ref.get("artifactId").toString();
        assertEquals(1, db.update(
                "update skill.artifact set expires_at=now()-interval '1 second'"
                        + " where tenant_id=? and principal_id=? and artifact_id=? and state='READY'",
                tenant, "owner", id));
        var original = inputRow(id);
        try (var downloads = new ProcessInputStore()) {
            assertProcessInputNotFound(downloads, id, temporary, call);
            assertEquals(0, downloads.opened.get());
            assertEquals(0, downloads.closed.get());
        }
        assertEquals(original, inputRow(id), "denial does not delete or rewrite the source");
        assertEquals(1, inputRowCount());
    }

    private Map<String, Object> inputRow(String id) {
        return db.queryForMap(
                "select artifact_id,tenant_id,principal_id,run_id,call_id,state,size_bytes,sha256,"
                        + "bucket,object_key,expires_at from skill.artifact"
                        + " where tenant_id=? and artifact_id=?", tenant, id);
    }

    private int inputRowCount() {
        return db.queryForObject(
                "select count(*) from skill.artifact where tenant_id=?", Integer.class, tenant);
    }

    private void assertProcessInputNotFound(
            ProcessInputStore downloads, String id, Path temporary, long executionCall)
            throws Exception {
        PlatformException rejected = null;
        try {
            runProcessInput(downloads, Map.of("files", Map.of("trajectory", Map.of("artifactId", id))),
                    temporary, "r_process_denied_" + UUID.randomUUID().toString().replace("-", ""),
                    executionCall, 9L);
        } catch (PlatformException failure) {
            rejected = failure;
        }
        final PlatformException actual = rejected;
        assertAll(
                () -> {
                    assertNotNull(actual, "unavailable input must fail, even if a worker returned ok");
                    assertEquals(404, actual.status());
                    assertEquals("PROCESS_INPUT_ARTIFACT_NOT_FOUND", actual.code());
                },
                () -> assertFalse(Files.exists(temporary.resolve("worker-started")),
                        "rejection must happen before the real worker starts"));
    }

    /** Delegates authorization and streaming to the real store; it never manufactures READY data. */
    private static final class ProcessInputStore extends ArtifactStore {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        ProcessInputStore() {
            super(ArtifactPersistenceTest.db, System.getenv());
        }

        @Override
        public Download download(String id, String sourceRun) {
            Download actual = super.download(id, sourceRun);
            opened.incrementAndGet();
            return new Download(new FilterInputStream(actual.source()) {
                @Override
                public void close() throws IOException {
                    closed.incrementAndGet();
                    super.close();
                }
            }, actual.bytes(), actual.contentType(), actual.sha256());
        }
    }

    private SkillAdapters.Raw runProcessInput(
            ArtifactStore inputs, Map<String, Object> arguments, Path temporary,
            String runId, long executionCall, long attempt) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("中文 空格 输入根")).toRealPath();
        Path marker = temporary.resolve("worker-started").toAbsolutePath();
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toRealPath().toString();
        Path classes = Path.of(ArtifactPersistenceTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        assertTrue(Files.isDirectory(classes));
        var process = new ProcessAdapter(true, root.toString(), 1, 1, 0, 0, executable);
        String requestId = Long.toString(executionCall);
        var contract = Map.<String, Object>of(
                "code", "input-probe", "type", "PROCESS", "invocationKind", "SYNC",
                "process", Map.of(
                        "command", List.of(executable, "-Xmx64m", "-cp", classes.toString(),
                                ProcessInputWorker.class.getName(), marker.toString()),
                        "timeoutMs", 20000, "stdin", Map.of("files", true)),
                "inputSchema", Map.of("type", "object"),
                "outputSchema", Map.of("type", "object"));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var caller = Executors.newSingleThreadExecutor();
        var future = caller.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                return new SkillAdapters(inputs, null, null, process).invoke(
                        contract, arguments, "process-input-" + executionCall, executionCall,
                        false, runId, attempt);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        ProcessHandle owned = null;
        Instant created = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !future.isDone()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("worker identity timeout");
                Thread.sleep(10);
            }
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                var fields = Files.readAllLines(marker, StandardCharsets.UTF_8);
                assertEquals(4, fields.size());
                var candidate = ProcessHandle.of(Long.parseLong(fields.get(0))).orElseThrow();
                Instant candidateCreated = Instant.parse(fields.get(1));
                assertEquals(candidateCreated, candidate.info().startInstant().orElseThrow());
                assertEquals(ProcessHandle.current().pid(), candidate.parent().orElseThrow().pid());
                assertEquals(Path.of(executable), Path.of(candidate.info().command().orElseThrow()).toRealPath());
                assertEquals(requestId, fields.get(2));
                Path callDir = Path.of(fields.get(3)).toRealPath();
                assertEquals(root, callDir.getParent());
                owned = candidate;
                created = candidateCreated;
                Files.writeString(callDir.resolve("input-probe.release"), requestId,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            try {
                return future.get(25, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException actual) throw actual;
                throw new AssertionError("unexpected invocation failure", failure.getCause());
            }
        } finally {
            process.cancel(requestId);
            if (owned != null && owned.isAlive()
                    && owned.info().startInstant().filter(created::equals).isPresent())
                ProcessTree.killTree(owned);
            future.cancel(true);
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(process.inFlight(requestId));
            if (owned != null) assertFalse(owned.isAlive(), "registered worker must terminate");
            try (var entries = Files.list(root)) {
                assertEquals(0, entries.count(), "only this callDir is reclaimed");
            }
        }
    }

    /** Pure JDK worker; absent input is reported normally so missing product IO is an assertion red. */
    public static final class ProcessInputWorker {
        public static void main(String[] args) throws Exception {
            if (args.length != 1) throw new IllegalArgumentException("marker argument required");
            byte[] stdin = System.in.readNBytes(1024 * 1024 + 1);
            if (stdin.length > 1024 * 1024) throw new IllegalArgumentException("oversize stdin");
            String received = new String(stdin, StandardCharsets.UTF_8).trim();
            var id = Pattern.compile("\"requestId\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").matcher(received);
            if (!id.find()) throw new IllegalArgumentException("requestId missing");
            String requestId = id.group(1);
            Path cwd = Path.of("").toRealPath();
            Path marker = Path.of(args[0]);
            Path pending = marker.resolveSibling("worker-started.pending");
            Files.write(pending, List.of(Long.toString(ProcessHandle.current().pid()),
                    ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                    requestId, cwd.toString()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.move(pending, marker);
            Path release = cwd.resolve("input-probe.release");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("release timeout");
                Thread.sleep(10);
            }
            if (!requestId.equals(Files.readString(release, StandardCharsets.UTF_8)))
                throw new IllegalStateException("release identity mismatch");

            long count = 0;
            var digest = MessageDigest.getInstance("SHA-256");
            var input = Pattern.compile("\"files\"\\s*:\\s*\\{\\s*\"trajectory\"\\s*:\\s*\"(input/[0-9]+\\.bin)\"\\s*}")
                    .matcher(received);
            if (input.find()) {
                Path path = cwd.resolve(input.group(1));
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().startsWith(cwd.resolve("input").toRealPath()))
                    throw new IllegalArgumentException("input must stay within this callDir");
                try (InputStream source = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = source.read(buffer)) >= 0;) {
                        count += n;
                        if (count > 16L * 1024 * 1024) throw new IllegalArgumentException("fixture bound");
                        digest.update(buffer, 0, n);
                    }
                }
            }
            String result = "{\"protocol\":\"algorithm-process/1\",\"requestId\":\"" + requestId
                    + "\",\"ok\":true,\"result\":{\"inputBytes\":" + count + ",\"inputSha256\":\""
                    + HexFormat.of().formatHex(digest.digest()) + "\",\"received\":" + received
                    + "},\"files\":[],\"warnings\":[],\"metrics\":{}}";
            System.out.write(result.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }
    }

    @Test
    void uploadedInputIsOwnerScopedAndDownloadableWithoutAnExecution() throws Exception {
        byte[] body = "satellite,tle\n1,line".getBytes(StandardCharsets.UTF_8);
        var ref = store.upload("text/csv", body.length, new ByteArrayInputStream(body));
        String id = ref.get("artifactId").toString();
        assertEquals("/admin/artifacts/" + id + "/download", ref.get("downloadUrl"));
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("READY", row.get("state"));
        assertTrue(row.get("run_id").toString().startsWith("r_upload_"));
        assertEquals("text/csv", row.get("content_type"));
        try (var download = store.download(id, null)) {
            assertArrayEquals(body, download.source().readAllBytes());
            assertEquals("text/csv", download.contentType());
        }
        identity(tenant, "other");
        assertThrows(PlatformException.class, () -> store.download(id, null));
    }

    @Test
    void fiveMegabyteArtifactIsOpaquePersistentAndDownloadableOnlyByOwnerAndRun() throws Exception {
        var ref = upload(5 * 1024 * 1024);
        String id = ref.get("artifactId").toString();
        assertFalse(Json.write(ref).contains("s3://"));
        assertFalse(ref.containsKey("uri"));
        assertTrue(ref.get("downloadUrl").toString().startsWith("/admin/artifacts/"));
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("READY", row.get("state"));
        assertEquals("owner", row.get("principal_id"));
        assertEquals("r_artifact", row.get("run_id"));
        assertEquals(ref.get("sha256"), row.get("sha256"));
        try (var download = store.download(id, "r_artifact")) {
            byte[] actual = download.source().readAllBytes();
            assertEquals(((Number) ref.get("bytes")).longValue(), actual.length);
            assertEquals(
                    ref.get("sha256"),
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(actual)));
            assertEquals(
                    "x".repeat(5 * 1024 * 1024),
                    ArtifactStore.readJson(new ByteArrayInputStream(actual)).path("data").asText());
        }
        assertEquals(ref, store.controlledReference(Map.of("artifactId", id), "r_artifact"));
        var adapter = new SkillAdapters(store, null);
        var contract =
                Map.<String, Object>of(
                        "outputMaxBytes",
                        1024,
                        "outputSchema",
                        Map.of("type", "object"),
                        "returnSpec",
                        Map.of("fileRefPaths", List.of("$.file")));
        var mapped =
                adapter.read(
                        new ByteArrayInputStream(
                                Json.write(Map.of("file", Map.of("artifactId", id)))
                                        .getBytes(StandardCharsets.UTF_8)),
                        contract,
                        call,
                        "r_artifact");
        assertEquals(ref, mapped.fileRefs().getFirst());
        identity(tenant, "other");
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        identity("other-tenant", "owner");
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        identity(tenant, "owner");
        assertThrows(PlatformException.class, () -> store.download(id, "r_other"));
        assertThrows(
                PlatformException.class,
                () ->
                        store.controlledReference(
                                Map.of("artifactId", id, "uri", "s3://evil/key"), "r_artifact"));
    }

    @Test
    void schemaFailureDeletesExactObjectAndLeavesDurableCleanupRecord() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.offload(
                                new byte[0],
                                new ByteArrayInputStream(
                                        "{\"data\":1}".getBytes(StandardCharsets.UTF_8)),
                                Map.of("type", "array"),
                                call,
                                "r_artifact"));
        assertDeletedObject();
    }

    @Test
    void recordedAttachmentGetsNewRunOwnershipAndSurvivesSourceExpiry() throws Exception {
        var original = upload(5 * 1024 * 1024);
        String sourceId = original.get("artifactId").toString();
        var adapters = new SkillAdapters(store, null);
        var source =
                Json.tree(
                        Map.of(
                                "structured",
                                Map.of(),
                                "fileRefs",
                                List.of(original),
                                "modelSummary",
                                "已核实的大结果",
                                "cardPayloadJson",
                                "{\"title\":\"原卡片\"}"));
        var reused =
                adapters.recorded(
                        source,
                        Map.of("outputSchema", Map.of("type", "object")),
                        call + 1,
                        "r_reused",
                        "r_artifact");
        var copy = reused.fileRefs().getFirst();
        String copiedId = copy.get("artifactId").toString();
        assertNotEquals(sourceId, copiedId);
        assertEquals(original.get("sha256"), copy.get("sha256"));
        assertEquals(
                "/v1/runs/r_reused/artifacts/" + copiedId + "/download",
                copy.get("externalDownloadUrl"));
        assertEquals(
                "r_reused",
                db.queryForObject(
                        "select run_id from skill.artifact where artifact_id=?",
                        String.class,
                        copiedId));
        assertThrows(PlatformException.class, () -> store.download(sourceId, "r_reused"));
        identity(tenant, "other");
        assertThrows(
                PlatformException.class,
                () ->
                        adapters.recorded(
                                source,
                                Map.of("outputSchema", Map.of()),
                                call + 2,
                                "r_stolen",
                                "r_artifact"));
        identity(tenant, "owner");
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where"
                        + " artifact_id=?",
                sourceId);
        assertEquals(1, store.cleanTenant(tenant, 1));
        try (var downloaded = store.download(copiedId, "r_reused")) {
            assertEquals(
                    ((Number) copy.get("bytes")).longValue(),
                    downloaded.source().transferTo(OutputStream.nullOutputStream()));
        }
    }

    @Test
    void hardByteLimitFailureDeletesExactObject() {
        InputStream oversized =
                new InputStream() {
                    long left = ArtifactStore.MAX_ARTIFACT_BYTES + 1;

                    public int read() {
                        return left-- > 0 ? ' ' : -1;
                    }

                    public int read(byte[] b, int off, int len) {
                        if (left <= 0) return -1;
                        int n = (int) Math.min(left, len);
                        Arrays.fill(b, off, off + n, (byte) ' ');
                        left -= n;
                        return n;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> store.offload(new byte[0], oversized, Map.of(), call, "r_artifact"));
        assertDeletedObject();
    }

    void assertDeletedObject() {
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals("DELETED", row.get("state"), Objects.toString(row.get("last_error_code")));
        assertNotNull(row.get("last_error_code"));
        var client =
                MinioClient.builder()
                        .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                        .credentials(
                                System.getenv("AGENT_MINIO_ACCESS_KEY"),
                                System.getenv("AGENT_MINIO_SECRET_KEY"))
                        .build();
        var error =
                assertThrows(
                        io.minio.errors.ErrorResponseException.class,
                        () ->
                                client.statObject(
                                        StatObjectArgs.builder()
                                                .bucket(row.get("bucket").toString())
                                                .object(row.get("object_key").toString())
                                                .build()));
        assertEquals("NoSuchKey", error.errorResponse().code());
    }

    @Test
    void retentionIsBoundedAndNeverDeletesAnotherTenantObject() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?",
                tenant);
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        assertEquals(0, store.cleanTenant(tenant + "-unrelated", 8));
        assertEquals(
                "READY",
                db.queryForObject(
                        "select state from skill.artifact where tenant_id=?",
                        String.class,
                        tenant));
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertDeletedObject();
    }

    @Test
    void interruptedUploadIsRecoveredFromMetadataWithoutBucketListing() throws Exception {
        var ref = upload(128);
        db.update(
                "update skill.artifact set state='UPLOADING',cleanup_after=now()-interval '1"
                        + " second' where tenant_id=? and artifact_id=?",
                tenant,
                ref.get("artifactId"));
        var row =
                db.queryForMap(
                        "select bucket,object_key from skill.artifact where tenant_id=?", tenant);
        try (var client =
                MinioAsyncClient.builder()
                        .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                        .credentials(
                                System.getenv("AGENT_MINIO_ACCESS_KEY"),
                                System.getenv("AGENT_MINIO_SECRET_KEY"))
                        .build()) {
            String bucket = row.get("bucket").toString(), key = row.get("object_key").toString();
            String uploadId =
                    client.createMultipartUploadAsync(bucket, null, key, null, null)
                            .get()
                            .result()
                            .uploadId();
            assertEquals(1, store.cleanTenant(tenant, 1));
            assertDeletedObject();
            var error =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () ->
                                    client.listPartsAsync(
                                                    bucket, null, key, 1, 0, uploadId, null, null)
                                            .get());
            assertEquals(
                    "NoSuchUpload",
                    ((io.minio.errors.ErrorResponseException) error.getCause())
                            .errorResponse()
                            .code());
        }
    }

    @Test
    void responseStreamFailureCleansPartialUploadAndDoesNotIssueReference() {
        InputStream broken =
                new InputStream() {
                    long left = 6 * 1024 * 1024;

                    public int read() throws IOException {
                        if (left-- <= 0) throw new IOException("fixture interruption");
                        return ' ';
                    }

                    public int read(byte[] b, int off, int len) throws IOException {
                        if (left <= 0) throw new IOException("fixture interruption");
                        int n = (int) Math.min(left, len);
                        Arrays.fill(b, off, off + n, (byte) ' ');
                        left -= n;
                        return n;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.offload(
                                new byte[0], broken, Map.of("type", "object"), call, "r_artifact"));
        assertDeletedObject();
    }

    @Test
    void storageCleanupFailureIsRecordedAndLaterRetriedWithoutChangingOwnership() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?"
                        + " and artifact_id=?",
                tenant,
                id);
        var invalid = new HashMap<>(System.getenv());
        invalid.put("AGENT_MINIO_ACCESS_KEY", "artifact-test-invalid-key");
        var failed = new ArtifactStore(db, invalid);
        try {
            assertEquals(1, failed.cleanTenant(tenant, 1));
        } finally {
            failed.close();
        }
        var row =
                db.queryForMap(
                        "select state,last_error_code,cleanup_attempts,cleanup_after>now() as"
                            + " delayed from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("CLEANUP_PENDING", row.get("state"));
        assertTrue(row.get("last_error_code").toString().startsWith("OBJECT_DELETE_FAILED_"));
        assertEquals(1, row.get("cleanup_attempts"));
        assertEquals(true, row.get("delayed"));
        db.update(
                "update skill.artifact set cleanup_after=now()-interval '1 second' where"
                        + " tenant_id=? and artifact_id=?",
                tenant,
                id);
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertDeletedObject();
    }

    @Test
    void databaseOwnershipCannotBeRewrittenAndCleanupHonorsBatchMaximum() {
        var ref = upload(128);
        upload(128);
        upload(128);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "update skill.artifact set principal_id='replacement' where"
                                        + " tenant_id=? and artifact_id=?",
                                tenant,
                                ref.get("artifactId")));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "update skill.artifact set object_key='different/key' where"
                                        + " tenant_id=? and artifact_id=?",
                                tenant,
                                ref.get("artifactId")));
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?",
                tenant);
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='DELETED'",
                        Integer.class,
                        tenant));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='READY'",
                        Integer.class,
                        tenant));
    }

    @Test
    void httpDownloadRoutesReturnBytesAndRejectSpoofedIdentityAndWrongRun() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new cn.agentplatform.skill.api.ArtifactController(store))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/admin/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "private, no-store"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentType("application/json"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/runs/r_artifact/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/runs/r_wrong/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        identity(tenant, "other");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/admin/artifacts/" + id + "/download")
                                .header("X-User-Id", "owner")
                                .param("principalId", "owner"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        SecurityContextHolder.clearContext();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/admin/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isUnauthorized());
    }

    @Test
    void declaredUploadOverHardLimitDoesNotReadOrRegister() throws Exception {
        var source = new UploadSource(ArtifactStore.MAX_ARTIFACT_BYTES + 1);
        // 参数预检拒绝前仍由调用方持有流，和 ArtifactController 的 try-with-resources 一致。
        try (source) {
            var rejected = assertThrows(PlatformException.class,
                    () -> store.upload("application/octet-stream",
                            ArtifactStore.MAX_ARTIFACT_BYTES + 1, source));
            assertEquals(413, rejected.status());
            assertEquals("ARTIFACT_TOO_LARGE", rejected.code());
            assertEquals(0, source.readCalls, "声明超限必须在读取文件前拒绝");
            assertEquals(0, db.queryForObject(
                    "select count(*) from skill.artifact where tenant_id=?", Integer.class, tenant));
        }
        assertEquals(1, source.closeCalls);
    }

    @Test
    void shortUploadNeverBecomesReadyAndCleansExactObject() throws Exception {
        var source = new UploadSource(3);
        assertRejectedUpload(source, 8);
    }

    @Test
    void longUploadNeverBecomesReadyAndCleansExactObject() throws Exception {
        var source = new UploadSource(8);
        assertRejectedUpload(source, 3);
    }

    @Test
    void brokenUploadCleansExactObjectAndClosesSource() throws Exception {
        long failAfter = 5L * 1024 * 1024 + 13;
        var source = new UploadSource(6L * 1024 * 1024, failAfter);
        assertRejectedUpload(source, 6L * 1024 * 1024);
        assertEquals(failAfter, source.position, "必须实际读到指定断流点，不能用预检失败冒充断流");
    }

    private void assertRejectedUpload(UploadSource source, long declared) throws Exception {
        PlatformException rejection = null;
        Map<String, Object> reference = null;
        try {
            reference = store.upload("application/octet-stream", declared, source);
        } catch (PlatformException expected) {
            rejection = expected;
        }
        var actualRejection = rejection;
        var actualReference = reference;
        assertAll(
                () -> assertNotNull(actualRejection, "大小不符或断流的上传必须拒绝"),
                () -> assertNull(actualReference, "失败上传不得发出 READY 引用"),
                () -> assertEquals(1, source.closeCalls, "已接管的上传源流必须只关闭一次"),
                this::assertUploadFailureCleaned);
    }

    private void assertUploadFailureCleaned() throws Exception {
        var rows = db.queryForList("select * from skill.artifact where tenant_id=?", tenant);
        assertEquals(1, rows.size(), "只允许本用例登记的一条上传事实");
        var row = rows.getFirst();
        assertEquals("DELETED", row.get("state"));
        assertEquals("UPLOAD_FAILED", row.get("last_error_code"));
        assertNotNull(row.get("deleted_at"));
        assertEquals("owner", row.get("principal_id"));
        assertTrue(row.get("run_id").toString().startsWith("r_upload_"));
        try (var client = MinioAsyncClient.builder()
                .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                .credentials(System.getenv("AGENT_MINIO_ACCESS_KEY"), System.getenv("AGENT_MINIO_SECRET_KEY"))
                .build()) {
            var missing = assertThrows(ExecutionException.class,
                    () -> client.statObject(StatObjectArgs.builder()
                            .bucket(row.get("bucket").toString())
                            .object(row.get("object_key").toString()).build()).get(10, TimeUnit.SECONDS));
            // MinIO 8.5.17 的 async HEAD 在 ExecutionException 内保留一层 CompletionException。
            var completion = assertInstanceOf(CompletionException.class, missing.getCause());
            var cause = assertInstanceOf(io.minio.errors.ErrorResponseException.class, completion.getCause());
            assertEquals("NoSuchKey", cause.errorResponse().code(), "只检查本次持久登记的精确对象键");
        }
    }

    @Test
    void sixtyFourMiBUploadSurvivesFreshStoreAndStreamsToHttpWithMatchingDigest() throws Exception {
        long bytes = ArtifactStore.MAX_ARTIFACT_BYTES;
        var source = new UploadSource(bytes);
        var reference = store.upload("application/octet-stream", bytes, source);
        String expectedDigest = HexFormat.of().formatHex(source.digest.digest());
        String id = reference.get("artifactId").toString();
        assertEquals(bytes, source.position);
        assertTrue(source.readCalls > 1, "文件按需生成，不能以一个大 byte[] 代替流式上传");
        assertEquals(1, source.closeCalls);
        assertEquals(bytes, ((Number) reference.get("bytes")).longValue());
        assertEquals(expectedDigest, reference.get("sha256"));
        var row = db.queryForMap("select * from skill.artifact where tenant_id=? and artifact_id=?", tenant, id);
        assertEquals("READY", row.get("state"));
        assertEquals(bytes, ((Number) row.get("size_bytes")).longValue());
        assertEquals(expectedDigest, row.get("sha256"));

        // 证明组件冷重建后仍可读取持久内容；这不是 JVM 或整个服务重启的证据。
        store.close();
        store = new ArtifactStore(db, System.getenv());
        var downloadedDigest = MessageDigest.getInstance("SHA-256");
        try (var download = store.download(id, null);
                var sink = new DigestOutputStream(OutputStream.nullOutputStream(), downloadedDigest)) {
            assertEquals(bytes, download.bytes());
            assertEquals(expectedDigest, download.sha256());
            assertEquals(bytes, download.source().transferTo(sink));
        }
        assertEquals(expectedDigest, HexFormat.of().formatHex(downloadedDigest.digest()));

        var received = new CompletableFuture<ReceivedUpload>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            try (exchange) {
                var actual = receiveSingleFile(exchange.getRequestBody(),
                        exchange.getRequestHeaders().getFirst("Content-Type"), bytes);
                received.complete(actual);
                byte[] response = "{\"received\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception failure) {
                received.completeExceptionally(failure);
            }
        });
        server.start();
        try {
            var contract = new LinkedHashMap<String, Object>();
            contract.put("type", "HTTP");
            contract.put("httpMethod", "POST");
            contract.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
            contract.put("timeoutMs", 30000);
            contract.put("execution", Map.of("readonly", true, "sideEffect", false));
            contract.put("outputSchema", Map.of("type", "object"));
            contract.put("outputMaxBytes", 1024);
            contract.put("httpMapping", Map.of("path", List.of(), "query", List.of(),
                    "multipart", Map.of("files", List.of(Map.of(
                            "name", "file", "from", "file", "filename", "payload.bin",
                            "maxBytes", bytes, "allowedContentTypes", List.of("application/octet-stream"))))));
            var output = new SkillAdapters(store, null).http(contract,
                    Map.of("file", reference.get("downloadUrl")), "artifact-64m", call, "r_upload_stream", 1);
            assertTrue(output.structured().path("received").asBoolean());
            var actual = received.get(10, TimeUnit.SECONDS);
            assertEquals(bytes, actual.bytes());
            assertEquals(expectedDigest, actual.sha256());
            assertEquals("application/octet-stream", actual.contentType());
            assertTrue(actual.disposition().contains("name=\"file\""));
            assertTrue(actual.disposition().contains("filename=\"payload.bin\""));
        } finally {
            server.stop(0);
        }
    }

    private record ReceivedUpload(long bytes, String sha256, String contentType, String disposition) {}

    /** 仅解析本例约定的一个文件：有界头、精确长度和结束边界，文件内容只进 8 KiB 缓冲。 */
    private static ReceivedUpload receiveSingleFile(InputStream raw, String contentType, long expected)
            throws Exception {
        String boundary = org.springframework.http.MediaType.parseMediaType(contentType).getParameter("boundary");
        if (boundary == null || boundary.length() > 200) throw new IOException("missing multipart boundary");
        var input = new BufferedInputStream(raw, 8192);
        if (!("--" + boundary).equals(readAsciiLine(input))) throw new IOException("wrong opening boundary");
        var headers = new LinkedHashMap<String, String>();
        for (int i = 0; ; i++) {
            String line = readAsciiLine(input);
            if (line.isEmpty()) break;
            if (i >= 16) throw new IOException("too many part headers");
            int colon = line.indexOf(':');
            if (colon <= 0 || headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim()) != null) throw new IOException("invalid part header");
        }
        if (!Long.toString(expected).equals(headers.get("content-length")))
            throw new IOException("part length does not match uploaded artifact");
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        long count = 0;
        while (count < expected) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected - count));
            if (read < 0) throw new EOFException("truncated file part");
            digest.update(buffer, 0, read);
            count += read;
        }
        if (!readAsciiLine(input).isEmpty()
                || !("--" + boundary + "--").equals(readAsciiLine(input)) || input.read() != -1)
            throw new IOException("unexpected extra bytes or multipart parts");
        return new ReceivedUpload(count, HexFormat.of().formatHex(digest.digest()),
                headers.getOrDefault("content-type", ""), headers.getOrDefault("content-disposition", ""));
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        var line = new ByteArrayOutputStream(128);
        while (line.size() <= 8192) {
            int value = input.read();
            if (value < 0) throw new EOFException("truncated multipart framing");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("invalid multipart newline");
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (value > 127 || value == '\n') throw new IOException("invalid multipart header byte");
            line.write(value);
        }
        throw new IOException("multipart header exceeds 8 KiB");
    }

    private static final class UploadSource extends InputStream {
        final long total;
        final long failAfter;
        final MessageDigest digest;
        long position;
        int readCalls;
        int closeCalls;

        UploadSource(long total) throws Exception {
            this(total, Long.MAX_VALUE);
        }

        UploadSource(long total, long failAfter) throws Exception {
            this.total = total;
            this.failAfter = failAfter;
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public int read() throws IOException {
            readCalls++;
            if (position >= failAfter) throw new IOException("fixture upload interruption");
            if (position >= total) return -1;
            int value = (int) (position++ % 251);
            digest.update((byte) value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            readCalls++;
            if (position >= failAfter) throw new IOException("fixture upload interruption");
            if (position >= total) return -1;
            int count = (int) Math.min(Math.min(length, 8192), Math.min(total - position, failAfter - position));
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) ((position + i) % 251);
            digest.update(buffer, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
