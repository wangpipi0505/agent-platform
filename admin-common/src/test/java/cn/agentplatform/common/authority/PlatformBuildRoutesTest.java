package cn.agentplatform.common.authority;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBuildRoutesTest {
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
