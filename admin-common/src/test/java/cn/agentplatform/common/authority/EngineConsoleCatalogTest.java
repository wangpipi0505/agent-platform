package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 防止 Y-1 家族回归：引擎控制台的运行列表页与内部运维总览必须登记在路由目录里。
 *
 * <p>两者都是"控制器里有、目录里没有"造成的漂移：任何启动 {@link RouteCoverageVerifier} 的上下文
 * （网关链路夹具、审计脚本）都会以 {@code ROUTE_COVERAGE_MISSING} 失败，而失败信息指向的是夹具，
 * 不是缺失的行，排查成本很高。这里把两条行的授权形态钉死：
 *
 * <ul>
 *   <li>{@code GET /admin/runs/page} 由网关代用户调用，必须是 delegated + caller=gateway；
 *   <li>{@code GET /internal/operations/overview} 目前没有服务调用方（运维总览走 monitor 的
 *       {@code /admin/operations/overview}），必须是 disabled + caller=null——登记为可见但零授权，
 *       将来接入方确定后再改为 delegated，并同步改这条测试。
 * </ul>
 */
class EngineConsoleCatalogTest {

    @Test
    void runPageIsDelegatedToGateway() {
        List<ServiceRoutePolicy.Route> rows = rows("/admin/runs/page");

        assertThat(rows).hasSize(1);
        ServiceRoutePolicy.Route row = rows.get(0);
        assertThat(row.method()).isEqualTo("GET");
        assertThat(row.target()).isEqualTo("engine");
        assertThat(row.caller()).isEqualTo("gateway");
        assertThat(row.mode()).isEqualTo("delegated");
        assertThat(row.integrationAllowed()).isFalse();
    }

    @Test
    void operationsOverviewIsRegisteredAsDisabledWithoutCaller() {
        List<ServiceRoutePolicy.Route> rows = rows("/internal/operations/overview");

        assertThat(rows).hasSize(1);
        ServiceRoutePolicy.Route row = rows.get(0);
        assertThat(row.method()).isEqualTo("GET");
        assertThat(row.target()).isEqualTo("engine");
        assertThat(row.mode()).isEqualTo("disabled");
        assertThat(row.caller()).isNull();
        assertThat(row.integrationAllowed()).isFalse();
        assertThat(row.reason()).isNotBlank();
    }

    private static List<ServiceRoutePolicy.Route> rows(String template) {
        return new ServiceRoutePolicy().routes().stream()
                .filter(row -> template.equals(row.template()))
                .toList();
    }
}
