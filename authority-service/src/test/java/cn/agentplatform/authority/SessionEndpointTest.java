package cn.agentplatform.authority;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人身份会话端点（{@code operation=session}）：把 user-mgmt 的 API-Key 身份换成平台 gateway 认得的 portal 令牌。
 *
 * <p>用真实的 JDK HttpServer 桩住 user-mgmt，而不是 mock {@code HttpClient}——本类要证明的是
 * "请求头里带的是 X-API-Key""HTTP 200 + body code 的非 2xx 语义""停用账号不泄露身份"这些**线级**契约，
 * 桩掉 HttpClient 就把这些全部绕过了。
 *
 * <p>五种失败路径各自钉一个不变量：停用/无角色/被拒 → 403 且不区分原因（防账号枚举）；
 * 身份服务 5xx → 503（可重试，不能伪装成无权限）；非 gateway 调用方 → 403；非 session 请求体 → 不被本端点拦截。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class SessionEndpointTest extends AuthorityTestSupport {

    private static final String AUDIENCE = "agent-platform-portal";
    private static final String TENANT = "local-v12";
    private static final int SESSION_SECONDS = 600;

    private static HttpServer identity;
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");
    private static final AtomicReference<String> LAST_API_KEY = new AtomicReference<>();

    @BeforeAll
    static void startIdentity() throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/system/apikeys/identity/self", exchange -> {
            LAST_API_KEY.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            String mode = MODE.get();
            int status = 200;
            String body;
            switch (mode) {
                case "server-error" -> {
                    status = 500;
                    body = "{\"code\":500,\"msg\":\"boom\"}";
                }
                case "disabled" -> body = envelope(identity("wangwu", "1", List.of(role("common_user", "5"))));
                case "no-roles" -> body = envelope(identity("nobody", "0", List.<Map<String, Object>>of()));
                case "deny-code" -> body = "{\"code\":403,\"msg\":\"nope\"}";
                default -> body = envelope(identity("admin", "0",
                        List.of(role("admin", "1"), role("SERVICE", "1"))));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        identity.start();
    }

    @AfterAll
    static void stopIdentity() {
        if (identity != null) identity.stop(0);
    }

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        // 直传 api_key 只允许 test profile：这条约束本身在 SessionService 里，测试必须走同一分支。
        System.setProperty("spring.profiles.active", "test");
        registry.add("agent.authority.identity.enabled", () -> "true");
        registry.add("agent.authority.identity.url", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
        registry.add("agent.authority.identity.issuer", () -> ISSUER);
        registry.add("agent.authority.identity.audience", () -> AUDIENCE);
        registry.add("agent.authority.identity.client-id", () -> GATEWAY);
        registry.add("agent.authority.identity.tenant", () -> TENANT);
        registry.add("agent.authority.identity.session-seconds", () -> String.valueOf(SESSION_SECONDS));
    }

    /** 桩的状态与"最近一次看到的 API-Key"都是跨用例的：不重置就会把上个用例的痕迹当成本用例的结果。 */
    @BeforeEach
    void resetStub() {
        MODE.set("ok");
        LAST_API_KEY.set(null);
    }

    @Test
    void issuesPortalTokenBoundToUserIdentityAndAttributes() {
        MODE.set("ok");

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                sessionBody("ak-admin-test"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(LAST_API_KEY.get()).as("API-Key 必须放在 X-API-Key 头里，而不是查询串或 body")
                .isEqualTo("ak-admin-test");

        JsonNode body = response.getBody();
        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asInt()).isEqualTo(SESSION_SECONDS);
        assertThat(body.get("session").get("userName").asText()).isEqualTo("admin");
        assertThat(body.get("session").get("attributes").get("deptId").get(0).asText()).isEqualTo("103");

        JsonNode claims = TestJwt.verifiedClaims(body.get("access_token").asText(), "at+jwt", publishedKey());
        assertThat(claims.get("iss").asText()).isEqualTo(ISSUER);
        assertThat(claims.get("aud").get(0).asText()).isEqualTo(AUDIENCE);
        assertThat(claims.get("client_id").asText()).isEqualTo(GATEWAY);
        assertThat(claims.get("token_use").asText()).isEqualTo("user");
        assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT);
        assertThat(claims.get("sub").asText()).isEqualTo("admin");
        assertThat(claims.get("scope").asText()).isEqualTo("platform.access");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(SESSION_SECONDS);
        assertThat(claims.get("nbf").asLong()).isEqualTo(claims.get("iat").asLong());
        assertThat(claims.get("jti").asText()).startsWith("ses_");

        List<String> roles = Json.read(Json.write(claims.get("roles")), List.class);
        assertThat(roles).as("保留角色 SERVICE 必须被剔除：否则人身份能拿到服务级角色名")
                .containsExactly("admin");
        assertThat(claims.get("attributes").get("userId").get(0).asText()).isEqualTo("admin");
        assertThat(claims.get("attributes").get("bizSystemCode").get(0).asText()).isEqualTo("agent-platform");
        assertThat(claims.get("attributes").get("dataScope").get(0).asText()).isEqualTo("1");
    }

    @Test
    void disabledAccountIsRejectedWithoutRevealingIdentity() {
        MODE.set("disabled");

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                sessionBody("ak-wangwu-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().toString()).doesNotContain("wangwu").doesNotContain("userName");
        assertThat(response.getBody().get("access_token")).isNull();
    }

    @Test
    void identityWithoutUsableRolesIsRejected() {
        MODE.set("no-roles");

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                sessionBody("ak-nobody-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().toString()).doesNotContain("nobody");
    }

    @Test
    void identityServiceFailureIsUnavailableNotDenied() {
        MODE.set("server-error");

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                sessionBody("ak-admin-test"));

        assertThat(response.getStatusCode()).as("身份服务 5xx 必须可重试，不能被当成无权限")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code").asText()).isEqualTo("IAM_IDENTITY_UNAVAILABLE");
    }

    @Test
    void onlyGatewayMayExchangeIdentityForToken() {
        MODE.set("ok");

        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(APP, APP_SECRET),
                sessionBody("ak-admin-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(LAST_API_KEY.get()).as("被拒时不得先去打身份服务").isNull();
    }

    @Test
    void nonSessionBodiesStillGoThroughTheTicketPath() {
        // 目录行 gateway -> app / integration.origin.check 是既有能力：加了 session 分支后必须原样可用。
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                requestBody("client", APP, ORIGIN_ACTION, "nonce-after-session", null));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("ticket").asText()).isNotBlank();
    }

    private static String sessionBody(String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", "session");
        body.put("api_key", apiKey);
        return Json.write(body);
    }

    private static Map<String, Object> identity(String userName, String status, List<Map<String, Object>> roles) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userName", userName);
        data.put("nickName", "测试用户");
        data.put("status", status);
        data.put("deptId", 103);
        data.put("deptName", "研发部门");
        data.put("roles", roles);
        return data;
    }

    private static Map<String, Object> role(String key, String dataScope) {
        Map<String, Object> role = new LinkedHashMap<>();
        role.put("roleKey", key);
        role.put("roleName", key);
        role.put("dataScope", dataScope);
        return role;
    }

    private static String envelope(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("msg", "操作成功");
        body.put("data", data);
        return Json.write(body);
    }
}
