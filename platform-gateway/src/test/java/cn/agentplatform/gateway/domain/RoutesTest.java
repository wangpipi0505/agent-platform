package cn.agentplatform.gateway.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RoutesTest {
    @Test
    void routesOnlyPersistentOnboardingSessionOperations() {
        for (String path : new String[] {
                "/admin/onboarding-sessions",
                "/admin/onboarding-sessions/onb_123",
                "/admin/onboarding-sessions/onb_123/preview",
                "/admin/onboarding-sessions/onb_123/steps/ASSEMBLE/execute",
                "/admin/onboarding-sessions/onb_123/steps/VERIFY/execute",
                "/admin/onboarding-sessions/onb_123/steps/PUBLISH/execute",
                "/admin/onboarding-sessions/onb_123/cancel"
        }) assertThat(Routes.service(path)).as(path).isEqualTo("app");
        for (String path : new String[] {
                "/admin/onboarding-sessions-extra",
                "/admin/onboarding-sessions/",
                "/admin/onboarding-sessions/onb_123/secret",
                "/admin/onboarding-sessions/onb_123/steps/UNKNOWN/execute",
                "/admin/onboarding-sessions/onb_123/steps/PUBLISH/execute/more",
                "/v1/onboarding-sessions"
        }) assertThatThrownBy(() -> Routes.service(path)).as(path)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesNamedInputsAndFrozenDatasetsWithoutExposingPromotionInternals() {
        assertThat(Routes.service("/admin/debug-inputs")).isEqualTo("engine");
        assertThat(Routes.service("/admin/debug-inputs/42/promote")).isEqualTo("engine");
        assertThat(Routes.service("/admin/evaluation-datasets")).isEqualTo("monitor");
        assertThat(Routes.service("/admin/evaluation-datasets/ops/regression_v1"))
                .isEqualTo("monitor");
        assertThatThrownBy(() -> Routes.service("/internal/datasets/promotions"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/admin/debug-inputs/42/secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesFormalRunsToEngineBeforeAppCrud() {
        assertThat(Routes.service("/admin/apps/weather/runs")).isEqualTo("engine");
        assertThat(Routes.service("/admin/apps/weather/config")).isEqualTo("app");
        assertThat(Routes.service("/admin/model-providers")).isEqualTo("model");
    }

    @Test
    void neverExposesInternalServicesByCatchAll() {
        assertThatThrownBy(() -> Routes.service("/internal/tool-calls/1/fact"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/anything"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesControlledArtifactStreamsToSkillBeforeGenericRuns() {
        assertThat(Routes.service("/admin/artifacts/abc/download")).isEqualTo("skill");
        assertThat(Routes.service("/v1/runs/r_1/artifacts/abc/download")).isEqualTo("skill");
    }

    @Test
    void routesOnlyPublishedInputContractToApplicationService() {
        assertThat(Routes.service("/v1/apps/ops/input-schema")).isEqualTo("app");
    }

    @Test
    void routesSessionAndReproductionOwnershipChecksToEngine() {
        assertThat(Routes.service("/v1/apps/ops/sessions")).isEqualTo("engine");
        assertThat(Routes.service("/v1/apps/ops/sessions/s_1")).isEqualTo("engine");
        assertThat(Routes.service("/admin/apps/ops/sessions/s_1/turns")).isEqualTo("engine");
        assertThat(Routes.service("/admin/reproductions/rp_1")).isEqualTo("engine");
        assertThat(Routes.service("/admin/apps/ops/versions/1")).isEqualTo("app");
        assertThatThrownBy(() -> Routes.service("/v1/apps/ops/sessions/s_1/secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesSessionEventControlAndStreamSubpathsToEngine() {
        // H1-9：会话事件增量/控制/SSE 子路径显式接线；其余未登记子路径仍拒绝。
        assertThat(Routes.service("/v1/apps/ops/sessions/s_1/events")).isEqualTo("engine");
        assertThat(Routes.service("/v1/apps/ops/sessions/s_1/events/stream")).isEqualTo("engine");
        assertThat(Routes.service("/v1/apps/ops/sessions/s_1/control")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/v1/apps/ops/sessions/s_1/events/other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/apps/ops/sessions/s_1/control/extra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesMemoryCrudToEngineWithUnknownSubpathsRejected() {
        // A3-4：记忆列表/单条/保存/更新/撤销五条入口指向 engine；未登记子路径仍拒绝。
        assertThat(Routes.service("/v1/apps/ops/memories")).isEqualTo("engine");
        assertThat(Routes.service("/v1/apps/ops/memories/m_abc123")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/v1/apps/ops/memories/m_abc123/extra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/apps/ops/memories/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesRuleAssetsToApplicationServiceOnBothPathFamilies() {
        // A3-3：应用侧目录走 /admin/apps 前缀；版本链与启停走独立 /admin/rules 前缀，都指向 app。
        for (String path : new String[] {
                "/admin/apps/weather/rules",
                "/admin/rules/no-direct-delete/draft",
                "/admin/rules/no-direct-delete/versions",
                "/admin/rules/no-direct-delete/versions/2/publish",
                "/admin/rules/no-direct-delete/enable",
                "/admin/rules/no-direct-delete/disable"
        }) assertThat(Routes.service(path)).as(path).isEqualTo("app");
    }

    @Test
    void routesWorkspaceManifestToApplicationService() {
        // A3-1：manifest 读/写与版本列表都在 /admin/apps 前缀下（既有前缀规则决定方向，
        // 权威目录补三条 delegate 能力行）；不放权，会话等特殊前缀仍按既有优先级分流。
        for (String path : new String[] {
                "/admin/apps/weather/manifest",
                "/admin/apps/weather/manifests"
        }) assertThat(Routes.service(path)).as(path).isEqualTo("app");
    }

    @Test
    void routesOnlyExactCapabilityPackageEndpointsToApplicationService() {
        for (String path : new String[] {
                "/admin/capability-packages",
                "/admin/capability-packages/import",
                "/admin/capability-packages/1",
                "/admin/capability-packages/42",
                "/admin/capability-packages/1/bindings/resolve",
                "/admin/capability-packages/42/bindings/resolve",
                "/admin/capability-packages/1/applications",
                "/admin/capability-packages/42/applications"
        }) {
            assertThatCode(() -> Routes.service(path)).as(path).doesNotThrowAnyException();
            assertThat(Routes.service(path)).as(path).isEqualTo("app");
        }
        for (String path : new String[] {
                "/admin/capability-packages-extra",
                "/admin/capability-packagesX",
                "/admin/capability-packages/",
                "/admin/capability-packages/import/1",
                "/admin/capability-packages/0",
                "/admin/capability-packages/01",
                "/admin/capability-packages/-1",
                "/admin/capability-packages/abc",
                "/admin/capability-packages/internal",
                "/admin/capability-packages/1/",
                "/admin/capability-packages/1/bindings",
                "/admin/capability-packages/1/bindings/resolve/more",
                "/admin/capability-packages/1/applications/2",
                "/admin/capability-packages/1/unknown",
                "/internal/capability-packages",
                "/v1/capability-packages"
        }) {
            assertThatThrownBy(() -> Routes.service(path)).as(path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
