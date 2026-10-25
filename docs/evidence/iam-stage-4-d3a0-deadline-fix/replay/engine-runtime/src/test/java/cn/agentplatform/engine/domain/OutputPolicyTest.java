package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import org.junit.jupiter.api.Test;

class OutputPolicyTest {
    @Test
    void referencedLiteralCannotActivateAnExternalSchemaLoader() {
        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () ->
                                new OutputPolicy(
                                        Json.read(
                                                """
                                                {"format":{"type":"json","schema":{"$ref":"#/const",
                                                "const":{"$ref":"file:///invalid/schema.json"}}}}
                                                """)));
        assertEquals("OUTPUT_POLICY_INVALID", fault.semantic);
    }

    @Test
    void acyclicLocalAnchorsPreserveNativeValidAndInvalidInstances() {
        var policy =
                new OutputPolicy(
                        Json.read(
                                """
{"format":{"type":"json","schema":{"$defs":{"branch":{
"$anchor":"branch","type":"object","properties":{
"value":{"type":"integer"},"child":{"$ref":"#leaf"}},"required":["value"]},
"leaf":{"$anchor":"leaf","type":"object","properties":{"value":{"type":"integer"}},"required":["value"]}},
"$ref":"#/$defs/branch"}}}
"""));
        assertTrue(
                policy.validate(
                                Json.tree(
                                        java.util.Map.of(
                                                "text", "{\"value\":1,\"child\":{\"value\":2}}")))
                        .valid());
        assertFalse(
                policy.validate(
                                Json.tree(
                                        java.util.Map.of(
                                                "text",
                                                "{\"value\":1,\"child\":{\"value\":\"bad\"}}")))
                        .valid());
    }

    @Test
    void cyclicLocalSchemaIsExplicitlyUnsupportedByBoundedAdmission() {
        // Compatibility is intentionally narrower: only acyclic local reference graphs are
        // admitted.
        var error =
                assertThrows(
                        RuntimeFault.class,
                        () ->
                                new OutputPolicy(
                                        Json.read(
                                                """
{"format":{"type":"json","schema":{"$defs":{"branch":{
"$anchor":"branch","type":"object","properties":{
"value":{"type":"integer"},"child":{"$ref":"#branch"}},"required":["value"]}},
"$ref":"#/$defs/branch"}}}
""")));
        assertEquals("OUTPUT_POLICY_INVALID", error.semantic);
        assertTrue(error.getMessage().contains("循环引用"));
    }

    @Test
    void referenceNamedBusinessPropertiesAreNotSchemaKeywords() {
        var declaration =
                Json.read(
                        """
                        {"type":"object","properties":{"$ref":{"type":"string"},
                        "$dynamicRef":{"type":"integer"},"$recursiveRef":{"type":"boolean"}},
                        "required":["$ref","$dynamicRef","$recursiveRef"]}
                        """);
        var instance = Json.read("{\"$ref\":\"value\",\"$dynamicRef\":2,\"$recursiveRef\":true}");
        assertTrue(
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(declaration)
                        .validate(instance)
                        .isEmpty());
        var policy =
                assertDoesNotThrow(
                        () ->
                                new OutputPolicy(
                                        Json.tree(
                                                java.util.Map.of(
                                                        "format",
                                                        java.util.Map.of(
                                                                "type",
                                                                "json",
                                                                "schema",
                                                                declaration)))));
        assertTrue(
                policy.validate(Json.tree(java.util.Map.of("text", instance.toString()))).valid());
        assertFalse(policy.validate(Json.tree(java.util.Map.of("text", "{}"))).valid());
    }

    @Test
    void referenceNamedLiteralFieldsRemainData() {
        for (String keyword : java.util.List.of("const", "enum", "examples", "default")) {
            var literal =
                    Json.read("{\"$ref\":\"https://invalid.example/data\",\"$dynamicRef\":7}");
            var declaration = Json.object();
            declaration.set(
                    keyword,
                    java.util.Set.of("enum", "examples").contains(keyword)
                            ? Json.tree(java.util.List.of(literal))
                            : literal);
            var policy =
                    assertDoesNotThrow(
                            () ->
                                    new OutputPolicy(
                                            Json.tree(
                                                    java.util.Map.of(
                                                            "format",
                                                            java.util.Map.of(
                                                                    "type",
                                                                    "json",
                                                                    "schema",
                                                                    declaration)))));
            assertTrue(
                    policy.validate(Json.tree(java.util.Map.of("text", literal.toString())))
                            .valid());
        }
    }

    @Test
    void unresolvedLocalReferencesFailDuringConstructionEvenInUnevaluatedBranches() {
        for (String declaration :
                java.util.List.of(
                        "{\"$ref\":\"#/missing\"}",
                        "{\"$defs\":{\"unused\":{\"$ref\":\"#/missing\"}}}",
                        "{\"properties\":{\"optional\":{\"$ref\":\"#/missing\"}}}",
                        "{\"anyOf\":[true,{\"$ref\":\"#/missing\"}]}")) {
            var fault =
                    assertThrows(
                            RuntimeFault.class,
                            () ->
                                    new OutputPolicy(
                                            Json.tree(
                                                    java.util.Map.of(
                                                            "format",
                                                            java.util.Map.of(
                                                                    "type",
                                                                    "json",
                                                                    "schema",
                                                                    Json.read(declaration))))));
            assertEquals("OUTPUT_POLICY_INVALID", fault.semantic);
        }
    }

    @Test
    void actualSchemaReferencesRejectNetworkFileAndRelativeTargetsInNestedSchemas() {
        for (String reference :
                java.util.List.of(
                        "https://invalid.example/schema",
                        "file:///invalid/schema",
                        "relative.json")) {
            for (String keyword : java.util.List.of("$ref", "$dynamicRef", "$recursiveRef")) {
                var declaration =
                        java.util.Map.of(
                                "properties",
                                java.util.Map.of("value", java.util.Map.of(keyword, reference)));
                var fault =
                        assertThrows(
                                RuntimeFault.class,
                                () ->
                                        new OutputPolicy(
                                                Json.tree(
                                                        java.util.Map.of(
                                                                "format",
                                                                java.util.Map.of(
                                                                        "type",
                                                                        "json",
                                                                        "schema",
                                                                        declaration)))));
                assertEquals("OUTPUT_POLICY_INVALID", fault.semantic);
            }
        }
    }

    @Test
    void explicitNullPolicyIsInvalidRatherThanDefaulted() {
        assertThrows(RuntimeFault.class, () -> new OutputPolicy(Json.read("{\"onInvalid\":null}")));
        assertThrows(
                RuntimeFault.class,
                () -> new OutputPolicy(Json.read("{\"format\":{\"type\":null}}")));
    }

    @Test
    void strictJsonRejectsDuplicateKeysAndEmptyDocument() {
        var policy = new OutputPolicy(Json.read("{\"format\":{\"type\":\"json\"}}"));
        assertFalse(
                policy.validate(Json.read("{\"text\":\"{\\\"answer\\\":1,\\\"answer\\\":2}\"}"))
                        .valid());
        assertFalse(policy.validate(Json.read("{\"text\":\"  \"}")).valid());
        assertTrue(policy.validate(Json.read("{\"text\":\"null\"}")).valid());
    }

    @Test
    void schemaUsesCompositionAndLocalReferencesWithoutNetwork() {
        var policy =
                new OutputPolicy(
                        Json.read(
                                "{\"format\":{\"type\":\"json\",\"schema\":{\"$defs\":{\"positive\":{\"type\":\"integer\",\"minimum\":1}},\"allOf\":[{\"$ref\":\"#/$defs/positive\"},{\"maximum\":5}]}}}"));
        assertFalse(policy.validate(Json.read("{\"text\":\"0\"}")).valid());
        assertFalse(policy.validate(Json.read("{\"text\":\"6\"}")).valid());
        assertTrue(policy.validate(Json.read("{\"text\":\"2\"}")).valid());
        assertThrows(
                RuntimeFault.class,
                () ->
                        new OutputPolicy(
                                Json.read(
                                        "{\"format\":{\"type\":\"json\",\"schema\":{\"$ref\":\"https://invalid.example/schema\"}}}")));
    }

    @Test
    void noneDoesNotApplySchemaToOrdinaryText() {
        var policy =
                new OutputPolicy(
                        Json.read(
                                "{\"format\":{\"type\":\"none\",\"schema\":{\"type\":\"object\"}}}"));
        assertTrue(policy.validate(Json.read("{\"text\":\"ordinary text\"}")).valid());
    }
}
