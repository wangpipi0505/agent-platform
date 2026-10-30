package cn.agentplatform.common;

import org.slf4j.MDC;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 详设总册统一响应；traceId只关联本次请求，不暴露凭据或内部异常。 */
public record ApiResponse<T>(String code, String message, T data, String traceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String userMessage,
        @JsonInclude(JsonInclude.Include.NON_NULL) String category,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean retryable) {
    /** 旧成功/业务调用的四参源码入口与 JSON 形状保持兼容。 */
    public ApiResponse(String code, String message, T data, String traceId) {
        this(code, message, data, traceId, null, null, null);
    }

    /** API 层只声明可安全重试的入口限流；不能据通用 5xx 推断带副作用操作可重发。 */
    public static ApiResponse<Object> error(int status, String code, String message) {
        String category = status == 401 || status == 403 ? "AUTH"
                : status == 429 ? "QUOTA"
                : status == 400 || status == 422 ? "PARAM"
                : status == 502 || status == 503 || status == 504 ? "TRANSPORT"
                : status >= 500 ? "SYSTEM" : "BUSINESS";
        return new ApiResponse<>(code, message, null, TraceContextFilter.current(), message, category, status == 429);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "", data, MDC.get("traceId"));
    }
}
