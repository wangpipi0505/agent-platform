package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/** OR-S01 manifest导入的失败路径与字段映射；catalog以mock替换，不依赖PG。 */
class OntologyManifestImportsTest {
    SkillCatalog catalog = mock(SkillCatalog.class);
    OntologyManifestImports imports = new OntologyManifestImports(catalog);

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", "ontology-import-tests")
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 按ContractGenerator.formalize产出形状构造正式manifest。 */
    private Map<String, Object> formalManifest(String toolCode) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("spaceCode", "route3");
        meta.put("releaseId", "9f8e7d6c-1111-2222-3333-444455556666");
        meta.put("version", "v3");
        meta.put("contentDigest", "a".repeat(64));
        var exec = new LinkedHashMap<String, Object>();
        exec.put("readonly_flag", true);
        exec.put("side_effect", false);
        exec.put("idempotent", true);
        exec.put("cancellable", false);
        exec.put("timeout_ms", 30000);
        exec.put("max_rows", 1000);
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("tool_code", toolCode);
        manifest.put("name", "对象查询");
        manifest.put("description", "查询冻结模型");
        manifest.put("invocation_kind", "SYNC");
        manifest.put("adapter_type", "HTTP");
        manifest.put("category", "ontology_service");
        manifest.put(
                "endpoint",
                "https://onto.example.com/onto/r/route3/releases/9f8e7d6c-1111-2222-3333-444455556666/ops/"
                        + toolCode);
        manifest.put("http_method", "POST");
        manifest.put(
                "input_schema",
                Map.of("type", "object", "properties", Map.of("region", Map.of("type", "string"))));
        manifest.put(
                "output_schema",
                Map.of(
                        "type", "object",
                        "required", List.of("code", "data", "meta"),
                        "properties",
                                Map.of(
                                        "code", Map.of("const", "OK"),
                                        "data", Map.of("type", "object"),
                                        "meta", Map.of("type", "object"))));
        manifest.put("guard_attrs", List.of("region"));
        manifest.put("exec_attrs", exec);
        manifest.put("risk_level", "READ_ONLY");
        manifest.put("error_semantics", List.of(Map.of("match", "HTTP 403", "semantic", "NO_PERMISSION")));
        manifest.put(
                "return_spec",
                Map.of(
                        "modelSummary", Map.of("mode", "TEMPLATE", "template", "查询结果"),
                        "structuredPath", "$.data",
                        "fileRefPaths", List.of(),
                        "cardTemplate", "查询结果",
                        "ontologyMetaPath", "$.meta"));
        manifest.put("ontology_meta", meta);
        return manifest;
    }

    private PlatformException invalid(Map<String, Object> body) {
        return assertThrows(PlatformException.class, () -> imports.importManifests(body));
    }

    @Test
    void 正式manifest完整映射为技能契约() {
        var drafts = new ArrayList<Map<String, Object>>();
        drafts.add(Map.of("code", "user_query", "status", "DRAFT"));
        when(catalog.create(any())).thenReturn(drafts.getFirst());
        var result =
                imports.importManifests(
                        Map.of("manifests", List.of(formalManifest("user_query")), "authRef", "vault:onto-release"));
        assertEquals(1, result.get("drafts") instanceof List<?> d ? d.size() : 0);
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(catalog).create(captor.capture());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) captor.getValue();
        assertEquals("user_query", body.get("code"));
        assertEquals("HTTP", body.get("type"));
        assertEquals("POST", body.get("httpMethod"));
        assertEquals(30000L, body.get("timeoutMs"));
        var execution = (Map<?, ?>) body.get("execution");
        assertEquals(true, execution.get("readonly"));
        assertEquals(false, execution.get("sideEffect"));
        // riskLevel不从manifest映射：平台按readonly旗标确定性推导
        assertFalse(body.containsKey("riskLevel"));
        var guard = (Map<?, ?>) body.get("guard");
        assertEquals(List.of("region"), guard.get("guardAttrs"));
        assertTrue(((List<?>) guard.get("roles")).isEmpty());
        var importSource = (Map<?, ?>) body.get("importSource");
        assertEquals("ONTOLOGY", importSource.get("kind"));
        assertEquals("route3", importSource.get("spaceCode"));
        assertEquals("v3", importSource.get("version"));
        assertEquals("vault:onto-release", body.get("authRef"));
    }

    @Test
    void preview与缺releaseId的manifest拒绝() {
        var preview = formalManifest("user_query");
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) preview.get("ontology_meta");
        meta.put("preview", true);
        var ex = invalid(Map.of("manifests", List.of(preview)));
        assertTrue(ex.getMessage().contains("正式"));
    }

    @Test
    void 非发布路由endpoint拒绝() {
        var manifest = formalManifest("user_query");
        manifest.put("endpoint", "https://onto.example.com/onto/internal/candidates/abc/ops/user_query");
        var ex = invalid(Map.of("manifests", List.of(manifest)));
        assertTrue(ex.getMessage().contains("发布消费路由"));
    }

    @Test
    void 非HTTP或非同步或非POST的manifest拒绝() {
        var adapter = formalManifest("user_query");
        adapter.put("adapter_type", "MCP");
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of(adapter))));
        var kind = formalManifest("user_query");
        kind.put("invocation_kind", "SUBMIT_QUERY");
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of(kind))));
        var method = formalManifest("user_query");
        method.put("http_method", "GET");
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of(method))));
    }

    @Test
    void tool_code非法编码拒绝() {
        var ex = invalid(Map.of("manifests", List.of(formalManifest("对象查询"))));
        assertTrue(ex.getMessage().contains("ASCII"));
    }

    @Test
    void timeout越界与guard_attrs非法拒绝() {
        var timeout = formalManifest("user_query");
        ((Map<String, Object>) timeout.get("exec_attrs")).put("timeout_ms", 900000);
        var ex = invalid(Map.of("manifests", List.of(timeout)));
        assertTrue(ex.getMessage().contains("100至600000"));
        var guards = formalManifest("user_query");
        guards.put("guard_attrs", List.of(""));
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of(guards))));
    }

    @Test
    void 整批校验先行任一非法则零创建() {
        var good = formalManifest("user_query");
        var bad = formalManifest("user_query2");
        ((Map<String, Object>) bad.get("exec_attrs")).put("side_effect", "yes");
        var ex = invalid(Map.of("manifests", List.of(good, bad)));
        assertTrue(ex.getMessage().contains("布尔"));
        verify(catalog, never()).create(any());
    }

    @Test
    void manifest编码重复拒绝() {
        var ex = invalid(Map.of("manifests", List.of(formalManifest("user_query"), formalManifest("user_query"))));
        assertTrue(ex.getMessage().contains("重复"));
        verify(catalog, never()).create(any());
    }

    @Test
    void 缺manifests拒绝() {
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of()));
        assertThrows(PlatformException.class, () -> imports.importManifests(Map.of("manifests", List.of())));
    }
}
