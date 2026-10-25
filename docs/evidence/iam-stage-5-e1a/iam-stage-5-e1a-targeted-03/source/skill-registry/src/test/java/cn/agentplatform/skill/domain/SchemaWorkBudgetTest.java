package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaWorkBudget;

import org.junit.jupiter.api.Test;

import java.util.*;

/** Only the pure preflight is called for excessive graphs; never construct networknt first. */
class SchemaWorkBudgetTest {
    static Map<String, Object> sharedGraph(int levels, int width) {
        var defs = new LinkedHashMap<String, Object>();
        defs.put("n0", Map.of("type", "string"));
        for (int i = 1; i <= levels; i++) {
            var refs = new ArrayList<Object>();
            for (int j = 0; j < width; j++) refs.add(Map.of("$ref", "#/$defs/n" + (i - 1)));
            defs.put("n" + i, Map.of("allOf", refs));
        }
        return Map.of("$defs", defs, "$ref", "#/$defs/n" + levels);
    }

    @Test
    void repeatedSharedReferencesAreBudgetedWithoutBuildingValidator() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaWorkBudget.check(Json.tree(sharedGraph(10, 3))));
    }

    @Test
    void cyclesAreExplicitlyRejectedWithoutBuildingValidator() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaWorkBudget.check(Json.tree(Map.of("$ref", "#"))));
    }

    @Test
    void nestedResourceIdsAreExplicitlyUnsupported() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "x",
                                                        Map.of(
                                                                "$id", "other", "type",
                                                                "string"))))));
    }

    @Test
    void ambiguousAnchorsAreRejectedBeforeResolver() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "$anchor",
                                                "x",
                                                "$defs",
                                                Map.of("again", Map.of("$anchor", "x"))))));
    }

    @Test
    void ordinarySharedDefinitionsAndLiteralReferencesRemainAdmissible() {
        assertDoesNotThrow(() -> SchemaWorkBudget.check(Json.tree(sharedGraph(3, 2))));
        assertDoesNotThrow(
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "type",
                                                "object",
                                                "default",
                                                Map.of("$ref", "literal"),
                                                "properties",
                                                Map.of("$ref", Map.of("type", "string"))))));
    }

    @Test
    void localPointerEscapesAndAnchorsHaveSingleResourceMeaning() {
        assertDoesNotThrow(
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "$id",
                                                "https://own.invalid/schema",
                                                "$defs",
                                                Map.of(
                                                        "a/b~c",
                                                        Map.of(
                                                                "type", "string", "$anchor",
                                                                "item")),
                                                "allOf",
                                                List.of(
                                                        Map.of("$ref", "#/$defs/a~1b~0c"),
                                                        Map.of("$ref", "#item"))))));
        assertDoesNotThrow(
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "$defs",
                                                Map.of(
                                                        "number",
                                                        Map.of(
                                                                "type",
                                                                "integer",
                                                                "$dynamicAnchor",
                                                                "number")),
                                                "$dynamicRef",
                                                "#number"))));
    }

    @Test
    void indirectCyclesAndTargetsInsideLiteralDataAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "$defs",
                                                Map.of(
                                                        "a",
                                                        Map.of("$ref", "#/$defs/b"),
                                                        "b",
                                                        Map.of("$ref", "#/$defs/a"))))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SchemaWorkBudget.check(
                                Json.tree(
                                        Map.of(
                                                "default",
                                                Map.of("x", Map.of("type", "string")),
                                                "$ref",
                                                "#/default/x"))));
    }
}
