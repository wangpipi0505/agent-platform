package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.skill.application.SkillCatalog;
import cn.agentplatform.skill.application.SkillExecution;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.MDC;

/**
 * 复核P2修正（R13时限贯通）：同一 queryDeadline 必须约束异步查询的每个阶段——
 * 状态请求、独立结果请求与结果拒绝后的状态复查。多阶段耗时叠加不得把总期限拖成 1.6T；
 * 已耗尽的期限在发出新请求前即拒绝，不再按完整 timeoutMs 重置读超时。
 */
class AsyncResultDeadlinePropagationTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    String tenant, key;
    long call;
    HttpServer server;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    final AtomicInteger resultHits = new AtomicInteger();
    final java.util.List<String> phaseHits = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_async_test",
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
        tx = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void setup() throws Exception {
        tenant = "deadline_" + UUID.randomUUID();
        key = "key/" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("deadline-owner")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MDC.put("traceId", "trace-async-deadline");
        resultHits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/submit",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            Json.write(Map.of("jobId", "job-1", "idempotencyKey", key))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        MDC.remove("traceId");
        server.stop(0);
        db.update("delete from skill.execution_fact where tenant_id=?", tenant);
        db.update("delete from skill.external_job_binding where tenant_id=?", tenant);
    }

    /** 状态端点先耗时 slowStatusMs 再返回DONE；结果端点挂起不返回（超出任何剩余期限）。 */
    private void startSlowPeer(long slowStatusMs) {
        server.createContext(
                "/jobs",
                exchange -> {
                    phaseHits.add("status@" + System.currentTimeMillis());
                    exchange.getRequestBody().readAllBytes();
                    try {
                        Thread.sleep(slowStatusMs);
                    } catch (InterruptedException done) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "jobId",
                                                    "job-1",
                                                    "idempotencyKey",
                                                    key,
                                                    "status",
                                                    "DONE"))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/jobs-result",
                exchange -> {
                    phaseHits.add("result@" + System.currentTimeMillis());
                    exchange.getRequestBody().readAllBytes();
                    resultHits.incrementAndGet();
                    try {
                        Thread.sleep(60_000); // 挂起远超任何剩余期限；由客户端读超时切断
                    } catch (InterruptedException hung) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
    }

    void prepareExecution(long timeoutMs) {
        long version = ((Number) catalog.create(contract(timeoutMs)).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_deadline",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                Map.of("region", "A"),
                                "idempotencyKey",
                                key,
                                "leaseOwner",
                                "worker"));
        var authority =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "claimed",
                                true,
                                "status",
                                "UNKNOWN",
                                "skill_version_id",
                                version,
                                "skill_code",
                                "deadline_probe",
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                "r_deadline",
                                "trigger_type",
                                "TASK",
                                "idempotency_key",
                                key,
                                "mock_snapshot",
                                Map.of()));
        authority.put("attempt_id", 456);
        when(clients.get(anyString()))
                .thenAnswer(
                        i ->
                                Json.tree(
                                        i.<String>getArgument(0).contains("/admin/runs/")
                                                ? Map.of(
                                                        "runId",
                                                        "r_deadline",
                                                        "status",
                                                        "RUNNING",
                                                        "nodes",
                                                        List.of(
                                                                Map.of(
                                                                        "nodeInstanceId",
                                                                        123,
                                                                        "status",
                                                                        "RUNNING")))
                                                : authority));
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        i -> {
                            String url = i.getArgument(0);
                            return Json.tree(
                                    url.endsWith("/claim")
                                            ? authority
                                            : url.endsWith("/submitted")
                                                    ? Map.of("submitted", true)
                                                    : Map.of("accepted", true));
                        });
        executor =
                new SkillExecution(
                        db,
                        tx,
                        catalog,
                        new SkillAdapters(
                                new ArtifactStore(db),
                                new AlgorithmAdapter(db),
                                new HttpAsyncAdapter(db)),
                        clients,
                        "http://engine.test");
    }

    Map<String, Object> contract(long timeoutMs) {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", "deadline_probe");
        c.put("name", "deadline_probe");
        c.put("type", "HTTP");
        c.put("description", "查询总期限贯通验证");
        c.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/submit");
        c.put("invocationKind", "SUBMIT_QUERY");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", timeoutMs);
        c.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        c.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs/{jobId}");
        reconciliation.put("method", "GET");
        reconciliation.put("donePath", "$.status");
        reconciliation.put("doneValue", "DONE");
        reconciliation.put(
                "resultEndpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs-result/{jobId}");
        reconciliation.put("resultEndpointPath", "$");
        c.put("reconciliation", reconciliation);
        return c;
    }

    /**
     * 多阶段耗时：状态请求耗 0.8T，随后结果端点挂起。总期限 T 必须约束整体——
     * 结果请求的读超时是剩余的 0.2T 而不是重置的完整 T；整个查询在远小于 0.8T+T 的时间内
     * 以 RESULT_UNKNOWN 收敛，且结果请求确实已被发出（期限贯通不是跳过阶段）。
     */
    @Test
    @Timeout(40)
    void 多阶段耗时不拖破查询总期限() throws Exception {
        // T=3000ms：状态耗0.8T=2400ms，结果请求的读超时应为剩余~0.2T而非重置的完整T。
        // 修复后总耗时≈3.4s；未修复≈5.4s。阈值4.4s两侧各留约1s调度/预热余量（首轮JVM预热
        // 实测会把固定开销放大~0.5s，已计入余量）。
        long timeoutMs = 3000;
        startSlowPeer(2400); // 0.8T
        prepareExecution(timeoutMs);
        phaseHits.add("start@" + System.currentTimeMillis());
        long started = System.nanoTime();
        var result = executor.execute("deadline_probe", request);
        long elapsedMs =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("RESULT_UNKNOWN", result.get("errorSemantic"), "总期限耗尽属不确定，保留对账语义");
        assertTrue(resultHits.get() >= 1, "期限贯通后结果请求仍应在剩余期限内被发出");
        System.out.println("R13证据 resultHits=" + resultHits.get() + " elapsedMs=" + elapsedMs + " phases=" + phaseHits);
        // 修复后：~0.8T(状态) + 0.2T(结果剩余读超时) ≈ T=1200ms。
        // 未修复时结果请求会重置完整读超时 T：~960+1200=2160ms。断言取两值之间，留足调度余量。
        assertTrue(elapsedMs < 4400, "实际耗时 " + elapsedMs + "ms 超过总期限约束（T=3000ms，修复后预期≈3.4s）");
    }

    /** 已耗尽的期限在发出请求前即拒绝：不建立任何连接，语义保持 RESULT_UNKNOWN。 */
    @Test
    void 已耗尽期限在发请求前拒绝且不建连() {
        var adapter = new HttpAsyncAdapter(db);
        var contract = Map.<String, Object>of("timeoutMs", 5000);
        String url = "http://127.0.0.1:9/submit";
        var exhausted = assertThrows(
                SkillAdapters.Failure.class,
                () -> adapter.request(contract, url, "GET", null, key, System.nanoTime() - 1));
        assertEquals("RESULT_UNKNOWN", exhausted.semantic, "期限耗尽属不确定证据");
        assertTrue(exhausted.getMessage().contains("查询总期限已耗尽"));
        // null 期限（提交路径）与未耗尽期限不抛异常，返回请求规格（不建立连接）。
        assertDoesNotThrow(() -> adapter.request(contract, url, "GET", null, key, null));
        assertDoesNotThrow(
                () -> adapter.request(contract, url, "GET", null, key, System.nanoTime() + 1_000_000_000L));
    }
}
