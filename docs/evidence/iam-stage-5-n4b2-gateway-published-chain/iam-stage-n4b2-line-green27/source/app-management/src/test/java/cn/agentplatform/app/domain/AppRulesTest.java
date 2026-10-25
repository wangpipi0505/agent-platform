package cn.agentplatform.app.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class AppRulesTest {
    @Test
    void cycleAndDisconnectedGraphRejected() {
        var nodes =
                List.of(
                        Map.of("id", "s", "type", "START"),
                        Map.of("id", "e", "type", "END"),
                        Map.of("id", "x", "type", "TRANSFORM"));
        assertFalse(
                AppRules.graphErrors(
                                Map.of(
                                        "nodes",
                                        nodes,
                                        "edges",
                                        List.of(Map.of("source", "s", "target", "e"))))
                        .isEmpty());
    }

    @Test
    void frozenContractEdgesAndCycleAreChecked() {
        var nodes = List.of(Map.of("id", "s", "type", "START"), Map.of("id", "e", "type", "END"));
        assertTrue(
                AppRules.graphErrors(
                                Map.of(
                                        "nodes",
                                        nodes,
                                        "edges",
                                        List.of(Map.of("from", "s", "to", "e"))))
                        .isEmpty());
        assertFalse(
                AppRules.graphErrors(
                                Map.of(
                                        "nodes",
                                        nodes,
                                        "edges",
                                        List.of(
                                                Map.of("from", "s", "to", "e"),
                                                Map.of("from", "e", "to", "s"))))
                        .isEmpty());
    }

    @Test
    void errorEdgeMustExist() {
        var nodes =
                List.of(
                        Map.of(
                                "id",
                                "s",
                                "type",
                                "START",
                                "errorPolicy",
                                Map.of("onFail", "ERROR_EDGE", "errorEdgeTo", "missing")),
                        Map.of("id", "e", "type", "END"));
        assertFalse(
                AppRules.graphErrors(
                                Map.of(
                                        "nodes",
                                        nodes,
                                        "edges",
                                        List.of(Map.of("from", "s", "to", "e"))))
                        .isEmpty());
    }

    @Test
    void schemaRejectsMissingRequiredAndArrayToScalar() {
        var schema =
                Map.<String, Object>of(
                        "type",
                        "object",
                        "required",
                        List.of("region"),
                        "properties",
                        Map.of("region", Map.of("type", "string")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppRules.validateValue(schema, Map.of(), "args", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppRules.validateValue(schema, Map.of("region", List.of("A")), "args", true));
        AppRules.validateValue(schema, Map.of("region", "${input.region}"), "args", true);
    }

    @Test
    void variableValuesAreNotReparsedAsTemplates() {
        assertEquals(
                "{{literal}}",
                AppRules.render(
                        "{{value}}",
                        List.of(Map.of("name", "value", "type", "string", "required", true)),
                        Map.of("value", "{{literal}}")));
    }

    @Test
    void canonicalHashIgnoresMapOrderButNotChanges() {
        assertEquals(
                AppRules.canonical(Map.of("b", 2, "a", 1)),
                AppRules.canonical(new TreeMap<>(Map.of("a", 1, "b", 2))));
        assertNotEquals(AppRules.canonical(Map.of("a", 1)), AppRules.canonical(Map.of("a", 2)));
    }

    @Test
    void renderRejectsUndeclaredAndMissingAndWrongType() {
        var vars =
                List.<Map<String, Object>>of(
                        Map.of("name", "count", "type", "integer", "required", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppRules.render("{{unknown}}", vars, Map.of("count", 1)));
        assertThrows(
                IllegalArgumentException.class, () -> AppRules.render("{{count}}", vars, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AppRules.render("{{count}}", vars, Map.of("count", "one")));
        assertEquals("1", AppRules.render("{{count}}", vars, Map.of("count", 1)));
    }
}
