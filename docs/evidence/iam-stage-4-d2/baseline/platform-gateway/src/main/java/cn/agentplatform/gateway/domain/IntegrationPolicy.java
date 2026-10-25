package cn.agentplatform.gateway.domain;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/** 出票声明仅能在平台管理员预设的上限内收缩；业务身份不能获得平台管理/内部调用角色。 */
public final class IntegrationPolicy {
    private static final Set<String> PLATFORM_ROLES =
            Set.of("ADMIN", "EDITOR", "SERVICE", "GATEWAY_AUTH");

    private IntegrationPolicy() {}

    public static Actor clip(JsonNode credential, JsonNode requested) {
        active(credential);
        JsonNode limits = credential.path("claimLimits");
        String user = requested.path("userId").asText(requested.path("user_id").asText(""));
        if (user.isBlank() || !strings(limits.path("principalIds")).contains(user))
            throw new PlatformException(403, "CLAIM_LIMIT_EXCEEDED", "用户不在签发声明上限内");
        Set<String> roles = strings(requested.path("roles"));
        roles.retainAll(strings(limits.path("roles")));
        roles.removeIf(r -> PLATFORM_ROLES.contains(r.toUpperCase(Locale.ROOT)));
        Map<String, Set<String>> attributes = new LinkedHashMap<>();
        requested
                .path("attributes")
                .fields()
                .forEachRemaining(
                        e -> {
                            if (Set.of("bizSystemCode", "userId").contains(e.getKey())) return;
                            Set<String> values = strings(e.getValue());
                            values.retainAll(strings(limits.path("attributes").path(e.getKey())));
                            if (!values.isEmpty()) attributes.put(e.getKey(), values);
                        });
        String biz = credential.path("bizSystemCode").asText();
        attributes.put("bizSystemCode", Set.of(biz));
        attributes.put("userId", Set.of(user));
        String principal = biz + ":" + user;
        if (principal.length() > 64)
            principal = "ext_" + Digest.sha256(biz + "\u0000" + user).substring(0, 60);
        return new Actor(credential.path("tenantId").asText(), principal, roles, attributes);
    }

    public static void active(JsonNode credential) {
        if (!"ACTIVE".equals(credential.path("status").asText()))
            throw new PlatformException(401, "CREDENTIAL_REVOKED", "集成凭据已吊销");
    }

    public static void origin(JsonNode credential, String origin) {
        if (origin != null && !strings(credential.path("allowedOrigins")).contains(origin))
            throw new PlatformException(403, "ORIGIN_DENIED", "当前来源未获授权");
    }

    public static String digest(JsonNode c) {
        return Digest.sha256(
                Json.canonical(
                        Map.of(
                                "secretRef",
                                c.path("secretRef"),
                                "claimLimits",
                                c.path("claimLimits"),
                                "allowedOrigins",
                                c.path("allowedOrigins"))));
    }

    public static boolean allowedPath(String path, String method, String app) {
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
                    "/v1/runs/[A-Za-z0-9_-]+(?:/events|/approvals|/artifacts/[A-Za-z0-9_-]+/download)?");
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
