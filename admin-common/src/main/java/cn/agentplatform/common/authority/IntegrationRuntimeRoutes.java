package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;
import java.util.regex.Pattern;

/** The credential's app is the resource for integration metadata reads. */
public final class IntegrationRuntimeRoutes {
    private static final Pattern METADATA =
            Pattern.compile("/v1/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/(input-schema|experience)");
    private static final Map<String, String> ACTIONS = Map.of(
            "input-schema", "proxy.app.get.AppController.inputSchema",
            "experience", "proxy.app.get.ExperienceController.experience");

    private IntegrationRuntimeRoutes() {}

    public static boolean metadata(String method, String path) {
        return "GET".equals(method) && METADATA.matcher(path).matches();
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String path) {
        var match = METADATA.matcher(path);
        if (!"GET".equals(method) || !match.matches()) throw AuthorityProtocol.denied();
        var route = routes.require("gateway", "app", method, "/v1/apps/{code}/" + match.group(2));
        return new ServiceCall(route.target(), route.action(), application(match.group(1)));
    }

    public static ResourceBinding application(String app) {
        MetadataSelectors.code(app);
        return new ResourceBinding("integration-app", app, Digest.sha256("integration-app.v1:" + app));
    }

    public static boolean permits(ServiceCall call, String credentialApp) {
        return "app".equals(call.target()) && ACTIONS.containsValue(call.action())
                && application(credentialApp).equals(call.resource());
    }

    public static Jwt currentSource() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)
                || !"integration".equals(jwt.getClaimAsString("token_use"))
                || !IntegrationSource.TYPE.equals(jwt.getHeaders().get("typ"))) return null;
        return jwt;
    }
}
