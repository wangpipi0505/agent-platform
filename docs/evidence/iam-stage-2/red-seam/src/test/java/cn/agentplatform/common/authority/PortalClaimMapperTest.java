package cn.agentplatform.common.authority;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PortalClaimMapperTest {
    @Test void issuerClientCeilingClipsPermissionsAndPreservesSensitivityObligations() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var jwt=Jwt.withTokenValue("already-verified-test-input").header("alg","RS256")
                .claims(c->c.putAll(issuer.portal(TrustedJwtDecoderTest.NOW)))
                .issuedAt(TrustedJwtDecoderTest.NOW).expiresAt(TrustedJwtDecoderTest.NOW.plusSeconds(300)).build();
            var mapped=new PortalClaimMapper(issuer.config().portal()).map(jwt);
            assertThat(mapped.actor().roles()).containsExactly("EDITOR");
            assertThat(mapped.actor().attributes().get("region")).containsExactly("A");
            assertThat(mapped.actor().attributes().get("dataLabels")).containsExactlyInAnyOrder("confidential","restricted");
            assertThat(mapped.scopes()).containsExactly("platform.access");
        }
    }
}
