package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.SkillCatalog;
import cn.agentplatform.skill.application.SkillExecution;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real storage/facts and a real worker; only the engine authority client is a controlled fixture. */
@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")
class ProcessBinaryReplayTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    @TempDir Path temporary;
    JdbcTemplate db;
    ArtifactStore store;
    SkillExecution execution;
    ProcessAdapter process;
    String tenant, code, executable;
    Path root, marker;
    long version;
    Map<String, Object> currentRequest;
    Map<String, Object> authorityMock = Map.of();
    int submitted;

    @BeforeEach
    void prepare() throws Exception {
        tenant = "binary-replay-" + UUID.randomUUID();
        identity(tenant, "owner");
        db = database();
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()));
        store = new ArtifactStore(db, System.getenv());
        executable = javaBinary().toString();
        root = Files.createDirectory(temporary.resolve("中文 空格 二进制根")).toRealPath();
        marker = temporary.resolve("worker-started").toAbsolutePath();
        process = new ProcessAdapter(true, root.toString(), 1, 1, 0, 0, executable);
        var clients = mock(SkillClients.class);
        var catalog = new SkillCatalog(db, manager, clients, "http://unused", true, true, executable);
        code = "binary_" + UUID.randomUUID().toString().replace("-", "");
        Path classes = Path.of(ProcessBinaryReplayTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        var contract = new LinkedHashMap<String, Object>();
        contract.put("code", code);
        contract.put("name", "二进制回放夹具");
        contract.put("description", "真实本地二进制输出，仅用于受控产物回放验证");
        contract.put("type", "PROCESS");
        contract.put("process", Map.of("command", List.of(executable, "-Xmx64m", "-cp", classes.toString(),
                ArtifactPersistenceTest.ProcessOutputWorker.class.getName(), marker.toString(), "one"),
                "timeoutMs", 20000, "stdin", Map.of("files", true)));
        contract.put("inputSchema", Map.of("type", "object"));
        contract.put("outputSchema", Map.of("type", "object", "required", List.of("binary"),
                "properties", Map.of("binary", Map.of("type", "boolean"))));
        contract.put("execution", Map.of("readonly", true, "sideEffect", false,
                "idempotent", true, "cancellable", false));
        contract.put("guard", Map.of("roles", List.of("EDITOR")));
        version = ((Number) catalog.create(contract).get("skillVersionId")).longValue();
        // Published fixture for execution only, not a claim that publication governance was exercised.
        assertEquals(1, db.update("update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                tenant, version));
        assertEquals(1, db.update("update skill.skill set status='PUBLISHED',current_version_id=?"
                + " where tenant_id=? and code=?", version, tenant, code));
        execution = new SkillExecution(db, manager, catalog,
                new SkillAdapters(store, null, null, process), clients, "http://engine.fixture");
        when(clients.get(anyString())).thenReturn(Json.tree(Map.of()));
        when(clients.post(anyString(), any())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url.endsWith("/claim")) {
                var claim = new LinkedHashMap<String, Object>();
                claim.put("claimed", true);
                claim.put("status", "EXECUTING");
                claim.put("skill_version_id", version);
                claim.put("skill_code", code);
                claim.put("args_digest", Digest.sha256(Json.canonical(currentRequest.get("arguments"))));
                claim.put("node_instance_id", currentRequest.get("nodeInstanceId"));
                claim.put("runId", currentRequest.get("runId"));
                claim.put("attempt_id", currentRequest.get("attemptId"));
                claim.put("idempotency_key", currentRequest.get("idempotencyKey"));
                claim.put("trigger_type", authorityMock.isEmpty() ? "TASK" : "DEBUG");
                claim.put("mock_snapshot", authorityMock);
                return Json.tree(claim);
            }
            if (url.endsWith("/submitted")) {
                submitted++;
                return Json.tree(Map.of("submitted", true));
            }
            if (url.endsWith("/fact")) {
                assertEquals(1, db.queryForObject("select count(*) from skill.execution_fact"
                        + " where tenant_id=? and call_id=?", Integer.class, tenant, currentRequest.get("callId")));
                return Json.tree(Map.of("accepted", true));
            }
            throw new AssertionError("unexpected engine fixture operation");
        });
    }

    @AfterEach
    void cleanup() {
        try {
            if (store != null) store.close();
            if (db != null && tenant != null) {
                identity(tenant, "owner");
                db.update("update skill.artifact set expires_at=now()-interval '1 second',"
                        + "cleanup_after=now()-interval '1 second' where tenant_id=? and state<>'DELETED'", tenant);
                try (var cleanup = new ArtifactStore(db, System.getenv())) {
                    cleanup.cleanTenant(tenant, 8);
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @Timeout(120)
    void lastRealCopiesBinaryWithoutJsonOffloadAndPreservesNewAttempt() throws Exception {
        var source = executeSource();
        String sourceId = source.path("fileRefs").get(0).path("artifactId").asText();
        String sourceRun = currentRequest.get("runId").toString();
        long sourceCall = ((Number) currentRequest.get("callId")).longValue();
        var original = row(sourceId);
        var originalFact = db.queryForMap("select * from skill.execution_fact where tenant_id=? and call_id=?",
                tenant, sourceCall);
        authorityMock = Map.of("skillCode", code, "mode", "FIXED", "sourceKind", "LAST_REAL",
                "sourceCallId", sourceCall, "sourceRunId", sourceRun, "sourcePrincipal", "owner",
                "sourceDigest", Digest.sha256(Json.canonical(source)), "fixedResponse", source);
        currentRequest = request("r_binary_replay_", 23L);
        var result = execution.execute(code, currentRequest);
        assertEquals("SUCCEEDED", result.get("status"),
                "binary LAST_REAL must not run the JSON offload parser");
        var replay = Json.tree(result.get("result"));
        assertTrue(replay.path("isMock").asBoolean());
        assertEquals(source.path("structured"), replay.path("structured"));
        assertEquals(1, replay.path("fileRefs").size());
        String id = replay.path("fileRefs").get(0).path("artifactId").asText();
        var copied = row(id);
        String runId = currentRequest.get("runId").toString();
        long callId = ((Number) currentRequest.get("callId")).longValue();
        assertAll(
                () -> assertNotEquals(sourceId, id),
                () -> assertEquals(tenant, copied.get("tenant_id")),
                () -> assertEquals("owner", copied.get("principal_id")),
                () -> assertEquals(runId, copied.get("run_id")),
                () -> assertEquals(callId, ((Number) copied.get("call_id")).longValue()),
                () -> assertTrue(copied.get("object_key").toString().contains("/attempt-23/")),
                () -> assertEquals("application/octet-stream", copied.get("content_type")),
                () -> assertEquals(original.get("sha256"), copied.get("sha256")),
                () -> assertEquals(original.get("size_bytes"), copied.get("size_bytes")),
                () -> assertEquals(original, row(sourceId)),
                () -> assertEquals(originalFact, db.queryForMap(
                        "select * from skill.execution_fact where tenant_id=? and call_id=?", tenant, sourceCall)),
                () -> assertEquals(1, submitted, "replay must not submit or start a second worker"),
                () -> assertEquals(23L, replay.path("owner").path("attemptId").asLong()));
        assertBinary(store, id, runId);
        assertEquals(1, db.update("update skill.artifact set expires_at=now()-interval '1 second'"
                + " where tenant_id=? and artifact_id=? and state='READY'", tenant, sourceId));
        assertDenied(store, sourceId, sourceRun);
        assertBinary(store, id, runId);
        assertDenied(store, id, sourceRun);
        identity(tenant, "not-owner");
        assertDenied(store, id, runId);
        identity(tenant + "-unrelated", "owner");
        assertDenied(store, id, runId);
        identity(tenant, "owner");
    }

    @Test
    @Timeout(120)
    void independentJvmReadsPersistedBinaryAndRejectsOtherOwners() throws Exception {
        var source = executeSource();
        String id = source.path("fileRefs").get(0).path("artifactId").asText();
        String run = currentRequest.get("runId").toString();
        var original = row(id);
        store.close(); // The creator's store is closed before a different JVM constructs its reader.
        Path metadata = temporary.resolve("read-metadata.json");
        Path proof = temporary.resolve("read-proof.json");
        Path release = temporary.resolve("read.release");
        Path rawLog = temporary.resolve("read.private.log");
        Files.writeString(metadata, Json.write(Map.of("tenant", tenant, "principal", "owner",
                "artifactId", id, "runId", run)), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(executable, "-Xmx128m", "-cp", classpath,
                ReadProbe.class.getName(), metadata.toString(), proof.toString(), release.toString());
        builder.directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(rawLog.toFile());
        var originalEnv = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String key : List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "PATH",
                "AGENT_DB_PASSWORD", "AGENT_MINIO_ENDPOINT", "AGENT_MINIO_ACCESS_KEY",
                "AGENT_MINIO_SECRET_KEY", "AGENT_MINIO_BUCKET")) {
            if (originalEnv.containsKey(key)) builder.environment().put(key, originalEnv.get(key));
        }
        Process child = builder.start();
        Instant created = child.info().startInstant().orElseThrow();
        long pid = child.pid();
        try {
            assertNotEquals(ProcessHandle.current().pid(), pid);
            assertEquals(ProcessHandle.current().pid(), child.toHandle().parent().orElseThrow().pid());
            assertEquals(javaBinary(), Path.of(child.info().command().orElseThrow()).toRealPath());
            Files.writeString(release, Long.toString(pid), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "new-JVM readback has a finite wait");
            assertEquals(0, child.exitValue(), "probe raw diagnostics remain only in the private temp file");
            JsonNode actual = Json.read(Files.readString(proof, StandardCharsets.UTF_8));
            assertEquals(pid, actual.path("pid").asLong());
            assertEquals(created, Instant.parse(actual.path("started").asText()));
            assertEquals(ProcessHandle.current().pid(), actual.path("parent").asLong());
            assertTrue(actual.path("read").asBoolean());
            assertTrue(actual.path("wrongOwnerDenied").asBoolean());
            assertTrue(actual.path("wrongTenantDenied").asBoolean());
            assertEquals(original, row(id), "fresh JVM download does not rewrite source ownership");
        } finally {
            if (child.isAlive() && child.info().startInstant().filter(created::equals).isPresent())
                child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "only the registered reader JVM is stopped");
        }
    }

    private JsonNode executeSource() throws Exception {
        currentRequest = request("r_binary_source_", 11L);
        String requestId = currentRequest.get("callId").toString();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var caller = Executors.newSingleThreadExecutor();
        var future = caller.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                return execution.execute(code, currentRequest);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        ProcessHandle owned = null;
        Instant created = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !future.isDone()) {
                if (System.nanoTime() >= deadline) throw new AssertionError("worker registration timeout");
                Thread.sleep(10);
            }
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                var fields = Files.readAllLines(marker, StandardCharsets.UTF_8);
                assertEquals(4, fields.size());
                var candidate = ProcessHandle.of(Long.parseLong(fields.get(0))).orElseThrow();
                Instant start = Instant.parse(fields.get(1));
                assertEquals(start, candidate.info().startInstant().orElseThrow());
                assertEquals(ProcessHandle.current().pid(), candidate.parent().orElseThrow().pid());
                assertEquals(javaBinary(), Path.of(candidate.info().command().orElseThrow()).toRealPath());
                assertEquals(requestId, fields.get(2));
                Path callDir = Path.of(fields.get(3)).toRealPath();
                assertEquals(root, callDir.getParent());
                owned = candidate;
                created = start;
                Files.writeString(callDir.resolve("input-probe.release"), requestId,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            var actual = future.get(45, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", actual.get("status"), "real source PROCESS output must be available");
            JsonNode source = Json.tree(actual.get("result"));
            assertFalse(source.path("isMock").asBoolean());
            assertEquals(1, source.path("fileRefs").size());
            return source;
        } finally {
            process.cancel(requestId);
            if (owned != null && owned.isAlive()
                    && owned.info().startInstant().filter(created::equals).isPresent())
                ProcessTree.killTree(owned);
            future.cancel(true);
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(process.inFlight(requestId));
            if (owned != null) assertFalse(owned.isAlive());
            try (var children = Files.list(root)) {
                assertEquals(0, children.count());
            }
        }
    }

    private Map<String, Object> request(String prefix, long attempt) {
        return new LinkedHashMap<>(Map.of("callId", IDS.incrementAndGet(),
                "runId", prefix + UUID.randomUUID().toString().replace("-", ""), "attemptId", attempt,
                "nodeInstanceId", 991L, "skillVersionId", version, "arguments", Map.of(),
                "idempotencyKey", UUID.randomUUID().toString(), "leaseOwner", "binary-fixture"));
    }

    private Map<String, Object> row(String id) {
        return db.queryForMap("select * from skill.artifact where tenant_id=? and artifact_id=?", tenant, id);
    }

    private static JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12", "agent_v12",
                System.getenv("AGENT_DB_PASSWORD")));
    }

    private static Path javaBinary() throws Exception {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toRealPath();
    }

    private static void identity(String tenant, String principal) {
        var jwt = Jwt.withTokenValue("verified-binary-fixture").header("alg", "HS256").subject(principal)
                .claim("tenant_id", tenant).claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void assertBinary(ArtifactStore store, String id, String run) throws Exception {
        byte[] expected = ArtifactPersistenceTest.ProcessOutputWorker.payload(1);
        try (var download = store.download(id, run)) {
            assertEquals(expected.length, download.bytes());
            assertEquals("application/octet-stream", download.contentType());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)),
                    download.sha256());
            assertArrayEquals(expected, download.source().readNBytes(expected.length + 1));
            assertEquals(-1, download.source().read());
        }
    }

    private static void assertDenied(ArtifactStore store, String id, String run) {
        var error = assertThrows(PlatformException.class, () -> {
            try (var unexpected = store.download(id, run)) {
                fail("unexpected artifact visibility");
            }
        });
        assertEquals(404, error.status());
        assertEquals("ARTIFACT_NOT_FOUND", error.code());
    }

    /** A new JVM reads platform artifacts; it does not migrate, start services, or write artifact rows. */
    public static final class ReadProbe {
        public static void main(String[] args) {
            try {
                if (args.length != 3) throw new IllegalArgumentException("probe arguments");
                Path release = Path.of(args[2]);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("probe release timeout");
                    Thread.sleep(10);
                }
                if (!Long.toString(ProcessHandle.current().pid()).equals(
                        Files.readString(release, StandardCharsets.UTF_8)))
                    throw new IllegalStateException("probe release identity");
                JsonNode input = Json.read(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
                String tenant = input.path("tenant").asText(), owner = input.path("principal").asText();
                String id = input.path("artifactId").asText(), run = input.path("runId").asText();
                identity(tenant, owner);
                try (var store = new ArtifactStore(database(), System.getenv())) {
                    assertBinary(store, id, run);
                    identity(tenant, owner + "-unrelated");
                    assertDenied(store, id, run);
                    identity(tenant + "-unrelated", owner);
                    assertDenied(store, id, run);
                }
                Files.writeString(Path.of(args[1]), Json.write(Map.of(
                        "pid", ProcessHandle.current().pid(),
                        "started", ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                        "parent", ProcessHandle.current().parent().orElseThrow().pid(),
                        "read", true, "wrongOwnerDenied", true, "wrongTenantDenied", true)),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (Throwable failure) {
                // Never emit URLs, object keys, environment values, or a raw exception into public XML.
                System.err.println("BINARY_READ_PROBE_FAILED:" + failure.getClass().getSimpleName());
                System.exit(2);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
