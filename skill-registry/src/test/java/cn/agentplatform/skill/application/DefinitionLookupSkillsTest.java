package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SemanticDefinitionClient;
import cn.agentplatform.skill.infra.SkillAdapters;
import cn.agentplatform.skill.infra.SkillClients;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SEM-06.2 技能单测：固定版本、运行内一致性与预算都必须显式失败，不能静默换成参数版本或
 * 把超预算截断成“查完了”。这里不触网，避免把网络可用性混进契约语义。
 */
class DefinitionLookupSkillsTest {
    private static final UUID RELEASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void invokeUsesPinnedReleaseAndReportsBudget() {
        var definitions = new StubDefinitions(1);
        var skills = new DefinitionLookupSkills(definitions);
        var raw = skills.invoke(null, contract(RELEASE), Map.of("limit", 5), "key", 1L, false, "run-1", 1L);

        assertEquals(RELEASE, definitions.last);
        assertEquals(1, definitions.calls);
        assertEquals("run-1", "run-1");
        var structured = raw.structured();
        assertEquals(RELEASE.toString(), structured.get("releaseRef").get("releaseId").asText());
        assertEquals(1, structured.get("budget").get("callsUsed").asInt());
        assertEquals(8, structured.get("budget").get("maxCalls").asInt());
        assertTrue(raw.summary().startsWith("固定版本定义查询"));
    }

    @Test
    void rejectsMissingFixedRelease() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                new DefinitionLookupSkills(new StubDefinitions(0))
                                        .invoke(null, Map.of(), Map.of(), "key", 1L, false, "run", 1L));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_REF_INVALID", failure.code());
    }

    @Test
    void rejectsArgumentThatContradictsPinnedRelease() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                new DefinitionLookupSkills(new StubDefinitions(0))
                                        .invoke(
                                                null,
                                                contract(RELEASE),
                                                Map.of("releaseId", OTHER.toString()),
                                                "key",
                                                1L,
                                                false,
                                                "run",
                                                1L));
        assertEquals("SEMANTIC_REF_INVALID", failure.code());
    }

    @Test
    void rejectsReleaseChangeWithinSameRun() {
        var definitions = new StubDefinitions(0);
        var skills = new DefinitionLookupSkills(definitions);
        skills.invoke(null, contract(RELEASE), Map.of(), "key", 1L, false, "run", 1L);

        var failure =
                assertThrows(
                        PlatformException.class,
                        () -> skills.invoke(null, contract(OTHER), Map.of(), "key", 2L, false, "run", 1L));
        assertEquals(409, failure.status());
        assertEquals("SEMANTIC_REF_INVALID", failure.code());
        assertEquals(1, definitions.calls);
    }

    @Test
    void rejectsUnknownArgumentWithoutCallingSource() {
        var definitions = new StubDefinitions(0);
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                new DefinitionLookupSkills(definitions)
                                        .invoke(
                                                null,
                                                contract(RELEASE),
                                                Map.of("secret", "x"),
                                                "key",
                                                1L,
                                                false,
                                                "run",
                                                1L));
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", failure.code());
        assertEquals(0, definitions.calls);
    }

    @Test
    void enforcesCallBudgetPerRun() {
        var skills = new DefinitionLookupSkills(new StubDefinitions(0));
        for (int i = 0; i < DefinitionLookupSkills.MAX_CALLS; i++)
            skills.invoke(null, contract(RELEASE), Map.of(), "key", i, false, "run", 1L);

        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                skills.invoke(
                                        null, contract(RELEASE), Map.of(), "key", 99, false, "run", 1L));
        assertEquals(400, failure.status());
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", failure.code());
    }

    @Test
    void enforcesItemBudgetPerRun() {
        var definitions = new StubDefinitions(150);
        var skills = new DefinitionLookupSkills(definitions);
        skills.invoke(null, contract(RELEASE), Map.of(), "key", 1L, false, "run", 1L);
        skills.invoke(null, contract(RELEASE), Map.of(), "key", 2L, false, "run", 1L);

        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                skills.invoke(
                                        null, contract(RELEASE), Map.of(), "key", 3L, false, "run", 1L));
        assertEquals(400, failure.status());
        assertEquals("SEMANTIC_FIELD_OUT_OF_RANGE", failure.code());
    }

    @Test
    void templateIsADraftableRegistrationBody() {
        Map<String, Object> template = DefinitionLookupSkills.template();
        assertEquals("semantic-definition-lookup", template.get("code"));
        assertEquals("SEMANTIC_DEFINITION", template.get("type"));
        assertEquals("SYNC", template.get("invocationKind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> execution = (Map<String, Object>) template.get("execution");
        assertEquals(Boolean.TRUE, execution.get("readonly"));
        for (String flag : List.of("readonly", "sideEffect", "idempotent", "cancellable"))
            assertInstanceOf(Boolean.class, execution.get(flag));
        assertTrue(template.containsKey("inputSchema"));
        assertTrue(template.containsKey("outputSchema"));
        assertTrue(template.containsKey("guard"));
        // 模板要能直接走登记门禁：Schema 方言与端点校验都必须在注册期通过，
        // 否则“只产草稿”会在管理员点创建时才暴露非法形状。
        assertDoesNotThrow(
                () -> cn.agentplatform.skill.domain.SkillRules.normalizedSchema(template.get("inputSchema")));
        assertDoesNotThrow(
                () -> cn.agentplatform.skill.domain.SkillRules.endpoint((String) template.get("endpoint")));
    }

    private static Map<String, Object> contract(UUID releaseId) {
        return Map.of("execution", Map.of("releaseId", releaseId.toString()));
    }

    /** 非静态内部类：避免被类路径扫描当候选组件（同 OntologyManifestClientTest.StubClients）。 */
    private final class StubDefinitions extends SemanticDefinitionClient {
        private final int items;
        private int calls;
        private UUID last;

        private StubDefinitions(int items) {
            super(new SkillClients(), "http://or");
            this.items = items;
        }

        @Override
        public Projection query(UUID releaseId, Map<String, Object> selection) {
            calls++;
            last = releaseId;
            return projection(items);
        }

        private static Projection projection(int items) {
            var definitions = new LinkedHashMap<String, List<Map<String, Object>>>();
            for (String name : List.of("objects", "properties", "relations", "rules", "actions"))
                definitions.put(name, List.of());
            var objects = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < items; i++)
                objects.add(
                        Map.of(
                                "ref",
                                Map.of("kind", "object-type", "id", "t-" + i, "name", "T" + i)));
            definitions.put("objects", List.copyOf(objects));
            var counts = new LinkedHashMap<String, Object>();
            counts.put("objects", items);
            for (String name : List.of("properties", "relations", "rules", "actions"))
                counts.put(name, 0);
            var ref = new LinkedHashMap<String, Object>();
            ref.put("spaceCode", "space-a");
            ref.put("releaseId", RELEASE.toString());
            ref.put("version", "v1");
            ref.put("contentDigest", "b".repeat(64));
            return new Projection(
                    ref, definitions, counts, items == 0, false, null, "c".repeat(64));
        }
    }
}
