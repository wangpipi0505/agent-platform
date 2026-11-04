package cn.agentplatform.gateway.domain;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.IntegrationClaims;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * 出票声明仅能在平台管理员预设的上限内收缩；业务身份不能获得平台管理/内部调用角色。
 *
 * <p><b>INT-04 边界</b>：短票必须绑定 tenant+principal+app+integration+environment 上限及有效期，
 * 且 origin 精确匹配。tenant/principal 由凭据（服务端记录）与已验证身份派生，客户端自报的
 * tenant/principal 一律拒绝而不是"忽略后继续"——静默忽略会让业务方误以为绑定生效。
 *
 * <p>environment 的上限只能来自接入档案（{@code claimLimits.attributes.environment}）：
 * 网关没有 app-management 的档案读取端点，本期不改动 app 侧数据模型，因此由管理员在签发/修订
 * 凭据时把环境写入 claim limits；票据中的 {@code attributes.environment} 必须落在该上限内。
 * 未声明上限的旧凭据保持"不绑定环境"的既有语义（契约文档登记为待收敛缺口）。
 */
public final class IntegrationPolicy {

    /** 客户端不得自报的身份字段：出现即拒绝，避免"写了 tenant 但被静默覆盖"的误解。 */
    private static final Set<String> CLIENT_IDENTITY_FIELDS =
            Set.of("tenant", "tenantId", "tenant_id", "principal", "principalId", "principal_id");

    /** 页面上下文只允许业务展示信息；任何身份/凭据字段都不得借它进入 Actor。 */
    private static final Set<String> PAGE_CONTEXT_FORBIDDEN =
            Set.of(
                    "tenant", "tenantId", "tenant_id", "principal", "principalId", "principal_id",
                    "userId", "user_id", "roles", "permissions", "token", "accessToken",
                    "access_token", "authorization", "cookie", "secret", "secretRef");

    /** 直连 ApiKey 路径的环境选择头；值仍按接入档案上限逐字校验。 */
    public static final String ENVIRONMENT_HEADER = "X-Integration-Environment";

    private IntegrationPolicy() {}

    public static Actor clip(JsonNode credential, JsonNode requested) {
        active(credential);
        rejectClientIdentity(requested);
        JsonNode limits = credential.path("claimLimits");
        String user = requested.path("userId").asText(requested.path("user_id").asText(""));
        if (user.isBlank() || !strings(limits.path("principalIds")).contains(user))
            throw new PlatformException(403, "CLAIM_LIMIT_EXCEEDED", "用户不在签发声明上限内");
        Set<String> roles = strings(requested.path("roles"));
        roles.retainAll(strings(limits.path("roles")));
        // 平台管理/内部角色即使被签发者写进请求也不生效：这里是"不采信"，不是放行后再校验。
        roles.removeIf(IntegrationClaims::platformRole);
        Map<String, Set<String>> attributes = new LinkedHashMap<>();
        requested
                .path("attributes")
                .fields()
                .forEachRemaining(
                        e -> {
                            if (Set.of("bizSystemCode", "userId", IntegrationClaims.ENVIRONMENT_ATTRIBUTE)
                                    .contains(e.getKey()))
                                return;
                            Set<String> values = strings(e.getValue());
                            values.retainAll(strings(limits.path("attributes").path(e.getKey())));
                            if (!values.isEmpty()) attributes.put(e.getKey(), values);
                        });
        String environment = environment(credential, requested);
        if (environment != null)
            attributes.put(IntegrationClaims.ENVIRONMENT_ATTRIBUTE, Set.of(environment));
        String biz = credential.path("bizSystemCode").asText();
        attributes.put("bizSystemCode", Set.of(biz));
        attributes.put("userId", Set.of(user));
        String principal = IntegrationClaims.principal(biz, user);
        return new Actor(credential.path("tenantId").asText(), principal, roles, attributes);
    }

    /**
     * 环境上限校验。上限为空表示该凭据是 INT-04 之前签发的（没有环境维度），保持旧行为；
     * 一旦管理员声明了 {@code attributes.environment}，请求必须带值且逐字命中。
     */
    public static String environment(JsonNode credential, JsonNode requested) {
        Set<String> ceiling = environmentCeiling(credential);
        if (ceiling.isEmpty()) return null;
        String requestedEnv = requested.path("environment").asText("");
        if (requestedEnv.isBlank() || !ceiling.contains(requestedEnv))
            throw new PlatformException(
                    403, "CLAIM_LIMIT_EXCEEDED", "environment 不在接入档案声明的上限内");
        return requestedEnv;
    }

    /** 复核既有短票：凭据撤销或环境上限收紧后必须重新出票，不能继续沿用旧票据。 */
    public static void bound(JsonNode credential, String environment) {
        if (!"ACTIVE".equals(credential.path("status").asText()))
            throw new PlatformException(401, "INTEGRATION_TICKET_EXPIRED", "集成凭据已撤销，请重新授权");
        Set<String> ceiling = environmentCeiling(credential);
        if (!ceiling.isEmpty() && (environment == null || !ceiling.contains(environment)))
            throw new PlatformException(
                    401, "INTEGRATION_TICKET_EXPIRED", "接入档案已收紧，请重新出票");
    }

    public static Set<String> environmentCeiling(JsonNode credential) {
        return strings(
                credential.path("claimLimits")
                        .path("attributes")
                        .path(IntegrationClaims.ENVIRONMENT_ATTRIBUTE));
    }

    /**
     * 客户端自报身份/角色/tenant 不被采信：出现即拒绝。
     *
     * <p>角色走"与上限求交"而不是报错，是因为既有接入方会习惯性带上角色列表；求交后平台角色
     * 一律移除，等价于"不采信"。tenant/principal 不同：它们会改变主体，必须显式拒绝。
     */
    private static void rejectClientIdentity(JsonNode requested) {
        for (String field : CLIENT_IDENTITY_FIELDS) {
            JsonNode value = requested.get(field);
            if (value != null && !value.isNull())
                throw new PlatformException(
                        403, "INTEGRATION_CLIENT_IDENTITY_REJECTED", "客户端自报的 tenant/principal 不被采信");
        }
        JsonNode attributes = requested.path("attributes");
        if (attributes.isObject()) {
            for (String field : CLIENT_IDENTITY_FIELDS) {
                if (attributes.has(field))
                    throw new PlatformException(
                            403, "INTEGRATION_CLIENT_IDENTITY_REJECTED", "客户端自报的 tenant/principal 不被采信");
            }
        }
        // pageContext 是展示用上下文；携带身份或凭据字段即拒绝，而不是"读了但不用"。
        JsonNode pageContext = requested.path("pageContext");
        if (pageContext.isObject()) {
            for (String field : PAGE_CONTEXT_FORBIDDEN) {
                if (pageContext.has(field))
                    throw new PlatformException(
                            403,
                            "INTEGRATION_CLIENT_IDENTITY_REJECTED",
                            "pageContext 不得携带身份或凭据字段");
            }
        }
    }

    public static void active(JsonNode credential) {
        if (!"ACTIVE".equals(credential.path("status").asText()))
            throw new PlatformException(401, "CREDENTIAL_REVOKED", "集成凭据已吊销");
    }

    public static void origin(JsonNode credential, String origin) {
        if (origin != null && !strings(credential.path("allowedOrigins")).contains(origin))
            throw new PlatformException(
                    403, "INTEGRATION_SOURCE_NOT_ALLOWED", "当前来源未获授权");
    }

    public static String digest(JsonNode c) {
        return IntegrationClaims.digest(c);
    }

    public static boolean allowedPath(String path, String method, String app) {
        if ("GET".equals(method) && path.equals("/v1/apps/" + app + "/experience")) return true;
        if ("GET".equals(method) && path.equals("/v1/apps/" + app + "/input-schema")) return true;
        if ("POST".equals(method) && path.equals("/v1/apps/" + app + "/runs")) return true;
        // 应用名使用相等比较，禁止把凭据绑定值直接拼入正则；session主体仍由engine检查。
        String sessions = "/v1/apps/" + app + "/sessions";
        if ("POST".equals(method) && path.equals(sessions)) return true;
        if (path.startsWith(sessions + "/")) {
            String tail = path.substring(sessions.length() + 1);
            if ("GET".equals(method) && tail.matches("[A-Za-z0-9_-]+")) return true;
            if ("POST".equals(method) && tail.matches("[A-Za-z0-9_-]+/turns")) return true;
        }
        if ("GET".equals(method))
            return path.matches(
                    "/v1/runs/[A-Za-z0-9_-]+(?:/events|/approvals|/semantic-results|/artifacts/[A-Za-z0-9_-]+/download)?");
        return "POST".equals(method)
                && path.matches("/v1/runs/[A-Za-z0-9_-]+/(?:cancel|approvals/[0-9]+/decision)");
    }

    private static Set<String> strings(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        if (node.isArray())
            node.forEach(
                    v -> {
                        if (v.isTextual()) result.add(v.asText());
                    });
        return result;
    }
}
