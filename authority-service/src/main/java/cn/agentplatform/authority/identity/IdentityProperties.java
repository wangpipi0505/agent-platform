package cn.agentplatform.authority.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * 人身份会话（portal 令牌）签发配置。
 *
 * <p>与平台侧 {@code AuthorityProperties.Portal} 是同一份契约的两端：issuer/audience/client_id 必须与
 * 平台配置逐字一致，否则签出来的令牌在 {@code TrustedJwtDecoder} 的 portal 分支上校验不过。
 * 这里不提供"放宽校验"的开关——对不齐就是配置错误，应该在启动/首个请求时暴露，而不是签一张没人认的票。
 *
 * @param enabled        是否启用 {@code operation=session}；默认关，保证既有部署行为不变
 * @param url            user-mgmt 基地址（只允许 http/https，不允许携带 userinfo 或片段）
 * @param apiKeyRef      默认取哪个 API-Key（{@code env:NAME}），请求体可覆盖
 * @param identityPath   身份查询路径，默认为 user-mgmt 的 {@code /system/apikeys/identity/self}
 * @param tenant         签进 portal 令牌的 {@code tenant_id}
 * @param issuer         令牌 {@code iss}
 * @param audience       令牌 {@code aud}
 * @param clientId       令牌 {@code client_id}
 * @param scope          令牌 {@code scope}（空格分隔）
 * @param bizSystemCode  写入 {@code attributes.bizSystemCode}，用于与平台侧的属性上限核验
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs    读超时
 * @param sessionSeconds   会话有效期（平台 portal 分支不校验上限，这里仍自限 1 小时）
 * @param roleDenylist  从 user-mgmt 角色里剔除的保留名（平台会再按 allowlist 过滤一次）
 */
@ConfigurationProperties(prefix = "agent.authority.identity")
public record IdentityProperties(
        @DefaultValue("false") boolean enabled,
        String url,
        String apiKeyRef,
        @DefaultValue("/system/apikeys/identity/self") String identityPath,
        @DefaultValue("local-v12") String tenant,
        String issuer,
        String audience,
        String clientId,
        @DefaultValue("platform.access") String scope,
        @DefaultValue("agent-platform") String bizSystemCode,
        @DefaultValue("2000") int connectTimeoutMs,
        @DefaultValue("2000") int readTimeoutMs,
        @DefaultValue("3600") int sessionSeconds,
        Set<String> roleDenylist) {

    public IdentityProperties {
        roleDenylist = roleDenylist == null ? Set.of("SERVICE", "GATEWAY_AUTH") : Set.copyOf(roleDenylist);
        if (enabled) {
            requireText(url, "url");
            requireText(tenant, "tenant");
            requireText(issuer, "issuer");
            requireText(audience, "audience");
            requireText(clientId, "clientId");
            requireText(scope, "scope");
            requireText(bizSystemCode, "bizSystemCode");
            requireText(identityPath, "identityPath");
            if (!identityPath.startsWith("/")) {
                throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: identity-path 必须以 / 开头");
            }
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!(lower.startsWith("http://") || lower.startsWith("https://")) || url.indexOf('@') >= 0
                    || url.indexOf('#') >= 0 || url.endsWith("/")) {
                throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: url 必须是 http(s) 基地址且不含 userinfo/片段/尾斜杠");
            }
            if (connectTimeoutMs < 100 || connectTimeoutMs > 10_000 || readTimeoutMs < 100 || readTimeoutMs > 10_000) {
                throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: 超时必须在 100..10000ms");
            }
            if (sessionSeconds < 60 || sessionSeconds > 3600) {
                throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: session-seconds 必须在 60..3600");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: 启用人身份会话时必须配置 " + name);
        }
    }
}
