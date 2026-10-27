package cn.agentplatform.monitor.domain;

import cn.agentplatform.common.PlatformException;

import java.util.*;

/**
 * Pure alert policy. It receives only the live projection, so an alert can never be created
 * from a guessed metric. Service outages are critical; a completed-cohort failure rate is
 * warning at 20% and critical at 50%, with a minimum cohort to avoid one failed run becoming
 * an alert storm.
 */
public final class OperationsRules {
    public static final int MIN_COMPLETED_RUNS = 20;
    public static final double WARNING_FAILURE_RATE = 0.20;
    public static final double CRITICAL_FAILURE_RATE = 0.50;

    private OperationsRules() {}

    public record Alert(
            String ruleCode, String subject, String severity, String title, String detail) {}

    public static int window(int minutes) {
        if (minutes < 5 || minutes > 1440)
            throw new PlatformException(400, "OPS_WINDOW_INVALID", "时间窗口必须在5到1440分钟之间");
        return minutes;
    }

    public static List<Alert> evaluate(Map<String, Object> overview) {
        List<Alert> alerts = new ArrayList<>();
        if (overview.get("checks") instanceof List<?> checks) {
            for (Object item : checks) {
                if (!(item instanceof Map<?, ?> check)) continue;
                if (Boolean.FALSE.equals(check.get("ok"))) {
                    String key = text(check.get("key"), "unknown");
                    String label = text(check.get("label"), key);
                    String reason = text(check.get("detail"), "健康检查失败");
                    alerts.add(
                            new Alert(
                                    "SERVICE_UNREACHABLE",
                                    key,
                                    "CRITICAL",
                                    "依赖服务不可达：" + label,
                                    reason));
                }
            }
        }
        if (overview.get("runs") instanceof Map<?, ?> runs) {
            // The live snapshot embeds the engine projection whole (runs.runs); a unit caller
            // may pass the compact run summary directly. Accept both without guessing values.
            if (runs.get("runs") instanceof Map<?, ?> nested) runs = nested;
            long completed = number(runs.get("completed"));
            long failed = number(runs.get("failed"));
            if (completed >= MIN_COMPLETED_RUNS) {
                double rate = failed / (double) completed;
                if (rate >= WARNING_FAILURE_RATE) {
                    String percent = String.format(Locale.ROOT, "%.1f%%", rate * 100);
                    alerts.add(
                            new Alert(
                                    "RUN_FAILURE_RATE",
                                    "run-failure-rate",
                                    rate >= CRITICAL_FAILURE_RATE ? "CRITICAL" : "WARNING",
                                    "运行失败率偏高",
                                    "窗口内完成 " + completed + " 次运行，失败 " + failed
                                            + " 次，失败率 " + percent + "（阈值 20%）"));
                }
            }
        }
        return alerts;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Object value, String fallback) {
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() ? fallback : (text.length() > 300 ? text.substring(0, 300) : text);
    }
}
