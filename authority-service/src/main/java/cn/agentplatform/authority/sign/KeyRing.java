package cn.agentplatform.authority.sign;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.StrictJson;
import cn.agentplatform.authority.config.AuthorityServiceProperties;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * RSA 签发/验签的唯一实现（JDK {@code java.security}，不引入 JOSE 库，避免为 3 个端点背一个
 * 解析器的供应链面）。
 * <p>验签与平台侧 {@code AuthorityResponseVerifier} 保持同等严格度：只认 RS256、禁止 {@code jku/x5u/jwk/x5c/crit}
 * 这类"由令牌自带密钥"的头部（否则等于允许自签），并要求 {@code iat/nbf/exp} 完整且时间窗自洽。
 */
public final class KeyRing {

    private static final Logger log = LoggerFactory.getLogger(KeyRing.class);
    private static final String ALGORITHM = "RS256";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int MAX_TOKEN_CHARS = 16384;
    private static final int MIN_KEY_BITS = 2048;
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final List<String> FORBIDDEN_HEADERS = List.of("jku", "x5u", "jwk", "x5c", "crit");

    private final SigningKey active;
    private final SigningKey previous;
    private final Clock clock;

    public KeyRing(AuthorityServiceProperties properties, Clock clock) {
        this.clock = clock;
        if (properties.keyPath() == null || properties.keyPath().isBlank()) {
            // 没有稳定私钥 = 重启即换 kid，平台缓存全部失效；开发可用，生产必须挂载。
            log.warn("authority.key ephemeral kid={}：未配置 agent.authority.key-path，重启后 kid 会变化，生产必须挂载稳定私钥",
                    properties.kid());
            this.active = generate(properties.kid());
        } else {
            this.active = load(properties.kid(), properties.keyPath());
        }
        this.previous = properties.hasPreviousKey() ? load(properties.previousKid(), properties.previousKeyPath()) : null;
    }

    public String activeKid() {
        return active.kid();
    }

    /** JWKS 响应体：active 在前，轮换期同时暴露 previous 供平台侧平滑换键。 */
    public Map<String, Object> jwks() {
        List<Map<String, Object>> keys = new ArrayList<>(2);
        keys.add(active.jwk());
        if (previous != null) {
            keys.add(previous.jwk());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keys", keys);
        return body;
    }

    /** 签名。header 由本类拼装（{@code typ/kid} 都是内部常量/已校验值），不接受外部头字段。 */
    public String sign(String typ, Map<String, Object> claims) {
        String header = "{\"alg\":\"" + ALGORITHM + "\",\"typ\":\"" + typ + "\",\"kid\":\"" + active.kid() + "\"}";
        String payload = Json.write(claims);
        String signingInput = encode(header.getBytes(StandardCharsets.UTF_8))
                + "." + encode(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(active.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AUTHORITY_SIGN_FAILED: 票据签名失败", e);
        }
    }

    /**
     * 验签 + 头部白名单 + 时间窗自洽，返回已解析的 claims。
     * <p>任何不满足都抛 403（调用方看到的是"当前授权不允许此操作"），审计原因保留在日志。
     */
    public JsonNode verify(String token, String expectedTyp) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) {
            throw AuthorityException.denied("token 缺失或超长");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw AuthorityException.denied("token 不是三段式 JWS");
        }
        for (String part : parts) {
            if (!BASE64URL.matcher(part).matches()) {
                throw AuthorityException.denied("token 含非 base64url 字符");
            }
        }
        JsonNode header = decode(parts[0]);
        if (!ALGORITHM.equals(text(header, "alg"))) {
            throw AuthorityException.denied("alg 必须是 RS256");
        }
        if (!Objects.equals(expectedTyp, text(header, "typ"))) {
            throw AuthorityException.denied("typ 与预期不符");
        }
        for (String forbidden : FORBIDDEN_HEADERS) {
            if (header.has(forbidden)) {
                throw AuthorityException.denied("header 含禁用字段 " + forbidden);
            }
        }
        SigningKey key = keyFor(text(header, "kid"));
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw AuthorityException.denied("签名不是合法 base64url");
        }
        boolean valid;
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            valid = verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw AuthorityException.denied("签名校验异常");
        }
        if (!valid) {
            throw AuthorityException.denied("签名不匹配");
        }
        JsonNode claims = decode(parts[1]);
        long now = clock.instant().getEpochSecond();
        long iat = integral(claims, "iat");
        long nbf = integral(claims, "nbf");
        long exp = integral(claims, "exp");
        if (iat > now) {
            throw AuthorityException.denied("iat 在未来");
        }
        if (nbf > now || nbf < iat) {
            throw AuthorityException.denied("nbf 非法");
        }
        if (exp <= now) {
            throw AuthorityException.denied("票据已过期");
        }
        if (exp <= iat) {
            throw AuthorityException.denied("exp 必须晚于 iat");
        }
        return claims;
    }

    private SigningKey keyFor(String kid) {
        if (kid == null || kid.isBlank()) {
            throw AuthorityException.denied("header 缺少 kid");
        }
        if (kid.equals(active.kid())) {
            return active;
        }
        if (previous != null && kid.equals(previous.kid())) {
            return previous;
        }
        throw AuthorityException.denied("kid 不在当前 JWKS 中");
    }

    private static JsonNode decode(String part) {
        try {
            return StrictJson.read(Base64.getUrlDecoder().decode(part));
        } catch (IOException | IllegalArgumentException e) {
            throw AuthorityException.denied("JWS 分段不是合法 JSON");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static long integral(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw AuthorityException.denied("claims." + field + " 必须是整数");
        }
        return value.longValue();
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SigningKey generate(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            return new SigningKey(kid, (RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AUTHORITY_KEY_INVALID: 无法生成 RSA 密钥", e);
        }
    }

    private static SigningKey load(String kid, String path) {
        byte[] raw = readBytes(path);
        String pem = new String(raw, StandardCharsets.US_ASCII);
        if (!pem.contains("BEGIN")) {
            throw new IllegalStateException("AUTHORITY_KEY_INVALID: " + path + " 不是 PEM 格式私钥");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(pem
                    .replaceAll("-----BEGIN [A-Z0-9 ]+-----", "")
                    .replaceAll("-----END [A-Z0-9 ]+-----", "")
                    .replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AUTHORITY_KEY_INVALID: " + path + " 的 PEM base64 非法", e);
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                // 无 CRT 参数就拿不到公钥指数 e，也就无法发布 JWKS。
                throw new IllegalStateException("AUTHORITY_KEY_INVALID: " + path + " 缺少 CRT 参数（需要 PKCS#8 RSA 私钥）");
            }
            if (crt.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException("AUTHORITY_KEY_INVALID: " + path + " 的 RSA 密钥不足 " + MIN_KEY_BITS + " 位");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new SigningKey(kid, crt, publicKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AUTHORITY_KEY_INVALID: " + path + " 不是合法的 PKCS#8 RSA 私钥", e);
        }
    }

    private static byte[] readBytes(String path) {
        try {
            if (path.startsWith("classpath:")) {
                try (InputStream in = new ClassPathResource(path.substring("classpath:".length())).getInputStream()) {
                    return in.readAllBytes();
                }
            }
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("AUTHORITY_KEY_INVALID: 无法读取签发密钥 " + path, e);
        }
    }
}
