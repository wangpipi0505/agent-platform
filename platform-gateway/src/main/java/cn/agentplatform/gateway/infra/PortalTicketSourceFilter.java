package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.authority.PortalRuntimeRoutes;
import cn.agentplatform.common.authority.PortalTaskRoutes;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * EX-43 Q4: the SSE (and the two sibling portal observations) ticket comes from the
 * {@code Authorization} header only. A query-string credential survives in access logs, browser
 * history, proxies and referrers, so the only safe handling is an explicit rejection instead of an
 * ignored parameter: an ignored {@code ?access_token=} still lets the caller believe it worked while
 * the real credential is a different one.
 *
 * <p>Ordered before the security filter chain on purpose. Rejecting a misplaced credential is a
 * request-shape decision, not an authentication outcome: if this ran after the chain, a request that
 * carries only {@code ?access_token=} would be answered with {@code 401} (it has no usable header) and
 * the caller could not tell "wrong carrier" from "invalid ticket". Running first also means the
 * parameter is never parsed by anything downstream.
 *
 * <p>Scope is the portal surface only ({@code /admin/**}, {@code /auth/session} and the user-plane
 * {@code /v1/apps/**} and the registered owner operations on {@code /v1/tasks/**}). The {@code 400}
 * status is deliberate: the request itself is malformed, not unauthorised, and {@code 401} stays
 * reserved for "no usable ticket".
 */
final class PortalTicketSourceFilter extends OncePerRequestFilter {
    /** Only the header is a supported credential carrier; this name is rejected, never read. */
    static final String QUERY_TICKET = "access_token";

    static final int REJECTED_STATUS = 400;
    static final String REJECTED_CODE = "PORTAL_TICKET_SOURCE_INVALID";

    /** The gateway ingress boundary owns the portal surface and nothing else. */
    static boolean portalSurface(String method, String path) {
        return path != null
                && (path.startsWith("/admin/") || path.equals("/auth/session") || path.startsWith("/v1/apps/")
                    || PortalTaskRoutes.match(method, path) != null || PortalRuntimeRoutes.query(method, path));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !portalSurface(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getParameterMap().containsKey(QUERY_TICKET)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(REJECTED_STATUS);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write(Json.write(ApiResponse.error(REJECTED_STATUS, REJECTED_CODE, "凭证只允许通过Authorization头传递")));
    }
}
