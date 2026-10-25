package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortalSessionAuthorizationTest {
    // Reflect only to keep the feature's missing API a recorded behavior failure, never compile-red.
    static Object session(CurrentAuthorityClient client,String token) throws Exception {
        try{return CurrentAuthorityClient.class.getMethod("portalSession",String.class).invoke(client,token);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    static Object invoke(Object proof,String method)throws Exception {
        try{return proof.getClass().getMethod(method).invoke(proof);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    @Test void signedPortalSessionUsesExactlyExchangeAndAuthorizeAndProjectsFourFields()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var proof=session(gateway,f.portal());
            var view=Json.tree(invoke(proof,"view"));
            assertThat(view.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("tenantId","principalId","roles","expiresAt");
            assertThat(view.path("tenantId").asText()).isEqualTo("tenant-a");
            assertThat(view.path("principalId").asText()).isEqualTo("alice");
            assertThat(view.path("roles").toString()).isEqualTo("[\"EDITOR\"]");
            assertThat(view.path("expiresAt").asText()).isEqualTo(f.clock.instant().plusSeconds(40).toString());
            invoke(proof,"requireVerified");
            assertThat(f.requests).extracting(r->r.path("operation").asText()).containsExactly("exchange","authorize");
            assertThat(f.requests).allSatisfy(r->{assertThat(r.path("target").asText()).isEqualTo("gateway");assertThat(r.path("action").asText()).isEqualTo("portal.session.read");});
            assertThat(f.businessSends).hasValue(0);assertThat(f.policyCalls).hasValue(0);
        }
    }
    @Test void unsupportedDefaultCannotMintSessionFromPublicAuthority()throws Exception {
        var fallback=(CurrentAuthorityClient)Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),new Class<?>[]{CurrentAuthorityClient.class},(proxy,method,args)->{
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args);
            throw new AssertionError("A public VA/currentOnly cannot mint a session proof");
        });
        assertThatThrownBy(()->session(fallback,"unused")).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
    }
}
