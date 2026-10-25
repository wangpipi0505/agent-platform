package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import jakarta.servlet.http.*;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class GatewayEventsIngressBoundaryTest {
    @Test void ordinaryAttributesAndWrappersCannotMintEventsCapture(){
        var request=new MockHttpServletRequest("GET","/admin/runs/r_"+"a".repeat(32)+"/events");
        var response=new MockHttpServletResponse();
        for(Object fake:new Object[]{new Object(),"true",Boolean.TRUE}){
            request.setAttribute(GatewayIngressBoundary.class.getName()+".proof",fake);
            assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(request,response)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(new HttpServletRequestWrapper(request),new HttpServletResponseWrapper(response))).isInstanceOf(PlatformException.class);
        }
        request.removeAttribute(GatewayIngressBoundary.class.getName()+".proof");
        assertThatThrownBy(()->GatewayIngressBoundary.claimEvents(request,response)).isInstanceOf(PlatformException.class);
    }
    @Test void eventsCapabilityHasOnlyPrivateConstruction(){
        assertThat(GatewayIngressBoundary.PortalEventsRequest.class.getDeclaredConstructors()).allSatisfy(c->assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
        assertThat(GatewayIngressBoundary.PortalEventsRequest.class.getMethods()).noneMatch(m->m.getReturnType()==cn.agentplatform.common.Actor.class);
    }
}
