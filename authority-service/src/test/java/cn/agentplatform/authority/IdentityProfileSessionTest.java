package cn.agentplatform.authority;

import static org.assertj.core.api.Assertions.assertThat;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * INT-04：同一个身份查询协议，换业务系统只改"有限字段映射"，验证语义（状态/角色/租户上限）
 * 保持同一套；身份源返回别的租户或缺少映射后的用户名必须拒绝，而不是回落到配置默认值。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class IdentityProfileSessionTest extends AuthorityTestSupport {

    private static final String TENANT = "local-v12";
    private static final int SESSION_SECONDS = 600;
    private static final AtomicReference<String> MODE = new AtomicReference<>("ok");

    private static HttpServer identity;

    @BeforeAll
    static void startIdentity() throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext(
                "/system/apikeys/identity/self",
                exchange -> {
                    int status = 200;
                    String body;
                    switch (MODE.get()) {
                        case "tenant-mismatch" ->
                                body = envelope(directory("zhaoliu", "tenant-b"));
                        case "no-user-name" -> body = envelope(directory("", TENANT));
                        case "server-error" -> {
                            status = 500;
                            body = "{\"code\":500,\"msg\":\"boom\"}";
                        }
                        default -> body = envelope(directory("zhaoliu", TENANT));
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
    static void identityProfile(DynamicPropertyRegistry registry) {
        // api_key 直传只允许 test profile；与 SessionEndpointTest 走同一条分支。
        System.setProperty("spring.profiles.active", "test");
        registry.add("agent.authority.identity.enabled", () -> "true");
        registry.add(
                "agent.authority.identity.url",
                () -> "http://127.0.0.1:" + identity.getAddress().getPort());
        registry.add("agent.authority.identity.issuer", () -> ISSUER);
        registry.add("agent.authority.identity.audience", () -> "agent-platform-portal");
        registry.add("agent.authority.identity.client-id", () -> GATEWAY);
        registry.add("agent.authority.identity.tenant", () -> TENANT);
        registry.add("agent.authority.identity.session-seconds", () -> String.valueOf(SESSION_SECONDS));
        registry.add("agent.authority.identity.protocol", () -> "USER_MGMT");
        registry.add("agent.authority.identity.environment", () -> "prod");
        // 业务系统的字段命名与 user-mgmt 不同：只改这张有限映射表，验证/签名语义不变。
        registry.add("agent.authority.identity.mapping.user-name", () -> "profile.login");
        registry.add("agent.authority.identity.mapping.status", () -> "profile.state");
        registry.add("agent.authority.identity.mapping.roles", () -> "grants");
        registry.add("agent.authority.identity.mapping.role-key", () -> "code");
        registry.add("agent.authority.identity.mapping.role-data-scope", () -> "scope");
        registry.add("agent.authority.identity.mapping.dept-id", () -> "org.id");
        registry.add("agent.authority.identity.mapping.dept-name", () -> "org.name");
        registry.add("agent.authority.identity.mapping.tenant", () -> "org.tenant");
    }

    @BeforeEach
    void resetStub() {
        MODE.set("ok");
    }

    @Test
    void finiteMappingDrivesIdentityWithoutChangingVerificationSemantics() {
        ResponseEntity<JsonNode> response =
                postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), sessionBody("ak-mapped"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode session = response.getBody().get("session");
        assertThat(session.get("userName").asText()).isEqualTo("zhaoliu");
        assertThat(session.get("attributes").get("deptId").get(0).asText()).isEqualTo("9");
        assertThat(session.get("attributes").get("deptName").get(0).asText()).isEqualTo("研发一部");
        assertThat(session.get("attributes").get("dataScope").toString()).contains("dept");
        assertThat(session.get("roles").toString()).as("保留角色 SERVICE 仍必须被剔除").doesNotContain("SERVICE");

        JsonNode claims =
                TestJwt.verifiedClaims(
                        response.getBody().get("access_token").asText(), "at+jwt", publishedKey());
        assertThat(claims.get("sub").asText()).isEqualTo("zhaoliu");
        assertThat(claims.get("tenant_id").asText()).isEqualTo(TENANT);
        assertThat(claims.get("roles").toString()).contains("ops").doesNotContain("SERVICE");
        assertThat(claims.get("attributes").get("userId").get(0).asText()).isEqualTo("zhaoliu");
        assertThat(claims.get("attributes").has("deptId"))
                .as("展示属性不得进入签名面").isFalse();
    }

    @Test
    void identityFromAnotherTenantIsRejectedInsteadOfRelabelled() {
        MODE.set("tenant-mismatch");

        ResponseEntity<JsonNode> response =
                postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), sessionBody("ak-other-tenant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().has("access_token")).isFalse();
    }

    @Test
    void missingMappedUserNameIsRejectedNotDefaulted() {
        MODE.set("no-user-name");

        ResponseEntity<JsonNode> response =
                postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), sessionBody("ak-anonymous"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().has("access_token")).isFalse();
    }

    @Test
    void mappedProfileStillUsesTheTransportUnavailableCodeWhenSourceIsDown() {
        MODE.set("server-error");

        ResponseEntity<JsonNode> response =
                postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET), sessionBody("ak-down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code").asText()).isEqualTo("IAM_IDENTITY_UNAVAILABLE");
    }

    private static Map<String, Object> directory(String userName, String tenant) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("login", userName);
        profile.put("state", "0");
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("id", 9);
        org.put("name", "研发一部");
        org.put("tenant", tenant);
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("code", "ops");
        ops.put("scope", "dept");
        Map<String, Object> reserved = new LinkedHashMap<>();
        reserved.put("code", "SERVICE");
        reserved.put("scope", "all");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", profile);
        data.put("org", org);
        data.put("grants", List.of(ops, reserved));
        return data;
    }

    private static String envelope(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 200);
        body.put("msg", "ok");
        body.put("data", data);
        return Json.write(body);
    }

    private static String sessionBody(String apiKey) {
        return Json.write(Map.of("operation", "session", "api_key", apiKey));
    }
}
