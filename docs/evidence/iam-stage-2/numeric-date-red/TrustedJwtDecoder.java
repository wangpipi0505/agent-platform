package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/** RS256 portal and authority domains only. Integration HMAC is never an alternative key source. */
public final class TrustedJwtDecoder implements JwtDecoder,AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final AuthorityProperties config;
    private final Clock clock;
    private final BoundedIamHttp http;
    private final NimbusJwtDecoder authority,portal;
    private final PortalClaimMapper mapper;
    private final List<ServiceRoutePolicy.Route> routes=new ServiceRoutePolicy().routes();
    public TrustedJwtDecoder(AuthorityProperties config,Clock clock,boolean testProfile){
        config.validate(testProfile);this.config=config;this.clock=Objects.requireNonNull(clock);
        http=new BoundedIamHttp(config,testProfile);
        authority=decoder(config.authority().issuer(),config.authority().jwksUri(),false);
        boolean enabled=config.portal()!=null && config.portal().enabled();
        portal=enabled?decoder(config.portal().issuer(),config.portal().jwksUri(),true):null;
        mapper=enabled?new PortalClaimMapper(config.portal()):null;
    }
    @Override public Jwt decode(String token){
        try{
            if(token==null || token.length()>16384 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw invalid();
            var parts=token.split("\\.");var header=object(Base64.getUrlDecoder().decode(parts[0]));
            if(!"RS256".equals(header.path("alg").textValue()) || !"at+jwt".equals(header.path("typ").textValue())
                    || !header.path("kid").isTextual() || header.path("kid").textValue().isBlank()
                    || header.has("jku") || header.has("x5u") || header.has("jwk") || header.has("x5c") || header.has("crit"))throw invalid();
            var raw=object(Base64.getUrlDecoder().decode(parts[1]));
            // Original JSON types are checked before Nimbus/Spring coercion and synthesized iat.
            for(String k:List.of("iss","sub","jti","token_use","client_id"))requiredText(raw,k);
            for(String k:List.of("exp","iat"))if(!raw.path(k).isIntegralNumber() || !raw.path(k).canConvertToLong())throw invalid();
            if(raw.has("nbf") && (!raw.path("nbf").isIntegralNumber() || !raw.path("nbf").canConvertToLong()))throw invalid();
            var aud=raw.get("aud");
            if(!(aud!=null && (aud.isTextual() || (aud.isArray() && aud.size()==1 && aud.get(0).isTextual()))))throw invalid();
            String issuer=raw.path("iss").textValue();
            // Unverified issuer/use select fixed candidates only; no URL comes from a token.
            boolean user="user".equals(raw.path("token_use").textValue());
            NimbusJwtDecoder selected=user && portal!=null && config.portal().issuer().equals(issuer)?portal:
                    !user && config.authority().issuer().equals(issuer)?authority:null;
            if(selected==null)throw invalid();
            var jwt=selected.decode(token);
            if(user){
                var mapped=mapper.map(jwt);
                return Jwt.withTokenValue(token).headers(h->h.putAll(jwt.getHeaders())).claims(c->{
                    c.putAll(jwt.getClaims());c.put("roles",mapped.actor().roles());c.put("attributes",mapped.actor().attributes());
                    c.put("scope",String.join(" ",new TreeSet<>(mapped.scopes())));
                }).build();
            }
            return jwt;
        }catch(JwtException ex){
            // Spring wraps custom JWKSource exceptions. Keep upstream failures distinct from bad tokens.
            for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof PlatformException p && p.status()==503)throw p;
            throw new BadJwtException("JWT_INVALID");
        }catch(PlatformException ex){throw ex;}
        catch(Exception ex){throw invalid();}
    }
    private NimbusJwtDecoder decoder(String issuer,String jwks,boolean user){
        JWKSource<SecurityContext> source=(selector,context)->selector.select(keys(URI.create(jwks)));
        var processor=new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,source));
        processor.setJWTClaimsSetVerifier((claims,context)->{});
        var decoder=new NimbusJwtDecoder(processor);
        var timestamps=new JwtTimestampValidator(Duration.ZERO);timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,new JwtIssuerValidator(issuer),jwt->{
            try{validateClaims(jwt,user);return OAuth2TokenValidatorResult.success();}
            catch(JwtException ex){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","JWT_PROFILE_INVALID",null));}
        }));return decoder;
    }
    private void validateClaims(Jwt jwt,boolean user){
        var c=jwt.getClaims();var now=clock.instant();var exp=jwt.getExpiresAt();var iat=jwt.getIssuedAt();
        if(exp==null || iat==null || !exp.isAfter(now) || !exp.isAfter(iat) || iat.isAfter(now))throw invalid();
        PortalClaimMapper.scopes(c.get("scope"));PortalClaimMapper.strings(c.get("roles"));PortalClaimMapper.attributes(c.get("attributes"));
        if(user){mapper.map(jwt);return;}
        var a=config.authority();
        if(!"service".equals(c.get("token_use")) || !jwt.getAudience().equals(List.of(a.audience()))
                || exp.isAfter(iat.plusSeconds(a.maxTicketSeconds())) || jwt.getNotBefore()==null)throw invalid();
        String caller=PortalClaimMapper.text(c.get("caller"));
        String client=PortalClaimMapper.text(c.get("client_id"));
        if(!AuthorityProperties.SERVICES.contains(caller) || !caller.equals(client)
                || !config.serviceId().equals(c.get("target")))throw invalid();
        String action=PortalClaimMapper.text(c.get("action"));
        if(!PortalClaimMapper.scopes(c.get("scope")).equals(Set.of(action)))throw invalid();
        if(!(c.get("resource") instanceof Map<?,?> resource) || !resource.keySet().equals(Set.of("kind","id","version_digest")))throw invalid();
        for(var v:resource.values())PortalClaimMapper.text(v);
        String mode=PortalClaimMapper.text(c.get("authority_mode"));
        if("workload".equals(mode)){
            if(!caller.equals(jwt.getSubject()) || List.of("tenant_id","grant_ref","roles","attributes","authorization_version","act","user_id").stream().anyMatch(c::containsKey))throw invalid();
        }else if("delegated".equals(mode)){
            for(String k:List.of("tenant_id","grant_ref","authorization_version"))PortalClaimMapper.text(c.get(k));
        }else throw invalid();
        if(routes.stream().noneMatch(r->mode.equals(r.mode()) && caller.equals(r.caller())
                && config.serviceId().equals(r.target()) && action.equals(r.action())))throw invalid();
    }
    private JWKSet keys(URI uri){
        byte[] bytes=http.get(uri);
        try{
            var root=object(bytes);var array=root.get("keys");
            if(array==null || !array.isArray() || array.isEmpty() || array.size()>8)throw invalid();
            var ids=new HashSet<String>();var keys=new ArrayList<JWK>();
            for(var key:array){
                if(!key.isObject() || !"RSA".equals(key.path("kty").textValue()) || !"RS256".equals(key.path("alg").textValue())
                        || !"sig".equals(key.path("use").textValue()) || !key.path("kid").isTextual()
                        || key.path("kid").textValue().isBlank() || !ids.add(key.path("kid").textValue()))throw invalid();
                for(String forbidden:List.of("d","p","q","dp","dq","qi","oth","k","x5u","jku","x5c"))if(key.has(forbidden))throw invalid();
                if(key.has("key_ops") && (!key.get("key_ops").isArray() || key.get("key_ops").size()!=1 || !"verify".equals(key.get("key_ops").get(0).textValue())))throw invalid();
                var rsa=RSAKey.parse(key.toString());if(rsa.isPrivate() || rsa.size()<2048)throw invalid();keys.add(rsa);
            }
            return new JWKSet(keys);
        }catch(Exception ex){throw BoundedIamHttp.unavailable();}
    }
    private static JsonNode object(byte[] bytes)throws Exception{
        try(var parser=JSON.createParser(bytes)){
            JsonNode node=JSON.readTree(parser);if(node==null || !node.isObject() || parser.nextToken()!=null)throw invalid();return node;
        }
    }
    private static void requiredText(JsonNode c,String k){if(!c.path(k).isTextual() || c.path(k).textValue().isBlank())throw invalid();}
    private static BadJwtException invalid(){return PortalClaimMapper.invalid();}
    @Override public void close(){http.close();}
}
