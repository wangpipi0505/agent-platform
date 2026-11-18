package cn.agentplatform.skill.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.application.PluginDiscoveryExecutor;
import cn.agentplatform.skill.application.PluginGovernance;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

/** 插件治理 API 骨架的 HTTP 面：统一回包、发现受理 202、错误码透传（接口稿 §5.6）。 */
class PluginGovernanceControllerTest {
    PluginGovernance governance = mock(PluginGovernance.class);
    PluginDiscoveryExecutor discoveryExecutor = mock(PluginDiscoveryExecutor.class);
    MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new PluginGovernanceController(governance, discoveryExecutor))
                    .setControllerAdvice(new cn.agentplatform.common.PlatformErrors())
                    .build();

    @Test
    void discoveryTriggerReturnsAcceptedWithQueuedProjection() throws Exception {
        // R-6 接线：触发改走执行器 trigger（受理+派发），202 契约不变。
        when(discoveryExecutor.trigger(7L))
                .thenReturn(Map.of("discoveryId", "disc_" + "0".repeat(32), "status", "QUEUED", "batchNo", 3L));
        mvc.perform(post("/admin/plugin-sources/7/discover"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.batchNo").value(3));
    }

    @Test
    void sourceAndGrantEndpointsUseCommonEnvelope() throws Exception {
        when(governance.createSource(anyMap()))
                .thenReturn(Map.of("id", 1L, "code", "src", "status", "ACTIVE"));
        when(governance.grant(eq(9L), anyMap()))
                .thenReturn(Map.of("grantId", 5L, "status", "GRANTED"));
        when(governance.revoke(eq("5"), any()))
                .thenReturn(Map.of("grantId", 5L, "status", "REVOKED"));
        when(governance.listPlugins(isNull(), isNull())).thenReturn(List.of());
        mvc.perform(
                        post("/admin/plugin-sources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"code\":\"src\",\"name\":\"n\",\"sourceType\":\"DIRECTORY\","
                                                + "\"location\":\"file:///srv\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/admin/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(
                        post("/admin/plugins/9/grants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"granteeType\":\"WORKSPACE\",\"granteeCode\":\"app_1\","
                                                + "\"capabilities\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grantId").value(5));
        mvc.perform(delete("/admin/plugins/grants/5").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }

    @Test
    void domainErrorsMapThroughPlatformAdvice() throws Exception {
        when(discoveryExecutor.trigger(404L))
                .thenThrow(new PlatformException(404, "PLUGIN_SOURCE_NOT_FOUND", "插件来源不存在"));
        when(governance.enableVersion(9L))
                .thenThrow(new PlatformException(409, "PLUGIN_STATE", "REVOKED 为终态，不能启用"));
        mvc.perform(post("/admin/plugin-sources/404/discover"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLUGIN_SOURCE_NOT_FOUND"));
        mvc.perform(post("/admin/plugins/9/enable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLUGIN_STATE"));
    }
}
