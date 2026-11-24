package cn.agentplatform.authority.identity;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 人身份会话签发（口径 B 的 M4 前置）：把 user-mgmt 的 API-Key 身份换成平台 gateway 认得的 portal 令牌。
 *
 * <p><b>为什么必须由签发方做</b>：user-mgmt 没有 JWKS/OIDC/票据签发能力，只有"凭 API-Key 查身份"。
 * 平台 {@code TrustedJwtDecoder} 的 portal 分支要求 {@code typ=at+jwt}、{@code token_use=user}、
 * {@code iss/aud/client_id} 三处与配置全等，以及 {@code tenant_id/sub/roles/attributes/scope} 齐备，
 * 因此这一段只能由持有私钥的 authority 服务完成。
 *
 * <p><b>失败语义</b>：user-mgmt 超时/5xx/响应不可解析 → {@code 503}（可重试，且不能伪装成"无权限"）；
 * 身份不存在或已停用 → {@code 403} 且不区分原因（避免用错误码枚举账号）；请求体非法 → {@code 400}。
 *
 * <p><b>不外泄</b>：API-Key 只在内存里拼请求头，日志与异常消息都不含它；签出的令牌只回给调用方一次。
 */
@Component
public final class SessionService {

    public static final String OPERATION = "session";
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_IDENTITY_BYTES = 64 * 1024;
    private static final String TYPE = "at+jwt";
    /**
     * 委派天花板维度：必须与 {@code DelegatedAuthority.ATTRIBUTE_KEYS} 同集合。
     *
     * <p>签进令牌的 {@code attributes} 只表示"这份人身份可以被委派到哪些维度"，是授权上限而不是
     * 身份画像；{@code DelegatedAuthority.attributesOf} 对白名单外的键按 fail-closed 拒绝
     * （{@code SUBJECT_ATTRIBUTES}）。因此 deptId/deptName/dataScope 这类展示属性只能留在
     * 会话响应体里，不能进签名面——否则本服务签出的人令牌无法作为它自己委派链的主体令牌。
     */
    private static final Set<String> CEILING_KEYS = Set.of("region", "dataLabels", "bizSystemCode", "userId");

    private final KeyRing keys;
    private final IssuerResolver issuerResolver;
    private final Clock clock;
    private final IdentityProperties properties;
    private final HttpClient http;

    @Value("${agent.authority.identity.subject-lookup-key-ref:}")
    private String subjectLookupKeyRef = "";

    @Value("${agent.authority.identity.subject-lookup-path:/system/apikeys/identity/}")
    private String subjectLookupPath = "/system/apikeys/identity/";

    public SessionService(KeyRing keys, IssuerResolver issuerResolver, Clock clock, IdentityProperties properties) {
        this.keys = keys;
        this.issuerResolver = issuerResolver;
        this.clock = clock;
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 解析请求体里的 {@code operation}；不属于本服务时返回 null，由调用方走原路径。 */
    public static String operationOf(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) return null;
        try {
            JsonNode root = Json.tree(Json.read(new String(body, StandardCharsets.UTF_8), Object.class));
            JsonNode operation = root.get("operation");
            return operation != null && operation.isTextual() ? operation.asText() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public IdentityProperties properties() {
        return properties;
    }

    /**
     * @param clientId 已通过 Basic 认证的调用方；只有配置里指定的会话签发者可换取令牌（默认 gateway）
     * @return 含 {@code access_token}/{@code expires_in}/{@code session} 的响应体
     */
    public Map<String, Object> session(String clientId, byte[] body) {
        if (!properties.enabled()) {
            throw AuthorityException.denied("人身份会话未启用（agent.authority.identity.enabled=false）");
        }
        if (!"gateway".equals(clientId)) {
            throw AuthorityException.denied("只有 gateway 可以换取人身份会话，调用方=" + clientId);
        }
        Map<String, Object> request = parse(body);
        String apiKey = resolveApiKey(request);
        Map<String, Object> identity = fetchIdentity(apiKey, properties.identityPath());
        if (!admissible(identity)) {
            // 不存在/停用/无角色都归一到同一个 403：否则错误码本身就是账号枚举接口。
            throw AuthorityException.denied("user-mgmt 身份不可用或已停用");
        }
        requireTenant(identity);
        String userName = mappedText(identity, "userName");
        List<String> roles = roles(identity);
        if (roles.isEmpty()) throw AuthorityException.denied("user-mgmt 身份没有任何可用角色");
        Map<String, Object> attributes = attributes(identity, userName);
        Map<String, Object> claims = claims(userName, roles, attributes);
        String token = keys.sign(TYPE, claims);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("userName", userName);
        session.put("roles", roles);
        session.put("attributes", attributes);
        session.put("subject", userName);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token);
        response.put("token_type", "Bearer");
        response.put("expires_in", properties.sessionSeconds());
        response.put("session", session);
        return response;
    }

    /** Check the verified ticket's principal against the live directory before issuing a new delegation. */
    public void requireCurrentIdentity(String tenant, String subject) {
        if (subjectLookupKeyRef.isBlank()) return;
        if (!properties.enabled() || !properties.tenant().equals(tenant) || subject == null
                || subject.isBlank() || subject.length() > 128 || subject.equals(".") || subject.equals("..")
                || subject.indexOf('/') >= 0 || subject.indexOf('\\') >= 0)
            throw inactiveSubject();
        if (!subjectLookupPath.matches("/(?:[A-Za-z0-9_-]+/)+"))
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE",
                    "身份查询配置不可用", "identity_subject_path");

        String key = resolveApiKey(Map.of("api_key_ref", subjectLookupKeyRef));
        try {
            var identity = fetchIdentity(key, subjectLookupPath + UriUtils.encodePathSegment(subject, StandardCharsets.UTF_8));
            requireTenant(identity);
            if (!admissible(identity) || !subject.equals(mappedText(identity, "userName")))
                throw inactiveSubject();
        } catch (AuthorityException error) {
            // USER_MGMT reports absent users through a non-success business envelope.
            if (error.status() == 403) throw inactiveSubject();
            throw error;
        }
    }

    private static AuthorityException inactiveSubject() {
        return new AuthorityException(401, "SYS-AUTH-401", "当前身份不可用", "identity_subject_unavailable");
    }

    private Map<String, Object> claims(String userName, List<String> roles, Map<String, Object> attributes) {
        long iat = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer());
        claims.put("aud", List.of(properties.audience()));
        claims.put("client_id", properties.clientId());
        claims.put("token_use", "user");
        claims.put("tenant_id", properties.tenant());
        claims.put("sub", userName);
        claims.put("roles", roles);
        claims.put("attributes", ceilingAttributes(attributes));
        claims.put("scope", properties.scope());
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", iat + properties.sessionSeconds());
        String jti = "ses_" + UUID.randomUUID().toString().replace("-", "");
        claims.put("jti", jti);
        // EX-44：SSE 第二跳 gateway→gateway 的观测票要能证明"同一个浏览器会话"。origin 只做等值关联、
        // 不是凭据——用会话 jti 的摘要，既不把可重放的会话标识透给下游，又让 authority 能对两跳做同一性比对。
        claims.put("origin_authentication_id", originAuthenticationId(jti));
        return claims;
    }

    /** {@code oa1:<32hex>}：sha256("portal-session:" + 会话 jti) 的前 32 位，绑定会话但不可反推 jti。 */
    static String originAuthenticationId(String jti) {
        return "oa1:" + Digest.sha256("portal-session:" + jti).substring(0, 32);
    }

    /**
     * 只把委派天花板维度签进令牌；其余身份画像留在响应体 {@code session.attributes} 里。
     *
     * <p>两个面必须分开：响应体是给调用方渲染用的，不参与验签；令牌是下游
     * {@code DelegatedAuthority} 的授权上限输入，只能出现它认识的四个维度，否则
     * {@code attributesOf} 会整条拒绝（{@code 403 SUBJECT_ATTRIBUTES}）。
     */
    private static Map<String, Object> ceilingAttributes(Map<String, Object> attributes) {
        Map<String, Object> ceiling = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (CEILING_KEYS.contains(key)) ceiling.put(key, value);
        });
        return ceiling;
    }

    /**
     * 平台侧只看"用户是否可用"这一个事实：user-mgmt 的 {@code status} 是字符串枚举（"0" 正常），
     * 这里不接受其它类型，避免 "false"/0 之类的宽松相等把停用账号放进来。
     *
     * <p>状态字面量来自档案（{@code active-status}），字段位置来自有限映射；不做大小写/数字归一。
     */
    private boolean admissible(Map<String, Object> identity) {
        if (!properties.activeStatus().equals(mapped(identity, "status"))) return false;
        String name = mappedText(identity, "userName");
        return name != null && !name.isBlank() && name.length() <= 128;
    }

    /**
     * 租户上限：只有档案里映射了 {@code tenant} 源字段时才校验，且必须与配置的 tenant 逐字相等。
     *
     * <p>平台签出的 {@code tenant_id} 永远取配置值，身份源返回值不得改变它；这里只是把
     * "身份服务返回了别的租户"提前拒绝，而不是让它悄悄落到 {@code local-v12}。
     */
    private void requireTenant(Map<String, Object> identity) {
        String sourceTenant = IdentityMapping.text(identity, properties.mapping().get("tenant"));
        if (sourceTenant != null && !sourceTenant.equals(properties.tenant())) {
            throw AuthorityException.denied("身份源返回的租户不属于本平台");
        }
    }

    private List<String> roles(Map<String, Object> identity) {
        Object raw = mapped(identity, "roles");
        if (!(raw instanceof List<?> list)) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        String keyPath = properties.mapping().get("roleKey");
        for (Object item : list) {
            if (item instanceof String text) {
                addRole(unique, text);
            } else if (item instanceof Map<?, ?> role) {
                // roleKey 在"角色对象内"解析；也容忍身份文档本来就给字符串数组（authorities: ["ops"]）。
                for (String key : strings(IdentityMapping.resolve(role, keyPath))) addRole(unique, key);
            }
        }
        unique.removeAll(properties.roleDenylist());
        return List.copyOf(unique);
    }

    private static void addRole(Set<String> unique, String key) {
        if (key != null && !key.isBlank() && key.length() <= 64) unique.add(key);
    }

    private Map<String, Object> attributes(Map<String, Object> identity, String userName) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        String deptId = scalar(IdentityMapping.resolve(identity, properties.mapping().get("deptId")));
        if (deptId != null) attributes.put("deptId", List.of(deptId));
        String deptName = IdentityMapping.text(identity, properties.mapping().get("deptName"));
        if (deptName != null && !deptName.isBlank()) attributes.put("deptName", List.of(deptName));
        List<String> dataScopes = new ArrayList<>();
        Object rawRoles = mapped(identity, "roles");
        if (rawRoles instanceof List<?> list) {
            String scopePath = properties.mapping().get("roleDataScope");
            for (Object item : list) {
                if (item instanceof Map<?, ?> role) {
                    dataScopes.addAll(strings(IdentityMapping.resolve(role, scopePath)));
                }
            }
        }
        if (!dataScopes.isEmpty()) attributes.put("dataScope", List.copyOf(new LinkedHashSet<>(dataScopes)));
        attributes.put("userId", List.of(userName));
        attributes.put("bizSystemCode", List.of(properties.bizSystemCode()));
        return attributes;
    }

    private Object mapped(Map<String, Object> identity, String target) {
        return IdentityMapping.resolve(identity, properties.mapping().get(target));
    }

    private String mappedText(Map<String, Object> identity, String target) {
        return IdentityMapping.text(identity, properties.mapping().get(target));
    }

    private static String scalar(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Number number) return String.valueOf(number);
        return null;
    }

    private static List<String> strings(Object value) {
        if (value instanceof String text) return text.isBlank() ? List.of() : List.of(text);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) result.add(text);
            }
            return result;
        }
        return List.of();
    }

    private Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw AuthorityException.invalidRequest("session 请求体缺失或过大", "body_missing");
        }
        try {
            Object parsed = Json.read(new String(body, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("not an object");
            Map<String, Object> request = new LinkedHashMap<>();
            map.forEach((key, value) -> request.put(String.valueOf(key), value));
            return request;
        } catch (RuntimeException malformed) {
            throw AuthorityException.invalidRequest("session 请求体必须是 JSON 对象", "body_invalid");
        }
    }

    private String resolveApiKey(Map<String, Object> request) {
        Object direct = request.get("api_key");
        if (direct != null) {
            // 直传只允许在测试 profile 里；生产必须走引用，避免密钥进日志/配置。
            if (!"test".equals(System.getProperty("spring.profiles.active"))) {
                throw AuthorityException.invalidRequest("api_key 仅测试 profile 允许直传，请改用 api_key_ref", "api_key_direct");
            }
            return requireKey(text(direct));
        }
        Object ref = request.get("api_key_ref");
        String reference = ref == null ? properties.apiKeyRef() : text(ref);
        if (reference == null || !reference.startsWith("env:")) {
            throw AuthorityException.invalidRequest("api_key_ref 必须是 env:NAME 形式", "api_key_ref_invalid");
        }
        String name = reference.substring(4);
        if (name.isBlank() || name.length() > 128) {
            throw AuthorityException.invalidRequest("api_key_ref 名称非法", "api_key_ref_invalid");
        }
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份凭据引用未配置", "api_key_ref_missing");
        }
        return requireKey(value);
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw AuthorityException.invalidRequest("API-Key 形状非法", "api_key_invalid");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchIdentity(String apiKey, String path) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(properties.url() + path))
                    .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                    .header("Accept", "application/json")
                    .header("X-API-Key", apiKey)
                    .GET()
                    .build();
        } catch (IllegalArgumentException malformed) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务地址不可用", "identity_uri_invalid");
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务调用被中断", "identity_interrupted");
        } catch (Exception unavailable) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务暂不可用", "identity_unavailable");
        }
        if (response.statusCode() != 200) {
            // user-mgmt 正常契约是 HTTP 200 + body 内 code；走到这里说明链路本身有问题。
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务返回非 200", "identity_status");
        }
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IDENTITY_BYTES) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务响应为空或过大", "identity_body");
        }
        Object parsed;
        try {
            parsed = Json.read(new String(bytes, StandardCharsets.UTF_8), Object.class);
        } catch (RuntimeException malformed) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务响应不可解析", "identity_body_invalid");
        }
        if (!(parsed instanceof Map<?, ?> envelope)) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务响应结构非法", "identity_body_invalid");
        }
        Object code = envelope.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            // 账号不存在/无权限在 user-mgmt 里也是 200 + code，这里统一按"身份不可用"处理，
            // 但保留 403 语义由调用方决定：对外仍是同一个拒绝。
            throw AuthorityException.denied("user-mgmt 拒绝该 API-Key（code=" + code + "）");
        }
        Object data = envelope.get("data");
        if (!(data instanceof Map<?, ?> identity)) {
            throw new AuthorityException(503, "IAM_IDENTITY_UNAVAILABLE", "身份服务未返回 data", "identity_body_invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        identity.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    /** 供测试断言：当前实现里 issuer 由配置决定，不从请求方推断。 */
    String issuer() {
        return issuerResolver.issuer();
    }

    /** 保留给将来的轮换/审计：签发时间使用注入的 Clock，便于测试断言 exp-iat。 */
    long now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }
}
