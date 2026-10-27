package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.FakeProcessWorker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PLAT-21d：allowed-executables 白名单的注册期闸（契约 §5.5/§5.6，T21c 标注"未实现归 T21d"）。
 *
 * <p>口径：白名单已配置时，PROCESS 契约的 command[0] 必须命中，否则注册期即 400 可读拒绝
 * （不用等到第一次真实调用才发现白名单外可执行文件）；白名单未配置时保持 T21b/c 兼容行为
 * （ADMIN 登记 + 发布预检兜底），升级不炸存量。
 */
class SkillProcessWhitelistRegistrationTest {

    private static final String TENANT = "tenant-process-whitelist";

    private static final Path JAVA =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                            ? "java.exe"
                            : "java");

    @AfterEach
    void clear() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void registrationRejectsExecutableOutsideWhitelistWith400() {
        signIn();
        SkillCatalog catalog =
                new SkillCatalog(
                        null, null, null, "http://unused", true, true, "V:/不存在/other-worker.exe");

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.normalize(body()),
                        "白名单外可执行文件必须在注册期被拒绝");

        assertEquals(400, ex.status());
        assertEquals("PROCESS_EXECUTABLE_FORBIDDEN", ex.code());
        assertTrue(ex.getMessage().contains("白名单"), ex.getMessage());
    }

    @Test
    void registrationAcceptsExecutableInsideWhitelist() {
        signIn();
        SkillCatalog catalog =
                new SkillCatalog(
                        null,
                        null,
                        null,
                        "http://unused",
                        true,
                        true,
                        FakeProcessWorker.javaExecutable() + ", V:/不存在/other.exe");

        var normalized = catalog.normalize(body());

        assertEquals("PROCESS", normalized.get("type"));
        assertNotNull(normalized.get("process"));
    }

    @Test
    void emptyWhitelistKeepsLegacyRegistrationUnrestricted() {
        signIn();
        SkillCatalog catalog =
                new SkillCatalog(null, null, null, "http://unused", true, true, "");

        assertDoesNotThrow(() -> catalog.normalize(body()), "未配置白名单时保持 T21c 兼容行为");
    }

    private static void signIn() {
        var jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("admin")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN"))
                        .build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationToken(jwt));
    }

    private static Map<String, Object> body() {
        var process = new LinkedHashMap<String, Object>();
        process.put("command", List.of(JAVA.toString(), "--version"));
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "PROCESS");
        body.put("description", "本地执行重组算法二进制（白名单注册期校验）");
        body.put("process", process);
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        body.put("guard", Map.of("roles", List.of("EDITOR")));
        return body;
    }
}
