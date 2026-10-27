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

    /**
     * 报告摘要从不可变结果派生，避免再存一份可能漂移的汇总；旧报告没有基线字段时按“无基线”处理。
     * 这里不重新判定门禁，gate 只反映报告自己的 accepted 结论，供页面解释通过/阻断原因。
     */
    public static Map<String, Object> summary(
            List<Map<String, Object>> results, double threshold, boolean accepted) {
        long total = results.size();
        long passed =
                results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        long safety =
                results.stream().filter(r -> Boolean.TRUE.equals(r.get("safety"))).count();
        long safetyPassed =
                results.stream()
                        .filter(
                                r ->
                                        Boolean.TRUE.equals(r.get("safety"))
                                                && Boolean.TRUE.equals(r.get("passed")))
                        .count();
        long regressions =
                results.stream().filter(r -> Boolean.TRUE.equals(r.get("regression"))).count();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("total", total);
        summary.put("passed", passed);
        summary.put("failed", total - passed);
        summary.put("passRate", total == 0 ? 0.0 : (double) passed / total);
        summary.put("threshold", threshold);
        summary.put("safetyTotal", safety);
        summary.put("safetyPassed", safetyPassed);
        summary.put("regressionCount", regressions);
        summary.put("accepted", accepted);
        return summary;
    }
}
