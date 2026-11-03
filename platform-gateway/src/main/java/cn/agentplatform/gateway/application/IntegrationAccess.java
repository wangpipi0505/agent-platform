package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.IntegrationClaims;
import cn.agentplatform.gateway.domain.IntegrationPolicy;
import cn.agentplatform.gateway.domain.IntegrationRateLimit;

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
import java.time.Clock;
import java.util.*;

/** 每次新请求实时读取权威状态，吊销/策略收紧无需等待JWT过期；在途SSE完成当前响应后关闭。 */
@Service
public class IntegrationAccess implements AllowedOriginPolicy, ApiKeyAuthenticator {
    private final RestClient client;
    private final String appUrl, engineUrl;
    private final JwtDecoder decoder;
    // T18.1：凭据维度入口限流；默认关（application.yml），构造期即装配，@Value 注入仅调参。
    private final IntegrationRateLimit rateLimit = new IntegrationRateLimit(Clock.systemUTC());
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

    @Value("${agent.integration.rate-limit-enabled:false}")
    public void rateLimitEnabled(boolean value) {
        rateLimit.enabled(value);
    }

    @Value("${agent.integration.rate-limit.window-seconds:60}")
    public void rateLimitWindowSeconds(long value) {
        rateLimit.windowSeconds(value);
    }

    @Value("${agent.integration.rate-limit.max-requests:0}")
    public void rateLimitMaxRequests(long value) {
        rateLimit.maxRequests(value);
    }

    /** 测试与独立装配缝：暴露凭据限流器（开关/窗口/上限/时钟/归零）。 */
    public IntegrationRateLimit rateLimit() {
        return rateLimit;
    }

    @Override
    public Authentication authenticate(HttpServletRequest request) {
        // 只允许可信后端声明"这是哪个终端用户"；tenant/principal 属于票据派生字段，出现即拒绝。
        for (String header : List.of("X-User-Tenant", "X-Tenant", "X-Principal", "X-User-Principal")) {
            if (request.getHeader(header) != null)
                throw new PlatformException(
                        403, "INTEGRATION_CLIENT_IDENTITY_REJECTED", "客户端自报的 tenant/principal 不被采信");
        }
        String roles = Objects.toString(request.getHeader("X-User-Roles"), "[]"),
                attributes = Objects.toString(request.getHeader("X-User-Attributes"), "{}");
        if (roles.length() + attributes.length() > 16384)
            throw new PlatformException(400, "CLAIMS_TOO_LARGE", "身份声明超过安全上限");
        JsonNode claimed;
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("userId", Objects.toString(request.getHeader("X-User-Id"), ""));
            claims.put("roles", Json.read(roles));
            claims.put("attributes", Json.read(attributes));
            // 环境不是身份：它是"允许落到哪个环境"的选择器，仍须落在接入档案上限内。
            String environment = request.getHeader(IntegrationPolicy.ENVIRONMENT_HEADER);
            if (environment != null && !environment.isBlank())
                claims.put("environment", environment.trim());
            claimed = Json.tree(claims);
        } catch (Exception e) {
            throw new PlatformException(400, "INVALID_CLAIMS", "身份声明须为有效JSON");
        }
        // 同一条出票策略只对已验证ApiKey的签发者声明裁剪；这些头本身从不构成身份。
        // 直连ApiKey的一次业务请求在此计一次入口量并打标，check 凭标记去重，避免同请求重复计数。
        String token =
                ticket(
                                request.getHeader("Authorization"),
                                claimed,
                                request.getHeader("Origin"),
                                (credentialId, keyTail) ->
                                        rateLimit.admit(
                                                credentialId, keyTail, request.getRequestURI()))
                        .get("ticket")
                        .toString();
        request.setAttribute(IntegrationRateLimit.METERED_ATTRIBUTE, Boolean.TRUE);
        return new JwtAuthenticationToken(decoder.decode(token), List.of());
    }

    private JsonNode credential(long id) {
        JsonNode body;
        try {
            body =
                    client.get()
                            .uri(appUrl + "/internal/integration-credentials/" + id)
                            .header("Authorization", "Bearer " + ServiceTokens.issue(BOOTSTRAP))
                            .retrieve()
                            .body(JsonNode.class);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 4xx 是"凭据不存在/无权读取"，属于调用方问题；不能包装成传输故障让业务方重试。
            throw new PlatformException(401, "INVALID_CREDENTIAL", "集成凭据不可用");
        } catch (org.springframework.web.client.RestClientException e) {
            // 连接失败/超时/5xx：接入档案暂不可达。零业务执行，且必须让宿主看到可解释的阻塞。
            throw new PlatformException(
                    503, "INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", "接入档案暂不可用，未执行任何业务操作");
        }
        if (body == null || body.isMissingNode())
            throw new PlatformException(503, "INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE", "接入档案响应为空");
        return body.path("data");
    }

    /** 出票成功后的凭据计数回调；只在凭据校验与声明裁剪全部通过后才触发（被拒流量不消耗配额）。 */
    public interface CredentialSink {
        void admit(long credentialId, String keyTail);
    }

    public Map<String, Object> ticket(String authorization, JsonNode requested, String origin) {
        return ticket(authorization, requested, origin, null);
    }

    /** /embed/ticket 出票入口：出票成功即按凭据计数（与直连ApiKey的打标计数互不影响）。 */
    public Map<String, Object> embedTicket(String authorization, JsonNode requested, String origin) {
        return ticket(authorization, requested, origin, (id, tail) -> rateLimit.admit(id, tail, "/embed/ticket"));
    }

    public Map<String, Object> ticket(
            String authorization, JsonNode requested, String origin, CredentialSink meter) {
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
        // 限流只在出票真正成功后计数；密钥材料仅留脱敏尾4位。
        if (meter != null) meter.admit(id, tail4(pair[1]));
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
        String boundEnvironment =
                actor.attributes()
                        .getOrDefault(
                                IntegrationClaims.ENVIRONMENT_ATTRIBUTE,
                                Set.of())
                        .stream()
                        .findFirst()
                        .orElse(null);
        // 撤权、环境上限收紧都归一到"重新出票"；旧票据不能因为签名仍有效就继续用。
        IntegrationPolicy.bound(credential, boundEnvironment);
        IntegrationPolicy.origin(credential, req.getHeader("Origin"));
        String app = credential.path("appCode").asText();
        if (!actor.tenantId().equals(credential.path("tenantId").asText())
                || !app.equals(jwt.getClaimAsString("app_code")))
            throw new PlatformException(403, "APP_BINDING_DENIED", "出票与当前应用/租户不一致");
        if (!credential
                        .path("bizSystemCode")
                        .asText()
                        .equals(jwt.getClaimAsString("biz_system_code"))
                || !IntegrationPolicy.digest(credential)
                        .equals(jwt.getClaimAsString("policy_digest")))
            throw new PlatformException(401, "INTEGRATION_TICKET_EXPIRED", "集成授权已变更，请重新出票");
        String path = req.getRequestURI();
        if (!IntegrationPolicy.allowedPath(path, req.getMethod(), app))
            throw new PlatformException(403, "APP_BINDING_DENIED", "出票仅可访问绑定应用的运行接口");
        // 票据代理放行计一次；直连ApiKey请求已在 authenticate 出票时计数并打标，此处去重。
        if (!Boolean.TRUE.equals(req.getAttribute(IntegrationRateLimit.METERED_ATTRIBUTE)))
            rateLimit.admit(id.longValue(), null, path);
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

    /** 密钥脱敏尾4位；仅在计数消息/审计中用于指认“哪把 key”，绝不回显密钥本体。 */
    private static String tail4(String secret) {
        if (secret == null || secret.isEmpty()) return "未知";
        return secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
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
