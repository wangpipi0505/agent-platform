package cn.agentplatform.gateway.domain;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RoutesTest {
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
}
