package cn.agentplatform.common;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class PlatformErrors {
    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ApiResponse<Object>> business(PlatformException e) {
        return ResponseEntity.status(e.status())
                .body(ApiResponse.error(e.status(), e.code(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    ResponseEntity<ApiResponse<Object>> invalid(Exception e) {
        // 参数形状错误对调用方只暴露统一提示；服务端保留原因，便于定位是哪个字段被拒。
        org.slf4j.LoggerFactory.getLogger(PlatformErrors.class).warn("INVALID_INPUT_CAUSE", e);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "INVALID_INPUT", "请检查必填参数及数据格式"));
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    ResponseEntity<ApiResponse<Object>> unavailable(Exception e) {
        return ResponseEntity.status(502)
                .body(ApiResponse.error(502, "DEPENDENCY_UNAVAILABLE", "依赖服务暂不可用，请稍后重试"));
    }
}
