package cn.agentplatform.gateway;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.domain.IntegrationRateLimit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** EX22：经过真实 SecurityFilterChain 与公共异常处理，不以 standalone MVC 冒充鉴权过滤器。 */
@SpringBootTest(classes = ErrorEnvelopeBoundaryTest.BoundaryApp.class, properties = {
        "agent.security.mode=test", "agent.security.service-id=gateway", "agent.dev-auth=false",
        "server.address=127.0.0.1"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ErrorEnvelopeBoundaryTest {
    private static final String OLD_KEY = System.getProperty("agent.jwt.secret");
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        String key = System.getenv().getOrDefault("AGENT_JWT_SECRET", "ex22-independent-test-key-0123456789abcdef");
        System.setProperty("agent.jwt.secret", key);
        properties.add("agent.security.jwt-secret", () -> key);
    }

    @AfterAll static void restoreKey() {
        if (OLD_KEY == null) System.clearProperty("agent.jwt.secret");
        else System.setProperty("agent.jwt.secret", OLD_KEY);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfiguration.class, PlatformErrors.class, TraceContextFilter.class, BoundaryController.class})
    static class BoundaryApp {
        @Bean ApiKeyAuthenticator limitedApiKey() {
            var rate = new IntegrationRateLimit();
            rate.enabled(true);
            rate.maxRequests(1);
            rate.admit(22, "0022", "/v1/apps/ex22/runs");
            // 有限可信夹具只提供成功校验后已满的限流窗；真实凭据校验仍由原 IntegrationRateLimitTest 覆盖。
            return request -> {
                rate.admit(22, null, request.getRequestURI());
                throw new AssertionError("quota must reject before authentication completes");
            };
        }
    }

    @RestController static class BoundaryController {
        @GetMapping("/admin/ex22/remote") Object remote() {
            throw new PlatformException(403, "REMOTE_SCOPE_DENIED", "当前数据范围不允许此操作");
        }
        @GetMapping("/admin/ex22/dependency") Object dependency() {
            throw new ResourceAccessException("private SQL/password must not reach response");
        }
        @GetMapping("/admin/ex22/ok") Object ok() { return ApiResponse.ok(Map.of("accepted", true)); }
    }

    @Test
    void securityDirectQuotaAndControllerErrorsKeepOneTraceAndExplicitSafeSemantics() throws Exception {
        check(mvc.perform(get("/admin/ex22/ok").header("X-Trace-Id", "ex22-401")).andReturn(),
                401, "SYS-AUTH-401", "AUTH", false, "ex22-401");
        String user = ServiceTokens.userToken(new Actor("tenant-ex22", "alice", Set.of("EDITOR"), Map.of()), 60);
        check(mvc.perform(get("/internal/ex22").header("Authorization", "Bearer " + user)
                        .header("X-Trace-Id", "ex22-403")).andReturn(),
                403, "SYS-AUTH-403", "AUTH", false, "ex22-403");
        check(mvc.perform(post("/v1/apps/ex22/runs").header("Authorization", "ApiKey finite-test")
                        .header("X-Trace-Id", "ex22-429")).andReturn(),
                429, "INTEGRATION_RATE_LIMITED", "QUOTA", true, "ex22-429");
        check(mvc.perform(get("/admin/ex22/remote").header("Authorization", "Bearer " + user)
                        .header("X-Trace-Id", "ex22-business")).andReturn(),
                403, "REMOTE_SCOPE_DENIED", "AUTH", false, "ex22-business");
        var unavailable = mvc.perform(get("/admin/ex22/dependency").header("Authorization", "Bearer " + user)
                .header("X-Trace-Id", "ex22-dependency")).andReturn();
        check(unavailable, 502, "DEPENDENCY_UNAVAILABLE", "TRANSPORT", false, "ex22-dependency");
        assertThat(unavailable.getResponse().getContentAsString()).doesNotContain("SQL", "password");

        var duplicate = mvc.perform(get("/admin/ex22/ok").header("X-Trace-Id", "caller-first", "caller-second")).andReturn();
        String regenerated = duplicate.getResponse().getHeader("X-Trace-Id");
        assertThat(regenerated).matches("[0-9a-f-]{36}").isNotEqualTo("caller-first").isNotEqualTo("caller-second");
        check(duplicate, 401, "SYS-AUTH-401", "AUTH", false, regenerated);

        var ok = mvc.perform(get("/admin/ex22/ok").header("Authorization", "Bearer " + user)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(Json.read(ok.getResponse().getContentAsString()).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "data", "traceId");
        assertThat(org.slf4j.MDC.get("traceId")).isNull();
    }

    private static void check(MvcResult result, int status, String code, String category, boolean retryable, String trace) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        var body = Json.read(result.getResponse().getContentAsString());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isEqualTo(trace);
        assertThat(result.getResponse().getHeaders("X-Trace-Id")).containsExactly(trace);
        assertThat(body.path("category").asText()).isEqualTo(category);
        assertThat(body.path("retryable").isBoolean()).isTrue();
        assertThat(body.path("retryable").asBoolean()).isEqualTo(retryable);
        assertThat(body.path("userMessage").asText()).isNotBlank().isEqualTo(body.path("message").asText());
    }
}
