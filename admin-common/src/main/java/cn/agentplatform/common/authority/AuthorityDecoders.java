package cn.agentplatform.common.authority;

import cn.agentplatform.common.Json;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * EX-43 §3.4 iam inbound decoder factory.
 *
 * <p>Both trust domains are fixed by deployment configuration: no issuer, JWKS URL or key is ever
 * read from a token. The authority domain accepts {@code aud=urn:agent-platform:service:<self>}
 * with {@code token_use=service}; the portal domain (gateway only) accepts the portal user audience
 * with {@code token_use=user}. {@link NimbusJwtDecoder#withJwkSetUri} fetches the key set lazily on
 * first verification, so constructing a decoder performs no network call: an authority outage is a
 * request-time 503, never a startup failure.
 */
public final class AuthorityDecoders {
    private AuthorityDecoders() {}

    /** Builds the iam decoder for this deployment; dev-hmac never calls this factory. */
    public static JwtDecoder iam(AuthorityProperties config) {
        Objects.requireNonNull(config, "config");
        var authority = domain(config.authority().jwksUri(), config.authority().issuer(),
                config.authority().audience(), "service");
        if (config.portal() == null || !config.portal().enabled()) return authority;
        var portal = domain(config.portal().jwksUri(), config.portal().issuer(),
                config.portal().audience(), "user");
        return new IssuerRoutingDecoder(Map.of(
                config.authority().issuer(), authority,
                config.portal().issuer(), portal));
    }

    private static JwtDecoder domain(String jwksUri, String issuer, String audience, String tokenUse) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"))))
                .build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> tokenUse.equals(jwt.getClaimAsString("token_use"))
                && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "票据受众无效", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }

    /**
     * Selects a fixed decoder by the unverified issuer only. Unknown issuers are rejected without
     * touching either key set, so a forged token cannot choose a URL or probe the authority.
     */
    static final class IssuerRoutingDecoder implements JwtDecoder {
        private final Map<String, JwtDecoder> domains;

        IssuerRoutingDecoder(Map<String, JwtDecoder> domains) {
            this.domains = Map.copyOf(domains);
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            var decoder = domains.get(unverifiedIssuer(token));
            if (decoder == null) throw new BadJwtException("JWT_ISSUER_UNKNOWN");
            return decoder.decode(token);
        }

        static String unverifiedIssuer(String token) {
            try {
                if (token == null) return null;
                var parts = token.split("\\.", -1);
                if (parts.length != 3 || parts[1].isEmpty()) return null;
                var payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                var issuer = Json.read(payload).path("iss");
                return issuer.isTextual() ? issuer.textValue() : null;
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }
}
