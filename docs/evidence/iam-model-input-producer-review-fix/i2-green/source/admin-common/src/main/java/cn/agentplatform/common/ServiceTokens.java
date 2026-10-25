package cn.agentplatform.common;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Legacy explicit dev/test HMAC only; IAM listeners are closed until authority exchange is installed.
 * This key domain is never a fallback for TrustedJwtDecoder's RS256 portal/service domains. */
public final class ServiceTokens {
    public static final String ISSUER = "agent-platform-v12";

    private ServiceTokens() {}

    // One classloader lifetime: context close/start failure and JVM property edits cannot
    // reopen IAM. Already signed strings and previously running listeners are not revoked.
    private static String deploymentMode;

    static synchronized void registerDeployment(String mode) {
        if ("iam".equals(deploymentMode) && !"iam".equals(mode))
            throw new PlatformException(403, "AUTHORITY_CONTEXT_MISSING", "IAM部署已关闭旧票据签发");
        deploymentMode = mode;
    }

    public static synchronized String issue(Actor actor) {
        if (deploymentMode == null || "iam".equals(deploymentMode))
            throw new PlatformException(403, "AUTHORITY_CONTEXT_MISSING", "服务票据必须由当前可信授权签发");
        return sign(actor, "agent-platform-internal", "service", 120, Map.of());
    }

    public static String userToken(Actor actor, int ttl) {
        return sign(actor, "agent-platform", "user", ttl, Map.of());
    }

    public static String integrationToken(
            Actor actor, String app, String biz, long credentialId, String policyDigest, int ttl) {
        if (ttl < 1 || ttl > 300) throw new IllegalArgumentException("出票有效期必须为1至300秒");
        String user =
                actor.attributes()
                        .getOrDefault("userId", Set.of(actor.principalId()))
                        .iterator()
                        .next();
        return sign(
                actor,
                "agent-platform-embed",
                "integration",
                ttl,
                Map.of(
                        "app_code",
                        app,
                        "biz_system_code",
                        biz,
                        "user_id",
                        user,
                        "credential_id",
                        credentialId,
                        "policy_digest",
                        policyDigest));
    }

    // JLS 21 §§17.1/17.4.5: the same class monitor covers registration, issue
    // permission and signing; its unlock is visible to subsequent signing calls.
    // ponytail: one short classloader lock; no cross-context registry or throughput framework.
    private static synchronized String sign(
            Actor actor, String audience, String use, int ttl, Map<String, Object> extra) {
        if ("iam".equals(deploymentMode))
            throw new PlatformException(403, "AUTHORITY_CONTEXT_MISSING", "IAM部署已关闭旧票据签发");
        long now = Instant.now().getEpochSecond();
        Set<String> roles = new HashSet<>(actor.roles());
        if ("service".equals(use)) roles.add("SERVICE");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", actor.principalId());
        claims.put("tenant_id", actor.tenantId());
        claims.put("aud", audience);
        claims.put("iat", now);
        claims.put("exp", now + ttl);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("token_use", use);
        claims.put("roles", roles);
        claims.put("attributes", actor.attributes());
        claims.putAll(extra);
        String signing =
                b64(Json.write(Map.of("alg", "HS256", "typ", "JWT")))
                        + "."
                        + b64(Json.write(claims));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes(), "HmacSHA256"));
            return signing
                    + "."
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(mac.doFinal(signing.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("内部票据签发失败", e);
        }
    }

    public static byte[] secretBytes() {
        String value = System.getenv("AGENT_JWT_SECRET");
        if (value == null || value.isBlank()) value = System.getProperty("agent.jwt.secret", "");
        if (value.length() < 32) throw new IllegalStateException("必须为新工程配置至少32字符的独立JWT密钥");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
