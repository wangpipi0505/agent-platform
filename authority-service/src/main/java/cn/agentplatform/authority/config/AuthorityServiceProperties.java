package cn.agentplatform.authority.config;

import cn.agentplatform.common.authority.AuthorityProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 签发方自身配置（与平台侧 {@link AuthorityProperties} 是两端契约的两半）。
 * <p>时限上界刻意对齐平台校验器：平台只接受 {@code exp-iat <= maxTicketSeconds(<=60)} 的票据、
 * {@code <= maxDecisionSeconds(<=5)} 的断言，签发方超过这个预算就是签出一张必然被拒的票。
 * <p>{@code events-hard-window-seconds} 只限制观测窗的 hard_deadline（默认 55s，正是客户端夹取值），
 * 供测试把"跨过首个断言窗后继续续期"压到秒级；断言窗上限仍是 {@code assertion-seconds<=5}，
 * 客户端夹取逻辑不读这个配置。
 * <p>{@code clients} 用 {@code Map<service,secret>} 而不是 {@code List<Client>}：map 可以按 key
 * 被环境变量/测试属性单独覆盖（list 只能整体替换），凭据注入因此不需要重写整块配置。
 */
@ConfigurationProperties(prefix = "agent.authority")
public record AuthorityServiceProperties(
        String issuer,
        String keyPath,
        String kid,
        String previousKeyPath,
        String previousKid,
        @DefaultValue("60") int ticketSeconds,
        @DefaultValue("5") int assertionSeconds,
        @DefaultValue("55") int eventsHardWindowSeconds,
        Map<String, String> clients) {

    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CLIENT_KEY = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public AuthorityServiceProperties {
        if (kid == null || !KID.matcher(kid).matches()) {
            throw new IllegalStateException("AUTHORITY_KID_INVALID: agent.authority.kid 必须是 1-64 位 [A-Za-z0-9._-]");
        }
        if (previousKid != null && !previousKid.isBlank()) {
            if (!KID.matcher(previousKid).matches()) {
                throw new IllegalStateException("AUTHORITY_KID_INVALID: agent.authority.previous-kid 非法");
            }
            if (previousKid.equals(kid)) {
                throw new IllegalStateException("AUTHORITY_KID_INVALID: previous-kid 不能等于 kid");
            }
            if (previousKeyPath == null || previousKeyPath.isBlank()) {
                throw new IllegalStateException("AUTHORITY_KID_INVALID: 配置 previous-kid 时必须同时配置 previous-key-path");
            }
        }
        if (ticketSeconds < 1 || ticketSeconds > 60) {
            // 平台 TrustedJwtDecoder 用同一个上界（60s）校验，超出即"签了也用不了"。
            throw new IllegalStateException("AUTHORITY_TTL_INVALID: ticket-seconds 必须在 1..60");
        }
        if (assertionSeconds < 1 || assertionSeconds > 5) {
            throw new IllegalStateException("AUTHORITY_TTL_INVALID: assertion-seconds 必须在 1..5");
        }
        if (eventsHardWindowSeconds < 5 || eventsHardWindowSeconds > 55) {
            // 只夹观测窗的 hard_deadline 上界（S3 及沿用同一上限的 S4/S5）；断言窗仍是 1..5s，
            // 客户端夹取的 55s 常量也不随它变。上限 55 与客户端同值，配置不能把硬窗放宽。
            throw new IllegalStateException(
                    "AUTHORITY_TTL_INVALID: events-hard-window-seconds 必须在 5..55");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        if (clients != null) {
            clients.forEach((key, secret) -> {
                if (key == null || !CLIENT_KEY.matcher(key).matches()) {
                    throw new IllegalStateException("AUTHORITY_CLIENT_INVALID: clients 的 key 必须是平台服务名：" + key);
                }
                if (!AuthorityProperties.SERVICES.contains(key)) {
                    throw new IllegalStateException("AUTHORITY_CLIENT_INVALID: 未知服务名 " + key);
                }
                if (secret != null && (secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)) {
                    // 凭据会进 Basic 头；控制字符会撕裂 HTTP 报文。
                    throw new IllegalStateException("AUTHORITY_CLIENT_INVALID: " + key + " 的 client secret 含换行");
                }
                normalized.put(key, secret == null ? "" : secret);
            });
        }
        clients = Collections.unmodifiableMap(normalized);
    }

    /** 配置了的 issuer 必须是绝对 http(s) URL，且不含 userinfo/query/fragment。 */
    static String requireValidIssuer(String raw) {
        String value = raw.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AUTHORITY_ISSUER_REQUIRED: agent.authority.issuer 不是合法 URI");
        }
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException("AUTHORITY_ISSUER_REQUIRED: agent.authority.issuer 只允许 http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("AUTHORITY_ISSUER_REQUIRED: agent.authority.issuer 缺少主机名");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("AUTHORITY_ISSUER_REQUIRED: agent.authority.issuer 不允许 userinfo/query/fragment");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public boolean hasConfiguredIssuer() {
        return issuer != null && !issuer.isBlank();
    }

    public String previousKidOrNull() {
        return previousKeyPath == null || previousKeyPath.isBlank() ? null : previousKid;
    }

    public boolean hasPreviousKey() {
        return previousKeyPath != null && !previousKeyPath.isBlank();
    }

    public String clientSecret(String clientId) {
        return Objects.requireNonNullElse(clients.get(clientId), "");
    }

    public Map<String, String> enabledClients() {
        Map<String, String> enabled = new LinkedHashMap<>();
        clients.forEach((key, secret) -> {
            if (secret != null && !secret.isBlank()) {
                enabled.put(key, secret);
            }
        });
        return Collections.unmodifiableMap(enabled);
    }
}
