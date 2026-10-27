package cn.agentplatform.common.authority;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止 Y-1 回归：R-A6 的唤醒端点必须在路由目录里"可见但不可被调用"。
 *
 * <p>目录里缺行时，任何启动 {@link RouteCoverageVerifier} 的上下文（测试夹具、审计脚本）都会以
 * {@code ROUTE_COVERAGE_MISSING} 失败；把行改成 delegated 又会给一个尚未定调用方的入口授予服务能力。
 * 因此这里同时锁住"恰好一行"和"必须是 disabled 且无 caller"两点——将来定稿调用方时，必须改这条测试
 * 并留下评审记录，而不是只改 JSON。
 */
class RunWakeCatalogTest {

    @Test
    void wakeEndpointIsRegisteredAsDisabledWithoutCaller() {
        List<ServiceRoutePolicy.Route> rows = new ServiceRoutePolicy().routes().stream()
                .filter(row -> "/internal/runs/{id}/wake".equals(row.template()))
                .toList();

        assertThat(rows).hasSize(1);
        ServiceRoutePolicy.Route row = rows.get(0);
        assertThat(row.method()).isEqualTo("POST");
        assertThat(row.target()).isEqualTo("engine");
        assertThat(row.action()).isEqualTo("run.wake");
        assertThat(row.mode()).isEqualTo("disabled");
        assertThat(row.caller()).isNull();
        assertThat(row.integrationAllowed()).isFalse();
        assertThat(row.reason()).isNotBlank();
    }
}
