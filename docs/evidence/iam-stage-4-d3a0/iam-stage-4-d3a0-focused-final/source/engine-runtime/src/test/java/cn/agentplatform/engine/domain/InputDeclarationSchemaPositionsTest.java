package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Stream;

class InputDeclarationSchemaPositionsTest {
    private static final List<String> REFERENCES = List.of("$ref", "$dynamicRef", "$recursiveRef");

    @ParameterizedTest
    @ValueSource(strings = {"$ref", "$dynamicRef", "$recursiveRef"})
    void referenceNamesAreLegalPropertyNames(String name) {
        var schema =
                Json.tree(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(name, Map.of("type", "string", "minLength", 3)),
                                "required",
                                List.of(name)));
        assertDoesNotThrow(() -> validate(schema, Json.tree(Map.of(name, "value"))));
        assertInputMismatch(schema, Json.tree(Map.of(name, "x")));
    }

    static Stream<Object[]> instanceLiterals() {
        return Stream.of("const", "default", "enum", "examples")
                .flatMap(
                        keyword ->
                                REFERENCES.stream()
                                        .flatMap(
                                                reference ->
                                                        Stream.of(
                                                                new Object[] {
                                                                    keyword,
                                                                    Json.tree(
                                                                            Map.of(
                                                                                    reference,
                                                                                    "https://business.invalid/value"))
                                                                },
                                                                new Object[] {
                                                                    keyword,
                                                                    Json.tree(
                                                                            Map.of(
                                                                                    reference,
                                                                                    Map.of(
                                                                                            "value",
                                                                                            "literal")))
                                                                })));
    }

    @ParameterizedTest
    @MethodSource("instanceLiterals")
    void instanceDataDoesNotIntroduceReferences(String keyword, JsonNode literal) {
        var property = Json.object();
        property.put("type", "object");
        property.set(
                keyword,
                Set.of("enum", "examples").contains(keyword)
                        ? Json.tree(List.of(literal))
                        : literal);
        var schema = Json.tree(Map.of("type", "object", "properties", Map.of("payload", property)));
        var inputs = Json.tree(Map.of("payload", literal));
        var before = inputs.deepCopy();
        assertDoesNotThrow(() -> validate(schema, inputs));
        assertEquals(before, inputs);
        if (Set.of("const", "enum").contains(keyword))
            assertInputMismatch(schema, Json.tree(Map.of("payload", Map.of("different", true))));
        if (keyword.equals("default")) {
            var absent = Json.object();
            assertDoesNotThrow(() -> validate(schema, absent));
            assertEquals(Json.object(), absent);
        }
    }

    private static List<JsonNode> schemaPositions(JsonNode child) {
        var schemas = new ArrayList<JsonNode>();
        schemas.add(child);
        for (String keyword :
                List.of(
                        "$defs",
                        "definitions",
                        "properties",
                        "patternProperties",
                        "dependentSchemas",
                        "dependencies"))
            schemas.add(Json.tree(Map.of(keyword, Map.of("unused", child))));
        for (String keyword : List.of("allOf", "anyOf", "oneOf", "prefixItems"))
            schemas.add(Json.tree(Map.of(keyword, List.of(child))));
        for (String keyword :
                List.of(
                        "items",
                        "additionalItems",
                        "additionalProperties",
                        "contains",
                        "propertyNames",
                        "not",
                        "if",
                        "then",
                        "else",
                        "unevaluatedItems",
                        "unevaluatedProperties",
                        "contentSchema")) schemas.add(Json.tree(Map.of(keyword, child)));
        schemas.add(Json.tree(Map.of("items", List.of(child))));
        schemas.add(
                Json.tree(
                        Map.of(
                                "$defs",
                                Map.of(
                                        "unused",
                                        Map.of(
                                                "allOf",
                                                List.of(
                                                        Map.of(
                                                                "if",
                                                                Map.of(
                                                                        "properties",
                                                                        Map.of(
                                                                                "nested",
                                                                                child)))))))));
        return schemas;
    }

    static Stream<JsonNode> externalReferences() {
        return REFERENCES.stream()
                .flatMap(
                        keyword ->
                                Stream.of(
                                                "http://127.0.0.1:1/private",
                                                "file:///C:/Windows/win.ini")
                                        .flatMap(
                                                ref ->
                                                        schemaPositions(
                                                                Json.tree(Map.of(keyword, ref)))
                                                                .stream()));
    }

    @ParameterizedTest
    @MethodSource("externalReferences")
    void externalReferencesAreRejectedEvenInUnusedSchemaPositions(JsonNode schema) {
        assertInvalidDeclaration(schema);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"$ref\":\"#/$defs/missing\"}",
                "{\"properties\":{\"absent\":{\"$ref\":\"#/$defs/missing\"}}}",
                "{\"$defs\":{\"unused\":{\"$ref\":\"#/$defs/missing\"}}}",
                "{\"if\":false,\"then\":{\"$ref\":\"#/$defs/missing\"}}"
            })
    void missingLocalReferencesFailDuringInitialization(String schema) {
        assertInvalidDeclaration(Json.read(schema));
    }

    @Test
    void localReferenceToAnnotationCannotBypassExternalLoaderBlock() {
        assertInvalidDeclaration(
                Json.read(
                        """
                        {"$ref":"#/default", "default":{"$ref":"file:///C:/Windows/win.ini"}}
                        """));
    }

    @Test
    void localDefinitionsStillEnforceTypedValuesWithoutCoercion() {
        var schema =
                Json.read(
                        """
{"type":"object","$defs":{"number":{"type":"integer","minimum":2}},
 "properties":{"quantity":{"$ref":"#/$defs/number"}},"required":["quantity"]}
""");
        assertDoesNotThrow(() -> validate(schema, Json.read("{\"quantity\":2}")));
        assertInputMismatch(schema, Json.read("{\"quantity\":1}"));
        assertInputMismatch(schema, Json.read("{\"quantity\":\"2\"}"));
        assertInputMismatch(schema, Json.object());
    }

    private static void assertInvalidDeclaration(JsonNode schema) {
        var error = assertThrows(PlatformException.class, () -> validate(schema, Json.object()));
        assertEquals(400, error.status());
        assertEquals("INVALID_INPUT_DECLARATION", error.code());
    }

    private static void assertInputMismatch(JsonNode schema, JsonNode inputs) {
        var error = assertThrows(PlatformException.class, () -> validate(schema, inputs));
        assertEquals(400, error.status());
        assertEquals("INPUT_SCHEMA_CHANGED", error.code());
    }

    private static void validate(JsonNode schema, JsonNode inputs) {
        InputDeclarations.validate(
                Json.tree(Map.of("type", "CHAT", "config", Map.of("inputSchema", schema))), inputs);
    }
}
