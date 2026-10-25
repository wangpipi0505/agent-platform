package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RetainedAuthorizationTest {
    static Map<String,Object> claims(String token){
        return Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8)),new TypeReference<Map<String,Object>>(){});
    }
    @Test void receivedSignedTicketInitialCannotDisagreeWithItsOperationHash()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var original=gateway.exchange(f.portal(),call).value();
            var c=claims(original);
            c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of("EDITOR"),
                "attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("confidential","restricted"))));
            assertThatThrownBy(()->engine.authorize(f.sign(c,f.key,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void singleSidedOriginCannotBeSilentlyIgnored()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var c=claims(gateway.exchange(f.portal(),call).value());
            c.put("origin_authentication_id","oa1:ticket-only");
            f.changeDecision=m->m.remove("origin_authentication_id");
            assertThatThrownBy(()->engine.authorize(f.sign(c,f.key,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void samePortalAuthenticationCannotChangeOriginalRolesOnAnotherGrant()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var call=LoopbackAuthority.call("gateway","engine");var source=f.portal();gateway.exchange(source,call);
            var c=claims(source);c.put("roles",Set.of("EDITOR"));
            assertThatThrownBy(()->gateway.exchange(f.sign(c,f.portalKey,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void legacyIntegrationSourceMustHaveFixedVerifiedIssuerAndBoundedJti()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var call=LoopbackAuthority.call("gateway","engine");
            var c=f.integrationClaims();c.put("iss","https://untrusted.invalid");
            assertThatThrownBy(()->gateway.exchange(f.integration(c),call)).isInstanceOf(PlatformException.class);
        }
    }
}
