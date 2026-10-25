package cn.agentplatform.engine.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.engine.domain.RunAdmissionRejected;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RunAdmissionErrors {
    @ExceptionHandler(RunAdmissionRejected.class)
    ResponseEntity<ApiResponse<Object>> rejected(RunAdmissionRejected error) {
        return ResponseEntity.status(error.status())
                .header("Retry-After", Integer.toString(error.retryAfterSeconds()))
                .body(
                        new ApiResponse<>(
                                error.code(),
                                error.getMessage(),
                                Map.of("retryAfterSeconds", error.retryAfterSeconds()),
                                MDC.get("traceId")));
    }
}
