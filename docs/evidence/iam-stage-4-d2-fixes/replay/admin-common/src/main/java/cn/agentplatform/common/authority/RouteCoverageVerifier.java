package cn.agentplatform.common.authority;

import java.lang.reflect.Method;
import java.util.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Complete MVC mapping inspection before Boot starts accepting requests. */
public final class RouteCoverageVerifier implements SmartInitializingSingleton {
    /** Explicit non-service ingress registration. This grants no service capability; its owning
     * portal/integration/policy chain must enforce that domain independently. No wildcard exemption. */
    public record Ingress(String method,String template,Method handler) {
        public Ingress {
            if(method==null||!Set.of("GET","POST","PUT","DELETE","PATCH","OPTIONS","HEAD").contains(method)
                ||template==null||!template.startsWith("/")||template.contains("*")||handler==null)
                throw new IllegalArgumentException("INGRESS_REGISTRATION_INVALID");
        }
    }
    private final String serviceId;
    private final ServiceRoutePolicy routes;
    private final List<RequestMappingInfoHandlerMapping> mappings;
    private final Set<Ingress> ingress;
    public RouteCoverageVerifier(String serviceId,ServiceRoutePolicy routes,Collection<? extends RequestMappingInfoHandlerMapping> mappings){this(serviceId,routes,mappings,Set.of());}
    public RouteCoverageVerifier(String serviceId,ServiceRoutePolicy routes,Collection<? extends RequestMappingInfoHandlerMapping> mappings,Set<Ingress> ingress){
        if(!AuthorityProperties.SERVICES.contains(serviceId))throw new IllegalArgumentException("SERVICE_ID_INVALID");
        this.serviceId=serviceId;this.routes=Objects.requireNonNull(routes);this.mappings=List.copyOf(mappings);if(this.mappings.isEmpty())throw new IllegalArgumentException("ROUTE_MAPPINGS_REQUIRED");this.ingress=Set.copyOf(ingress);
    }
    @Override public void afterSingletonsInstantiated(){
        // Context 6.2.7 preInstantiateSingletons invokes SmartInitializingSingleton before
        // finishRefresh/lifecycle.onRefresh. Boot 3.5.0 Tomcat initializes with removed connectors
        // and bindOnInit=false; WebServerStartStopLifecycle.start is the later listening boundary.
        var seen=new HashSet<Ingress>();
        for(var mapping:mappings)mapping.getHandlerMethods().forEach((info,handler)->{
            Set<String> methods=new HashSet<>();info.getMethodsCondition().getMethods().forEach(m->methods.add(m.name()));
            if(methods.isEmpty())methods.add("ANY");
            for(String method:methods)for(String template:info.getPatternValues()){
                if(frameworkError(method,template,handler))continue;
                var registration=ingress.stream().filter(i->i.method().equals(method)&&i.template().equals(template)&&i.handler().equals(handler.getMethod())).findFirst().orElse(null);
                if(registration!=null&&ingress.contains(registration)){seen.add(registration);continue;}
                boolean covered=routes.routes().stream().anyMatch(r->serviceId.equals(r.target())&&method.equals(r.method())&&template.equals(r.template()));
                if(!covered)throw new IllegalStateException("ROUTE_COVERAGE_MISSING: "+serviceId+" "+method+" "+template+" "+handler.getMethod().toGenericString());
                // Disabled service rows also need the runtime denial guard. A bean elsewhere,
                // or a partial include/exclude mapping, proves nothing about this handler chain.
                var interceptors=mapping.getAdaptedInterceptors();
                boolean guarded=interceptors!=null&&Arrays.stream(interceptors).anyMatch(interceptor->
                    interceptor instanceof org.springframework.web.servlet.handler.MappedInterceptor mapped
                    &&mapped.getIncludePathPatterns()==null&&mapped.getExcludePathPatterns()==null
                    &&mapped.getInterceptor() instanceof ServiceAuthorization receiver&&receiver.guards(serviceId));
                if(!guarded)throw new IllegalStateException("ROUTE_GUARD_MISSING: "+serviceId+" "+method+" "+template);
            }
        });
        if(!seen.equals(ingress))throw new IllegalStateException("INGRESS_REGISTRATION_UNUSED");
    }
    private static boolean frameworkError(String method,String template,HandlerMethod handler){
        // Only Boot's exact built-in error rendering methods. Custom controllers, docs, health
        // aliases and custom error paths still require registration; no package-wide exemption.
        return "ANY".equals(method)&&"/error".equals(template)&&handler.getBeanType()==BasicErrorController.class
            &&handler.getMethod().getDeclaringClass()==BasicErrorController.class
            &&Set.of("error","errorHtml").contains(handler.getMethod().getName());
    }
}
