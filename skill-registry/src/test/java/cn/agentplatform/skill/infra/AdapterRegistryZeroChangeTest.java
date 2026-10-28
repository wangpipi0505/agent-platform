package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.application.SkillCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/**
 * PLAT-22C（A-01 适配器注册表）：新执行方式"零改代码接入"验证与既有 kind 分发锚点。
 *
 * <p>红例（实现前）：硬编码类型集合在注册期拒绝 TEST_ECHO（1 run / 1 Error "适配器类型无效"，
 * red1-registration-rejected.log）。绿例（实现后）：注册表接受注册的测试单元并分发执行，
 * 产品主代码不含 TEST_ECHO 任何字样（grep 由证据 README 固化）。
 *
 * <p>锚点：内置五单元的路由表、组合白名单单一来源（SkillCatalog 判定 == registry 判定）、
 * bind/persistentBinding/observe 旁路语义与原 if-chain 等价、MCP/PROCESS 对账保护随单元迁移。
 */
class AdapterRegistryZeroChangeTest {
    private static final String TENANT = "tenant-registry";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** 测试 kind 的最小合法契约形状（endpoint 等形状规则沿用既有分支，收敛属 A-02，非本卡）。 */
    static Map<String, Object> testEchoBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "TEST_ECHO");
        body.put("description", "零改代码接入验证：注册表运行层新执行方式");
        body.put("endpoint", "http://echo.test/invoke");
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        return body;
    }

    /**
     * 测试专用执行单元：证明"实现 SkillAdapter + 注册 = 接入"。它完全住在测试代码里，
     * 不进任何产品枚举——运行层注册与能力包 schema 声明层（T22A 五值枚举）是两层关系。
     */
    static final class TestEchoAdapter implements SkillAdapter {
        boolean invoked;

        @Override
        public String type() {
            return "TEST_ECHO";
        }

        @Override
        public Set<String> supportedKinds() {
            return Set.of("SYNC");
        }

        @Override
        public SkillAdapters.Raw invoke(
                SkillAdapters host,
                Map<String, Object> contract,
                Map<String, Object> arguments,
                String key,
                long callId,
                boolean queryOnly,
                String runId,
                long expectedAttempt) {
            invoked = true;
            var structured = Json.mapper().createObjectNode();
            structured.put("contractType", Objects.toString(contract.get("type"), ""));
            structured.set("echoedArguments", Json.tree(arguments));
            return new SkillAdapters.Raw(
                    structured, List.of(), "TEST_ECHO 回显成功", Json.tree(null), "");
        }
    }

    @Test
    void newExecutionKindOnboardsWithoutTouchingPlatformDispatch() {
        signIn();
        // 注册：实现 SkillAdapter 的测试单元 → 显式注册 → 目录接受。产品主代码零改动。
        var registry = AdapterRegistry.withPlatformDefaults();
        var echo = new TestEchoAdapter();
        registry.register(echo);
        var catalog =
                new SkillCatalog(null, null, null, "http://unused", true, true, "", registry);

        var normalized = catalog.normalize(testEchoBody());

        assertEquals("TEST_ECHO", normalized.get("type"), "测试 kind 必须能通过注册期目录校验");
        assertEquals("SYNC", normalized.get("invocationKind"), "kind 缺省来自注册表单元声明");

        // 执行：SkillAdapters 按注册表分发，适配器被调用——不经过任何产品 switch/分支。
        var adapters =
                new SkillAdapters(null, null, null, null, registry, "DEGRADED", 8192);
        var raw =
                adapters.invoke(
                        normalized, Map.of("ping", "pong"), "idem-echo-1", 42L, false, "run-echo", 1L);
        assertTrue(echo.invoked, "注册的测试单元必须被分发调用");
        assertEquals("TEST_ECHO", raw.structured().path("contractType").asText());
        assertEquals("pong", raw.structured().path("echoedArguments").path("ping").asText());
        assertTrue(raw.summary().contains("TEST_ECHO"));
    }

    @Test
    void unregisteredKindStaysRejectedWithReadableErrorsOnBothGates() {
        // 注册期：未注册 kind → 400 可读（与现状同码同文案，只是裁决者从硬编码集合换成注册表）。
        signIn();
        var catalog =
                new SkillCatalog(
                        null,
                        null,
                        null,
                        "http://unused",
                        true,
                        true,
                        "",
                        AdapterRegistry.withPlatformDefaults());
        var rejected = assertThrows(PlatformException.class, () -> catalog.normalize(testEchoBody()));
        assertEquals(400, rejected.status());
        assertEquals("INVALID_SKILL", rejected.code());
        assertEquals("适配器类型无效", rejected.getMessage());

        // 执行期：未注册 kind → PARAM_ERROR（与原 switch default 臂同码同 unknown 位），正文列出已注册类型。
        var adapters =
                new SkillAdapters(
                        null, null, null, null, AdapterRegistry.withPlatformDefaults(), "DEGRADED", 8192);
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        Map.of("type", "TEST_ECHO", "invocationKind", "SYNC"),
                                        Map.of(),
                                        "k",
                                        1L,
                                        false,
                                        "r",
                                        1L));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertTrue(failure.getMessage().contains("TEST_ECHO"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ALGORITHM"), "诊断必须列出已注册类型: " + failure.getMessage());
    }

    @Test
    void dispatchResolvesTheExpectedUnitForEveryBuiltInKind() {
        var registry = AdapterRegistry.withPlatformDefaults();
        assertEquals(
                HttpSyncExecutionAdapter.class,
                registry.dispatch(Map.of("type", "HTTP", "invocationKind", "SYNC")).getClass());
        assertEquals(
                HttpAsyncExecutionAdapter.class,
                registry
                        .dispatch(Map.of("type", "HTTP", "invocationKind", "SUBMIT_QUERY"))
                        .getClass());
        assertEquals(
                HttpAsyncExecutionAdapter.class,
                registry.dispatch(Map.of("type", "HTTP", "invocationKind", "CALLBACK")).getClass());
        assertEquals(
                McpExecutionAdapter.class,
                registry.dispatch(Map.of("type", "MCP", "invocationKind", "SYNC")).getClass());
        assertEquals(
                ProcessExecutionAdapter.class,
                registry.dispatch(Map.of("type", "PROCESS", "invocationKind", "SYNC")).getClass());
        assertEquals(
                AlgorithmExecutionAdapter.class,
                registry
                        .dispatch(Map.of("type", "ALGORITHM", "invocationKind", "SUBMIT_QUERY"))
                        .getClass());
        // kind 缺省单一来源：ALGORITHM=SUBMIT_QUERY，其余 SYNC。
        assertEquals("SUBMIT_QUERY", registry.defaultKind("ALGORITHM"));
        assertEquals("SYNC", registry.defaultKind("HTTP"));
        assertEquals("SYNC", registry.defaultKind("MCP"));
        assertEquals("SYNC", registry.defaultKind("PROCESS"));
    }

    @Test
    void builtinUnitsAreSpringDiscoverableAndRegistrationAddsNewKinds() {
        // Spring 自动发现路径：内置单元与注册表都是 @Component，容器装配即完成注册。
        assertAll(
                () ->
                        assertTrue(
                                AdapterRegistry.class.isAnnotationPresent(Component.class),
                                "注册表必须可被容器装配"),
                () ->
                        assertTrue(
                                HttpSyncExecutionAdapter.class.isAnnotationPresent(Component.class)),
                () ->
                        assertTrue(
                                HttpAsyncExecutionAdapter.class.isAnnotationPresent(Component.class)),
                () -> assertTrue(McpExecutionAdapter.class.isAnnotationPresent(Component.class)),
                () ->
                        assertTrue(
                                ProcessExecutionAdapter.class.isAnnotationPresent(Component.class)),
                () ->
                        assertTrue(
                                AlgorithmExecutionAdapter.class.isAnnotationPresent(
                                        Component.class)));
        // 运行层扩展：注册 TEST_ECHO 后 isSupportedType/组合即翻真，schema 声明层（T22A 枚举）不受影响。
        var registry = AdapterRegistry.withPlatformDefaults();
        assertFalse(registry.isSupportedType("TEST_ECHO"));
        registry.register(new TestEchoAdapter());
        assertTrue(registry.isSupportedType("TEST_ECHO"));
        assertTrue(registry.isSupportedCombination("TEST_ECHO", "SYNC"));
        assertFalse(registry.isSupportedCombination("TEST_ECHO", "SUBMIT_QUERY"));
    }

    @Test
    void builtinKindMatrixIsSingleSourcedFromRegistry() {
        var registry = AdapterRegistry.withPlatformDefaults();
        var catalog =
                new SkillCatalog(null, null, null, "http://unused", true, true, "", registry);
        signIn();
        for (String type : List.of("HTTP", "MCP", "ALGORITHM", "PROCESS"))
            for (String kind : List.of("SYNC", "SUBMIT_QUERY", "CALLBACK", "STREAM")) {
                boolean accepted = registry.isSupportedCombination(type, kind);
                try {
                    catalog.normalize(minimalBody(type, kind));
                    assertTrue(
                            accepted,
                            type + "/" + kind + " 目录放行但注册表拒绝——单一来源被破坏");
                } catch (PlatformException ex) {
                    if ("UNSUPPORTED_INVOCATION_KIND".equals(ex.code())) {
                        assertFalse(
                                accepted,
                                type + "/" + kind + " 注册表放行但目录拒绝——单一来源被破坏");
                        if ("PROCESS".equals(type))
                            assertTrue(
                                    ex.getMessage().contains("PROCESS 仅支持 SYNC")
                                            && ex.getMessage().contains(kind),
                                    "PROCESS 定制诊断必须由注册表单元承载: " + ex.getMessage());
                    }
                    // 其余 400 属契约形状规则（endpoint/toolName/process 子契约/callback 声明），
                    // 与 kind 白名单无关，不计入矩阵结论。
                }
            }
    }

    @Test
    void persistentBindingAndBindKeepLegacySemanticsThroughRegistry() {
        var algorithm = mock(AlgorithmAdapter.class);
        var asyncHttp = mock(HttpAsyncAdapter.class);
        var adapters =
                new SkillAdapters(
                        mock(ArtifactStore.class), algorithm, asyncHttp, null, AdapterRegistry.withPlatformDefaults(), "DEGRADED", 8192);

        // 持久绑定形态 = ALGORITHM 与 HTTP 异步（与原 if-chain 等价）；未注册 kind 静默 false。
        assertTrue(
                adapters.persistentBinding(
                        Map.of("type", "ALGORITHM", "invocationKind", "SUBMIT_QUERY")));
        assertTrue(
                adapters.persistentBinding(Map.of("type", "HTTP", "invocationKind", "SUBMIT_QUERY")));
        assertTrue(adapters.persistentBinding(Map.of("type", "HTTP", "invocationKind", "CALLBACK")));
        assertFalse(adapters.persistentBinding(Map.of("type", "HTTP", "invocationKind", "SYNC")));
        assertFalse(adapters.persistentBinding(Map.of("type", "MCP", "invocationKind", "SYNC")));
        assertFalse(adapters.persistentBinding(Map.of("type", "PROCESS", "invocationKind", "SYNC")));
        assertFalse(adapters.persistentBinding(Map.of("type", "TEST_UNKNOWN")));

        // bind 路由：ALGORITHM→算法绑定、HTTP 异步→幂等键登记；HTTP 同步/MCP/PROCESS 无绑定；未注册 kind 静默。
        adapters.bind(Map.of("type", "ALGORITHM", "invocationKind", "SUBMIT_QUERY"), Map.of());
        verify(algorithm).bind(any(), any());
        adapters.bind(Map.of("type", "HTTP", "invocationKind", "CALLBACK"), Map.of());
        verify(asyncHttp).bind(any(), any());
        adapters.bind(Map.of("type", "HTTP", "invocationKind", "SYNC"), Map.of());
        adapters.bind(Map.of("type", "MCP", "invocationKind", "SYNC"), Map.of());
        adapters.bind(Map.of("type", "PROCESS", "invocationKind", "SYNC"), Map.of());
        adapters.bind(Map.of("type", "TEST_UNKNOWN"), Map.of());
        verifyNoMoreInteractions(algorithm, asyncHttp);
    }

    @Test
    void queryOnlyGuardsMoveWithTheirUnits() {
        // MCP/PROCESS 的对账保护（RESULT_UNKNOWN）随单元迁移，注册表分发不得丢失该语义。
        var mcp = new McpExecutionAdapter();
        var mcpFailure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                mcp.invoke(
                                        null,
                                        Map.of("type", "MCP", "invocationKind", "SYNC"),
                                        Map.of(),
                                        "k",
                                        1L,
                                        true,
                                        "r",
                                        1L));
        assertEquals("RESULT_UNKNOWN", mcpFailure.semantic);
        assertTrue(mcpFailure.unknown);
        assertTrue(mcpFailure.getMessage().contains("MCP"));

        var process = new ProcessExecutionAdapter();
        var processFailure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                process.invoke(
                                        null,
                                        Map.of("type", "PROCESS", "invocationKind", "SYNC"),
                                        Map.of(),
                                        "k",
                                        1L,
                                        true,
                                        "r",
                                        1L));
        assertEquals("RESULT_UNKNOWN", processFailure.semantic);
        assertTrue(processFailure.unknown);
        assertTrue(processFailure.getMessage().contains("本地进程"));
    }

    @Test
    void observeDefaultsToInvokeRoutingForNonObservationKinds() {
        // 非 ALGORITHM 的 observe 缺省 = invoke + 空观测源（与原 if-chain 的 else 臂等价）。
        signIn();
        var registry = AdapterRegistry.withPlatformDefaults();
        var echo = new TestEchoAdapter();
        registry.register(echo);
        var adapters = new SkillAdapters(null, null, null, null, registry, "DEGRADED", 8192);

        var observation =
                adapters.observe(
                        testEchoBody(),
                        Map.of("ping", "pong"),
                        "idem-echo-obs",
                        7L,
                        false,
                        "run-echo",
                        1L);

        assertTrue(echo.invoked, "observe 缺省路由必须到达单元 invoke");
        assertTrue(observation.source().isEmpty(), "非观测型 kind 的观测源为空（既有语义）");
        assertEquals("TEST_ECHO", observation.raw().structured().path("contractType").asText());
    }

    /** 矩阵用最小契约：只保证通过形状规则到达 kind 组合判定，形状拒绝（非 UNSUPPORTED_INVOCATION_KIND）不计入。 */
    private static final Path JAVA =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                            ? "java.exe"
                            : "java");

    static Map<String, Object> minimalBody(String type, String kind) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("description", "注册表单一来源矩阵用例 " + type + "/" + kind);
        body.put("invocationKind", kind);
        if (!type.equals("ALGORITHM") && !type.equals("PROCESS"))
            body.put("endpoint", "http://matrix.test/invoke");
        if (type.equals("MCP")) body.put("toolName", "echo.tool");
        if (type.equals("PROCESS"))
            body.put("process", Map.of("command", List.of(JAVA.toString(), "--version")));
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        return body;
    }
}
