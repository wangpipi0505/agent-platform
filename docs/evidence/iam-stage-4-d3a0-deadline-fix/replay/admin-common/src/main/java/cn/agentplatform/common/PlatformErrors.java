package cn.agentplatform.common;

import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class PlatformErrors {
    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiResponse<Object>> business(PlatformException e) {
        return ResponseEntity.status(e.status())
                .body(new ApiResponse<>(e.code(), e.getMessage(), null, MDC.get("traceId")));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<ApiResponse<Object>> invalid(Exception e) {
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>("INVALID_INPUT", "请检查必填参数及数据格式", null, MDC.get("traceId")));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    ResponseEntity<ApiResponse<Object>> unavailable(Exception e) {
        return ResponseEntity.status(502)
                .body(
                        new ApiResponse<>(
                                "DEPENDENCY_UNAVAILABLE",
                                "依赖服务暂不可用，请稍后重试",
                                null,
                                MDC.get("traceId")));
    }
}
