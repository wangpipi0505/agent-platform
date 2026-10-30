package cn.agentplatform.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * 测试侧的 JWT 解码/验签工具：刻意不复用生产 {@code KeyRing}，否则"用同一段代码验自己签的票"
 * 会把签名实现的错误一起放过。这里从 JWKS 的 {@code n/e} 用 {@link KeyFactory} 还原公钥，
 * 再用 JDK {@link Signature} 独立验签，等价于平台消费者做的事。
 */
public final class TestJwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestJwt() {
    }

    public static JsonNode header(String token) {
        return part(token, 0);
    }

    public static JsonNode claims(String token) {
        return part(token, 1);
    }

    private static JsonNode part(String token, int index) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("不是三段式 JWS");
        }
        try {
            return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[index]));
        } catch (Exception e) {
            throw new IllegalArgumentException("JWS 段不是合法 JSON", e);
        }
    }

    public static PublicKey publicKey(JsonNode jwk) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asText()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asText()));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWK 无法还原为 RSA 公钥", e);
        }
    }

    /** 验签并返回 claims；typ/alg 与签名任一不符即抛 {@link IllegalStateException}。 */
    public static JsonNode verifiedClaims(String token, String expectedTyp, PublicKey key) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("不是三段式 JWS");
        }
        JsonNode header = header(token);
        if (!"RS256".equals(header.path("alg").asText())) {
            throw new IllegalStateException("alg 不是 RS256: " + header.path("alg").asText());
        }
        if (!expectedTyp.equals(header.path("typ").asText())) {
            throw new IllegalStateException("typ 不是 " + expectedTyp + ": " + header.path("typ").asText());
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new IllegalStateException("签名校验失败");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名校验失败", e);
        }
        return claims(token);
    }
}
