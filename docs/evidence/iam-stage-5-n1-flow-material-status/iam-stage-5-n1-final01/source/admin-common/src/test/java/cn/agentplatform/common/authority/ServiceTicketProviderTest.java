package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ServiceTicketProviderTest {
    @Test void actorCannotMintAnIamServiceTicketEvenWhenLegacyKeyExists() {
        var names=List.of("agent.jwt.secret","agent.security.mode","agent.dev-auth","spring.profiles.active");
        var before=new HashMap<String,String>();names.forEach(n->before.put(n,System.getProperty(n)));
        try {
            System.setProperty("agent.jwt.secret","task3-independent-temporary-test-secret-0123456789");
            var actor=new Actor("t","alice",Set.of("ADMIN"),Map.of());
            System.clearProperty("agent.security.mode");
            assertThatThrownBy(()->ServiceTokens.issue(actor)).isInstanceOf(PlatformException.class);
            for(String mode:List.of("iam","test","dev-hmac")){
                System.setProperty("agent.security.mode",mode);System.clearProperty("agent.dev-auth");System.clearProperty("spring.profiles.active");
                assertThatThrownBy(()->ServiceTokens.issue(actor)).as(mode).isInstanceOf(PlatformException.class);
            }
        } finally {before.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
    }
    @Test void resolverRequiresOwnDeploymentCredentialAndTransportCannotSendItToJwks()throws Exception{
        try(var f=new LoopbackAuthority()){
            assertThatThrownBy(()->new WorkloadCredentialResolver(f.config("engine"),name->null,true)).isInstanceOf(IllegalStateException.class);
            var names=new ArrayList<String>();var resolver=new WorkloadCredentialResolver(f.config("engine"),name->{names.add(name);return "independent";},true);
            assertThat(names).containsExactly("AGENT_SECRET_IAM_ENGINE_CLIENT");
            try(var http=new BoundedIamHttp(f.config("engine"),true)){
                assertThatThrownBy(()->http.postJsonAuthenticated(java.net.URI.create(f.issuer()+"/jwks"),Map.of(),resolver.resolve())).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(f.requests).isEmpty();
        }
    }
    @Test void sharedStrictJwksParsingRejectsPrivateAndDuplicateKeys()throws Exception{
        try(var f=new LoopbackAuthority()){
            var key=f.key.toPublicJWK().toJSONObject();byte[] valid=Json.write(Map.of("keys",List.of(key))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(TrustedJwtDecoder.parseKeys(valid).getKeys()).hasSize(1);
            byte[] duplicate=Json.write(Map.of("keys",List.of(key,key))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(()->TrustedJwtDecoder.parseKeys(duplicate)).isInstanceOf(PlatformException.class);
            byte[] secret=Json.write(Map.of("keys",List.of(f.key.toJSONObject()))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(()->TrustedJwtDecoder.parseKeys(secret)).isInstanceOf(PlatformException.class);
        }
    }
}
