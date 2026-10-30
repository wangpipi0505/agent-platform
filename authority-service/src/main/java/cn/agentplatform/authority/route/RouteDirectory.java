package cn.agentplatform.authority.route;

import cn.agentplatform.common.authority.ServiceRoutePolicy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 授权判定的唯一依据：与平台侧同一份 classpath 资源 {@code agent-service-authority.json}，
 * 由 admin-common 的 {@link ServiceRoutePolicy} 加载/校验（行级不变量只在一处维护）。
 * <p>判定是"白名单 + 模式精确匹配"：同 (caller,target,action) 但 mode 不同的行不算命中，
 * disabled 行因为 caller 恒为 null 且 mode=disabled，永远不会被 {@link #permits} 命中。
 */
@Component
public final class RouteDirectory {

    private static final Logger log = LoggerFactory.getLogger(RouteDirectory.class);
    public static final String WORKLOAD = "workload";

    private final List<ServiceRoutePolicy.Route> routes;

    public RouteDirectory() {
        this.routes = List.copyOf(new ServiceRoutePolicy().routes());
    }

    @PostConstruct
    void report() {
        long workload = routes.stream().filter(route -> WORKLOAD.equals(route.mode())).count();
        long enabled = routes.stream().filter(route -> route.caller() != null).count();
        long disabled = routes.size() - enabled;
        log.info("authority.routes rows={} enabled={} disabled={} workload={}", routes.size(), enabled, disabled, workload);
        if (workload == 0) {
            // 没有 workload 行时本服务只能拒绝：这是配置错误而不是运行时错误，必须显式告警。
            log.warn("authority.routes workload=0：目录里没有任何 workload 行，所有 machine-to-machine 请求都会被拒绝");
        }
    }

    public boolean permits(String caller, String target, String action, String mode) {
        if (caller == null || target == null || action == null || mode == null) {
            return false;
        }
        return routes.stream().anyMatch(route -> Objects.equals(caller, route.caller())
                && Objects.equals(target, route.target())
                && Objects.equals(action, route.action())
                && mode.equals(route.mode()));
    }

    public List<ServiceRoutePolicy.Route> routes() {
        return routes;
    }
}
