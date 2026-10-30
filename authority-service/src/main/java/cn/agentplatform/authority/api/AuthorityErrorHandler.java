package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 错误响应体固定为 {@code {"code":…,"message":…}}：平台侧 {@code AuthorityResponseVerifier} 只解析成功体，
 * 但运维/测试需要稳定的机器可读码；调用方拿不到 stack trace，内部细节只进日志。
 * <p>不用 {@code ApiResponse} 信封——本服务的对外契约是 OAuth/JWT 邻域的形状（#4.2/#4.3），
 * 不是平台业务接口的 {@code {code,message,data,traceId}}。
 */
@RestControllerAdvice
public final class AuthorityErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthorityErrorHandler.class);

    @ExceptionHandler(AuthorityException.class)
    public ResponseEntity<Map<String, String>> handle(AuthorityException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "请求体不可读"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(RuntimeException e) {
        log.error("authority.internal unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "AUTHORITY_INTERNAL_ERROR", "message", "可信授权服务内部错误"));
    }
}
