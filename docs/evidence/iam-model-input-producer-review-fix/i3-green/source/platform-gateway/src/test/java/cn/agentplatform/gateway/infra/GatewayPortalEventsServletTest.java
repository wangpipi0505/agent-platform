package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.*;
import cn.agentplatform.gateway.application.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.context.request.async.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;

/** Isolated explicit launcher; default GatewayApplication and real joined E/PG are outside B1. */
public class GatewayPortalEventsServletTest {
    static LoopbackAuthority iam;
    static PortalEventsTls endpoint;
    static CurrentAuthorityClient client;
    static String mode;
    static Path activeRun;
    static final AtomicInteger getters=new AtomicInteger(),writers=new AtomicInteger(),asyncGetters=new AtomicInteger(),completionThrows=new AtomicInteger();
    static final AtomicInteger requests=new AtomicInteger();
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity
    @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){return factory->factory.registerSingleton("authorityProperties",iam.config("gateway"));}
        @Bean Clock clock(){return iam.clock();}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder decoder){return new IntegrationAccess(iam.issuer(),iam.issuer(),decoder);}
        @Bean FilterRegistrationBean<Filter> observe(){
            var registration=new FilterRegistrationBean<Filter>((request,response,chain)->{
                requests.incrementAndGet();
                var supplied=new HttpServletResponseWrapper((HttpServletResponse)response){
                    @Override public PrintWriter getWriter()throws IOException{writers.incrementAndGet();return super.getWriter();}
                    @Override public ServletOutputStream getOutputStream()throws IOException{
                        getters.incrementAndGet();if(request.isAsyncStarted())asyncGetters.incrementAndGet();
                        if(mode.startsWith("postasync")){
                            Files.writeString(activeRun.resolve("getter-"+getters.get()+".txt"),"asyncStarted="+request.isAsyncStarted()+"\n"+String.join("\n",Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::toString).toList()));
                            if(request.isAsyncStarted())throw new IllegalStateException("owned getter failure");
                        }
                        return super.getOutputStream();
                    }
                };
                HttpServletRequest actual=(HttpServletRequest)request;
                if(mode.startsWith("postasync-cleanup"))actual=new HttpServletRequestWrapper(actual){
                    @Override public AsyncContext startAsync(){return throwingCompletion(super.startAsync());}
                };
                chain.doFilter(actual,supplied);
            });registration.setOrder(-200);registration.setAsyncSupported(true);return registration;
        }
    }
    @Configuration(proxyBeanMethods=false)
    @Import({Receiver.class,GatewayPortalEventsConfiguration.class})
    static class EventsReceiver {
        @Bean PortalEventsTls tls(){return endpoint;}
        @Bean CurrentAuthorityClient authority(){return client;}
        @Bean org.springframework.boot.web.server.WebServerFactoryCustomizer<TomcatServletWebServerFactory> customExecutorProbe(){
            return factory->{if(mode.equals("bad-custom-executor"))factory.addConnectorCustomizers(connector->((org.apache.coyote.AbstractProtocol<?>)connector.getProtocolHandler()).setExecutor(Runnable::run));};
        }
    }
    static AsyncContext throwingCompletion(AsyncContext actual){
        return new AsyncContext(){
            public ServletRequest getRequest(){return actual.getRequest();}
            public ServletResponse getResponse(){return actual.getResponse();}
            public boolean hasOriginalRequestAndResponse(){return actual.hasOriginalRequestAndResponse();}
            public void dispatch(){actual.dispatch();}
            public void dispatch(String path){actual.dispatch(path);}
            public void dispatch(ServletContext context,String path){actual.dispatch(context,path);}
            public void complete(){actual.complete();completionThrows.incrementAndGet();if(mode.endsWith("-error"))throw new AssertionError("owned completion returned then error");throw new UnsupportedOperationException("owned completion returned then threw");}
            public void start(Runnable task){actual.start(task);}
            public void addListener(AsyncListener listener){actual.addListener(listener);}
            public void addListener(AsyncListener listener,ServletRequest request,ServletResponse response){actual.addListener(listener,request,response);}
            public <T extends AsyncListener>T createListener(Class<T> type)throws ServletException{return actual.createListener(type);}
            public void setTimeout(long timeout){actual.setTimeout(timeout);}
            public long getTimeout(){return actual.getTimeout();}
        };
    }
    static ServletWebServerApplicationContext start(Path run,boolean events){
        var app=new SpringApplication(events?EventsReceiver.class:Receiver.class);app.setAdditionalProfiles("test");
        var values=new HashMap<String,Object>();
        values.put("server.address","127.0.0.1");values.put("server.port","0");values.put("server.tomcat.basedir",run.resolve("tomcat").toString());
        values.put("server.tomcat.threads.max",mode.equals("bad-threads")?65:64);
        values.put("server.tomcat.max-connections",mode.equals("bad-connections")?1025:1024);
        values.put("server.tomcat.processor-cache",mode.equals("bad-cache")?65:64);
        values.put("server.http2.enabled",mode.equals("bad-http2"));
        values.put("spring.config.location","optional:classpath:/c2b1-no-config/");values.put("spring.main.banner-mode","off");values.put("logging.level.root","ERROR");
        values.put("springdoc.api-docs.enabled",false);values.put("management.endpoints.web.exposure.exclude","*");values.put("management.endpoints.web.discovery.enabled",false);
        app.setDefaultProperties(values);return (ServletWebServerApplicationContext)app.run();
    }
    @Test void actualControllerSecurityChainHasExactEventsIngress()throws Exception{
        for(String group:System.getProperty("c2b1.groups","default,header,query,postasync,postasync-cleanup,postasync-cleanup-error,q403,qtransport,no-proxy").split(","))child(group);
    }
    @Test void exactFrameworkResponseBindingRejectsForeignManagersAndWrappers()throws Exception{child("framework-boundary");}
    @Test void exactCaptureCursorAndPurposeDenialsPrecedeSessionAndQuery()throws Exception{child("boundary");}
    @Test void actualBootFinalControlledProfileRejectsOverrides()throws Exception{
        for(String group:List.of("bad-threads","bad-connections","bad-cache","bad-http2","bad-custom-executor"))child(group);
    }
    @Test void originalRawServletBootstrapRegressionUsesOnlyOwnedRunRoot()throws Exception{child("raw-sequence");}
    static void child(String group)throws Exception{
        Path run=Files.createDirectories(root().resolve(".local/iam-o2b-f2c2b1-events-wiring/peers")).resolve(group+"-"+UUID.randomUUID());Files.createDirectory(run);
        var args=List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768","-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false","-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),GatewayPortalEventsServletTest.class.getName(),group,run.toString());
        Files.writeString(run.resolve("client.args"),String.join("\n",args));
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());command.addAll(args);
        Process process=new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Files.writeString(run.resolve("launch.properties"),"pid="+process.pid()+"\ncwd="+root()+"\n");
        var error=new AtomicReference<Throwable>();
        Thread collector=Thread.ofPlatform().daemon().start(()->{
            try(var out=Files.newOutputStream(run.resolve("client.out"));var in=process.getInputStream()){
                byte[] buffer=new byte[4096];int n,total=0;
                while((n=in.read(buffer))!=-1){if((total+=n)>131072)throw new IOException("bounded child output");out.write(buffer,0,n);}
            }catch(Throwable failure){error.set(failure);process.destroyForcibly();}
        });
        try{
            assertTrue(process.waitFor(60,TimeUnit.SECONDS),"owned child deadline");collector.join(3000);assertFalse(collector.isAlive());assertNull(error.get());
            assertEquals(0,process.exitValue(),new String(Files.readAllBytes(run.resolve("client.out")),StandardCharsets.UTF_8));
        }finally{
            if(process.isAlive())process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));collector.join(3000);assertFalse(collector.isAlive());
            Files.writeString(run.resolve("exit.properties"),"pid="+process.pid()+"\nexit="+process.exitValue()+"\nalive="+process.isAlive()+"\ncollectorAlive="+collector.isAlive()+"\nwaitForReturned=true\n");
        }
    }
    public static void main(String[] args)throws Exception{
        mode=args[0];Path run=Path.of(args[1]);activeRun=run;
        if(mode.equals("raw-sequence")){PortalRunEventsSseLifecycleTest.scenario("sequence",run);return;}
        if(mode.equals("boundary")){boundary(run);return;}
        if(mode.equals("framework-boundary")){frameworkBoundary(run);return;}
        if(mode.equals("no-proxy")){noProxy(run);return;}
        if(mode.startsWith("bad-")){badProfile(run);return;}
        boot(run);
    }
    static void boot(Path run)throws Exception{
        try(var f=new Fixture();var peer=new PortalRunEventsSseLifecycleTest.SequencePeer(f,run,m->{},mode.equals("q403")?"q403":mode.equals("qtransport")?"qtransport":"sequence",utf("null"));var tls=peer.tls()){
            iam=f.iam;endpoint=tls;client=f.gateway;
            try(var context=start(run,true);var http=HttpClient.newHttpClient()){
                assertSame(PortalRunEventsSse.class,context.getBean(PortalRunEventsSse.class).getClass());assertSame(PortalRunEventsHttp.class,context.getBean(PortalRunEventsHttp.class).getClass());assertSame(RunEventsProxy.class,context.getBean(RunEventsProxy.class).getClass());
                var controlled=assertInstanceOf(PortalRunEventsSse.ControlledProtocol.class,((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler());
                assertEquals(1024,controlled.getMaxConnections());assertEquals(64,controlled.getMaxThreads());assertEquals(64,controlled.getProcessorCache());assertInstanceOf(org.apache.tomcat.util.threads.ThreadPoolExecutor.class,controlled.getExecutor());assertEquals(0,controlled.findUpgradeProtocols().length);
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/runs/"+f.run+"/events"+(mode.equals("query")?"?Last-Event-ID=0":""))).header("Authorization","Bearer "+f.source).timeout(Duration.ofSeconds(15));
                if(mode.equals("header"))request.header("Last-Event-ID","0");
                if(mode.startsWith("postasync")){
                    // JDK HttpClient retries a GET on pre-header CLOSE_NOW. One physical request
                    // isolates the owned response and cannot mistake a second 503 for a JSON tail.
                    try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){
                        socket.setSoTimeout(12000);
                        socket.getOutputStream().write(utf("GET /admin/runs/"+f.run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAuthorization: Bearer "+f.source+"\r\n\r\n"));socket.getOutputStream().flush();
                        try{assertFalse(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("\"code\":"));}catch(SocketException expected){}
                    }
                }else try{
                    var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if(mode.equals("q403")){assertEquals(403,response.statusCode());assertEquals("RUN_AUTHORITY_DENIED",Json.read(response.body()).path("code").asText());}
                    else if(mode.equals("qtransport")){assertEquals(503,response.statusCode());assertEquals("RUN_EVENTS_UNAVAILABLE",Json.read(response.body()).path("code").asText());}
                    else if(!mode.startsWith("postasync")){
                        assertEquals(200,response.statusCode());assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));assertTrue(response.body().contains("id: 2\n"));assertTrue(response.body().contains("event: stream_status"));assertTrue(response.body().contains("data: null"));assertEquals(0,writers.get());
                    }
                }catch(IOException failure){throw failure;}
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
                if(mode.startsWith("postasync")){assertEquals(1,requests.get());assertEquals(0,writers.get());assertTrue(asyncGetters.get()>0);if(mode.startsWith("postasync-cleanup"))assertTrue(completionThrows.get()>0);}
                boolean preasyncFailure=mode.equals("q403")||mode.equals("qtransport");
                if(!preasyncFailure)assertTrue(asyncGetters.get()>0,"actual supplied MVC response getter reached");assertEquals(preasyncFailure?1:2,peer.connections);
                Files.writeString(run.resolve("boot.properties"),"actualController=true\nactualSecurityWrappers=true\nproductionBeans=true\nactualProtocol="+controlled.getClass().getName()+"\nmaxConnections=1024\nmaxThreads=64\nprocessorCache=64\ninternalExecutor=true\nhttp2=false\ngetters="+getters+"\nwriters="+writers+"\nasyncGetters="+asyncGetters+"\ncleanupThrows="+completionThrows+"\nfinalOwners=0\nfinalBytes=3145728\nprivateConnections="+peer.connections+"\n");
            }
        }
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("portal-events-sse-reader")));
        Files.writeString(run.resolve("peer-exit.properties"),"contextClosed=true\npeerClosed=true\nnoLiveSseReader=true\n");
    }
    static void noProxy(Path run)throws Exception{
        try(var f=new LoopbackAuthority()){
            iam=f;try(var context=start(run,false);var http=HttpClient.newHttpClient()){
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+"/admin/runs/r_"+"a".repeat(32)+"/events")).header("Authorization","Bearer "+f.portal()).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(403,response.statusCode());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertTrue(context.getBeansOfType(RunEventsProxy.class).isEmpty());
            }
        }Files.writeString(run.resolve("no-proxy.properties"),"actualControllerDenied=403\nnewSession=0\n");
    }
    static void badProfile(Path run)throws Exception{
        try(var f=new LoopbackAuthority();var tls=new PortalEventsTls(URI.create("https://"+HOST+":443"),new byte[]{127,0,0,1},PortalRunEventsHttpTest.ca(run,"root"));var authority=f.client("gateway")){
            iam=f;endpoint=tls;client=authority;
            Throwable failure=assertThrows(RuntimeException.class,()->{try(var ignored=start(run,true)){fail("invalid profile started");}});
            boolean found=false;for(Throwable cursor=failure;cursor!=null;cursor=cursor.getCause())if(cursor.getMessage()!=null&&cursor.getMessage().contains(mode.equals("bad-custom-executor")?"Custom executor prohibited":"Controlled HTTP/1.1 NIO profile changed"))found=true;
            assertTrue(found,"actual controlled profile must reject the final Boot override");assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
            Files.writeString(run.resolve("profile-denied.properties"),"mode="+mode+"\nrealBootStartupRejected=true\nnewSession=0\n");
        }
    }
    interface Check {void run(GatewayIngressBoundary boundary,HttpServletRequest request,HttpServletResponse response)throws Exception;}
    static MockHttpServletRequest request(String token){var r=new MockHttpServletRequest("GET","/admin/runs/r_"+"a".repeat(32)+"/events");r.addHeader("Authorization","Bearer "+token);return r;}
    static int ingress(GatewayIngressBoundary boundary,GatewayController controller,MockHttpServletRequest request,Check check)throws Exception{
        var response=new MockHttpServletResponse();boundary.filter().doFilter(request,response,(r,s)->{
            var actual=new HttpServletRequestWrapper((HttpServletRequest)r);var supplied=new HttpServletResponseWrapper((HttpServletResponse)s);
            try{assertTrue(boundary.preHandle(actual,supplied,new HandlerMethod(controller,GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class))));check.run(boundary,actual,supplied);}
            catch(RuntimeException|Error failure){throw failure;}catch(Exception failure){throw new ServletException(failure);}
            finally{boundary.afterCompletion(actual,supplied,null,null);}
        });return response.getStatus();
    }
    static void denied(Runnable action){assertThrows(PlatformException.class,action::run);}
    static void frameworkBoundary(Path run)throws Exception{
        try(var f=new LoopbackAuthority();var decoder=new TrustedJwtDecoder(f.config("gateway"),f.clock(),true)){
            var controller=new GatewayController(new StandardEnvironment(),new IntegrationAccess(f.issuer(),f.issuer(),decoder),f.config("gateway"),Optional.empty());
            var boundary=new GatewayIngressBoundary(controller,null,false,true,decoder,f.clock());String token=f.portal(120);
            var saved=new AtomicReference<Object>();
            assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{
                saved.set(r.getAttribute(GatewayIngressBoundary.class.getName()+".proof"));
                var manager=WebAsyncUtils.getAsyncManager(r);var current=new StandardServletAsyncWebRequest(r,s);manager.setAsyncWebRequest(current);
                var actual=current.getResponse();assertNotSame(s,actual);
                denied(()->GatewayIngressBoundary.claimEvents(r,s));denied(()->GatewayIngressBoundary.claimEvents(r,new HttpServletResponseWrapper(actual)));
                var otherManager=WebAsyncUtils.getAsyncManager(new MockHttpServletRequest());r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,otherManager);denied(()->GatewayIngressBoundary.claimEvents(r,actual));r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,manager);
                manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(new HttpServletRequestWrapper(r),s));denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
                manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,new MockHttpServletResponse()));denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
                manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s){});denied(()->GatewayIngressBoundary.claimEvents(r,manager.getAsyncWebRequest().getNativeResponse(HttpServletResponse.class)));
                manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s));denied(()->GatewayIngressBoundary.claimEvents(r,actual));
                manager.setAsyncWebRequest(current);var capture=GatewayIngressBoundary.claimEvents(r,actual);capture.requireLive(r,actual);denied(()->capture.requireLive(r,new HttpServletResponseWrapper(actual)));denied(()->capture.requireLive(r,s));
                manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(r,s));denied(()->capture.requireLive(r,actual));manager.setAsyncWebRequest(current);
                r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,otherManager);denied(()->capture.requireLive(r,actual));r.setAttribute(WebAsyncUtils.WEB_ASYNC_MANAGER_ATTRIBUTE,manager);
                b.afterCompletion(r,s,null,null);denied(()->capture.requireLive(r,actual));
            }));
            for(String field:List.of("eventsRequest","eventsResponse","eventsHandlerResponse","eventsManager","eventsAsyncRequest"))assertNull(PortalRunEventsSseLifecycleTest.field(saved.get(),field));
            var r=request(token);var response=new MockHttpServletResponse();
            boundary.filter().doFilter(r,response,(a,s)->{
                try{boundary.preHandle((HttpServletRequest)a,(HttpServletResponse)s,new HandlerMethod(new GatewayController(new StandardEnvironment(),null,f.config("gateway"),Optional.empty()),GatewayController.class.getMethod("proxy",HttpServletRequest.class,HttpServletResponse.class)));}
                catch(ReflectiveOperationException failure){throw new ServletException(failure);}
            });assertEquals(403,response.getStatus());
            assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertTrue(f.observedOperations().isEmpty());
            Files.writeString(run.resolve("framework-binding.properties"),"exactManager=true\nexactStandardFrameworkCurrent=true\nexactHandlerResponse=true\ndirectPreHandleDelegate=true\nforeignManagerRejected=true\nforeignNativeResponseRejected=true\narbitraryWrapperRejected=true\nsubclassRejected=true\nforeignHandlerRejected=true\nclearCutsFiveReverseReferences=true\nnewSession=0\nnewQuery=0\n");
        }
    }
    static void boundary(Path run)throws Exception{
        try(var f=new LoopbackAuthority();var decoder=new TrustedJwtDecoder(f.config("gateway"),f.clock(),true);var tls=new PortalEventsTls(URI.create("https://"+HOST+":443"),new byte[]{127,0,0,1},PortalRunEventsHttpTest.ca(run,"root"));var authority=f.client("gateway")){
            var integration=new IntegrationAccess(f.issuer(),f.issuer(),decoder);var env=new StandardEnvironment();
            var controller=new GatewayController(env,integration,f.config("gateway"),Optional.empty(),Optional.empty(),Optional.empty());
            assertNotNull(new GatewayController(env,integration,f.config("gateway"),Optional.empty()));assertNotNull(new GatewayController(env,integration,f.config("gateway"),Optional.empty(),Optional.empty()));
            var boundary=new GatewayIngressBoundary(controller,null,true,true,decoder,f.clock());String token=f.portal(120);var http=new PortalRunEventsHttp(tls);var sse=new PortalRunEventsSse();
            for(boolean query:new boolean[]{false,true})for(String value:List.of("0","1",Long.toString(Long.MAX_VALUE))){
                var r=request(token);if(query)r.setQueryString("Last-Event-ID="+value);else r.addHeader("Last-Event-ID",value);
                assertEquals(200,ingress(boundary,controller,r,(b,actual,supplied)->{
                    var capture=GatewayIngressBoundary.claimEvents(actual,supplied);assertEquals(Long.parseLong(value),capture.cursor());assertEquals("r_"+"a".repeat(32),capture.publicRunId());assertEquals(new SubjectKey("tenant-a","alice"),capture.subject());capture.requireLive(actual,supplied);
                    r.removeHeader("Last-Event-ID");r.addHeader("Last-Event-ID","invalid-after-capture");capture.requireLive(actual,supplied);assertEquals(Long.parseLong(value),capture.cursor());
                }));
            }
            var escaped=new AtomicReference<GatewayIngressBoundary.PortalEventsRequest>();var savedR=new AtomicReference<HttpServletRequest>();var savedS=new AtomicReference<HttpServletResponse>();var savedProof=new AtomicReference<Object>();
            assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{
                var capture=GatewayIngressBoundary.claimEvents(r,s);assertEquals(0,capture.cursor());escaped.set(capture);savedR.set(r);savedS.set(s);savedProof.set(r.getAttribute(GatewayIngressBoundary.class.getName()+".proof"));
                denied(()->GatewayIngressBoundary.claimEvents(r,s));denied(()->GatewayIngressBoundary.claim(r));denied(()->GatewayIngressBoundary.claimStatus(r));denied(()->GatewayIngressBoundary.claimSession(r));
                denied(()->capture.requireLive(new HttpServletRequestWrapper(r),s));denied(()->capture.requireLive(r,new HttpServletResponseWrapper(s)));denied(()->capture.requireLive(new MockHttpServletRequest("GET",r.getRequestURI()),s));denied(()->capture.requireLive(r,new MockHttpServletResponse()));
                var foreign=request(token);foreign.setAttribute(GatewayIngressBoundary.class.getName()+".proof",savedProof.get());denied(()->GatewayIngressBoundary.claimEvents(foreign,s));
                var error=new AtomicReference<Throwable>();var thread=Thread.ofPlatform().start(()->{try{capture.requireLive(r,s);}catch(Throwable failure){error.set(failure);}});thread.join(2000);assertFalse(thread.isAlive());assertInstanceOf(PlatformException.class,error.get());
                assertThrows(PlatformException.class,()->sse.start(new HttpServletRequestWrapper(r),s,http,authority,capture));assertThrows(IllegalStateException.class,()->sse.start(r,s,http,authority,capture),"no controlled original token");
                b.afterConcurrentHandlingStarted(r,s,null);denied(()->capture.requireLive(r,s));
            }));
            denied(()->escaped.get().requireLive(savedR.get(),savedS.get()));assertNull(PortalRunEventsSseLifecycleTest.field(savedProof.get(),"eventsRequest"));assertNull(PortalRunEventsSseLifecycleTest.field(savedProof.get(),"eventsResponse"));
            var invalid=new ArrayList<Consumer<MockHttpServletRequest>>();
            for(String value:List.of("","-1","+1","00","01"," 1","1 ","1,2","9223372036854775808","99999999999999999999","１","١")){invalid.add(r->r.addHeader("Last-Event-ID",value));invalid.add(r->r.setQueryString("Last-Event-ID="+value));}
            for(String query:List.of("x=1","last-event-id=0","%4cast-Event-ID=0","Last-Event-ID=%30","Last-Event-ID=0&Last-Event-ID=0","Last-Event-ID=0&tenant=x","Last-Event-ID=0;tenant=x","tenant=x","Last-Event-ID=0&"))invalid.add(r->r.setQueryString(query));
            invalid.add(r->{r.addHeader("Last-Event-ID","0");r.addHeader("Last-Event-ID","0");});invalid.add(r->{r.addHeader("Last-Event-ID","0");r.setQueryString("Last-Event-ID=0");});
            for(String header:List.of("Cookie","X-Api-Key","ApiKey","X-User-id","X-Tenant-id","X-Principal","X-Actor-id","tenant","subject","Proxy-Authorization","X-Auth-token"))invalid.add(r->r.addHeader(header,"forged"));
            invalid.add(r->r.setContent(utf("body")));invalid.add(r->r.addHeader("Transfer-Encoding","chunked"));invalid.add(r->r.setContextPath("/ctx"));invalid.add(r->r.setDispatcherType(DispatcherType.ASYNC));invalid.add(r->r.setMethod("POST"));
            invalid.add(r->r.setRequestURI("/admin/runs/r_"+"A".repeat(32)+"/events"));invalid.add(r->r.setRequestURI("/admin/runs/r_"+"a".repeat(32)+"/events/"));invalid.add(r->r.setRequestURI("/admin/runs/r_"+"a".repeat(32)+"/%65vents"));
            for(var mutate:invalid){var r=request(token);mutate.accept(r);assertEquals(403,ingress(boundary,controller,r,(b,a,s)->fail("invalid ingress reached handler")));}
            var noEvents=new GatewayIngressBoundary(controller,null,true,decoder,f.clock());assertEquals(403,ingress(noEvents,controller,request(token),(b,a,s)->fail("missing component")));
            for(String path:List.of("/admin/runs/r_"+"a".repeat(32),"/admin/apps/Demo/runs","/auth/session")){
                var r=request(token);r.setRequestURI(path);if(path.contains("apps"))r.setMethod("POST");r.setQueryString("Last-Event-ID=0");assertEquals(403,ingress(boundary,controller,r,(b,a,s)->fail("other purpose query broadened")));
            }
            assertEquals(200,ingress(boundary,controller,request(token),(b,r,s)->{var capture=GatewayIngressBoundary.claimEvents(r,s);f.advanceClock(Duration.ofSeconds(121));assertEquals(401,assertThrows(PlatformException.class,()->capture.requireLive(r,s)).status());}));
            assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());assertTrue(f.observedOperations().isEmpty(),"no opening/Q before capture and controlled token checks");
            Files.writeString(run.resolve("boundary.properties"),"cursorAccepted=default0,header0,header1,headerLongMax,query0,query1,queryLongMax\ninvalidVectors="+invalid.size()+"\nexactPairAndThread=true\nclearCutsReverseReferences=true\nprivateProofOnly=true\nnewSession=0\nnewQuery=0\noldConstructors=true\n");
        }
    }
}
