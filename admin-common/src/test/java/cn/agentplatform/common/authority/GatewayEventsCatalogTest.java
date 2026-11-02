package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EX-44 切片 1a：SSE 出流第二步 gateway→gateway 的观测订阅票必须登记在目录里。
 *
 * <p>为什么需要这一行：{@code HttpCurrentAuthorityClient.openGatewayPortalEvents} 在拿到 S0
 * 元数据票之后，用 {@code ServiceCall("gateway", "gateway.run.events.observe", run-snapshot)}
 * 发起第二次 exchange；目录里缺行时 authority-service 的 {@code RouteDirectory.permits} 直接
 * 403 ROUTE_NOT_REGISTERED，随后的 authorize 也一并被拒，SSE 在出流前就断。
 *
 * <p>method/template 取真实调用形状：网关的 SSE 入口是 {@code GET /admin/runs/{id}/events}
 * （{@code PortalRunEventsSse} 的 Servlet 路由，与 engine 行同模板），action 取客户端 S1 调用的
 * {@code RunObservationSelectors.GATEWAY_EVENTS_ACTION}。
 */
class GatewayEventsCatalogTest {

    private static final String ACTION = "gateway.run.events.observe";
    private static final String TEMPLATE = "/admin/runs/{id}/events";

    @Test
    void registersTheGatewayToGatewayObservationTicket() {
        List<ServiceRoutePolicy.Route> rows = rows();
        assertThat(rows).hasSize(1);
        ServiceRoutePolicy.Route row = rows.getFirst();
        assertThat(row.method()).isEqualTo("GET");
        assertThat(row.template()).isEqualTo(TEMPLATE);
        assertThat(row.caller()).isEqualTo("gateway");
        assertThat(row.target()).isEqualTo("gateway");
        assertThat(row.action()).isEqualTo(ACTION);
        assertThat(row.mode()).isEqualTo("delegated");
        assertThat(row.integrationAllowed()).isFalse();
        assertThat(row.source()).contains("HttpCurrentAuthorityClient.java");
        assertThat(row.callerSource()).contains("PortalRunEventsHttp.java");
        assertThat(row.reason()).contains("SSE").contains("ROUTE_NOT_REGISTERED");
    }

    @Test
    void resolvesToTheExactClientCallWithoutDisplacingTheEngineRow() {
        ServiceCall call = new ServiceRoutePolicy().require("gateway", "gateway", "GET", TEMPLATE);
        assertThat(call).isEqualTo(new ServiceCall("gateway", ACTION, null));

        assertThat(new ServiceRoutePolicy().routes().stream()
                .filter(row -> TEMPLATE.equals(row.template()) && "GET".equals(row.method()))
                .map(ServiceRoutePolicy.Route::target))
                .containsExactlyInAnyOrder("gateway", "engine");
    }

    private static List<ServiceRoutePolicy.Route> rows() {
        return new ServiceRoutePolicy().routes().stream()
                .filter(row -> ACTION.equals(row.action()))
                .toList();
    }
}
