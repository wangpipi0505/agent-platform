package cn.agentplatform.common.authority;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PortalSessionRoutesTest {
    private static final String SESSION = "/v1/apps/ops/sessions/s_0123456789abcdef0123456789abcdef";

    @Test
    void leavesCollectionRoutesIntactAndUsesExactMethodsForDetailRoutes() {
        assertThat(PortalRuntimeRoutes.matches("GET", "/v1/apps/ops/sessions")).isTrue();
        assertThat(PortalRuntimeRoutes.matches("GET", SESSION)).isTrue();
        assertThat(PortalRuntimeRoutes.matches("POST", SESSION + "/turns")).isTrue();
        assertThat(PortalRuntimeRoutes.matches("GET", SESSION + "/events")).isTrue();
        assertThat(PortalRuntimeRoutes.matches("GET", SESSION + "/events/stream")).isTrue();
        assertThat(PortalRuntimeRoutes.matches("POST", SESSION)).isFalse();
        assertThat(PortalRuntimeRoutes.matches("GET", SESSION + "/turns")).isFalse();
        assertThat(PortalRuntimeRoutes.matches("POST", SESSION + "/control")).isFalse();
        assertThat(PortalRuntimeRoutes.matches("GET", SESSION + "/events/stream/extra")).isFalse();
    }

    @Test
    void changesInApplicationOrSessionChangeTheSignedResource() {
        var policy = new ServiceRoutePolicy();
        var call = PortalRuntimeRoutes.call(policy, "POST", SESSION + "/turns");
        assertThat(call.action()).isEqualTo("proxy.engine.post.ChatSessionController.turn");
        assertThat(PortalRuntimeRoutes.call(policy, "GET", SESSION + "/events/stream").resource())
                .isEqualTo(call.resource());
        assertThat(PortalRuntimeRoutes.call(policy, "GET", SESSION.replace("/ops/", "/other/")).resource())
                .isNotEqualTo(call.resource());
        assertThat(PortalRuntimeRoutes.call(policy, "GET", SESSION.replace("s_0", "s_f")).resource())
                .isNotEqualTo(call.resource());
    }
}
