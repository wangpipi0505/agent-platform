package cn.agentplatform.common.authority;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁住 X-10 的唤醒端点授权面：只授予 skill 服务，且不进入对外集成面。
 *
 * <p>目录里缺行时，任何启动 {@link RouteCoverageVerifier} 的上下文（测试夹具、审计脚本）都会以
 * {@code ROUTE_COVERAGE_MISSING} 失败；把 caller 放宽到其它服务则会打开一条"任意服务票据都能重排
 * 引擎派发时间"的入口。因此这里锁住"恰好一行"、"delegated 且 caller 恰为 skill"、"仍不允许集成"
 * 三点——将来调整调用方时，必须改这条测试并留下评审记录，而不是只改 JSON。
 */
class RunWakeCatalogTest {

    @Test
    void wakeEndpointIsDelegatedToSkillOnly() {
        List<ServiceRoutePolicy.Route> rows = new ServiceRoutePolicy().routes().stream()
                .filter(row -> "/internal/runs/{id}/wake".equals(row.template()))
                .toList();

        assertThat(rows).hasSize(1);
        ServiceRoutePolicy.Route row = rows.get(0);
        assertThat(row.method()).isEqualTo("POST");
        assertThat(row.target()).isEqualTo("engine");
        assertThat(row.action()).isEqualTo("run.wake");
        assertThat(row.mode()).isEqualTo("delegated");
        assertThat(row.caller()).isEqualTo("skill");
        assertThat(row.integrationAllowed()).isFalse();
        assertThat(row.reason()).isNotBlank();
    }
}
