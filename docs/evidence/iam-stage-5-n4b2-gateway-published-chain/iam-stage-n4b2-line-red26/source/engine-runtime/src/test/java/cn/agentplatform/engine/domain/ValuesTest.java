package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

class ValuesTest {
    @Test
    void equalityIsAllowed() {
        assertEquals(
                true, Values.expression("${input.n} == 2", Json.read("{\"input\":{\"n\":2}}")));
    }

    @Test
    void functionsAreExplicit() {
        assertEquals(
                2,
                Values.expression(
                        "size(${input.items})", Json.read("{\"input\":{\"items\":[1,2]}}")));
    }

    @Test
    void forbidsJavaAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Values.expression("T(java.lang.Runtime).getRuntime()", Json.read("{}")));
    }

    @Test
    void missingPathIsNull() {
        assertTrue(
                Values.resolve(Json.tree("${input.missing}"), Json.read("{\"input\":{}}"))
                        .isNull());
    }

    @Test
    void wildcardPreservesMappedElements() {
        assertEquals(
                Json.read("[1,2]"),
                Values.path(Json.read("{\"items\":[{\"x\":1},{\"x\":2}]}"), "items[*].x"));
    }

    @Test
    void nullComparisonDoesNotSelectCondition() {
        assertEquals(
                false, Values.expression("${input.missing} == null", Json.read("{\"input\":{}}")));
    }
}
