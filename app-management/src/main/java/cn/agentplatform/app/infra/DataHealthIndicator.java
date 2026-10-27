package cn.agentplatform.app.infra;

import cn.agentplatform.app.application.DataHealthService;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * PLAT-20 / T20.1：/actuator/health 的 {@code data} 贡献者。聚合状态参与整体健康判定
 * （数据面不可查询时整体 DOWN/503，与既有 db 指示器一致）；明细（库名、Flyway 版本、行数量级）
 * 只挂在贡献者 details 与 {@code /health/data}（ADMIN）上，公开端点因 show-details=never 不外泄。
 * 只带异常类型不带消息，避免把连接串等环境信息暴露到健康面。
 */
@Component
public class DataHealthIndicator implements HealthIndicator {
    private final DataHealthService data;

    public DataHealthIndicator(DataHealthService data) {
        this.data = data;
    }

    @Override
    public Health health() {
        try {
            return Health.up().withDetails(data.snapshot()).build();
        } catch (RuntimeException failure) {
            return Health.down(failure).build();
        }
    }
}
