package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Actor;

import org.junit.jupiter.api.Test;

import java.util.*;

class SkillRulesTest {
    @Test
    void schemaRejectsRequiredAndTypeMismatch() {
        var schema =
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("region"),
                        "properties",
                        Map.of("region", Map.of("type", "string")));
        assertThrows(IllegalArgumentException.class, () -> SkillRules.validate(schema, Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.validate(schema, Map.of("region", List.of("A"))));
        SkillRules.validate(schema, Map.of("region", "A"));
    }

    @Test
    void scopeIntersectionCannotBeBypassedByArgument() {
        var actor = new Actor("t", "alice", Set.of("operator"), Map.of("region", Set.of("A")));
        var guard =
                Map.<String, Object>of(
                        "roles",
                        List.of("operator"),
                        "attributes",
                        Map.of("region", List.of("A", "B")));
        assertEquals(
                List.of("A"),
                SkillRules.guard(actor, guard, Map.of("region", List.of("A", "B"))).get("region"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.guard(actor, guard, Map.of("region", "B")));
    }

    @Test
    void declaredAttributeUsesOnlyVerifiedActorScope() {
        var actor = new Actor("t", "alice", Set.of(), Map.of("region", Set.of("A")));
        assertEquals(
                "A",
                SkillRules.guard(
                                actor,
                                Map.of("attributes", Map.of("region", List.of())),
                                Map.of("region", "A"))
                        .get("region"));
    }

    @Test
    void emptyGuardIsDefaultDeny() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillRules.guard(
                                new Actor("t", "a", Set.of(), Map.of()), Map.of(), Map.of()));
    }

    @Test
    void rejectsCredentialInEndpointAndArbitraryScheme() {
        assertThrows(
                IllegalArgumentException.class, () -> SkillRules.endpoint("file:///etc/passwd"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.endpoint("https://secret:password@example.com/tool"));
        SkillRules.endpoint("http://127.0.0.1:32123/tool");
    }
}
