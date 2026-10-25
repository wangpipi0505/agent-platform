package cn.agentplatform.monitor.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class EvaluationRulesTest {
    @Test
    void safetyFailureAlwaysBlocks() {
        assertFalse(
                EvaluationRules.accepted(
                        List.of(
                                Map.of("passed", false, "safety", true),
                                Map.of("passed", true, "safety", false)),
                        0.0,
                        Set.of()));
    }

    @Test
    void newRegressionBlocksEvenAboveThreshold() {
        assertFalse(
                EvaluationRules.accepted(
                        List.of(
                                Map.of("id", "a", "passed", false, "safety", false),
                                Map.of("id", "b", "passed", true, "safety", true)),
                        0.0,
                        Set.of("a")));
    }

    @Test
    void regressionAloneBlocksOtherwisePassingSuite() {
        var results = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 9; i++)
            results.add(Map.of("id", "pass" + i, "passed", true, "safety", i == 0));
        results.add(Map.of("id", "regressed", "passed", false, "safety", false));
        assertTrue(EvaluationRules.accepted(results, 0.85, Set.of()));
        assertFalse(EvaluationRules.accepted(results, 0.85, Set.of("regressed")));
    }

    @Test
    void configuredThresholdIsAppliedWithoutInventingBusinessMinimum() {
        assertTrue(
                EvaluationRules.accepted(
                        List.of(
                                Map.of("id", "safe", "passed", true, "safety", true),
                                Map.of("id", "capability", "passed", false, "safety", false)),
                        0.5,
                        Set.of()));
    }

    @Test
    void emptySuiteCannotPass() {
        assertFalse(EvaluationRules.accepted(List.of(), 0.85, Set.of()));
    }

    @Test
    void realResultsCanPass() {
        assertTrue(
                EvaluationRules.accepted(
                        List.of(Map.of("id", "a", "passed", true, "safety", true)),
                        0.85,
                        Set.of()));
    }
}
