package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAT-04 外部任务态：远端任务标识、终态映射与轮询幂等。
 *
 * <p>HTTP 服务只是故障注入桩；绑定/事实/轮询全部落在真实 PostgreSQL 上，断言的是 PG 中的权威行，而不是内存状态。
 * 与 {@link AsyncHttpExecutionTest} 共用同一个测试库（Flyway 幂等），但每条用例使用独立 tenant，互不共享行。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ExternalJobStateTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;

    String tenant, code, key;
    long call;
    HttpServer server;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    Map<String, Object> authority;
    final AtomicInteger submits = new AtomicInteger(), queries = new AtomicInteger();
    /** 远端任务态；SUBMITTED 提交成功，RUNNING 未终结，FAILED 终态失败，DONE 终态成功。 */
    volatile String remoteState = "RUNNING";
    /** 模拟"异步提交成功但没有回 taskId"的远端。 */
    volatile boolean omitTaskId;
    volatile String remoteError = "remote checksum mismatch: expected 9f2 got 1a3";

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
        tenant = "extjob_" + UUID.randomUUID().toString().replace("-", "");
        code = "extjob";
        key = "key/" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        remoteState = "RUNNING";
        omitTaskId = false;
        authenticate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", this::submit);
        server.createContext("/jobs", this::jobs);
        server.start();
    }

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        SecurityContextHolder.clearContext();
    }

    void authenticate() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("owner")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    void submit(HttpExchange exchange) throws java.io.IOException {
        submits.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        var ack = new LinkedHashMap<String, Object>();
        if (!omitTaskId) {
            ack.put("jobId", "job-1");
            ack.put("idempotencyKey", key);
        }
        respond(exchange, 202, ack);
    }

    void jobs(HttpExchange exchange) throws java.io.IOException {
        queries.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        var body = new LinkedHashMap<String, Object>();
        body.put("jobId", "job-1");
        body.put("idempotencyKey", key);
        body.put("status", remoteState);
        if ("FAILED".equals(remoteState)) body.put("errorMessage", remoteError);
        // 结果必须落在 $.result：契约的结果读取路径默认为该位置，模拟真实异步服务。
        if ("DONE".equals(remoteState)) body.put("result", Map.of("value", 7));
        respond(exchange, 200, body);
    }

    static void respond(HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    Map<String, Object> contract() {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", code);
        c.put("name", "external job");
        c.put("type", "HTTP");
        c.put("description", "外部异步任务");
        c.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/submit");
        c.put("invocationKind", "SUBMIT_QUERY");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", 100);
        c.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        c.put("guard", Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs/{jobId}");
        reconciliation.put("method", "GET");
        reconciliation.put("donePath", "$.status");
        reconciliation.put("doneValue", "DONE");
        reconciliation.put("failedValue", "FAILED");
        reconciliation.put("failureMessagePath", "$.errorMessage");
        c.put("reconciliation", reconciliation);
        return c;
    }

    void prepareExecution() {
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
        long version = ((Number) catalog.create(contract()).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId", call,
                                "runId", "r_extjob",
                                "nodeInstanceId", 321,
                                "attemptId", 654,
                                "skillVersionId", version,
                                "arguments", Map.of("region", "A"),
                                "idempotencyKey", key,
                                "leaseOwner", "worker"));
        authority =
                new LinkedHashMap<>(
                        Map.of(
                                "claimed", true,
                                "status", "UNKNOWN",
                                "skill_version_id", version,
                                "skill_code", code,
                                "args_digest", Digest.sha256(Json.canonical(Map.of("region", "A"))),
                                "node_instance_id", 321,
                                "runId", "r_extjob",
                                "trigger_type", "TASK",
                                "idempotency_key", key,
                                "mock_snapshot", Map.of()));
        authority.put("callId", call);
        authority.put("attempt_id", 654);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        when(clients.get(anyString())).thenAnswer(i -> Json.tree(authority));
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
                                new ArtifactStore(db), new AlgorithmAdapter(db), new HttpAsyncAdapter(db)),
                        clients,
                        "http://engine.test");
    }

    String factStatus() {
        return db.queryForObject(
                "select status from skill.execution_fact where tenant_id=? and call_id=?",
                String.class,
                tenant,
                call);
    }

    Map<String, Object> bindingRow() {
        return db.queryForMap(
                "select status, external_job_id, external_state, submitted_at, last_polled_at,"
                        + " remote_error_code, remote_error_message from skill.external_job_binding"
                        + " where tenant_id=? and call_id=?",
                tenant,
                call);
    }

    Map<String, Object> fact() {
        return executor.fact(call);
    }

    // ---------- 1. 提交成功但远端不回 taskId：必须失败，不得静默成功 ----------
    @Test
    void missingRemoteTaskIdFailsSubmissionInsteadOfUnknown() {
        omitTaskId = true;
        prepareExecution();
        var result = executor.execute(code, request);
        assertEquals("FAILED", result.get("status"));
        assertEquals("SUBMIT_NO_TASK_ID", result.get("errorSemantic"));
        assertEquals(0, queries.get());
        assertEquals("FAILED", factStatus());
        var binding = bindingRow();
        assertEquals("FAILED", binding.get("status"));
        assertNull(binding.get("external_job_id"));
        assertEquals("SUBMIT_NO_TASK_ID", binding.get("remote_error_code"));
    }

    // ---------- 2. 远端 failed 终态：envelope 带远端原文，run 落 FAILED ----------
    @Test
    void remoteFailureCarriesRemoteCodeAndMessageIntoFact() {
        prepareExecution();
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        remoteState = "FAILED";
        var result = executor.reconcile(call);
        assertEquals("FAILED", result.get("status"));
        var envelope = Json.tree(result.get("result"));
        assertEquals("REMOTE_FAILED", envelope.path("error").path("semantic").asText());
        assertTrue(
                envelope.path("error").path("reason").asText().contains(remoteError),
                "远端原文必须进入 error.reason");
        var external = envelope.path("externalJob");
        assertEquals("job-1", external.path("remoteJobId").asText());
        assertEquals("FAILED", external.path("state").asText());
        assertEquals("外部任务终态失败：" + remoteError, external.path("remoteMessage").asText());
        var binding = bindingRow();
        assertEquals("FAILED", binding.get("status"));
        assertEquals("FAILED", binding.get("external_state"));
        assertEquals("REMOTE_FAILED", binding.get("remote_error_code"));
        assertEquals("外部任务终态失败：" + remoteError, binding.get("remote_error_message"));
        assertEquals("FAILED", factStatus());
    }

    // ---------- 3. 远端一直 running：仍 UNKNOWN，但 remoteJobId 与 lastPolledAt 可见 ----------
    @Test
    void runningRemoteJobStaysUnknownButVisible() {
        prepareExecution();
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        var result = executor.reconcile(call);
        assertEquals("UNKNOWN", result.get("status"));
        assertEquals("UNKNOWN", factStatus());
        var envelope = Json.read(fact().get("result").toString());
        var external = envelope.path("externalJob");
        assertEquals("job-1", external.path("remoteJobId").asText());
        assertEquals("RUNNING", external.path("state").asText());
        assertFalse(external.path("submittedAt").isNull(), "提交时间必须可见");
        assertFalse(external.path("lastPolledAt").isNull(), "最后轮询时间必须可见");
        var binding = bindingRow();
        assertEquals("SUBMITTED", binding.get("status"));
        assertEquals("RUNNING", binding.get("external_state"));
        assertNotNull(binding.get("last_polled_at"));
    }

    // ---------- 4. 同一 callId 重复轮询：幂等，不重复写 binding ----------
    @Test
    void repeatedPollIsIdempotent() {
        prepareExecution();
        executor.execute(code, request);
        executor.reconcile(call);
        executor.reconcile(call);
        assertEquals(1, submits.get(), "不得重复提交业务请求");
        assertEquals(1, count("skill.external_job_binding"));
        assertEquals(1, count("skill.execution_fact"));
        assertEquals("job-1", bindingRow().get("external_job_id"));
    }

    int count(String table) {
        return db.queryForObject(
                "select count(*) from " + table + " where tenant_id=? and call_id=?",
                Integer.class,
                tenant,
                call);
    }

    // ---------- 5. 回滚开关关闭：行为回到当前 ----------
    @Test
    void disabledFlagKeepsLegacyUnknownBehavior() {
        prepareExecution();
        executor.externalJobEnabled = false;
        omitTaskId = true;
        var result = executor.execute(code, request);
        assertEquals("UNKNOWN", result.get("status"));
        assertEquals("RESULT_UNKNOWN", result.get("errorSemantic"));
        assertTrue(Json.tree(result.get("result")).path("externalJob").isNull());
    }

    @Test
    void disabledFlagOmitsExternalJobFromRunningFact() {
        prepareExecution();
        executor.externalJobEnabled = false;
        executor.execute(code, request);
        assertEquals("UNKNOWN", executor.reconcile(call).get("status"));
        assertTrue(Json.read(fact().get("result").toString()).path("externalJob").isNull());
        assertEquals("RUNNING", bindingRow().get("external_state"));
    }

    // ---------- 6. 脏数据：事实已终态时，迟到的轮询不得把终态改回 UNKNOWN/WAITING ----------
    @Test
    void terminalFactIsNotReopenedByLatePoll() {
        prepareExecution();
        executor.execute(code, request);
        remoteState = "DONE";
        assertEquals("SUCCEEDED", executor.reconcile(call).get("status"));
        remoteState = "RUNNING";
        var second = executor.reconcile(call);
        assertEquals("SUCCEEDED", second.get("status"));
        assertEquals("SUCCEEDED", factStatus());
        assertEquals("SUCCEEDED", bindingRow().get("external_state"));
        assertEquals(1, submits.get());
    }

    // ---------- 7. 远端失败原文有界：超长 message 截断，但保留可识别前缀 ----------
    @Test
    void remoteFailureMessageIsBounded() {
        prepareExecution();
        executor.execute(code, request);
        remoteState = "FAILED";
        remoteError = "E" + "x".repeat(4096);
        var result = executor.reconcile(call);
        assertEquals("FAILED", result.get("status"));
        var reason = Json.tree(result.get("result")).path("error").path("reason").asText();
        assertTrue(reason.startsWith("外部任务终态失败：E"));
        assertTrue(reason.length() <= 640, "失败原文必须截断到有界长度，实际 " + reason.length());
        assertTrue(
                String.valueOf(bindingRow().get("remote_error_message")).length() <= 512);
    }
}
