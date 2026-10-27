package cn.agentplatform.common.schema;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAT-08 方言用例：两种写法（联合 type 数组 / nullable 布尔）必须通过且归一后互相等价；
 * 非法 union、远程 $ref 必须带 JSON Pointer 拒绝；旧 schema 归一后与原树逐字段相等。
 */
class SchemaDialectTest {

    private static JsonNode schema(String text) {
        return Json.read(text);
    }

    private static List<String> errors(JsonNode schema) {
        return SchemaDialect.validate(schema);
    }

    @Test
    void unionTypeWithNullIsAcceptedAsIs() {
        var schema = schema("{\"type\":[\"string\",\"null\"]}");
        assertTrue(errors(schema).isEmpty(), errors(schema).toString());
        assertEquals(schema, SchemaDialect.normalize(schema));
    }

    @Test
    void nullableBooleanIsRewrittenToTheEquivalentUnion() {
        var nullable = schema("{\"type\":\"string\",\"nullable\":true}");
        var union = schema("{\"type\":[\"string\",\"null\"]}");
        var normalized = SchemaDialect.normalize(nullable);
        assertEquals(union, normalized);
        assertFalse(normalized.has("nullable"));
        assertEquals(normalized, SchemaDialect.normalize(union));
        assertTrue(errors(nullable).isEmpty(), errors(nullable).toString());
    }

    @Test
    void unionOfTwoConcreteTypesIsRejectedWithPointer() {
        var schema =
                schema(
                        "{\"type\":\"object\",\"properties\":{\"errorMessage\":{\"type\":[\"string\",\"object\"]}}}");
        var errors = errors(schema);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/properties/errorMessage/type"), errors.get(0));
    }

    @Test
    void remoteRefIsRejectedWithPointer() {
        var schema =
                schema(
                        "{\"type\":\"object\",\"properties\":{\"body\":{\"$ref\":\"http://example.com/schema.json\"}}}");
        var errors = errors(schema);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/properties/body/$ref"), errors.get(0));
    }

    @Test
    void localRefIsAccepted() {
        var schema =
                schema(
                        "{\"type\":\"object\",\"properties\":{\"body\":{\"$ref\":\"#/$defs/body\"}},\"$defs\":{\"body\":{\"type\":\"string\"}}}");
        assertTrue(errors(schema).isEmpty(), errors(schema).toString());
    }

    @Test
    void nestedItemsUnionIsNormalized() {
        var normalized =
                SchemaDialect.normalize(
                        schema(
                                "{\"type\":\"array\",\"items\":{\"type\":\"string\",\"nullable\":true}}"));
        assertEquals(
                schema("{\"type\":\"array\",\"items\":{\"type\":[\"string\",\"null\"]}}"),
                normalized);
    }

    @Test
    void propertiesNullableIsNormalized() {
        var normalized =
                SchemaDialect.normalize(
                        schema(
                                "{\"type\":\"object\",\"properties\":{\"errorMessage\":{\"type\":\"string\",\"nullable\":true}}}"));
        assertEquals(
                schema(
                        "{\"type\":\"object\",\"properties\":{\"errorMessage\":{\"type\":[\"string\",\"null\"]}}}"),
                normalized);
    }

    @Test
    void legacySchemaIsUnchangedByNormalization() {
        var legacy =
                schema(
                        """
                        {"type":"object","required":["region"],"additionalProperties":false,
                         "properties":{"region":{"type":"string","enum":["A","B"]},
                           "rows":{"type":"array","items":{"type":"object","properties":{
                             "id":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}}}}},
                         "$defs":{"unused":{"type":"boolean"}}}
                        """);
        assertEquals(legacy, SchemaDialect.normalize(legacy));
        assertTrue(errors(legacy).isEmpty(), errors(legacy).toString());
    }

    @Test
    void nullFirstUnionIsCanonicalized() {
        assertEquals(
                schema("{\"type\":[\"string\",\"null\"]}"),
                SchemaDialect.normalize(schema("{\"type\":[\"null\",\"string\"]}")));
    }

    @Test
    void singleElementUnionCollapsesToString() {
        assertEquals(
                schema("{\"type\":\"string\"}"),
                SchemaDialect.normalize(schema("{\"type\":[\"string\"]}")));
    }

    @Test
    void nullableTrueOnUnionContainingNullJustDropsTheFlag() {
        var normalized =
                SchemaDialect.normalize(
                        schema("{\"type\":[\"string\",\"null\"],\"nullable\":true}"));
        assertEquals(schema("{\"type\":[\"string\",\"null\"]}"), normalized);
    }

    @Test
    void nullableFalseIsDropped() {
        var normalized =
                SchemaDialect.normalize(schema("{\"type\":\"string\",\"nullable\":false}"));
        assertEquals(schema("{\"type\":\"string\"}"), normalized);
    }

    @Test
    void emptyTypeArrayIsRejected() {
        var errors = errors(schema("{\"type\":[]}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("/type"), errors.get(0));
    }

    @Test
    void duplicateTypeEntriesAreRejected() {
        var errors = errors(schema("{\"type\":[\"string\",\"string\"]}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/type"), errors.get(0));
    }

    @Test
    void unknownTypeIsRejected() {
        var errors =
                errors(schema("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"text\"}}}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/properties/a/type"), errors.get(0));
    }

    @Test
    void nullableWithoutTypeIsRejected() {
        var errors = errors(schema("{\"nullable\":true}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/nullable"), errors.get(0));
    }

    @Test
    void nullableFalseConflictingWithNullUnionIsRejected() {
        var errors = errors(schema("{\"type\":[\"string\",\"null\"],\"nullable\":false}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(
                errors.get(0).contains("/type") || errors.get(0).contains("/nullable"),
                errors.get(0));
    }

    @Test
    void nonBooleanNullableIsRejected() {
        var errors = errors(schema("{\"type\":\"string\",\"nullable\":\"yes\"}"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/nullable"), errors.get(0));
    }

    @Test
    void booleanSchemaPasses() {
        assertTrue(errors(schema("true")).isEmpty());
        assertTrue(
                errors(schema("{\"type\":\"object\",\"properties\":{\"anything\":true}}")).isEmpty());
    }

    @Test
    void nonObjectRootIsRejected() {
        var errors = errors(schema("\"string\""));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("/"), errors.get(0));
    }

    @Test
    void pointersEscapePropertyNames() {
        var errors =
                errors(
                        schema(
                                "{\"type\":\"object\",\"properties\":{\"a/b\":{\"type\":\"nope\"},\"c~d\":{\"type\":\"nope\"}}}"));
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("/properties/a~1b/type"), errors.get(0));
        assertTrue(errors.get(1).contains("/properties/c~0d/type"), errors.get(1));
    }

    @Test
    void unknownKeywordsAreTolerated() {
        assertTrue(
                errors(
                                schema(
                                        "{\"type\":\"object\",\"x-vendor\":{\"type\":[\"string\",\"object\"]}}"))
                        .isEmpty());
    }

    @Test
    void patternPropertiesAndDependentSchemasAreWalked() {
        var errors =
                errors(
                        schema(
                                "{\"type\":\"object\",\"patternProperties\":{\"^x-\":{\"type\":\"nope\"}},\"dependentSchemas\":{\"a\":{\"type\":[\"string\",\"object\"]}}}"));
        assertEquals(2, errors.size(), errors.toString());
    }

    @Test
    void defsAndCombinatorsAreNormalized() {
        var normalized =
                SchemaDialect.normalize(
                        schema(
                                """
                                {"type":"object","$defs":{"s":{"type":"string","nullable":true}},
                                 "allOf":[{"type":"object","properties":{"a":{"type":"string","nullable":true}}}],
                                 "anyOf":[{"type":"array","items":{"type":"string","nullable":true}}],
                                 "oneOf":[{"type":"number","nullable":true}]}
                                """));
        var expected =
                schema(
                        """
                        {"type":"object","$defs":{"s":{"type":["string","null"]}},
                         "allOf":[{"type":"object","properties":{"a":{"type":["string","null"]}}}],
                         "anyOf":[{"type":"array","items":{"type":["string","null"]}}],
                         "oneOf":[{"type":["number","null"]}]}
                        """);
        assertEquals(expected, normalized);
    }

    @Test
    void normalizeIsIdempotentAndDoesNotMutateInput() {
        var original =
                schema(
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"nullable\":true}}}");
        var copy = original.deepCopy();
        var once = SchemaDialect.normalize(original);
        assertEquals(copy, original, "normalize 不得修改入参");
        assertEquals(once, SchemaDialect.normalize(once), "normalize 必须幂等");
        assertNotSame(original, once);
    }
}
