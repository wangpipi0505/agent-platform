package cn.agentplatform.engine.domain;

import java.util.Map;

/**
 * Runtime validation is a node failure with an inspectable path, never an implicit retry (§30.4.2).
 *
 * <p>PLAT-05（contracts/skill-invocation.md §11.1）：技能 envelope 的统一错误对象
 * （semantic/code/category/retryable/userMessage/detail/traceId 七字段 + reason 兼容键）
 * 经 {@code structured} 原样携带进运行链路，{@link #error()} 只在其上追加引擎元数据
 * （path 与 message 兜底），不降级成 {semantic,reason}、不重算语义；run.error、SSE 事件、
 * 运行详情与模型工具结果因此看到同一结构。无 structured 的旧构造保持原四键形态不变。
 */
public final class RuntimeFault extends RuntimeException {
    public final String semantic, path;
    private final Map<String, Object> structured;

    public RuntimeFault(String semantic, String path, String reason) {
        this(semantic, path, reason, null);
    }

    /** structured 为 envelope 解析出的统一错误对象；null 表示没有结构化证据（旧形态）。 */
    public RuntimeFault(
            String semantic, String path, String reason, Map<String, Object> structuredError) {
        super(reason);
        this.semantic = semantic;
        this.path = path;
        this.structured = structuredError;
    }

    /** 结构化错误对象（可为 null）：调用方据此决定重试、展示与工具结果透传。 */
    public Map<String, Object> structuredError() {
        return structured;
    }

    public Map<String, Object> error() {
        if (structured == null || structured.isEmpty())
            return Map.of(
                    "semantic",
                    semantic,
                    "path",
                    path,
                    "reason",
                    getMessage(),
                    "message",
                    getMessage());
        var error = new java.util.LinkedHashMap<>(structured);
        error.putIfAbsent("semantic", semantic);
        error.put("path", path);
        String message = java.util.Objects.toString(structured.get("userMessage"), "");
        if (message.isBlank()) message = getMessage();
        if (message != null && !message.isBlank()) error.putIfAbsent("message", message);
        return error;
    }
}
