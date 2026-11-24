package cn.agentplatform.authority.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 人身份会话（portal 令牌）签发配置，也就是平台管理员可填写的"身份源档案"。
 *
 * <p>与平台侧 {@code AuthorityProperties.Portal} 是同一份契约的两端：issuer/audience/client_id 必须与
 * 平台配置逐字一致，否则签出来的令牌在 {@code TrustedJwtDecoder} 的 portal 分支上校验不过。
 * 这里不提供"放宽校验"的开关——对不齐就是配置错误，应该在启动/首个请求时暴露，而不是签一张没人认的票。
 *
 * <p><b>协议是有限枚举，不是脚本</b>：当前只实现 {@link #PROTOCOL_USER_MGMT}（user-mgmt 的
 * "API-Key 查身份"查询协议）。{@code OIDC_USERINFO} / {@code SIGNED_JWT} 等其它协议属于新增适配，
 * 需要先在契约登记、再由平台适配器实现；把未实现的协议名写进配置会拒绝启动，而不是在请求期
 * 猜测字段或执行任意表达式。字段适配只允许 {@link IdentityMapping} 里的目标字段与点分路径。
 *
 * <p><b>上限</b>：tenant 是配置里的固定值，身份源返回的 tenant（若映射了）必须与之相等；
 * environment 是接入档案声明的部署环境，只作为会话展示/诊断属性，不进入签名面
 * （签进去的 {@code attributes} 只能是委派天花板维度，见 {@code SessionService.CEILING_KEYS}）。
 *
 * @param enabled        是否启用 {@code operation=session}，默认关闭
 * @param url            user-mgmt 基地址（只允许 http/https，不允许携带 userinfo 或片段）
 * @param apiKeyRef      默认取哪个 API-Key（{@code env:NAME}），请求体可覆盖
 * @param identityPath   身份查询路径，默认为 user-mgmt 的 {@code /system/apikeys/identity/self}
 * @param tenant         签进 portal 令牌的 {@code tenant_id}，同时是身份源租户上限
 * @param issuer         令牌 {@code iss}
 * @param audience       令牌 {@code aud}
 * @param clientId       令牌 {@code client_id}
 * @param scope          令牌 {@code scope}（空格分隔）
 * @param bizSystemCode  写入 {@code attributes.bizSystemCode}，用于与平台侧的属性上限核验
 * @param connectTimeoutMs 连接超时
 * @param readTimeoutMs    读超时
 * @param sessionSeconds   会话有效期（平台 portal 分支不校验上限，这里仍自限 1 小时）
 * @param roleDenylist  从身份源角色里剔除的保留名（平台会再按 allowlist 过滤一次）
 * @param protocol      身份源协议；当前仅支持 {@code USER_MGMT}
 * @param mapping       有限字段映射：目标字段 → 源字段点分路径；未配置的目标使用默认路径
 * @param activeStatus  判定"账号可用"的状态字面量；默认 {@code "0"}（user-mgmt 约定）
 * @param environment   部署环境标识（{@code ^[a-z][a-z0-9-]{0,31}$}），只进会话展示属性
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
        Set<String> roleDenylist,
        @DefaultValue("USER_MGMT") String protocol,
        Map<String, String> mapping,
        @DefaultValue("0") String activeStatus,
        String environment) {

    /** 当前唯一实现的协议：凭 API-Key 调 user-mgmt 的身份查询接口，再按有限字段映射取身份。 */
    public static final String PROTOCOL_USER_MGMT = "USER_MGMT";
    /** 已登记但未实现的协议名；配置它们会拒绝启动，避免"看起来支持、实际不校验"。 */
    public static final Set<String> RESERVED_PROTOCOLS = Set.of("OIDC_USERINFO", "SIGNED_JWT");

    private static final java.util.regex.Pattern ENVIRONMENT =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    public IdentityProperties {
        roleDenylist =
                roleDenylist == null ? Set.of("SERVICE", "GATEWAY_AUTH") : Set.copyOf(roleDenylist);
        if (enabled) {
            validateDirectory(url, tenant, protocol, activeStatus, connectTimeoutMs, readTimeoutMs);
            requireText(issuer, "issuer");
            requireText(audience, "audience");
            requireText(clientId, "clientId");
            requireText(scope, "scope");
            requireText(bizSystemCode, "bizSystemCode");
            requireText(identityPath, "identityPath");
            if (!identityPath.startsWith("/")) {
                throw new IllegalStateException(
                        "AUTHORITY_IDENTITY_INVALID: identity-path 必须以 / 开头");
            }
            if (sessionSeconds < 60 || sessionSeconds > 3600) {
                throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: session-seconds 必须在 60..3600");
            }
            if (environment != null && !environment.isBlank() && !ENVIRONMENT.matcher(environment).matches()) {
                throw new IllegalStateException(
                        "AUTHORITY_IDENTITY_INVALID: environment 必须匹配 ^[a-z][a-z0-9-]{0,31}$");
            }
        }
        mapping = IdentityMapping.normalize(enabled, mapping);
    }

    void validateDirectory() {
        validateDirectory(url, tenant, protocol, activeStatus, connectTimeoutMs, readTimeoutMs);
    }

    private static void validateDirectory(String url, String tenant, String protocol, String activeStatus,
                                          int connectTimeoutMs, int readTimeoutMs) {
        requireText(url, "url");
        requireText(tenant, "tenant");
        requireText(protocol, "protocol");
        requireText(activeStatus, "activeStatus");
        if (!PROTOCOL_USER_MGMT.equals(protocol)) {
            throw new IllegalStateException(
                    "AUTHORITY_IDENTITY_INVALID: 身份源协议 "
                            + protocol
                            + " 尚未实现（当前仅支持 USER_MGMT）；其它协议必须先登记为平台适配器，"
                            + "不得用脚本或任意表达式替代");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))
                || url.indexOf('@') >= 0
                || url.indexOf('#') >= 0
                || url.indexOf('?') >= 0
                || url.endsWith("/")) {
            throw new IllegalStateException(
                    "AUTHORITY_IDENTITY_INVALID: url 必须是 http(s) 基地址且不含 userinfo/查询参数/片段/尾斜杠");
        }
        if (connectTimeoutMs < 100 || connectTimeoutMs > 10_000 || readTimeoutMs < 100 || readTimeoutMs > 10_000) {
            throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: 超时必须在 100..10000ms");
        }
        if (activeStatus.length() > 16) {
            throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: active-status 最长 16 个字符");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "AUTHORITY_IDENTITY_INVALID: 必须配置 " + name);
        }
    }

    /** 供诊断/证据脱敏输出：只回协议、issuer、audience 与映射键，绝不回 apiKeyRef 指向的值。 */
    public Map<String, Object> profileSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("protocol", protocol);
        summary.put("issuer", issuer);
        summary.put("audience", audience);
        summary.put("tenant", tenant);
        summary.put("environment", environment == null ? "" : environment);
        summary.put("sessionSeconds", sessionSeconds);
        summary.put("mapping", mapping.keySet().stream().sorted().toList());
        return summary;
    }
}
