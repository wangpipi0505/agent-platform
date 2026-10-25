package cn.agentplatform.common.authority;

import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TrustedJwtDecoderTest {
    static final Instant NOW=Instant.parse("2026-10-20T00:00:00Z");
    @Test void validRsaSignatureWorksButWrongSignatureNeverPasses() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var decoder=new TrustedJwtDecoder(issuer.config(),Clock.fixed(NOW,ZoneOffset.UTC),true);
            assertThat(decoder.decode(issuer.sign(issuer.portal(NOW))).getSubject()).isEqualTo("alice");
            assertThatThrownBy(()->decoder.decode(issuer.sign(issuer.portal(NOW),issuer.k2,"k1","at+jwt")))
                .isInstanceOf(JwtException.class);
        }
    }
}
