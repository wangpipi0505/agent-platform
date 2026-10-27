package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.file.Path;
import java.util.*;

/**
 * PLAT-21b 目录层：PROCESS 进入类型集合后的注册期契约。
 *
 * <p>断言全部落在 {@link SkillCatalog#normalize} 上——技能契约进入版本表的唯一入口。这里的拒绝意味着
 * “坏配置从未被发布”，而不是等第一次执行才暴露。PROCESS 与 HTTP/MCP/ALGORITHM 的差别集中在这三件事：
 * 不寻址（endpoint/httpMapping 互斥）、只有同步形态、以及一个由 {@code ProcessContract.parse} 唯一解释的
 * {@code process} 子契约。
 */
class SkillProcessRegistrationTest {
    private static final String TENANT = "tenant-process";

    /** 注册期只校验“首元素是绝对路径”，不要求可执行文件存在（发布预检留给 T21c）。 */
    private static final Path JAVA =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                            ? "java.exe"
                            : "java");

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

    private static SkillCatalog catalog() {
        // 与既有注册期测试一致：legacy-bearer 打开，PROCESS 与 MCP/ALGORITHM 同样补 BEARER。
        return new SkillCatalog(null, null, null, "http://unused", true);
    }

    private static Map<String, Object> process() {
        var process = new LinkedHashMap<String, Object>();
        process.put("command", List.of(JAVA.toString(), "--version"));
        return process;
    }

    private static Map<String, Object> body() {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "PROCESS");
        body.put("description", "本地执行重组算法二进制");
        body.put("process", process());
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

    @Test
    void processContractIsAcceptedAndNormalizedBeforePersistence() {
        signIn();

        Map<String, Object> normalized = catalog().normalize(body());

        assertEquals("PROCESS", normalized.get("type"));
        assertEquals("SYNC", normalized.get("invocationKind"));
        assertEquals("", normalized.get("endpoint"), "PROCESS 不寻址，endpoint 必须为空");
        // 顶层超时对 PROCESS 由 process.timeoutMs 派生：契约里只保留一个真实超时，
        // 否则编辑页显示30s、执行器实际按60s跑，是两个互相矛盾的数。
        assertEquals(60000L, normalized.get("timeoutMs"));

        var persisted = Json.tree(normalized).path("process");
        assertTrue(persisted.isObject(), "process 必须以规范化后的对象写回持久化契约");
        assertEquals(JAVA.toString(), persisted.path("command").get(0).asText());
        assertEquals(
                60000,
                persisted.path("timeoutMs").asInt(),
                "ProcessContract.parse 未显式声明时补默认 60000");
        assertEquals(1048576, persisted.path("maxOutputBytes").asInt());
        assertFalse(persisted.has("workDirRoot"), "未声明可选字段时不写空值");
        assertFalse(persisted.has("stdin"));
        assertFalse(persisted.has("env"));
    }

    @Test
    void processRejectsEndpointBecauseALocalProcessNeverAddressesAnHttpEndpoint() {
        signIn();
        var body = body();
        body.put("endpoint", "https://algo.example.test/run");

        var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body));

        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().contains("endpoint"), "报错必须点名冲突字段: " + ex.getMessage());
    }

    @Test
    void processRejectsHttpMappingBecauseThereIsNoHttpRequestToMap() {
        signIn();
        var body = body();
        body.put("httpMapping", Map.of("method", "POST", "path", "/run"));

        var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body));

        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().contains("httpMapping"), "报错必须点名冲突字段: " + ex.getMessage());
    }

    @Test
    void processOnlySupportsSyncInvocationKind() {
        signIn();

        for (String kind : List.of("SUBMIT_QUERY", "CALLBACK")) {
            var body = body();
            body.put("invocationKind", kind);

            var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body), kind);

            assertEquals(400, ex.status());
            assertEquals("UNSUPPORTED_INVOCATION_KIND", ex.code());
            assertTrue(
                    ex.getMessage().contains("PROCESS") && ex.getMessage().contains(kind),
                    "错误要点名适配器与调用形态: " + ex.getMessage());
        }
    }

    @Test
    void processObjectIsMandatoryForProcessSkills() {
        signIn();
        var body = body();
        body.remove("process");

        var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_CONTRACT_INVALID", ex.code());
    }

    @Test
    void processCommandMustStartWithAnAbsolutePath() {
        signIn();
        var body = body();
        body.put("process", Map.of("command", List.of("bin/recombine")));

        var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_CONTRACT_INVALID", ex.code());
        assertTrue(ex.getMessage().contains("绝对路径"), "错误必须指出首元素要求: " + ex.getMessage());
    }
}
