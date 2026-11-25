package cn.agentplatform.common.authority;

import java.util.Set;
import java.util.regex.Pattern;

/** The integration AGENT admission and its fixed, same-version execution edges. */
public final class IntegrationTaskRoutes {
    public static final String CREATE = "proxy.engine.post.TaskController.create";
    public static final String METADATA = "app.integration.published.metadata.read";
    public static final String METADATA_PATH = "/internal/apps/{code}/integration-published-metadata";
    public static final String TASK_MARKER = "integration_task";
    private static final Pattern SUBMIT = Pattern.compile("/v1/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/tasks");
    private static final Pattern APP_VERSION = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}@[1-9][0-9]{0,9}");

    private IntegrationTaskRoutes() {}

    public static String application(String method, String path) {
        var match = SUBMIT.matcher(path);
        return "POST".equals(method) && match.matches() ? match.group(1) : null;
    }

    public static ServiceCall metadataCall(String app) {
        return new ServiceCall("app", METADATA, IntegrationRuntimeRoutes.application(app));
    }

    public static ServiceCall startCall(String app, ResourceBinding version) {
        var call = new ServiceCall("engine", CREATE, version);
        if (!starts(call, app)) throw AuthorityProtocol.denied();
        return call;
    }

    public static boolean starts(ServiceCall call, String app) {
        return "engine".equals(call.target()) && CREATE.equals(call.action())
                && versionRoot(call.resource()) && call.resource().id().startsWith(app + "@");
    }

    public static boolean submitRoles(Set<String> roles) {
        return roles.contains("AGENT_OPERATOR") || roles.contains("AGENT_ADMIN");
    }

    public static boolean versionRoot(ResourceBinding root) {
        return root != null && "app-version".equals(root.kind()) && APP_VERSION.matcher(root.id()).matches()
                && root.versionDigest().matches("[0-9a-f]{64}");
    }

    public static boolean downstream(String caller, ServiceCall call) {
        if (!versionRoot(call.resource())) return false;
        if ("engine".equals(caller))
            return ("app".equals(call.target()) && "app.published.read".equals(call.action()))
                    || ("model".equals(call.target()) && "model.chat-stream".equals(call.action()));
        return "model".equals(caller) && "engine".equals(call.target()) && "material.scope.read".equals(call.action());
    }

    public static boolean modelExecution(VerifiedAuthority authority) {
        return "INTEGRATION".equals(authority.operation().originKind())
                && "DELEGATED".equals(authority.operation().sourceKind())
                && "model".equals(authority.call().target()) && downstream(authority.caller(), authority.call());
    }
}
