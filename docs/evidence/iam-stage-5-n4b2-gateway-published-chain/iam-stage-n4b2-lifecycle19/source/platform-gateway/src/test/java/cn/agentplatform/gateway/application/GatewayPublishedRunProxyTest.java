package cn.agentplatform.gateway.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.infra.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Actual controller, installed Security 6.5 wrapper, signed portal HTTP, and real cancellable transport. */
class GatewayPublishedRunProxyTest {
    static LoopbackAuthority iam;static HttpServer app;static String origin;static ServletWebServerApplicationContext context;
    static ExecutorService backendThreads;static final AtomicInteger legacy=new AtomicInteger(),legacyChecks=new AtomicInteger(),queries=new AtomicInteger(),posts=new AtomicInteger(),handlers=new AtomicInteger();
    static volatile String metadataAuth,engineAuth,originalBody;static volatile String responseMode="normal";static volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    static final AtomicInteger listenerEvents=new AtomicInteger(),writtenBytes=new AtomicInteger();static final AtomicBoolean expireOnReady=new AtomicBoolean();
    static HttpCurrentAuthorityClient authority;static PublishedRunProxy proxy;
    static final String SHA=Digest.sha256("stored");
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class) @EnableWebSecurity(debug=false)
    @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor deployment(org.springframework.core.env.Environment e){return f->f.registerSingleton("authorityProperties",SecurityConfiguration.bindDeployment(e));}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess(origin,origin,d){@Override public void check(HttpServletRequest r,Actor a){legacyChecks.incrementAndGet();super.check(r,a);}};}
        @Bean cn.agentplatform.gateway.api.DevAuthentication explicitDisabledDevHandler(){return new cn.agentplatform.gateway.api.DevAuthentication();}
        @Bean Clock clock(){return iam.clock();}
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> outputObservation(){
            var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>((req,res,chain)->{
                var response=(HttpServletResponse)res;chain.doFilter(req,new HttpServletResponseWrapper(response){private ServletOutputStream observed;
                    @Override public ServletOutputStream getOutputStream()throws java.io.IOException {if(observed==null){var out=super.getOutputStream();observed=new ServletOutputStream(){public boolean isReady(){boolean ready=out.isReady();if(ready&&responseMode.equals("before-write-expire")&&expireOnReady.compareAndSet(false,true))iam.advanceClock(Duration.ofSeconds(6));return ready;}public void setWriteListener(WriteListener l){out.setWriteListener(l);}public void write(int b)throws java.io.IOException{writtenBytes.incrementAndGet();out.write(b);}public void write(byte[] b,int o,int n)throws java.io.IOException{writtenBytes.addAndGet(n);out.write(b,o,n);}};}return observed;}
                });
            });registration.setOrder(-200);registration.setAsyncSupported(true);return registration;
        }
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return authority=iam.client("gateway");}
        @Bean(destroyMethod="close") PublishedRunHttp transport(){return new PublishedRunHttp();}
        @Bean(destroyMethod="close") PublishedMetadataClient metadata(PublishedRunHttp h,HttpCurrentAuthorityClient a){return new PublishedMetadataClient(URI.create(origin),a,h,true);}
        @Bean(destroyMethod="close") PublishedRunProxy proxy(PublishedRunHttp h,HttpCurrentAuthorityClient a,PublishedMetadataClient m){return proxy=new PublishedRunProxy(URI.create(origin),m,a,h,true);}
    }
    @BeforeAll static void start()throws Exception{
        iam=new LoopbackAuthority();iam.portalActor(new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",Set.of("A"),"dataLabels",Set.of("restricted"))));
        app=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);backendThreads=Executors.newCachedThreadPool();app.setExecutor(backendThreads);
        app.createContext("/",x->{handlers.incrementAndGet();try(x){
            boolean query=x.getRequestURI().getPath().equals("/internal/apps/Demo/published-metadata"),post=x.getRequestURI().getPath().equals("/admin/apps/Demo/runs");
            if(query){queries.incrementAndGet();metadataAuth=x.getRequestHeaders().getFirst("Authorization");}
            else if(post){posts.incrementAndGet();engineAuth=x.getRequestHeaders().getFirst("Authorization");originalBody=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);}
            else {legacy.incrementAndGet();}
            String mode=responseMode;byte[] b=Json.write(ApiResponse.ok(query?Map.of("appCode","Demo","versionId",17,"versionNo",3,"versionSha256",SHA):Map.of("runId","r-finite","status","SUCCEEDED","output",Map.of("ok",true)))).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type","application/json");
            if(mode.equals("redirect")&&post){x.getResponseHeaders().set("Location","/escape");x.sendResponseHeaders(302,-1);return;}
            if(mode.equals("oversize")&&post)b=("{\"padding\":\""+"x".repeat(2*1024*1024)+"\"}").getBytes(StandardCharsets.UTF_8);
            if(mode.equals("large")&&post){String prefix="{\"code\":\"OK\",\"message\":\"\",\"data\":{\"padding\":\"",suffix="\"},\"traceId\":null}";b=(prefix+"x".repeat(2*1024*1024-prefix.length()-suffix.length())+suffix).getBytes(StandardCharsets.UTF_8);}
            if(mode.equals("expire")&&post)iam.advanceClock(Duration.ofSeconds(6));
            x.sendResponseHeaders(200,b.length);
            if((mode.equals("stall-metadata")&&query)||(mode.equals("stall-engine")&&post)){
                x.getResponseBody().write(b,0,b.length-1);x.getResponseBody().flush();entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}x.getResponseBody().write(b,b.length-1,1);
            }else x.getResponseBody().write(b);
        }catch(Exception ignored){}finally{handlers.decrementAndGet();}});app.start();origin="http://127.0.0.1:"+app.getAddress().getPort();
        context=boot(Receiver.class);
    }
    static ServletWebServerApplicationContext boot(Class<?> type){return boot(type,(bean,name)->bean);}
    static ServletWebServerApplicationContext boot(Class<?> type,java.util.function.BiFunction<Object,String,Object> transform){
        var b=new SpringApplication(type);b.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.web.context.WebServerInitializedEvent>)e->listenerEvents.incrementAndGet());b.setAdditionalProfiles("test");b.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n4b2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.services.app",origin),Map.entry("agent.services.engine",origin)));
        b.addInitializers(c->{c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("n4b2-trusted-deployment",trustSettings()));c.getBeanFactory().addBeanPostProcessor(new org.springframework.beans.factory.config.BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){return transform.apply(bean,name);}});});return (ServletWebServerApplicationContext)b.run();
    }
    static Map<String,Object> trustSettings(){var p=new LinkedHashMap<String,Object>();flatten("agent.security",Json.tree(iam.config("gateway")),p);p.put("agent.dev-auth",false);return p;}
    static void flatten(String prefix,com.fasterxml.jackson.databind.JsonNode v,Map<String,Object> p){
        if(v.isObject())v.fields().forEachRemaining(e->{String k=prefix.endsWith("attribute-value-allowlist")?"["+e.getKey()+"]":"."+e.getKey().replaceAll("([a-z])([A-Z])","$1-$2").toLowerCase(Locale.ROOT);flatten(prefix+k,e.getValue(),p);});else if(v.isArray()){for(int i=0;i<v.size();i++)flatten(prefix+"["+i+"]",v.get(i),p);}else if(!v.isNull())p.put(prefix,v.isBoolean()?v.booleanValue():v.isNumber()?v.numberValue():v.asText());
    }
    @BeforeEach void reset()throws Exception {release.countDown();awaitIdle();iam.clearFiniteGrants();iam.revoked(false);iam.unavailable(false);iam.ticketMutation(c->{});iam.decisionMutation(c->{});responseMode="normal";writtenBytes.set(0);expireOnReady.set(false);legacy.set(0);legacyChecks.set(0);queries.set(0);posts.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    static void awaitIdle()throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end&&(handlers.get()>0||proxy.observation().active()>0||proxy.observation().workerStarts()!=proxy.observation().workerExits()))Thread.sleep(10);assertThat(handlers).hasValue(0);assertThat(proxy.observation().active()).isZero();assertThat(proxy.observation().workerExits()).isEqualTo(proxy.observation().workerStarts());}
    @AfterAll static void stop()throws Exception {release.countDown();if(context!=null)context.close();if(app!=null)app.stop(0);if(backendThreads!=null){backendThreads.shutdownNow();assertThat(backendThreads.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}if(iam!=null)iam.close();}
    static HttpResponse<String> request(String method,String path,String token,String body)throws Exception {
        try(var h=HttpClient.newHttpClient()){var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json");if(token!=null)b.header("Authorization",token);return h.send(b.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    @Test void actualIamControllerRejectsQueryBeforeAnyLegacyOrAuthorityBusinessSend()throws Exception {
        int before=iam.authorityCalls();var r=request("POST","/admin/apps/Demo/runs?clientTenant=forged","Bearer "+iam.portal(),"{}");assertThat(legacyChecks).hasValue(0);assertThat(r.statusCode()).isEqualTo(403);assertThat(queries).hasValue(0);assertThat(posts).hasValue(0);assertThat(iam.authorityCalls()).isEqualTo(before);
    }
    @Test void realMvcAsyncProxyUsesSeparateTargetTicketsAndPreservesExactLegalBody()throws Exception {
        String source=iam.portal(),body=" { \"requestId\" : \"one\", \"inputs\" : {\"a\":7} } \n";var r=request("POST","/admin/apps/Demo/runs","Bearer "+source,body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(Json.read(r.body()).path("data").path("status").asText()).isEqualTo("SUCCEEDED");assertThat(originalBody).isEqualTo(body);assertThat(queries).hasValue(1);assertThat(posts).hasValue(1);assertThat(legacyChecks).hasValue(0);assertThat(metadataAuth).doesNotContain(source);assertThat(engineAuth).doesNotContain(source);
        try(var appClient=iam.client("app");var engineClient=iam.client("engine")){
            var metadata=appClient.authorize(metadataAuth.substring(7),new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published("tenant-a","Demo")));
            var main=engineClient.authorize(engineAuth.substring(7),new ServiceCall("engine","proxy.engine.post.RunController.run",new ResourceBinding("app-version","Demo@3",SHA)));
            assertThat(main.delegation().value()).isNotEqualTo(metadata.delegation().value());assertThat(main.operation().operationId()).isNotEqualTo(metadata.operation().operationId());assertThat(main.operation().subject()).isEqualTo(metadata.operation().subject());
        }awaitIdle();
    }
    @Test void wrongMethodsPathsQueriesAndIdentityAreDeniedBeforeAnyExchange()throws Exception {
        for(String path:List.of("/admin/apps/Demo/runs/","/admin/apps/1Demo/runs","/admin/apps/Demo/runs;alias","/v1/runs","/admin/apps/Demo","/assistant.js","/v3/api-docs"))assertThat(request("POST",path,"Bearer "+iam.portal(),"{}").statusCode()).as(path).isIn(400,403);
        for(String method:List.of("GET","PUT","DELETE","OPTIONS","PATCH"))assertThat(request(method,"/admin/apps/Demo/runs","Bearer "+iam.portal(),"{}").statusCode()).isEqualTo(403);
        for(String token:List.of("ApiKey forged","Bearer forged"))assertThat(request("POST","/admin/apps/Demo/runs",token,"{}").statusCode()).isIn(401,403);
        assertThat(queries).hasValue(0);assertThat(posts).hasValue(0);assertThat(legacyChecks).hasValue(0);assertThat(iam.authorityCalls()).isZero();
    }
    @Test void malformedCompressedAndOversizeBodiesNeverStartMetadata()throws Exception {
        for(String body:List.of("[]","{\"x\":1,\"x\":2}","{} {}","{invalid"))assertThat(request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),body).statusCode()).as(body).isEqualTo(400);
        assertThat(request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),"x".repeat(2*1024*1024+1)).statusCode()).isEqualTo(413);
        assertThat(queries).hasValue(0);assertThat(posts).hasValue(0);assertThat(iam.authorityCalls()).isZero();
    }
    @Test void stalledActualMetadataHandleIsCancelledAndWorkerReallyExits()throws Exception {
        responseMode="stall-metadata";int exits=proxy.observation().workerExits();var r=request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),"{}");assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(r.statusCode()).isEqualTo(503);assertThat(posts).hasValue(0);assertThat(release.getCount()).isEqualTo(1);release.countDown();awaitIdle();assertThat(proxy.observation().workerExits()).isEqualTo(exits+1);assertThat(proxy.observation().handleInstalls()).isPositive();
    }
    @Test void expiredMainAfterCompleteBufferNeverLeaksSuccessfulJson()throws Exception {
        responseMode="expire";var r=request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),"{}");assertThat(r.statusCode()).isEqualTo(403);assertThat(r.body()).doesNotContain("r-finite","SUCCEEDED");assertThat(posts).hasValue(1);awaitIdle();
    }
    @Test void engineRedirectOrOversizeDoesNotEscapeFiniteJsonOutput()throws Exception {
        for(String mode:List.of("redirect","oversize")){responseMode=mode;var r=request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),"{}");assertThat(r.statusCode()).isEqualTo(503);assertThat(r.body()).doesNotContain("r-finite");awaitIdle();}
        assertThat(posts).hasValue(2);assertThat(legacy).hasValue(0);
    }
    @Test void rawHttpWireDiagnostic()throws Exception {
        try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){socket.setSoTimeout(8000);String b="{\"inputs\":{},\"requestId\":\"raw\"}";socket.getOutputStream().write(("POST /admin/apps/Demo/runs HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+iam.portal()+"\r\nContent-Type: application/json\r\nContent-Length: "+b.length()+"\r\nConnection: close\r\n\r\n"+b).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();String result=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);System.out.println("N4B2_RAW_RESPONSE="+result);assertThat(result).startsWith("HTTP/1.1 200");}
    }    @Test void rawEmptyQueryIsRejected()throws Exception {
        try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){socket.setSoTimeout(8000);String b="{}";socket.getOutputStream().write(("POST /admin/apps/Demo/runs? HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+iam.portal()+"\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"+b).getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();String r=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(r).startsWith("HTTP/1.1 403");assertThat(queries).hasValue(0);}
    }    @Test void actualFilterProofUsesOwnedAncestorSingleClaimAndRejectsCopyAndReentry()throws Exception {
        var boundary=context.getBean(GatewayIngressBoundary.class);var controller=context.getBean(GatewayController.class);var handler=new org.springframework.web.method.HandlerMethod(controller,GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class));
        var r=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin/apps/Demo/runs");r.addHeader("Authorization","Bearer "+iam.portal());var response=new org.springframework.mock.web.MockHttpServletResponse();var calls=new AtomicInteger();
        try{boundary.filter().doFilter(r,response,(request,res)->{
            calls.incrementAndGet();var wrapped=new HttpServletRequestWrapper((HttpServletRequest)request);assertThat(boundary.preHandle(wrapped,response,handler)).isTrue();String key=GatewayIngressBoundary.class.getName()+".proof";
            var copied=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin/apps/Demo/runs");copied.setAttribute(key,r.getAttribute(key));assertThatThrownBy(()->GatewayIngressBoundary.claim(copied)).isInstanceOf(PlatformException.class);
            var wrong=new HttpServletRequestWrapper(copied);assertThatThrownBy(()->GatewayIngressBoundary.claim(wrong)).isInstanceOf(PlatformException.class);
            var loop=new HttpServletRequestWrapper(wrapped){@Override public ServletRequest getRequest(){return this;}};assertThatThrownBy(()->GatewayIngressBoundary.claim(loop)).isInstanceOf(PlatformException.class);
            HttpServletRequest deep=wrapped;for(int i=0;i<33;i++)deep=new HttpServletRequestWrapper(deep);final var tooDeep=deep;assertThatThrownBy(()->GatewayIngressBoundary.claim(tooDeep)).isInstanceOf(PlatformException.class);
            var capture=GatewayIngressBoundary.claim(new HttpServletRequestWrapper(wrapped));assertThat(capture.subject()).isEqualTo(new SubjectKey("tenant-a","alice"));assertThat(capture.appCode()).isEqualTo("Demo");assertThatThrownBy(()->GatewayIngressBoundary.claim(wrapped)).isInstanceOf(PlatformException.class);
            boundary.afterConcurrentHandlingStarted(wrapped,response,handler);assertThat(r.getAttribute(key)).isNull();assertThatThrownBy(()->GatewayIngressBoundary.claim(wrapped)).isInstanceOf(PlatformException.class);
        });assertThat(calls).hasValue(1);}finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        for(var dispatch:DispatcherType.values())if(dispatch!=DispatcherType.REQUEST){var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin/apps/Demo/runs");request.setDispatcherType(dispatch);var denied=new org.springframework.mock.web.MockHttpServletResponse();boundary.filter().doFilter(request,denied,(a,b)->{throw new AssertionError("dispatch entered handler");});assertThat(denied.getStatus()).as(dispatch.name()).isEqualTo(403);}
    }
    @Test void cglibActualControllerAndFrameworkOuterFilterRemainTheSameInstalledObjects()throws Exception {
        var saved=proxy;var savedAuthority=authority;
        try(var c=boot(Receiver.class,(bean,name)->{if(bean instanceof GatewayController){var factory=new org.springframework.aop.framework.ProxyFactory(bean);factory.setProxyTargetClass(true);return factory.getProxy();}return bean;})){
            var actual=c.getBean(GatewayController.class);assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(actual)).isTrue();var b=c.getBean(GatewayIngressBoundary.class);var f=(org.springframework.security.web.FilterChainProxy)c.getBean("springSecurityFilterChain");var expected=c.getBean("gatewayIamChain",org.springframework.security.web.SecurityFilterChain.class);b.requireChain(f,expected);c.getBean(GatewayIngressBoundary.InstalledFilterRegistration.class).requireInstalled(c.getServletContext(),f);
            var req=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin/apps/Demo/runs");req.addHeader("Authorization","Bearer "+iam.portal());var res=new org.springframework.mock.web.MockHttpServletResponse();var h=new org.springframework.web.method.HandlerMethod(actual,GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class));b.filter().doFilter(req,res,(r,s)->{assertThat(b.preHandle((HttpServletRequest)r,res,h)).isTrue();assertThat(GatewayIngressBoundary.claim((HttpServletRequest)r).appCode()).isEqualTo("Demo");});
            assertThat(c.getServletContext().getFilterRegistrations().keySet()).doesNotContain("springSecurityFilterChain");
        }finally{proxy=saved;authority=savedAuthority;org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void invalidActualRegistrationChainsAndGlobalMappingsFailBeforeListenerEvent()throws Exception {
        var saved=proxy;var savedAuthority=authority;
        for(String variant:List.of("disabled","partial-url","async-false","dispatch-partial","wrong-installed-instance","earlier-empty-chain","missing-owned-filter","partial-interceptor","missing-any","third-any","alternate-handler")){
            int beforeEvents=listenerEvents.get();
            try{
                assertThatThrownBy(()->{try(var c=boot(Receiver.class,(bean,name)->{
                    if(bean instanceof GatewayIngressBoundary.InstalledFilterRegistration r){switch(variant){case "disabled"->r.setEnabled(false);case "partial-url"->r.setUrlPatterns(List.of("/admin/*"));case "async-false"->r.setAsyncSupported(false);case "dispatch-partial"->r.setDispatcherTypes(DispatcherType.REQUEST);case "wrong-installed-instance"->r.setFilter(new org.springframework.security.web.FilterChainProxy());}}
                    if(name.equals("springSecurityFilterChain")&&bean instanceof org.springframework.security.web.FilterChainProxy f&&variant.equals("earlier-empty-chain")){var chains=new ArrayList<org.springframework.security.web.SecurityFilterChain>();chains.add(new org.springframework.security.web.DefaultSecurityFilterChain(org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE));chains.addAll(f.getFilterChains());return new org.springframework.security.web.FilterChainProxy(chains);}
                    if(bean instanceof org.springframework.security.web.SecurityFilterChain chain&&variant.equals("missing-owned-filter"))chain.getFilters().removeIf(f->f.getClass().getName().contains("GatewayIngressBoundary"));
                    if(name.equals("gatewayIamInterceptor")&&variant.equals("partial-interceptor"))return new org.springframework.web.servlet.handler.MappedInterceptor(new String[]{"/admin/**"},null,((org.springframework.web.servlet.handler.MappedInterceptor)bean).getInterceptor());
                    if(bean instanceof org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping&&Set.of("missing-any","third-any","alternate-handler").contains(variant)){
                        var entry=mapping.getHandlerMethods().entrySet().stream().filter(e->e.getKey().getPatternValues().contains("/admin/**")).findFirst().orElse(null);
                        if(entry!=null){var h=entry.getValue().createWithResolvedBean();if(variant.equals("missing-any")){mapping.unregisterMapping(entry.getKey());mapping.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/admin/**").build(),h.getBean(),h.getMethod());}else if(variant.equals("third-any")){mapping.registerMapping(org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/secondary/**").build(),h.getBean(),h.getMethod());}else{mapping.unregisterMapping(entry.getKey());var factory=new org.springframework.aop.framework.ProxyFactory(h.getBean());factory.setProxyTargetClass(true);mapping.registerMapping(entry.getKey(),factory.getProxy(),h.getMethod());}}
                    }
                    
                    return bean;
                })){throw new AssertionError("Invalid startup unexpectedly listened");}}).as(variant).isInstanceOf(Exception.class).hasStackTraceContaining(switch(variant){case "earlier-empty-chain"->"GATEWAY_CATCHALL_CHAIN_REQUIRED";case "missing-owned-filter"->"GATEWAY_FILTER_ORDER_INVALID";case "partial-interceptor"->"GATEWAY_GLOBAL_GUARD_REQUIRED";case "missing-any"->"GATEWAY_ANY_MAPPING_MISSING";case "third-any","alternate-handler"->"GATEWAY_ANY_MAPPING_INVALID";default->"GATEWAY_FILTER_NOT_INSTALLED";});
                assertThat(listenerEvents.get()).as("no WebServerInitializedEvent for "+variant).isEqualTo(beforeEvents);System.out.println("N4B2_STARTUP_REJECTED="+variant);
            }finally{proxy=saved;authority=savedAuthority;}
        }
    }
    @Test void liteAssemblyIsNotAComponentScanCandidate(){var scan=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(true);assertThat(scan.findCandidateComponents("cn.agentplatform.gateway.infra").stream().map(org.springframework.beans.factory.config.BeanDefinition::getBeanClassName)).doesNotContain(GatewayIamAssembly.class.getName());}    @Test void mainExpiresBetweenBufferAndActualFirstWriteWithZeroSuccessBytes()throws Exception {responseMode="before-write-expire";var r=request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),"{}");assertThat(r.statusCode()).isEqualTo(403);assertThat(expireOnReady).isTrue();assertThat(writtenBytes).hasValue(0);assertThat(posts).hasValue(1);awaitIdle();}
    @Test void exactTwoMibLegalRequestRemainsUnchangedAndOnlyPostsOnce()throws Exception {String prefix="{\"requestId\":\"boundary\",\"inputs\":{\"padding\":\"",suffix="\"}}";String body=prefix+"x".repeat(2*1024*1024-prefix.length()-suffix.length())+suffix;assertThat(request("POST","/admin/apps/Demo/runs","Bearer "+iam.portal(),body).statusCode()).isEqualTo(200);assertThat(originalBody).isEqualTo(body);assertThat(posts).hasValue(1);awaitIdle();}
    @Test void duplicateAuthCookieEncodedPathAndRegisteredDevHandlerCannotMintProof()throws Exception {
        try(var h=HttpClient.newHttpClient()){String target="http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/apps/Demo/runs";for(boolean cookie:List.of(false,true)){var b=HttpRequest.newBuilder(URI.create(target)).header("Authorization","Bearer "+iam.portal()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));if(cookie)b.header("Cookie","identity=forged");else b.header("Authorization","Bearer "+iam.portal());assertThat(h.send(b.build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);}}
        assertThat(request("POST","/admin/apps/%44emo/runs","Bearer "+iam.portal(),"{}").statusCode()).isIn(400,403);assertThat(context.getBean(cn.agentplatform.gateway.api.DevAuthentication.class)).isNotNull();assertThat(request("POST","/auth/dev-token","Bearer "+iam.portal(),"{}").statusCode()).isEqualTo(403);assertThat(queries).hasValue(0);assertThat(posts).hasValue(0);assertThat(iam.authorityCalls()).isZero();
    }
    @Test void incompletePrefixUsesOneTotalBudgetWithoutStartingAnyUpstream()throws Exception {
        try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){socket.setSoTimeout(8000);var out=socket.getOutputStream();out.write(("POST /admin/apps/Demo/runs HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+iam.portal()+"\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n1\r\n{\r\n").getBytes(StandardCharsets.UTF_8));out.flush();long start=System.nanoTime();while(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(5400)){Thread.sleep(400);try{out.write("1\r\n \r\n".getBytes(StandardCharsets.UTF_8));out.flush();}catch(java.io.IOException closed){break;}}assertThat(queries).hasValue(0);assertThat(posts).hasValue(0);assertThat(iam.authorityCalls()).isZero();}awaitIdle();
    }
    @Test void realHttpHandleLateInstallAndCloseRaceNeverStartAnotherRequest()throws Exception {
        try(var h=new PublishedRunHttp()){
            var cancelled=new PublishedRunHttp.Cancellation();assertThatThrownBy(()->h.get(URI.create(origin+"/internal/apps/Demo/published-metadata"),"finite-test",4096,System.nanoTime()+TimeUnit.SECONDS.toNanos(2),cancelled,cancelled::cancel)).isInstanceOf(PlatformException.class);assertThat(cancelled.installedHandles()).isEqualTo(1);assertThat(cancelled.cancelledHandles()).isEqualTo(1);assertThat(queries).hasValue(0);
            responseMode="stall-metadata";var scope=new PublishedRunHttp.Cancellation();var exited=new CountDownLatch(1);var task=Executors.newSingleThreadExecutor();try{var call=task.submit(()->{try{return h.get(URI.create(origin+"/internal/apps/Demo/published-metadata"),"finite-test",4096,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),scope,()->{});}finally{exited.countDown();}});assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();h.close();assertThatThrownBy(()->call.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThat(exited.await(1,TimeUnit.SECONDS)).isTrue();assertThat(scope.installedHandles()).isEqualTo(1);release.countDown();}finally{release.countDown();task.shutdownNow();assertThat(task.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}}
        awaitIdle();assertThat(queries).hasValue(1);assertThat(posts).hasValue(0);
    }
    @Test void slowResponseReaderClosesItselfBeforeOwnedResourcesAreChecked()throws Exception {
        responseMode="large";try(var socket=new Socket()){socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",context.getWebServer().getPort()));socket.setSoTimeout(8000);socket.getOutputStream().write(("POST /admin/apps/Demo/runs HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+iam.portal()+"\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}").getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();Thread.sleep(5500);}awaitIdle();assertThat(posts).hasValue(1);System.out.println("N4B2_SLOW_READER="+Json.write(Map.of("fixtureActivelyClosed",true,"submittedSuccessBytes",writtenBytes.get(),"workerStarts",proxy.observation().workerStarts(),"workerExits",proxy.observation().workerExits())));
    }}
