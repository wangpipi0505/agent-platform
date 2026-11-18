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

    /** T2-7：子代理目录查询显式接线；/v1/sessions 其余子路径不存在（会话面在 apps 前缀下）。 */
    @Test
    void routesSubagentCatalogQueryToEngine() {
        assertThat(Routes.service("/v1/sessions/s_1/subagents")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/v1/sessions/s_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/sessions/s_1/secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/sessions/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesTaskEventIncrementAndStreamSubpathsToEngine() {
        // T2-2/T2-11：任务事件增量与 SSE 子路径显式接线；其余未登记任务子路径仍拒绝。
        assertThat(Routes.service("/v1/tasks/77/events")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks/77/events/stream")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/v1/tasks/77/events/other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/tasks/77/events/stream/extra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesTaskResultReadbackToEngine() {
        // T2-15：任务结果读取（SERVICE 四端点白名单读面）显式接线；未登记形状仍拒绝。
        assertThat(Routes.service("/v1/tasks/77/result")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/v1/tasks/77/results"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesTaskPlanAndExecuteEndpointsToEngineWithUnknownSubpathsRejected() {
        // R-9②：T2-3 计划面（plans 追加/修订读/版本级 confirm）与选定计划 execute 显式接线
        // （authority 目录行已有、网关方向此前缺登记致经网关 404——M5-2/M5-3 缺陷②收口）；
        // {taskId} 形状覆盖对外业务号（task-publicId，R-9⑤）与数字 id 兼容形态；
        // 形状相近但未登记的子路径仍拒绝。
        assertThat(Routes.service("/v1/tasks/77/plans")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks/task-abcdef0123456789abcd/plans")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks/77/plans/p1/revisions/2")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks/77/plans/p1/revisions/2/confirm")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks/77/execute")).isEqualTo("engine");
        for (String path : new String[] {
                "/v1/tasks/77/plans/p1",
                "/v1/tasks/77/plans/p1/revisions",
                "/v1/tasks/77/plans/p1/revisions/2/confirm/extra",
                "/v1/tasks/77/execute/extra",
                "/v1/tasks/77/executex",
                "/v1/tasks/77/plansx",
                "/v1/tasks/77/plans/p1/revisions/2/confirmtx"
        }) assertThatThrownBy(() -> Routes.service(path)).as(path)
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
    void routesPluginGovernanceToSkillService() {
        // A3-2：插件治理数据面（来源/发现/版本/授权）指向 skill；GUIDE 双轨沿用 /admin/skills 前缀，
        // 权威目录补九条 gateway→skill delegate 能力行；登记/触发/授权由 skill 服务按 ADMIN 校验。
        for (String path : new String[] {
                "/admin/plugin-sources",
                "/admin/plugin-sources/1",
                "/admin/plugin-sources/1/discover",
                "/admin/plugin-sources/1/discoveries/disc_0123456789abcdef0123456789abcdef",
                "/admin/plugins",
                "/admin/plugins/7/enable",
                "/admin/plugins/7/disable",
                "/admin/plugins/7/grants",
                "/admin/plugins/grants/3"
        }) assertThat(Routes.service(path)).as(path).isEqualTo("skill");
        // 拼写相近但未登记的入口不得被新前缀吞进去（/admin/plugins-x 与既有前缀规则同属
        // startsWith 语义，命中后由 skill 服务按精确模板 404，这里只断言未登记形状不入新方向）。
        assertThatThrownBy(() -> Routes.service("/admin/plugin-source"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/admin/plugin-discoveries"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesMcpConnectionRegistryToSkillService() {
        // A3-6：MCP 连接档案七条入口指向 skill（登记/配置变更/查询/测试/发现/目录差异/审核）；
        // 修订绑定与批次串行发布由 skill 服务按 ADMIN 核验；未登记形状不得被新前缀吞进去。
        for (String path : new String[] {
                "/admin/mcp-connections",
                "/admin/mcp-connections/db_conn",
                "/admin/mcp-connections/db_conn/test",
                "/admin/mcp-connections/db_conn/discover",
                "/admin/mcp-connections/db_conn/tools",
                "/admin/mcp-connections/db_conn/tools?diff=true",
                "/admin/mcp-connections/db_conn/tools/review"
        }) assertThat(Routes.service(path)).as(path).isEqualTo("skill");
        // 拼写相近但未登记的入口不得被新前缀吞进去（/admin/mcp-connection 单数不命中任何前缀）；
        // 前缀内的未登记子路径（如 /secret）由 skill 服务按精确模板 404，与 A3-2 startsWith 语义一致。
        assertThatThrownBy(() -> Routes.service("/admin/mcp-connection"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/admin/mcp-connectionX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesConfirmationCenterCountsAndTaskListToEngine() {
        // B4-1：确认中心角标（接口稿 §5.7）与任务列表（confirmableBy=me）显式接线到 engine；
        // 未登记子路径仍拒绝，不得被相近前缀吞进去。
        assertThat(Routes.service("/admin/confirmation-center/counts")).isEqualTo("engine");
        assertThat(Routes.service("/v1/tasks")).isEqualTo("engine");
        assertThatThrownBy(() -> Routes.service("/admin/confirmation-center/counts/extra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/admin/confirmation-center"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/admin/confirmation-centers/counts"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Routes.service("/v1/tasks/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routesPlanImportCanvasBridgeToEngineBeforeAppCrudPrefix() {
        // B4-1：计划导入草稿画布桥提前于 /admin/apps 配置面前缀指向 engine；未登记子路径不因
        // 新桥接改向（仍按既有 /admin/apps 前缀归 app，由 app 服务按精确模板 404）。
        assertThat(Routes.service("/admin/apps/weather/draft/plan-import")).isEqualTo("engine");
        assertThat(Routes.service("/admin/apps/weather/config")).isEqualTo("app");
        assertThat(Routes.service("/admin/apps/weather/draft/plan-import/extra")).isEqualTo("app");
        assertThat(Routes.service("/admin/apps/weather/draft/plan-import-x")).isEqualTo("app");
        assertThat(Routes.service("/admin/apps/weather/draft")).isEqualTo("app");
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
