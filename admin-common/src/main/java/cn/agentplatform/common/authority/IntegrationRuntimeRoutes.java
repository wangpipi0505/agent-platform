package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact integration runtime actions and their credential-bound selectors. */
public final class IntegrationRuntimeRoutes {
    private static final Pattern METADATA =
            Pattern.compile("/v1/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/(input-schema|experience)");
    private static final Map<String, String> ACTIONS = Map.of(
            "input-schema", "proxy.app.get.AppController.inputSchema",
            "experience", "proxy.app.get.ExperienceController.experience");
    private static final String TASK_KIND = "integration-task";
    private static final String TASK_CODE = "task-[0-9a-f]{20}";
    private static final Pattern TASK = Pattern.compile("/v1/tasks/(" + TASK_CODE + ")(/events|/result)?");
    private static final Pattern TASK_CANCEL = Pattern.compile("/v1/tasks/(" + TASK_CODE + ")/cancel");
    private static final String CANCEL_ACTION = "proxy.engine.post.TaskController.cancel";
    private static final Set<String> TASK_READ_ACTIONS = Set.of("proxy.engine.get.TaskController.detail",
            "proxy.engine.get.TaskController.events", "proxy.engine.get.TaskController.result");

    private IntegrationRuntimeRoutes() {}

    public static boolean metadata(String method, String path) {
        return "GET".equals(method) && METADATA.matcher(path).matches();
    }

    public static boolean taskRead(String method, String path) {
        return "GET".equals(method) && TASK.matcher(path).matches();
    }

    public static boolean read(String method, String path) {
        return metadata(method, path) || taskRead(method, path);
    }

    public static boolean taskCancel(String method, String path) {
        return "POST".equals(method) && TASK_CANCEL.matcher(path).matches();
    }

    public static boolean supported(String method, String path) {
        return read(method, path) || taskCancel(method, path);
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path) {
        var match = METADATA.matcher(path);
        if (!"GET".equals(method) || !match.matches()) throw AuthorityProtocol.denied();
        var route = routes.require("gateway", "app", method, "/v1/apps/{code}/" + match.group(2));
        return new ServiceCall(route.target(), route.action(), application(match.group(1)));
    }

    public static ServiceCall taskCall(ServiceRoutePolicy routes, String method, String path, String app) {
        var cancel = TASK_CANCEL.matcher(path);
        if ("POST".equals(method) && cancel.matches()) {
            var route = routes.require("gateway", "engine", method, "/v1/tasks/{taskId}/cancel");
            return new ServiceCall(route.target(), route.action(), task(app, cancel.group(1)));
        }
        var match = TASK.matcher(path);
        if (!"GET".equals(method) || !match.matches()) throw AuthorityProtocol.denied();
        String suffix = match.group(2) == null ? "" : match.group(2);
        var route = routes.require("gateway", "engine", method, "/v1/tasks/{taskId}" + suffix);
        return new ServiceCall(route.target(), route.action(), task(app, match.group(1)));
    }

    public static void requireTaskQuery(String path, String query) {
        if (query == null) return;
        if (!path.endsWith("/events") || query.length() > 64) throw invalidQuery();
        var names = new HashSet<String>();
        for (String field : query.split("&", -1)) {
            if (!field.matches("afterSeq=[0-9]{1,18}|limit=[0-9]{1,9}")
                    || !names.add(field.substring(0, field.indexOf('=')))) throw invalidQuery();
        }
    }

    private static PlatformException invalidQuery() {
        return new PlatformException(400, "TASK_QUERY_INVALID", "任务查询参数无效");
    }

    private static ResourceBinding task(String app, String publicId) {
        MetadataSelectors.code(app);
        if (!publicId.matches(TASK_CODE)) throw AuthorityProtocol.denied();
        String id = app + ":" + publicId;
        return new ResourceBinding(TASK_KIND, id, Digest.sha256("integration-task.v1:" + id));
    }

    public static ResourceBinding application(String app) {
        MetadataSelectors.code(app);
        return new ResourceBinding("integration-app", app, Digest.sha256("integration-app.v1:" + app));
    }

    public static boolean permits(ServiceCall call, String credentialApp) {
        if ("engine".equals(call.target())
                && (TASK_READ_ACTIONS.contains(call.action()) || CANCEL_ACTION.equals(call.action()))
                && isTaskSelector(call.resource())) {
            String prefix = credentialApp + ":";
            String id = call.resource().id();
            return id.startsWith(prefix) && id.substring(prefix.length()).matches(TASK_CODE)
                    && task(credentialApp, id.substring(prefix.length())).equals(call.resource());
        }
        return "app".equals(call.target()) && ACTIONS.containsValue(call.action())
                && application(credentialApp).equals(call.resource());
    }

    public static boolean isTaskSelector(ResourceBinding resource) {
        return resource != null && TASK_KIND.equals(resource.kind());
    }

    public static boolean permitsRoles(ServiceCall call, Set<String> roles) {
        return CANCEL_ACTION.equals(call.action()) ? roles.contains("AGENT_ADMIN") : !roles.isEmpty();
    }

    public record TaskScope(String appCode, String bizSystemCode, Set<String> dataLabels, long credentialId) {}

    /** Only the mapped receiver's current, verified integration service ticket can supply this scope. */
    public static TaskScope currentTaskScope(String publicId) {
        var jwt = currentJwt();
        if (jwt == null || !"service".equals(jwt.getClaimAsString("token_use"))) return null;
        var claims = Json.tree(jwt.getClaims());
        var operation = claims.path("operation");
        if (!"INTEGRATION".equals(operation.path("origin_kind").textValue())) return null;
        var verified = AuthorityContext.current();
        if (!"INTEGRATION".equals(verified.operation().originKind()) || !isTaskSelector(verified.call().resource()))
            throw AuthorityProtocol.denied();
        var identity = IntegrationSource.decisionIdentity(operation.get("integration_credential"), claims);
        String app = identity.path("app_code").textValue();
        AuthorityProtocol.same(task(app, publicId), verified.call().resource());
        return new TaskScope(app, identity.path("biz_system_code").textValue(),
                verified.actor().attributes().getOrDefault("dataLabels", Set.of()),
                identity.path("credential_id").longValue());
    }

    public static Jwt currentSource() {
        var jwt = currentJwt();
        if (jwt == null || !"integration".equals(jwt.getClaimAsString("token_use"))
                || !IntegrationSource.TYPE.equals(jwt.getHeaders().get("typ"))) return null;
        return jwt;
    }

    private static Jwt currentJwt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }
}
