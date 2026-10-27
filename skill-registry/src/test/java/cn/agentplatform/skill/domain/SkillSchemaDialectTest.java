package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.application.SkillCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/**
 * PLAT-08b 技能契约 Schema 方言：技能侧与 App 侧共用 admin-common 的 SchemaDialect，而不是各写一套
 * type 白名单。
 *
 * <p>断言落在 {@link SkillCatalog#normalize} 的返回值上：它是技能契约进入版本表的唯一入口，
 * {@code create}/{@code save} 直接把该返回值写进 {@code skill_version.contract}（jsonb），所以
 * 这里的"归一后形状"就是落库形状，不是内存里另算的一份。
 *
 * <p>规范形只有两种：单一 type 字符串，或 {@code [T,"null"]}（null 恒在最后）。{@code nullable:true}
 * 只是输入写法，归一后必然消失并转成联合写法——这是 PLAT-08a 已拍板的方言定义，技能侧不再保留第二种
 * 可空写法，否则读回契约时同一语义会有两种 JSON 形状，摘要与前端 diff 都随之漂移。
 */
class SkillSchemaDialectTest {
    private static final String TENANT = "tenant-dialect";

    @BeforeEach
    void signIn() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("EDITOR"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static SkillCatalog catalog() {
        // 只有归一/校验参与，不触库；与 SkillAuthSchemeRegistrationTest 用同一个纯内存构造。
        return new SkillCatalog(null, null, null, "http://unused");
    }

    private static Map<String, Object> contract(Object inputSchema, Object outputSchema) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "HTTP");
        body.put("description", "按主体查询本体对象");
        body.put("endpoint", "https://onto.example.test/onto/query");
        body.put("httpMethod", "POST");
        body.put("timeoutMs", 2000);
        body.put("inputSchema", inputSchema);
        body.put("outputSchema", outputSchema);
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        body.put("guard", Map.of("roles", List.of("READER"), "attributes", Map.of()));
        return body;
    }

    private static Map<String, Object> input(Map<String, Object> body) {
        return SkillCatalog.object(SkillCatalog.object(catalog().normalize(body)).get("inputSchema"));
    }

    @Test
    void unionWithNullIsRegisteredAndReadBackAsArrayType() {
        var stored = input(contract(Map.of("type", List.of("string", "null")), Map.of("type", "object")));
        assertEquals(List.of("string", "null"), stored.get("type"));
    }

    @Test
    void nullableFlagIsAcceptedAndStoredInTheSingleCanonicalForm() {
        var stored =
                input(
                        contract(
                                Map.of("type", "string", "nullable", true),
                                Map.of("type", "object")));
        // nullable:true 与 type:["string","null"] 是同一语义；落库只保留后者，读回不再有两种写法。
        assertEquals(List.of("string", "null"), stored.get("type"));
        assertFalse(stored.containsKey("nullable"), "归一后 nullable 必须消失");
    }

    @Test
    void nullIsAlwaysMovedToTheEndOfTheUnion() {
        var stored = input(contract(Map.of("type", List.of("null", "string")), Map.of("type", "object")));
        assertEquals(List.of("string", "null"), stored.get("type"), "规范形固定把 null 排在最后");
    }

    @Test
    void multipleConcreteTypesAreRejectedWithJsonPointer() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                catalog()
                                        .normalize(
                                                contract(
                                                        Map.of("type", List.of("string", "object")),
                                                        Map.of("type", "object"))));
        assertEquals(400, failure.status());
        assertEquals("INVALID_INPUT", failure.code());
        assertTrue(failure.getMessage().contains("Schema"), "前端依赖 Schema 关键字");
        assertTrue(failure.getMessage().contains("/type"), "必须带 JSON Pointer 定位字段");
        assertTrue(failure.getMessage().contains("多具体类型"), "必须说明拒绝的是多具体类型联合");
    }

    @Test
    void remoteReferenceIsRejectedWithJsonPointer() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                catalog()
                                        .normalize(
                                                contract(
                                                        Map.of("$ref", "https://evil.example/x.json"),
                                                        Map.of("type", "object"))));
        assertEquals(400, failure.status());
        assertTrue(failure.getMessage().contains("/$ref"), "必须带 JSON Pointer 定位引用位置");
        assertTrue(
                failure.getMessage().contains("https://evil.example/x.json"),
                "必须指出被拒绝的远程引用");
    }

    @Test
    void legacyObjectSchemaKeepsWorkingUnchanged() {
        var output = new LinkedHashMap<String, Object>();
        output.put("type", "object");
        var stored =
                SkillCatalog.object(
                        SkillCatalog.object(
                                        catalog()
                                                .normalize(
                                                        contract(
                                                                Map.of("type", "object"), output)))
                                .get("outputSchema"));
        assertEquals(Map.of("type", "object"), stored, "旧写法不得改写");
        // 执行期入口仍可编译旧写法：归一只是注册期的写入约束，不改变已发布契约的执行语义。
        assertDoesNotThrow(() -> SkillRules.validate(Map.of("type", "object"), Map.of()));
    }
}
