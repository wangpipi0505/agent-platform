package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Public owner operations. The selector binds method and concrete task, step, plan and revision. */
public final class PortalTaskRoutes {
    public static final String ACCESS_PATH = "/internal/platform-authorization/owned-task";
    public static final String ACCESS_ACTION = "platform.authorization.owned-task";
    private static final String KIND = "portal-owned-task";
    private static final String BASE = "/v1/tasks/{taskId}";
    private static final String REVISION = "/plans/{planId}/revisions/{revision}";
    private static final String READ = "agent.view";
    public static final String CONTROL = "agent.task.control";
    private static final String WRITE = "agent.plan.revise";
    public static final String CONFIRM = "agent.plan.confirm";
    public static final String EXECUTE = "agent.plan.execute";
    public static final String OPERATE = "agent.plan.operate";
    private static final Set<String> PERMISSIONS = Set.of(READ, CONTROL, WRITE, CONFIRM, EXECUTE);
    private static final List<Route> ROUTES = List.of(
            route("GET", "", READ), route("GET", "/events", READ), route("GET", "/result", READ),
            route("GET", "/steps/{stepId}/calls", READ), route("GET", REVISION, READ),
            route("POST", "/cancel", CONTROL), route("POST", "/resume", CONTROL),
            route("POST", "/plans", WRITE), route("POST", REVISION + "/confirm", CONFIRM),
            route("POST", "/execute", EXECUTE));

    private record Route(String method, String template, Pattern path, String permission) {}
    public record Matched(String method, String path, String template, String taskId, String permission) {
        public ServiceCall call(ServiceRoutePolicy policy) {
            var entry = policy.require("gateway", "engine", method, template);
            var root = new ResourceBinding(KIND, taskId + "@" + permission,
                    Digest.sha256("portal-owned-task.v1:" + method + ":" + path));
            return new ServiceCall(entry.target(), entry.action(), root);
        }
    }

    private PortalTaskRoutes() {}

    private static Route route(String method, String suffix, String permission) {
        String template = BASE + suffix;
        String path = template.replace("{taskId}", "(task-[0-9a-f]{20})")
                .replace("{planId}", "[A-Za-z0-9_-]{1,64}").replace("{stepId}", "[A-Za-z0-9_-]{1,64}")
                .replace("{revision}", "[1-9][0-9]{0,8}");
        return new Route(method, template, Pattern.compile(path), permission);
    }

    public static Matched match(String method, String path) {
        if (!path.startsWith("/v1/tasks/")) return null;
        for (var route : ROUTES) {
            if (!method.equals(route.method())) continue;
            var matcher = route.path().matcher(path);
            if (matcher.matches()) return new Matched(method, path, route.template(), matcher.group(1), route.permission());
        }
        return null;
    }

    public static boolean ownsTemplate(String method, Object template) {
        return ROUTES.stream().anyMatch(r -> r.method().equals(method) && r.template().equals(template));
    }

    public static String permission(ResourceBinding resource) {
        if (resource == null || !KIND.equals(resource.kind()) || !resource.versionDigest().matches("[0-9a-f]{64}"))
            throw AuthorityProtocol.denied();
        String[] parts = resource.id().split("@", -1);
        if (parts.length != 2 || !parts[0].matches("task-[0-9a-f]{20}") || !PERMISSIONS.contains(parts[1]))
            throw AuthorityProtocol.denied();
        return parts[1];
    }

    public static boolean requiresPlan(String permission) {
        return Set.of(WRITE, CONFIRM, EXECUTE).contains(permission);
    }

    public static VerifiedAuthority current(String taskId) {
        var current = AuthorityContext.currentIfBound();
        if (current == null || !KIND.equals(current.call().resource().kind())) return null;
        permission(current.call().resource());
        if (!"gateway".equals(current.caller()) || !"engine".equals(current.call().target())
                || !"PORTAL".equals(current.operation().originKind())
                || !current.call().resource().id().startsWith(taskId + "@")) throw AuthorityProtocol.denied();
        return current;
    }
}
