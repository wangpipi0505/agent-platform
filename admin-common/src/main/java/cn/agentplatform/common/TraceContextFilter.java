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

    /** Current request's correlation ID, or a fresh one outside a request (e.g. scheduler). */
    public static String current() {
        String trace = MDC.get("traceId");
        return trace == null || trace.isBlank() ? UUID.randomUUID().toString() : trace;
    }
}
