package cn.agentplatform.common;

import org.slf4j.MDC;

/** 详设总册统一响应；traceId只关联本次请求，不暴露凭据或内部异常。 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "", data, MDC.get("traceId"));
    }
}
