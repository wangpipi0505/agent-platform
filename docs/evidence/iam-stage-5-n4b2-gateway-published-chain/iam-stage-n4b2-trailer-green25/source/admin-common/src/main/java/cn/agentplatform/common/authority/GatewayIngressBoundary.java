package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.aop.support.AopUtils;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.*;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.*;
import java.util.*;

/**
 * A private request capability, owned by the installed filter and consumed once by the actual MVC
 * handler.
 */
public final class GatewayIngressBoundary implements AsyncHandlerInterceptor {
    private static final String KEY = GatewayIngressBoundary.class.getName() + ".proof";
    private static final String CONTROLLER = "cn.agentplatform.gateway.api.GatewayController";
    private final Object controller;
    private final Method method;
    private final TrustedJwtDecoder decoder;
    private final Clock clock;
    private final Filter filter = new IngressFilter();

    public GatewayIngressBoundary(Object controller, TrustedJwtDecoder decoder, Clock clock) {
        this.controller = Objects.requireNonNull(controller);
        this.decoder = Objects.requireNonNull(decoder);
        this.clock = Objects.requireNonNull(clock);
        try {
            Class<?> type = AopUtils.getTargetClass(controller);
            if (!CONTROLLER.equals(type.getName())
                    || type != Class.forName(CONTROLLER, false, type.getClassLoader()))
                throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED");
            method =
                    type.getDeclaredMethod(
                            "proxy", HttpServletRequest.class, HttpServletResponse.class);
            if (method.getReturnType() != void.class)
                throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED");
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("GATEWAY_HANDLER_REQUIRED", e);
        }
    }

    public Filter filter() {
        return filter;
    }

    public MappedInterceptor mappedInterceptor() {
        return new MappedInterceptor(null, null, this);
    }

    public boolean owns(HandlerMethod h) {
        var actual = h.createWithResolvedBean();
        return actual.getBean() == controller
                && AopUtils.getTargetClass(actual.getBean()) == method.getDeclaringClass()
                && AopUtils.getMostSpecificMethod(
                                actual.getMethod(), AopUtils.getTargetClass(actual.getBean()))
                        .equals(method);
    }

    private static void route(HttpServletRequest r) {
        if (r.getDispatcherType() != DispatcherType.REQUEST
                || !"POST".equals(r.getMethod())
                || r.getQueryString() != null
                || !r.getContextPath().isEmpty()
                || !r.getRequestURI().matches("/admin/apps/[A-Za-z][A-Za-z0-9_-]{0,63}/runs"))
            throw denied();
    }

    private static final class Proof {
        final GatewayIngressBoundary owner;
        final HttpServletRequest anchor;
        final String path, source;
        final SubjectKey subject;
        final Instant expires;
        boolean ready, claimed, invalid;

        Proof(
                GatewayIngressBoundary owner,
                HttpServletRequest anchor,
                String source,
                SubjectKey subject,
                Instant expires) {
            this.owner = owner;
            this.anchor = anchor;
            this.source = source;
            this.subject = subject;
            this.expires = expires;
            path = anchor.getRequestURI();
        }

        void check(HttpServletRequest request) {
            route(request);
            if (invalid
                    || claimed
                    || !path.equals(request.getRequestURI())
                    || !expires.isAfter(owner.clock.instant())
                    || !hasAnchor(request, anchor)) throw denied();
        }
    }

    /**
     * Only the owned filter can create this capture; no Actor/VA or caller-supplied JWT can mint
     * it.
     */
    public static final class PortalRequest {
        private final String source, code;
        private final SubjectKey subject;

        private PortalRequest(Proof p) {
            source = p.source;
            subject = p.subject;
            code = p.path.substring(12, p.path.length() - 5);
        }

        public String sourceToken() {
            return source;
        }

        public String appCode() {
            return code;
        }

        public SubjectKey subject() {
            return subject;
        }

        @Override
        public String toString() {
            return "PortalRequest[private-capture]";
        }
    }

    private static boolean hasAnchor(ServletRequest request, ServletRequest anchor) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<ServletRequest, Boolean>());
        for (int i = 0; i < 32 && request != null && seen.add(request); i++) {
            if (request == anchor) return true;
            if (!(request instanceof ServletRequestWrapper wrapper)) return false;
            request = wrapper.getRequest();
        }
        return false;
    }

    private static Proof proof(HttpServletRequest r) {
        if (!(r.getAttribute(KEY) instanceof Proof p)) throw denied();
        return p;
    }

    public static PortalRequest claim(HttpServletRequest r) {
        var p = proof(r);
        synchronized (p) {
            p.check(r);
            if (!p.ready) throw denied();
            p.claimed = true;
            return new PortalRequest(p);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object handler) {
        var p = proof(r);
        synchronized (p) {
            p.check(r);
            if (p.owner != this || p.ready || !(handler instanceof HandlerMethod h) || !owns(h))
                throw denied();
            p.ready = true;
        }
        return true;
    }

    private static void clear(HttpServletRequest r) {
        Object value = r.getAttribute(KEY);
        if (value instanceof Proof p && hasAnchor(r, p.anchor)) {
            synchronized (p) {
                p.invalid = true;
            }
            r.removeAttribute(KEY);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest r, HttpServletResponse s, Object h, Exception e) {
        clear(r);
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest r, HttpServletResponse s, Object h) {
        clear(r);
    }

    private final class IngressFilter extends GenericFilterBean {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            var r = (HttpServletRequest) request;
            var s = (HttpServletResponse) response;
            try {
                route(r);
                if (r.getAttribute(KEY) != null
                        || r.getHeader("X-Api-Key") != null
                        || r.getHeader("Cookie") != null) throw denied();
                var headers = Collections.list(r.getHeaders("Authorization"));
                if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer "))
                    throw denied();
                String token = headers.getFirst().substring(7);
                var jwt = decoder.decode(token);
                Object scopes = jwt.getClaims().get("scope");
                if (!"user".equals(jwt.getClaimAsString("token_use"))
                        || !(scopes instanceof String text)
                        || !Set.of(text.split(" ")).contains("platform.access")) throw denied();
                var p =
                        new Proof(
                                GatewayIngressBoundary.this,
                                r,
                                token,
                                new SubjectKey(jwt.getClaimAsString("tenant_id"), jwt.getSubject()),
                                jwt.getExpiresAt());
                r.setAttribute(KEY, p);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
                SecurityContextHolder.setContext(context);
                chain.doFilter(r, s);
            } catch (org.springframework.security.oauth2.jwt.JwtException bad) {
                reject(s, 401);
            } catch (PlatformException bad) {
                reject(s, bad.status());
            } finally {
                clear(r);
            }
        }
    }

    private static void reject(HttpServletResponse s, int status) throws IOException {
        if (!s.isCommitted()) {
            s.setStatus(status);
            s.setContentType("application/json;charset=UTF-8");
            s.getWriter()
                    .write(Json.write(new ApiResponse<>("SYS-AUTH-403", "入口未获授权", null, null)));
        }
    }

    private static PlatformException denied() {
        return new PlatformException(403, "SYS-AUTH-403", "入口未获授权");
    }

    /**
     * Records our actual addFilter instance and successful configure call, not an inference from
     * Servlet metadata.
     */
    public static final class InstalledFilterRegistration
            extends FilterRegistrationBean<FilterChainProxy> {
        public static final String NAME = "gatewayIamFilterChain";
        private final FilterChainProxy owned;
        private ServletContext context;
        private FilterRegistration.Dynamic added;
        private boolean configured;
        private Snapshot snapshot;

        private record Snapshot(
                FilterChainProxy filter,
                Set<String> urls,
                Set<String> servlets,
                Set<DispatcherType> dispatchers,
                boolean async,
                boolean matchAfter,
                int order,
                Map<String, String> init) {}

        public InstalledFilterRegistration(FilterChainProxy chain) {
            super(chain);
            owned = Objects.requireNonNull(chain);
            setName(NAME);
            setUrlPatterns(List.of("/*"));
            setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
            setAsyncSupported(true);
            setOrder(-100);
        }

        private Snapshot current() {
            return new Snapshot(
                    getFilter(),
                    Set.copyOf(getUrlPatterns()),
                    Set.copyOf(getServletNames()),
                    Set.copyOf(determineDispatcherTypes()),
                    isAsyncSupported(),
                    isMatchAfter(),
                    getOrder(),
                    Map.copyOf(getInitParameters()));
        }

        @Override
        protected FilterRegistration.Dynamic addRegistration(
                String description, ServletContext context) {
            if (this.context != null) throw new IllegalStateException("GATEWAY_FILTER_REINSTALL");
            this.context = context;
            var result = super.addRegistration(description, context);
            added = result;
            return result;
        }

        @Override
        protected void configure(FilterRegistration.Dynamic registration) {
            super.configure(registration);
            if (registration != added)
                throw new IllegalStateException("GATEWAY_FILTER_REGISTRATION_MISMATCH");
            snapshot = current();
            configured = true;
        }

        public void requireInstalled(ServletContext actual, FilterChainProxy chain) {
            var metadata = actual.getFilterRegistration(NAME);
            if (!configured
                    || context != actual
                    || chain != owned
                    || getFilter() != owned
                    || !isEnabled()
                    || !NAME.equals(getFilterName())
                    || !Objects.equals(snapshot, current())
                    || !snapshot.urls.equals(Set.of("/*"))
                    || !snapshot.servlets.isEmpty()
                    || !getServletRegistrationBeans().isEmpty()
                    || !snapshot.dispatchers.equals(EnumSet.allOf(DispatcherType.class))
                    || !snapshot.async
                    || snapshot.matchAfter
                    || snapshot.order != -100
                    || !snapshot.init.isEmpty()
                    || metadata == null
                    || !Set.copyOf(metadata.getUrlPatternMappings()).equals(Set.of("/*"))
                    || !metadata.getServletNameMappings().isEmpty())
                throw new IllegalStateException("GATEWAY_FILTER_NOT_INSTALLED");
        }
    }

    public void requireChain(FilterChainProxy proxy, SecurityFilterChain expected) {
        var chains = proxy.getFilterChains();
        if (chains.size() != 1
                || chains.getFirst() != expected
                || !(chains.getFirst() instanceof DefaultSecurityFilterChain c)
                || c.getRequestMatcher() != AnyRequestMatcher.INSTANCE)
            throw new IllegalStateException("GATEWAY_CATCHALL_CHAIN_REQUIRED");
        var filters = c.getFilters();
        int owned = filters.indexOf(filter), aware = -1;
        for (int i = 0; i < filters.size(); i++)
            if (filters.get(i) instanceof SecurityContextHolderAwareRequestFilter) aware = i;
        if (owned < 0 || Collections.frequency(filters, filter) != 1 || aware <= owned)
            throw new IllegalStateException("GATEWAY_FILTER_ORDER_INVALID");
    }
}
