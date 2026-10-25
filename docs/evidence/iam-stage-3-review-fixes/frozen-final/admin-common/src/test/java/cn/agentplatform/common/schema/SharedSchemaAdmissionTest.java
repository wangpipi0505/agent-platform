package cn.agentplatform.common.schema;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

import java.util.Map;

class SharedSchemaAdmissionTest {
    @Test
    void lowerGraphLimitRefusesSmallSchemaAndCannotRaiseHardCeiling() {
        var schema = Json.read("{\"type\":\"string\"}");
        assertThrows(IllegalArgumentException.class, () -> SchemaWorkBudget.inspect(schema, 1));
        assertDoesNotThrow(() -> SchemaWorkBudget.inspect(schema, 20));
        for (long limit : new long[] {-1, 0, SchemaWorkBudget.MAX_EXPANDED_WORK + 1})
            assertThrows(
                    IllegalArgumentException.class, () -> SchemaWorkBudget.inspect(schema, limit));
    }

    @Test
    void lowerInstanceLimitRefusesFewNodesAndCannotRaiseHardCeiling() {
        var plan = SchemaWorkBudget.inspect(Json.read("{\"type\":\"object\"}"));
        var instance = Json.read("{\"value\":1}");
        assertThrows(IllegalArgumentException.class, () -> plan.admitInstance(instance, 0, 1));
        assertDoesNotThrow(() -> plan.admitInstance(instance, 0, 100));
        for (long limit : new long[] {-1, 0, 2_000_001})
            assertThrows(
                    IllegalArgumentException.class, () -> plan.admitInstance(instance, 0, limit));
    }

    @Test
    void rawDeclarationArrayIsBoundedWithoutInterpretingItsMetadata() {
        assertDoesNotThrow(
                () ->
                        SchemaWorkBudget.checkStructure(
                                Json.read(
                                        "[{\"name\":\"$ref\",\"required\":true,\"default\":{\"$ref\":\"literal\"}}]")));
        var root = Json.object();
        var cursor = root;
        for (int i = 0; i < 66; i++) cursor = cursor.putObject("child");
        assertThrows(IllegalArgumentException.class, () -> SchemaWorkBudget.checkStructure(root));
    }

    @Test
    void nestedDeadlineNeverExtendsCallerAndRestoresPreviousScope() {
        try (var outer = SchemaDeadline.until(System.nanoTime() + 10_000_000_000L)) {
            try (var expired = SchemaDeadline.until(System.nanoTime() - 1)) {
                try (var inner = SchemaDeadline.until(System.nanoTime() + 10_000_000_000L)) {
                    assertThrows(IllegalArgumentException.class, SchemaDeadline::check);
                }
                assertThrows(IllegalArgumentException.class, SchemaDeadline::check);
            }
            assertDoesNotThrow(SchemaDeadline::check);
        }
        assertDoesNotThrow(SchemaDeadline::check);
    }

    @Test
    void ordinaryLocalAnchorAndLiteralDataRemainDistinct() {
        var schema =
                Json.read(
                        """
                        {"$defs":{"word":{"$anchor":"word","type":"string"}},
                        "properties":{"$ref":{"$ref":"#word"}},
                        "default":{"$ref":"https://business.invalid/literal"}}
                        """);
        assertDoesNotThrow(() -> SchemaWorkBudget.inspect(schema));
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaWorkBudget.inspect(Json.tree(Map.of("$ref", "#"))));
    }
}
