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
 * PLAT-05：适配器错误必须落成方案 §4.5 的统一七字段结构。
 * 未声明 errorSemantics 时平台默认映射生效（含路线三桥 envelope ok:false 403 → NO_PERMISSION，
 * category=AUTH、retryable=false、中文 userMessage）；声明 codePath/messagePath/retryableCodes
 * 时声明优先于 HTTP 状态默认分类。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillErrorEnvelopeTest {
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
                        .subject("error-envelope-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MDC.put("traceId", "trace-error-envelope");
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

    void prepare(String code, Map<String, Object> errorSemantics) {
        this.code = code;
        var contract = contract();
        if (errorSemantics != null) contract.put("errorSemantics", errorSemantics);
        var created = catalog.create(contract);
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
                                "r_error",
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
                                "r_error",
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
        b.put("name", "错误信封测试技能");
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

    static void assertSevenFields(JsonNode error) {
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
    }

    @Test
    void bridgeEnvelope403BecomesReadableAuthRejection() {
        // 路线三桥：业务系统返回自定义 envelope，HTTP 403 + ok:false + 业务码/中文原因。
        prepare("bridge_" + UUID.randomUUID().toString().replace("-", ""), null);
        responseStatus = 403;
        responseBody =
                "{\"ok\":false,\"code\":\"DATA_SCOPE_DENIED\",\"message\":\"无权访问其他部门数据\"}";
        var result = executor.execute(code, request);
        assertEquals("FAILED", result.get("status"));
        assertEquals("NO_PERMISSION", result.get("errorSemantic"));
        var error = Json.tree(result.get("result")).path("error");
        assertSevenFields(error);
        assertEquals("NO_PERMISSION", error.path("semantic").asText());
        assertEquals("DATA_SCOPE_DENIED", error.path("code").asText());
        assertEquals("AUTH", error.path("category").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(
                error.path("userMessage").asText().contains("无权访问其他部门数据"),
                "userMessage 必须携带远端中文原因: " + error.path("userMessage").asText());
        assertEquals("无权访问其他部门数据", error.path("detail").path("raw").asText());
        assertEquals("trace-error-envelope", error.path("traceId").asText());
    }

    @Test
    void declaredErrorSemanticsRetryableCodeOverridesHttpDefault() {
        // HTTP 400 默认 PARAM_ERROR（不可重试）；声明的 retryableCodes 命中远端码后改判可重试。
        prepare("declared_" + UUID.randomUUID().toString().replace("-", ""),
                Map.of(
                        "codePath",
                        "$.code",
                        "messagePath",
                        "$.message",
                        "retryableCodes",
                        List.of("TIMEOUT")));
        responseStatus = 400;
        responseBody = "{\"code\":\"TIMEOUT\",\"message\":\"上游超时\"}";
        var result = executor.execute(code, request);
        assertEquals("RETRYABLE_FAILURE", result.get("errorSemantic"));
        var error = Json.tree(result.get("result")).path("error");
        assertSevenFields(error);
        assertEquals("RETRYABLE_FAILURE", error.path("semantic").asText());
        assertTrue(error.path("retryable").asBoolean());
        assertEquals("TIMEOUT", error.path("code").asText());
        assertEquals("TRANSPORT", error.path("category").asText());
        assertTrue(error.path("userMessage").asText().contains("上游超时"));
        assertEquals("上游超时", error.path("detail").path("raw").asText());
    }

    @Test
    void untrackedBusinessCodeKeepsHttpDefaultSemantics() {
        // 声明 retryableCodes 未命中时保持 HTTP 状态默认分类，仅补充可读 code/userMessage。
        prepare("mixed_" + UUID.randomUUID().toString().replace("-", ""),
                Map.of(
                        "codePath",
                        "$.code",
                        "messagePath",
                        "$.message",
                        "retryableCodes",
                        List.of("TIMEOUT")));
        responseStatus = 400;
        responseBody = "{\"code\":\"BAD_INPUT\",\"message\":\"区域编码不存在\"}";
        var result = executor.execute(code, request);
        assertEquals("PARAM_ERROR", result.get("errorSemantic"));
        var error = Json.tree(result.get("result")).path("error");
        assertEquals("PARAM", error.path("category").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertEquals("BAD_INPUT", error.path("code").asText());
        assertTrue(error.path("userMessage").asText().contains("区域编码不存在"));
    }
}
