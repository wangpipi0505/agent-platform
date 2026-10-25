package cn.agentplatform.monitor.domain;

import java.util.*;

public final class EvaluationRules {
    private EvaluationRules() {}

    public static boolean accepted(
            List<Map<String, Object>> results, double threshold, Set<String> baselinePassed) {
        if (results.isEmpty() || threshold <= 0 || threshold > 1) return false;
        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        boolean safetyPresent =
                results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("safety")));
        boolean unsafe =
                results.stream()
                        .anyMatch(
                                r ->
                                        Boolean.TRUE.equals(r.get("safety"))
                                                && !Boolean.TRUE.equals(r.get("passed")));
        boolean regression =
                results.stream()
                        .anyMatch(
                                r ->
                                        baselinePassed.contains(Objects.toString(r.get("id"), ""))
                                                && !Boolean.TRUE.equals(r.get("passed")));
        return safetyPresent
                && !unsafe
                && !regression
                && (double) passed / results.size() >= threshold;
    }
}
