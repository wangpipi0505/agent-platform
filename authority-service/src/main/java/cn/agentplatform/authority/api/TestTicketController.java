package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.sign.KeyRing;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * R-26 测试用户票据铸造端点（<b>测试支撑面</b>，契约：09 测试细则 v1.2 §9.2；不进 04 对外契约稿）。
 *
 * <p>{@code POST /test/tickets}：把测试需要的"人身份"直接铸成与生产用户票同构的 RS256 JWT，
 * 让 E2（iam 模式）有真实的 USER/BUILDER 票可发 PR-A/SEC-07，而不是引入 HS256 本地票旁路
 * （b 方案的四条否决理由见细则 §9.5）。
 *
 * <p><b>注册双条件</b>：{@code @Profile("test")} 且 {@code agent.authority.test-ticket.enabled=true}
 * （默认 false）同时成立才注册本控制器；任一不成立则 bean 与路由都不存在——非 test 环境启动即拒绝
 * 注册，运行期表现为 404。不存在"配置开关一开就全网可用"的路径。
 *
 * <p><b>票面与生产用户票（{@code SessionService} 的 portal 令牌）逐字段同构</b>：
 * <ul>
 *   <li>issuer/aud/client_id/scope 取自身份档案 {@link IdentityProperties}（即 {@code SessionService}
 *       签发门户令牌的同一组配置值）——测试票与生产票天然同 issuer、同 aud，不另设一套可漂移的配置；</li>
 *   <li>签名走 {@link KeyRing}（同 typ {@code at+jwt}、同 active kid、同 RS256 私钥）；</li>
 *   <li>claims 含 {@code iss/aud/token_use/sub/tenant_id/roles/attributes/iat/exp/jti}，
 *       另带生产票也有的 {@code client_id/scope/nbf/origin_authentication_id}——
 *       平台 {@code TrustedJwtDecoder} 的 portal 分支要求这四项齐备，缺一即 401。</li>
 * </ul>
 *
 * <p><b>约束（§9.2 逐条）</b>：roles 仅 {@code ADMIN|BUILDER|OPERATOR|USER}（含 {@code SERVICE} 一律 400，
 * SERVICE 走 client_credentials 不得由本端点签发）；{@code tenantId} 仅限 {@code demo} 与 {@code t-*}
 * 前缀；{@code ttlSeconds ≤ 3600}（缺省 3600）。
 *
 * <p><b>attributes 签名口径</b>：请求按契约收 {@code region/dataLabels/bizSystemCode/userId} 四键并校验
 * （归属键恰一值），但<b>签进票的 attributes 只有 {@code region/dataLabels}</b>。依据是平台归属键的
 * 钉扎语义：{@code ClaimCeiling.attributes} 要求 token 与网关白名单两侧要么都带、要么都不带该键，
 * 且值恰一相等——userId 随测试 subject 变化，无法与静态白名单钉扎共存。平台自有 portal 夹具
 * （{@code LoopbackAuthority.portal}）与网关默认 {@code attribute-allowlist=region,dataLabels} 同口径
 * 都只签这两键。需要归属键钉扎的固定场景（单用户固定值）另行登记扩展，不在本端点猜配置。
 *
 * <p><b>审计 fail-closed</b>：每次签发先写审计行 {@code AUDIT TEST_TICKET_MINTED}
 * （subject/roles/tenant/jti/issuer/iat，照 engine {@code Tasks/ChatSessions} 的 {@code AUDIT xxx}
 * warn 行先例），写入抛错即 503 拒签——不存在"票已发出而审计缺失"的路径。
 */
@RestController
@Profile("test")
@ConditionalOnProperty(name = "agent.authority.test-ticket.enabled", havingValue = "true")
public final class TestTicketController {

    private static final Logger audit = LoggerFactory.getLogger("agent.authority.audit");

    /** 允许签发的角色集合；{@code SERVICE}/{@code GATEWAY_AUTH} 及其它一律 400。 */
    static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "BUILDER", "OPERATOR", "USER");
    /** 与 {@code SessionService.CEILING_KEYS} 同集合：签进令牌的委派天花板维度。 */
    static final Set<String> ATTRIBUTE_KEYS = Set.of("region", "dataLabels", "bizSystemCode", "userId");
    /** 单值归属键：平台 {@code ClaimCeiling} 对这两键要求左右两侧恰一值且相等。 */
    private static final Set<String> SINGLE_VALUE_KEYS = Set.of("bizSystemCode", "userId");

    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_SUBJECT_LENGTH = 128;
    private static final int MAX_TENANT_LENGTH = 64;
    private static final int MAX_VALUE_LENGTH = 128;
    private static final int MAX_VALUES = 16;
    private static final int DEFAULT_TTL_SECONDS = 3600;
    private static final int MAX_TTL_SECONDS = 3600;
    private static final String TOKEN_TYPE = "at+jwt";

    private final KeyRing keys;
    private final IdentityProperties identity;
    private final Clock clock;
    private final AuditWriter writer;

    /** 审计写入的唯一出口；生产装配用 SLF4J，测试可注入替身验证 fail-closed。 */
    @FunctionalInterface
    interface AuditWriter {
        void emit(String line);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TestTicketController(KeyRing keys, IdentityProperties identity, Clock clock) {
        this(keys, identity, clock, audit::warn);
    }

    TestTicketController(KeyRing keys, IdentityProperties identity, Clock clock, AuditWriter writer) {
        this.keys = keys;
        this.identity = identity;
        this.clock = clock;
        this.writer = writer;
    }

    @PostMapping(path = "/test/tickets", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> mint(@RequestBody(required = false) byte[] body) {
        Request request = parse(body);
        requireProfileClaims();
        long iat = clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
        long exp = iat + request.ttlSeconds();
        String jti = "tt-" + UUID.randomUUID().toString().replace("-", "");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", identity.issuer());
        claims.put("aud", List.of(identity.audience()));
        claims.put("client_id", identity.clientId());
        claims.put("token_use", "user");
        claims.put("tenant_id", request.tenantId());
        claims.put("sub", request.subject());
        claims.put("roles", request.roles());
        // 签名面只带 region/dataLabels（见类注释"attributes 签名口径"）；bizSystemCode/userId 已按契约
        // 在 parse() 校验，但不进签名面——钉扎语义下它们必须与网关白名单静态值逐字相等。
        claims.put("attributes", request.attributes());
        claims.put("scope", identity.scope());
        claims.put("iat", iat);
        claims.put("nbf", iat);
        claims.put("exp", exp);
        claims.put("jti", jti);
        // 与 SessionService 同推导：会话 jti 的有界摘要。测试票因此也能走 SSE 第二跳的同一性比对。
        claims.put("origin_authentication_id", "oa1:" + sha256Hex(("portal-session:" + jti)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 32));

        String accessToken = keys.sign(TOKEN_TYPE, claims);
        // 审计行在返回体组装前写：写不进去就没有票。jti/iat 是签名前生成的，行内容与票面逐字段一致。
        String line = "AUDIT TEST_TICKET_MINTED subject=" + request.subject()
                + " roles=" + request.roles() + " tenant=" + request.tenantId()
                + " jti=" + jti + " issuer=" + identity.issuer() + " iat=" + iat;
        try {
            writer.emit(line);
        } catch (RuntimeException failed) {
            throw new AuthorityException(503, "TEST_TICKET_AUDIT_FAILED",
                    "测试票审计写入失败，已拒签", "audit_write_failed");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", accessToken);
        response.put("tokenType", "Bearer");
        response.put("expiresIn", request.ttlSeconds());
        response.put("jti", jti);
        return response;
    }

    /** 身份档案三项是票面 issuer/aud/client_id 的唯一来源；缺一就签出一张没人认的票，启动档不拦则请求档拦。 */
    private void requireProfileClaims() {
        if (isBlank(identity.issuer()) || isBlank(identity.audience()) || isBlank(identity.clientId())) {
            throw new AuthorityException(503, "TEST_TICKET_PROFILE_MISSING",
                    "测试票签发需要身份档案（agent.authority.identity 的 issuer/audience/client-id）",
                    "identity_profile_missing");
        }
        if (isBlank(identity.scope())) {
            throw new AuthorityException(503, "TEST_TICKET_PROFILE_MISSING",
                    "测试票签发需要身份档案 scope（默认 platform.access）", "identity_profile_missing");
        }
    }

    private Request parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw AuthorityException.invalidRequest("测试票请求体缺失或过大", "body_missing");
        }
        JsonNode root;
        try {
            root = Json.read(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw AuthorityException.invalidRequest("测试票请求体必须是 JSON 对象", "body_invalid");
        }
        if (root == null || !root.isObject()) {
            throw AuthorityException.invalidRequest("测试票请求体必须是 JSON 对象", "body_invalid");
        }
        List<String> unknown = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!Set.of("tenantId", "subject", "roles", "attributes", "ttlSeconds").contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw AuthorityException.invalidRequest("测试票请求含未知字段: " + String.join(",", unknown),
                    "unknown_fields");
        }
        String tenantId = requireText(root, "tenantId", MAX_TENANT_LENGTH, "tenantId");
        // 租户白名单（§9.2）：demo 租户 + t-* 测试租户前缀。前缀段必须有非空尾巴，避免裸 "t-"。
        if (!"demo".equals(tenantId)
                && !(tenantId.startsWith("t-") && tenantId.length() >= 3
                        && tenantId.matches("[A-Za-z0-9._-]+"))) {
            throw AuthorityException.invalidRequest(
                    "tenantId 不在测试租户白名单（demo、t-*）内: " + tenantId, "tenant_not_allowed");
        }
        String subject = requireText(root, "subject", MAX_SUBJECT_LENGTH, "subject");
        List<String> roles = roles(root);
        Map<String, List<String>> attributes = attributes(root);
        int ttl = ttlSeconds(root);
        // 签名面：region/dataLabels 两键（归属键的钉扎语义见类注释）。LinkedHashMap 保持键序稳定。
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("region", attributes.get("region"));
        signed.put("dataLabels", attributes.get("dataLabels"));
        return new Request(tenantId, subject, roles, signed, ttl);
    }

    private static String requireText(JsonNode root, String field, int maxLength, String label) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw AuthorityException.invalidRequest(label + " 必须是非空字符串", field + "_invalid");
        }
        if (value.textValue().length() > maxLength) {
            throw AuthorityException.invalidRequest(label + " 长度不得超过 " + maxLength, field + "_invalid");
        }
        return value.textValue();
    }

    private List<String> roles(JsonNode root) {
        JsonNode value = root.get("roles");
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw AuthorityException.invalidRequest("roles 必须是非空数组", "roles_invalid");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode role : value) {
            if (role == null || !role.isTextual() || role.textValue().isBlank()) {
                throw AuthorityException.invalidRequest("roles 每项必须是非空字符串", "roles_invalid");
            }
            if ("SERVICE".equals(role.textValue()) || "GATEWAY_AUTH".equals(role.textValue())) {
                // 契约红线：SERVICE 一律走 client_credentials 服务票，不得由测试端点签发人票。
                throw AuthorityException.invalidRequest(
                        "roles 含 " + role.textValue() + "：服务身份走 client_credentials，不得由本端点签发",
                        "role_service_forbidden");
            }
            if (!ALLOWED_ROLES.contains(role.textValue())) {
                throw AuthorityException.invalidRequest(
                        "roles 含未知角色 " + role.textValue() + "（允许 " + ALLOWED_ROLES + "）",
                        "role_unknown");
            }
            unique.add(role.textValue());
        }
        return List.copyOf(unique);
    }

    /** 校验并归一四键（契约请求形状）；返回值供签名面筛选（见类注释"attributes 签名口径"）。 */
    private Map<String, List<String>> attributes(JsonNode root) {
        JsonNode value = root.get("attributes");
        if (value == null || !value.isObject()) {
            throw AuthorityException.invalidRequest(
                    "attributes 必须是对象（region/dataLabels/bizSystemCode/userId）", "attributes_invalid");
        }
        List<String> unknown = new ArrayList<>();
        value.fieldNames().forEachRemaining(name -> {
            if (!ATTRIBUTE_KEYS.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw AuthorityException.invalidRequest("attributes 含未知键: " + String.join(",", unknown),
                    "attributes_invalid");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : List.of("region", "dataLabels", "bizSystemCode", "userId")) {
            JsonNode entry = value.get(key);
            if (entry == null) {
                throw AuthorityException.invalidRequest("attributes 缺少 " + key, "attributes_invalid");
            }
            List<String> values = new ArrayList<>();
            if (entry.isTextual()) {
                values.add(requireValue(entry.textValue(), key));
            } else if (entry.isArray()) {
                if (entry.size() > MAX_VALUES) {
                    throw AuthorityException.invalidRequest(
                            key + " 不得超过 " + MAX_VALUES + " 个值", "attributes_invalid");
                }
                for (JsonNode item : entry) {
                    if (item == null || !item.isTextual() || item.textValue().isBlank()) {
                        throw AuthorityException.invalidRequest(
                                key + " 的值必须是非空字符串", "attributes_invalid");
                    }
                    values.add(requireValue(item.textValue(), key));
                }
            } else {
                throw AuthorityException.invalidRequest(
                        key + " 必须是字符串或字符串数组", "attributes_invalid");
            }
            if (SINGLE_VALUE_KEYS.contains(key) && values.size() != 1) {
                throw AuthorityException.invalidRequest(
                        key + " 必须恰好一个值（平台归属钉扎要求）", "attributes_invalid");
            }
            result.put(key, List.copyOf(new LinkedHashSet<>(values)));
        }
        return result;
    }

    private static String requireValue(String value, String key) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw AuthorityException.invalidRequest(key + " 的值长度不得超过 " + MAX_VALUE_LENGTH,
                    "attributes_invalid");
        }
        return value;
    }

    private int ttlSeconds(JsonNode root) {
        JsonNode value = root.get("ttlSeconds");
        if (value == null || value.isNull()) {
            return DEFAULT_TTL_SECONDS;
        }
        if (!value.isIntegralNumber()) {
            throw AuthorityException.invalidRequest("ttlSeconds 必须是整数", "ttl_invalid");
        }
        long ttl = value.longValue();
        if (ttl < 1 || ttl > MAX_TTL_SECONDS) {
            throw AuthorityException.invalidRequest(
                    "ttlSeconds 必须在 1.." + MAX_TTL_SECONDS + "（7200 这类超预算一律 400）", "ttl_invalid");
        }
        return (int) ttl;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 已解析并校验过的请求（字段与 §9.2 契约一一对应；attributes 四键已归一为列表）。 */
    private record Request(String tenantId, String subject, List<String> roles,
                           Map<String, Object> attributes, int ttlSeconds) {
    }
}
