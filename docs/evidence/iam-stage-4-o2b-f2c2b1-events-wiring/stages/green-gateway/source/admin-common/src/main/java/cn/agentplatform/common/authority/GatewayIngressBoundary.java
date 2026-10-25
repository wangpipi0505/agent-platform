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
    private static final String SESSION_CONTROLLER="cn.agentplatform.gateway.api.PortalSessionController";
    private enum Purpose { RUN, SESSION, STATUS, EVENTS }
    private final boolean statusInstalled,eventsInstalled;
    private final Object sessionController;
    private final Method sessionMethod;
    private final TrustedJwtDecoder decoder;
    private final Clock clock;
    private final Filter filter = new IngressFilter();

    public GatewayIngressBoundary(Object controller, TrustedJwtDecoder decoder, Clock clock) {
        this(controller,null,decoder,clock);
    }

    /** A second fixed purpose, never an arbitrary caller-provided handler allow-list. */
    public GatewayIngressBoundary(Object controller,Object sessionController,TrustedJwtDecoder decoder,Clock clock) {
        this(controller,sessionController,false,decoder,clock);
    }

    public GatewayIngressBoundary(Object controller,Object sessionController,boolean statusInstalled,TrustedJwtDecoder decoder,Clock clock) {
        this(controller,sessionController,statusInstalled,false,decoder,clock);
    }

    public GatewayIngressBoundary(Object controller,Object sessionController,boolean statusInstalled,boolean eventsInstalled,TrustedJwtDecoder decoder,Clock clock) {
        this.controller = Objects.requireNonNull(controller);
        this.sessionController=sessionController;
        this.statusInstalled=statusInstalled;
        this.eventsInstalled=eventsInstalled;
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
            if(sessionController==null)sessionMethod=null;
            else {
                Class<?> sessionType=AopUtils.getTargetClass(sessionController);
                if(!SESSION_CONTROLLER.equals(sessionType.getName())||sessionType!=Class.forName(SESSION_CONTROLLER,false,sessionType.getClassLoader()))throw new IllegalArgumentException("GATEWAY_SESSION_HANDLER_REQUIRED");
                sessionMethod=sessionType.getDeclaredMethod("session",HttpServletRequest.class,HttpServletResponse.class);
                if(sessionMethod.getReturnType()!=void.class)throw new IllegalArgumentException("GATEWAY_SESSION_HANDLER_REQUIRED");
            }
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
        return owns(h,controller,method);
    }
    public boolean sessionInstalled(){return sessionController!=null;}
    public boolean ownsSession(HandlerMethod h){return sessionInstalled()&&owns(h,sessionController,sessionMethod);}
    private static boolean owns(HandlerMethod h,Object controller,Method method){
        var actual = h.createWithResolvedBean();
        return actual.getBean() == controller
                && AopUtils.getTargetClass(actual.getBean()) == method.getDeclaringClass()
                && AopUtils.getMostSpecificMethod(
                                actual.getMethod(), AopUtils.getTargetClass(actual.getBean()))
                        .equals(method);
    }

    private Purpose route(HttpServletRequest r) {
        if(eventsInstalled&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
                &&r.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")&&r.getContextPath().isEmpty())return Purpose.EVENTS;
        if(statusInstalled&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
                &&r.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}")&&r.getQueryString()==null&&r.getContextPath().isEmpty())return Purpose.STATUS;
        if(sessionInstalled()&&r.getDispatcherType()==DispatcherType.REQUEST&&"GET".equals(r.getMethod())
                &&"/auth/session".equals(r.getRequestURI())&&r.getQueryString()==null&&r.getContextPath().isEmpty())return Purpose.SESSION;
        if (r.getDispatcherType() != DispatcherType.REQUEST
                || !"POST".equals(r.getMethod())
                || r.getQueryString() != null
                || !r.getContextPath().isEmpty()
                || !r.getRequestURI().matches("/admin/apps/[A-Za-z][A-Za-z0-9_-]{0,63}/runs"))
            throw denied();
        return Purpose.RUN;
    }

    private static final class Proof {
        final GatewayIngressBoundary owner;
        final HttpServletRequest anchor;
        final String path, source, query;
        final long cursor;
        HttpServletRequest eventsRequest;
        HttpServletResponse eventsResponse;
        final SubjectKey subject;
        final Instant expires;
        final Purpose purpose;
        final Thread thread=Thread.currentThread();
        final long startedNanos=System.nanoTime(),sourceBudget;
        boolean ready, claimed, invalid;

        Proof(
                GatewayIngressBoundary owner,
                HttpServletRequest anchor,
                String source,
                SubjectKey subject,
                Instant expires,long cursor) {
            this.owner = owner;
            this.anchor = anchor;
            this.source = source;
            this.subject = subject;
            this.expires = expires;
            sourceBudget=remaining(owner.clock.instant(),expires);
            path = anchor.getRequestURI();
            query=anchor.getQueryString();this.cursor=cursor;
            purpose=owner.route(anchor);
        }

        void check(HttpServletRequest request) {
            if(owner.route(request)!=purpose)throw denied();
            if(!expires.isAfter(owner.clock.instant())&&(purpose==Purpose.SESSION||purpose==Purpose.STATUS||purpose==Purpose.EVENTS))throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
            if(purpose==Purpose.EVENTS&&(Thread.currentThread()!=thread||!Objects.equals(query,request.getQueryString())))throw denied();
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
            if (!p.ready||p.purpose!=Purpose.RUN) throw denied();
            p.claimed = true;
            return new PortalRequest(p);
        }
    }
    public static final class PortalSessionRequest {
        private final String source;
        private PortalSessionRequest(Proof p){source=p.source;}
        public String sourceToken(){return source;}
        @Override public String toString(){return "PortalSessionRequest[private-capture]";}
    }
    public static PortalSessionRequest claimSession(HttpServletRequest r){
        var p=proof(r);synchronized(p){p.check(r);if(!p.ready||p.purpose!=Purpose.SESSION)throw denied();p.claimed=true;return new PortalSessionRequest(p);}
    }

    /** This capability belongs to one synchronous captured request, including after stream acquisition. */
    public static final class PortalStatusRequest {
        private final Proof capture;
        private PortalStatusRequest(Proof p){capture=p;}
        public String sourceToken(){return capture.source;}
        public SubjectKey subject(){return capture.subject;}
        public String publicRunId(){return capture.path.substring("/admin/runs/".length());}
        public void requireLive(HttpServletRequest request){
            synchronized(capture){
                var p=capture;long elapsed=System.nanoTime()-p.startedNanos;
                if(!p.expires.isAfter(p.owner.clock.instant())||elapsed<0||elapsed>=p.sourceBudget)
                    throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
                if(p.purpose!=Purpose.STATUS||!p.claimed||!p.ready||p.invalid
                        ||Thread.currentThread()!=p.thread||request.getAttribute(KEY)!=p
                        ||!hasAnchor(request,p.anchor)||!p.path.equals(request.getRequestURI())
                        ||p.owner.route(request)!=Purpose.STATUS)throw denied();
            }
        }
        @Override public String toString(){return "PortalStatusRequest[private-capture]";}
    }
    private static long remaining(Instant start,Instant end){try{return Math.max(0,Duration.between(start,end).toNanos());}catch(ArithmeticException e){return Long.MAX_VALUE;}}
    public static PortalStatusRequest claimStatus(HttpServletRequest request){
        var p=proof(request);synchronized(p){p.check(request);if(!p.ready||p.purpose!=Purpose.STATUS)throw denied();p.claimed=true;var result=new PortalStatusRequest(p);result.requireLive(request);return result;}
    }

    /** One actual MVC argument pair, consumed on its original thread before async bootstrap. */
    public static final class PortalEventsRequest {
        private final Proof capture;
        private PortalEventsRequest(Proof capture){this.capture=capture;}
        public String sourceToken(){return capture.source;}
        public SubjectKey subject(){return capture.subject;}
        public String publicRunId(){return capture.path.substring("/admin/runs/".length(),capture.path.length()-"/events".length());}
        public long cursor(){return capture.cursor;}
        public void requireLive(HttpServletRequest request,HttpServletResponse response){
            synchronized(capture){
                var p=capture;long elapsed=System.nanoTime()-p.startedNanos;
                if(!p.expires.isAfter(p.owner.clock.instant())||elapsed<0||elapsed>=p.sourceBudget)
                    throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
                if(p.purpose!=Purpose.EVENTS||!p.claimed||!p.ready||p.invalid
                        ||Thread.currentThread()!=p.thread||request!=p.eventsRequest||response!=p.eventsResponse
                        ||request.getAttribute(KEY)!=p||!hasAnchor(request,p.anchor)
                        ||!p.path.equals(request.getRequestURI())||!Objects.equals(p.query,request.getQueryString())
                        ||p.owner.route(request)!=Purpose.EVENTS)throw denied();
            }
        }
        @Override public String toString(){return "PortalEventsRequest[private-capture]";}
    }
    public static PortalEventsRequest claimEvents(HttpServletRequest request,HttpServletResponse response){
        var p=proof(request);synchronized(p){
            p.check(request);
            if(!p.ready||p.purpose!=Purpose.EVENTS||request!=p.eventsRequest||response!=p.eventsResponse)throw denied();
            p.claimed=true;var result=new PortalEventsRequest(p);result.requireLive(request,response);return result;
        }
    }
    private static long eventsCursor(HttpServletRequest request){
        var headers=Collections.list(request.getHeaders("Last-Event-ID"));String query=request.getQueryString(),value;
        if(headers.size()>1||!headers.isEmpty()&&query!=null)throw denied();
        if(query!=null){
            if(!query.startsWith("Last-Event-ID="))throw denied();
            value=query.substring("Last-Event-ID=".length());
        }else value=headers.isEmpty()?"0":headers.getFirst();
        if(!value.matches("0|[1-9][0-9]{0,18}"))throw denied();
        try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw denied();}
    }

    @Override
    public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object handler) {
        var p = proof(r);
        synchronized (p) {
            p.check(r);
            if (p.owner != this || p.ready || !(handler instanceof HandlerMethod h)
                    || !(p.purpose==Purpose.SESSION?ownsSession(h):owns(h)))
                throw denied();
            if(p.purpose==Purpose.EVENTS){p.eventsRequest=r;p.eventsResponse=s;}
            p.ready = true;
        }
        return true;
    }

    private static void clear(HttpServletRequest r) {
        Object value = r.getAttribute(KEY);
        if (value instanceof Proof p && hasAnchor(r, p.anchor)) {
            synchronized (p) {
                p.invalid = true;
                p.eventsRequest=null;p.eventsResponse=null;
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
            boolean observation=false;
            try {
                var purpose=route(r);
                observation=purpose==Purpose.STATUS||purpose==Purpose.EVENTS;
                if("/auth/session".equals(r.getRequestURI()))s.setHeader("Cache-Control","no-store");
                if (r.getAttribute(KEY) != null
                        || r.getHeader("X-Api-Key") != null
                        || r.getHeader("Cookie") != null) throw denied();
                if(purpose==Purpose.SESSION||purpose==Purpose.STATUS||purpose==Purpose.EVENTS){
                    var names=Collections.list(r.getHeaderNames());
                    for(String name:names){String lower=name.toLowerCase(Locale.ROOT);
                        if(lower.startsWith("x-user-")||lower.startsWith("x-tenant")||lower.startsWith("x-principal")||lower.equals("apikey")||lower.equals("api-key")||lower.equals("proxy-authorization")||lower.startsWith("x-auth-")||lower.equals("x-subject")||lower.equals("x-resource")||lower.equals("x-target")||lower.equals("x-action"))throw denied();
                        if(Set.of("tenant","tenant-id","principal","principal-id","subject","resource","target","action","actor","roles","attributes","access-token","service-token").contains(lower)
                                ||List.of("x-actor","x-role","x-attribute","x-identity","x-credential","x-access-token","x-service-token","x-forwarded-user","x-forwarded-tenant").stream().anyMatch(lower::startsWith))throw denied();
                    }
                    if(r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null||r.getInputStream().read()!=-1)throw denied();
                }
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
                                jwt.getExpiresAt(),purpose==Purpose.EVENTS?eventsCursor(r):0);
                r.setAttribute(KEY, p);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
                SecurityContextHolder.setContext(context);
                chain.doFilter(r, s);
            } catch (org.springframework.security.oauth2.jwt.JwtException bad) {
                if(observation)rejectObservation(s,401);else reject(s,401);
            } catch (PlatformException bad) {
                if(observation&&bad.status()==503)rejectObservation(s,503);else reject(s,bad.status());
            } finally {
                clear(r);
            }
        }
    }

    private static void rejectObservation(HttpServletResponse response,int status)throws IOException {
        if(!response.isCommitted()){
            response.setHeader("Cache-Control","no-store");response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(Json.write(new ApiResponse<>(status==401?"PORTAL_TOKEN_INVALID":"RUN_STATUS_UNAVAILABLE",status==401?"门户身份已失效":"运行状态暂不可用",null,null)));
        }
    }

    private static void reject(HttpServletResponse s, int status) throws IOException {
        if (!s.isCommitted()) {
            s.setHeader("Cache-Control","no-store");
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
