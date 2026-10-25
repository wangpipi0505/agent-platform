package cn.agentplatform.common.authority;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Actor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
class PortalClaimMapperTest {
    @Test void iterativeScopeParsingPreservesAsciiTokensAndExactSpaceRules() {
        assertThat(PortalClaimMapper.scopes("Az09:._- abc")).containsExactlyInAnyOrder("Az09:._-", "abc");
        assertThat(PortalClaimMapper.scopes("a a")).containsExactly("a");
        assertThat(PortalClaimMapper.scopes("")).isEmpty();
        assertThat(PortalClaimMapper.scopes(null)).isEmpty();
        for (String scope : List.of(" a", "a ", "a  b", "a\tb", "a\nb", "中文", "a/b", "a,b"))
            assertThatThrownBy(() -> PortalClaimMapper.scopes(scope)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        assertThatThrownBy(() -> PortalClaimMapper.scopes(List.of("a"))).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
    @Test void signedConfiguredDomainRolesArePreservedWithoutAdminSubstitution() throws Exception {
        try (var issuer = new LoopbackIssuer()) {
            var c = issuer.config(); var p = c.portal();
            var portal = new AuthorityProperties.Portal(true, p.issuer(), p.jwksUri(), p.audience(), p.clientId(),
                    Set.of("ops", "VIEWER"), p.attributeAllowlist(), p.attributeValueAllowlist(), p.scopeAllowlist());
            var config = new AuthorityProperties(c.mode(), c.serviceId(), portal, c.authority(), null, null, null, false, null);
            assertThatCode(() -> config.validate(true)).doesNotThrowAnyException();
            var claims = issuer.portal(TrustedJwtDecoderTest.NOW);
            claims.put("roles", List.of("ops", "VIEWER", "ADMIN", "SERVICE", "GATEWAY_AUTH", "unconfigured"));
            try (var decoder = new TrustedJwtDecoder(config, java.time.Clock.fixed(TrustedJwtDecoderTest.NOW, java.time.ZoneOffset.UTC), true)) {
                assertThat(new PortalClaimMapper(portal).map(decoder.decode(issuer.sign(claims))).actor().roles())
                        .containsExactlyInAnyOrder("ops", "VIEWER");
            }
            try (var decoder = new TrustedJwtDecoder(c, java.time.Clock.fixed(TrustedJwtDecoderTest.NOW, java.time.ZoneOffset.UTC), true)) {
                assertThat(new PortalClaimMapper(p).map(decoder.decode(issuer.sign(claims))).actor().roles()).isEmpty();
            }
        }
    }
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
    @Test void clientAndClaimTypesMustMatchAndMissingScopeAddsNoPermission() throws Exception {
        try(var issuer=new LoopbackIssuer()){
            var mapper=new PortalClaimMapper(issuer.config().portal());
            for(String key:List.of("iss","client_id","token_use")){
                var c=issuer.portal(TrustedJwtDecoderTest.NOW);c.put(key,"other");
                assertThatThrownBy(()->mapper.map(jwt(c))).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            }
            var c=issuer.portal(TrustedJwtDecoderTest.NOW);c.remove("scope");
            assertThat(mapper.map(jwt(c)).scopes()).isEmpty();
            c.put("roles",List.of(42));assertThatThrownBy(()->mapper.map(jwt(c))).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
    private static Jwt jwt(Map<String,Object> claims){return Jwt.withTokenValue("verified-test-input").header("alg","RS256").claims(c->c.putAll(claims))
        .issuedAt(TrustedJwtDecoderTest.NOW).expiresAt(TrustedJwtDecoderTest.NOW.plusSeconds(300)).build();}
    @Test void actorNeverGrantsServiceBusinessRolesFromTrustedRsaClaims()throws Exception{
        try(var issuer=new LoopbackAuthority();var gateway=issuer.client("gateway");var engine=issuer.client("engine");
                var decoder=new TrustedJwtDecoder(issuer.config("engine"),issuer.clock,true)){
            var call=LoopbackAuthority.call("gateway","engine");var ticket=gateway.exchange(issuer.portal(),call);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));
            try{
                assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
                try(var context=AuthorityContext.bind(engine.authorize(ticket.value(),call))){
                    assertThat(Actor.current().roles()).contains("EDITOR").doesNotContain("SERVICE","GATEWAY_AUTH");
                }
            }finally{SecurityContextHolder.clearContext();}
        }
    }
    @Test void knownOwnershipValuesRequireExactSingleSignedValuesAndLabelsCannotDisappear()throws Exception{
        try(var issuer=new LoopbackIssuer()){
            var base=issuer.config().portal();
            var p=new AuthorityProperties.Portal(true,base.issuer(),base.jwksUri(),base.audience(),base.clientId(),base.roleAllowlist(),
                Set.of("region","bizSystemCode","userId"),Map.of("region",Set.of("A"),"bizSystemCode",Set.of("biz"),"userId",Set.of("alice")),base.scopeAllowlist());
            var mapper=new PortalClaimMapper(p);var c=issuer.portal(TrustedJwtDecoderTest.NOW);
            c.put("attributes",Map.of("region",List.of("B"),"dataLabels",List.of("secret"),"bizSystemCode",List.of("biz"),"userId",List.of("alice")));
            var actor=mapper.map(jwt(c)).actor();assertThat(actor.attributes().get("region")).isEmpty();
            assertThat(actor.attributes().get("dataLabels")).containsExactly("secret");
            for(var ownership:List.of(List.of("other"),List.of("alice","other"),List.of(" "))){
                c.put("attributes",Map.of("bizSystemCode",List.of("biz"),"userId",ownership));
                assertThatThrownBy(()->mapper.map(jwt(c))).isInstanceOf(ownership.equals(List.of(" "))
                    ?org.springframework.security.oauth2.jwt.JwtException.class:PlatformException.class);
            }
        }
    }
}
