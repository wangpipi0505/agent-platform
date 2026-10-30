package cn.agentplatform.common.authority;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.lang.reflect.Method;
import java.util.*;

/** Complete MVC mapping inspection before Boot starts accepting requests. */
public final class RouteCoverageVerifier implements SmartInitializingSingleton {
    /**
     * Explicit non-service ingress registration. This grants no service capability; its owning
     * portal/integration/policy chain must enforce that domain independently. No wildcard
     * exemption.
     */
    public record Ingress(String method, String template, Method handler) {
        public Ingress {
            if (method == null
                    || !Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                            .contains(method)
                    || template == null
                    || !template.startsWith("/")
                    || template.contains("*")
                    || handler == null)
                throw new IllegalArgumentException("INGRESS_REGISTRATION_INVALID");
        }
    }

    /**
     * One expanded handler ingress. Both the legacy verifier and the iam startup gate judge the
     * same expansion, so a handler cannot be covered by one check and missing in the other.
     */
    public record HandlerRoute(
            String method, String template, HandlerMethod handler, RequestMappingInfoHandlerMapping mapping) {}

    private final String serviceId;
    private final ServiceRoutePolicy routes;
    private final List<RequestMappingInfoHandlerMapping> mappings;
    private final Set<Ingress> ingress;

    public record GatewayProxyIngress(
            GatewayIngressBoundary boundary,
            org.springframework.security.web.FilterChainProxy chain,
            org.springframework.security.web.SecurityFilterChain expected,
            GatewayIngressBoundary.InstalledFilterRegistration registration,
            jakarta.servlet.ServletContext context) {
        public GatewayProxyIngress {
            Objects.requireNonNull(boundary);
            Objects.requireNonNull(chain);
            Objects.requireNonNull(expected);
            Objects.requireNonNull(registration);
            Objects.requireNonNull(context);
        }
    }

    private final GatewayProxyIngress gateway;

    public RouteCoverageVerifier(
            String serviceId,
            ServiceRoutePolicy routes,
            Collection<? extends RequestMappingInfoHandlerMapping> mappings) {
        this(serviceId, routes, mappings, Set.of());
    }

    public RouteCoverageVerifier(
            String serviceId,
            ServiceRoutePolicy routes,
            Collection<? extends RequestMappingInfoHandlerMapping> mappings,
            Set<Ingress> ingress) {
        this(serviceId, routes, mappings, ingress, null);
    }

    public RouteCoverageVerifier(
            String serviceId,
            ServiceRoutePolicy routes,
            Collection<? extends RequestMappingInfoHandlerMapping> mappings,
            Set<Ingress> ingress,
            GatewayProxyIngress gateway) {
        if (!AuthorityProperties.SERVICES.contains(serviceId))
            throw new IllegalArgumentException("SERVICE_ID_INVALID");
        this.serviceId = serviceId;
        this.routes = Objects.requireNonNull(routes);
        this.mappings = List.copyOf(mappings);
        if (this.mappings.isEmpty()) throw new IllegalArgumentException("ROUTE_MAPPINGS_REQUIRED");
        this.ingress = Set.copyOf(ingress);
        if (gateway != null && (!"gateway".equals(serviceId) || !ingress.isEmpty()))
            throw new IllegalArgumentException("GATEWAY_INGRESS_INVALID");
        this.gateway = gateway;
    }

    /**
     * Expands handler metadata exactly once: request methods (ANY when unconstrained) crossed with
     * pattern values, skipping only Boot's built-in error rendering.
     */
    public static List<HandlerRoute> handlerRoutes(
            Collection<? extends RequestMappingInfoHandlerMapping> mappings) {
        var routes = new ArrayList<HandlerRoute>();
        for (var mapping : mappings)
            mapping.getHandlerMethods()
                    .forEach(
                            (info, handler) -> {
                                Set<String> methods = new HashSet<>();
                                info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
                                if (methods.isEmpty()) methods.add("ANY");
                                for (String method : methods)
                                    for (String template : info.getPatternValues()) {
                                        if (frameworkError(method, template, handler)) continue;
                                        routes.add(new HandlerRoute(method, template, handler, mapping));
                                    }
                            });
        return List.copyOf(routes);
    }

    /**
     * The single catalog-coverage judgement: a service handler is covered when some row targets this
     * service with the same method and template. Disabled rows still count here because the legacy
     * verifier also requires the runtime denial guard for them.
     */
    public static Optional<ServiceRoutePolicy.Route> catalogRow(
            String serviceId, ServiceRoutePolicy routes, String method, String template) {
        return routes.routes().stream()
                .filter(
                        r ->
                                serviceId.equals(r.target())
                                        && method.equals(r.method())
                                        && template.equals(r.template()))
                .findFirst();
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Context 6.2.7 preInstantiateSingletons invokes SmartInitializingSingleton before
        // finishRefresh/lifecycle.onRefresh. Boot 3.5.0 Tomcat initializes with removed connectors
        // and bindOnInit=false; WebServerStartStopLifecycle.start is the later listening boundary.
        var seen = new HashSet<Ingress>();
        var proxyMappings = new HashSet<String>();
        var sessionMappings = new HashSet<String>();
        if (gateway != null) {
            gateway.boundary().requireChain(gateway.chain(), gateway.expected());
            gateway.registration().requireInstalled(gateway.context(), gateway.chain());
        }
        for (var route : handlerRoutes(mappings)) {
            String method = route.method();
            String template = route.template();
            HandlerMethod handler = route.handler();
            var mapping = route.mapping();
            if (gateway != null) {
                var boundaryInterceptors = mapping.getAdaptedInterceptors();
                long guards =
                        boundaryInterceptors == null
                                ? 0
                                : Arrays.stream(boundaryInterceptors)
                                        .filter(
                                                i ->
                                                        i instanceof MappedInterceptor mapped
                                                                && mapped.getIncludePathPatterns() == null
                                                                && mapped.getExcludePathPatterns() == null
                                                                && mapped.getInterceptor()
                                                                        == gateway.boundary())
                                        .count();
                if (guards != 1)
                    throw new IllegalStateException("GATEWAY_GLOBAL_GUARD_REQUIRED");
                if ("/auth/session".equals(template) || gateway.boundary().ownsSession(handler)) {
                    if (!"GET".equals(method)
                            || !"/auth/session".equals(template)
                            || !gateway.boundary().ownsSession(handler)
                            || !sessionMappings.add(template))
                        throw new IllegalStateException("GATEWAY_SESSION_MAPPING_INVALID");
                    long catalog =
                            routes.routes().stream()
                                    .filter(
                                            r ->
                                                    "GET".equals(r.method())
                                                            && "/auth/session".equals(r.template())
                                                            && "gateway".equals(r.caller())
                                                            && "gateway".equals(r.target())
                                                            && "portal.session.read".equals(r.action())
                                                            && "delegated".equals(r.mode())
                                                            && !r.integrationAllowed())
                                    .count();
                    if (catalog != 1)
                        throw new IllegalStateException("GATEWAY_SESSION_CATALOG_INVALID");
                    continue;
                }
                if ("ANY".equals(method)) {
                    if (!Set.of("/admin/**", "/v1/**").contains(template)
                            || !gateway.boundary().owns(handler)
                            || !proxyMappings.add(template))
                        throw new IllegalStateException("GATEWAY_ANY_MAPPING_INVALID");
                    continue;
                }
            }
            var registration =
                    ingress.stream()
                            .filter(
                                    i ->
                                            i.method().equals(method)
                                                    && i.template().equals(template)
                                                    && i.handler().equals(handler.getMethod()))
                            .findFirst()
                            .orElse(null);
            if (registration != null && ingress.contains(registration)) {
                seen.add(registration);
                continue;
            }
            if (catalogRow(serviceId, routes, method, template).isEmpty())
                throw new IllegalStateException(
                        "ROUTE_COVERAGE_MISSING: "
                                + serviceId
                                + " "
                                + method
                                + " "
                                + template
                                + " "
                                + handler.getMethod().toGenericString());
            if (gateway != null) continue; // Same global boundary denies every other gateway catalog row.
            // Disabled service rows also need the runtime denial guard. A bean elsewhere, or a
            // partial include/exclude mapping, proves nothing about this handler chain.
            var interceptors = mapping.getAdaptedInterceptors();
            boolean guarded =
                    interceptors != null
                            && Arrays.stream(interceptors)
                                    .anyMatch(
                                            interceptor ->
                                                    interceptor instanceof MappedInterceptor mapped
                                                            && mapped.getIncludePathPatterns() == null
                                                            && mapped.getExcludePathPatterns() == null
                                                            && mapped.getInterceptor()
                                                                    instanceof ServiceAuthorization receiver
                                                            && receiver.guards(serviceId));
            if (!guarded)
                throw new IllegalStateException(
                        "ROUTE_GUARD_MISSING: " + serviceId + " " + method + " " + template);
        }
        if (!seen.equals(ingress)) throw new IllegalStateException("INGRESS_REGISTRATION_UNUSED");
        if (gateway != null && !proxyMappings.equals(Set.of("/admin/**", "/v1/**")))
            throw new IllegalStateException("GATEWAY_ANY_MAPPING_MISSING");
        if (gateway != null
                && gateway.boundary().sessionInstalled() != sessionMappings.equals(Set.of("/auth/session")))
            throw new IllegalStateException("GATEWAY_SESSION_MAPPING_MISSING");
    }

    private static boolean frameworkError(String method, String template, HandlerMethod handler) {
        // Only Boot's exact built-in error rendering methods. Custom controllers, docs, health
        // aliases and custom error paths still require registration; no package-wide exemption.
        return "ANY".equals(method)
                && "/error".equals(template)
                && handler.getBeanType() == BasicErrorController.class
                && handler.getMethod().getDeclaringClass() == BasicErrorController.class
                && Set.of("error", "errorHtml").contains(handler.getMethod().getName());
    }
}
