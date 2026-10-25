package cn.agentplatform.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真正经过SecurityFilterChain验签与网关策略，不用伪造已认证SecurityContext。外部依赖限隔离HTTP端点。 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationSecurityTest {
    static final String SECRET = "dedicated-integration-test-only-secret-000000000";
    static final AtomicReference<Map<String, Object>> credential = new AtomicReference<>();
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
        fixture.createContext(
                "/internal/integration-credentials/1",
                exchange -> {
                    byte[] json =
                            Json.write(ApiResponse.ok(credential.get()))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        fixture.createContext(
                "/internal/integration-origins",
                exchange -> {
                    byte[] json =
                            Json.write(
                                            ApiResponse.ok(
                                                    Map.of(
                                                            "allowed",
                                                            exchange.getRequestURI()
                                                                    .getRawQuery()
                                                                    .contains("partner.example"))))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        fixture.createContext(
                "/v1/runs/other_run",
                exchange -> {
                    byte[] json =
                            Json.write(ApiResponse.ok(Map.of("appCode", "other")))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        fixture.createContext(
                "/v1/apps/ops/runs",
                exchange -> {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    JsonNode claims =
                            Json.read(
                                    new String(
                                            Base64.getUrlDecoder()
                                                    .decode(
                                                            authorization.substring(7)
                                                                    .split("\\.")[1]),
                                            StandardCharsets.UTF_8));
                    byte[] json =
                            Json.write(
                                            ApiResponse.ok(
                                                    Map.of(
                                                            "runId",
                                                            "r_verified",
                                                            "principalId",
                                                            claims.path("sub").asText(),
                                                            "tokenUse",
                                                            claims.path("token_use").asText())))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        fixture.createContext(
                "/admin/debug-runs",
                exchange -> {
                    byte[] json =
                            Json.write(
                                            new ApiResponse<>(
                                                    "ENGINE_BUSY",
                                                    "请稍后重试",
                                                    Map.of("retryAfterSeconds", 3),
                                                    null))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Retry-After", "3");
                    exchange.getResponseHeaders().set("X-Internal-Owner", "must-not-leak");
                    exchange.sendResponseHeaders(429, json.length);
                    exchange.getResponseBody().write(json);
                    exchange.close();
                });
        fixture.start();
        String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
        properties.add(
                "agent.security.jwt-secret",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "AGENT_JWT_SECRET",
                                        "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY"));
        System.setProperty("agent.jwt.secret", "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY");
        properties.add("agent.services.app", () -> base);
        properties.add("agent.services.engine", () -> base);
    }

    @BeforeEach
    void reset() {
        String ref =
                SecretVault.put(SECRET, new Actor("tenant-a", "admin", Set.of("ADMIN"), Map.of()));
        credential.set(
                Map.of(
                        "id",
                        1,
                        "tenantId",
                        "tenant-a",
                        "appCode",
                        "ops",
                        "bizSystemCode",
                        "partner",
                        "status",
                        "ACTIVE",
                        "secretRef",
                        ref,
                        "allowedOrigins",
                        List.of("https://partner.example"),
                        "claimLimits",
                        Map.of(
                                "principalIds",
                                List.of("alice"),
                                "roles",
                                List.of("operator", "ADMIN"),
                                "attributes",
                                Map.of("region", List.of("A")))));
    }

    @AfterAll
    static void stop() {
        fixture.stop(0);
        System.clearProperty("agent.master.key");
        System.clearProperty("agent.secret.dir");
    }

    private String ticket() throws Exception {
        String response =
                mvc.perform(
                                post("/embed/ticket")
                                        .header("Authorization", "ApiKey 1:" + SECRET)
                                        .contentType("application/json")
                                        .content(
                                                "{\"userId\":\"alice\",\"roles\":[\"operator\",\"ADMIN\"]}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return Json.read(response).path("data").path("ticket").asText();
    }

    @Test
    void refusesInvalidSecretBeforeIssuingTicket() throws Exception {
        mvc.perform(
                        post("/embed/ticket")
                                .header("Authorization", "ApiKey 1:wrong")
                                .contentType("application/json")
                                .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void boundedEngineRetryAfterSurvivesGatewayWithoutOpeningOtherHeaders() throws Exception {
        String token =
                ServiceTokens.userToken(
                        new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of()), 120);
        mvc.perform(
                        post("/admin/debug-runs")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content("{\"appCode\":\"demo\",\"inputs\":{}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(header().doesNotExist("X-Internal-Owner"))
                .andExpect(jsonPath("$.code").value("ENGINE_BUSY"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(3));
    }

    @Test
    void validTicketCannotAccessAdminOrOtherAppOrOtherAppRun() throws Exception {
        String token = ticket();
        mvc.perform(get("/admin/apps").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/v1/apps/other/runs")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/runs/other_run").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void revocationAndPolicyChangeImmediatelyRejectExistingTicket() throws Exception {
        String token = ticket();
        var changed = new HashMap<>(credential.get());
        changed.put("status", "REVOKED");
        credential.set(changed);
        mvc.perform(
                        post("/v1/apps/ops/runs")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        changed.put("status", "ACTIVE");
        changed.put("allowedOrigins", List.of("https://new.example"));
        credential.set(changed);
        mvc.perform(
                        post("/v1/apps/ops/runs")
                                .header("Authorization", "Bearer " + token)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsRegisteredOriginAndRejectsUnknownOrigin() throws Exception {
        mvc.perform(
                        options("/v1/apps/ops/runs")
                                .header("Origin", "https://partner.example")
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string("Access-Control-Allow-Origin", "https://partner.example"));
        mvc.perform(
                        options("/v1/apps/ops/runs")
                                .header("Origin", "https://evil.example")
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void directServiceApiAuthenticatesApiKeyThenPassesOnlyScopedInternalTicket() throws Exception {
        mvc.perform(
                        post("/v1/apps/ops/runs")
                                .header("Authorization", "ApiKey 1:" + SECRET)
                                .header("X-User-Id", "alice")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("partner:alice"))
                .andExpect(jsonPath("$.data.tokenUse").value("service"));
        mvc.perform(
                        post("/v1/apps/ops/runs")
                                .header("Authorization", "ApiKey 1:wrong")
                                .header("X-User-Id", "alice")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
