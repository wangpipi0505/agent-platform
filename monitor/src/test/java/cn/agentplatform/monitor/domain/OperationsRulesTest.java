package cn.agentplatform.monitor.domain;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OperationsRulesTest {
    @Test
    void windowRejectsValuesOutsideFiveTo1440Minutes() {
        assertEquals(5, OperationsRules.window(5));
        assertEquals(1440, OperationsRules.window(1440));
        var tooSmall = assertThrows(RuntimeException.class, () -> OperationsRules.window(4));
        assertEquals("OPS_WINDOW_INVALID", ((cn.agentplatform.common.PlatformException) tooSmall).code());
        assertThrows(RuntimeException.class, () -> OperationsRules.window(1441));
    }

    @Test
    void anUnhealthyDependencyRaisesOneCriticalAlertWithItsReason() {
        Map<String, Object> check =
                Map.of("key", "engine", "label", "运行时", "ok", false, "detail", "连接失败");
        var alerts = OperationsRules.evaluate(Map.of("checks", List.of(check), "runs", Map.of()));
        assertEquals(1, alerts.size());
        assertEquals("SERVICE_UNREACHABLE", alerts.get(0).ruleCode());
        assertEquals("engine", alerts.get(0).subject());
        assertEquals("CRITICAL", alerts.get(0).severity());
        assertTrue(alerts.get(0).detail().contains("连接失败"));
    }

    @Test
    void healthyDependencyDoesNotRaiseAnAlert() {
        Map<String, Object> check =
                Map.of("key", "app", "label", "应用管理", "ok", true, "detail", "运行正常");
        assertTrue(OperationsRules.evaluate(Map.of("checks", List.of(check))).isEmpty());
    }

    @Test
    void smallCohortNeverRaisesFailureRateNoise() {
        var alerts =
                OperationsRules.evaluate(
                        Map.of("runs", Map.of("completed", 19, "failed", 19)));
        assertTrue(alerts.isEmpty());
    }

    @Test
    void failureRateUsesWarningAtTwentyPercentAndCriticalAtFifty() {
        var warning =
                OperationsRules.evaluate(
                        Map.of("runs", Map.of("completed", 20, "failed", 5)));
        assertEquals(1, warning.size());
        assertEquals("WARNING", warning.get(0).severity());

        var critical =
                OperationsRules.evaluate(
                        Map.of("runs", Map.of("completed", 20, "failed", 10)));
        assertEquals("CRITICAL", critical.get(0).severity());
        assertTrue(critical.get(0).detail().contains("50.0%"));
    }

    @Test
    void liveSnapshotNestsRunSummaryUnderRuns() {
        var alerts =
                OperationsRules.evaluate(
                        Map.of(
                                "runs",
                                Map.of(
                                        "runs",
                                        Map.of("completed", 20, "failed", 10),
                                        "model",
                                        Map.of("calls", 3))));
        assertEquals(1, alerts.size());
        assertEquals("RUN_FAILURE_RATE", alerts.get(0).ruleCode());
    }

    @Test
    void malformedProjectionDoesNotThrowOrInventAnAlert() {
        var projection = new HashMap<String, Object>();
        projection.put("checks", List.of("not-a-map", Map.of("key", "x", "ok", "no")));
        projection.put("runs", Map.of("completed", "nope"));
        assertTrue(OperationsRules.evaluate(projection).isEmpty());
    }
}
