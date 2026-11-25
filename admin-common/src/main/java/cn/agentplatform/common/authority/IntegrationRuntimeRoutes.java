package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
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
    private static final String RESULT_FILE_KIND = "integration-task-result";
    private static final String RESULT_FILE_ACTION = "proxy.engine.get.TaskController.downloadResult";
    public static final String RESULT_FILE_TEMPLATE = "/v1/runs/{runId}/artifacts/{artifactId}/download";
    private static final String RUN_ID = "r_[0-9a-f]{32}";
    private static final Pattern RESULT_FILE =
            Pattern.compile("/v1/runs/r_([0-9a-f]{32})/artifacts/task_result_\\1/download");
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

    public static boolean resultFileRead(String method, String path) {
        return "GET".equals(method) && RESULT_FILE.matcher(path).matches();
    }

    public static boolean read(String method, String path) {
        return metadata(method, path) || taskRead(method, path) || resultFileRead(method, path);
    }

    public static boolean taskCancel(String method, String path) {
        return "POST".equals(method) && TASK_CANCEL.matcher(path).matches();
    }

    public static boolean supported(String method, String path) {
        return read(method, path) || taskCancel(method, path)
                || IntegrationTaskRoutes.application(method, path) != null;
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path) {
        var match = METADATA.matcher(path);
        if (!"GET".equals(method) || !match.matches()) throw AuthorityProtocol.denied();
        var route = routes.require("gateway", "app", method, "/v1/apps/{code}/" + match.group(2));
        return new ServiceCall(route.target(), route.action(), application(match.group(1)));
    }

    public static ServiceCall taskCall(ServiceRoutePolicy routes, String method, String path, String app) {
        if ("GET".equals(method)) {
            var file = RESULT_FILE.matcher(path);
            if (file.matches()) {
                var route = routes.require("gateway", "engine", method, RESULT_FILE_TEMPLATE);
                return new ServiceCall(route.target(), route.action(), resultFile(app, "r_" + file.group(1)));
            }
        }
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

    public static String resultFileId(String runId) {
        if (!runId.matches(RUN_ID)) throw AuthorityProtocol.denied();
        return "task_result_" + runId.substring(2);
    }

    private static ResourceBinding resultFile(String app, String runId) {
        MetadataSelectors.code(app);
        if (!runId.matches(RUN_ID)) throw AuthorityProtocol.denied();
        String id = app + ":" + runId;
        return new ResourceBinding(RESULT_FILE_KIND, id, Digest.sha256("integration-task-result.v1:" + id));
    }

    public static boolean permits(ServiceCall call, String credentialApp) {
        if (IntegrationTaskRoutes.starts(call, credentialApp)
                || IntegrationTaskRoutes.metadataCall(credentialApp).equals(call)) return true;
        if ("engine".equals(call.target()) && isScopedRuntimeSelector(call.resource())) {
            String prefix = credentialApp + ":";
            String id = call.resource().id();
            if (!id.startsWith(prefix)) return false;
            String value = id.substring(prefix.length());
            if (RESULT_FILE_KIND.equals(call.resource().kind())) {
                return RESULT_FILE_ACTION.equals(call.action()) && value.matches(RUN_ID)
                        && resultFile(credentialApp, value).equals(call.resource());
            }
            return (TASK_READ_ACTIONS.contains(call.action()) || CANCEL_ACTION.equals(call.action()))
                    && value.matches(TASK_CODE) && task(credentialApp, value).equals(call.resource());
        }
        return "app".equals(call.target()) && ACTIONS.containsValue(call.action())
                && application(credentialApp).equals(call.resource());
    }

    public static boolean isScopedRuntimeSelector(ResourceBinding resource) {
        return resource != null && (TASK_KIND.equals(resource.kind()) || RESULT_FILE_KIND.equals(resource.kind()));
    }

    public static boolean permitsRoles(ServiceCall call, Set<String> roles) {
        if (IntegrationTaskRoutes.CREATE.equals(call.action()) || IntegrationTaskRoutes.METADATA.equals(call.action()))
            return IntegrationTaskRoutes.submitRoles(roles);
        return CANCEL_ACTION.equals(call.action()) ? roles.contains("AGENT_ADMIN") : !roles.isEmpty();
    }

    public record TaskScope(String appCode, String bizSystemCode, Set<String> dataLabels, long credentialId) {}

    /** Only the mapped receiver's current, verified integration service ticket can supply this scope. */
    public static TaskScope currentTaskScope(String publicId) {
        return currentScope((verified, app) -> AuthorityProtocol.same(task(app, publicId), verified.call().resource()));
    }

    public static TaskScope currentStartScope(String appCode) {
        return currentScope((verified, app) -> {
            AuthorityProtocol.same(app, appCode);
            if (!IntegrationTaskRoutes.starts(verified.call(), app)
                    || !IntegrationTaskRoutes.submitRoles(verified.actor().roles())) throw AuthorityProtocol.denied();
        });
    }

    public static TaskScope currentResultFileScope(String runId, String artifactId) {
        return currentScope((verified, app) -> {
            AuthorityProtocol.same(artifactId, resultFileId(runId));
            AuthorityProtocol.same(new ServiceCall("engine", RESULT_FILE_ACTION, resultFile(app, runId)),
                    verified.call());
        });
    }

    private static TaskScope currentScope(BiConsumer<VerifiedAuthority, String> check) {
        var jwt = currentJwt();
        if (jwt == null || !"service".equals(jwt.getClaimAsString("token_use"))) return null;
        var claims = Json.tree(jwt.getClaims());
        var operation = claims.path("operation");
        if (!"INTEGRATION".equals(operation.path("origin_kind").textValue())) return null;
        var verified = AuthorityContext.current();
        if (!"INTEGRATION".equals(verified.operation().originKind()))
            throw AuthorityProtocol.denied();
        var identity = IntegrationSource.decisionIdentity(operation.get("integration_credential"), claims);
        String app = identity.path("app_code").textValue();
        check.accept(verified, app);
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
