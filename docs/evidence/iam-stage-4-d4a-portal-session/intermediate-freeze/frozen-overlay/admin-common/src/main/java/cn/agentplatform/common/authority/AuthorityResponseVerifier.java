package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.time.*;
import java.util.*;

/** No remote-key hints, coercion, or success cache; completion permits pin only their opening key. */
final class AuthorityResponseVerifier {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    record Verified(JsonNode claims,RSAKey key) {}
    private final AuthorityProperties config;
    private final Clock clock;
    private final BoundedIamHttp http;
    AuthorityResponseVerifier(AuthorityProperties config,Clock clock,BoundedIamHttp http){this.config=config;this.clock=clock;this.http=http;}
    Verified verify(String token,String type,String use,String audience,long maxSeconds){
        try{
            if(token==null||token.length()>16384)throw BoundedIamHttp.unavailable();
            var keys=TrustedJwtDecoder.parseKeys(http.get(URI.create(config.authority().jwksUri())));
            var parsed=JWSObject.parse(token);
            var key=keys.getKeyByKeyId(parsed.getHeader().getKeyID());
            if(!(key instanceof RSAKey rsa))throw AuthorityProtocol.denied();
            return verifyPinned(token,type,use,audience,maxSeconds,rsa,config.authority().issuer(),clock);
        }catch(PlatformException e){throw e;}catch(Exception e){throw BoundedIamHttp.unavailable();}
    }
    /** Session only: retain a cryptographically valid elapsed envelope until its request bindings
     * have been checked by the owner. This never authorizes output and never relaxes future dates. */
    Verified verifySession(String token,String type,String use,String audience,long maxSeconds){
        try{
            if(token==null||token.length()>16384)throw BoundedIamHttp.unavailable();
            var keys=TrustedJwtDecoder.parseKeys(http.get(URI.create(config.authority().jwksUri())));
            var parsed=JWSObject.parse(token);var key=keys.getKeyByKeyId(parsed.getHeader().getKeyID());
            if(!(key instanceof RSAKey rsa))throw BoundedIamHttp.unavailable();
            return verifyPinned(token,type,use,audience,maxSeconds,rsa,config.authority().issuer(),clock,true);
        }catch(Exception e){throw BoundedIamHttp.unavailable();}
    }
    static Verified verifyPinned(String token,String type,String use,String audience,long maxSeconds,RSAKey key,String issuer,Clock clock){
        return verifyPinned(token,type,use,audience,maxSeconds,key,issuer,clock,false);
    }
    private static Verified verifyPinned(String token,String type,String use,String audience,long maxSeconds,RSAKey key,String issuer,Clock clock,boolean elapsedSession){
        try{
            if(token==null||token.length()>16384||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw AuthorityProtocol.denied();
            var p=token.split("\\.");var header=object(Base64.getUrlDecoder().decode(p[0]));
            AuthorityProtocol.same("RS256",AuthorityProtocol.text(header,"alg"));AuthorityProtocol.same(type,AuthorityProtocol.text(header,"typ"));
            AuthorityProtocol.same(key.getKeyID(),AuthorityProtocol.text(header,"kid"));
            for(String k:List.of("jku","x5u","jwk","x5c","crit"))if(header.has(k))throw AuthorityProtocol.denied();
            var signed=JWSObject.parse(token);
            // Nimbus JWSObject.verify / RSASSAVerifier establish cryptographic integrity only.
            // As in Spring NimbusJwtDecoder, separate claim/use/time validation is still mandatory.
            if(!signed.verify(new RSASSAVerifier(key.toRSAPublicKey())))throw AuthorityProtocol.denied();
            var c=object(Base64.getUrlDecoder().decode(p[1]));
            AuthorityProtocol.same(issuer,AuthorityProtocol.text(c,"iss"));AuthorityProtocol.same(use,AuthorityProtocol.text(c,"token_use"));
            var aud=c.get("aud");if(aud==null||!(aud.isTextual()&&aud.textValue().equals(audience)||aud.isArray()&&aud.size()==1&&aud.get(0).isTextual()&&aud.get(0).textValue().equals(audience)))throw AuthorityProtocol.denied();
            AuthorityProtocol.text(c,"jti");var iat=AuthorityProtocol.time(c,"iat");var exp=AuthorityProtocol.time(c,"exp");var nbf=AuthorityProtocol.time(c,"nbf");
            if(iat.isAfter(clock.instant())||nbf.isAfter(clock.instant())||nbf.isBefore(iat)||(!elapsedSession&&!exp.isAfter(clock.instant()))||!exp.isAfter(iat)||exp.isAfter(iat.plusSeconds(maxSeconds)))throw AuthorityProtocol.expired();
            return new Verified(c.deepCopy(),key);
        }catch(Exception e){throw BoundedIamHttp.unavailable();}
    }
    static JsonNode object(byte[] bytes){
        try(var p=JSON.createParser(bytes)){JsonNode n=JSON.readTree(p);if(n==null||!n.isObject()||p.nextToken()!=null)throw AuthorityProtocol.denied();return n;}
        catch(Exception e){throw BoundedIamHttp.unavailable();}
    }
}
