package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * multipart契约的失败路径：声明互斥、部分规格、构建期参数错误与类型白名单。
 * 文件字节来源以假FileSource注入，不依赖PG/MinIO。
 */
class MultipartFormMappingTest {

    private static Map<String, Object> contract(
            String httpMethod, Map<String, Object> mapping, Map<String, Object> inputSchema) {
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("endpoint", "http://127.0.0.1:2/api/v1/ballistic/files");
        contract.put("httpMethod", httpMethod);
        contract.put("httpMapping", mapping);
        if (inputSchema != null) contract.put("inputSchema", inputSchema);
        return contract;
    }

    private static Map<String, Object> form(
            List<Map<String, Object>> fields, List<Map<String, Object>> files) {
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("path", List.of());
        mapping.put("query", List.of());
        if (fields != null) mapping.put("multipart", new LinkedHashMap<>(Map.of("fields", fields, "files", files)));
        return mapping;
    }

    private static Map<String, Object> fileSpec(String from, List<String> types) {
        return new LinkedHashMap<>(
                Map.of("name", from, "from", from, "filename", "trajectory.csv",
                        "maxBytes", 1024L * 1024, "allowedContentTypes", types));
    }

    private static Map<String, Object> schemaWith(String... names) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (String name : names) {
            properties.put(name, Map.of("type", "string"));
            required.add(name);
        }
        return new LinkedHashMap<>(Map.of("type", "object", "properties", properties, "required", required));
    }

    private static MultipartFormMapping.FileSource source(String contentType, long bytes) {
        return id -> {
            byte[] data = new byte[(int) bytes];
            Arrays.fill(data, (byte) 'x');
            return new ArtifactStore.Download(
                    new ByteArrayInputStream(data), bytes, contentType, "sha");
        };
    }

    @Test
    void multipart与JSON体互斥且必须落在multipart键下() {
        var mapping = form(List.of(), List.of(fileSpec("file", List.of("text/csv"))));
        mapping.put("body", "");
        var contract = contract("POST", mapping, schemaWith("file"));
        var ex = assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(contract));
        assertTrue(ex.getMessage().contains("互斥"));
    }

    @Test
    void GET不能携带multipart() {
        var contract = contract("GET", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var ex = assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(contract));
        assertTrue(ex.getMessage().contains("GET"));
    }

    @Test
    void 异步调用不支持multipart() {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        contract.put("invocationKind", "SUBMIT_QUERY");
        var ex = assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(contract));
        assertTrue(ex.getMessage().contains("同步"));
    }

    @Test
    void 文件白名单禁止通配与空表() {
        var wildcard = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/*")))), schemaWith("file"));
        assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(wildcard));
        var empty = contract("POST", form(List.of(), List.of(fileSpec("file", List.of()))), schemaWith("file"));
        assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(empty));
    }

    @Test
    void 文件名拒绝路径注入() {
        var bad = fileSpec("file", List.of("text/csv"));
        bad.put("filename", "../etc/passwd");
        var contract = contract("POST", form(List.of(), List.of(bad)), schemaWith("file"));
        assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(contract));
    }

    @Test
    void maxBytes上限与输入Schema一致性() {
        var over = fileSpec("file", List.of("text/csv"));
        over.put("maxBytes", MultipartFormMapping.MAX_FILE_BYTES + 1);
        var contract = contract("POST", form(List.of(), List.of(over)), schemaWith("file"));
        assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(contract));

        // 输入Schema属性必须覆盖multipart输入
        var mismatch = contract(
                "POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("other"));
        var ex = assertThrows(PlatformException.class, () -> OpenApiHttpMapping.validate(mismatch));
        assertTrue(ex.getMessage().contains("一致"));
    }

    @Test
    void 未声明multipart的契约不进入multipart构建() {
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("path", List.of());
        mapping.put("query", List.of());
        mapping.put("body", "body");
        assertFalse(MultipartFormMapping.declared(contract("POST", mapping, null)));
    }

    @Test
    void 缺少multipart参数在构建期拒绝() {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var ex = assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(contract, Map.of(), source("text/csv", 8)));
        assertEquals("PARAM_ERROR", ex.semantic);
    }

    @Test
    void 文件超限与类型不符在构建期拒绝() {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var tooBig = assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(
                        contract, Map.of("file", "art_" + "a".repeat(32)), source("text/csv", 1024L * 1024 + 1)));
        assertEquals("PARAM_ERROR", tooBig.semantic);
        var wrongType = assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(
                        contract, Map.of("file", "art_" + "a".repeat(32)), source("application/zip", 8)));
        assertEquals("PARAM_ERROR", wrongType.semantic);
    }

    @Test
    void 非法产物引用与未登记字节数在构建期拒绝() {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var badRef = assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(
                        contract, Map.of("file", "http://evil/artifacts/x"), source("text/csv", 8)));
        assertEquals("PARAM_ERROR", badRef.semantic);
        // 元数据字节数与实际流不一致：防静默截断，按RESULT_UNKNOWN处理。
        assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(
                        contract,
                        Map.of("file", "art_" + "a".repeat(32)),
                        id -> new ArtifactStore.Download(
                                new ByteArrayInputStream(new byte[3]), 8, "text/csv", "sha")));
    }

    @Test
    void 构建产出文本与文件部分且文件名来自契约() {
        var contract = contract(
                "POST",
                form(
                        List.of(Map.of("name", "payloadType", "from", "payloadType")),
                        List.of(fileSpec("file", List.of("text/csv")))),
                schemaWith("payloadType", "file"));
        MultiValueMap<String, Object> parts =
                MultipartFormMapping.build(
                        contract,
                        Map.of("payloadType", "SPACE", "file", "art_" + "b".repeat(32)),
                        source("text/csv", 5));
        assertEquals(2, parts.size());
        assertEquals("SPACE", parts.getFirst("payloadType"));
        var entity = (HttpEntity<?>) parts.getFirst("file");
        assertEquals("text/csv", entity.getHeaders().getContentType().toString());
        var resource = (org.springframework.core.io.ByteArrayResource) entity.getBody();
        assertEquals(5, resource.contentLength());
        assertEquals("trajectory.csv", resource.getFilename());
    }

    @Test
    void request解析multipart契约端点且body为空() {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var request = OpenApiHttpMapping.request(contract, Map.of("file", "art_" + "c".repeat(32)));
        assertTrue(request.endpoint().startsWith("http://127.0.0.1:2/api/v1/ballistic/files"));
        assertNull(request.body());
        var unmapped =
                assertThrows(
                        PlatformException.class,
                        () ->
                                OpenApiHttpMapping.request(
                                        contract, Map.of("file", "art_" + "c".repeat(32), "extra", "x")));
        assertTrue(unmapped.getMessage().contains("未映射"));
    }

    @Test
    void 受控下载路径与art直接引用解析到同一产物() throws Exception {
        var contract = contract("POST", form(List.of(), List.of(fileSpec("file", List.of("text/csv")))), schemaWith("file"));
        var parts =
                MultipartFormMapping.build(
                        contract,
                        Map.of("file", "/admin/artifacts/" + "d".repeat(32) + "/download"),
                        source("text/csv", 4));
        var entity = (HttpEntity<?>) parts.getFirst("file");
        var resource = (org.springframework.core.io.ByteArrayResource) entity.getBody();
        assertEquals(4, resource.contentLength());
    }
}
