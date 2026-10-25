package cn.agentplatform.model.application;

import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

/** 仅探测DEGRADED，人工DISABLED不会被健康定时器自动启用。 */
@Component
@EnableScheduling
final class ModelHealthProbe {
    private final ModelCalls calls;

    ModelHealthProbe(ModelCalls calls) {
        this.calls = calls;
    }

    @Scheduled(
            fixedDelayString = "${agent.model.health-delay-ms:30000}",
            initialDelayString = "${agent.model.health-delay-ms:30000}")
    void probe() {
        calls.reconcilePendingUsage();
        calls.probeDegraded();
    }
}
