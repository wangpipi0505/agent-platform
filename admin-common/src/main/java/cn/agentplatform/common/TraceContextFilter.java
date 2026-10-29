package cn.agentplatform.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request correlation. An inbound X-Trace-Id is accepted only when it is a bounded opaque
 * token, so a caller cannot inject log forging characters or unbounded MDC values. Generated
 * IDs are UUIDs. The same value is returned in the response and in error envelopes; downstream
 * calls may forward it explicitly, but identity/authority is never carried in this header.
 */
@Component
public class TraceContextFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String trace = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put("traceId", trace);
        res.setHeader(HEADER, trace);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** 运行工作线程只绑定本 run 的冻结关联值；旧记录缺失时仅生成当次值，不回写历史。 */
    public static Scope bind(String frozenTrace) {
        String previous = MDC.get("traceId");
        String trace = frozenTrace != null && SAFE.matcher(frozenTrace).matches()
                ? frozenTrace : UUID.randomUUID().toString();
        MDC.put("traceId", trace);
        return () -> {
            if (previous == null) MDC.remove("traceId");
            else MDC.put("traceId", previous);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    /** Current request's correlation ID, or a fresh one outside a request (e.g. scheduler). */
    public static String current() {
        String trace = MDC.get("traceId");
        return trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
    }
}
