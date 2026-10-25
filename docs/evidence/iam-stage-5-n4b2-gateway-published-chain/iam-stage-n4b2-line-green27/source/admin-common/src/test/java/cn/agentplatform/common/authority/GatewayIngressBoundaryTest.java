package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

class GatewayIngressBoundaryTest {
    public void handler() {}

    @Test
    void noPublicJwtOrCopiedOrdinaryAttributeCanMintRequestCapability() {
        var r = new MockHttpServletRequest("POST", "/admin/apps/Demo/runs");
        r.setAttribute(GatewayIngressBoundary.class.getName() + ".proof", new Object());
        assertThatThrownBy(() -> GatewayIngressBoundary.claim(r))
                .isInstanceOf(cn.agentplatform.common.PlatformException.class);
        r.removeAttribute(GatewayIngressBoundary.class.getName() + ".proof");
        assertThatThrownBy(() -> GatewayIngressBoundary.claim(r))
                .isInstanceOf(cn.agentplatform.common.PlatformException.class);
    }

    @Test
    void ordinaryIngressStillRejectsAnyAndWildcards() throws Exception {
        var m = getClass().getMethod("handler");
        for (String method : List.of("ANY", "*", ""))
            assertThatThrownBy(() -> new RouteCoverageVerifier.Ingress(method, "/x", m))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteCoverageVerifier.Ingress("POST", "/admin/**", m))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ordinaryReceiverStillRequiresItsServiceGuardOnDisabledCatalogRows() {
        var mapping = new RequestMappingHandlerMapping();
        try {
            mapping.registerMapping(
                    RequestMappingInfo.paths("/unregistered").methods(RequestMethod.POST).build(),
                    this,
                    getClass().getMethod("handler"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThatThrownBy(
                        () ->
                                new RouteCoverageVerifier(
                                                "engine",
                                                new ServiceRoutePolicy(),
                                                List.of(mapping))
                                        .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTE_COVERAGE_MISSING");
    }
}
