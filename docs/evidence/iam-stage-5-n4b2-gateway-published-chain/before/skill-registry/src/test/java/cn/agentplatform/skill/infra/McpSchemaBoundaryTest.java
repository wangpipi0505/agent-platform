package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.schema.SchemaWorkBudget;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** Network reads are counted on an actual separate HTTP route of the MCP test peer. */
class McpSchemaBoundaryTest {
    McpAdapterTest fixture;

    @BeforeEach
    void start() throws Exception {
        fixture = new McpAdapterTest();
        fixture.start();
    }

    @AfterEach
    void stop() throws Exception {
        fixture.stop();
    }

    Map<String, Object> schema() {
        return new LinkedHashMap<>(fixture.peer.inputSchema);
    }

    void rejectedWithoutExternalRead() {
        SkillAdapters.Failure failure = null;
        try {
            fixture.invoke(false);
        } catch (SkillAdapters.Failure actual) {
            failure = actual;
        }
        assertEquals(
                0, fixture.peer.schemaReads.get(), "untrusted JSON Schema must never read HTTP");
        assertEquals(0, fixture.peer.calls.get(), "unusable schema must fail before tools/call");
        assertNotNull(failure);
        assertEquals("OUTPUT_INVALID", failure.semantic);
    }

    @Test
    void externalDynamicRefCannotFetchSchema() {
        var s = schema();
        s.put("$dynamicRef", fixture.peer.schemaUrl());
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void externalRecursiveRefCannotFetchSchema() {
        var s = schema();
        s.put("$recursiveRef", fixture.peer.schemaUrl());
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void localRecursiveRefIsExplicitlyUnsupportedInsteadOfIgnored() {
        var s = schema();
        s.put("$recursiveRef", "#");
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void idRebasingCannotCauseFragmentRefToLoadRemoteDocument() {
        var s = schema();
        s.put(
                "properties",
                Map.of(
                        "region",
                        Map.of(
                                "$id",
                                fixture.peer.schemaUrl() + "/child",
                                "$ref",
                                "#/$defs/missing")));
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void unknownMetaSchemaCannotReachNetwork() {
        var s = schema();
        s.put("$schema", fixture.peer.schemaUrl());
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void unusedMissingLocalDefinitionIsRejectedBeforeCallingTool() {
        var s = schema();
        s.put("$defs", Map.of("unused", Map.of("$ref", "#/$defs/missing")));
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @Test
    void unusedExternalDefinitionIsRejectedWithoutNetwork() {
        var s = schema();
        s.put("$defs", Map.of("unused", Map.of("$ref", fixture.peer.schemaUrl())));
        fixture.peer.inputSchema = s;
        rejectedWithoutExternalRead();
    }

    @ParameterizedTest
    @ValueSource(strings = {"const", "default", "enum", "examples"})
    void literalRefValuesAreInstanceDataNotSchema(String keyword) {
        var s = schema();
        Object literal =
                Map.of(
                        "$ref",
                        fixture.peer.schemaUrl(),
                        "$dynamicRef",
                        "file:///untrusted-instance-data");
        s.put(
                "properties",
                Map.of(
                        "region",
                        Map.of("type", "string"),
                        "optional",
                        Map.of(
                                keyword,
                                Set.of("enum", "examples").contains(keyword)
                                        ? List.of(literal)
                                        : literal)));
        fixture.peer.inputSchema = s;
        assertEquals(
                7,
                assertDoesNotThrow(() -> fixture.invoke(false)).structured().path("value").asInt());
        assertEquals(1, fixture.peer.calls.get());
        assertEquals(0, fixture.peer.schemaReads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$ref", "$dynamicRef", "$recursiveRef"})
    void literalPropertyNamesAreNotSchemaReferenceKeywords(String field) {
        var s = schema();
        s.put(
                "properties",
                Map.of(
                        "region",
                        Map.of("type", "string"),
                        field,
                        Map.of("type", "string", "default", "literal")));
        fixture.peer.inputSchema = s;
        assertEquals(
                7,
                assertDoesNotThrow(() -> fixture.invoke(false)).structured().path("value").asInt());
        assertEquals(0, fixture.peer.schemaReads.get());
    }

    @Test
    void validLocalDefinitionRemainsUsable() {
        var s = schema();
        s.put("$defs", Map.of("region", Map.of("type", "string", "pattern", "^A$")));
        s.put("properties", Map.of("region", Map.of("$ref", "#/$defs/region")));
        fixture.peer.inputSchema = s;
        assertEquals(
                7,
                assertDoesNotThrow(() -> fixture.invoke(false)).structured().path("value").asInt());
        assertEquals(0, fixture.peer.schemaReads.get());
    }

    static Map<String, Object> overBudgetSchema() {
        var defs = new LinkedHashMap<String, Object>();
        defs.put("n0", Map.of("type", "object"));
        for (int i = 1; i <= 8; i++) {
            var reference = Map.of("$ref", "#/$defs/n" + (i - 1));
            defs.put("n" + i, Map.of("allOf", List.of(reference, reference, reference)));
        }
        return Map.of("type", "object", "$defs", defs, "$ref", "#/$defs/n8");
    }

    @Test
    void schemaExpansionIsRejectedBeforeBusinessAndWithoutExternalReads() {
        // First assert the pure entry rejects; this graph never reaches validator construction.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaWorkBudget.check(
                                cn.agentplatform.common.Json.tree(overBudgetSchema())));
        fixture.peer.inputSchema = overBudgetSchema();
        rejectedWithoutExternalRead();
    }

    @Test
    void unsupportedRegexDoesNotReachBusinessOrFallbackToJdk() {
        fixture.peer.inputSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("region", Map.of("type", "string", "pattern", "(?=A)A")));
        rejectedWithoutExternalRead();
    }

    @Test
    void localAnchorsKeepTheirActualConstraints() {
        fixture.peer.inputSchema =
                Map.of(
                        "type",
                        "object",
                        "$id",
                        "https://own.invalid/tool",
                        "$defs",
                        Map.of(
                                "region",
                                Map.of("type", "string", "pattern", "^A$", "$anchor", "region")),
                        "properties",
                        Map.of("region", Map.of("$ref", "#region")));
        assertEquals(
                7,
                assertDoesNotThrow(() -> fixture.invoke(false)).structured().path("value").asInt());
        assertEquals(1, fixture.peer.calls.get());
        assertEquals(0, fixture.peer.schemaReads.get());
    }

    @Test
    void frozenSchemaBudgetIsEnforcedBeforeToolsCall() {
        var contract = fixture.contract(false);
        contract.put("inputSchema", overBudgetSchema());
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                fixture.adapters.invoke(
                                        contract,
                                        Map.of("region", "A"),
                                        "own-budget-key",
                                        1,
                                        false,
                                        "own-budget-run",
                                        1));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertEquals(0, fixture.peer.calls.get());
    }

    @Test
    void instanceRegexWorkIsRejectedBeforeToolsCallWithinSharedDeadline() {
        fixture.peer.inputSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("region", Map.of("type", "string", "pattern", "^A+$")));
        var contract = fixture.contract(false);
        contract.put("timeoutMs", 800);
        long started = System.nanoTime();
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                fixture.adapters.invoke(
                                        contract,
                                        Map.of("region", "A".repeat(100000)),
                                        "own-budget-key",
                                        1,
                                        false,
                                        "own-budget-run",
                                        1));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertTrue(
                System.nanoTime() - started
                        < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1200));
        assertEquals(0, fixture.peer.calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputWorkRejectionPreservesReadAndWriteSemantics(boolean write) {
        fixture.peer.output =
                cn.agentplatform.common.Json.write(Map.of("value", "a".repeat(100000)));
        var contract = fixture.contract(write);
        contract.put(
                "outputSchema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("value", Map.of("type", "string", "pattern", "^a+$"))));
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                fixture.adapters.invoke(
                                        contract,
                                        Map.of("region", "A"),
                                        "own-budget-key",
                                        1,
                                        false,
                                        "own-budget-run",
                                        1));
        assertEquals(write ? "RESULT_UNKNOWN" : "OUTPUT_INVALID", failure.semantic);
        assertEquals(write, failure.unknown);
        assertEquals(
                1, fixture.peer.calls.get(), "a validation failure must never resend the call");
    }
}
