package cn.agentplatform.common.authority;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBuildRoutesTest {
    @Test
    void workspaceReadsUseRegisteredApplicationRoutes() {
        var routes = new ServiceRoutePolicy();
        var subject = new SubjectKey("tenant", "editor");
        assertThat(PlatformBuildRoutes.template("GET", "/admin/apps/example/manifest"))
                .isEqualTo("/admin/apps/{code}/manifest");
        assertThat(PlatformBuildRoutes.template("GET", "/admin/apps/example/manifests"))
                .isEqualTo("/admin/apps/{code}/manifests");
        var rules = PlatformBuildRoutes.template("GET", "/admin/apps/example/rules");
        assertThat(rules).isEqualTo("/admin/apps/{code}/rules");
        assertThat(PlatformBuildRoutes.call(routes, "GET", rules, subject).target()).isEqualTo("app");
        assertThat(PlatformBuildRoutes.template("GET", "/admin/apps/example/manifest/extra")).isNull();
        assertThat(PlatformBuildRoutes.template("POST", "/admin/apps/example/rules")).isNull();
    }

    @Test
    void integrationCredentialManagementStaysOnTheAppBuildSurface() {
        var issue = PlatformBuildRoutes.template("POST", "/admin/apps/example/integration/credentials");
        assertThat(issue).isEqualTo("/admin/apps/{code}/integration/credentials");
        assertThat(PlatformBuildRoutes.call(new ServiceRoutePolicy(), "POST", issue,
                new SubjectKey("a", "user")).target()).isEqualTo("app");
        assertThat(PlatformBuildRoutes.template("POST", "/v1/apps/example/integration/credentials")).isNull();
    }

    @Test
    void integrationMetadataUsesASeparateCredentialBoundRoute() {
        assertThat(IntegrationRuntimeRoutes.metadata("GET", "/v1/apps/example/input-schema")).isTrue();
        assertThat(IntegrationRuntimeRoutes.metadata("GET", "/admin/apps/example/input-schema")).isFalse();
        assertThat(IntegrationRuntimeRoutes.metadata("POST", "/v1/apps/example/input-schema")).isFalse();
        var call = IntegrationRuntimeRoutes.call(new ServiceRoutePolicy(), "GET", "/v1/apps/example/input-schema");
        assertThat(call.resource()).isEqualTo(IntegrationRuntimeRoutes.application("example"));
    }

    @Test
    void auditCapabilityDoesNotOpenApprovals() {
        var template = PlatformBuildRoutes.template("GET", "/admin/audit");
        assertThat(template).isEqualTo("/admin/{kind:approvals|audit}");
        assertThat(PlatformBuildRoutes.requiredCapability("GET", template)).isEqualTo("audit.read");
        assertThat(PlatformBuildRoutes.call(new ServiceRoutePolicy(), "GET", template,
                new SubjectKey("a", "user")).target()).isEqualTo("monitor");
        assertThat(PlatformBuildRoutes.template("GET", "/admin/approvals")).isNull();
    }

    @Test
    void skillAndPluginManagementUseExactPortalRoutes() {
        assertRoute("GET", "/admin/skills/semantic-catalog", "/admin/skills/semantic-catalog", "skill.manage");
        assertRoute("PUT", "/admin/skills/weather/draft", "/admin/skills/{code}/draft", "skill.manage");
        assertRoute("POST", "/admin/skills/weather/versions/1/service-probe",
                "/admin/skills/{code}/versions/{version}/service-probe", "skill.manage");
        assertRoute("POST", "/admin/plugins/7/grants", "/admin/plugins/{versionId}/grants", "plugin.manage");
        assertRoute("POST", "/admin/mcp-connections/prod/tools/review",
                "/admin/mcp-connections/{id}/tools/review", "plugin.manage");
        assertThat(PlatformBuildRoutes.template("DELETE", "/admin/skills/fixture")).isNull();
        assertThat(PlatformBuildRoutes.template("POST", "/admin/skills/fixture/purge")).isNull();
        assertThat(PlatformBuildRoutes.template("POST", "/admin/plugins/0/grants")).isNull();
    }

    private static void assertRoute(String method, String path, String template, String capability) {
        assertThat(PlatformBuildRoutes.template(method, path)).isEqualTo(template);
        assertThat(PlatformBuildRoutes.requiredCapability(method, template)).isEqualTo(capability);
        var call = PlatformBuildRoutes.call(new ServiceRoutePolicy(), method, template, new SubjectKey("a", "user"));
        assertThat(call.target()).isEqualTo("skill");
    }
}
