package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

/**
 * R07/SCN-010：expectSelected恰为空字符串表示无工具预期（实际未选工具才算对，意外选任一工具即失败）；
 * 缺失/null/非字符串/仅空白仍拒绝；原非空工具编码、expectArgs参数断言、分母与0.8门槛保持。
 */
class SkillAcceptanceNegativeCaseTest {
    private static final String CODE = "query_tool";
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final SkillCatalog catalog = mock(SkillCatalog.class);
    private final SkillClients clients = mock(SkillClients.class);
    private final SkillGovernance governance =
            new SkillGovernance(db, catalog, mock(SkillAdapters.class), clients, "http://model.test", "http://engine.test");

    @BeforeEach
    void signIn() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", "acceptance_neg")
                        .claim("roles", List.of("EDITOR"))
                        .claim("attributes", Map.of())
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        when(catalog.version(eq(CODE), anyInt()))
                .thenReturn(
                        Map.of(
                                "description", "查询",
                                // 与真实DB读回一致：契约字段为Map而非JsonNode（normalizedSchema只接受Map）。
                                "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties", Map.of("region", Map.of("type", "string"))),
                                "skillVersionId", 1L,
                                "sha256", "a".repeat(64),
                                "semanticDigest", "b".repeat(64),
                                "digestVersion", "v1"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Map<String, Object> run(Object expectSelected, Map<String, Object> expectArgs, String... toolNames) {
        var calls = new ArrayList<Map<String, Object>>();
        for (String name : toolNames) calls.add(Map.of("name", name, "arguments", Map.of("region", "A")));
        when(clients.post(eq("http://model.test/internal/model/chat"), anyMap()))
                .thenReturn(Json.tree(Map.of("toolCalls", calls)));
        var item = new LinkedHashMap<String, Object>();
        item.put("question", "查询A区域");
        if (expectSelected != null || expectArgs != null) {
            // null同时表示“缺失该字段”（负例）与“不填预期”（不会发生：正例必带预期或空串）。
            if (expectSelected != null) item.put("expectSelected", expectSelected);
            if (expectArgs != null) item.put("expectArgs", expectArgs);
        }
        var report =
                governance.acceptance(
                        CODE, 1, Map.of("modelEntryId", 1, "cases", List.of(new LinkedHashMap<>(item))));
        @SuppressWarnings("unchecked")
        var results = (List<Map<String, Object>>) report.get("cases");
        assertEquals(1, results.size());
        assertTrue((Boolean) results.getFirst().get("pass") == (Boolean) report.get("pass"));
        return report;
    }

    private PlatformException invalid(Object expectSelected) {
        var item = new LinkedHashMap<String, Object>();
        item.put("question", "查询A区域");
        if (expectSelected != null) item.put("expectSelected", expectSelected);
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> governance.acceptance(CODE, 1, Map.of("modelEntryId", 1, "cases", List.of(item))));
        assertEquals(400, failure.status());
        assertEquals("INVALID_ACCEPTANCE", failure.code());
        return failure;
    }

    @Test
    void 正常精确选工具原行为保持() {
        var report = run(CODE, null, CODE);
        assertTrue((Boolean) report.get("pass"));
        assertEquals(1.0, (Double) report.get("hitRate"));
    }

    @Test
    void 无工具正例空字符串预期通过() {
        var report = run("", null);
        assertTrue((Boolean) report.get("pass"));
        assertEquals("", ((Map<?, ?>) ((List<?>) report.get("cases")).getFirst()).get("selected"));
    }

    @Test
    void 无工具预期但意外选任一工具计失败() {
        assertFalse((Boolean) run("", null, CODE).get("pass"));
        assertFalse((Boolean) run("", null, "unrelated_tool").get("pass"));
    }

    @Test
    void 原expectArgs参数断言保持() {
        // 参数匹配：通过。
        assertTrue((Boolean) run(CODE, Map.of("region", "A"), CODE).get("pass"));
        // 参数不匹配：argsOk失败计入，整体不通过（分母与门槛语义不变）。
        var report = run(CODE, Map.of("region", "B"), CODE);
        assertFalse((Boolean) report.get("pass"));
        assertFalse((Boolean) ((Map<?, ?>) ((List<?>) report.get("cases")).getFirst()).get("argsOk"));
    }

    @Test
    void 缺失null非字符串与仅空白仍拒绝() {
        assertTrue(invalid(null).getMessage().contains("expectSelected"));
        assertTrue(invalid("  ").getMessage().contains("仅空白无效"));
        var nonString = invalid(36);
        assertTrue(nonString.getMessage().contains("expectSelected"));
        // null字段（显式null）与非字符串同走“缺少或无效字段”。
        var explicitNull = new LinkedHashMap<String, Object>();
        explicitNull.put("question", "q");
        explicitNull.put("expectSelected", null);
        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> governance.acceptance(CODE, 1, Map.of("modelEntryId", 1, "cases", List.of(explicitNull))));
        assertEquals("INVALID_ACCEPTANCE", failure.code());
    }
}
