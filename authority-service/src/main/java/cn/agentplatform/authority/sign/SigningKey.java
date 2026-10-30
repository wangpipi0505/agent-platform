package cn.agentplatform.authority.sign;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一把签发密钥及其对外 JWK 表示；{@code kid} 是平台侧 JWKS 缓存与轮换的唯一锚点。 */
public record SigningKey(String kid, RSAPrivateKey privateKey, RSAPublicKey publicKey) {

    /** RFC 7517 JWK：只暴露公钥参数，{@code use/alg} 固定，平台侧按 {@code kid} 选键。 */
    public Map<String, Object> jwk() {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid);
        jwk.put("n", unsigned(publicKey.getModulus().toByteArray()));
        jwk.put("e", unsigned(publicKey.getPublicExponent().toByteArray()));
        return jwk;
    }

    /**
     * {@code BigInteger.toByteArray()} 会给正数补一个 0x00 符号位；不裁掉的话 JWK 的模数会比真实值
     * 多一个前导零字节，某些 JOSE 实现还原出的公钥与签发密钥不一致。
     */
    private static String unsigned(byte[] magnitude) {
        int offset = magnitude.length > 1 && magnitude[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Arrays.copyOfRange(magnitude, offset, magnitude.length));
    }
}
