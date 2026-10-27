package cn.agentplatform.skill.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * PLAT-05 统一错误语义（方案 §4.5/§4.16）。所有适配器失败最终都经 {@link #error} 落成同一套
 * 七字段结构（semantic/code/category/retryable/userMessage/detail/traceId，另保留既有
 * {@code reason} 键作兼容），让 API、SSE、运行详情与模型工具结果看到同一语义。
 *
 * <p>本类型是纯函数域模型：不做 IO、不读 MDC——traceId 由调用方显式传入，便于测试与复用。
 * 分类（category）与可重试（retryable）是语义的派生属性，缺省表是唯一事实来源；契约侧
 * {@code errorSemantics} 声明（codePath/messagePath/retryableCodes）只改判语义与补充
 * 可读信息，不能推翻终态结论（例如 REMOTE_FAILED 永不因命中 retryableCodes 变为可重试）。
 */
public final class ErrorSemantics {
    /** 错误大类：参数 / 鉴权 / 配额 / 传输与未知 / 远端业务 / 平台系统。 */
    public enum Category { PARAM, AUTH, QUOTA, TRANSPORT, BUSINESS, SYSTEM }

    private record Default(Category category, boolean retryable, String userMessage) {}

    private static final int MAX_RAW = 512;
    private static final int MAX_MESSAGE = 200;

    private static final Map<String, Default> DEFAULTS =
            Map.ofEntries(
                    Map.entry("PARAM_ERROR", new Default(Category.PARAM, false, "调用参数无效，请检查后重试")),
                    Map.entry("NO_PERMISSION", new Default(Category.AUTH, false, "无权执行该操作")),
                    Map.entry("QUOTA", new Default(Category.QUOTA, true, "调用频次或配额超限，请稍后重试")),
                    Map.entry("RETRYABLE_FAILURE", new Default(Category.TRANSPORT, true, "外部服务暂时不可用，请稍后重试")),
                    Map.entry(
                            "RESULT_UNKNOWN",
                            new Default(Category.TRANSPORT, false, "执行结果未知，已进入对账核实，请稍后查询")),
                    Map.entry("REMOTE_FAILED", new Default(Category.BUSINESS, false, "外部任务失败")),
                    Map.entry(
                            "SUBMIT_NO_TASK_ID",
                            new Default(Category.BUSINESS, false, "外部提交未返回任务标识，无法确认提交结果")),
                    Map.entry("OUTPUT_INVALID", new Default(Category.SYSTEM, false, "外部返回结果不符合约定格式")),
                    Map.entry("NON_RETRYABLE_FAILURE", new Default(Category.BUSINESS, false, "外部任务已核实失败")));

    /**
     * 契约 errorSemantics 的对象形态（方案 §4.5）：声明远端错误码/原因的读取路径与“视为可重试”的
     * 业务码集合。v1.2 的数组形态（match 列表）从未被执行侧解释过，这里同样按未声明处理并原样
     * 透传，避免两类形态互相污染。
     */
    public record Declared(String codePath, String messagePath, Set<String> retryableCodes) {
        public static final Declared NONE = new Declared(null, null, Set.of());

        public static Declared parse(Object declaration) {
            if (!(declaration instanceof Map<?, ?> map) || map.isEmpty()) return NONE;
            String codePath = path(map.get("codePath"), "codePath");
            String messagePath = path(map.get("messagePath"), "messagePath");
            var codes = new LinkedHashSet<String>();
            if (map.containsKey("retryableCodes") && map.get("retryableCodes") != null) {
                if (!(map.get("retryableCodes") instanceof Collection<?> values))
                    throw new IllegalArgumentException("errorSemantics.retryableCodes 必须为字符串数组");
                for (Object value : values) {
                    if (!(value instanceof String code) || code.isBlank())
                        throw new IllegalArgumentException("errorSemantics.retryableCodes 必须为字符串数组");
                    codes.add(code);
                }
            }
            return new Declared(codePath, messagePath, Set.copyOf(codes));
        }

        private static String path(Object value, String field) {
            if (value == null) return null;
            String path = value.toString();
            // 与 HttpAsyncAdapter.path 同一口径的受控点路径：根路径没有读取意义，直接拒绝。
            if (!path.matches("\\$\\.[A-Za-z_][A-Za-z0-9_.]*"))
                throw new IllegalArgumentException("errorSemantics." + field + " 仅支持点路径");
            return path;
        }
    }

    /** 从远端错误响应体中提取的业务码与原文（可能都缺失）。 */
    public record Remote(String code, String message) {
        public static final Remote NONE = new Remote(null, null);
    }

    private ErrorSemantics() {}

    /**
     * 七字段错误对象。{@code raw} 是远端原文（有界）：有则拼进中文 userMessage 并落
     * {@code detail.raw}，无则 userMessage 用语义缺省模板、detail 为 null。
     * {@code reason} 保留改造前的诊断原文，属于兼容键而非第七字段之外的新语义。
     */
    public static Map<String, Object> error(
            String semantic, String code, String reason, String raw, String traceId) {
        var semanticKey = Objects.toString(semantic, "").trim();
        var fallback = new Default(Category.SYSTEM, false, "技能执行失败");
        var defaults = DEFAULTS.getOrDefault(semanticKey, fallback);
        var boundedRaw = bound(raw);
        var result = new LinkedHashMap<String, Object>();
        result.put("semantic", semantic);
        result.put("code", code);
        result.put("category", defaults.category().name());
        result.put("retryable", defaults.retryable());
        result.put(
                "userMessage",
                boundedRaw == null
                        ? defaults.userMessage()
                        : defaults.userMessage() + "：" + truncate(boundedRaw, MAX_MESSAGE));
        result.put("detail", boundedRaw == null ? null : Map.of("raw", boundedRaw));
        result.put("traceId", traceId);
        result.put("reason", reason);
        return result;
    }

    /**
     * 按声明（或平台缺省 {@code $.code/$.message}）读取远端错误响应体。非 JSON 对象、缺字段、
     * 非文本值都按缺失处理——错误分类此时退回 HTTP 状态默认，不影响调用方语义。
     */
    public static Remote extract(JsonNode body, Declared declared) {
        if (body == null || !body.isObject()) return Remote.NONE;
        String code =
                text(
                        body,
                        declared.codePath() != null ? declared.codePath() : "$.code");
        String message =
                text(
                        body,
                        declared.messagePath() != null ? declared.messagePath() : "$.message");
        return new Remote(code, message);
    }

    /** 声明的 retryableCodes 命中远端业务码时，HTTP 状态默认分类改判为 RETRYABLE_FAILURE。 */
    public static boolean retryableCode(Declared declared, Remote remote) {
        return remote.code() != null && declared.retryableCodes().contains(remote.code());
    }

    /** 远端原文进入 JSONB 与 varchar 的统一有界化（含省略号仍不超过上限）。 */
    public static String bound(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        return value.length() <= MAX_RAW ? value : value.substring(0, MAX_RAW - 1) + "…";
    }

    private static String text(JsonNode body, String path) {
        JsonNode value = body;
        for (String part : path.substring(2).split("\\.")) value = value.path(part);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }
}
