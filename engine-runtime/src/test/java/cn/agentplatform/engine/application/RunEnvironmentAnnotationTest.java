package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

/**
 * PLAT-20 T20.1（run 环境标注半段）：
 *
 * <ul>
 *   <li>environment 取值与 /health/data（DataHealthService）同源：AGENT_DEMO_MODE=true → DEMO，
 *       否则 REAL；无演示标记的环境缺省语义定为 REAL（新运行一律落标注，旧记录不回填）。
 *   <li>dataset 引用只在运行入参携带 datasetVersion 时透传进快照，缺失不落键、不伪造。
 *   <li>标注随冻结快照持久化（与 T13 的 snapshot.contextPack 同层级，不另造并行结构），
 *       运行详情与 RUN_STARTED 事件载荷都从快照读取；旧运行无 runtime 对象时整体缺省、不报错。
 * </ul>
 */
class RunEnvironmentAnnotationTest {
    private static ObjectNode snapshot() {
        var s = Json.object();
        s.put("appCode", "app_a");
        s.put("type", "FLOW");
        s.set("config", Json.object());
        s.set("definition", Json.read("{\"nodes\":[]}"));
        return s;
    }

    /** 模拟带本体审计的快照：标注不得触碰既有键（T13 冻结结果保持不变）。 */
    private static ObjectNode snapshotWithContextPack() {
        var s = snapshot();
        var audit = s.putObject("contextPack");
        audit.put("space", "11111111-1111-1111-1111-111111111111");
        audit.put("releaseId", "22222222-2222-2222-2222-222222222222");
        audit.put("environment", "production");
        return s;
    }

    private static boolean demoModeOf(RunService service) throws Exception {
        Field field = RunService.class.getDeclaredField("demoMode");
        field.setAccessible(true);
        return field.getBoolean(service);
    }

    // === ① 演示模式：AGENT_DEMO_MODE=true → environment=DEMO ================================

    @Test
    void demoRunSnapshotCarriesEnvironmentDemo() {
        var s = snapshot();
        RunService.annotateRuntime(s, true, Json.object());
        var runtime = s.path("runtime");
        assertTrue(runtime.isObject(), "演示运行快照必须带 runtime 标注");
        assertEquals("DEMO", runtime.path("environment").asText());
        assertTrue(runtime.path("demoMode").asBoolean());
        assertFalse(runtime.has("datasetVersion"), "未携带 dataset 引用时不落键");
    }

    // === ② 非演示缺省语义：无演示标记 → REAL（与 /health/data 对齐，写明） ===================

    @Test
    void nonDemoRunDefaultsToReal() {
        var s = snapshot();
        RunService.annotateRuntime(s, false, Json.object());
        var runtime = s.path("runtime");
        assertEquals("REAL", runtime.path("environment").asText());
        assertFalse(runtime.path("demoMode").asBoolean());
    }

    @Test
    void demoModeSetterParsesAgentDemoModeString() throws Exception {
        var service = new RunService(null, null, null, null);
        assertFalse(demoModeOf(service), "缺省（未注入）必须等价 REAL");
        service.demoMode("true");
        assertTrue(demoModeOf(service));
        service.demoMode(" true ");
        assertTrue(demoModeOf(service), "环境变量值两侧空白必须容忍");
        service.demoMode("false");
        assertFalse(demoModeOf(service));
    }

    // === ⑤ dataset 引用：入参带 datasetVersion 才透传，缺失不落键 ===========================

    @Test
    void datasetVersionIsPassedThroughVerbatim() {
        var s = snapshot();
        var req = Json.object();
        req.put("datasetVersion", "v7");
        RunService.annotateRuntime(s, false, req);
        assertEquals("v7", s.path("runtime").path("datasetVersion").asText());
    }

    @Test
    void blankDatasetReferenceLeavesNoKey() {
        var s = snapshot();
        var req = Json.object();
        req.put("datasetVersion", "  ");
        RunService.annotateRuntime(s, false, req);
        assertFalse(s.path("runtime").has("datasetVersion"), "空白引用等价未携带，不伪造");
    }

    @Test
    void annotationPreservesExistingSnapshotKeys() {
        var s = snapshotWithContextPack();
        RunService.annotateRuntime(s, false, Json.object());
        assertEquals("app_a", s.path("appCode").asText());
        assertEquals(
                "22222222-2222-2222-2222-222222222222",
                s.path("contextPack").path("releaseId").asText());
        assertEquals(
                "production", s.path("contextPack").path("environment").asText(),
                "contextPack.environment 是发布绑定环境，与运行标注互不覆盖");
        assertTrue(s.path("runtime").isObject());
    }

    // === ④ 运行详情可见：标注铺到详情顶层 ====================================================

    @Test
    void runDetailSurfacesAnnotationAtTopLevel() {
        var s = snapshot();
        RunService.annotateRuntime(s, true, Json.read("{\"datasetVersion\":\"v2\"}"));
        var detail = new LinkedHashMap<String, Object>();
        RunService.surfaceRuntimeAnnotation(detail, s);
        assertEquals("DEMO", detail.get("environment"));
        assertEquals(true, detail.get("demoMode"));
        assertEquals("v2", detail.get("datasetVersion"));
    }

    // === ③ 旧 run（无标注）读取不报错、不补键 ================================================

    @Test
    void legacyRunWithoutAnnotationReadsCleanly() {
        var detail = new LinkedHashMap<String, Object>();
        RunService.surfaceRuntimeAnnotation(detail, Json.read("{\"appCode\":\"old\"}"));
        assertFalse(detail.containsKey("environment"));
        assertFalse(detail.containsKey("demoMode"));
        assertFalse(detail.containsKey("datasetVersion"));
    }

    @Test
    void partialAnnotationSurfacesOnlyPresentKeys() {
        var runtime = Json.object();
        runtime.put("environment", "REAL");
        var s = snapshot();
        s.set("runtime", runtime);
        var detail = new LinkedHashMap<String, Object>();
        RunService.surfaceRuntimeAnnotation(detail, s);
        assertEquals("REAL", detail.get("environment"));
        assertFalse(detail.containsKey("demoMode"));
        assertFalse(detail.containsKey("datasetVersion"));
    }

    // === ④b SSE 可见：RUN_STARTED 载荷携带同一标注（旧运行不落键） ===========================

    @Test
    void runStartedPayloadCarriesAnnotation() {
        var s = snapshot();
        RunService.annotateRuntime(s, false, Json.read("{\"datasetVersion\":\"v1\"}"));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("runId", "r_x");
        payload.put("triggerType", "EVALUATION");
        TraceStore.putRuntimeAnnotation(payload, s);
        assertEquals("REAL", payload.get("environment"));
        assertEquals("v1", payload.get("datasetVersion"));
        assertFalse(payload.containsKey("demoMode"), "SSE 载荷只带 environment/datasetVersion 两个事实");
    }

    @Test
    void runStartedPayloadWithoutAnnotationOmitsKeys() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("runId", "r_x");
        payload.put("triggerType", "TASK");
        TraceStore.putRuntimeAnnotation(payload, Json.read("{}"));
        assertFalse(payload.containsKey("environment"));
        assertFalse(payload.containsKey("datasetVersion"));
    }
}
