package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PLAT-05 引擎侧（纯函数部分）：管理台运行详情的错误渲染必须透传统一错误对象的新字段。
 * 契约依据 contracts/skill-invocation.md §11.1：API、SSE、运行详情与模型工具结果使用同一套
 * 七字段语义（semantic/code/category/retryable/userMessage/detail/traceId + reason 兼容键）；
 * 显式 retryable=false 不得被语义推断覆盖；旧 {semantic,reason} 形态保持既有渲染不回归。
 */
class SkillErrorSurfaceTest {

    @Test
    void normalizedErrorSurfacesUnifiedEnvelopeFields() {
        var run = Map.<String, Object>of("status", "FAILED");
        var error =
                Json.tree(
                        Map.of(
                                "semantic", "REMOTE_FAILED",
                                "code", "TLE_CHECKSUM_INVALID",
                                "category", "BUSINESS",
                                "retryable", false,
                                "userMessage", "外部任务失败：TLE 校验和错误",
                                "detail", Map.of("raw", "wrong checksum"),
                                "traceId", "trace-u1",
                                "reason", "wrong checksum"));

        var normalized = RunService.normalizedError(run, List.of(), List.of(), error);

        assertThat(normalized).isNotNull();
        assertThat(normalized.get("semantic")).isEqualTo("REMOTE_FAILED");
        assertThat(normalized.get("userMessage")).isEqualTo("外部任务失败：TLE 校验和错误");
        assertThat(normalized.get("traceId")).isEqualTo("trace-u1");
        assertThat(normalized.get("errorCode")).isEqualTo("TLE_CHECKSUM_INVALID");
        assertThat(normalized.get("category")).isEqualTo("BUSINESS");
        // 显式 retryable=false 必须按声明呈现，不得由 semantic 推断成 true。
        assertThat(normalized.get("retryable")).isEqualTo(false);
        assertThat(normalized.get("message")).asString().isEqualTo("wrong checksum");
        // 完整原始错误仍随 raw 保留，供诊断下钻。
        assertThat(((JsonNode) normalized.get("raw")).path("detail").path("raw").asText())
                .isEqualTo("wrong checksum");
    }

    @Test
    void normalizedErrorExplicitRetryableTrueIsHonoredOverSemanticInference() {
        var run = Map.<String, Object>of("status", "FAILED");
        var error =
                Json.tree(
                        Map.of(
                                "semantic", "QUOTA",
                                "category", "QUOTA",
                                "retryable", true,
                                "userMessage", "调用频次超限，请稍后重试",
                                "traceId", "trace-u2"));

        var normalized = RunService.normalizedError(run, List.of(), List.of(), error);

        assertThat(normalized.get("retryable")).isEqualTo(true);
        assertThat(normalized.get("userMessage")).isEqualTo("调用频次超限，请稍后重试");
        assertThat(normalized.get("traceId")).isEqualTo("trace-u2");
    }

    @Test
    void legacySemanticReasonErrorKeepsLegacyRendering() {
        var run = Map.<String, Object>of("status", "FAILED");
        var error = Json.tree(Map.of("semantic", "TOOL_ENDPOINT_FAILURE", "reason", "HTTP 500"));

        var normalized = RunService.normalizedError(run, List.of(), List.of(), error);

        // 旧形态没有新字段：渲染不得发明字段值，重试性仍按语义推断。
        assertThat(normalized.get("code")).isEqualTo("TOOL_ENDPOINT_FAILURE");
        assertThat(normalized.get("message")).isEqualTo("HTTP 500");
        assertThat(normalized.get("retryable")).isEqualTo(true);
        assertThat(normalized).doesNotContainKeys("userMessage", "traceId", "errorCode", "category");
    }

    @Test
    void effectiveErrorReturnsEnvelopeErrorVerbatimForRunDetail() {
        var nodeError =
                Json.tree(
                        Map.of(
                                "semantic", "NO_PERMISSION",
                                "code", "DATA_SCOPE_DENIED",
                                "category", "AUTH",
                                "retryable", false,
                                "userMessage", "无权访问其他部门数据",
                                "traceId", "trace-u3",
                                "reason", "无权访问其他部门数据"));
        var run = Map.<String, Object>of("status", "FAILED", "error", Json.tree(Map.of()));
        var nodes =
                List.<Map<String, Object>>of(
                        Map.of("id", 7L, "status", "FAILED", "error", nodeError));

        var error = RunService.effectiveError(run, nodes, List.of());

        // run.error 表面（组合状态的 error 字段）必须原样携带统一结构，不能中途降级。
        assertThat(error).isEqualTo(nodeError);
        assertThat(error.path("traceId").asText()).isEqualTo("trace-u3");
        assertThat(error.path("userMessage").asText()).isEqualTo("无权访问其他部门数据");
    }

    // ---- envelopeError（AgentDriver）解析契约：七字段 + 兼容键不丢不造 ----

    @Test
    void envelopeErrorKeepsSevenFieldsAndCompatKeys() {
        var response =
                Json.tree(
                        Map.of(
                                "status", "FAILED",
                                "result",
                                        Map.of(
                                                "error",
                                                Map.of(
                                                        "semantic", "REMOTE_FAILED",
                                                        "code", "TLE_CHECKSUM_INVALID",
                                                        "category", "BUSINESS",
                                                        "retryable", false,
                                                        "userMessage", "外部任务失败：TLE 校验和错误",
                                                        "detail", Map.of("raw", "wrong checksum"),
                                                        "traceId", "trace-p1",
                                                        "reason", "诊断原文"))));

        var error = AgentDriver.envelopeError(response.path("result"), null);

        assertThat(error).isNotNull();
        assertThat(error.get("semantic")).isEqualTo("REMOTE_FAILED");
        assertThat(error.get("code")).isEqualTo("TLE_CHECKSUM_INVALID");
        assertThat(error.get("category")).isEqualTo("BUSINESS");
        assertThat(error.get("retryable")).isEqualTo(false);
        assertThat(error.get("userMessage")).isEqualTo("外部任务失败：TLE 校验和错误");
        assertThat(error.get("detail")).isEqualTo(Map.of("raw", "wrong checksum"));
        assertThat(error.get("traceId")).isEqualTo("trace-p1");
        assertThat(error.get("reason")).isEqualTo("诊断原文");
    }

    @Test
    void envelopeErrorKeepsLegacyShapesWithoutInventingFields() {
        var legacyReason =
                AgentDriver.envelopeError(
                        Json.tree(Map.of("error", Map.of("semantic", "NO_PERMISSION", "reason", "无权访问其他部门数据"))),
                        null);
        assertThat(legacyReason)
                .containsEntry("semantic", "NO_PERMISSION")
                .containsEntry("reason", "无权访问其他部门数据")
                .doesNotContainKeys("code", "category", "retryable", "userMessage", "detail", "traceId");

        var legacyMessage =
                AgentDriver.envelopeError(
                        Json.tree(Map.of("error", Map.of("semantic", "PARAM_ERROR", "message", "invalid parameter"))),
                        null);
        assertThat(legacyMessage)
                .containsEntry("semantic", "PARAM_ERROR")
                .containsEntry("message", "invalid parameter")
                .doesNotContainKeys("userMessage", "traceId");
    }

    @Test
    void envelopeErrorFallsBackToCompatSemanticKeyThenNull() {
        var wrap = Json.tree(Map.of("status", "FAILED", "errorSemantic", "PARAM_ERROR"));
        assertThat(AgentDriver.envelopeError(wrap.path("result"), wrap.path("errorSemantic").asText(null)))
                .containsEntry("semantic", "PARAM_ERROR");

        // 兼容键也没有时返回 null，调用方用 SKILL_<status> 兜底，不发明语义。
        assertThat(
                        AgentDriver.envelopeError(
                                Json.tree(Map.of("modelSummary", "x")), null))
                .isNull();
    }

    // ---- RuntimeFault 结构化合并契约：只追加引擎元数据，不覆盖统一对象 ----

    @Test
    void runtimeFaultCarriesStructuredErrorAndAppendsEngineMetadata() {
        var structured =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "semantic", "REMOTE_FAILED",
                                "code", "TLE_CHECKSUM_INVALID",
                                "retryable", false,
                                "userMessage", "外部任务失败：TLE 校验和错误",
                                "traceId", "trace-p2",
                                "reason", "诊断原文"));
        var fault =
                new cn.agentplatform.engine.domain.RuntimeFault(
                        "REMOTE_FAILED", "toolCall.9", "外部任务失败：TLE 校验和错误", structured);

        assertThat(fault.structuredError()).isSameAs(structured);
        var error = fault.error();
        assertThat(error.get("semantic")).isEqualTo("REMOTE_FAILED");
        assertThat(error.get("retryable")).isEqualTo(false);
        assertThat(error.get("userMessage")).isEqualTo("外部任务失败：TLE 校验和错误");
        assertThat(error.get("traceId")).isEqualTo("trace-p2");
        assertThat(error.get("reason")).isEqualTo("诊断原文");
        // 引擎元数据只追加不覆盖：path 是引擎侧定位，message 兜底为 userMessage。
        assertThat(error.get("path")).isEqualTo("toolCall.9");
        assertThat(error.get("message")).isEqualTo("外部任务失败：TLE 校验和错误");
    }

    @Test
    void runtimeFaultLegacyConstructorKeepsOriginalShape() {
        var fault = new cn.agentplatform.engine.domain.RuntimeFault("OUTPUT_INVALID", "output", "模型返回不是有效JSON");

        assertThat(fault.structuredError()).isNull();
        assertThat(fault.error())
                .containsEntry("semantic", "OUTPUT_INVALID")
                .containsEntry("path", "output")
                .containsEntry("reason", "模型返回不是有效JSON")
                .containsEntry("message", "模型返回不是有效JSON");
    }
}
