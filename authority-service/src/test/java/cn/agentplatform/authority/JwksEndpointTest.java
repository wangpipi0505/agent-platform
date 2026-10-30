package cn.agentplatform.authority;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWKS 契约：平台侧 {@code TrustedJwtDecoder/AuthorityResponseVerifier} 会用 Nimbus 解析这份 JWK
 * 并据此验签，所以这里断言的不只是"字段齐全"，而是"能被 {@code KeyFactory} 还原成 2048 位 RSA 公钥"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                AuthorityTestSupport.P_ISSUER,
                AuthorityTestSupport.P_KID,
                AuthorityTestSupport.P_KEY,
                AuthorityTestSupport.P_GATEWAY,
                AuthorityTestSupport.P_APP})
class JwksEndpointTest extends AuthorityTestSupport {

    @Test
    void servesExactlyOneRsaKeyThatKeyFactoryCanRestore() {
        ResponseEntity<JsonNode> response = rest.getForEntity(url("/.well-known/jwks.json"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=300");

        JsonNode keys = response.getBody().get("keys");
        assertThat(keys).isNotNull();
        assertThat(keys).hasSize(1);

        JsonNode jwk = keys.get(0);
        assertThat(jwk.get("kty").asText()).isEqualTo("RSA");
        assertThat(jwk.get("use").asText()).isEqualTo("sig");
        assertThat(jwk.get("alg").asText()).isEqualTo("RS256");
        assertThat(jwk.get("kid").asText()).isEqualTo(KID);
        assertThat(jwk.get("n").asText()).isNotBlank();
        assertThat(jwk.get("e").asText()).isEqualTo("AQAB");

        PublicKey key = TestJwt.publicKey(jwk);
        assertThat(key).isInstanceOf(RSAPublicKey.class);
        assertThat(((RSAPublicKey) key).getModulus().bitLength()).isEqualTo(2048);
        assertThat(((RSAPublicKey) key).getPublicExponent()).isEqualTo(java.math.BigInteger.valueOf(65537));
    }

    @Test
    void publishedKeyVerifiesASignatureProducedByTheService() throws Exception {
        String ticket = gatewayTicket("nonce-jwks-1");

        String[] parts = ticket.split("\\.");
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withRSA");
        signature.initVerify(publishedKey());
        signature.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThat(signature.verify(java.util.Base64.getUrlDecoder().decode(parts[2])))
                .as("JWKS 公钥必须与签发私钥配对")
                .isTrue();
    }
}
