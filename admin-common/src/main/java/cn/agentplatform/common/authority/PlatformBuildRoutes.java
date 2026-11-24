package cn.agentplatform.common.authority;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact portal build requests and their owning services. */
public final class PlatformBuildRoutes {
    public static final String SESSION = "/internal/platform-authorization/session";
    public static final String RUN_AUDIT = "/internal/audit";
    public static final String AUDIT_TEMPLATE = "/admin/{kind:approvals|audit}";
    private static final String CODE = "[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final String SKILL_CODE = "(?!(?:fixture|semantic-catalog|import-openapi|import-ontology-manifests)(?:/|$))" + CODE;
    private static final String NUMBER = "[1-9][0-9]*";
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
                    "/admin/platform-authorization/configuration-audit"),
            new Rule(Set.of("GET"), Pattern.compile("/admin/audit"), AUDIT_TEMPLATE, "monitor", "audit.read"),
            model("GET,POST", "/admin/model-providers", "/admin/model-providers"),
            model("PUT", "/admin/model-providers/[1-9][0-9]*", "/admin/model-providers/{id}"),
            model("POST", "/admin/model-providers/[1-9][0-9]*/test", "/admin/model-providers/{id}/test"),
            model("POST", "/admin/model-providers/[1-9][0-9]*/disable", "/admin/model-providers/{id}/disable"),
            model("POST", "/admin/model-providers/[1-9][0-9]*/credentials/rotate",
                    "/admin/model-providers/{id}/credentials/rotate"),
            model("PUT", "/admin/model-providers/[1-9][0-9]*/quota", "/admin/model-providers/{id}/quota"),
            model("GET,POST", "/admin/model-entries", "/admin/model-entries"),
            model("PUT", "/admin/model-entries/[1-9][0-9]*", "/admin/model-entries/{id}"),
            model("POST", "/admin/model-entries/[1-9][0-9]*/test", "/admin/model-entries/{id}/test"),
            model("GET", "/admin/model-entries/[1-9][0-9]*/metering", "/admin/model-entries/{id}/metering"),
            skill("GET,POST", "/admin/skills", "/admin/skills"),
            skill("GET", "/admin/skills/semantic-catalog", "/admin/skills/semantic-catalog"),
            skill("POST", "/admin/skills/import-openapi", "/admin/skills/import-openapi"),
            skill("POST", "/admin/skills/import-openapi/preview", "/admin/skills/import-openapi/preview"),
            skill("POST", "/admin/skills/import-ontology-manifests", "/admin/skills/import-ontology-manifests"),
            skill("GET,PUT,DELETE", "/admin/skills/" + SKILL_CODE, "/admin/skills/{code}"),
            skill("PUT", "/admin/skills/" + SKILL_CODE + "/draft", "/admin/skills/{code}/draft"),
            skill("GET", "/admin/skills/" + SKILL_CODE + "/versions", "/admin/skills/{code}/versions"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/versions/" + NUMBER + "/publish",
                    "/admin/skills/{code}/versions/{version}/publish"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/versions/" + NUMBER + "/service-probe",
                    "/admin/skills/{code}/versions/{version}/service-probe"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/versions/" + NUMBER + "/rebind-evidence",
                    "/admin/skills/{code}/versions/{version}/rebind-evidence"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/drafts/" + NUMBER + "/publish",
                    "/admin/skills/{code}/drafts/{version}/publish"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/drafts/" + NUMBER + "/connection-test",
                    "/admin/skills/{code}/drafts/{version}/connection-test"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/drafts/" + NUMBER + "/acceptance",
                    "/admin/skills/{code}/drafts/{version}/acceptance"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/drafts/" + NUMBER + "/mcp-tools",
                    "/admin/skills/{code}/drafts/{version}/mcp-tools"),
            skill("POST", "/admin/skills/" + SKILL_CODE + "/disable", "/admin/skills/{code}/disable"),
            skill("GET", "/admin/skills/" + SKILL_CODE + "/referenced-by", "/admin/skills/{code}/referenced-by"),
            skill("GET", "/admin/skills/" + SKILL_CODE + "/stats", "/admin/skills/{code}/stats"),
            plugin("GET,POST", "/admin/plugin-sources", "/admin/plugin-sources"),
            plugin("POST", "/admin/plugin-sources/" + NUMBER + "/discover", "/admin/plugin-sources/{id}/discover"),
            plugin("GET", "/admin/plugin-sources/" + NUMBER + "/discoveries/disc_[0-9a-f]{32}",
                    "/admin/plugin-sources/{id}/discoveries/{discoveryId}"),
            plugin("GET", "/admin/plugins", "/admin/plugins"),
            plugin("POST", "/admin/plugins/" + NUMBER + "/enable", "/admin/plugins/{versionId}/enable"),
            plugin("POST", "/admin/plugins/" + NUMBER + "/disable", "/admin/plugins/{versionId}/disable"),
            plugin("POST", "/admin/plugins/" + NUMBER + "/grants", "/admin/plugins/{versionId}/grants"),
            plugin("DELETE", "/admin/plugins/grants/" + NUMBER, "/admin/plugins/grants/{grantId}"),
            plugin("POST", "/admin/mcp-connections", "/admin/mcp-connections"),
            plugin("GET,PUT", "/admin/mcp-connections/" + CODE, "/admin/mcp-connections/{id}"),
            plugin("POST", "/admin/mcp-connections/" + CODE + "/test", "/admin/mcp-connections/{id}/test"),
            plugin("POST", "/admin/mcp-connections/" + CODE + "/discover", "/admin/mcp-connections/{id}/discover"),
            plugin("GET", "/admin/mcp-connections/" + CODE + "/tools", "/admin/mcp-connections/{id}/tools"),
            plugin("POST", "/admin/mcp-connections/" + CODE + "/tools/review", "/admin/mcp-connections/{id}/tools/review"));

    private PlatformBuildRoutes() {}

    private record Rule(Set<String> methods, Pattern path, String template, String target, String capability) {}

    private static Rule rule(String methods, String path, String template) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile(path), template, "app", null);
    }

    private static Rule model(String methods, String path, String template) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile(path), template, "model", "model.manage");
    }

    private static Rule skill(String methods, String path, String template) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile(path), template, "skill", "skill.manage");
    }

    private static Rule plugin(String methods, String path, String template) {
        return new Rule(Set.of(methods.split(",")), Pattern.compile(path), template, "skill", "plugin.manage");
    }

    public static String template(String method, String path) {
        for (var rule : RULES) {
            if (rule.methods().contains(method) && rule.path().matcher(path).matches()) return rule.template();
        }
        return null;
    }

    public static ServiceCall call(ServiceRoutePolicy routes, String method, String template, SubjectKey subject) {
        String target = SESSION.equals(template) ? "app" : ruleFor(method, template).target();
        var route = routes.require("gateway", target, method, template);
        return new ServiceCall(route.target(), route.action(), resource(subject));
    }

    static ServiceCall sessionCall(ServiceRoutePolicy routes, String caller, SubjectKey subject) {
        var route = routes.require(caller, "app", "GET", SESSION);
        return new ServiceCall(route.target(), route.action(), resource(subject));
    }

    public static ServiceCall runAudit(ServiceRoutePolicy routes, SubjectKey subject) {
        var route = routes.require("monitor", "engine", "GET", RUN_AUDIT);
        return new ServiceCall(route.target(), route.action(), resource(subject));
    }

    public static boolean serves(String service, String method, String path) {
        String template = template(method, path);
        return template != null && service.equals(ruleFor(method, template).target());
    }

    public static String requiredCapability(String method, String template) {
        return ruleFor(method, template).capability();
    }

    private static Rule ruleFor(String method, String template) {
        return RULES.stream().filter(rule -> rule.methods().contains(method) && rule.template().equals(template))
                .findFirst().orElseThrow(AuthorityProtocol::denied);
    }

    public static ResourceBinding resource(SubjectKey subject) {
        String identity = Json.canonical(List.of(subject.tenantId(), subject.principalId()));
        return new ResourceBinding("platform-build", Digest.sha256(identity),
                Digest.sha256("platform-build.v1:" + identity));
    }
}
