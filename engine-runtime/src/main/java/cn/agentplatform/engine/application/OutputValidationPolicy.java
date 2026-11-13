package cn.agentplatform.engine.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A15b 模型输出 Schema 校验策略（评审十九轮，1109 推理4；主方案 §6.7、接口稿 §5.5）。
 *
 * <p><b>职责一（装配层翻译）</b>：启用新结构化输出策略时（manifest.model.inferencePolicy
 * 含 {@code outputSchema} 对象），将 Schema 与 {@code retry.on} 统一转换为既有
 * {@link OutputPolicy} 的 output 契约——{@code retry.on} 含 {@code OUTPUT_SCHEMA_INVALID}
 * 时为 RETRY（重试上限=retry.max，每次实际调用计入同一预算，重试上限取 retry.max 与
 * 首次调用后剩余模型调用预算的较小值，预算扣减由 ModelExchange 统一执行），其余为
 * TERMINATE（首次失败即终止）。未配置新策略的存量发布返回 null，沿用原 onInvalid 行为。
 *
 * <p><b>职责二（诊断投影）</b>：既有校验错误 {@code OUTPUT_INVALID} 在新策略启用时投影为
 * {@code OUTPUT_SCHEMA_INVALID}——校验失败原因（reason）与策略动作（onInvalid）随诊断写
 * {@code model_invocation.output_validation}（V213 既有列，复用不新增），上层不再据此
 * 额外重试。翻译产物在 output 契约上携带 {@code reasonCode} 标记随 final_request 固化，
 * ModelExchange 据此判定投影口径（存量发布无标记，原样 OUTPUT_INVALID）。
 *
 * <p>{@code ModelExchange} 是输出校验重试的唯一执行处（§5.5），本类只做翻译与诊断注记，
 * 不自建重试循环。
 */
public final class OutputValidationPolicy {

    /** 校验失败原因码（1109 推理4：输出校验失败记录 OUTPUT_SCHEMA_INVALID 及处理结果）。 */
    public static final String REASON_CODE = "OUTPUT_SCHEMA_INVALID";

    /** 存量发布（未启用新策略）的诊断原因码，保持既有 OUTPUT_INVALID 不变。 */
    public static final String LEGACY_REASON_CODE = "OUTPUT_INVALID";

    private OutputValidationPolicy() {}

    /**
     * 推理策略是否启用新结构化输出策略（inferencePolicy 含 outputSchema 对象即启用，
     * 接口稿 §5.5 manifest 形状）。
     */
    public static boolean enabled(JsonNode inferencePolicy) {
        return inferencePolicy != null
                && inferencePolicy.isObject()
                && inferencePolicy.path("outputSchema").isObject();
    }

    /**
     * manifest.model.inferencePolicy → 既有 OutputPolicy 的 output 契约；未启用返回 null
     * （存量发布沿用原策略）。
     */
    public static JsonNode translate(JsonNode inferencePolicy) {
        if (!enabled(inferencePolicy)) return null;
        boolean retryable = false;
        for (JsonNode on : inferencePolicy.path("retry").path("on"))
            if (REASON_CODE.equals(on.asText())) retryable = true;
        var max = inferencePolicy.path("retry").path("max");
        if (!max.isMissingNode() && !max.isNull()
                && (!max.isIntegralNumber() || !max.canConvertToInt() || max.intValue() < 0))
            throw new PlatformException(409, "OUTPUT_POLICY_INVALID", "推理策略 retry.max 必须为非负整数");
        var output = Json.object();
        var format = output.putObject("format");
        format.put("type", "json");
        format.set("schema", inferencePolicy.path("outputSchema").deepCopy());
        output.put("onInvalid", retryable ? "RETRY" : "TERMINATE");
        output.put("maxRetries", max.asInt(2));
        output.put("reasonCode", REASON_CODE);
        return output;
    }

    /**
     * 装配层挂载（主方案 §6.7：启用新策略时由装配层转换）。config.output 已有 format
     * 契约时跳过（存量显式契约优先，沿用原 onInvalid 行为），否则以翻译产物挂载。
     */
    public static void applyFromManifest(JsonNode config) {
        var translated = translate(config.path("model").path("inferencePolicy"));
        if (translated == null) return;
        var output = config.path("output");
        if (output.isObject() && output.path("format").isObject()) return;
        ((ObjectNode) config).set("output", translated);
    }

    /**
     * 诊断原因码：翻译产物携带 reasonCode 标记 → OUTPUT_SCHEMA_INVALID；存量发布
     * （无标记）→ 原 OUTPUT_INVALID。
     */
    public static String reasonCode(JsonNode output) {
        return output.path("reasonCode").asText("").equals(REASON_CODE)
                ? REASON_CODE
                : LEGACY_REASON_CODE;
    }

    /**
     * 诊断注记：校验失败（valid=false）且新策略启用时把 semantic 投影为
     * OUTPUT_SCHEMA_INVALID；reason（校验失败原因）与 onInvalid（策略动作）保持
     * OutputPolicy.diagnostic 既有形状。valid 诊断不投影（成功无失败原因码）。
     */
    public static Map<String, Object> annotate(Map<String, Object> diagnostic, JsonNode output) {
        if (Boolean.TRUE.equals(diagnostic.get("valid"))) return diagnostic;
        if (!reasonCode(output).equals(REASON_CODE)) return diagnostic;
        var annotated = new LinkedHashMap<>(diagnostic);
        annotated.put("semantic", REASON_CODE);
        return annotated;
    }
}
