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

    @Test
    void admissionExposesEstimatedWorkAndKeepsDenialMessageCompatible() {
        var plan = SchemaWorkBudget.inspect(Json.read("{\"type\":\"object\"}"));
        var instance = Json.read("{\"value\":1}");
        var admitted = plan.admission(instance, 0, 100);
        assertTrue(admitted.admitted());
        assertEquals(8, admitted.estimatedWork());
        assertNull(admitted.reason());
        var refused = plan.admission(instance, 0, 1);
        assertFalse(refused.admitted());
        assertEquals(8, refused.estimatedWork());
        var error = assertThrows(IllegalArgumentException.class, refused::require);
        assertEquals("Schema预算拒绝：" + refused.reason(), error.getMessage());
        assertTrue(
                error.getMessage()
                        .startsWith("Schema预算拒绝：本次实例的校验工作量超过上限"));
        assertTrue(error.getMessage().contains("estimatedWork=8"));
        assertTrue(error.getMessage().contains("上限=1"));
        assertEquals(2_000_000L, SchemaWorkBudget.MAX_INSTANCE_WORK);
    }

    @Test
    void admissionStillRefusesStructuralOverflowInsteadOfReturningADecision() {
        var plan = SchemaWorkBudget.inspect(Json.read("{\"type\":\"array\"}"));
        var oversized = Json.mapper().createArrayNode();
        for (int i = 0; i < 250_001; i++) oversized.add(i);
        // 结构上限不是“预算可降级”的一部分：它必须直接拒绝，不能被降级逻辑吞掉。
        assertThrows(
                IllegalArgumentException.class, () -> plan.admission(oversized, 0, 2_000_000L));
    }

    @Test
    void onlyTheInstanceWorkDimensionIsDegradable() {
        // 主维度（节点数 × 展开工作量）是 PLAT-07 唯一允许 DEGRADED 跳过的拒绝。
        assertTrue(
                new SchemaWorkBudget.Admission(
                                false, 12, "本次实例的校验工作量超过上限：estimatedWork=12，上限=1")
                        .degradable());
        // 其余五维保护“校验自身成本”，理由里没有 estimatedWork=，因此不可降级。
        assertFalse(
                new SchemaWorkBudget.Admission(
                                false, 12, "本次实例的校验工作量超过上限：regexWork=9，上限=4000000")
                        .degradable());
        assertFalse(new SchemaWorkBudget.Admission(true, 12, null).degradable());
    }
}
