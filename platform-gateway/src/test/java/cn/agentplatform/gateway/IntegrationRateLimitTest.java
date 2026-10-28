package cn.agentplatform.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.application.IntegrationAccess;
import cn.agentplatform.gateway.domain.IntegrationRateLimit;

import com.fasterxml.jackson.databind.JsonNode;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T18.1 剩余段（PLAT-18）：/integration 业务入口按凭据维度的滑动窗口限流。
 * 与 IntegrationSecurityTest 同款纪律：真正经过 SecurityFilterChain 验签与网关策略，
 * 外部依赖限隔离 HTTP 端点；限流器状态经装配缝在每个用例前归零，互不污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationRateLimitTest {
    static final String SECRET1 = "dedicated-rate-limit-test-secret-one-0000000001";
    static final String SECRET2 = "dedicated-rate-limit-test-secret-two-0000000002";
    static final AtomicReference<Map<String, Object>> credential1 = new AtomicReference<>();
    static final AtomicReference<Map<String, Object>> credential2 = new AtomicReference<>();
    static HttpServer fixture;
    @Autowired MockMvc mvc;
    @Autowired IntegrationAccess access;

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T08:00:00Z"));

        void advanceSeconds(long seconds) { now.updateAndGet(t -> t.plusSeconds(seconds)); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    static final MutableClock clock = new MutableClock();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) throws Exception {
        System.setProperty(
                "agent.master.key",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        System.setProperty(
                "agent.secret.dir",
                System.getProperty("user.dir") + "/../.local/gateway-test-secrets");
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of("/internal/integration-credentials/1", "/internal/integration-credentials/2")) {
            var holder = path.endsWith("/2") ? credential2 : credential1;
            fixture.createContext(
                    path,
                    exchange -> {
                        byte[] json =
                                Json.write(ApiResponse.ok(holder.get()))
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, json.length);
                        exchange.getResponseBody().write(json);
                        exchange.close();
                    });
        }
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
        fixture.start();
        String base = "http://127.0.0.1:" + fixture.getAddress().getPort();
        properties.add(
                "agent.security.jwt-secret",
                () ->
                        System.getenv()
                                .getOrDefault(
                                        "AGENT_JWT_SECRET",
                                        "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY"));
        // 部署安全输入必须显式：dev-hmac+devAuth 的旧票链路正是集成入口的生产形态（SecurityConfiguration 链）。
        System.setProperty("agent.jwt.secret", "abcdefghijklmnopqrstuvwxyz0123456789-TEST-ONLY");
        properties.add("agent.security.mode", () -> "dev-hmac");
        properties.add("agent.security.service-id", () -> "gateway");
        properties.add("agent.dev-auth", () -> "true");
        properties.add("agent.services.app", () -> base);
        properties.add("agent.services.engine", () -> base);
    }

    @BeforeEach
    void reset() throws Exception {
        String ref1 = SecretVault.put(SECRET1, new Actor("tenant-a", "admin", Set.of("ADMIN"), Map.of()));
        String ref2 = SecretVault.put(SECRET2, new Actor("tenant-a", "admin", Set.of("ADMIN"), Map.of()));
        credential1.set(credential(1, "partner", ref1));
        credential2.set(credential(2, "partner2", ref2));
        // 用例间共享进程内窗口，必须显式归零；默认上限放高，由各用例按自己的计数叙述收紧。
        IntegrationRateLimit rate = access.rateLimit();
        rate.enabled(true);
        rate.windowSeconds(60);
        rate.maxRequests(1024);
        rate.clock(clock);
        clock.now.set(Instant.parse("2026-10-28T08:00:00Z"));
        rate.reset();
    }

    private static Map<String, Object> credential(long id, String biz, String ref) {
        return Map.of(
                "id", id,
                "tenantId", "tenant-a",
                "appCode", "ops",
                "bizSystemCode", biz,
                "status", "ACTIVE",
                "secretRef", ref,
                "allowedOrigins", List.of("https://partner.example"),
                "claimLimits",
                        Map.of(
                                "principalIds", List.of("alice"),
                                "roles", List.of("operator"),
                                "attributes", Map.of("region", List.of("A"))));
    }

    @AfterAll
    static void stop() {
        fixture.stop(0);
        System.clearProperty("agent.master.key");
        System.clearProperty("agent.secret.dir");
    }

    /** 出票即计数（凭据 1 的第一个窗口占用）。 */
    private String ticket(String secret, long credentialId) throws Exception {
        String response =
                mvc.perform(
                                post("/embed/ticket")
                                        .header("Authorization", "ApiKey " + credentialId + ":" + secret)
                                        .contentType("application/json")
                                        .content("{\"userId\":\"alice\",\"roles\":[\"operator\"]}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return Json.read(response).path("data").path("ticket").asText();
    }

    private MvcResult call(String authorization) throws Exception {
        return mvc.perform(
                        post("/v1/apps/ops/runs")
                                .header("Authorization", authorization)
                                .header("X-User-Id", "alice")
                                .contentType("application/json")
                                .content("{}"))
                .andReturn();
    }

    private String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    void admitsWithinQuotaAndProxiesToBackend() throws Exception {
        access.rateLimit().maxRequests(5);
        String token = ticket(SECRET1, 1); // 出票计 1
        for (int i = 0; i < 4; i++) { // 票据代理各计 1，共 5 次占满不超限
            MvcResult result = call("Bearer " + token);
            Assertions.assertEquals(200, result.getResponse().getStatus(), bodyOf(result));
            Assertions.assertTrue(bodyOf(result).contains("r_verified"), bodyOf(result));
            Assertions.assertTrue(bodyOf(result).contains("service"), bodyOf(result));
        }
    }

    @Test
    void rejectsOverLimitWithReadable429AndMaskedKey() throws Exception {
        access.rateLimit().maxRequests(3);
        String token = ticket(SECRET1, 1); // 1
        Assertions.assertEquals(200, call("Bearer " + token).getResponse().getStatus()); // 2
        Assertions.assertEquals(200, call("Bearer " + token).getResponse().getStatus()); // 3，窗口已满
        MvcResult over =
                mvc.perform(
                                post("/v1/apps/ops/runs")
                                        .header("X-Trace-Id", "trace-rate-limit-1")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType("application/json")
                                        .content("{}"))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.code").value("INTEGRATION_RATE_LIMITED"))
                        .andExpect(jsonPath("$.traceId").value("trace-rate-limit-1"))
                        .andReturn();
        String body = bodyOf(over);
        // 可读性：哪把 key（脱敏尾4位）、当前用量、上限、重试建议；完整密钥绝不回显。
        Assertions.assertTrue(body.contains("凭据 1"), body);
        Assertions.assertTrue(body.contains("尾号 " + SECRET1.substring(SECRET1.length() - 4)), body);
        Assertions.assertTrue(body.contains("已放行 3"), body);
        Assertions.assertTrue(body.contains("上限 3"), body);
        Assertions.assertTrue(body.contains("重试"), body);
        Assertions.assertFalse(body.contains(SECRET1), body);
    }

    @Test
    void distinctCredentialsHaveIndependentWindows() throws Exception {
        access.rateLimit().maxRequests(2);
        String token1 = ticket(SECRET1, 1); // key1 计 1
        Assertions.assertEquals(200, call("Bearer " + token1).getResponse().getStatus()); // key1 计 2，满
        String rejected = bodyOf(call("Bearer " + token1));
        Assertions.assertTrue(rejected.contains("INTEGRATION_RATE_LIMITED"), rejected);
        Assertions.assertTrue(rejected.contains("凭据 1"), rejected);
        String token2 = ticket(SECRET2, 2); // key2 独立窗口，不受 key1 耗尽影响
        MvcResult ok = call("Bearer " + token2);
        Assertions.assertEquals(200, ok.getResponse().getStatus(), bodyOf(ok));
    }

    @Test
    void windowSlideRestoresAccess() throws Exception {
        access.rateLimit().maxRequests(2);
        String token = ticket(SECRET1, 1);
        Assertions.assertEquals(200, call("Bearer " + token).getResponse().getStatus());
        Assertions.assertTrue(bodyOf(call("Bearer " + token)).contains("INTEGRATION_RATE_LIMITED"));
        clock.advanceSeconds(61); // 整窗滑出后恢复
        MvcResult recovered = call("Bearer " + token);
        Assertions.assertEquals(200, recovered.getResponse().getStatus(), bodyOf(recovered));
    }

    @Test
    void disabledSwitchBypassesLimit() throws Exception {
        access.rateLimit().enabled(false); // 回滚形态：开关关闭时限流整体旁路
        access.rateLimit().maxRequests(1);
        String token = ticket(SECRET1, 1);
        for (int i = 0; i < 5; i++)
            Assertions.assertEquals(200, call("Bearer " + token).getResponse().getStatus());
    }

    @Test
    void directApiKeyRequestCountsOncePerHttpRequest() throws Exception {
        access.rateLimit().maxRequests(3);
        for (int i = 0; i < 3; i++) { // 直连 ApiKey 一次请求=出票+代理，必须只计一次
            MvcResult result = call("ApiKey 1:" + SECRET1);
            Assertions.assertEquals(200, result.getResponse().getStatus(), bodyOf(result));
            Assertions.assertTrue(bodyOf(result).contains("r_verified"), bodyOf(result));
        }
        String over = bodyOf(call("ApiKey 1:" + SECRET1));
        Assertions.assertTrue(over.contains("INTEGRATION_RATE_LIMITED"), over);
        Assertions.assertTrue(over.contains("尾号 " + SECRET1.substring(SECRET1.length() - 4)), over);
    }
}
