package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact portal build requests currently served by app. */
public final class PlatformBuildRoutes {
    public static final String SESSION = "/internal/platform-authorization/session";
    private static final String CODE = "[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final List<Rule> RULES = List.of(
            rule("GET,POST", "/admin/apps", "/admin/apps"),
            rule("GET,PUT,DELETE", "/admin/apps/" + CODE, "/admin/apps/{code}"),
            rule("PUT", "/admin/apps/" + CODE + "/config", "/admin/apps/{code}/config"),
            rule("POST", "/admin/apps/" + CODE + "/copy", "/admin/apps/{code}/copy"),
            rule("GET", "/admin/apps/" + CODE + "/lifecycle-logs", "/admin/apps/{code}/lifecycle-logs"),
            rule("GET", "/admin/apps/" + CODE + "/pin-status", "/admin/apps/{code}/pin-status"),
            rule("GET", "/admin/apps/" + CODE + "/versions", "/admin/apps/{code}/versions"),
            rule("GET", "/admin/apps/" + CODE + "/versions/diff", "/admin/apps/{code}/versions/diff"),
            rule("GET", "/admin/apps/" + CODE + "/versions/[1-9][0-9]*", "/admin/apps/{code}/versions/{versionNo}"),
            rule("POST", "/admin/apps/" + CODE + "/publish/precheck", "/admin/apps/{code}/publish/precheck"),
            rule("POST", "/admin/apps/" + CODE + "/publish/commit", "/admin/apps/{code}/publish/commit"),
            rule("POST", "/admin/apps/" + CODE + "/(?:disable|enable|archive|rollback)",
                    "/admin/apps/{code}/{action:disable|enable|archive|rollback}"),
            rule("GET,PUT", "/admin/flows/" + CODE, "/admin/flows/{code}"),
            rule("GET,POST", "/admin/classifications", "/admin/classifications"),
            rule("GET,POST", "/admin/prompt-templates", "/admin/prompt-templates"),
            rule("PUT", "/admin/prompt-templates/" + CODE, "/admin/prompt-templates/{code}"),
            rule("GET", "/admin/prompt-templates/" + CODE + "/versions",
                    "/admin/prompt-templates/{code}/versions"),
            rule("POST", "/admin/prompt-templates/" + CODE + "/versions/[1-9][0-9]*/publish",
                    "/admin/prompt-templates/{code}/versions/{version}/publish"),
            rule("GET", "/admin/platform-authorization/users", "/admin/platform-authorization/users"),
            rule("GET,PUT,DELETE", "/admin/platform-authorization/grants", "/admin/platform-authorization/grants"),
            rule("GET", "/admin/platform-authorization/audit", "/admin/platform-authorization/audit"),
            rule("GET", "/admin/platform-authorization/configuration-audit",
                    "/admin/platform-authorization/configuration-audit"));

    private PlatformBuildRoutes() {}

    private record Rule(Set<String> methods, Pattern path, String template) {}

    private static Rule rule(String methods, String path, String template) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile(path), template);
    }

    public static String template(String method, String path) {
        for (var rule : RULES) {
            if (rule.methods().contains(method) && rule.path().matcher(path).matches()) return rule.template();
        }
        return null;
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String template, SubjectKey subject) {
        var route = routes.require("gateway", "app", method, template);
        return new ServiceCall(route.target(), route.action(), resource(subject));
    }

    public static ResourceBinding resource(SubjectKey subject) {
        String identity = Json.canonical(List.of(subject.tenantId(), subject.principalId()));
        return new ResourceBinding("platform-build", Digest.sha256(identity),
                Digest.sha256("platform-build.v1:" + identity));
    }
}
