package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.OpenApiHttpMapping;
import cn.agentplatform.skill.infra.SkillClients;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * 路线三（HTTP 能力导入）用真实业务系统规格做的验收：规格来自 space-ground-visible 的
 * {@code docs/openapi/openapi.json}（43 paths / 51 operations），不是测试内手写的玩具文档。
 *
 * <p>不变量一：只取真实文件。文件不存在时整类 abort（{@link Assumptions}），不允许伪造一份
 * "看起来像"的规格来制造绿色；这类测试一旦自己造输入，就无法证明平台能吃下真实世界的大规格。
 *
 * <p>不变量二：只读操作用 GET 表达，但契约里的 {@code execution.readonly} 不是导入器推导的，
 * 而是导入时必须由运营显式填写的字段（见 {@code OpenApiImports#create} 对 overrides 的读取）。
 * 本类因此显式声明 readonly=true，再用 {@link SkillCatalog#normalize} 断言平台把它归一化成
 * {@code riskLevel=READ_ONLY}——这是"只读契约合法"真正被平台承认的地方。
 *
 * <p>不变量三：写操作与不可转换操作按实现如实断言，不假装平台拒绝所有 POST。multipart 上传
 * （operation25）是 supported 的：导入器只校验形态，不替运营判断业务只读性，只读边界由本类的
 * 选择过滤（{@code SELECTED}）守。真正被拒绝的是不受支持的形态——本规格里 POST 查询的请求体
 * 带 {@code pattern}、文件下载返回非 JSON——preview 标 unsupported 并给诊断，create 阶段以
 * {@code OPENAPI_UNSUPPORTED} 挡住。两类边界都写进断言，避免证据变成"只报喜"。
 *
 * <p>不变量四：真实网络调用只发生在被测服务已启动时（3s 超时），连不上就 abort 并写进证据；
 * 导入本身（preview/create）不发起任何网络，authRef 只走 {@link SecretVault} 解析，本规格未声明
 * security，因此这里留空——用假 vault ref 会被 SecretVault 拒绝，反而掩盖了"本规格无需凭据"的事实。
 */
class SpaceGroundOpenApiImportTest {
    /** 真实规格路径；可用 -Dspace.ground.spec=... 覆盖（证据脚本换机器时不必改代码）。 */
    private static final Path SPEC =
            Path.of(
                    System.getProperty(
                            "space.ground.spec",
                            "G:/5-YG/space-ground-visible/docs/openapi/openapi.json"));

    private static final String BASE_URL = "http://127.0.0.1:8080";

    /** 选中的 5 个只读操作：覆盖无参、纯路径参数、纯查询参数、路径+查询参数四种形状。 */
    private static final List<String> SELECTED =
            List.of("operation01", "operation05", "operation17", "operation27", "operation03");

    /** operationId → 期望的 {path 参数, query 参数}（与规格声明逐字对应）。 */
    private static final Map<String, List<String>> EXPECTED_PATH =
            Map.of(
                    "operation01", List.of(),
                    "operation05", List.of("satelliteId"),
                    "operation17", List.of("taskId"),
                    "operation27", List.of("fileId"),
                    "operation03", List.of());

    private static final Map<String, List<String>> EXPECTED_QUERY =
            Map.of(
                    "operation01", List.of(),
                    "operation05", List.of(),
                    "operation17", List.of(),
                    "operation27", List.of("page", "limit"),
                    "operation03", List.of("pageNumber", "pageSize", "keyword"));

    private static final Map<String, String> EXPECTED_HTTP_PATH =
            Map.of(
                    "operation01", "/api/v1/space/health",
                    "operation05", "/api/v1/space/satellites/{satelliteId}/payload",
                    "operation17", "/api/v1/space/visibility/tasks/{taskId}",
                    "operation27", "/api/v1/ballistic/files/{fileId}/preview",
                    "operation03", "/api/v1/space/satellites/page");

    private static final Map<String, String> CODE =
            Map.of(
                    "operation01", "space_health",
                    "operation05", "space_satellite_payload",
                    "operation17", "space_visibility_task",
                    "operation27", "space_ballistic_preview",
                    "operation03", "space_satellites_page");

    private final SkillCatalog catalog = mock(SkillCatalog.class);
    private final OpenApiImports imports = new OpenApiImports(catalog);
    private String document;

    @BeforeEach
    void actorAndRealSpec() throws Exception {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("space-ground-importer")
                        .claim("tenant_id", "space-ground-import")
                        .claim("roles", List.of("EDITOR"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        Assumptions.assumeTrue(
                Files.isRegularFile(SPEC),
                "真实 OpenAPI 规格不存在，跳过（不伪造输入）：" + SPEC.toAbsolutePath());
        document = Files.readString(SPEC, StandardCharsets.UTF_8);
        when(catalog.create(anyMap()))
                .thenAnswer(
                        invocation -> {
                            Map<String, Object> contract = invocation.getArgument(0);
                            var draft = new LinkedHashMap<String, Object>();
                            draft.put("code", contract.get("code"));
                            draft.put("status", "DRAFT");
                            draft.put("currentVersion", null);
                            return draft;
                        });
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewsRealSpecAndConvertsFiveGetOperationsToMappingConsistentSchemas() {
        var preview = imports.preview(Map.of("document", document));
        assertEquals(Digest.sha256(document), preview.get("documentSha256"));
        var operations = operations(preview);
        // 规格事实：43 paths / 51 操作，全部被解析并按 operationId 唯一标识。
        assertEquals(51, operations.size(), "真实规格应有 51 个 operation");
        assertEquals(51, operations.stream().map(o -> o.get("operationId")).distinct().count());
        // 规格事实之二：转换器当前只接受 16/51 个操作（诊断逐条见证据 README）。这个数字变化
        // 说明转换器或规格变了，证据必须同步，不能让它悄悄漂移。
        assertEquals(
                16,
                operations.stream().filter(o -> Boolean.TRUE.equals(o.get("supported"))).count(),
                "可转换操作数变了，需同步证据");

        for (String id : SELECTED) {
            var op = operation(operations, id);
            assertEquals(true, op.get("supported"), id + " 应可转换：" + op.get("diagnostics"));
            assertEquals("GET", op.get("method"));
            assertEquals(EXPECTED_HTTP_PATH.get(id), op.get("path"));

            var mapping = SkillCatalog.object(op.get("httpMapping"));
            assertEquals(Set.of("path", "query", "body"), mapping.keySet(), id + " 映射键集");
            assertEquals(EXPECTED_PATH.get(id), mapping.get("path"), id + " path 映射");
            assertEquals(EXPECTED_QUERY.get(id), mapping.get("query"), id + " query 映射");
            assertEquals("", mapping.get("body"), id + " GET 不得携带请求体");

            var schema = SkillCatalog.object(op.get("inputSchema"));
            assertEquals("object", schema.get("type"));
            assertEquals(false, schema.get("additionalProperties"));
            var properties = SkillCatalog.object(schema.get("properties"));
            var expectedNames =
                    new LinkedHashSet<String>(EXPECTED_PATH.get(id));
            expectedNames.addAll(EXPECTED_QUERY.get(id));
            assertEquals(expectedNames, properties.keySet(), id + " 属性与参数一一对应");
            // path 参数恒为 required；query 参数在本规格全部可选。
            assertEquals(EXPECTED_PATH.get(id), schema.get("required"), id + " required 只含 path");
            // 标量映射：path/query 只允许四类标量，这里逐项确认转换没有产出对象/数组。
            for (String name : expectedNames) {
                var property = SkillCatalog.object(properties.get(name));
                assertTrue(
                        Set.of("string", "integer", "number", "boolean")
                                .contains(property.get("type")),
                        id + "." + name + " 必须是标量：" + property);
            }
            // 类型抽查只在操作确实声明了该参数时成立：无参操作（health）没有 keyword/pageSize。
            if (expectedNames.contains("keyword"))
                assertEquals("string", SkillCatalog.object(properties.get("keyword")).get("type"));
            if (expectedNames.contains("pageSize"))
                assertEquals("integer", SkillCatalog.object(properties.get("pageSize")).get("type"));
        }
    }

    @Test
    void createProducesLegalReadonlyHttpContractsThatCatalogNormalizes() {
        var request = createRequest(List.of("operation01", "operation05", "operation17", "operation27", "operation03"));
        var response = imports.create(request);
        assertEquals(5, ((List<?>) response.get("drafts")).size());
        assertFalse(((List<?>) response.get("warnings")).isEmpty());

        var captured = ArgumentCaptor.forClass(Map.class);
        verify(catalog, times(5)).create(captured.capture());
        List<Map<String, Object>> contracts =
                ((List<Map<String, Object>>) (List<?>) captured.getAllValues());
        assertEquals(5, contracts.size());

        // 用真实的目录归一化（不写库：normalize 是纯函数）验证契约能被平台接受，而不是只看导入器输出。
        var real = new SkillCatalog(
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class),
                mock(SkillClients.class),
                "http://127.0.0.1:9");
        for (var contract : contracts) {
            String id = SkillCatalog.object(contract.get("importSource")).get("operationId").toString();
            assertEquals("HTTP", contract.get("type"));
            assertEquals("GET", contract.get("httpMethod"));
            assertEquals(BASE_URL + EXPECTED_HTTP_PATH.get(id), contract.get("endpoint"));
            assertEquals(CODE.get(id), contract.get("code"));
            assertEquals("", contract.get("authRef"), id + " 规格未声明 security，不伪造凭据");
            assertEquals(30_000, contract.get("timeoutMs"));
            var mapping = SkillCatalog.object(contract.get("httpMapping"));
            assertEquals(EXPECTED_PATH.get(id), mapping.get("path"));
            assertEquals(EXPECTED_QUERY.get(id), mapping.get("query"));

            var normalized = real.normalize(contract);
            assertEquals("SYNC", normalized.get("invocationKind"), id + " 同步调用");
            assertEquals("READ_ONLY", normalized.get("riskLevel"), id + " 只读风险级别由 readonly 归一化");
            assertEquals(
                    Boolean.TRUE,
                    SkillCatalog.object(normalized.get("execution")).get("readonly"),
                    id + " 只读标志");
            assertEquals("BEARER", normalized.get("authScheme"), id + " 未声明时的兼容默认值");
            assertEquals("object", SkillCatalog.object(normalized.get("inputSchema")).get("type"));
            // 声明式映射可被请求构造器直接消费（等价于执行期拼 URL 的同一段代码）。
            var built = OpenApiHttpMapping.request(contract, emptyArguments(id));
            var uri = URI.create(built.endpoint());
            assertEquals("http", uri.getScheme());
            assertEquals("127.0.0.1", uri.getHost());
            assertEquals(8080, uri.getPort());
            assertFalse(built.endpoint().contains("{"), id + " 构造后不得残留模板变量");
            if (EXPECTED_PATH.get(id).isEmpty()) assertNull(uri.getRawQuery(), id + " 无参数不得出现 ?");
        }
    }

    @Test
    void writeOperationsAreNotSelectedEvenWhenConvertibleAndUnsupportedOnesAreRejected() {
        var operations = operations(imports.preview(Map.of("document", document)));

        // 如实断言一：本规格的 POST /targets/query 形式上"是查询"，但请求体 Schema 带 pattern，
        // 转换器不支持，preview 直接标 unsupported；它既不在只读选择里，也进不了 create。
        var query = operation(operations, "operation11");
        assertEquals("POST", query.get("method"));
        assertEquals(false, query.get("supported"));
        assertTrue(
                ((List<?>) query.get("diagnostics")).toString().contains("pattern"),
                "诊断应指出不支持的 Schema 关键字：" + query.get("diagnostics"));
        assertFalse(SELECTED.contains("operation11"));

        // 如实断言二：带 requestBody 的写操作并不一律"不可转换"。multipart 上传（operation25）
        // 是 supported 的，映射用 multipart 而非 body；平台只校验形态、不替运营判断只读性，
        // 因此它可转换但绝不能出现在只读选择集合里——边界由选择过滤守，而不是方法名。
        var upload = operation(operations, "operation25");
        assertEquals("POST", upload.get("method"));
        assertEquals(true, upload.get("supported"), "诊断：" + upload.get("diagnostics"));
        assertEquals(
                Set.of("path", "query", "multipart"),
                SkillCatalog.object(upload.get("httpMapping")).keySet());
        assertFalse(SELECTED.contains("operation25"));

        // 真正不可转换的是非 JSON 成功响应（原文件下载）：create 阶段统一以
        // OPENAPI_UNSUPPORTED 挡住，不能半批写入。
        var binary = operation(operations, "operation28");
        assertEquals("GET", binary.get("method"));
        assertEquals(false, binary.get("supported"));
        assertFalse(((List<?>) binary.get("diagnostics")).isEmpty(), "不可转换必须给出诊断");

        var request = createRequest(List.of("operation01", "operation28"));
        var failure = assertThrows(PlatformException.class, () -> imports.create(request));
        assertEquals("OPENAPI_UNSUPPORTED", failure.code());
        verify(catalog, never()).create(anyMap());

        // 选中不存在的 operationId 同样拒绝，避免"静默少导入一个"。
        var missing = createRequest(List.of("operation01", "operation99"));
        assertEquals(
                "OPENAPI_SELECTION",
                assertThrows(PlatformException.class, () -> imports.create(missing)).code());
    }

    @Test
    void generatedHealthContractReachesTheRunningService() throws Exception {
        var operations = operations(imports.preview(Map.of("document", document)));
        var contract = Map.of(
                "type", "HTTP",
                "endpoint", BASE_URL + EXPECTED_HTTP_PATH.get("operation01"),
                "httpMethod", "GET",
                "httpMapping", operation(operations, "operation01").get("httpMapping"),
                "inputSchema", operation(operations, "operation01").get("inputSchema"));
        var built = OpenApiHttpMapping.request(
                new LinkedHashMap<>(contract), Map.of());

        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> response;
        try {
            response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(built.endpoint()))
                                    .timeout(Duration.ofSeconds(3))
                                    .header("Accept", "application/json")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException unreachable) {
            Assumptions.abort(
                    "space-ground-visible 未在本机 " + BASE_URL + " 运行，跳过真实 GET：" + unreachable);
            return;
        }
        assertEquals(200, response.statusCode(), "健康检查应返回 2xx");
        assertTrue(
                Objects.toString(response.headers().firstValue("content-type").orElse(""))
                        .contains("json"),
                "响应必须是 JSON：" + response.headers().map());
        var body = Json.read(response.body(), Map.class);
        // 真实响应是 {"status":"UP"}，不是平台统一信封：路线三原样暴露上游既有契约。
        assertEquals("UP", body.get("status"), "响应形状：" + body);
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> preview) {
        return (List<Map<String, Object>>) preview.get("operations");
    }

    private static Map<String, Object> operation(List<Map<String, Object>> operations, String id) {
        return operations.stream()
                .filter(o -> id.equals(o.get("operationId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("规格中缺少 " + id));
    }

    private Map<String, Object> createRequest(List<String> ids) {
        var overrides = new LinkedHashMap<String, Object>();
        for (String id : ids)
            if (CODE.containsKey(id)) overrides.put(id, manual(CODE.get(id), id));
        var request = new LinkedHashMap<String, Object>();
        request.put("document", document);
        request.put("includeOps", ids);
        request.put("defaults", Map.of("baseUrl", BASE_URL));
        request.put("overrides", overrides);
        return request;
    }

    private static Map<String, Object> manual(String code, String operationId) {
        var manual = new LinkedHashMap<String, Object>();
        manual.put("code", code);
        manual.put("name", "空间只读：" + operationId);
        manual.put("description", "导入自 space-ground-visible 真实规格的只读查询；示例参数按规格类型填写。");
        manual.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        manual.put("guard", Map.of("roles", List.of("READER"), "attributes", Map.of()));
        manual.put("errorSemantics", List.of(Map.of("code", "PARAM_ERROR", "retryable", false)));
        manual.put(
                "returnSpec",
                Map.of(
                        "summaryTemplate", "已查询空间能力",
                        "structuredPath", "$",
                        "fileRefPaths", List.of()));
        return manual;
    }

    /** 构造请求参数所需的占位值：path 用 1，query 留空（本规格 query 全部可选）。 */
    private static Map<String, Object> emptyArguments(String id) {
        var arguments = new LinkedHashMap<String, Object>();
        for (String name : EXPECTED_PATH.get(id)) arguments.put(name, 1);
        return arguments;
    }
}
