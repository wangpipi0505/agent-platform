package cn.agentplatform.common.authority;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-43 §3.4: the iam decoder trusts two fixed domains. The service audience is
 * {@code urn:agent-platform:service:<self>} with {@code token_use=service}; the portal user
 * audience ({@code agent-platform}/portal-gateway) exists only on the gateway portal face.
 */
class IamInboundDecoderTest {
    static LoopbackIssuer issuer;

    @BeforeAll
    static void startIssuer() throws Exception {
        issuer = new LoopbackIssuer();
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @Test
    void serviceDeploymentAcceptsOnlyTheConfiguredAuthorityDomain() {
        var decoder = AuthorityDecoders.iam(issuer.internalConfig());
        var jwt = decoder.decode(issuer.sign(issuer.service(Instant.now(), false)));
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("service");
        assertThat(jwt.getAudience()).containsExactly("urn:agent-platform:service:app");
    }

    @Test
    void serviceDeploymentRejectsWrongAudienceUnknownIssuerAndPortalUserTokens() {
        var decoder = AuthorityDecoders.iam(issuer.internalConfig());

        var wrongAudience = issuer.service(Instant.now(), false);
        wrongAudience.put("aud", List.of("urn:agent-platform:service:gateway"));
        assertThatThrownBy(() -> decoder.decode(issuer.sign(wrongAudience)))
                .isInstanceOf(JwtException.class);

        var wrongIssuer = issuer.service(Instant.now(), false);
        wrongIssuer.put("iss", issuer.issuer() + "/somewhere-else");
        assertThatThrownBy(() -> decoder.decode(issuer.sign(wrongIssuer)))
                .isInstanceOf(JwtException.class);

        assertThatThrownBy(() -> decoder.decode(issuer.sign(issuer.portal(Instant.now()))))
                .as("user tokens belong to the gateway portal face only").isInstanceOf(JwtException.class);
    }

    @Test
    void gatewayDeploymentAcceptsBothPortalUserAndServiceAudiences() {
        var decoder = AuthorityDecoders.iam(issuer.config());

        var user = decoder.decode(issuer.sign(issuer.portal(Instant.now())));
        assertThat(user.getClaimAsString("token_use")).isEqualTo("user");
        assertThat(user.getAudience()).contains("portal-gateway");

        var claims = issuer.service(Instant.now(), false);
        claims.put("aud", List.of("urn:agent-platform:service:gateway"));
        var service = decoder.decode(issuer.sign(claims));
        assertThat(service.getClaimAsString("token_use")).isEqualTo("service");

        var wrongAudience = issuer.portal(Instant.now());
        wrongAudience.put("aud", List.of("urn:agent-platform:service:gateway"));
        assertThatThrownBy(() -> decoder.decode(issuer.sign(wrongAudience)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void decoderConstructionIsLazySoStartupMakesNoNetworkCall() {
        int before = issuer.requests.get();
        AuthorityDecoders.iam(issuer.internalConfig());
        AuthorityDecoders.iam(issuer.config());
        assertThat(issuer.requests).as("no JWKS fetch while building the inbound decoder").hasValue(before);

        AuthorityDecoders.iam(issuer.internalConfig())
                .decode(issuer.sign(issuer.service(Instant.now(), false)));
        assertThat(issuer.requests.get()).as("the key set is fetched on first verification only")
                .isGreaterThan(before);
    }
}
