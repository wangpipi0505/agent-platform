package cn.agentplatform.gateway.domain;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.authority.IntegrationClaimPolicy;
import cn.agentplatform.common.authority.IntegrationClaims;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Paths and credential restrictions for gateway integration requests. */
public final class IntegrationPolicy {
    public static final String ENVIRONMENT_HEADER = IntegrationClaimPolicy.ENVIRONMENT_HEADER;

    private IntegrationPolicy() {}

    public static Actor clip(JsonNode credential, JsonNode requested) {
        return IntegrationClaimPolicy.clip(credential, requested);
    }

    public static String environment(JsonNode credential, JsonNode requested) {
        return IntegrationClaimPolicy.environment(credential, requested);
    }

    public static void bound(JsonNode credential, String environment) {
        IntegrationClaimPolicy.bound(credential, environment);
    }

    public static Set<String> environmentCeiling(JsonNode credential) {
        return IntegrationClaimPolicy.environmentCeiling(credential);
    }

    public static void active(JsonNode credential) {
        IntegrationClaimPolicy.active(credential);
    }

    public static void origin(JsonNode credential, String origin) {
        IntegrationClaimPolicy.origin(credential, origin);
    }

    public static String digest(JsonNode credential) {
        return IntegrationClaims.digest(credential);
    }

    public static boolean allowedPath(String path, String method, String app) {
        if ("GET".equals(method) && path.equals("/v1/apps/" + app + "/experience")) return true;
        if ("GET".equals(method) && path.equals("/v1/apps/" + app + "/input-schema")) return true;
        if ("POST".equals(method) && path.equals("/v1/apps/" + app + "/runs")) return true;
        // T2-15（台账 §7.3）：任务域四端点对 SERVICE 票据面开放（首期仅 AGENT——mode=PLAN 由引擎
        // Tasks.create 外部 caller 分支拒绝并审计，网关请求体是有界流转发、不解析业务负载）。
        // 旧票路径保持这四条。IAM 中断使用独立的实时策略复核和引擎归属校验。
        if ("POST".equals(method) && path.equals("/v1/apps/" + app + "/tasks")) return true;
        if ("GET".equals(method)
                && path.matches("/v1/tasks/[A-Za-z0-9_-]+(?:/events|/result)?")) return true;
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
}
