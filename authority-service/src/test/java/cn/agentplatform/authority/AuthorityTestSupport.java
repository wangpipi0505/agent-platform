package cn.agentplatform.authority;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个端点共用的测试支撑：固定测试密钥（classpath 里的 PEM，只用于测试）、固定 client 凭据、
 * 以及与目录行一致的 resource。所有子类共享同一组 properties，因此整个测试运行只起一个 Spring 上下文。
 */
@ActiveProfiles("test")
public abstract class AuthorityTestSupport {

    public static final String ISSUER = "https://authority.test";
    public static final String KID = "test-2026-10";
    public static final String GATEWAY = "gateway";
    public static final String APP = "app";
    public static final String GATEWAY_SECRET = "test-gateway-secret";
    public static final String APP_SECRET = "test-app-secret";
    public static final String GATEWAY_SECRET_REF = "env:AGENT_SECRET_IAM_GATEWAY_CLIENT";
    public static final String APP_SECRET_REF = "env:AGENT_SECRET_IAM_APP_CLIENT";
    /** 与平台 {@code Digest.sha256} 同形状的 64 位十六进制摘要，仅用于 resource.version_digest。 */
    public static final String DIGEST = "0123456789abcdef".repeat(4);
    public static final String ORIGIN_ACTION = "integration.origin.check";
    public static final String ORIGIN_ID = "app-a";

    public static final String P_ISSUER = "agent.authority.issuer=" + ISSUER;
    public static final String P_KID = "agent.authority.kid=" + KID;
    public static final String P_KEY = "agent.authority.key-path=classpath:authority-test-key.pem";
    public static final String P_GATEWAY = "agent.authority.clients.gateway=" + GATEWAY_SECRET;
    public static final String P_APP = "agent.authority.clients.app=" + APP_SECRET;

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    protected static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    protected static Map<String, Object> resource() {
        return Map.of("kind", "integration-origin", "id", ORIGIN_ID, "version_digest", DIGEST);
    }

    /** 请求体：字段集合与 {@code AuthorityRequest} 白名单一致；{@code service_ticket} 为空时不出现。 */
    protected static String requestBody(String operation, String target, String action, String nonce,
                                        String serviceTicket) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("target", target);
        body.put("action", action);
        body.put("resource", resource());
        body.put("nonce", nonce);
        if (serviceTicket != null) {
            body.put("service_ticket", serviceTicket);
        }
        return Json.write(body);
    }

    protected ResponseEntity<JsonNode> postJson(String path, String authorization, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return rest.postForEntity(url(path), new HttpEntity<>(body, headers), JsonNode.class);
    }

    protected JsonNode jwks() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/.well-known/jwks.json"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected PublicKey publishedKey() {
        return TestJwt.publicKey(jwks().get("keys").get(0));
    }

    /** 以 gateway 身份取一张 workload 票据（目录行 {@code gateway -> app / integration.origin.check}）。 */
    protected String gatewayTicket(String nonce) {
        ResponseEntity<JsonNode> response = postJson("/oauth/token", basic(GATEWAY, GATEWAY_SECRET),
                requestBody("client", APP, ORIGIN_ACTION, nonce, null));
        assertThat(response.getStatusCode())
                .as("ticket endpoint body: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody().get("ticket").asText();
    }
}
