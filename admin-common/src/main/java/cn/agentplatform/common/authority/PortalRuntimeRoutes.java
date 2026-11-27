package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;

import java.util.Set;
import java.util.regex.Pattern;

/** Bind the selected portal route to its application or its exact published chat session. */
public final class PortalRuntimeRoutes {
    public static final String SESSION_KIND = "portal-chat-session";
    public static final String MEMORY_KIND = "portal-memory";
    public static final String QUERY_KIND = "portal-runtime-query";
    public static final String ACCESS_PATH = "/internal/platform-authorization/runtime";
    public static final String ACCESS_ACTION = "platform.authorization.runtime";
    public static final String RESOURCE_MANIFEST = "/v1/deploy/resource-manifest";
    public static final String TURN_ACTION = "proxy.engine.post.ChatSessionController.turn";
    public static final String STREAM_ACTION = "proxy.engine.get.ChatSessionController.stream";
    private static final String CODE = "[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final String SESSION_ID = "s_[a-f0-9]{32}";
    private static final String SESSION = "/v1/apps/{code}/sessions/{id}";
    private static final String MEMORY = "/v1/apps/{code}/memories/{memoryId}";
    private static final String MEMORY_ID = "[A-Za-z0-9_-]{1,128}";
    private static final Pattern MEMORY_PATH = Pattern.compile("/v1/apps/(" + CODE + ")/memories/(" + MEMORY_ID + ")");
    private static final Set<String> QUERY_PATHS = Set.of("/v1/tasks", "/admin/confirmation-center/counts", RESOURCE_MANIFEST);
    private static final Pattern PUBLISHED_RUN = Pattern.compile("/admin/apps/(" + CODE + ")/runs");
    private static final Pattern COLLECTION =
            Pattern.compile("/v1/apps/(" + CODE + ")/(sessions|tasks|memories)");
    private static final Pattern DETAIL = Pattern.compile(
            "/v1/apps/(" + CODE + ")/sessions/(" + SESSION_ID + ")(/turns|/events(?:/stream)?)?");
    private static final Set<String> TEMPLATES = Set.of(
            "/v1/apps/{code}/sessions", "/v1/apps/{code}/tasks", "/v1/apps/{code}/memories",
            SESSION, SESSION + "/turns", SESSION + "/events", SESSION + "/events/stream", MEMORY,
            "/v1/tasks", "/admin/confirmation-center/counts", RESOURCE_MANIFEST);

    private PortalRuntimeRoutes() {}

    public static String publishedRunApp(String method, String path) {
        var match = PUBLISHED_RUN.matcher(path);
        return "POST".equals(method) && match.matches() ? match.group(1) : null;
    }

    public static boolean matches(String method, String path) {
        if ("GET".equals(method) && QUERY_PATHS.contains(path)) return true;
        if (memoryMethod(method) && MEMORY_PATH.matcher(path).matches()) return true;
        var detail = DETAIL.matcher(path);
        if (detail.matches()) return detailMethod(method, detail.group(3));
        return ("GET".equals(method) || "POST".equals(method)) && COLLECTION.matcher(path).matches();
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path) {
        return call(routes, method, path, null);
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path, String[] appCodes) {
        if ("GET".equals(method) && QUERY_PATHS.contains(path)) {
            if (appCodes != null && appCodes.length != 1) throw AuthorityProtocol.denied();
            String app = appCodes == null ? "" : appCodes[0];
            if (RESOURCE_MANIFEST.equals(path) || !app.isEmpty()) MetadataSelectors.code(app);
            var route = routes.require("gateway", "engine", method, path);
            String selector = path + ":" + app;
            return new ServiceCall(route.target(), route.action(), new ResourceBinding(
                    QUERY_KIND, Digest.sha256(selector), Digest.sha256("portal-runtime-query.v1:" + selector)));
        }
        var memory = MEMORY_PATH.matcher(path);
        if (memoryMethod(method) && memory.matches()) {
            var route = routes.require("gateway", "engine", method, MEMORY);
            return new ServiceCall(route.target(), route.action(), memoryResource(memory.group(1), memory.group(2)));
        }
        var detail = DETAIL.matcher(path);
        if (detail.matches() && detailMethod(method, detail.group(3))) {
            var template = SESSION + (detail.group(3) == null ? "" : detail.group(3));
            var route = routes.require("gateway", "engine", method, template);
            return new ServiceCall(route.target(), route.action(), sessionResource(detail.group(1), detail.group(2)));
        }
        var match = COLLECTION.matcher(path);
        if (!("GET".equals(method) || "POST".equals(method)) || !match.matches())
            throw AuthorityProtocol.denied();
        var route = routes.require("gateway", "engine", method, "/v1/apps/{code}/" + match.group(2));
        return new ServiceCall(route.target(), route.action(), appResource(match.group(1)));
    }

    private static boolean detailMethod(String method, String suffix) {
        return ("/turns".equals(suffix) ? "POST" : "GET").equals(method);
    }

    private static boolean memoryMethod(String method) {
        return Set.of("GET", "PATCH", "DELETE").contains(method);
    }

    private static ResourceBinding memoryResource(String code, String id) {
        String selector = code + "@" + id;
        return new ResourceBinding(MEMORY_KIND, selector, Digest.sha256("portal-memory.v1:" + selector));
    }

    public static boolean memoryForApp(ResourceBinding root, String code) {
        String prefix = code + "@";
        if (root == null || !MEMORY_KIND.equals(root.kind()) || !root.id().startsWith(prefix)) return false;
        String id = root.id().substring(prefix.length());
        return id.matches(MEMORY_ID) && memoryResource(code, id).equals(root);
    }

    public static ServiceCall accessCall(ResourceBinding root) {
        if (root == null || !Set.of(QUERY_KIND, MEMORY_KIND, SESSION_KIND).contains(root.kind()))
            throw AuthorityProtocol.denied();
        return new ServiceCall("app", ACCESS_ACTION, root);
    }

    public static ResourceBinding sessionResource(String code, String session) {
        MetadataSelectors.code(code);
        if (session == null || !session.matches(SESSION_ID)) throw AuthorityProtocol.denied();
        String id = code + "@" + session;
        return new ResourceBinding(SESSION_KIND, id, Digest.sha256("portal-chat-session.v1:" + id));
    }

    public static boolean sessionForApp(ResourceBinding root, String code) {
        String prefix = code + "@";
        return root != null && SESSION_KIND.equals(root.kind()) && root.id().startsWith(prefix)
                && root.id().substring(prefix.length()).matches(SESSION_ID)
                && sessionResource(code, root.id().substring(prefix.length())).equals(root);
    }

    public static boolean isSessionTurn(VerifiedAuthority current) {
        return current != null && current.operation() != null && "gateway".equals(current.caller())
                && "engine".equals(current.call().target()) && TURN_ACTION.equals(current.call().action()) && current.call().resource() != null
                && SESSION_KIND.equals(current.call().resource().kind())
                && "PORTAL".equals(current.operation().sourceKind())
                && "PORTAL".equals(current.operation().originKind())
                && java.util.Objects.equals(current.call().resource(), current.operation().resource());
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

    /** Directory attributes can appear on portal users, so only the bound route proves its source. */
    public static boolean currentTaskStartMatches(String code, Actor actor) {
        var current = AuthorityContext.currentIfBound();
        return current != null
                && current.actor().equals(actor)
                && "gateway".equals(current.caller())
                && "engine".equals(current.call().target())
                && "PORTAL".equals(current.operation().sourceKind())
                && "PORTAL".equals(current.operation().originKind())
                && isPinnedTaskCall(current.call(), code);
    }
}
