package cn.agentplatform.common.authority;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.io.IOException;
import java.util.*;

/** Exact controller-template catalog, not a URL matcher or proof of concrete resource access. */
public final class ServiceRoutePolicy {
    public record Route(String method, String template, String caller, String target, String action,
                        String mode, boolean integrationAllowed, String source, String callerSource, String reason) {}
    private record Key(String caller, String target, String method, String template) {}
    private record Handler(String target, String method, String template) {}
    private final List<Route> routes;
    private final Map<Key, Route> capabilities;

    public ServiceRoutePolicy() { this(load()); }

    public ServiceRoutePolicy(List<Route> entries) {
        routes = List.copyOf(entries);
        var indexed = new HashMap<Key, Route>();
        var disabled = new HashSet<Handler>();
        var enabled = new HashMap<Handler, String>();
        for (var row : routes) {
            if (!AuthorityProperties.SERVICES.contains(Objects.toString(row.target(), ""))
                    || !Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD", "ANY").contains(Objects.toString(row.method(), ""))
                    || row.template() == null || !row.template().startsWith("/")
                    || row.template().contains("?") || row.template().contains("#")
                    || row.action() == null || !row.action().matches("[A-Za-z][A-Za-z0-9_.-]*")
                    || row.source() == null || row.source().isBlank()) throw invalid();
            var handler = new Handler(row.target(), row.method(), row.template());
            if ("disabled".equals(row.mode())) {
                if (row.caller() != null || row.integrationAllowed() || row.reason() == null || row.reason().isBlank()
                        || !disabled.add(handler)) throw invalid();
            } else {
                if (!Set.of("delegated", "workload").contains(Objects.toString(row.mode(), ""))
                        || !AuthorityProperties.SERVICES.contains(Objects.toString(row.caller(), ""))
                        || row.template().contains("*") || "ANY".equals(row.method())
                        || (row.integrationAllowed() && (!"gateway".equals(row.caller()) || !"delegated".equals(row.mode()))))
                    throw invalid();
                String previous = enabled.putIfAbsent(handler, row.action());
                if (previous != null && !previous.equals(row.action())) throw invalid();
                if (indexed.putIfAbsent(new Key(row.caller(), row.target(), row.method(), row.template()), row) != null)
                    throw invalid();
            }
        }
        if (disabled.stream().anyMatch(enabled::containsKey)) throw invalid();
        capabilities = Map.copyOf(indexed);
    }

    /** Local continuation capability has no URL and is never returned by require/routes. */
    public static boolean localRunAdvance(String caller, ServiceCall call, String mode, String source, String origin) {
        return call != null && "engine".equals(caller) && "engine".equals(call.target())
            && "run.advance".equals(call.action()) && "delegated".equals(mode)
            && call.resource() != null && "app-version".equals(call.resource().kind())
            && "DELEGATED".equals(source) && "PORTAL".equals(origin);
    }

    public List<Route> routes() { return routes; }

    public ServiceCall require(String caller, String target, String method, String template) {
        var row = capabilities.get(new Key(caller, target, method, template));
        if (row == null) throw new PlatformException(403, "SYS-AUTH-403", "服务调用能力未登记");
        return new ServiceCall(row.target(), row.action(), null);
    }

    private static List<Route> load() {
        try (var input = ServiceRoutePolicy.class.getResourceAsStream("/agent-service-authority.json")) {
            if (input == null) throw invalid();
            return List.of(Json.mapper().readValue(input, Route[].class));
        } catch (IOException ex) { throw new IllegalArgumentException("SERVICE_ROUTE_CATALOG_INVALID", ex); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SERVICE_ROUTE_CATALOG_INVALID"); }
}
