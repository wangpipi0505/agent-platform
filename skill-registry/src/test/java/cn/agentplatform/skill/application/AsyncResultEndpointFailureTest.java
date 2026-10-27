package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PLAT-05 实现点3：结果端点返回 409/4xx 时先复查状态端点，能确定终态失败则产出
 * REMOTE_FAILED + 远端原因，绝不把 409 直接折叠成"结果未知"抛给用户。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AsyncResultEndpointFailureTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    String tenant, code, key;
    long call;
    HttpServer server;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    final AtomicInteger queries = new AtomicInteger();
    volatile int resultStatus = 409;
    volatile String resultCode = "TLE_CHECKSUM_INVALID";

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
        tenant = "asyncfail_" + UUID.randomUUID();
        code = "asyncfail";
        key = "key/" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("asyncfail-owner")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MDC.put("traceId", "trace-async-result-409");
        queries.set(0);
        resultStatus = 409;
        resultCode = "TLE_CHECKSUM_INVALID";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/submit",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "jobId",
                                                    "job-1",
                                                    "idempotencyKey",
                                                    key))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/jobs",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    int nth = queries.incrementAndGet();
                    var poll = new LinkedHashMap<String, Object>();
                    poll.put("jobId", "job-1");
                    poll.put("idempotencyKey", key);
                    if (nth == 1) {
                        // 第一次轮询：任务已完成，按 done 值引导执行器去取独立结果端点。
                        poll.put("status", "DONE");
                        poll.put("result", Map.of("value", 7));
                    } else {
                        // 复查（结果端点 409 之后）：远端已确认终态失败并给出业务码与原文。
                        poll.put("status", "FAILED");
                        poll.put(
                                "errorMessage",
                                "wrong checksum of TLE line 1, expected 3 but got 6");
                        poll.put("code", resultCode);
                    }
                    byte[] body = Json.write(poll).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/jobs-result",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            "{\"error\":\"result pruned\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(resultStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        MDC.remove("traceId");
        server.stop(0);
        // 与 AsyncHttpExecutionTest 同口径：事实与绑定按租户清掉；已发布技能版本受
        // protect_published_contract 触发器保护且租户唯一，留给库内不清理（不跨用例可见）。
        db.update("delete from skill.execution_fact where tenant_id=?", tenant);
        db.update("delete from skill.external_job_binding where tenant_id=?", tenant);
    }

    void prepareExecution() {
        long version = ((Number) catalog.create(contract()).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_asyncfail",
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
                                code,
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                "r_asyncfail",
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
                                                        "r_asyncfail",
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

    Map<String, Object> contract() {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", code);
        c.put("name", "asyncfail");
        c.put("type", "HTTP");
        c.put("description", "查询任务结果");
        c.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/submit");
        c.put("invocationKind", "SUBMIT_QUERY");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", 5000);
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
        // PLAT-05：契约声明远端业务码/原因的读取路径，REMOTE_FAILED 需要携带稳定业务码。
        c.put("errorSemantics", Map.of("codePath", "$.code", "messagePath", "$.message"));
        var reconciliation = new LinkedHashMap<String, Object>();
        reconciliation.put(
                "endpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs/{jobId}");
        reconciliation.put("method", "GET");
        reconciliation.put("donePath", "$.status");
        reconciliation.put("doneValue", "DONE");
        reconciliation.put("failedValue", "FAILED");
        reconciliation.put("failureMessagePath", "$.errorMessage");
        reconciliation.put(
                "resultEndpoint",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jobs-result/{jobId}");
        reconciliation.put("resultEndpointPath", "$");
        c.put("reconciliation", reconciliation);
        return c;
    }

    @Test
    void resultEndpoint409RechecksStatusAndReportsRemoteFailed() {
        prepareExecution();
        var result = executor.execute(code, request);
        assertEquals(
                "REMOTE_FAILED",
                result.get("errorSemantic"),
                "结果端点 409 不能折叠成 RESULT_UNKNOWN，必须复查状态端点后给终态失败");
        JsonNode error = Json.tree(result.get("result")).path("error");
        assertEquals("REMOTE_FAILED", error.path("semantic").asText());
        assertEquals("TLE_CHECKSUM_INVALID", error.path("code").asText());
        assertEquals("BUSINESS", error.path("category").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(
                error.path("userMessage").asText().contains("wrong checksum"),
                "userMessage 必须携带远端原文: " + error.path("userMessage").asText());
        assertTrue(error.path("detail").path("raw").asText().contains("wrong checksum"));
        assertEquals("trace-async-result-409", error.path("traceId").asText());
        // 绑定行同样收敛为已核实失败，供引擎与运行详情直接读取。
        var binding =
                db.queryForList(
                        "select status,external_state,remote_error_code from"
                                + " skill.external_job_binding where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals(1, binding.size());
        assertEquals("FAILED", binding.getFirst().get("status"));
        assertEquals("REMOTE_FAILED", binding.getFirst().get("remote_error_code"));
    }
}
