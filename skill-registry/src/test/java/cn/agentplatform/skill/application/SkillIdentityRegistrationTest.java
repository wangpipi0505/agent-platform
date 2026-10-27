package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.SkillRules;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/**
 * PLAT-12 注册期 identity 声明校验：mode 只允许 PLATFORM_SUBJECT，headers 值只允许主体快照
 * 字段白名单，header 名不得是适配器已占用的保留头——在保存期拒绝，错误配置从未被发布。
 *
 * <p>断言全部落在 {@link SkillCatalog#normalize} 上（与 SkillAuthSchemeRegistrationTest 同一口径）：
 * 它是技能契约进入版本表的唯一入口。未声明 identity 的契约不得出现 identity 键，向后兼容由
 * 该锚点用例与既有出站用例双重锁定。
 */
class SkillIdentityRegistrationTest {
    private static final String TENANT = "tenant-identity";

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

    private static Map<String, Object> body(Map<String, Object> identity) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "HTTP");
        body.put("description", "按主体查询业务对象");
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
        if (identity != null) body.put("identity", identity);
        return body;
    }

    private static Map<String, Object> declaredIdentity(Object headers) {
        return declaredIdentity("PLATFORM_SUBJECT", headers);
    }

    private static Map<String, Object> declaredIdentity(String mode, Object headers) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("mode", mode);
        identity.put("headers", headers);
        return identity;
    }

    private static SkillCatalog catalog() {
        return new SkillCatalog(null, null, null, "http://unused", true);
    }

    private static PlatformException rejected(Map<String, Object> identity) {
        signIn();
        return assertThrows(
                PlatformException.class, () -> catalog().normalize(body(identity)));
    }

    @Test
    void platformSubjectDeclarationIsNormalizedIntoTheContract() {
        signIn();
        var normalized =
                catalog()
                        .normalize(
                                body(
                                        declaredIdentity(
                                                Map.of(
                                                        "X-User-Id", "principalId",
                                                        "X-User-Roles", "roles",
                                                        "X-User-Attributes", "attributes"))));
        assertEquals(
                Map.of(
                        "mode",
                        "PLATFORM_SUBJECT",
                        "headers",
                        Map.of(
                                "X-User-Id", "principalId",
                                "X-User-Roles", "roles",
                                "X-User-Attributes", "attributes")),
                normalized.get("identity"));
    }

    @Test
    void undeclaredIdentityLeavesTheContractUnchanged() {
        signIn();
        var normalized = catalog().normalize(body(null));
        assertFalse(
                normalized.containsKey("identity"),
                "未声明 identity 的契约不得出现该键：出站行为与信封形状零变化");
    }

    @Test
    void unknownModeIsRejectedWithReadableError() {
        var ex = rejected(declaredIdentity("LOCAL_SUBJECT", Map.of("X-User-Id", "principalId")));
        assertEquals(400, ex.status());
        assertEquals(SkillRules.CODE_IDENTITY_MODE_INVALID, ex.code());
        assertTrue(ex.getMessage().contains("LOCAL_SUBJECT"), "错误必须指出收到的非法mode值");
    }

    @Test
    void missingModeIsRejected() {
        var ex = rejected(Map.of("headers", Map.of("X-User-Id", "principalId")));
        assertEquals(400, ex.status());
        assertEquals(SkillRules.CODE_IDENTITY_MODE_INVALID, ex.code());
    }

    @Test
    void headerValueOutsideSnapshotWhitelistIsRejected() {
        // 任意 header 注入防线：值只能是快照字段，契约不能把请求头当自由写通道（如把 Authorization、
        // 远端内部字段或调用方可控参数映射进头）。
        var ex = rejected(declaredIdentity(Map.of("X-User-Email", "email")));
        assertEquals(400, ex.status());
        assertEquals(SkillRules.CODE_IDENTITY_HEADER_INVALID, ex.code());
        assertTrue(ex.getMessage().contains("X-User-Email"), "错误必须指出是哪个header声明非法");
    }

    @Test
    void reservedHeaderNamesAreRejected() {
        // 与 AuthScheme/适配器固定头冲突的声明会让同一请求发出两个同名头，注册期直接拒绝。
        for (String name : List.of("Authorization", "X-API-Key", "Idempotency-Key")) {
            var ex = rejected(declaredIdentity(Map.of(name, "principalId")));
            assertEquals(400, ex.status(), name);
            assertEquals(SkillRules.CODE_IDENTITY_HEADER_INVALID, ex.code(), name);
        }
    }

    @Test
    void malformedHeaderNameIsRejected() {
        var ex = rejected(declaredIdentity(Map.of("X User Id", "principalId")));
        assertEquals(400, ex.status());
        assertEquals(SkillRules.CODE_IDENTITY_HEADER_INVALID, ex.code());
    }

    @Test
    void nonStringOrEmptyHeadersDeclarationIsRejected() {
        assertEquals(
                400,
                rejected(declaredIdentity(List.of("X-User-Id"))).status(),
                "headers必须是字符串到快照字段的对象");
        assertEquals(400, rejected(declaredIdentity(Map.of())).status(), "空headers是无意义声明");
    }

    @Test
    void identityIsRejectedForAdaptersWithoutOutboundHeaderPoints() {
        signIn();
        var body = body(declaredIdentity(Map.of("X-User-Id", "principalId")));
        body.put("type", "MCP");
        body.put("toolName", "onto.query");
        var ex = assertThrows(PlatformException.class, () -> catalog().normalize(body));
        assertEquals(400, ex.status());
        assertEquals(SkillRules.CODE_IDENTITY_UNSUPPORTED, ex.code());
        assertTrue(ex.getMessage().contains("HTTP"), "错误必须说明仅HTTP适配器有出站头注入点");
    }
}
