package cn.agentplatform.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * INT-04 负例：短票绑定 environment 上限；客户端自报 tenant/principal/pageContext 身份即拒绝；
 * 来源、跨应用、撤权、环境收紧、接入档案不可达都必须给出 §6 登记错误码，而不是降级或 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationHostTicketTest {
    static final String SECRET = "int-04-host-ticket-test-only-secret-000000";
    static final String TICKET = "ApiKey 1:" + SECRET;
    /** 与 agent.security.jwt-secret 相同，用来构造"过期但签名正确"的票；密钥不出测试。 */
    static final String JWT_SECRET = "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY";
    static final AtomicReference<Map<String, Object>> credential = new AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicInteger upstreamCalls = new java.util.concurrent.atomic.AtomicInteger();
    static HttpServer fixture;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) throws Exception {
        System.setProperty(
                "agent.master.key",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        System.setProperty(
                "agent.secret.dir",
                System.getProperty("user.dir") + "/../.local/gateway-test-secrets");
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/internal/integration-credentials/1", exchange -> reply(exchange, 200, ApiResponse.ok(credential.get())));
        // 接入档案源不可达：真实故障是 5xx/连接失败，不能被当作"凭据无效"让业务方反复重试。
        fixture.createContext("/internal/integration-credentials/2", exchange -> reply(exchange, 500, Map.of("code", "BOOM", "message", "down")));
        fixture.createContext(
                "/internal/integration-origins",
                exchange -> reply(exchange, 200, ApiResponse.ok(Map.of("allowed", exchange.getRequestURI().getRawQuery().contains("partner.example")))));
        fixture.createContext("/v1/apps/ops/runs", exchange -> {
            upstreamCalls.incrementAndGet();
            reply(exchange, 200, ApiResponse.ok(runClaims(exchange)));
        });
        fixture.createContext("/v1/runs/r_ops", exchange -> reply(exchange, 200, ApiResponse.ok(Map.of("appCode", "ops"))));
        fixture.createContext("/v1/runs/other_run", exchange -> reply(exchange, 200, ApiResponse.ok(Map.of("appCode", "other"))));
        fixture.start();
        String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
        // 本用例只验证票据签发/校验，不验证生产 iam 链路；dev-hmac 是仓库既有的本地装配模式，
        // 但必须同时给出回环绑定与 dev-auth 才允许启动（SecurityConfiguration 启动门）。
        properties.add("agent.security.mode", () -> "dev-hmac");
        properties.add("agent.security.service-id", () -> "gateway");
        properties.add("agent.dev-auth", () -> "true");
        properties.add("server.address", () -> "127.0.0.1");
        properties.add("agent.security.jwt-secret", () -> JWT_SECRET);
        System.setProperty("agent.jwt.secret", JWT_SECRET);
        properties.add("agent.services.app", () -> base);
        properties.add("agent.services.engine", () -> base);
    }

    @BeforeEach
    void reset() {
        credential.set(credential("ACTIVE", List.of("prod")));
    }

    @Test
    void ticketBindsTenantPrincipalAppAndEnvironment() throws Exception {
        String ticket = ticket(body(Map.of("userId", "alice", "roles", List.of("operator"), "environment", "prod")));
        JsonNode claims = claims(ticket);
        assertEquals("integration", claims.path("token_use").asText());
        assertEquals("partner:alice", claims.path("sub").asText());
        assertEquals("tenant-a", claims.path("tenant_id").asText());
        assertEquals("ops", claims.path("app_code").asText());
        assertEquals("partner", claims.path("biz_system_code").asText());
        assertEquals("prod", claims.path("attributes").path("environment").path(0).asText());
        // 平台角色即使被签发者写进请求也不进入票据。
        assertFalse(claims.path("roles").toString().contains("ADMIN"));
    }

    @Test
    void environmentIsRequiredOnceTheCredentialDeclaresACeiling() throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "https://partner.example")
                        .content(body(Map.of("userId", "alice", "roles", List.of("operator")))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("CLAIM_LIMIT_EXCEEDED", envelope(result).path("code").asText());
    }

    @Test
    void environmentOutsideTheCeilingIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "https://partner.example")
                        .content(body(Map.of("userId", "alice", "environment", "staging"))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("CLAIM_LIMIT_EXCEEDED", envelope(result).path("code").asText());
    }

    @Test
    void clientSuppliedTenantOrPrincipalIsRejectedInsteadOfSilentlyOverridden() throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "https://partner.example")
                        .content(body(Map.of("userId", "alice", "environment", "prod", "tenantId", "tenant-b"))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("INTEGRATION_CLIENT_IDENTITY_REJECTED", envelope(result).path("code").asText());
    }

    @Test
    void pageContextCannotCarryIdentityIntoTheActor() throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "https://partner.example")
                        .content(body(Map.of("userId", "alice", "environment", "prod",
                                "pageContext", Map.of("userId", "mallory")))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("INTEGRATION_CLIENT_IDENTITY_REJECTED", envelope(result).path("code").asText());
    }

    @Test
    void directApiKeyRequestCannotSelfDeclareTenant() throws Exception {
        MvcResult result = mvc.perform(post("/v1/apps/ops/runs")
                .header("Authorization", TICKET).header("Origin", "https://partner.example")
                .header("X-User-Id", "alice").header("X-User-Tenant", "tenant-b"))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("INTEGRATION_CLIENT_IDENTITY_REJECTED", envelope(result).path("code").asText());
    }

    @Test
    void unknownOriginUsesTheRegisteredSourceCode() throws Exception {
        // 部署级 CORS 放行 localhost 开发源，但该源不在接入档案 allowedOrigins 内：
        // 此时请求会到达控制器，必须由 IntegrationPolicy 给出 §6 的 source 错误码。
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "http://localhost:15173")
                        .content(body(Map.of("userId", "alice", "environment", "prod"))))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("INTEGRATION_SOURCE_NOT_ALLOWED", envelope(result).path("code").asText());
    }

    @Test
    void revocationInvalidatesALiveTicketWithTheExpiredCode() throws Exception {
        String ticket = ticket(body(Map.of("userId", "alice", "environment", "prod")));
        credential.set(credential("REVOKED", List.of("prod")));
        MvcResult result = run(ticket, "/v1/apps/ops/runs");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("INTEGRATION_TICKET_EXPIRED", envelope(result).path("code").asText());
    }

    @Test
    void tighteningTheEnvironmentCeilingInvalidatesALiveTicket() throws Exception {
        String ticket = ticket(body(Map.of("userId", "alice", "environment", "prod")));
        credential.set(credential("ACTIVE", List.of("staging")));
        MvcResult result = run(ticket, "/v1/apps/ops/runs");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("INTEGRATION_TICKET_EXPIRED", envelope(result).path("code").asText());
    }

    @Test
    void ticketStaysInsideItsBoundApplication() throws Exception {
        String ticket = ticket(body(Map.of("userId", "alice", "environment", "prod")));
        MvcResult result = run(ticket, "/v1/apps/other/runs");
        assertEquals(403, result.getResponse().getStatus());
        assertEquals("APP_BINDING_DENIED", envelope(result).path("code").asText());
    }

    @Test
    void unreachableCredentialSourceIsAnExplainableBlockNotAnInvalidCredential() throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "ApiKey 2:" + SECRET).header("Origin", "https://partner.example")
                        .content(body(Map.of("userId", "alice", "environment", "prod"))))
                .andReturn();
        assertEquals(503, result.getResponse().getStatus());
        assertEquals("INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", envelope(result).path("code").asText());
    }

    @Test
    void validTicketReachesOnlyItsOwnRunPath() throws Exception {
        String ticket = ticket(body(Map.of("userId", "alice", "environment", "prod")));
        MvcResult result = run(ticket, "/v1/apps/ops/runs");
        assertEquals(200, result.getResponse().getStatus());
        JsonNode data = Json.read(result.getResponse().getContentAsString()).path("data");
        // 下游只看到网关换发的服务票：集成票据不离开网关，主体仍是裁剪后的 business principal。
        assertEquals("service", data.path("tokenUse").asText());
        assertEquals("partner:alice", data.path("principalId").asText());
    }

    @Test
    void twoUsersOfOneBusinessSystemKeepSeparatePrincipals() throws Exception {
        credential.set(credential("ACTIVE", List.of("prod"), List.of("alice", "bob")));
        String alice = ticket(body(Map.of("userId", "alice", "environment", "prod")));
        String bob = ticket(body(Map.of("userId", "bob", "environment", "prod")));
        assertEquals("partner:alice", claims(alice).path("sub").asText());
        assertEquals("partner:bob", claims(bob).path("sub").asText());
        assertEquals("partner:alice", runPrincipal(alice));
        assertEquals("partner:bob", runPrincipal(bob));
        assertNotEquals(alice, bob);
    }

    @Test
    void expiredTicketIsRejectedBeforeAnyBusinessExecution() throws Exception {
        int before = upstreamCalls.get();
        MvcResult result = run(expiredTicket(), "/v1/apps/ops/runs");
        assertEquals(401, result.getResponse().getStatus());
        assertEquals(before, upstreamCalls.get(), "过期票不得触达业务服务");
    }

    private String runPrincipal(String ticket) throws Exception {
        MvcResult result = run(ticket, "/v1/apps/ops/runs");
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return Json.read(result.getResponse().getContentAsString()).path("data").path("principalId").asText();
    }

    private MvcResult run(String ticket, String path) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + ticket)
                        .header("Origin", "https://partner.example"))
                .andReturn();
    }

    /** 签名正确但 exp 已过的集成票：用于验证过期在执行前被拒绝。 */
    private static String expiredTicket() {
        try {
            long now = System.currentTimeMillis() / 1000;
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", "agent-platform-v12");
            claims.put("sub", "partner:alice");
            claims.put("tenant_id", "tenant-a");
            claims.put("aud", "agent-platform-embed");
            claims.put("iat", now - 600);
            claims.put("exp", now - 300);
            claims.put("jti", "expired-ticket-fixture");
            claims.put("token_use", "integration");
            claims.put("roles", List.of("operator"));
            claims.put("attributes", Map.of("environment", List.of("prod")));
            claims.put("app_code", "ops");
            claims.put("biz_system_code", "partner");
            claims.put("user_id", "alice");
            claims.put("credential_id", 1);
            claims.put("policy_digest", "fixture");
            String header = base64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = base64(Json.write(claims));
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
            return header + "." + payload + "." + signature;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String ticket(String json) throws Exception {
        MvcResult result = mvc.perform(post("/embed/ticket").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", TICKET).header("Origin", "https://partner.example")
                        .content(json))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return Json.read(result.getResponse().getContentAsString()).path("data").path("ticket").asText();
    }

    private static JsonNode envelope(MvcResult result) throws Exception {
        return Json.read(result.getResponse().getContentAsString());
    }

    private static JsonNode claims(String ticket) {
        try {
            return Json.read(new String(Base64.getUrlDecoder().decode(ticket.split("\\.")[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("ticket payload is not decodable", e);
        }
    }

    private static String body(Map<String, Object> values) {
        return Json.write(values);
    }

    private static Map<String, Object> credential(String status, List<String> environments) {
        return credential(status, environments, List.of("alice"));
    }

    private static Map<String, Object> credential(String status, List<String> environments, List<String> principals) {
        String ref = SecretVault.put(SECRET, new Actor("tenant-a", "admin", Set.of("ADMIN"), Map.of()));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("region", List.of("A"));
        attributes.put("environment", environments);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("principalIds", principals);
        limits.put("roles", List.of("operator", "ADMIN"));
        limits.put("attributes", attributes);
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", 1);
        credential.put("tenantId", "tenant-a");
        credential.put("appCode", "ops");
        credential.put("bizSystemCode", "partner");
        credential.put("status", status);
        credential.put("secretRef", ref);
        credential.put("allowedOrigins", List.of("https://partner.example"));
        credential.put("claimLimits", limits);
        return credential;
    }

    private static Map<String, Object> runClaims(com.sun.net.httpserver.HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        JsonNode claims = claims(authorization.substring(7));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", "r_ops");
        run.put("principalId", claims.path("sub").asText());
        run.put("tokenUse", claims.path("token_use").asText());
        run.put("appCode", claims.path("app_code").asText());
        return run;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, Object body) throws java.io.IOException {
        byte[] json = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        exchange.getResponseBody().write(json);
        exchange.close();
    }
}
