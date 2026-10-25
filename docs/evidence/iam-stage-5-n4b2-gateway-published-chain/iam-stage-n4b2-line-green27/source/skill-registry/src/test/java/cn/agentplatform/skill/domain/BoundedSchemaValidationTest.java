package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.schema.SchemaDeadline;

import org.junit.jupiter.api.Test;

import java.util.*;

/** Small, non-pathological examples; the old unbounded regex/graph probes are never replayed. */
class BoundedSchemaValidationTest {
    @Test
    void unsupportedLookaroundIsRejectedInsteadOfUsingJdkFallback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.schema(Map.of("type", "string", "pattern", "(?=a)a")));
    }

    @Test
    void unsupportedBackreferenceIsRejectedInsteadOfUsingJdkFallback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.schema(Map.of("type", "string", "pattern", "(a)\\1")));
    }

    @Test
    void countedExpansionIsRejectedBeforeRegexCompilation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.schema(Map.of("type", "string", "pattern", "((ab){20}){20}")));
    }

    @Test
    void expiredScopeRejectsSynchronousValidationAndRestoresCaller() {
        try (var ignored = SchemaDeadline.until(System.nanoTime() - 1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SkillRules.validate(Map.of("type", "string"), "a"));
        }
        assertDoesNotThrow(() -> SkillRules.validate(Map.of("type", "string"), "a"));
    }

    @Test
    void ordinarySharedDefinitionsStillEnforceConstraints() {
        var schema =
                Map.of(
                        "type",
                        "object",
                        "$defs",
                        Map.of("region", Map.of("type", "string", "pattern", "^A$")),
                        "properties",
                        Map.of("region", Map.of("$ref", "#/$defs/region")));
        assertDoesNotThrow(() -> SkillRules.validate(schema, Map.of("region", "A")));
        assertThrows(
                RuntimeException.class, () -> SkillRules.validate(schema, Map.of("region", "B")));
        assertDoesNotThrow(() -> SkillRules.schema(SchemaWorkBudgetTest.sharedGraph(3, 2)));
    }

    @Test
    void patternPropertiesUseTheSameFactoryAndSearchSemantics() {
        var schema =
                Map.of(
                        "type",
                        "object",
                        "patternProperties",
                        Map.of("x", Map.of("type", "integer")));
        assertDoesNotThrow(() -> SkillRules.validate(schema, Map.of("prefix-x", 1)));
        assertThrows(
                RuntimeException.class,
                () -> SkillRules.validate(schema, Map.of("prefix-x", "bad")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillRules.schema(
                                Map.of(
                                        "patternProperties",
                                        Map.of("(?=x)x", Map.of("type", "string")))));
    }

    @Test
    void regexAndComparisonInstanceWorkIsBoundedBeforeValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillRules.validate(
                                Map.of("type", "string", "pattern", "^a+$"), "a".repeat(100000)));
        assertDoesNotThrow(
                () -> SkillRules.validate(Map.of("type", "string"), "a".repeat(5 * 1024 * 1024)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SkillRules.validate(
                                Map.of("type", "array", "uniqueItems", true),
                                Collections.nCopies(1000, 1)));
    }

    @Test
    void boundedLinearPatternsAndEscapedBracketsStillWork() {
        assertDoesNotThrow(
                () ->
                        SkillRules.validate(
                                Map.of("type", "string", "pattern", "^a+$"), "a".repeat(1000)));
        assertDoesNotThrow(
                () ->
                        SkillRules.validate(
                                Map.of("type", "string", "pattern", "^\\[a{2}\\]$"), "[aa]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.schema(Map.of("pattern", "\\Q[\\E{20}{20}")));
        assertThrows(
                IllegalArgumentException.class, () -> SkillRules.schema(Map.of("format", "regex")));
    }

    @Test
    void ordinaryValidationErrorsKeepTheExistingBadRequestExceptionType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.validate(Map.of("type", "integer"), "bad"));
    }

    @Test
    void largePlainArtifactRetainsTheExistingSixtyFourMiBReceiveBoundary() {
        assertDoesNotThrow(
                () ->
                        SkillRules.validate(
                                Map.of("type", "object", "required", List.of("value")),
                                Map.of(
                                        "value",
                                        7,
                                        "data",
                                        Collections.nCopies(6000, "x".repeat(3000)))));
    }

    @Test
    void compactExtremeNumericExponentIsRejectedWithoutArithmeticExpansion() {
        var value = new java.math.BigDecimal(java.math.BigInteger.ONE, -10000);
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillRules.validate(Map.of("type", "number", "multipleOf", 3), value));
    }
}
