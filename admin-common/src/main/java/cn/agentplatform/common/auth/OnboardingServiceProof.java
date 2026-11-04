package cn.agentplatform.common.auth;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 显式 dev-hmac 的 app→skill 附加请求证明；调用方仍须校验服务 JWT、角色与精确允许路由。
 * IAM 不调用此工具，也不把证明转成当前授权上下文。nonce 供审计，短期证明本身不承担一次性消费。
 */
public final class OnboardingServiceProof {
    public static final String HEADER = "X-Agent-Onboarding-Proof";
    private static final String KEY_REF = "env:AGENT_ONBOARDING_DEV_APP_KEY";
    private static final Set<String> FIELDS = Set.of("kind", "caller", "target", "tenant", "principal", "method",
            "pathAndQuery", "bodySha256", "issuedAt", "expiresAt", "nonce");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private OnboardingServiceProof() {}

    public static String issue(String keyRef, Actor actor, String method, String pathAndQuery, Object body) {
        byte[] key = key(keyRef);
        try {
            var claims = binding(actor, method, pathAndQuery, body);
            long now = Instant.now().getEpochSecond();
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            claims.put("issuedAt", now);
            claims.put("expiresAt", now + 60);
            claims.put("nonce", ENCODER.encodeToString(nonce));
            String payload = ENCODER.encodeToString(Json.canonical(claims).getBytes(StandardCharsets.UTF_8));
            String proof = payload + "." + ENCODER.encodeToString(sign(key, payload));
            if (proof.length() > 4096) throw invalid();
            return proof;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public static Map<String, Object> verify(String keyRef, Actor actor, String method, String pathAndQuery,
            Object body, String proof) {
        byte[] key = key(keyRef);
        try {
            if (proof == null || proof.length() > 4096
                    || !proof.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}")) throw invalid();
            String[] parts = proof.split("\\.", -1);
            byte[] signature = DECODER.decode(parts[1]);
            if (!ENCODER.encodeToString(signature).equals(parts[1])
                    || !MessageDigest.isEqual(sign(key, parts[0]), signature)) throw invalid();
            byte[] payload = DECODER.decode(parts[0]);
            if (!ENCODER.encodeToString(payload).equals(parts[0])) throw invalid();
            var claims = Json.read(new String(payload, StandardCharsets.UTF_8));
            if (!claims.isObject() || claims.size() != FIELDS.size()) throw invalid();
            var names = new java.util.HashSet<String>();
            claims.fieldNames().forEachRemaining(names::add);
            if (!FIELDS.equals(names)) throw invalid();
            for (var entry : binding(actor, method, pathAndQuery, body).entrySet())
                if (!claims.path(entry.getKey()).isTextual()
                        || !entry.getValue().equals(claims.path(entry.getKey()).asText())) throw invalid();
            var issued = claims.path("issuedAt");
            var expires = claims.path("expiresAt");
            if (!issued.isIntegralNumber() || !issued.canConvertToLong()
                    || !expires.isIntegralNumber() || !expires.canConvertToLong()) throw invalid();
            long iat = issued.asLong(), exp = expires.asLong(), now = Instant.now().getEpochSecond();
            if (iat < 0 || exp <= iat || exp - iat > 60 || iat > now + 5 || exp < now - 5) throw invalid();
            String nonce = claims.path("nonce").asText();
            if (!claims.path("nonce").isTextual() || !nonce.matches("[A-Za-z0-9_-]{22}")
                    || !ENCODER.encodeToString(DECODER.decode(nonce)).equals(nonce)) throw invalid();
            Map<String, Object> audit = new LinkedHashMap<>();
            for (String field : FIELDS) {
                var value = claims.path(field);
                audit.put(field, value.isIntegralNumber() ? value.longValue() : value.asText());
            }
            return Map.copyOf(audit);
        } catch (RuntimeException failure) {
            throw invalid();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Map<String, Object> binding(Actor actor, String method, String pathAndQuery, Object body) {
        if (actor == null || method == null || !method.matches("[A-Z]{3,12}")
                || pathAndQuery == null || pathAndQuery.length() > 2048
                || pathAndQuery.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        URI path;
        try { path = URI.create(pathAndQuery); } catch (IllegalArgumentException failure) { throw invalid(); }
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getRawFragment() != null
                || path.getRawPath() == null || !path.getRawPath().startsWith("/")) throw invalid();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("kind", "onboarding-dev-app");
        claims.put("caller", "app");
        claims.put("target", "skill");
        claims.put("tenant", actor.tenantId());
        claims.put("principal", actor.principalId());
        claims.put("method", method);
        claims.put("pathAndQuery", pathAndQuery);
        claims.put("bodySha256", Digest.sha256(Json.canonical(body)));
        return claims;
    }

    private static byte[] key(String keyRef) {
        if (!KEY_REF.equals(keyRef)) throw missingKey();
        String value = System.getenv("AGENT_ONBOARDING_DEV_APP_KEY");
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (value == null || value.isBlank() || bytes.length < 32) {
            Arrays.fill(bytes, (byte) 0);
            throw missingKey();
        }
        return bytes;
    }

    private static byte[] sign(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException failure) {
            throw missingKey();
        }
    }

    private static PlatformException invalid() {
        return new PlatformException(403, "ONBOARDING_SERVICE_PROOF_INVALID", "应用专用请求证明无效或已过期");
    }

    private static PlatformException missingKey() {
        return new PlatformException(503, "ONBOARDING_SERVICE_PROOF_REQUIRED", "未配置独立的应用导入证明密钥");
    }
}
