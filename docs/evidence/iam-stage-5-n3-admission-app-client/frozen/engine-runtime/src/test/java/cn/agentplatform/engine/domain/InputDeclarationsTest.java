package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class InputDeclarationsTest {
    @Test
    void flowUsesDefinitionDeclarationsAndDoesNotMutateDefaults() {
        var declaration =
                Json.read(
                        "[{\"name\":\"q\",\"type\":\"STRING\",\"required\":true,\"default\":\"hello\"}]");
        var draft = Json.tree(Map.of("type", "FLOW", "definition", Map.of("inputs", declaration)));
        var missing = Json.object();
        assertThrows(PlatformException.class, () -> InputDeclarations.validate(draft, missing));
        assertEquals("{}", missing.toString());
        InputDeclarations.validate(draft, Json.tree(Map.of("q", "hello")));
    }

    @Test
    void externalReferencesCannotLoadNetworkOrFiles() {
        for (String ref : List.of("http://127.0.0.1:1/private", "file:///C:/Windows/win.ini")) {
            var draft =
                    Json.tree(
                            Map.of(
                                    "type",
                                    "CHAT",
                                    "config",
                                    Map.of(
                                            "inputSchema",
                                            Map.of(
                                                    "type",
                                                    "object",
                                                    "properties",
                                                    Map.of("x", Map.of("$ref", ref))))));
            assertEquals(
                    400,
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            InputDeclarations.validate(
                                                    draft, Json.tree(Map.of("x", 1))))
                            .status());
        }
    }

    @Test
    void schemasAndDeclarationsBothApplyAndLocalDefinitionsWork() {
        var draft =
                Json.read(
                        "{\"type\":\"CHAT\",\"config\":{\"inputSchema\":{\"type\":\"object\",\"$defs\":{\"word\":{\"type\":\"string\",\"minLength\":3}},\"properties\":{\"q\":{\"$ref\":\"#/$defs/word\"}}},\"inputs\":[{\"name\":\"q\",\"type\":\"STRING\",\"required\":true}]}}");
        assertThrows(
                PlatformException.class, () -> InputDeclarations.validate(draft, Json.object()));
        assertThrows(
                PlatformException.class,
                () -> InputDeclarations.validate(draft, Json.tree(Map.of("q", "x"))));
        InputDeclarations.validate(draft, Json.tree(Map.of("q", "hello")));
    }
}
