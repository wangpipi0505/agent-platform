package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaWorkBudget;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.AlgorithmAdapter;
import cn.agentplatform.skill.infra.ArtifactStore;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * PLAT-07：预算拒绝必须降级为顶层校验并显式告警，而不是把正确输出判成 OUTPUT_INVALID。
 *
 * <p>不变量：预算本身仍是安全边界。DEGRADED 只降“实例工作量超限”这一种拒绝，降到根 type 与根
 * required 两条顶层检查；类型不符、必填缺失、引用非法、Schema 展开超限一律照常失败。STRICT 保留
 * 原异常语义并追加可核对的数字，使既有按前缀识别 Budget 拒绝的调用方不受影响。
 */
class SchemaDegradedValidationTest {
    /**
     * 300 个简单属性：root 权重 305，每个属性权重 5，合计 expandedWork=1805（远低于展开上限 8192）。
     * 3000 字段实例有 3001 个节点，estimatedWork=1805×3001=5,416,805，稳超实例上限 2,000,000。
     */
    static Map<String, Object> wideSchema() {
        var properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 300; i++) properties.put("p" + i, Map.of("type", "string"));
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    static Map<String, Object> wideInstance() {
        var instance = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 3000; i++) instance.put("f" + i, "v");
        return instance;
    }

    static Map<String, Object> smallSchema() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("required", List.of("value"));
        schema.put("properties", Map.of("value", Map.of("type", "integer")));
        return schema;
    }

    static Map<String, Object> contract(Map<String, Object> schema) {
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("outputMaxBytes", 4 * 1024 * 1024);
        contract.put("outputSchema", schema);
        return contract;
    }

    static ByteArrayInputStream input(Object payload) {
        return new ByteArrayInputStream(Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    static long expectedWork() {
        var plan = SchemaWorkBudget.inspect(Json.tree(wideSchema()));
        return plan.admission(Json.tree(wideInstance()), 0, SchemaWorkBudget.MAX_INSTANCE_WORK)
                .estimatedWork();
    }

    @BeforeEach
    void verifiedActor() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("skill-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void degradedModeAcceptsOverBudgetInstanceAndReportsWarning() {
        long expected = expectedWork();
        assertTrue(expected > 2_000_000L, "夹具必须真的超预算：" + expected);
        var admission =
                SkillRules.validate(
                        wideSchema(), wideInstance(), SkillRules.SchemaMode.DEGRADED);
        assertFalse(admission.admitted(), "超预算必须在结论上可见，不能静默通过");
        assertEquals(expected, admission.estimatedWork());
        var validation = SkillAdapters.Validation.of(admission);
        assertEquals("DEGRADED", validation.mode());
        assertEquals("SCHEMA_BUDGET_EXCEEDED", validation.warning());
        assertEquals(expected, validation.estimatedWork());
        assertEquals("SCHEMA_BUDGET_EXCEEDED", validation.asMap().get("warning"));
    }

    @Test
    void strictModeStillRejectsOverBudgetInstanceWithNumbers() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SkillRules.validate(
                                        wideSchema(),
                                        wideInstance(),
                                        SkillRules.SchemaMode.STRICT));
        assertTrue(
                error.getMessage().startsWith("Schema预算拒绝：本次实例的校验工作量超过上限"),
                error.getMessage());
        assertTrue(error.getMessage().contains("estimatedWork=" + expectedWork()), error.getMessage());
        assertTrue(error.getMessage().contains("上限=2000000"), error.getMessage());
        // 无 mode 的旧重载保持 STRICT，回调/治理等既有调用点语义不变。
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.validate(wideSchema(), wideInstance()));
    }

    @Test
    void degradedStillRejectsMissingRootRequired() {
        var schema = new LinkedHashMap<String, Object>(wideSchema());
        schema.put("required", List.of("must"));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SkillRules.validate(
                                        schema, wideInstance(), SkillRules.SchemaMode.DEGRADED));
        assertTrue(error.getMessage().contains("缺少根必填字段"), error.getMessage());
    }

    @Test
    void degradedStillRejectsRootTypeMismatch() {
        var arrayInstance = new ArrayList<Object>();
        for (int i = 0; i < 3000; i++) arrayInstance.add("v");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SkillRules.validate(
                                        wideSchema(),
                                        arrayInstance,
                                        SkillRules.SchemaMode.DEGRADED));
        assertTrue(error.getMessage().contains("根类型"), error.getMessage());
    }

    @Test
    void smallInstanceIsAdmittedAsOkWithoutWarning() {
        var admission =
                SkillRules.validate(
                        smallSchema(), Map.of("value", 7), SkillRules.SchemaMode.DEGRADED);
        assertTrue(admission.admitted());
        var validation = SkillAdapters.Validation.of(admission);
        assertEquals("OK", validation.mode());
        assertNull(validation.warning());
        assertFalse(validation.asMap().containsKey("warning"), "通过时不应带空告警键");
    }

    @Test
    void degradedStillRejectsCommonSchemaViolation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillRules.validate(
                                smallSchema(),
                                Map.of("value", "x"),
                                SkillRules.SchemaMode.DEGRADED));
    }

    @Test
    void degradedStillRejectsSecondaryResourceBudget() {
        // 主维度之外的预算（正则程序 × 展开 × 字符数）保护的是校验自身成本，DEGRADED 不得跳过。
        var schema =
                Map.of(
                        "type", "object",
                        "properties",
                                Map.of("value", Map.of("type", "string", "pattern", "^a+$")));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SkillRules.validate(
                                        schema,
                                        Map.of("value", "a".repeat(200000)),
                                        SkillRules.SchemaMode.DEGRADED));
        assertTrue(error.getMessage().contains("regexWork="), error.getMessage());
    }

    @Test
    void schemaModeSettingsAreInjectedAndRangeChecked() {
        var range =
                assertThrows(
                        IllegalStateException.class,
                        () -> new SkillAdapters(null, null, null, null, "DEGRADED", 9000));
        assertTrue(range.getMessage().contains("1..8192"), range.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> new SkillAdapters(null, null, null, null, "DEGRADED", 0));
        assertThrows(
                IllegalStateException.class,
                () -> new SkillAdapters(null, null, null, null, "DEGRADED", 8193));
        var mode =
                assertThrows(
                        IllegalStateException.class,
                        () -> new SkillAdapters(null, null, null, null, "LOOSE", 8192));
        assertTrue(mode.getMessage().contains("STRICT"), mode.getMessage());
        assertTrue(mode.getMessage().contains("DEGRADED"), mode.getMessage());
        assertDoesNotThrow(() -> new SkillAdapters(null, null, null, null, "STRICT", 1));
    }

    @Test
    void adapterCarriesDegradedValidationIntoRaw() throws Exception {
        var adapters =
                new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
        var raw = adapters.read(input(wideInstance()), contract(wideSchema()), 1L);
        assertNotNull(raw.structured(), "降级后仍应带出结构化结果");
        assertNotNull(raw.validation(), "降级必须随 Raw 一起返回");
        assertEquals("DEGRADED", raw.validation().mode());
        assertEquals("SCHEMA_BUDGET_EXCEEDED", raw.validation().warning());
        assertEquals(expectedWork(), raw.validation().estimatedWork());
    }

    @Test
    void adapterMapsTopLevelFailureToOutputInvalid() {
        var schema = new LinkedHashMap<String, Object>(wideSchema());
        schema.put("required", List.of("must"));
        var adapters =
                new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.read(input(wideInstance()), contract(schema), 1L));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals("外部响应不符合输出Schema", failure.getMessage());
    }

    @Test
    void adapterStrictModeRejectsOverBudget() {
        var strict = new SkillAdapters(null, null, null, null, "STRICT", 8192);
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> strict.read(input(wideInstance()), contract(wideSchema()), 1L));
        assertEquals("OUTPUT_INVALID", failure.semantic);
    }

    @Test
    void adapterOkPathHasNoWarning() throws Exception {
        var adapters =
                new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
        var raw = adapters.read(input(Map.of("value", 7)), contract(smallSchema()), 1L);
        assertNotNull(raw.validation());
        assertEquals("OK", raw.validation().mode());
        assertNull(raw.validation().warning());
    }

    @Test
    void loweredExpansionLimitIsNotDowngraded() {
        // agent.schema.max-expanded-work 只能调低；调低后展开拒绝仍是硬失败，DEGRADED 只降实例预算。
        var lowered = new SkillAdapters(null, null, null, null, "DEGRADED", 1);
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> lowered.read(input(wideInstance()), contract(wideSchema()), 1L));
        assertEquals("OUTPUT_INVALID", failure.semantic);
    }

    @Test
    void envelopeCarriesValidationSoDegradationIsVisible() throws Exception {
        var adapters =
                new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
        var raw = adapters.read(input(wideInstance()), contract(wideSchema()), 1L);
        var execution =
                new SkillExecution(
                        mock(JdbcTemplate.class),
                        mock(PlatformTransactionManager.class),
                        mock(SkillCatalog.class),
                        null,
                        mock(SkillClients.class),
                        "http://unused");
        var envelope =
                execution.envelope(
                        Map.of(),
                        Map.of("code", "wide"),
                        "OK",
                        null,
                        null,
                        null,
                        null,
                        raw,
                        false,
                        Map.of());
        @SuppressWarnings("unchecked")
        var validation = (Map<String, Object>) envelope.get("validation");
        assertNotNull(validation, "降级必须出现在工具结果 envelope 中");
        assertEquals("DEGRADED", validation.get("mode"));
        assertEquals("SCHEMA_BUDGET_EXCEEDED", validation.get("warning"));
        assertEquals(expectedWork(), ((Number) validation.get("estimatedWork")).longValue());
    }
}
