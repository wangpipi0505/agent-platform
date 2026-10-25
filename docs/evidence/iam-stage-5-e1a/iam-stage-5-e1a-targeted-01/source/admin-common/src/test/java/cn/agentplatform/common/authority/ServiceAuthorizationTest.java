package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import static org.assertj.core.api.Assertions.*;

class ServiceAuthorizationTest {
    static final ResourceBinding RESOURCE=new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1"));
    static final String ACTION="proxy.engine.get.RunController.status";
    static LoopbackAuthority fixture;
    static String service="engine", requiredRole="EDITOR";
    static final AtomicInteger business=new AtomicInteger();
    static Consumer<Actor> beforeResource=a->{};
    static boolean checkResource;
    static final AtomicInteger cleaned=new AtomicInteger(),asyncMissing=new AtomicInteger(),servletFailures=new AtomicInteger();
    static final Map<String,ResourceBinding> domainRuns=Map.of("r-test",RESOURCE,
        "r-other",new ResourceBinding("run","r-other",Digest.sha256("other-snapshot")));
    static volatile boolean async;
    static CountDownLatch arrived,release;
    static final Map<String,AtomicInteger> byResource=new ConcurrentHashMap<>();
    static ServiceCall call(){return new ServiceCall("engine",ACTION,RESOURCE);}
    static ServiceRoutePolicy policy(){return new ServiceRoutePolicy();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class Receiver {
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(fixture.config(service),fixture.clock,true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient current(){return fixture.client(service);}
        @Bean ServiceAuthorization authorization(CurrentAuthorityClient current){return new ServiceAuthorization(service,policy(),current);}
        @Bean SecurityFilterChain security(HttpSecurity http,JwtDecoder decoder,ServiceAuthorization authorization)throws Exception {
            return SecurityConfiguration.serviceSecurity(http,decoder,authorization);
        }
        @Bean org.springframework.web.servlet.handler.MappedInterceptor receiverGuard(ServiceAuthorization authorization){return authorization.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mapping){return new RouteCoverageVerifier(service,policy(),mapping);}
        @Bean EngineController controller(ServiceAuthorization authorization){return new EngineController(authorization);}
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> cleanupProbe(){
            var filter=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();
            filter.setFilter((req,res,chain)->{
                // Outside DispatcherServlet, on the same pooled request thread, also after async handoff.
                try {chain.doFilter(req,res);} catch(jakarta.servlet.ServletException error){servletFailures.incrementAndGet();throw error;} finally {
                    if(catchThrowable(AuthorityContext::current) instanceof PlatformException)cleaned.incrementAndGet();
                    else throw new AssertionError("request frame leaked past DispatcherServlet");
                }
            });filter.setOrder(Integer.MAX_VALUE);return filter;
        }
        @Bean PlatformErrors errors(){return new PlatformErrors();}
    }
    @RestController static class EngineController {
        final ServiceAuthorization authorization;
        EngineController(ServiceAuthorization authorization){this.authorization=authorization;}
        @GetMapping({"/admin/runs/{id}","/admin/debug-runs/{id}/status","/v1/runs/{id}"})
        Object status(@PathVariable String id){
            Actor actor=Actor.current();beforeResource.accept(actor);
            if(checkResource)authorization.requireResource(domainRuns.get(id));
            if(arrived!=null){arrived.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("finite barrier timed out");}catch(InterruptedException e){throw new AssertionError(e);}}
            if(async){
                var verified=AuthorityContext.current();
                return (Callable<Object>)()->{
                    assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
                    asyncMissing.incrementAndGet();business.incrementAndGet();return verified.actor();
                };
            }
            assertThat(AuthorityContext.current().call().resource()).isEqualTo(domainRuns.get(id));
            Actor.current().requireRole(requiredRole);business.incrementAndGet();byResource.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();return Actor.current();
        }
        @PostMapping("/internal/tool-calls/register") Object disabled(){business.incrementAndGet();return "forbidden";}
    }
    static ServletWebServerApplicationContext start(Class<?> appClass){return start(appClass,app->{});}
    static ServletWebServerApplicationContext start(Class<?> appClass,Consumer<SpringApplication> customize){
        var app=new SpringApplication(appClass);app.setAdditionalProfiles("test");
        app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off",
            "spring.config.location","optional:classpath:/iam-stage-4-d1-no-config/","spring.jmx.enabled",false,
            "springdoc.api-docs.enabled",false,"logging.level.root","ERROR"));
        customize.accept(app);return (ServletWebServerApplicationContext)app.run();
    }
    static HttpResponse<String> request(ServletWebServerApplicationContext app,String path,String token,String method)throws Exception {
        try(var client=HttpClient.newHttpClient()){
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path))
                .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token)
                .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    static void expect(ServletWebServerApplicationContext app,String path,String token,int status)throws Exception {
        var response=request(app,path,token,"GET");assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }
    static String changed(String raw,Consumer<Map<String,Object>> change){
        var claims=Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(raw.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        change.accept(claims);return fixture.sign(claims,fixture.key,"at+jwt");
    }
    @BeforeEach void reset(){service="engine";requiredRole="EDITOR";business.set(0);beforeResource=a->{};checkResource=true;async=false;cleaned.set(0);asyncMissing.set(0);servletFailures.set(0);arrived=null;release=null;byResource.clear();}
    @Test void signedOldAdminCannotOverrideOnlineEditorInRealController()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;String ticket=gateway.exchange(f.portal(),call()).value();
            String old=changed(ticket,c->{c.put("roles",List.of("ADMIN","EDITOR"));c.put("attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("confidential","restricted")));});
            // Resource guard is deliberately off: the ingress projection itself must already be current.
            checkResource=false;requiredRole="APPROVER";
            try(var app=start(Receiver.class)){
                expect(app,"/admin/runs/r-test",old,403);assertThat(business).hasValue(0);
                requiredRole="EDITOR";var result=request(app,"/admin/runs/r-test",old,"GET");
                assertThat(result.statusCode()).as(result.body()).isEqualTo(200);assertThat(business).hasValue(1);
                assertThat(Json.read(result.body()).path("roles").toString()).isEqualTo("[\"EDITOR\"]");
                assertThat(Json.read(result.body()).path("attributes").path("region").toString()).isEqualTo("[\"A\"]");
            }
        }
    }
    @Test void integrationOriginCannotUseAdminAliasesEvenAfterDelegatedResume()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            fixture=f;var first=gateway.exchange(f.integration(),call());
            var proof=engine.authorize(first.value(),call());var resumed=gateway.resume(proof.delegation(),call());
            assertThat(resumed.operation().sourceKind()).isEqualTo("DELEGATED");
            try(var app=start(Receiver.class)){
                for(var token:List.of(first.value(),resumed.value())){
                    business.set(0);expect(app,"/v1/runs/r-test",token,200);assertThat(business).hasValue(1);
                    business.set(0);expect(app,"/admin/runs/r-test",token,403);expect(app,"/admin/debug-runs/r-test/status",token,403);assertThat(business).hasValue(0);
                }
            }
        }
    }
    @Test void exactIdentityCapabilityAndResolvedDomainResourceMatrixNeverEntersBusiness()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            try(var app=start(Receiver.class)){
                var cases=new LinkedHashMap<String,Consumer<Map<String,Object>>>();
                cases.put("caller",c->{c.put("caller","monitor");c.put("client_id","monitor");});
                cases.put("action",c->{c.put("action","proxy.engine.get.RunController.events");c.put("scope","proxy.engine.get.RunController.events");});
                cases.put("tenant",c->c.put("tenant_id","tenant-b"));cases.put("sub",c->c.put("sub","bob"));
                for(var entry:cases.entrySet()){
                    var response=request(app,"/admin/runs/r-test",changed(ticket,entry.getValue()),"GET");
                    assertThat(response.statusCode()).as(entry.getKey()+response.body()).isEqualTo(403);assertThat(business).hasValue(0);
                }
                expect(app,"/admin/runs/r-test",changed(ticket,c->c.put("target","skill")),401);
                expect(app,"/admin/runs/r-other",ticket,403);
                expect(app,"/not-registered",ticket,403);
                assertThat(request(app,"/admin/runs/r-test",ticket,"HEAD").statusCode()).isEqualTo(403);
                assertThat(request(app,"/internal/tool-calls/register",ticket,"POST").statusCode()).isEqualTo(403);
                expect(app,"/admin/runs/r-test",f.portal(),401);expect(app,"/admin/runs/r-test",f.integration(),401);
                for(String role:List.of("ADMIN","SERVICE"))expect(app,"/admin/runs/r-test",changed(ticket,c->{
                    c.put("roles",List.of(role));c.remove("authority_mode");c.remove("grant_ref");
                }),401);
                expect(app,"/admin/runs/r-test",ticket.substring(0,ticket.lastIndexOf('.')+1)+"invalid",401);
                expect(app,"/admin/runs/r-test","not-a-jwt",401);
                assertThat(business).hasValue(0);assertThat(cleaned.get()).isGreaterThanOrEqualTo(1);
                expect(app,"/admin/runs/r-test",ticket,200);assertThat(business).hasValue(1);
            }
        }
    }
    @Test void resourceRefreshRevocationExpiryAndUnavailableNeverReuseInitialSuccess()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();var originalTime=f.clock.instant();
            try(var app=start(Receiver.class)){
                for(String failure:List.of("revoked","expired","unavailable")){
                    beforeResource=a->{if(failure.equals("revoked"))f.revoked=true;
                        else if(failure.equals("expired"))f.clock.advance(41);else f.unavailable=true;};
                    expect(app,"/admin/runs/r-test",ticket,failure.equals("unavailable")?503:403);assertThat(business).hasValue(0);
                    assertThat(servletFailures).as("expected authorization failures must not escape as servlet failures").hasValue(0);
                    f.revoked=false;f.unavailable=false;f.clock.advance(Duration.between(f.clock.instant(),originalTime).getSeconds());
                }
                beforeResource=a->{};expect(app,"/admin/runs/r-test",ticket,200);assertThat(business).hasValue(1);
                business.set(0);f.revoked=true;expect(app,"/admin/runs/r-test",ticket,403);assertThat(business).hasValue(0);
                f.revoked=false;f.unavailable=true;expect(app,"/admin/runs/r-test",ticket,503);assertThat(business).hasValue(0);
                f.unavailable=false;f.clock.advance(41);expect(app,"/admin/runs/r-test",ticket,401);assertThat(business).hasValue(0);
            }
        }
    }
    @Test void requireForChecksLocalOwnerAndRejectsWiderActorWithoutUsingItAsAuthorityInput()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            try(var app=start(Receiver.class)){
                for(var actor:List.of(new Actor("tenant-b","alice",Set.of(),Map.of()),new Actor("tenant-a","bob",Set.of(),Map.of()),
                        new Actor("tenant-a","alice",Set.of("ADMIN"),Map.of("region",Set.of("A","B"))))){
                    beforeResource=a->AuthorityContext.requireFor(actor,RESOURCE);
                    expect(app,"/admin/runs/r-test",ticket,403);assertThat(business).hasValue(0);
                }
                beforeResource=a->{var fresh=AuthorityContext.requireFor(a,RESOURCE);assertThat(fresh.actor()).isEqualTo(a);};
                expect(app,"/admin/runs/r-test",ticket,200);assertThat(business).hasValue(1);
            }
            assertThatThrownBy(()->AuthorityContext.requireFor(LoopbackAuthority.ceiling(),RESOURCE)).isInstanceOf(PlatformException.class);
            try(var decoder=new TrustedJwtDecoder(f.config("engine"),f.clock,true)){
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket),List.of()));
                assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
            }finally{SecurityContextHolder.clearContext();}
        }
    }
    @Test void exceptionsAsyncHandoffAndConcurrentRequestsLeaveNoAmbientFrame()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            try(var app=start(Receiver.class)){
                beforeResource=a->{throw new PlatformException(403,"TEST_STOP","finite failure");};
                expect(app,"/admin/runs/r-test",ticket,403);assertThat(business).hasValue(0);assertThat(cleaned).hasValue(1);
                beforeResource=a->{};async=true;expect(app,"/admin/runs/r-test",ticket,200);
                assertThat(asyncMissing).hasValue(1);assertThat(business).hasValue(1);
                async=false;business.set(0);byResource.clear();arrived=new CountDownLatch(2);release=new CountDownLatch(1);
                var otherCall=new ServiceCall("engine",ACTION,domainRuns.get("r-other"));
                var other=gateway.exchange(f.portal(),otherCall).value();
                try(var pool=Executors.newFixedThreadPool(2)){
                    var one=pool.submit(()->request(app,"/admin/runs/r-test",ticket,"GET"));
                    var two=pool.submit(()->request(app,"/admin/runs/r-other",other,"GET"));
                    try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();}finally{release.countDown();}
                    assertThat(one.get(8,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                    assertThat(two.get(8,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                }
                assertThat(business).hasValue(2);assertThat(byResource.get("r-test")).hasValue(1);assertThat(byResource.get("r-other")).hasValue(1);assertThat(cleaned.get()).isGreaterThanOrEqualTo(4);
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class WorkloadReceiver {
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(fixture.config("app"),fixture.clock,true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient current(){return fixture.client("app");}
        @Bean ServiceAuthorization authorization(CurrentAuthorityClient current){return new ServiceAuthorization("app",policy(),current);}
        @Bean SecurityFilterChain security(HttpSecurity http,JwtDecoder decoder,ServiceAuthorization authorization)throws Exception{return SecurityConfiguration.serviceSecurity(http,decoder,authorization);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor receiverGuard(ServiceAuthorization authorization){return authorization.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mapping){return new RouteCoverageVerifier("app",policy(),mapping);}
        @Bean WorkloadController controller(ServiceAuthorization authorization){return new WorkloadController(authorization);}
        @Bean PlatformErrors errors(){return new PlatformErrors();}
    }
    @RestController static class WorkloadController {
        final ServiceAuthorization authorization;
        WorkloadController(ServiceAuthorization authorization){this.authorization=authorization;}
        @GetMapping("/internal/integration-origins") Object origins(){return run("integration.origin.check");}
        @GetMapping("/internal/integration-credentials/{id}") Object credential(@PathVariable String id){
            if(!id.equals("7"))throw AuthorityProtocol.denied();return run("integration.credential.read");
        }
        Object run(String action){
            assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
            assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
            authorization.requireWorkload(new ServiceCall("app",action,new ResourceBinding("credential","7",Digest.sha256("credential-v1"))));
            business.incrementAndGet();return Map.of("accepted",true);
        }
    }
    @Test void bothWorkloadRowsAreUsableAndCannotBecomeDelegatedUserCapabilities()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var resource=new ResourceBinding("credential","7",Digest.sha256("credential-v1"));
            try(var app=start(WorkloadReceiver.class)){
                for(var pair:Map.of("integration.origin.check","/internal/integration-origins","integration.credential.read","/internal/integration-credentials/7").entrySet()){
                    var ticket=gateway.client(new ServiceCall("app",pair.getKey(),resource)).value();
                    business.set(0);expect(app,pair.getValue(),ticket,200);assertThat(business).hasValue(1);
                    business.set(0);expect(app,pair.getValue(),changed(ticket,c->{
                        c.put("authority_mode","delegated");c.put("tenant_id","tenant-a");c.put("sub","alice");
                        c.put("roles",List.of("EDITOR"));c.put("attributes",Map.of("region",List.of("A")));
                        c.put("grant_ref","made-up");c.put("authorization_version","v1");
                    }),403);assertThat(business).hasValue(0);
                }
            }
            var ticket=gateway.exchange(f.portal(),call()).value();
            try(var app=start(Receiver.class)){
                expect(app,"/admin/runs/r-test",changed(ticket,c->{
                    c.put("authority_mode","workload");c.put("sub","gateway");
                    for(String k:List.of("tenant_id","grant_ref","roles","attributes","authorization_version","operation"))c.remove(k);
                }),403);assertThat(business).hasValue(0);
            }
        }
    }

    @Test void catchingFailedResourceRefreshCannotRecoverThePreviousActorProjection()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            beforeResource=a->{
                f.revoked=true;
                assertThatThrownBy(()->AuthorityContext.requireFor(a,RESOURCE)).isInstanceOf(PlatformException.class);
                observed.set(catchThrowable(Actor::current));
                throw new PlatformException(403,"TEST_AFTER_REFRESH_FAILURE","stop");
            };
            try(var app=start(Receiver.class)){
                expect(app,"/admin/runs/r-test",ticket,403);assertThat(business).hasValue(0);
                assertThat(observed.get()).as("the old current actor must be retired after failed revalidation").isInstanceOf(PlatformException.class);
            }
        }
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class HealthReceiver extends Receiver {
        @Override @Bean ServiceAuthorization authorization(CurrentAuthorityClient current){
            try{return new ServiceAuthorization(service,policy(),current,HealthController.class.getDeclaredMethod("get"));}
            catch(Exception e){throw new AssertionError(e);}
        }
        @Bean HealthController health(){return new HealthController();}
        @Override @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mapping){
            try{return new RouteCoverageVerifier(service,policy(),mapping,Set.of(new RouteCoverageVerifier.Ingress("GET","/actuator/health",HealthController.class.getDeclaredMethod("get"))));}
            catch(Exception e){throw new AssertionError(e);}
        }
    }
    @RestController static class HealthController {
        @GetMapping("/actuator/health") Object get(){return Map.of("status","UP");}
    }
    @Test void explicitlyRegisteredExactHealthNeedsNoBearerAndGrantsNoSiblingPath()throws Exception {
        try(var f=new LoopbackAuthority()){
            fixture=f;
            try(var app=start(HealthReceiver.class);var client=HttpClient.newHttpClient()){
                String url="http://127.0.0.1:"+app.getWebServer().getPort();
                var health=client.send(HttpRequest.newBuilder(URI.create(url+"/actuator/health")).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).as(health.body()).isEqualTo(200);
                for(String path:List.of("/actuator/health/liveness","/actuator/info","/actuator/prometheus")){
                    var sibling=client.send(HttpRequest.newBuilder(URI.create(url+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    assertThat(sibling.statusCode()).isEqualTo(401);
                }
                assertThat(business).hasValue(0);assertThat(f.requests).isEmpty();
            }
        }
    }

    @Test void freshSignedDecisionCannotChangeAnyOriginalSubjectGrantOperationOrCallBinding()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            var cases=new LinkedHashMap<String,Consumer<Map<String,Object>>>();
            cases.put("tenant",c->c.put("tenant_id","tenant-b"));cases.put("sub",c->c.put("sub","bob"));
            cases.put("grant",c->c.put("grant_ref","another-grant"));
            cases.put("caller",c->c.put("subject_caller","monitor"));
            cases.put("version",c->c.put("authorization_version","v2"));
            cases.put("action",c->c.put("action","proxy.engine.get.RunController.events"));
            cases.put("resource",c->c.put("resource",AuthorityProtocol.resource(domainRuns.get("r-other"))));
            cases.put("operation",c->{var op=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));op.put("operation_id","another-op");c.put("operation",op);});
            cases.put("roles",c->c.put("roles",List.of("ADMIN","EDITOR")));
            cases.put("attributes",c->c.put("attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("restricted","confidential"))));
            try(var app=start(Receiver.class)){
                for(var entry:cases.entrySet()){
                    f.changeDecision=c->{};beforeResource=a->f.changeDecision=entry.getValue();
                    var response=request(app,"/admin/runs/r-test",ticket,"GET");
                    assertThat(response.statusCode()).as(entry.getKey()+response.body()).isEqualTo(403);assertThat(business).hasValue(0);
                }
            }
        }
    }
    @Test void actorProjectionRequiresContextWithSameTenantAndSubject()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var decoder=new TrustedJwtDecoder(f.config("engine"),f.clock,true)){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();var proof=engine.authorize(ticket,call());
            try(var context=AuthorityContext.bind(proof)){
                for(var change:List.<Consumer<Map<String,Object>>>of(c->c.put("tenant_id","tenant-b"),c->c.put("sub","bob"))){
                    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(changed(ticket,change))));
                    assertThatThrownBy(Actor::current).isInstanceOf(PlatformException.class);
                }
            }finally{SecurityContextHolder.clearContext();}
        }
    }

    @Test void nestedServletDispatchCannotOverwriteTheRequestCleanupFrame()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var decoder=new TrustedJwtDecoder(f.config("engine"),f.clock,true)){
            fixture=f;var ticket=gateway.exchange(f.portal(),call()).value();
            var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/admin/runs/r-test");
            request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/runs/{id}");
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            var authorization=new ServiceAuthorization("engine",policy(),engine);
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket),List.of()));
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
            AuthorityContext first=null;
            try{
                assertThat(authorization.preHandle(request,response,this)).isTrue();
                first=(AuthorityContext)request.getAttribute(ServiceAuthorization.class.getName()+".frame");
                assertThatThrownBy(()->authorization.preHandle(request,response,this)).isInstanceOf(PlatformException.class);
            }finally{
                authorization.afterCompletion(request,response,this,null);
                if(first!=null)first.close();
                SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
            assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
        }
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class SecondaryReceiver extends Receiver {
        @Bean org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping secondaryMapping(ServiceAuthorization authorization){
            return new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping(){
                @Override protected boolean isHandler(Class<?> type){return false;}
                @Override public void afterPropertiesSet(){
                    setOrder(-1);super.afterPropertiesSet();
                    try {registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/internal/statistics/runs/{id}")
                        .methods(RequestMethod.GET).options(getBuilderConfiguration()).build(),new SecondaryController(authorization),SecondaryController.class.getDeclaredMethod("get"));}
                    catch(Exception e){throw new AssertionError(e);}
                }
            };
        }
    }
    @RestController static class SecondaryController {
        final ServiceAuthorization authorization;
        SecondaryController(ServiceAuthorization authorization){this.authorization=authorization;}
        @ResponseBody Object get(){authorization.requireResource(RESOURCE);business.incrementAndGet();return Actor.current();}
    }
    @Test void secondaryMappingUsesTheSameRealHttpCurrentAuthorityGuard()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            fixture=f;var initial=gateway.exchange(f.portal(),call()).value();
            var ticket=changed(initial,c->{c.put("caller","monitor");c.put("client_id","monitor");c.put("action","statistics.run.read");c.put("scope","statistics.run.read");});
            try(var app=start(SecondaryReceiver.class)){
                expect(app,"/internal/statistics/runs/r-test",ticket,200);assertThat(business).hasValue(1);
                business.set(0);f.revoked=true;expect(app,"/internal/statistics/runs/r-test",ticket,403);assertThat(business).hasValue(0);
                f.revoked=false;expect(app,"/internal/statistics/runs/r-test",initial,403);assertThat(business).hasValue(0);
            }
        }
    }

    @Test void retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync()throws Exception {
        try(var f=new LoopbackAuthority()){
            f.portalActor(RetainedAuthorizationTest.initialAB());fixture=f;
            try(var gateway=f.client("gateway")){
                String ticket=gateway.exchange(f.portal(),call()).value();f.changeDecision=RetainedAuthorizationTest::currentA;
                var captured=new java.util.concurrent.atomic.AtomicReference<HttpCurrentAuthorityClient.RetainedAuthorization>();
                checkResource=false;
                beforeResource=actor->{
                    var proof=AuthorityContext.requireInitialFor(actor,RESOURCE);captured.set(proof);
                    assertThat(proof.requireInitialCeiling()).isEqualTo(RetainedAuthorizationTest.initialAB());
                    assertThat(proof.current().actor().attributes().get("region")).containsExactly("A");
                    AuthorityContext.requireBound(proof);
                };
                try(var app=start(Receiver.class)){
                    int calls=f.authorityCalls();expect(app,"/admin/runs/r-test",ticket,200);
                    assertThat(f.authorityCalls()).isEqualTo(calls+2);assertThat(business).hasValue(1);assertThat(cleaned).hasValue(1);
                    assertThatThrownBy(()->AuthorityContext.requireBound(captured.get())).isInstanceOf(PlatformException.class);
                    async=true;calls=f.authorityCalls();expect(app,"/admin/runs/r-test",ticket,200);
                    assertThat(f.authorityCalls()).isEqualTo(calls+2);assertThat(asyncMissing).hasValue(1);
                    assertThatThrownBy(()->AuthorityContext.requireBound(captured.get())).isInstanceOf(PlatformException.class);
                }
            }
        }
    }

}
