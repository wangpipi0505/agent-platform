package cn.agentplatform.common.authority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * EX-43 §3.2 catalog/handler consistency gate.
 *
 * <p>In iam every {@code /internal/**} handler must own a catalog row whose mode is not
 * {@code disabled}; otherwise the service would expose an inbound surface that no caller may use or
 * that the guard would reject at runtime. The failure is therefore a startup failure. dev-hmac keeps
 * the historical warn-only behaviour so a partially migrated deployment stays observable during the
 * migration window rather than silently broken.
 */
public final class CoverageGate {
    public static final String CODE = "ROUTE_COVERAGE_MISSING";
    private static final Logger LOG = LoggerFactory.getLogger(CoverageGate.class);

    private CoverageGate() {}

    /** Fails closed in iam; warns in every other mode. */
    public static void verify(String mode, String serviceId, ServiceRoutePolicy routes,
            Collection<? extends RequestMappingInfoHandlerMapping> mappings) {
        var missing = missingInternal(serviceId, routes, mappings);
        if (missing.isEmpty()) return;
        var message = CODE + ": " + serviceId + " " + String.join(", ", missing);
        if ("iam".equals(mode)) throw new IllegalStateException(message);
        LOG.warn("{}", message);
    }

    /**
     * Reusable judgement shared with {@link RouteCoverageVerifier}: uncovered or disabled internal
     * handlers. Disabled rows are rejected here because an iam service that keeps a disabled
     * {@code /internal/**} handler assembled is a deployment mistake, not a runtime option.
     */
    public static List<String> missingInternal(String serviceId, ServiceRoutePolicy routes,
            Collection<? extends RequestMappingInfoHandlerMapping> mappings) {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(routes, "routes");
        var missing = new ArrayList<String>();
        for (var route : RouteCoverageVerifier.handlerRoutes(mappings)) {
            if (!isInternal(route.template())) continue;
            var row = RouteCoverageVerifier.catalogRow(serviceId, routes, route.method(), route.template());
            if (row.isEmpty()) missing.add(route.method() + " " + route.template() + " (no catalog row)");
            else if ("disabled".equals(row.get().mode()))
                missing.add(route.method() + " " + route.template() + " (disabled catalog row)");
        }
        return List.copyOf(missing);
    }

    private static boolean isInternal(String template) {
        return "/internal".equals(template) || template.startsWith("/internal/");
    }
}
