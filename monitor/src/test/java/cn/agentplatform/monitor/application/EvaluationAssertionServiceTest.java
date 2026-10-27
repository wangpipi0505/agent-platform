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

/**
 * 断言模型与评测报告的事务边界：断言配置在保存期校验（不落库、不调用上游），
 * 判分模式随不可变数据集冻结，恢复执行沿用冻结模式而不是当前默认。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EvaluationAssertionServiceTest {
    static JdbcTemplate db;
    static Stub upstream;
    /** 开关开启（默认）：按用例冻结的断言判分。 */
    static EvaluationService service;
    /** 开关关闭：回滚到旧"整份输出相等"判分，报告不写断言快照。 */
    static EvaluationService exactOnly;
    static final AtomicInteger calls = new AtomicInteger();
    static boolean pending;
    static Map<String, Object> outputs = new ConcurrentHashMap<>();
    /** 与引擎一致：同一(报告,用例)重放返回原run，不计新业务执行。 */
    static Map<String, String> runs = new ConcurrentHashMap<>();
    String code;
    String dataset;

    @BeforeAll
    static void prepare() throws Exception {
        System.setProperty("agent.jwt.secret", "TEST-ONLY-monitor-assertion-123456789012345678901");
        // 本类不启动Spring上下文：按生产SecurityConfiguration同一入口登记legacy部署模式。
        // 否则单类隔离运行时ServiceTokens.issue会以AUTHORITY_CONTEXT_MISSING拒绝签发，表现为假红。
        SecurityConfiguration.bindDeployment(
                new org.springframework.mock.env.MockEnvironment()
                        .withProperty("agent.security.mode", "dev-hmac")
                        .withProperty("agent.security.service-id", "monitor")
                        .withProperty("agent.dev-auth", "true")
                        .withProperty("server.address", "127.0.0.1"));
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
        var tx = new DataSourceTransactionManager(ds);
        service = new EvaluationService(db, tx, url, url, true);
        exactOnly = new EvaluationService(db, tx, url, url, false);
    }

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("assertion-verified")
                        .header("alg", "HS256")
                        .subject("evaluator")
                        .claim("tenant_id", "monitor-tests")
                        .claim("roles", List.of("EDITOR"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        code = "assert_" + UUID.randomUUID().toString().replace("-", "");
        dataset = "dataset-assert";
        pending = false;
        calls.set(0);
        runs.clear();
        outputs.clear();
        outputs.put("safe", "ok");
        outputs.put(
                "subset",
                obj("text", "ok", "usage", Map.of("total_tokens", 12), "actualModel", "m-1"));
        outputs.put("schema", obj("text", "ok", "extra", 1));
        outputs.put("legacy", obj("text", "ok", "usage", Map.of("total_tokens", 3)));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stop() throws Exception {
        if (upstream != null) upstream.close();
    }

    private Map<String, Object> request(String sha, List<Map<String, Object>> cases) {
        return Map.of(
                "appCode",
                code,
                "draftRev",
                1,
                "candidateSha256",
                sha,
                "datasetVersion",
                dataset,
                "threshold",
                0.5,
                "cases",
                cases);
    }

    private static Map<String, Object> safeCase() {
        return Map.of(
                "id",
                "safe",
                "inputs",
                Map.of("safety", true),
                "expected",
                "ok",
                "safety",
                true);
    }

    private static Map<String, Object> subsetCase() {
        return Map.of(
                "id",
                "subset",
                "inputs",
                Map.of(),
                "expected",
                Map.of("text", "ok"),
                "safety",
                false,
                "assertion",
                "JSON_SUBSET");
    }

    private static Map<String, Object> schemaCase() {
        return Map.of(
                "id",
                "schema",
                "inputs",
                Map.of(),
                "expected",
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("text"),
                        "properties",
                        Map.of("text", Map.of("type", "string"))),
                "safety",
                false,
                "assertion",
                "SCHEMA");
    }

    /** 历史用例：没有assertion字段，必须继续按整份输出相等解释。 */
    private static Map<String, Object> legacyCase() {
        return Map.of(
                "id",
                "legacy",
                "inputs",
                Map.of(),
                "expected",
                Map.of("text", "ok"),
                "safety",
                false);
    }

    private static List<Map<String, Object>> suite() {
        return List.of(safeCase(), subsetCase(), schemaCase(), legacyCase());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> results(Map<String, Object> report) {
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        for (var result : (List<Map<String, Object>>) report.get("results"))
            byId.put(Objects.toString(result.get("id")), result);
        return byId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedCases() {
        String text =
                db.queryForObject(
                        "select cases::text from monitor.dataset where tenant_id=? and app_code=?"
                                + " and dataset_version=?",
                        String.class,
                        "monitor-tests",
                        code,
                        dataset);
        return (List<Map<String, Object>>) (List<?>) Json.read(text, List.class);
    }

    private int count(String table) {
        return db.queryForObject(
                "select count(*) from " + table + " where tenant_id=? and app_code=?",
                Integer.class,
                "monitor-tests",
                code);
    }

    @Test
    void judgesEachCaseWithItsFrozenAssertionAndRecordsTheModel() {
        var report = service.evaluate(request("a".repeat(64), suite()));

        assertEquals(4, calls.get());
        assertEquals("ACCEPTED", report.get("status"));
        assertEquals("CONFIGURABLE", report.get("assertionModel"));

        var results = results(report);
        assertTrue((Boolean) results.get("safe").get("passed"));
        assertEquals("EXACT", results.get("safe").get("assertion"));
        assertTrue((Boolean) results.get("subset").get("passed"));
        assertEquals("JSON_SUBSET", results.get("subset").get("assertion"));
        assertTrue((Boolean) results.get("schema").get("passed"));
        assertEquals("SCHEMA", results.get("schema").get("assertion"));

        // 旧语义未变：CHAT 输出多出usage/actualModel，EXACT仍失败且原因可读、有界。
        var legacy = results.get("legacy");
        assertFalse((Boolean) legacy.get("passed"));
        assertEquals("EXACT", legacy.get("assertion"));
        assertEquals("ASSERTION_EXACT_MISMATCH", legacy.get("reasonCode"));
        String reason = Objects.toString(legacy.get("reason"));
        assertTrue(reason.contains("多出字段"), reason);
        assertTrue(reason.contains("usage"), reason);
        assertTrue(reason.length() < 400, "断言原因不得回贴整份输出: " + reason.length());

        @SuppressWarnings("unchecked")
        var snapshot = (Map<String, Object>) report.get("assertionSnapshot");
        assertEquals("EXACT", snapshot.get("safe"));
        assertEquals("JSON_SUBSET", snapshot.get("subset"));
        assertEquals("SCHEMA", snapshot.get("schema"));
        assertEquals("EXACT", snapshot.get("legacy"));

        var row =
                db.queryForMap(
                        "select assertion_model,assertion_snapshot::text as snapshot from"
                            + " monitor.evaluation_report where tenant_id=? and id=?",
                        "monitor-tests",
                        report.get("id"));
        assertEquals("CONFIGURABLE", row.get("assertion_model"));
        assertEquals(
                "JSON_SUBSET",
                Json.tree(Json.read(row.get("snapshot").toString())).path("subset").asText());

        // 数据集中断言被规范化冻结；历史用例没有该字段时保持缺失（不改变旧数据集语义）。
        var frozen = storedCases();
        assertEquals(
                "JSON_SUBSET",
                frozen.stream()
                        .filter(c -> "subset".equals(c.get("id")))
                        .findFirst()
                        .orElseThrow()
                        .get("assertion"));
        assertFalse(
                frozen.stream()
                        .filter(c -> "legacy".equals(c.get("id")))
                        .findFirst()
                        .orElseThrow()
                        .containsKey("assertion"));
    }

    @Test
    void historicalSuiteWithoutAssertionStillJudgesExactly() {
        var cases =
                List.of(
                        safeCase(),
                        Map.of(
                                "id",
                                "normal",
                                "inputs",
                                Map.of(),
                                "expected",
                                "ok",
                                "safety",
                                false));
        var report = service.evaluate(request("e".repeat(64), cases));
        assertEquals("ACCEPTED", report.get("status"));
        assertEquals("EXACT", results(report).get("normal").get("assertion"));
    }

    @Test
    void rejectsUnknownAssertionBeforeFreezingDatasetOrCallingUpstream() {
        var cases =
                List.of(
                        safeCase(),
                        Map.of(
                                "id",
                                "bad",
                                "inputs",
                                Map.of(),
                                "expected",
                                "ok",
                                "safety",
                                false,
                                "assertion",
                                "FUZZY"));
        var error =
                assertThrows(
                        PlatformException.class,
                        () -> service.evaluate(request("b".repeat(64), cases)));
        assertEquals(400, error.status());
        assertEquals("EVALUATION_ASSERTION_UNKNOWN", error.code());
        assertTrue(error.getMessage().contains("FUZZY"), error.getMessage());
        assertEquals(0, calls.get(), "保存期拒绝不得发起上游执行");
        assertEquals(0, count("monitor.dataset"), "保存期拒绝不得冻结数据集");
        assertEquals(0, count("monitor.evaluation_report"), "保存期拒绝不得创建报告");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTAINS", "REGEX"})
    void rejectsSafetyCaseThatWeakensEquality(String assertion) {
        var cases =
                List.of(
                        Map.of(
                                "id",
                                "safe",
                                "inputs",
                                Map.of("safety", true),
                                "expected",
                                ".*",
                                "safety",
                                true,
                                "assertion",
                                assertion));
        var error =
                assertThrows(
                        PlatformException.class,
                        () -> service.evaluate(request("c".repeat(64), cases)));
        assertEquals(400, error.status());
        assertEquals("EVALUATION_ASSERTION_SAFETY_CONSTRAINT", error.code());
        assertTrue(error.getMessage().contains("安全"), error.getMessage());
        assertEquals(0, calls.get());
        assertEquals(0, count("monitor.dataset"));
        assertEquals(0, count("monitor.evaluation_report"));
    }

    @Test
    void disabledSwitchRestoresLegacyEqualityJudging() {
        var report = exactOnly.evaluate(request("d".repeat(64), suite()));
        assertEquals("EXACT", report.get("assertionModel"));
        assertNull(report.get("assertionSnapshot"));
        assertEquals("REJECTED", report.get("status"));
        var subset = results(report).get("subset");
        assertFalse((Boolean) subset.get("passed"));
        assertEquals("ASSERTION_EXACT_MISMATCH", subset.get("reasonCode"));
    }

    @Test
    void resumeRejudgesWithTheFrozenAssertionModel() {
        pending = true;
        var report = service.evaluate(request("f".repeat(64), suite()));
        assertEquals("RUNNING", report.get("status"));
        pending = false;

        var resumed = service.resume(report.get("id").toString());
        assertEquals("ACCEPTED", resumed.get("status"));
        assertEquals("CONFIGURABLE", resumed.get("assertionModel"));
        assertTrue((Boolean) results(resumed).get("subset").get("passed"));
        assertEquals(4, calls.get(), "恢复沿用冻结用例的原有run");
    }

    private static Map<String, Object> obj(Object... kv) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i].toString(), kv[i + 1]);
        return map;
    }

    /** Test-only阻塞HTTP对端，避开本机JDK损坏的NIO Unix-domain selector路径。 */
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
                    String caseId = Objects.toString(request.get("caseId"));
                    assertNotNull(caseId);
                    String runId =
                            runs.computeIfAbsent(
                                    request.get("evalReportId") + ":" + caseId,
                                    key -> "assert-run-" + calls.incrementAndGet());
                    data =
                            Map.of(
                                    "status",
                                    pending ? "PENDING" : "SUCCEEDED",
                                    "runId",
                                    runId,
                                    "output",
                                    outputs.getOrDefault(caseId, "ok"));
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
