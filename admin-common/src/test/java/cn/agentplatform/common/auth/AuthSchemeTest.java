package cn.agentplatform.common.auth;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecretVault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLAT-11 的契约字段语义单测：解析、注册期归一化、组合校验、执行期头映射。
 *
 * <p>这里刻意只测 {@link AuthScheme} 自己，不启动 Spring：注册期与执行期的两条调用链分别在
 * SkillCatalog 与适配器层另有集成测试，本类失败时必须能立刻判断是“字段语义错”还是“接线错”。
 */
class AuthSchemeTest {
    private String previousDir;
    private String previousKey;

    @BeforeEach
    void setUp() {
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        try {
            Path dir = Path.of(".local", "auth-scheme-" + UUID.randomUUID()).toAbsolutePath();
            Files.createDirectories(dir);
            System.setProperty("agent.secret.dir", dir.toString());
            System.setProperty("agent.master.key", "PLAT-11-test-master-key-0123456789abcdef");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        Jwt jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", "tenant-auth")
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        restore("agent.secret.dir", previousDir);
        restore("agent.master.key", previousKey);
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    /** 契约里能存的只有三个枚举名；大小写不一致必须拒绝而不是猜意图。 */
    @Test
    void onlyTheThreeDeclaredSpellingsAreAccepted() {
        assertThat(AuthScheme.parse("BEARER")).isEqualTo(AuthScheme.BEARER);
        assertThat(AuthScheme.parse("API_KEY")).isEqualTo(AuthScheme.API_KEY);
        assertThat(AuthScheme.parse("NONE")).isEqualTo(AuthScheme.NONE);
        assertThat(AuthScheme.parse(" BEARER ")).isEqualTo(AuthScheme.BEARER);
        assertThatThrownBy(() -> AuthScheme.parse("api_key"))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure ->
                                assertThat(((PlatformException) failure).code())
                                        .isEqualTo(AuthScheme.CODE_INVALID));
        assertThatThrownBy(() -> AuthScheme.parse("BASIC"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("BEARER/API_KEY/NONE");
        assertThatThrownBy(() -> AuthScheme.parse(null))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure ->
                                assertThat(((PlatformException) failure).status()).isEqualTo(400));
    }

    /** 兼容窗口打开时，未声明 = 改造前的硬编码 Bearer；这是存量技能不中断的唯一依据。 */
    @Test
    void undeclaredDefaultsToBearerWhileTheLegacyWindowIsOpen() {
        assertThat(AuthScheme.registration(null, true)).isEqualTo(AuthScheme.BEARER);
        assertThat(AuthScheme.registration("   ", true)).isEqualTo(AuthScheme.BEARER);
        assertThat(AuthScheme.registration("NONE", true)).isEqualTo(AuthScheme.NONE);
    }

    /** 关闸后未声明必须拒绝注册，而不是继续被补成 Bearer；显式声明仍照常接受。 */
    @Test
    void undeclaredIsRejectedOnceTheLegacyWindowCloses() {
        assertThatThrownBy(() -> AuthScheme.registration(null, false))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure -> {
                            PlatformException error = (PlatformException) failure;
                            assertThat(error.status()).isEqualTo(400);
                            assertThat(error.code()).isEqualTo(AuthScheme.CODE_REQUIRED);
                            assertThat(error.getMessage()).contains("legacy-bearer");
                        });
        assertThat(AuthScheme.registration("NONE", false)).isEqualTo(AuthScheme.NONE);
        assertThat(AuthScheme.registration("API_KEY", false)).isEqualTo(AuthScheme.API_KEY);
    }

    /** NONE 与 authRef 互斥：声明无鉴权却留凭据是配置错误，注册期就该失败。 */
    @Test
    void noneCannotCarryCredentialAtRegistration() {
        AuthScheme.checkCombination(AuthScheme.NONE, "", "HTTP");
        AuthScheme.checkCombination(AuthScheme.NONE, null, "MCP");
        assertThatThrownBy(
                        () ->
                                AuthScheme.checkCombination(
                                        AuthScheme.NONE, "vault:00000000-0000-0000-0000-000000000000", "HTTP"))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure -> {
                            PlatformException error = (PlatformException) failure;
                            assertThat(error.status()).isEqualTo(400);
                            assertThat(error.code()).isEqualTo(AuthScheme.CODE_CONFLICT);
                            assertThat(error.getMessage()).contains("NONE");
                        });
    }

    /** API_KEY 只对 HTTP 有意义，且必须带凭据；BEARER 允许无凭据（改造前的匿名技能形态）。 */
    @Test
    void apiKeyRequiresHttpAdapterAndCredential() {
        AuthScheme.checkCombination(AuthScheme.API_KEY, "vault:x", "HTTP");
        AuthScheme.checkCombination(AuthScheme.BEARER, "", "HTTP");
        assertThatThrownBy(() -> AuthScheme.checkCombination(AuthScheme.API_KEY, "vault:x", "MCP"))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure ->
                                assertThat(((PlatformException) failure).code())
                                        .isEqualTo(AuthScheme.CODE_INVALID));
        assertThatThrownBy(() -> AuthScheme.checkCombination(AuthScheme.API_KEY, " ", "HTTP"))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure ->
                                assertThat(((PlatformException) failure).code())
                                        .isEqualTo(AuthScheme.CODE_CREDENTIAL_MISSING));
    }

    /** 头映射是执行侧唯一出口：每个方式只允许出现自己那一个头，NONE 必须为空表。 */
    @Test
    void mapsEachSchemeToExactlyOneOutboundHeader() {
        String ref = SecretVault.put("s3cret-value", owner());
        assertThat(AuthScheme.headers("API_KEY", ref))
                .containsExactly(Map.entry("X-API-Key", "s3cret-value"));
        assertThat(AuthScheme.headers("BEARER", ref))
                .containsExactly(Map.entry("Authorization", "Bearer s3cret-value"));
        assertThat(AuthScheme.headers(null, ref))
                .containsExactly(Map.entry("Authorization", "Bearer s3cret-value"));
        assertThat(AuthScheme.headers("NONE", "")).isEmpty();
        assertThat(AuthScheme.headers("BEARER", "")).isEmpty();
    }

    /** 凭据解析不到必须抛 401，且不产生任何“无鉴权请求”；不能把 500/IllegalState 漏给调用方。 */
    @Test
    void unresolvableCredentialIsA401InsteadOfAnAnonymousRequest() {
        String missing = "vault:" + UUID.randomUUID();
        assertThatThrownBy(() -> AuthScheme.headers("API_KEY", missing))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure -> {
                            PlatformException error = (PlatformException) failure;
                            assertThat(error.status()).isEqualTo(401);
                            assertThat(error.code()).isEqualTo(AuthScheme.CODE_CREDENTIAL_MISSING);
                        });
        assertThatThrownBy(() -> AuthScheme.headers("API_KEY", ""))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure ->
                                assertThat(((PlatformException) failure).status()).isEqualTo(401));
    }

    /** 已存契约声明 NONE 却带着凭据：运行期是 409 不一致，而不是静默忽略凭据。 */
    @Test
    void storedNoneWithCredentialSurfacesAsConflict() {
        assertThatThrownBy(() -> AuthScheme.headers("NONE", "vault:whatever"))
                .isInstanceOf(PlatformException.class)
                .satisfies(
                        failure -> {
                            PlatformException error = (PlatformException) failure;
                            assertThat(error.status()).isEqualTo(409);
                            assertThat(error.code()).isEqualTo(AuthScheme.CODE_CONFLICT);
                        });
    }

    /** NONE 不接受任何凭据值构造头：这是类型级的不变量，不靠调用方自觉。 */
    @Test
    void noneNeverProducesAHeaderValue() {
        assertThatThrownBy(() -> AuthScheme.NONE.headerValue("s3cret"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthScheme.BEARER.headerValue(" "))
                .isInstanceOf(PlatformException.class);
        assertThat(AuthScheme.API_KEY.headerValue("s3cret")).isEqualTo("s3cret");
    }

    private static Actor owner() {
        return new Actor("tenant-auth", "editor", Set.of("ADMIN"), Map.of());
    }
}
