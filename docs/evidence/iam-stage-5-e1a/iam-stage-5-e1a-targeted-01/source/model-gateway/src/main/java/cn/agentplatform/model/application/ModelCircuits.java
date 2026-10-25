package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.model.infra.ModelStore;

import io.github.resilience4j.circuitbreaker.*;

import java.time.Duration;

/** 供应商级Resilience4j熔断；PG管理状态可见，配额仍由Redis跨副本执行。 */
final class ModelCircuits {
    private final ModelStore store;
    private final CircuitBreakerRegistry registry =
            CircuitBreakerRegistry.of(
                    CircuitBreakerConfig.custom()
                            .slidingWindowSize(5)
                            .minimumNumberOfCalls(5)
                            .failureRateThreshold(60)
                            .waitDurationInOpenState(Duration.ofSeconds(30))
                            .automaticTransitionFromOpenToHalfOpenEnabled(true)
                            .permittedNumberOfCallsInHalfOpenState(1)
                            .recordException(
                                    e -> e instanceof PlatformException p && p.status() >= 500)
                            .build());

    ModelCircuits(ModelStore store) {
        this.store = store;
    }

    CircuitBreaker circuit(long provider, Actor actor) {
        return registry.circuitBreaker(actor.tenantId() + ":" + provider);
    }

    CircuitBreaker acquire(long provider, Actor actor) {
        var circuit = circuit(provider, actor);
        if ("DEGRADED".equals(store.provider(provider, actor).get("status"))) {
            if (circuit.getState() == CircuitBreaker.State.CLOSED) circuit.transitionToOpenState();
            throw new PlatformException(503, "SYS-MODEL-503", "供应商处于持久熔断状态，等待半开健康探测");
        }
        if (!circuit.tryAcquirePermission())
            throw new PlatformException(503, "SYS-MODEL-503", "模型供应商熔断中，请稍后重试");
        return circuit;
    }

    void failed(
            CircuitBreaker circuit,
            long provider,
            Actor actor,
            long nanos,
            PlatformException error) {
        circuit.onError(nanos, java.util.concurrent.TimeUnit.NANOSECONDS, error);
        if (circuit.getState() == CircuitBreaker.State.OPEN)
            store.circuitState(provider, actor, "OPEN");
    }

    void recovered(long provider, Actor actor) {
        circuit(provider, actor).reset();
        store.circuitState(provider, actor, "CLOSED");
    }

    void succeeded(CircuitBreaker circuit, long provider, Actor actor, long nanos) {
        boolean halfOpen = circuit.getState() == CircuitBreaker.State.HALF_OPEN;
        circuit.onSuccess(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if (halfOpen && circuit.getState() == CircuitBreaker.State.CLOSED)
            store.circuitState(provider, actor, "CLOSED");
    }

    CircuitBreaker probe(long provider, Actor actor) {
        boolean restored = registry.find(actor.tenantId() + ":" + provider).isEmpty();
        var circuit = circuit(provider, actor);
        // 重启后先等待完整半开窗口；探活同样消费唯一半开许可，避免各入口绕过熔断探针。
        if (restored) {
            circuit.transitionToOpenState();
            return null;
        }
        return circuit.tryAcquirePermission() ? circuit : null;
    }
}
