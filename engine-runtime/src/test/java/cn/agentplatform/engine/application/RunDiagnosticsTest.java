package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.agentplatform.common.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AP-S07：失败运行必须有可读根因，诊断字段来自持久记录而非前端推断。 */
class RunDiagnosticsTest {

    @Test
    void failedRunWithoutRunErrorFallsBackToFirstNodeError() {
        var nodeError =
                Json.tree(
                        Map.of(
                                "semantic",
                                "TOOL_ENDPOINT_FAILURE",
                                "message",
                                "外部端点HTTP 500"));
        var run = Map.<String, Object>of("status", "FAILED", "error", Json.tree(Map.of()));
        var nodes =
                List.<Map<String, Object>>of(
                        Map.of("id", 7L, "status", "FAILED", "error", nodeError));

        var error = RunService.effectiveError(run, nodes, List.of());
        var normalized = RunService.normalizedError(run, nodes, List.of(), error);

        assertThat(error.path("semantic").asText()).isEqualTo("TOOL_ENDPOINT_FAILURE");
        assertThat(normalized).isNotNull();
        assertThat(normalized.get("code")).isEqualTo("TOOL_ENDPOINT_FAILURE");
        assertThat(normalized.get("stage")).isEqualTo("工具调用");
        assertThat(normalized.get("retryable")).isEqualTo(true);
        assertThat(normalized.get("nodeInstanceId")).isEqualTo(7L);
        assertThat(normalized.get("message")).asString().contains("外部端点");
    }

    @Test
    void failedRunWithoutAnyErrorGetsExplicitSentinel() {
        var run = Map.<String, Object>of("status", "FAILED", "error", Json.tree(Map.of()));
        var error = RunService.effectiveError(run, List.of(), List.of());
        var normalized = RunService.normalizedError(run, List.of(), List.of(), error);

        assertThat(normalized).isNotNull();
        assertThat(normalized.get("code")).isEqualTo("RUN_FAILED_WITHOUT_ERROR");
        assertThat(normalized.get("message")).asString().contains("没有记录可诊断的错误");
    }

    @Test
    void successfulRunDoesNotRenderAnEmptyErrorAsFailure() {
        var run = Map.<String, Object>of("status", "SUCCEEDED", "error", Json.tree(Map.of()));
        var error = RunService.effectiveError(run, List.of(), List.of());
        assertThat(error.isEmpty()).isTrue();
        assertThat(RunService.normalizedError(run, List.of(), List.of(), error)).isNull();
    }

    @Test
    void modelAndToolErrorsCarryStageRetryabilityAndLinkedCall() {
        var run = Map.<String, Object>of("status", "FAILED");
        var model =
                Json.tree(
                        Map.of(
                                "semantic",
                                "MODEL_UPSTREAM_401",
                                "message",
                                "模型鉴权失败"));
        var modelResult = RunService.normalizedError(run, List.of(), List.of(), model);
        assertThat(modelResult.get("stage")).isEqualTo("模型调用");
        // 模型 401 是凭据错误，重试不会成功；只有上游暂时不可用/限流才可重试。
        assertThat(modelResult.get("retryable")).isEqualTo(false);
        assertThat(modelResult.get("hint")).asString().contains("模型供应商");

        var tool =
                Json.tree(
                        Map.of(
                                "semantic",
                                "TOOL_ENDPOINT_FAILURE",
                                "message",
                                "工具端点失败",
                                "callId",
                                9));
        var calls =
                List.<Map<String, Object>>of(
                        Map.of("id", 9L, "status", "FAILED", "skillCode", "space_visibility"));
        var toolResult = RunService.normalizedError(run, List.of(), calls, tool);
        assertThat(toolResult.get("stage")).isEqualTo("工具调用");
        assertThat(toolResult.get("toolCallId")).isEqualTo(9L);
        assertThat(toolResult.get("hint")).asString().contains("工具调用");

        var rateLimit =
                Json.tree(
                        Map.of(
                                "semantic",
                                "MODEL_RATE_LIMIT_429",
                                "message",
                                "供应商限流"));
        assertThat(RunService.normalizedError(run, List.of(), List.of(), rateLimit).get("retryable"))
                .isEqualTo(true);
    }

    @Test
    void parameterAndAuthErrorsAreNotRetryable() {
        var run = Map.<String, Object>of("status", "FAILED");
        var param = Json.tree(Map.of("semantic", "INPUT_INVALID", "message", "输入非法"));
        var auth = Json.tree(Map.of("semantic", "AUTH_DENIED", "message", "无权执行"));
        assertThat(RunService.normalizedError(run, List.of(), List.of(), param).get("retryable"))
                .isEqualTo(false);
        assertThat(RunService.normalizedError(run, List.of(), List.of(), auth).get("retryable"))
                .isEqualTo(false);
    }

    @Test
    void genericRetryableFailureUsesFailedToolReasonAsRootCause() {
        var run = Map.<String, Object>of("status", "FAILED");
        var error =
                Json.tree(
                        Map.of(
                                "semantic",
                                "RETRYABLE_FAILURE",
                                "message",
                                "RETRYABLE_FAILURE"));
        var calls =
                List.<Map<String, Object>>of(
                        Map.of(
                                "id",
                                142L,
                                "status",
                                "FAILED",
                                "skillCode",
                                "space_visibility",
                                "result",
                                Json.tree(
                                        Map.of(
                                                "error",
                                                Map.of(
                                                        "reason",
                                                        "外部端点HTTP 500")))));

        var normalized = RunService.normalizedError(run, List.of(), calls, error);

        assertThat(normalized.get("message")).isEqualTo("外部端点HTTP 500");
        assertThat(normalized.get("stage")).isEqualTo("工具调用");
        assertThat(normalized.get("toolCallId")).isEqualTo(142L);
        assertThat(normalized.get("retryable")).isEqualTo(true);
        assertThat(normalized.get("hint")).asString().contains("工具调用");
    }

    @Test
    void modelDispatchEventErrorIsUsedWhenNodeAndRunHaveNoError() {
        var run = Map.<String, Object>of("status", "FAILED", "error", Json.tree(Map.of()));
        var event =
                Map.<String, Object>of(
                        "type",
                        "MODEL_DISPATCH_FAILED",
                        "seq",
                        3,
                        "payload",
                        Json.tree(
                                Map.of(
                                        "error",
                                        Map.of(
                                                "semantic",
                                                "MODEL_PROVIDER_UNAVAILABLE",
                                                "message",
                                                "供应商不可用"))));
        var error = RunService.effectiveError(run, List.of(), List.of(event));
        assertThat(error.path("semantic").asText()).isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    }
}
