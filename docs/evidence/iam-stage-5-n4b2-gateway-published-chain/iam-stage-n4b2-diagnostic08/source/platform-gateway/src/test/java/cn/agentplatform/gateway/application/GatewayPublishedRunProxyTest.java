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
    static HttpCurrentAuthorityClient authority;static PublishedRunProxy proxy;
    static final String SHA=Digest.sha256("stored");
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class) @EnableWebSecurity(debug=false)
    @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor deployment(org.springframework.core.env.Environment e){return f->f.registerSingleton("authorityProperties",SecurityConfiguration.bindDeployment(e));}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess(origin,origin,d){@Override public void check(HttpServletRequest r,Actor a){legacyChecks.incrementAndGet();super.check(r,a);}};}
        @Bean Clock clock(){return iam.clock();}
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
            if(mode.equals("expire")&&post)iam.advanceClock(Duration.ofSeconds(6));
            x.sendResponseHeaders(200,b.length);
            if((mode.equals("stall-metadata")&&query)||(mode.equals("stall-engine")&&post)){
                x.getResponseBody().write(b,0,b.length-1);x.getResponseBody().flush();entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}x.getResponseBody().write(b,b.length-1,1);
            }else x.getResponseBody().write(b);
        }catch(Exception ignored){}finally{handlers.decrementAndGet();}});app.start();origin="http://127.0.0.1:"+app.getAddress().getPort();
        context=boot(Receiver.class);
    }
    static ServletWebServerApplicationContext boot(Class<?> type){
        var b=new SpringApplication(type);b.setAdditionalProfiles("test");b.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n4b2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.services.app",origin),Map.entry("agent.services.engine",origin)));
        b.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("n4b2-trusted-deployment",trustSettings())));return (ServletWebServerApplicationContext)b.run();
    }
    static Map<String,Object> trustSettings(){var p=new LinkedHashMap<String,Object>();flatten("agent.security",Json.tree(iam.config("gateway")),p);p.put("agent.dev-auth",false);return p;}
    static void flatten(String prefix,com.fasterxml.jackson.databind.JsonNode v,Map<String,Object> p){
        if(v.isObject())v.fields().forEachRemaining(e->{String k=prefix.endsWith("attribute-value-allowlist")?"["+e.getKey()+"]":"."+e.getKey().replaceAll("([a-z])([A-Z])","$1-$2").toLowerCase(Locale.ROOT);flatten(prefix+k,e.getValue(),p);});else if(v.isArray()){for(int i=0;i<v.size();i++)flatten(prefix+"["+i+"]",v.get(i),p);}else if(!v.isNull())p.put(prefix,v.isBoolean()?v.booleanValue():v.isNumber()?v.numberValue():v.asText());
    }
    @BeforeEach void reset()throws Exception {release.countDown();awaitIdle();iam.clearFiniteGrants();iam.revoked(false);iam.unavailable(false);iam.ticketMutation(c->{});iam.decisionMutation(c->{});responseMode="normal";legacy.set(0);legacyChecks.set(0);queries.set(0);posts.set(0);entered=new CountDownLatch(1);release=new CountDownLatch(1);}
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
        for(String path:List.of("/admin/apps/Demo/runs?","/admin/apps/Demo/runs/","/admin/apps/1Demo/runs","/admin/apps/Demo/runs;alias","/v1/runs","/admin/apps/Demo","/assistant.js","/v3/api-docs"))assertThat(request("POST",path,"Bearer "+iam.portal(),"{}").statusCode()).as(path).isIn(400,403);
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
}
