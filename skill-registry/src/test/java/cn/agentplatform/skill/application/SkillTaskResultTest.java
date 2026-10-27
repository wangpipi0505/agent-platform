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
import java.util.concurrent.atomic.AtomicLong;

/**
 * PLAT-05 T05.2：任务结果读取端点的应用层语义。
 * 失败任务返回完整七字段错误对象；不存在 → 可读 404；未终态 → 可读 409。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillTaskResultTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);
    HttpServer endpoint;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    String code;
    volatile int responseStatus = 200;
    volatile String responseBody = "{\"value\":7}";

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
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void prepare() throws Exception {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("task-result-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MDC.put("traceId", "trace-task-result");
        responseStatus = 200;
        responseBody = "{\"value\":7}";
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext(
                "/tool",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(responseStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        endpoint.start();
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, manager, clients, "http://unused");
        executor =
                new SkillExecution(
                        db,
                        manager,
                        catalog,
                        new SkillAdapters(new ArtifactStore(db), new AlgorithmAdapter(db)),
                        clients,
                        "http://engine.test");
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        MDC.remove("traceId");
        endpoint.stop(0);
    }

    void prepare(String skillCode) {
        this.code = skillCode;
        var created = catalog.create(contract());
        long version = ((Number) created.get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where"
                        + " tenant_id=? and code=?",
                version,
                "skill-tests",
                code);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                ids.incrementAndGet(),
                                "runId",
                                "r_result",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                Map.of("region", "A"),
                                "idempotencyKey",
                                "logical-key",
                                "leaseOwner",
                                "worker"));
        var authority =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "claimed",
                                true,
                                "status",
                                "EXECUTING",
                                "skill_version_id",
                                version,
                                "skill_code",
                                code,
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                "r_result",
                                "trigger_type",
                                "TASK",
                                "idempotency_key",
                                "logical-key",
                                "mock_snapshot",
                                Map.of()));
        authority.put("attempt_id", 456);
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
    }

    Map<String, Object> contract() {
        var b = new LinkedHashMap<String, Object>();
        b.put("code", code);
        b.put("name", "任务结果测试技能");
        b.put("type", "HTTP");
        b.put("description", "能力：返回测试值。适用：测试查询。不适用：修改数据。");
        b.put("endpoint", "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/tool");
        b.put("inputSchema", Map.of("type", "object"));
        b.put("outputSchema", Map.of("type", "object"));
        b.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        true));
        b.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        return b;
    }

    @Test
    void failedTaskResultCarriesCompleteReadableError() {
        prepare("result_" + UUID.randomUUID().toString().replace("-", ""));
        responseStatus = 403;
        responseBody =
                "{\"ok\":false,\"code\":\"DATA_SCOPE_DENIED\",\"message\":\"无权访问其他部门数据\"}";
        executor.execute(code, request);
        long callId = ((Number) request.get("callId")).longValue();
        var view = executor.taskResult(callId);
        assertEquals(callId, ((Number) view.get("callId")).longValue());
        assertEquals("FAILED", view.get("status"));
        JsonNode result = Json.tree(view.get("result"));
        assertEquals("FAILED", result.path("status").asText());
        var error = result.path("error");
        for (String field :
                List.of(
                        "semantic",
                        "code",
                        "category",
                        "retryable",
                        "userMessage",
                        "detail",
                        "traceId"))
            assertTrue(error.has(field), "错误对象缺少字段: " + field);
        assertEquals("NO_PERMISSION", error.path("semantic").asText());
        assertEquals("DATA_SCOPE_DENIED", error.path("code").asText());
        assertEquals("AUTH", error.path("category").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(error.path("userMessage").asText().contains("无权访问其他部门数据"));
    }

    @Test
    void unknownCallIdIsAReadable404() {
        prepare("unknown_" + UUID.randomUUID().toString().replace("-", ""));
        var denied =
                assertThrows(
                        PlatformException.class,
                        () -> executor.taskResult(Math.abs(UUID.randomUUID().getMostSignificantBits())));
        assertEquals(404, denied.status());
        assertEquals("FACT_NOT_FOUND", denied.code());
    }

    @Test
    void unfinishedTaskIsAReadable409InsteadOfBlankResult() {
        prepare("unfinished_" + UUID.randomUUID().toString().replace("-", ""));
        long callId = ids.incrementAndGet();
        db.update(
                "insert into"
                        + " skill.execution_fact(tenant_id,call_id,skill_version_id,principal_id,run_id,attempt_id,execution_owner,args_digest,skill_contract_sha256,request_snapshot,status,result,duration_ms,attempt_duration_ms,is_mock,call_kind)"
                        + " values(?,?,?,?,?,?,?,?,?,?::jsonb,'UNKNOWN',?::jsonb,0,0,false,'FORMAL')",
                "skill-tests",
                callId,
                ((Number) request.get("skillVersionId")).longValue(),
                "task-result-tester",
                "r_result",
                1,
                "worker",
                Digest.sha256(Json.canonical(Map.of("region", "A"))),
                "sha",
                Json.write(Map.of("modelSummary", "等待对账")),
                Json.write(Map.of("modelSummary", "等待对账")));
        var conflict = assertThrows(PlatformException.class, () -> executor.taskResult(callId));
        assertEquals(409, conflict.status());
        assertEquals("TASK_NOT_FINAL", conflict.code());
    }
}
