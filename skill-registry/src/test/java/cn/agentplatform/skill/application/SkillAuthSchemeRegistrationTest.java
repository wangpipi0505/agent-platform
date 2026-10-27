package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.auth.AuthScheme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/**
 * PLAT-11 注册期契约校验：authScheme 的归一化、与 authRef 的组合规则、legacy 收紧开关。
 *
 * <p>这些断言全部落在 {@link SkillCatalog#normalize} 上——它是技能契约进入版本表的唯一入口，
 * 在这里拒绝才意味着“错误配置从未被发布”，而不是等到第一次出站调用才暴露。
 */
class SkillAuthSchemeRegistrationTest {
    private static final String TENANT = "tenant-auth";

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

    private static Map<String, Object> body(Object scheme, String authRef) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "HTTP");
        body.put("description", "按主体查询本体对象");
        body.put("endpoint", "https://onto.example.test/onto/query");
        body.put("httpMethod", "POST");
        body.put("timeoutMs", 2000);
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        if (scheme != null) body.put("authScheme", scheme);
        if (authRef != null) body.put("authRef", authRef);
        return body;
    }

    private static SkillCatalog catalog(boolean legacyBearer) {
        return new SkillCatalog(null, null, null, "http://unused", legacyBearer);
    }

    @Test
    void apiKeyIsNormalizedIntoAnExplicitContractValue() {
        signIn();
        var normalized =
                catalog(true)
                        .normalize(
                                body("API_KEY", "vault:0b9f6f2e-1b0c-4b8e-9d6f-1234567890ab"));
        assertEquals("API_KEY", normalized.get(AuthScheme.FIELD));
        assertEquals("vault:0b9f6f2e-1b0c-4b8e-9d6f-1234567890ab", normalized.get("authRef"));
    }

    @Test
    void noneWithoutCredentialIsAccepted() {
        signIn();
        var normalized = catalog(true).normalize(body("NONE", null));
        assertEquals("NONE", normalized.get(AuthScheme.FIELD));
        assertEquals("", normalized.get("authRef"));
    }

    @Test
    void noneWithCredentialIsRejectedWithBusinessError() {
        signIn();
        var ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog(true).normalize(body("NONE", "vault:0b9f6f2e-1b0c-4b8e-9d6f-1234567890ab")));
        assertEquals(400, ex.status());
        assertEquals(AuthScheme.CODE_CONFLICT, ex.code());
        assertFalse(ex.getMessage().isBlank(), "拒绝必须带可展示的userMessage");
    }

    @Test
    void unknownSchemeIsRejectedInsteadOfSilentlyTreatedAsBearer() {
        signIn();
        var ex = assertThrows(PlatformException.class, () -> catalog(true).normalize(body("BASIC", null)));
        assertEquals(400, ex.status());
        assertEquals(AuthScheme.CODE_INVALID, ex.code());
        assertTrue(ex.getMessage().contains("BASIC"), "错误必须指出收到的非法值");
    }

    @Test
    void undeclaredSchemeDefaultsToBearerWhileLegacyWindowIsOpen() {
        signIn();
        var normalized = catalog(true).normalize(body(null, "vault:0b9f6f2e-1b0c-4b8e-9d6f-1234567890ab"));
        assertEquals("BEARER", normalized.get(AuthScheme.FIELD));
    }

    @Test
    void undeclaredSchemeIsRejectedOnceLegacyBearerIsTightened() {
        signIn();
        var ex = assertThrows(PlatformException.class, () -> catalog(false).normalize(body(null, null)));
        assertEquals(400, ex.status());
        assertEquals(AuthScheme.CODE_REQUIRED, ex.code());
    }

    @Test
    void apiKeyIsRejectedForNonHttpAdapterBecauseOnlyHttpHonoursTheField() {
        signIn();
        var body = body("API_KEY", "vault:0b9f6f2e-1b0c-4b8e-9d6f-1234567890ab");
        body.put("type", "MCP");
        body.put("toolName", "onto.query");
        body.put("inputSchema", Map.of("type", "object"));
        var ex = assertThrows(PlatformException.class, () -> catalog(true).normalize(body));
        assertEquals(400, ex.status());
        assertEquals(AuthScheme.CODE_INVALID, ex.code());
        assertTrue(ex.getMessage().contains("HTTP"), "错误必须说明仅HTTP支持该方式");
    }
}
