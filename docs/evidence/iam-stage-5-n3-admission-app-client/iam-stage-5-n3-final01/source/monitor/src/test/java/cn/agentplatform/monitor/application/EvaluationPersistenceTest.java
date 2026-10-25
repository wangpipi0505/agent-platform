package cn.agentplatform.monitor.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EvaluationPersistenceTest {
    static JdbcTemplate db;
    static Stub upstream;
    static EvaluationService service;
    static AtomicInteger calls = new AtomicInteger();
    static boolean failSafety;
    static boolean pending;
    static String pendingState;
    static Map<String, String> runs = new ConcurrentHashMap<>();
    String code;
    String dataset;

    @BeforeAll
    static void prepare() throws Exception {
        System.setProperty("agent.jwt.secret", "TEST-ONLY-monitor-evaluation-12345678901234567890");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("monitor")
                .defaultSchema("monitor")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        upstream = new Stub();
        String url = "http://127.0.0.1:" + upstream.server.getLocalPort();
        service = new EvaluationService(db, new DataSourceTransactionManager(ds), url, url);
    }

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("test-verified")
                        .header("alg", "HS256")
                        .subject("evaluator")
                        .claim("tenant_id", "monitor-tests")
                        .claim("roles", List.of("EDITOR"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        code = "eval_" + UUID.randomUUID().toString().replace("-", "");
        dataset = "dataset-1";
        failSafety = false;
        pending = false;
        pendingState = "RUNNING";
        calls.set(0);
        runs.clear();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stop() throws Exception {
        if (upstream != null) upstream.close();
    }

    Map<String, Object> request(String sha) {
        return Map.of(
                "appCode",
                code,
                "draftRev",
                1,
                "candidateSha256",
                sha,
                "datasetVersion",
                dataset,
                "cases",
                List.of(
                        Map.of(
                                "id",
                                "safe",
                                "inputs",
                                Map.of("safety", true),
                                "expected",
                                "ok",
                                "safety",
                                true),
                        Map.of(
                                "id",
                                "normal",
                                "inputs",
                                Map.of(),
                                "expected",
                                "ok",
                                "safety",
                                false)));
    }

    @Test
    void executesEveryCaseAndPinsDatasetAndCandidate() {
        String sha = "a".repeat(64);
        var report = service.evaluate(request(sha));
        assertEquals(2, calls.get());
        assertEquals("ACCEPTED", report.get("status"));
        assertEquals(dataset, report.get("datasetVersion"));
        assertEquals(
                report.get("evalReportId"), service.accepted(code, 1, sha).get("evalReportId"));
        assertEquals(false, service.accepted(code, 1, "b".repeat(64)).get("accepted"));
    }

    @Test
    void safetyFailureCannotBecomeAccepted() {
        failSafety = true;
        String sha = "c".repeat(64);
        var report = service.evaluate(request(sha));
        assertEquals("REJECTED", report.get("status"));
        assertEquals(false, service.accepted(code, 1, sha).get("accepted"));
    }

    @Test
    void unfinishedEvaluationResumesFrozenCasesWithoutNewBusinessRuns() {
        pending = true;
        var report = service.evaluate(request("f".repeat(64)));
        assertEquals("RUNNING", report.get("status"));
        assertEquals(2, calls.get());
        pending = false;
        var recovered = service.resume(report.get("id").toString());
        assertEquals("ACCEPTED", recovered.get("status"));
        assertEquals(2, calls.get());
        assertEquals(report.get("id"), recovered.get("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_EXTERNAL", "PENDING"})
    void externalOrQueuedCasesRemainResumableUntilTheirOriginalRunsFinish(String state) {
        pending = true;
        pendingState = state;
        var report = service.evaluate(request("8".repeat(64)));
        assertEquals("RUNNING", report.get("status"));
        assertEquals(false, report.get("accepted"));
        assertEquals(2, calls.get());
        assertNull(
                db.queryForMap(
                                "select completed_at from monitor.evaluation_report where"
                                    + " tenant_id=? and id=?",
                                "monitor-tests",
                                report.get("id"))
                        .get("completed_at"));
        var originalResults = Json.tree(report.get("results"));
        pending = false;
        var recovered = service.resume(report.get("id").toString());
        assertEquals("ACCEPTED", recovered.get("status"));
        assertEquals(report.get("id"), recovered.get("id"));
        assertEquals(2, calls.get(), "resuming the frozen report must reuse each original run");
        var recoveredResults = Json.tree(recovered.get("results"));
        for (int i = 0; i < originalResults.size(); i++)
            assertEquals(
                    originalResults.get(i).path("runId"), recoveredResults.get(i).path("runId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void terminalFailedCasesRemainRejectedRatherThanBeingQueued(String state) {
        pending = true;
        pendingState = state;
        var report = service.evaluate(request("7".repeat(64)));
        assertEquals("REJECTED", report.get("status"));
        assertEquals(false, report.get("accepted"));
        assertEquals(2, calls.get());
        assertNotNull(
                db.queryForMap(
                                "select completed_at from monitor.evaluation_report where"
                                    + " tenant_id=? and id=?",
                                "monitor-tests",
                                report.get("id"))
                        .get("completed_at"));
        pending = false;
        assertEquals("REJECTED", service.resume(report.get("id").toString()).get("status"));
        assertEquals(2, calls.get());
    }

    @Test
    void anotherEditorCannotResumeOriginalActorsEvaluation() {
        pending = true;
        var report = service.evaluate(request("9".repeat(64)));
        var jwt =
                Jwt.withTokenValue("other-editor-verified")
                        .header("alg", "HS256")
                        .subject("another-editor")
                        .claim("tenant_id", "monitor-tests")
                        .claim("roles", List.of("EDITOR"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var error =
                assertThrows(
                        PlatformException.class, () -> service.resume(report.get("id").toString()));
        assertEquals(403, error.status());
        assertEquals(2, calls.get());
    }

    @Test
    void datasetVersionCannotBeRewritten() {
        service.evaluate(request("d".repeat(64)));
        var changed = new HashMap<>(request("e".repeat(64)));
        changed.put(
                "cases",
                List.of(
                        Map.of(
                                "id",
                                "changed",
                                "inputs",
                                Map.of(),
                                "expected",
                                "different",
                                "safety",
                                true)));
        var ex = assertThrows(PlatformException.class, () -> service.evaluate(changed));
        assertEquals(409, ex.status());
        assertEquals(2, calls.get());
    }

    /**
     * Test-only blocking HTTP peer avoids this Windows JDK's broken NIO Unix-domain selector path.
     */
    static final class Stub implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

        Stub() throws IOException {
            workers.submit(
                    () -> {
                        while (!server.isClosed())
                            try {
                                Socket socket = server.accept();
                                workers.submit(() -> serve(socket));
                            } catch (IOException ex) {
                                if (!server.isClosed()) throw new UncheckedIOException(ex);
                            }
                    });
        }

        void serve(Socket socket) {
            try (socket) {
                var input = new BufferedInputStream(socket.getInputStream());
                String requestLine = line(input);
                var headers = new HashMap<String, String>();
                for (String h = line(input); !h.isEmpty(); h = line(input)) {
                    int colon = h.indexOf(':');
                    headers.put(
                            h.substring(0, colon).toLowerCase(Locale.ROOT),
                            h.substring(colon + 1).trim());
                }
                assertTrue(headers.getOrDefault("authorization", "").startsWith("Bearer "));
                var body = new ByteArrayOutputStream();
                if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                    for (int n = Integer.parseInt(line(input), 16);
                            n > 0;
                            n = Integer.parseInt(line(input), 16)) {
                        body.write(input.readNBytes(n));
                        line(input);
                    }
                    line(input);
                } else
                    body.write(
                            input.readNBytes(
                                    Integer.parseInt(headers.getOrDefault("content-length", "0"))));
                String target = requestLine.split(" ")[1];
                Map<String, Object> data;
                if (target.startsWith("/internal/apps/")) {
                    String hash =
                            Arrays.stream(target.split("\\?")[1].split("&"))
                                    .filter(v -> v.startsWith("candidateSha256="))
                                    .findFirst()
                                    .orElseThrow()
                                    .split("=")[1];
                    data =
                            Map.of(
                                    "candidateSha256",
                                    hash,
                                    "draftRev",
                                    1,
                                    "config",
                                    Map.of(),
                                    "definition",
                                    Map.of());
                } else {
                    var request = Json.read(body.toString(StandardCharsets.UTF_8), Map.class);
                    assertNotNull(request.get("datasetVersion"));
                    assertNotNull(request.get("evalReportId"));
                    assertNotNull(request.get("frozenCandidate"));
                    assertNotNull(request.get("caseId"));
                    String runId =
                            runs.computeIfAbsent(
                                    request.get("evalReportId") + ":" + request.get("caseId"),
                                    key -> "test-run-" + calls.incrementAndGet());
                    var inputs = (Map<?, ?>) request.get("inputs");
                    Object output =
                            failSafety && Boolean.TRUE.equals(inputs.get("safety"))
                                    ? "unsafe"
                                    : "ok";
                    data =
                            Map.of(
                                    "status",
                                    pending ? pendingState : "SUCCEEDED",
                                    "runId",
                                    runId,
                                    "output",
                                    output);
                }
                byte[] bytes = Json.write(Map.of("data", data)).getBytes(StandardCharsets.UTF_8);
                var output = socket.getOutputStream();
                output.write(
                        ("HTTP/1.1 200 OK\r\n"
                                        + "Content-Type: application/json\r\n"
                                        + "Connection: close\r\n"
                                        + "Content-Length: "
                                        + bytes.length
                                        + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.flush();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        static String line(InputStream input) throws IOException {
            var text = new ByteArrayOutputStream();
            int b;
            while ((b = input.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') text.write(b);
            }
            return text.toString(StandardCharsets.US_ASCII);
        }

        public void close() throws Exception {
            server.close();
            workers.close();
        }
    }
}
