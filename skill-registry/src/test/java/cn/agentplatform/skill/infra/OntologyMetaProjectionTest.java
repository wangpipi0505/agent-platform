package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.SkillRules;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SEM-05.0：本体元数据投影按 return_spec.ontologyMetaPath 声明提取。
 *
 * <p>O 侧正式信封固定为 {@code {code,data,meta}}，meta 是真实版本四元组；AP 旧实现硬编码读取
 * {@code ontologyMeta}，导致正式路径声明从未生效。本测试锁定四条边界：按声明路径提取、未声明保留
 * 旧字段行为、普通 HTTP 的根 meta 不被自动识别、声明路径错/字段不全/预览响应不得伪造实际版本。
 */
class OntologyMetaProjectionTest {
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final SkillAdapters adapters = new SkillAdapters(artifacts, null);

    private static Map<String, Object> formalMeta() {
        return Map.of(
                "spaceCode", "space_a",
                "releaseId", "11111111-2222-3333-4444-555555555555",
                "version", "v3",
                "contentDigest", "sha256:abc",
                "preview", false);
    }

    private static Map<String, Object> envelope(Object meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "OK");
        body.put("data", Map.of("columns", List.of(), "rows", List.of()));
        if (meta != null) body.put("meta", meta);
        return body;
    }

    private Map<String, Object> contract(String ontologyMetaPath) {
        return contract(ontologyMetaPath, 1048576);
    }

    private Map<String, Object> contract(String ontologyMetaPath, int outputMaxBytes) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("structuredPath", "$.data");
        if (ontologyMetaPath != null) spec.put("ontologyMetaPath", ontologyMetaPath);
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("outputMaxBytes", outputMaxBytes);
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put("returnSpec", spec);
        return contract;
    }

    private static ByteArrayInputStream input(Object output) {
        return new ByteArrayInputStream(Json.write(output).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 按声明的根meta路径提取正式四元组() throws IOException {
        // 同时存在同名字段 ontologyMeta：声明路径必须优先，不能被旧硬编码带回伪造版本。
        Map<String, Object> body = new LinkedHashMap<>(envelope(formalMeta()));
        body.put(
                "ontologyMeta",
                Map.of(
                        "spaceCode", "fake",
                        "releaseId", "fake",
                        "version", "v0",
                        "contentDigest", "fake"));

        var raw = adapters.read(input(body), contract("$.meta"), 7L);

        assertEquals("space_a", raw.ontologyMeta().path("spaceCode").asText());
        assertEquals(
                "11111111-2222-3333-4444-555555555555",
                raw.ontologyMeta().path("releaseId").asText());
        assertEquals("v3", raw.ontologyMeta().path("version").asText());
        assertEquals("sha256:abc", raw.ontologyMeta().path("contentDigest").asText());
        assertFalse(raw.ontologyMeta().path("preview").asBoolean());
    }

    @Test
    void 未声明时保留存量ontologyMeta字段行为() throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(envelope(formalMeta()));
        body.put(
                "ontologyMeta",
                Map.of(
                        "spaceCode", "legacy",
                        "releaseId", "r_legacy",
                        "version", "v1",
                        "contentDigest", "digest"));

        var raw = adapters.read(input(body), contract(null), 7L);

        assertEquals("legacy", raw.ontologyMeta().path("spaceCode").asText());
        assertEquals("r_legacy", raw.ontologyMeta().path("releaseId").asText());
    }

    @Test
    void 普通HTTP响应的根meta不被自动识别为本体元数据() throws IOException {
        var raw = adapters.read(input(envelope(formalMeta())), contract(null), 7L);

        assertFalse(raw.ontologyMeta().isObject(), "未声明 ontologyMetaPath 时根 meta 不是本体元数据");
    }

    @Test
    void 声明路径不存在时按OUTPUT_INVALID失败而不是回退到根meta或旧字段() {
        Map<String, Object> body = new LinkedHashMap<>(envelope(null));
        body.put(
                "ontologyMeta",
                Map.of(
                        "spaceCode", "fake",
                        "releaseId", "fake",
                        "version", "v0",
                        "contentDigest", "fake"));

        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.read(input(body), contract("$.meta"), 7L));

        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertTrue(failure.getMessage().contains("ontologyMetaPath"));
    }

    @Test
    void 声明路径指向非对象时失败() {
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.read(input(envelope("OK")), contract("$.meta"), 7L));

        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertTrue(failure.getMessage().contains("ontologyMetaPath"));
    }

    @Test
    void 声明路径的四元组不全时失败而不投影半截版本() {
        Map<String, Object> partial = new LinkedHashMap<>(formalMeta());
        partial.remove("contentDigest");

        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.read(input(envelope(partial)), contract("$.meta"), 7L));

        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertTrue(failure.getMessage().contains("contentDigest"));
    }

    @Test
    void 候选预览响应不得采集为实际版本() {
        Map<String, Object> preview = new LinkedHashMap<>(formalMeta());
        preview.put("preview", true);

        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.read(input(envelope(preview)), contract("$.meta"), 7L));

        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertTrue(failure.getMessage().contains("preview"));
    }

    @Test
    void 大响应转存不得伪称已采集元数据() throws IOException {
        when(artifacts.offload(any(), any(), any(), anyLong(), any()))
                .thenReturn(Map.of("artifactId", "art_1"));

        var raw =
                adapters.read(
                        input(envelope(formalMeta())), contract("$.meta", 64), 7L, null, 1L, 0);

        assertTrue(raw.ontologyMeta().isNull(), "转存只给受控文件引用，不能宣称四元组已采集");
    }

    @Test
    void 注册期接受受限根meta路径并拒绝越界声明() {
        SkillRules.validateReturnSpec(Map.of("ontologyMetaPath", "$.meta"));
        SkillRules.validateReturnSpec(Map.of("ontologyMetaPath", "$.payload.meta[0].release"));

        for (Object bad : List.of("", "  ", "$.meta[*]", "meta", "$.meta.", 7, Map.of())) {
            var ex =
                    assertThrows(
                            PlatformException.class,
                            () -> SkillRules.validateReturnSpec(Map.of("ontologyMetaPath", bad)),
                            "应拒绝: " + bad);
            assertEquals(400, ex.status());
            assertTrue(ex.getMessage().contains("ontologyMetaPath"), ex.getMessage());
        }
    }
}
