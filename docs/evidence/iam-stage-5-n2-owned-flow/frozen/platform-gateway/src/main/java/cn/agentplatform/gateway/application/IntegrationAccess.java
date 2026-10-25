package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.domain.IntegrationPolicy;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 每次新请求实时读取权威状态，吊销/策略收紧无需等待JWT过期；在途SSE完成当前响应后关闭。 */
@Service
public class IntegrationAccess implements AllowedOriginPolicy, ApiKeyAuthenticator {
    private final RestClient client;
    private final String appUrl, engineUrl;
    private final JwtDecoder decoder;
    private static final Actor BOOTSTRAP =
            new Actor("_gateway", "_gateway", Set.of("GATEWAY_AUTH"), Map.of());

    public IntegrationAccess(
            @Value("${agent.services.app}") String appUrl,
            @Value("${agent.services.engine}") String engineUrl,
            JwtDecoder decoder) {
        this.appUrl = appUrl;
        this.engineUrl = engineUrl;
        this.decoder = decoder;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Authentication authenticate(HttpServletRequest request) {
        String roles = Objects.toString(request.getHeader("X-User-Roles"), "[]"),
                attributes = Objects.toString(request.getHeader("X-User-Attributes"), "{}");
        if (roles.length() + attributes.length() > 16384)
            throw new PlatformException(400, "CLAIMS_TOO_LARGE", "身份声明超过安全上限");
        JsonNode claimed;
        try {
            claimed =
                    Json.tree(
                            Map.of(
                                    "userId",
                                    Objects.toString(request.getHeader("X-User-Id"), ""),
                                    "roles",
                                    Json.read(roles),
                                    "attributes",
                                    Json.read(attributes)));
        } catch (Exception e) {
            throw new PlatformException(400, "INVALID_CLAIMS", "身份声明须为有效JSON");
        }
        // 同一条出票策略只对已验证ApiKey的签发者声明裁剪；这些头本身从不构成身份。
        String token =
                ticket(request.getHeader("Authorization"), claimed, request.getHeader("Origin"))
                        .get("ticket")
                        .toString();
        return new JwtAuthenticationToken(decoder.decode(token), List.of());
    }

    private JsonNode credential(long id) {
        try {
            return client.get()
                    .uri(appUrl + "/internal/integration-credentials/" + id)
                    .header("Authorization", "Bearer " + ServiceTokens.issue(BOOTSTRAP))
                    .retrieve()
                    .body(JsonNode.class)
                    .path("data");
        } catch (Exception e) {
            throw new PlatformException(401, "INVALID_CREDENTIAL", "集成凭据不可用");
        }
    }

    public Map<String, Object> ticket(String authorization, JsonNode requested, String origin) {
        if (authorization == null || !authorization.startsWith("ApiKey "))
            throw new PlatformException(401, "API_KEY_REQUIRED", "请由业务系统后端使用集成凭据出票");
        String[] pair = authorization.substring(7).split(":", 2);
        long id;
        try {
            if (pair.length != 2) throw new IllegalArgumentException();
            id = Long.parseLong(pair[0]);
        } catch (Exception e) {
            throw new PlatformException(401, "INVALID_CREDENTIAL", "集成凭据格式无效");
        }
        JsonNode credential = credential(id);
        IntegrationPolicy.active(credential);
        IntegrationPolicy.origin(credential, origin);
        var owner =
                new Actor(
                        credential.path("tenantId").asText(),
                        "_gateway",
                        Set.of("GATEWAY_AUTH"),
                        Map.of());
        String actual = SecretVault.resolve(credential.path("secretRef").asText(), owner);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), pair[1].getBytes(StandardCharsets.UTF_8)))
            throw new PlatformException(401, "INVALID_CREDENTIAL", "集成凭据无效");
        if (requested.has("appCode")
                && !requested.path("appCode").asText().equals(credential.path("appCode").asText()))
            throw new PlatformException(403, "APP_BINDING_DENIED", "凭据不属于此应用");
        Actor actor = IntegrationPolicy.clip(credential, requested);
        String token =
                ServiceTokens.integrationToken(
                        actor,
                        credential.path("appCode").asText(),
                        credential.path("bizSystemCode").asText(),
                        id,
                        IntegrationPolicy.digest(credential),
                        300);
        return Map.of(
                "ticket", token, "accessToken", token, "expiresIn", 300, "tokenType", "Bearer");
    }

    public void check(HttpServletRequest req, Actor actor) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt jwt)
                || !"integration".equals(jwt.getClaimAsString("token_use"))) return;
        Number id = jwt.getClaim("credential_id");
        if (id == null) throw new PlatformException(401, "INVALID_TICKET", "出票缺少凭据绑定");
        JsonNode credential = credential(id.longValue());
        IntegrationPolicy.active(credential);
        IntegrationPolicy.origin(credential, req.getHeader("Origin"));
        String app = credential.path("appCode").asText();
        if (!actor.tenantId().equals(credential.path("tenantId").asText())
                || !app.equals(jwt.getClaimAsString("app_code"))
                || !credential
                        .path("bizSystemCode")
                        .asText()
                        .equals(jwt.getClaimAsString("biz_system_code"))
                || !IntegrationPolicy.digest(credential)
                        .equals(jwt.getClaimAsString("policy_digest")))
            throw new PlatformException(401, "TICKET_POLICY_CHANGED", "集成授权已变更，请重新出票");
        String path = req.getRequestURI();
        if (!IntegrationPolicy.allowedPath(path, req.getMethod(), app))
            throw new PlatformException(403, "APP_BINDING_DENIED", "出票仅可访问绑定应用的运行接口");
        if (path.startsWith("/v1/runs/")) {
            String runId = path.substring("/v1/runs/".length()).split("/")[0];
            // 引擎先检查tenant+principal所有权，网关再检查应用绑定，防同一业务身份跨应用读取。
            JsonNode run =
                    client.get()
                            .uri(engineUrl + "/v1/runs/" + runId)
                            .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                            .retrieve()
                            .body(JsonNode.class)
                            .path("data");
            if (!app.equals(run.path("appCode").asText()))
                throw new PlatformException(403, "APP_BINDING_DENIED", "运行不属于出票绑定应用");
        }
    }

    @Override
    public boolean allowed(String origin) {
        if (origin == null || origin.length() > 2048) return false;
        try {
            JsonNode result =
                    client.get()
                            .uri(appUrl + "/internal/integration-origins?origin={origin}", origin)
                            .header("Authorization", "Bearer " + ServiceTokens.issue(BOOTSTRAP))
                            .retrieve()
                            .body(JsonNode.class);
            return result != null && result.path("data").path("allowed").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
