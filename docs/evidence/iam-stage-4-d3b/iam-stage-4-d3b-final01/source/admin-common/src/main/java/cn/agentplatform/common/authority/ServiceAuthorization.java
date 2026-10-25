package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.*;

/**
 * Runs after MVC has selected the real method/template, before controller argument resolution.
 * This is a capability gate; controllers must resolve domain metadata and requireResource before
 * touching protected payload. No header or body can assert caller, origin, action or resource access.
 */
public final class ServiceAuthorization implements AsyncHandlerInterceptor {
    private static final String FRAME=ServiceAuthorization.class.getName()+".frame";
    private final String serviceId;
    private final ServiceRoutePolicy routes;
    private final CurrentAuthorityClient authority;
    private final java.lang.reflect.Method healthHandler;
    private final java.lang.reflect.Method policyHandler;
    private final PolicyRequestVerifier policyVerifier;

    public ServiceAuthorization(String serviceId,ServiceRoutePolicy routes,CurrentAuthorityClient authority){this(serviceId,routes,authority,null);}
    public ServiceAuthorization(String serviceId,ServiceRoutePolicy routes,CurrentAuthorityClient authority,java.lang.reflect.Method healthHandler) {
        this(serviceId,routes,authority,healthHandler,null,null);
    }
    public ServiceAuthorization(String serviceId,ServiceRoutePolicy routes,CurrentAuthorityClient authority,
            java.lang.reflect.Method healthHandler,java.lang.reflect.Method policyHandler,PolicyRequestVerifier policyVerifier) {
        if((policyHandler==null)!=(policyVerifier==null))throw new IllegalArgumentException("POLICY_HANDLER_VERIFIER_REQUIRED");
        this.policyHandler=policyHandler;this.policyVerifier=policyVerifier;
        if(!AuthorityProperties.SERVICES.contains(serviceId))throw new IllegalArgumentException("SERVICE_ID_INVALID");
        this.serviceId=serviceId;this.routes=Objects.requireNonNull(routes);this.authority=Objects.requireNonNull(authority);this.healthHandler=healthHandler;
    }
    /** Register this result as a bean. AbstractHandlerMapping discovers it across MVC and
     * additional RequestMappingInfo mappings, unlike MVC-local addInterceptors registration. */
    public org.springframework.web.servlet.handler.MappedInterceptor mappedInterceptor(){
        return new org.springframework.web.servlet.handler.MappedInterceptor(null,this);
    }
    boolean guards(String localService){return serviceId.equals(localService);}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        // Nested servlet forwards/includes must not overwrite the outer dispatch's close handle.
        // Async redispatch is separate: afterConcurrentHandlingStarted already retired its frame.
        if(request.getAttribute(FRAME)!=null)throw AuthorityProtocol.denied();
        if(healthRequest(request)&&handler instanceof org.springframework.web.method.HandlerMethod method
                &&healthHandler.equals(method.getMethod())
                &&"/actuator/health".equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)))return true;
        if(policyHandler!=null&&handler instanceof org.springframework.web.method.HandlerMethod method
                &&policyHandler.equals(method.getMethod())&&PolicyRequestVerifier.PATH.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))){
            Object proof=request.getAttribute(PolicyRequestVerifier.ATTRIBUTE);
            if(!(proof instanceof PolicyRequestVerifier.Proof verified))throw AuthorityProtocol.denied();
            policyVerifier.require(request,verified);return true;
        }
        var jwt=jwt();
        var call=new ServiceCall(serviceId,jwt.getClaimAsString("action"),
                AuthorityProtocol.resource(Json.tree(jwt.getClaims().get("resource"))));
        if("workload".equals(jwt.getClaimAsString("authority_mode")))requireWorkload(call);
        else {
            var current=requireRetained(call);
            // Retain the received, verified ticket only inside a request frame. Refresh always
            // reuses this exact call and route; it cannot acquire a new grant or wider operation.
            var frame=AuthorityContext.bindRetained(current,()->requireRetained(call));
            request.setAttribute(FRAME,frame);
        }
        return true;
    }
    /** Only opens the exact HTTP health candidate; preHandle still requires its registered method. */
    public boolean healthRequest(HttpServletRequest request){
        return healthHandler!=null&&"GET".equals(request.getMethod())&&"/actuator/health".equals(request.getServletPath());
    }
    public VerifiedAuthority require(ServiceCall call) {
        return requireRetained(call).current();
    }
    private HttpCurrentAuthorityClient.RetainedAuthorization requireRetained(ServiceCall call) {
        var jwt=jwt();var route=route(jwt,call,"delegated");
        HttpCurrentAuthorityClient.RetainedAuthorization retained;
        try {retained=authority.authorizeRetained(jwt.getTokenValue(),call);}
        catch(org.springframework.security.oauth2.jwt.JwtException expiredOrRetired){throw AuthorityProtocol.denied();}
        var result=retained.current();
        AuthorityProtocol.same(jwt.getClaimAsString("caller"),result.caller());
        AuthorityProtocol.same(jwt.getClaimAsString("tenant_id"),result.actor().tenantId());
        AuthorityProtocol.same(jwt.getSubject(),result.actor().principalId());
        AuthorityProtocol.same(call,result.call());
        // Origin survives a DELEGATED resume. Checking only sourceKind would launder integration
        // grants into admin aliases that deliberately share their action with the public v1 route.
        if("INTEGRATION".equals(result.operation().originKind())&&!route.integrationAllowed())throw AuthorityProtocol.denied();
        return retained;
    }
    public WorkloadAuthority requireWorkload(ServiceCall call) {
        var jwt=jwt();route(jwt,call,"workload");
        WorkloadAuthority result;
        try {result=authority.authorizeWorkload(jwt.getTokenValue(),call);}
        catch(org.springframework.security.oauth2.jwt.JwtException expiredOrRetired){throw AuthorityProtocol.denied();}
        AuthorityProtocol.same(jwt.getClaimAsString("caller"),result.caller());AuthorityProtocol.same(call,result.call());
        return result;
    }
    public VerifiedAuthority requireResource(ResourceBinding resource) {
        return AuthorityContext.requireFor(AuthorityContext.current().actor(),resource);
    }
    private ServiceRoutePolicy.Route route(Jwt jwt,ServiceCall call,String mode) {
        var operation=Json.tree(jwt.getClaims().get("operation"));
        MetadataSelectors.requirePurpose(call,jwt.getClaimAsString("caller"),mode,
            operation.path("source_kind").asText(),operation.path("origin_kind").asText());
        AuthorityProtocol.same("service",jwt.getClaimAsString("token_use"));
        AuthorityProtocol.same(mode,jwt.getClaimAsString("authority_mode"));
        AuthorityProtocol.same(serviceId,jwt.getClaimAsString("target"));
        AuthorityProtocol.same(serviceId,call.target());AuthorityProtocol.same(jwt.getClaimAsString("action"),call.action());
        AuthorityProtocol.same(AuthorityProtocol.resource(Json.tree(jwt.getClaims().get("resource"))),call.resource());
        var attributes=RequestContextHolder.getRequestAttributes();
        if(!(attributes instanceof ServletRequestAttributes servlet))throw AuthorityProtocol.denied();
        var request=servlet.getRequest();
        Object template=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return routes.routes().stream().filter(row->mode.equals(row.mode())
            &&Objects.equals(jwt.getClaimAsString("caller"),row.caller())&&serviceId.equals(row.target())
            &&request.getMethod().equals(row.method())&&Objects.equals(template,row.template())
            &&call.action().equals(row.action())).findFirst().orElseThrow(AuthorityProtocol::denied);
    }
    private static Jwt jwt() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof Jwt jwt))throw AuthorityProtocol.denied();
        return jwt;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error){clear(request);}
    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,HttpServletResponse response,Object handler){clear(request);}
    private static void clear(HttpServletRequest request){
        if(request.getAttribute(FRAME) instanceof AuthorityContext frame){request.removeAttribute(FRAME);frame.close();}
    }
}
