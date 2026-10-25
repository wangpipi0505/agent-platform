package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class PolicyRequestVerifierTest {
    static final byte[] BODY="{\"nonce\":\"finite-policy-nonce\"}".getBytes(StandardCharsets.UTF_8);
    static MockHttpServletRequest request(String token){var r=new MockHttpServletRequest("POST",PolicyRequestVerifier.PATH);r.setServletPath(PolicyRequestVerifier.PATH);r.setContent(BODY);r.addHeader("Authorization","Bearer "+token);return r;}
    static Map<String,Object> claims(LoopbackAuthority f){var c=f.base("policy-query","app-policy-resolver",30);c.put("iss",f.policyIssuer()+"/policy-issuer");c.put("sub","iam-policy-resolver");c.put("caller","iam-policy-resolver");c.put("target","app-policy-resolver");c.put("action","integration.policy.resolve");c.put("nonce","finite-policy-nonce");c.put("bodySha256",Digest.sha256(new String(BODY,StandardCharsets.UTF_8)));return c;}
    static PolicyRequestVerifier verifier(LoopbackAuthority f){return new PolicyRequestVerifier(f.policyIssuer()+"/policy-issuer",List.of(f.policyKey.toPublicJWK()),f.clock);}
    @Test void signedPolicyProofIsBoundToExactRequestBodyTimeAndVerifier()throws Exception{
        try(var f=new LoopbackAuthority()){
            var v=verifier(f);var r=request(f.sign(claims(f),f.policyKey,"agent-policy-request+jwt"));
            var proof=v.verify(r,BODY);assertThat(proof.body(r)).containsExactly(BODY);assertThatCode(()->v.require(r,proof)).doesNotThrowAnyException();
            assertThatThrownBy(()->v.require(request(r.getHeader("Authorization").substring(7)),proof)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->verifier(f).require(r,proof)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->proof.body(request("copied"))).isInstanceOf(PlatformException.class);
            f.clock.advance(31);assertThatThrownBy(()->v.require(r,proof)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void wrongDomainClaimsMethodPathAndBodyFailLocallyWithout503()throws Exception{
        try(var f=new LoopbackAuthority()){
            var v=verifier(f);
            for(String field:List.of("iss","sub","caller","target","action","token_use","aud","nonce","bodySha256")){
                var c=claims(f);c.put(field,"wrong");reject(v,request(f.sign(c,f.policyKey,"agent-policy-request+jwt")),BODY);
            }
            for(String field:List.of("iat","nbf","exp")){var c=claims(f);c.put(field,field.equals("exp")?0:f.clock.instant().plusSeconds(1).getEpochSecond());reject(v,request(f.sign(c,f.policyKey,"agent-policy-request+jwt")),BODY);}
            var longTtl=claims(f);longTtl.put("exp",f.clock.instant().plusSeconds(31).getEpochSecond());reject(v,request(f.sign(longTtl,f.policyKey,"agent-policy-request+jwt")),BODY);
            var raw=f.sign(claims(f),f.policyKey,"agent-policy-request+jwt");var method=request(raw);method.setMethod("GET");reject(v,method,BODY);var path=request(raw);path.setServletPath("/internal/other");reject(v,path,BODY);
            reject(v,request(f.sign(claims(f),new RSAKey.Builder(f.key).keyID(f.policyKey.getKeyID()).build(),"agent-policy-request+jwt")),BODY);
            reject(v,request(f.sign(claims(f),f.key,"agent-policy-request+jwt")),BODY);reject(v,request(f.sign(claims(f),f.policyKey,"at+jwt")),BODY);
            reject(v,request(raw),"{\"nonce\":\"changed\"}".getBytes(StandardCharsets.UTF_8));
            reject(v,request("eyJhbGciOiJub25lIn0.e30."),BODY);
            var duplicate="{\"nonce\":\"finite-policy-nonce\",\"nonce\":\"finite-policy-nonce\"}".getBytes(StandardCharsets.UTF_8);var c=claims(f);c.put("bodySha256",Digest.sha256(new String(duplicate,StandardCharsets.UTF_8)));reject(v,request(f.sign(c,f.policyKey,"agent-policy-request+jwt")),duplicate);
        }
    }
    static void reject(PolicyRequestVerifier v,MockHttpServletRequest r,byte[] b){assertThatThrownBy(()->v.verify(r,b)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isIn(401,403));}
    @Test void missingPrivateDuplicateOrTooManyPinnedKeysRejectAtConstruction()throws Exception{
        try(var f=new LoopbackAuthority()){
            for(var keys:List.of(List.<RSAKey>of(),List.of(f.policyKey),List.of(f.policyKey.toPublicJWK(),f.policyKey.toPublicJWK()),List.of(f.policyKey.toPublicJWK(),f.key.toPublicJWK(),f.portalKey.toPublicJWK())))
                assertThatThrownBy(()->new PolicyRequestVerifier("issuer",keys,f.clock)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static class PolicyHandler {public void resolve(){} public void other(){}}
    @Test void globalGuardRequiresRegisteredMethodAndUnforgeableProof()throws Exception{
        try(var f=new LoopbackAuthority();var client=f.client("app")){
            var verifier=verifier(f);var method=PolicyHandler.class.getMethod("resolve");
            var guard=new ServiceAuthorization("app",new ServiceRoutePolicy(),client,null,method,verifier);
            var handler=new org.springframework.web.method.HandlerMethod(new PolicyHandler(),method);
            var r=request(f.sign(claims(f),f.policyKey,"agent-policy-request+jwt"));r.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,PolicyRequestVerifier.PATH);
            assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
            r.setAttribute(PolicyRequestVerifier.ATTRIBUTE,true);assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
            var proof=verifier.verify(r,BODY);r.setAttribute(PolicyRequestVerifier.ATTRIBUTE,proof);assertThat(guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isTrue();
            var bytes=proof.body(r);bytes[0]=0;assertThat(proof.body(r)).containsExactly(BODY);
            var other=new org.springframework.web.method.HandlerMethod(new PolicyHandler(),PolicyHandler.class.getMethod("other"));assertThatThrownBy(()->guard.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),other)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->new ServiceAuthorization("app",new ServiceRoutePolicy(),client,null,method,null)).isInstanceOf(IllegalArgumentException.class);
            var withoutRegistration=new ServiceAuthorization("app",new ServiceRoutePolicy(),client);assertThatThrownBy(()->withoutRegistration.preHandle(r,new org.springframework.mock.web.MockHttpServletResponse(),handler)).isInstanceOf(PlatformException.class);
        }
    }
}
