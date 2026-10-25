package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.io.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.*;
import org.springframework.boot.web.servlet.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

class PortalRunEventsHttpTest {
    static PortalRunEventWindowPostgresTest.Fixture fixture;
    static final AtomicInteger frameLeaks=new AtomicInteger();
    static volatile Hooks hooks;
    static volatile int streamSlots=2;
    static final class Hooks {
        volatile String block;volatile Runnable afterGet=()->{},beforeWrite=()->{},afterFlush=()->{};
        final CountDownLatch entered,release=new CountDownLatch(1);volatile CountDownLatch secondRelease;
        final AtomicInteger blockCalls=new AtomicInteger();final List<AsyncContext> contexts=new CopyOnWriteArrayList<>();
        final AtomicInteger gets=new AtomicInteger(),writes=new AtomicInteger(),flushes=new AtomicInteger(),completes=new AtomicInteger(),exits=new AtomicInteger();
        final AtomicInteger timeouts=new AtomicInteger(),notifications=new AtomicInteger();
        volatile AsyncContext context;volatile long timeout;
        volatile boolean missingHandler,missingFrame,forward,failListener;
        Hooks(){this(1);}Hooks(int count){entered=new CountDownLatch(count);}
        void at(String stage){if(stage.equals(block)){var gate=blockCalls.incrementAndGet()>1&&secondRelease!=null?secondRelease:release;entered.countDown();boolean done=false;while(!done)try{done=gate.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}}
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import(cn.agentplatform.engine.EnginePortalEventsAssembly.class)
    static class Receiver {
        @Bean javax.sql.DataSource source(){return fixture.source;}
        @Bean java.time.Clock clock(){return fixture.auth.iam.clock();}
        @Bean(destroyMethod="") HttpCurrentAuthorityClient current(){return fixture.auth.engine;}
        @Bean JwtDecoder decoder(){return PortalRunEventsAuthorityFixture.decoder(fixture.auth);}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient c){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),c);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor mapped(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> maps){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),maps);}
        @Bean PlatformErrors errors(){return new PlatformErrors();}
        @Bean(destroyMethod="close") ChunkBus chunks(){return new ChunkBus(streamSlots,8192);}
        @Bean(destroyMethod="close") RuntimeClients clients(){return new RuntimeClients(fixture.auth.iam.config("engine"),fixture.auth.engine,"http://127.0.0.1:1",true);}
        @Bean(destroyMethod="close") RunScheduler scheduler(){return new RunScheduler(fixture.trace){@Override public void start(){}};}
        @Bean RunService service(RuntimeClients clients,RunScheduler scheduler){return new RunService(fixture.trace,clients,null,null,scheduler);}
        @Bean RunController controller(RunService s,ChunkBus c){return new RunController(s,fixture.trace,c,null);}
        @Bean FilterRegistrationBean<jakarta.servlet.Filter> probe(){
            var b=new FilterRegistrationBean<jakarta.servlet.Filter>();b.setAsyncSupported(true);b.setOrder(Integer.MAX_VALUE);
            b.setFilter((r,s,c)->{
                Hooks hook=hooks;
                if(hook==null){try{c.doFilter(r,s);}finally{probeFrame(r);}return;}
                if(hook.forward){try{((HttpServletRequest)r).getRequestDispatcher(((HttpServletRequest)r).getRequestURI()).forward(r,s);}finally{probeFrame(r);}return;}
                var response=new HttpServletResponseWrapper((HttpServletResponse)s){
                    @Override public ServletOutputStream getOutputStream()throws IOException{
                        hook.gets.incrementAndGet();hook.at("get");var delegate=super.getOutputStream();hook.afterGet.run();
                        return new ServletOutputStream(){
                            public boolean isReady(){return delegate.isReady();}public void setWriteListener(WriteListener l){delegate.setWriteListener(l);}
                            public void write(int v)throws IOException{hook.beforeWrite.run();hook.at("write");hook.writes.incrementAndGet();delegate.write(v);}
                            @Override public void write(byte[] a,int o,int n)throws IOException{hook.beforeWrite.run();hook.at("write");hook.writes.incrementAndGet();delegate.write(a,o,n);}
                            @Override public void flush()throws IOException{hook.at("flush");hook.flushes.incrementAndGet();delegate.flush();hook.afterFlush.run();}
                        };
                    }
                };
                var request=new HttpServletRequestWrapper((HttpServletRequest)r){
                    @Override public AsyncContext startAsync(ServletRequest req,ServletResponse res){
                        var actual=super.startAsync(req,res);hook.context=actual;hook.contexts.add(actual);
                        return new AsyncContext(){
                            public ServletRequest getRequest(){return actual.getRequest();}public ServletResponse getResponse(){return actual.getResponse();}public boolean hasOriginalRequestAndResponse(){return actual.hasOriginalRequestAndResponse();}
                            public void dispatch(){actual.dispatch();}public void dispatch(String p){actual.dispatch(p);}public void dispatch(ServletContext x,String p){actual.dispatch(x,p);}
                            public void complete(){hook.completes.incrementAndGet();hook.at("complete");try{actual.complete();}finally{hook.exits.incrementAndGet();}}
                            public void start(Runnable t){actual.start(t);}public void addListener(AsyncListener l){if(hook.failListener)throw new IllegalStateException("LISTENER_INSTALL_FAILURE");actual.addListener(new AsyncListener(){public void onTimeout(AsyncEvent e)throws IOException{hook.timeouts.incrementAndGet();l.onTimeout(e);}public void onComplete(AsyncEvent e)throws IOException{hook.notifications.incrementAndGet();l.onComplete(e);}public void onError(AsyncEvent e)throws IOException{l.onError(e);}public void onStartAsync(AsyncEvent e)throws IOException{l.onStartAsync(e);}});}public void addListener(AsyncListener l,ServletRequest q,ServletResponse p){actual.addListener(l,q,p);}
                            public <T extends AsyncListener>T createListener(Class<T> t)throws ServletException{return actual.createListener(t);}
                            public void setTimeout(long t){actual.setTimeout(hook.timeout>0?hook.timeout:t);}public long getTimeout(){return actual.getTimeout();}
                        };
                    }
                };
                try{c.doFilter(request,response);}finally{probeFrame(r);}
            });return b;
        }
        @Bean org.springframework.web.servlet.handler.MappedInterceptor negativeBoundaryProbe(){return new org.springframework.web.servlet.handler.MappedInterceptor(null,new org.springframework.web.servlet.HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
                var hook=hooks;if(hook!=null){if(hook.missingHandler)request.removeAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
                    if(hook.missingFrame&&request.getAttribute(ServiceAuthorization.class.getName()+".frame") instanceof AutoCloseable frame){frame.close();request.removeAttribute(ServiceAuthorization.class.getName()+".frame");}}
                return true;
            }
        });}
        static void probeFrame(ServletRequest r){if(r.getAttribute(ServiceAuthorization.class.getName()+".frame")!=null||catchThrowable(AuthorityContext::current)==null)frameLeaks.incrementAndGet();}
    }
    static ServletWebServerApplicationContext start(Class<?> receiver){
        var app=new SpringApplication(receiver);app.setAdditionalProfiles("test");app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off","spring.config.location","optional:classpath:/c-no-config/","spring.jmx.enabled",false,"spring.flyway.enabled",false,"springdoc.api-docs.enabled",false,"logging.level.root","ERROR","management.endpoints.web.exposure.exclude","*","management.endpoints.web.discovery.enabled",false));
        return (ServletWebServerApplicationContext)app.run();
    }
    static HttpResponse<String> request(ServletWebServerApplicationContext server,String run,String ticket,String suffix)throws Exception{
        try(var client=HttpClient.newHttpClient()) {return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getWebServer().getPort()+"/admin/runs/"+run+"/events"+suffix)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+ticket).GET().build(),HttpResponse.BodyHandlers.ofString());}
    }
    @Test void realSignedMappedEngineEventsUseProtectedWindow() throws Exception {
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){fixture=f;var b=f.seed(0);
            String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=start(Receiver.class)){
                var response=request(server,b.publicId(),ticket,"?Last-Event-ID=2");
                assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("CURSOR_AHEAD");
                assertThat(frameLeaks).hasValue(0);
            }
        }
    }
    @Test void actualWireDrainsFifteenNodeTerminalRunAndLastEventIdResumes()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){fixture=f;var b=f.seed(0);f.finish(b.runId());
            String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=start(Receiver.class)){
                var all=request(server,b.publicId(),ticket,"");assertThat(all.statusCode()).isEqualTo(200);
                assertThat(all.body()).contains("event:stream_status\ndata:{\"chunkAvailable\":false,\"chunkReplay\":false","id:1\nevent:C_FIRST\ndata:","观察","id:2\nevent:RUN_FINISHED\ndata:");
                var replay=send(server,"/admin/runs/"+b.publicId()+"/events",ticket,"GET",List.of("1"),null);
                assertThat(replay.body()).contains("id:2\nevent:RUN_FINISHED").doesNotContain("event:C_FIRST");
                var priority=send(server,"/admin/runs/"+b.publicId()+"/events?Last-Event-ID=0000000000000000000",ticket,"GET",List.of("bad","also-bad"),null);
                assertThat(priority.statusCode()).isEqualTo(200);assertThat(priority.body()).contains("id:1\nevent:C_FIRST");
            }
        }
    }
    @Test void selectedCursorInvalidDuplicatesUnknownQueryAliasesAndMissingServiceHaveNoPayloadSql()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=start(Receiver.class)){
                String path="/admin/runs/"+b.publicId()+"/events";
                for(String suffix:List.of("?Last-Event-ID=","?Last-Event-ID=-1","?Last-Event-ID=%2B1","?Last-Event-ID=%201","?Last-Event-ID=%EF%BC%91","?Last-Event-ID=9223372036854775808","?Last-Event-ID=1&Last-Event-ID=2","?unknown=1")){
                    f.source.start();var result=request(server,b.publicId(),ticket,suffix);assertThat(result.statusCode()).as(suffix).isEqualTo(400);assertNoPayload(f);
                }
                f.source.start();assertThat(send(server,path,ticket,"GET",List.of("1","2"),null).statusCode()).isEqualTo(400);assertNoPayload(f);
                f.source.start();assertThat(send(server,path,ticket,"GET",List.of(" "),null).statusCode()).isEqualTo(400);assertNoPayload(f);
                for(String alias:List.of("/admin/debug-runs/","/v1/runs/")){f.source.start();assertThat(send(server,alias+b.publicId()+"/events",ticket,"GET",List.of(),null).statusCode()).isEqualTo(400);assertNoPayload(f);}
                for(String method:List.of("HEAD","POST","PUT","DELETE")){f.source.start();assertThat(send(server,path,ticket,method,List.of(),null).statusCode()).isIn(403,405);assertNoPayload(f);}
                f.source.start();assertThat(send(server,path,ticket,"GET",List.of(),"body").statusCode()).isEqualTo(400);assertNoPayload(f);
                server.getBean(RunController.class).portalEvents(null);f.source.start();assertThat(request(server,b.publicId(),ticket,"").statusCode()).isEqualTo(403);assertNoPayload(f);
                assertThat(frameLeaks).hasValue(0);
            }
        }
    }
    static void assertNoPayload(PortalRunEventWindowPostgresTest.Fixture f){assertThat(f.source.getters).doesNotContain("payload_text","event_text","state_text");}
    @Test void realMappedHandlerDispatchAndFrameFailuresStayClosedAndUnknownOrOtherOwnerStay404()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=start(Receiver.class)){
                for(String failure:List.of("handler","frame","forward")){
                    var h=new Hooks();h.missingHandler=failure.equals("handler");h.missingFrame=failure.equals("frame");h.forward=failure.equals("forward");hooks=h;
                    try{f.source.start();assertThat(request(server,b.publicId(),ticket,"").statusCode()).isIn(400,403);assertNoPayload(f);}finally{hooks=null;}
                }
                String unknown="r_"+UUID.randomUUID().toString().replace("-","");String unknownTicket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),unknown,b.snapshotSha());
                f.source.start();assertThat(request(server,unknown,unknownTicket,"").statusCode()).isEqualTo(404);assertNoPayload(f);
                f.source.start();assertThat(request(server,b.publicId(),unknownTicket,"").statusCode()).isEqualTo(403);assertNoPayload(f);
                f.auth.actor("tenant-a","bob",Set.of("A","B"));var other=f.seed(0);f.auth.actor("tenant-a","alice",Set.of("A","B"));
                String otherTicket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),other.publicId(),other.snapshotSha());
                f.source.start();assertThat(request(server,other.publicId(),otherTicket,"").statusCode()).isEqualTo(404);assertNoPayload(f);
            }
        }
    }
    @Test void listenerInstallationFailureCompletesOnceAndReleasesItsRealOwnedResources()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());var h=new Hooks();h.failListener=true;hooks=h;
            try(var server=start(Receiver.class)){
                var result=request(server,b.publicId(),ticket,"");assertThat(result.statusCode()).isEqualTo(500);assertThat(result.body()).contains("STREAM_UNAVAILABLE");assertThat(h.completes).hasValue(1);assertThat(h.exits).hasValue(1);
                var bus=server.getBean(ChunkBus.class);assertThat(PortalRunEventsLifecycleTest.availableFrames(bus)).isEqualTo(62);try(var one=bus.subscribe(1);var two=bus.subscribe(2)){}
            }finally{hooks=null;}
        }
    }
    @Test void realTcpDisconnectReleasesObservationAndDoesNotCancelPersistedRun()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=start(Receiver.class)){
                try(var socket=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.getWebServer().getPort())){
                    socket.setSoTimeout(3000);socket.setSoLinger(true,0);socket.getOutputStream().write(("GET /admin/runs/"+b.publicId()+"/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+ticket+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    var read=new ByteArrayOutputStream();while(read.size()<4096&&!read.toString(java.nio.charset.StandardCharsets.UTF_8).contains("event:stream_status")){int value=socket.getInputStream().read();if(value<0)break;read.write(value);}assertThat(read.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("200","event:stream_status");
                }
                f.append(b.runId(),"C_AFTER_DISCONNECT",Map.of("stillWritable",true));
                var bus=server.getBean(ChunkBus.class);PortalRunEventsLifecycleTest.await(()->{try(var one=bus.subscribe(100);var two=bus.subscribe(101)){return true;}catch(PlatformException occupied){return false;}});
                assertThat(f.db.queryForObject("select status from engine.run where id=?",String.class,b.runId())).isEqualTo("PENDING");assertThat(f.db.queryForObject("select count(*) from engine.run_event where run_id=? and type='C_AFTER_DISCONNECT'",Integer.class,b.runId())).isEqualTo(1);
            }
        }
    }
    static HttpResponse<String> send(ServletWebServerApplicationContext server,String path,String ticket,String method,List<String> cursors,String body)throws Exception{
        try(var client=HttpClient.newHttpClient()){
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+ticket);
            cursors.forEach(value->request.header("Last-Event-ID",value));
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
