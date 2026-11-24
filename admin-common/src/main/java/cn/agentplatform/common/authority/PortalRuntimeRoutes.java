package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;

import java.util.Set;
import java.util.regex.Pattern;

/** The portal runtime collection is bound to the application in the selected MVC route. */
public final class PortalRuntimeRoutes {
    private static final Pattern COLLECTION =
            Pattern.compile("/v1/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/(sessions|tasks|memories)");
    private static final Set<String> TEMPLATES = Set.of(
            "/v1/apps/{code}/sessions", "/v1/apps/{code}/tasks", "/v1/apps/{code}/memories");

    private PortalRuntimeRoutes() {}

    public static boolean matches(String method, String path) {
        return ("GET".equals(method) || "POST".equals(method)) && COLLECTION.matcher(path).matches();
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path) {
        var match = COLLECTION.matcher(path);
        if (!("GET".equals(method) || "POST".equals(method)) || !match.matches())
            throw AuthorityProtocol.denied();
        var route = routes.require("gateway", "engine", method, "/v1/apps/{code}/" + match.group(2));
        return new ServiceCall(route.target(), route.action(), appResource(match.group(1)));
    }

    static boolean ownsTemplate(Object template) {
        return template instanceof String path && TEMPLATES.contains(path);
    }

    public static ResourceBinding appResource(String code) {
        MetadataSelectors.code(code);
        return new ResourceBinding("app-runtime", code, Digest.sha256("portal-runtime.v1:" + code));
    }

    public static boolean permitsPublishedRead(String action) {
        return Set.of("proxy.engine.post.ChatSessionController.create",
                "proxy.engine.post.TaskController.create", "proxy.engine.get.MemoryController.list",
                "proxy.engine.post.MemoryController.create").contains(action);
    }

    public static boolean isPinnedTaskCall(ServiceCall call, String code) {
        var resource = call.resource();
        return "proxy.engine.post.TaskController.create".equals(call.action()) && resource != null
                && "app-version".equals(resource.kind())
                && resource.id().matches(Pattern.quote(code) + "@[1-9][0-9]*")
                && resource.versionDigest().matches("[0-9a-f]{64}");
    }
}
