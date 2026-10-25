package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.infra.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

class PortalChildEventsHttpTest {
    static final String MIME="application/vnd.agent-platform.portal-events.v1";
    static PortalChildEventsPostgresTest.Fixture fixture;
    static volatile Hooks hooks;
    static final class Hooks {
        final String stage;final CountDownLatch entered,release=new CountDownLatch(1),firstWindow=new CountDownLatch(1);final AtomicInteger exits=new AtomicInteger(),flushes=new AtomicInteger(),capacityAttempts=new AtomicInteger();
        final List<ByteArrayOutputStream> wires=new CopyOnWriteArrayList<>();String capacityFailure;
        Hooks(String stage){this(stage,2);}Hooks(String stage,int connections){this.stage=stage;entered=new CountDownLatch(connections);}
        void block(String point){if(!stage.equals(point))return;entered.countDown();boolean done=false;while(!done)try{done=release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import(cn.agentplatform.engine.EnginePortalEventsAssembly.class)
    static class Receiver {
        @Bean javax.sql.DataSource source(){return fixture.source;}
        @Bean java.time.Clock clock(){return fixture.auth.iam.clock();}
        @Bean(destroyMethod="") HttpCurrentAuthorityClient current(){return fixture.auth.engine;}
        @Bean JwtDecoder decoder(){return PortalChildEventsAuthorityFixture.decoder(fixture.auth);}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient c){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),c);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor mapped(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> maps){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),maps);}
        @Bean PlatformErrors errors(){return new PlatformErrors();}
        @Bean(destroyMethod="close") ChunkBus chunks(){return PortalChildEventsPostgresTest.chunks();}
        @Bean(destroyMethod="close") RuntimeClients clients(){return new RuntimeClients(fixture.auth.iam.config("engine"),fixture.auth.engine,"http://127.0.0.1:1",true);}
        @Bean(destroyMethod="close") RunScheduler scheduler(){return new RunScheduler(fixture.trace){@Override public void start(){}};}
        @Bean RunService service(RuntimeClients c,RunScheduler s){return new RunService(fixture.trace,c,null,null,s);}
        @Bean RunController controller(RunService s,ChunkBus c){return new RunController(s,fixture.trace,c,null);}
        @Bean FilterRegistrationBean<jakarta.servlet.Filter> probe(){
            var registration=new FilterRegistrationBean<jakarta.servlet.Filter>();registration.setAsyncSupported(true);registration.setOrder(Integer.MAX_VALUE);
            registration.setFilter((request,response,chain)->{
                var h=hooks;if(h==null){chain.doFilter(request,response);return;}
                var wrapped=new HttpServletResponseWrapper((HttpServletResponse)response){
                    public ServletOutputStream getOutputStream()throws IOException{h.block("get");var actual=super.getOutputStream();var wire=new ByteArrayOutputStream();h.wires.add(wire);return new ServletOutputStream(){int kind;
                        public boolean isReady(){return actual.isReady();}public void setWriteListener(WriteListener listener){actual.setWriteListener(listener);}
                        public void write(int value)throws IOException{h.block("write");actual.write(value);wire.write(value);}
                        public void write(byte[] bytes,int offset,int length)throws IOException{h.block("write");
                            if(length>=24&&bytes[offset]=='A'&&bytes[offset+1]=='P'&&bytes[offset+2]=='E'&&bytes[offset+3]=='V'){kind=bytes[offset+7];if(kind==3){h.capacityAttempts.incrementAndGet();if("write".equals(h.capacityFailure))throw new IOException("injected CAPACITY write");}}
                            actual.write(bytes,offset,length);wire.write(bytes,offset,length);}
                        public void flush()throws IOException{h.block("flush");if(kind==3&&"flush".equals(h.capacityFailure))throw new IOException("injected CAPACITY flush");actual.flush();if(h.flushes.incrementAndGet()>=2)h.firstWindow.countDown();}
                    };}
                };
                var wrappedRequest=new HttpServletRequestWrapper((HttpServletRequest)request){
                    public AsyncContext startAsync(ServletRequest r,ServletResponse s){var actual=super.startAsync(r,s);return new AsyncContext(){
                        public ServletRequest getRequest(){return actual.getRequest();}public ServletResponse getResponse(){return actual.getResponse();}public boolean hasOriginalRequestAndResponse(){return actual.hasOriginalRequestAndResponse();}
                        public void dispatch(){actual.dispatch();}public void dispatch(String path){actual.dispatch(path);}public void dispatch(ServletContext context,String path){actual.dispatch(context,path);}
                        public void complete(){h.block("complete");try{actual.complete();}finally{h.exits.incrementAndGet();}}
                        public void start(Runnable task){actual.start(task);}public void addListener(AsyncListener listener){actual.addListener(listener);}public void addListener(AsyncListener listener,ServletRequest r,ServletResponse s){actual.addListener(listener,r,s);}public <T extends AsyncListener>T createListener(Class<T> type)throws ServletException{return actual.createListener(type);}public void setTimeout(long timeout){actual.setTimeout(timeout);}public long getTimeout(){return actual.getTimeout();}
                    };}
                };chain.doFilter(wrappedRequest,wrapped);
            });return registration;
        }
    }
    static ServletWebServerApplicationContext start(){return start(Receiver.class);}
    static ServletWebServerApplicationContext start(Class<?> type){var app=new SpringApplication(type);app.setAdditionalProfiles("test");app.setDefaultProperties(Map.of("spring.main.banner-mode","off","spring.jmx.enabled",false,"spring.flyway.enabled",false,"springdoc.api-docs.enabled",false,"logging.level.root","ERROR"));return(ServletWebServerApplicationContext)app.run("--spring.config.location=classpath:/application.yml","--server.port=0","--management.endpoints.web.exposure.exclude=*","--management.endpoints.web.discovery.enabled=false");}
    static HttpResponse<byte[]> request(ServletWebServerApplicationContext s,String run,String ticket,String accept)throws Exception{
        try(var client=HttpClient.newHttpClient()){var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+s.getWebServer().getPort()+"/admin/runs/"+run+"/events")).timeout(Duration.ofSeconds(8)).header("Accept",accept);if(ticket!=null)b.header("Authorization","Bearer "+ticket);return client.send(b.GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    }
    @Test void actualSignedChildUsesPrivateMimeAndCommitsReceiptAndCompleteWindow()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();f.finish(b.runId());
            try(var child=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var server=start()){
                assertThat(server.getBean(org.springframework.boot.autoconfigure.web.ServerProperties.class).getMaxHttpRequestHeaderSize().toBytes()).isEqualTo(32768);
                System.out.println("E_CHILD_HEADER_MEASURE ticketChars="+child.ticket.length()+" conservativeRequestHeaderBytes="+(child.ticket.length()+2048)+" configuredMax=32768");
                var result=request(server,b.publicId(),child.ticket,MIME);
                assertThat(result.statusCode()).as("ticket length %s, HTTP body: %s",child.ticket.length(),new String(result.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);assertThat(result.headers().firstValue("Content-Type")).hasValue(MIME);
                assertThat(new String(result.body(),java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("APEV").contains("APCM","receipt","initialCeiling","provenance","rootScopes","eventIdentity");
                var packets=PortalChildEventsPostgresTest.packets(result.body());assertThat(packets.stream().filter(p->p.kind()==1).count()).isEqualTo(1);child.parent.acceptChildReceipt(packets.getFirst().metadata().path("receipt").asText());
                assertThat(packets.getLast().header().path("eventSeq").longValue()).isEqualTo(2);assertThat(packets.getLast().metadata().path("state").path("status").asText()).isEqualTo("FAILED");
            }
        }
    }
    @Test void privateAndPublicModesAreMutuallyExclusiveAndMalformedPrivateNeverFallsBack()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();f.finish(b.runId());try(var server=start()){
            String standalone=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            f.observe();assertThat(request(server,b.publicId(),standalone,MIME).statusCode()).isEqualTo(403);assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");
            var ordinary=request(server,b.publicId(),standalone,"text/event-stream");assertThat(ordinary.statusCode()).isEqualTo(200);assertThat(new String(ordinary.body(),java.nio.charset.StandardCharsets.UTF_8)).contains("event:stream_status","event:C_FIRST").doesNotContain("receipt","APEV");
            try(var child=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0)){
                f.observe();assertThat(request(server,b.publicId(),child.ticket,"text/event-stream").statusCode()).isEqualTo(403);assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");
                for(String accept:List.of(MIME+";charset=UTF-8",MIME+", text/event-stream",MIME+";q=1")){f.observe();var invalid=request(server,b.publicId(),child.ticket,accept);assertThat(invalid.statusCode()).as("Accept %s; body %s",accept,new String(invalid.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(400);assertThat(invalid.headers().firstValue("Content-Type")).hasValue("application/json");assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");}
                try(var http=HttpClient.newHttpClient()){f.observe();var duplicate=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getWebServer().getPort()+"/admin/runs/"+b.publicId()+"/events")).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+child.ticket).header("Accept",MIME).header("Accept",MIME).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(duplicate.statusCode()).isEqualTo(400);assertThat(duplicate.headers().firstValue("Content-Type")).hasValue("application/json");assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");}
                f.observe();assertThat(request(server,"r_"+"0".repeat(32),child.ticket,MIME).statusCode()).isEqualTo(403);assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");
                assertThat(request(server,b.publicId(),null,MIME).statusCode()).isEqualTo(401);
            }
        }}
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class LegacyReceiver extends Receiver {
        @Override @Bean RunService service(RuntimeClients c,RunScheduler s){return new RunService(fixture.trace,null,null,null,s);}
    }
    @Test void privateNonIamRejectsBeforeLegacyActorOrSql()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());try(var server=start(LegacyReceiver.class)){f.observe();var result=request(server,b.publicId(),ticket,MIME);assertThat(result.statusCode()).isEqualTo(403);assertThat(result.headers().firstValue("Content-Type")).hasValue("application/json");assertThat(request(server,b.publicId(),ticket,MIME+";charset=UTF-8").statusCode()).isEqualTo(400);assertThat(f.getters()).doesNotContain("payload_text","state_text","event_text");}}
    }
    @Test void privateActiveIdleHasNoPacketAndNewTerminalCarriesProof()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();try(var child=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),1);var server=start();var callers=Executors.newSingleThreadExecutor()){
            var h=new Hooks("none");hooks=h;
            var wire=callers.submit(()->requestCursor(server,b.publicId(),child.ticket,1));
            // Wait for the first private WINDOW through actual SQL, then let several idle polls
            // pass before a real writer commits terminal state and events.
            assertThat(h.firstWindow.await(5,TimeUnit.SECONDS)).isTrue();Thread.sleep(180);f.finish(b.runId());var packets=PortalChildEventsPostgresTest.packets(wire.get(8,TimeUnit.SECONDS).body());
            assertThat(packets.getFirst().kind()).isEqualTo(1);assertThat(packets.stream().filter(p->p.kind()==1).count()).isEqualTo(1);assertThat(packets.getLast().metadata().path("state").path("status").asText()).isEqualTo("FAILED");assertThat(packets.getLast().header().path("eventSeq").longValue()).isEqualTo(2);
            for(var p:packets.subList(2,packets.size()))assertThat(p.metadata().path("hasEvent").booleanValue()||p.metadata().path("state").path("status").asText().equals("FAILED")).isTrue();
            try(var empty=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),2)){var terminal=PortalChildEventsPostgresTest.packets(requestCursor(server,b.publicId(),empty.ticket,2).body()).getLast();assertThat(terminal.metadata().path("hasEvent").booleanValue()).isFalse();assertThat(terminal.metadata().path("state").path("status").asText()).isEqualTo("FAILED");assertThat(terminal.payload()).isEmpty();}
        }finally{hooks=null;}}
    }
    @Test void privatePayloadCapacityAfterHelloCommitsOneCapacityThenCloses()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();
            try(var child=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),1);var server=start();var callers=Executors.newSingleThreadExecutor()){
                var h=new Hooks("none");hooks=h;var task=callers.submit(()->requestCursor(server,b.publicId(),child.ticket,1));assertThat(h.firstWindow.await(5,TimeUnit.SECONDS)).isTrue();f.append(b.runId(),"LARGE_PAYLOAD","x".repeat(262144));
                var result=task.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);PortalChildEventsPostgresTest.saveVector("actual-http-event-limit",result.body());var packets=PortalChildEventsPostgresTest.packets(result.body());assertThat(packets).hasSize(3);assertThat(packets.getFirst().kind()).isEqualTo(1);assertThat(packets.getLast().kind()).isEqualTo(3);assertThat(packets.getLast().metadata().toString()).isEqualTo("{\"code\":\"EVENT_LIMIT\"}");assertThat(packets.getLast().payload()).isEmpty();
            }finally{hooks=null;}
        }
    }
    static HttpResponse<byte[]> requestCursor(ServletWebServerApplicationContext s,String run,String ticket,long cursor)throws Exception{try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+s.getWebServer().getPort()+"/admin/runs/"+run+"/events?Last-Event-ID="+cursor)).timeout(Duration.ofSeconds(8)).header("Accept",MIME).header("Authorization","Bearer "+ticket).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}}
    @Test void privateBlockingGetWriteFlushAndCompleteRetainRealReaderFrameAndSlotUntilExit()throws Exception{
        for(String stage:List.of("get","write","flush","complete"))try(var f=new PortalChildEventsPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(2)){
            fixture=f;var b=f.seed();f.finish(b.runId());var h=new Hooks(stage);hooks=h;
            try(var a=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var c=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var probe=f.auth.open(b.owner(),b.publicId(),b.snapshotSha(),0);var server=start()){
                var first=callers.submit(()->request(server,b.publicId(),a.ticket,MIME));var second=callers.submit(()->request(server,b.publicId(),c.ticket,MIME));assertThat(h.entered.await(6,TimeUnit.SECONDS)).as(stage).isTrue();var actual=server.getBean(RunEventReader.class);var bus=server.getBean(ChunkBus.class);
                server.getBean(PortalRunEventsService.class).close();assertThatThrownBy(()->actual.readNext(probe,0)).as(stage).isInstanceOf(RunEventReader.CapacityFailure.class);assertThat(PortalChildEventsPostgresTest.frames(bus)).isEqualTo(60);assertThatThrownBy(()->bus.subscribe(999)).isInstanceOf(PlatformException.class);assertThat(h.exits).hasValue(0);
                h.release.countDown();first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(h.exits.get()!=2&&System.nanoTime()<until)Thread.sleep(10);assertThat(h.exits).hasValue(2);assertThat(PortalChildEventsPostgresTest.frames(bus)).isEqualTo(62);
                try(var one=actual.readNext(probe,0).orElseThrow();var two=actual.readNext(probe,0).orElseThrow();var x=bus.subscribe(998);var y=bus.subscribe(999)){}
            }finally{h.release.countDown();hooks=null;}
        }
    }
    static ThreadPoolExecutor executor(PortalRunEventsService service)throws Exception{var field=PortalRunEventsService.class.getDeclaredField("writers");field.setAccessible(true);return(ThreadPoolExecutor)field.get(service);}
    static void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("BOUNDED_WAIT");Thread.sleep(10);}}
    @Test void privateFreshQueuedCapacityBeforeHelloReturnsBoundedJsonUntilCompleteExits()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture();var callers=Executors.newSingleThreadExecutor()){
            fixture=f;var b=f.seed();var h=new Hooks("complete",1);hooks=h;var workers=new CountDownLatch(2);var release=new CountDownLatch(1);
            try(var child=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),1);var probe=f.auth.open(b.owner(),b.publicId(),b.snapshotSha(),0);var server=start()){
                try{
                var pool=executor(server.getBean(PortalRunEventsService.class));
                for(int i=0;i<2;i++)pool.execute(()->{workers.countDown();boolean done=false;while(!done)try{done=release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}});
                assertThat(workers.await(3,TimeUnit.SECONDS)).isTrue();var future=callers.submit(()->requestCursor(server,b.publicId(),child.ticket,1));await(()->pool.getQueue().size()==1);assertThat(h.wires).isEmpty();
                // Opening preflight succeeded and released its RR; the queued writer must
                // independently see the real writer's newly oversized payload on fresh RR.
                f.append(b.runId(),"LARGE_FRESH","x".repeat(262144));release.countDown();assertThat(h.entered.await(6,TimeUnit.SECONDS)).isTrue();
                var bus=server.getBean(ChunkBus.class);var reader=server.getBean(RunEventReader.class);assertThat(PortalChildEventsPostgresTest.frames(bus)).isEqualTo(61);assertThat(h.exits).hasValue(0);
                try(var one=reader.readNext(probe,0).orElseThrow();var two=reader.readNext(probe,0).orElseThrow();var slot=bus.subscribe(100)){assertThatThrownBy(()->bus.subscribe(101)).isInstanceOf(PlatformException.class);}
                var expected=Json.write(new ApiResponse<>("EVENT_LIMIT","事件观察不可用",null,null));
                // The fixed small JSON has physically written/flushed before complete;
                // no packet, receipt, state or watermark may be exposed on this branch.
                assertThat(h.wires).hasSize(1);assertThat(h.wires.getFirst().toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expected);
                h.release.countDown();var result=future.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(413);assertThat(result.headers().firstValue("Content-Type")).hasValue("application/json");assertThat(new String(result.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expected).doesNotContain("APEV","APCM","receipt","watermark","state");
                await(()->h.exits.get()==1);await(()->{try{return PortalChildEventsPostgresTest.frames(bus)==62;}catch(Exception x){throw new AssertionError(x);}});try(var a=bus.subscribe(100);var c=bus.subscribe(101)){}
                }finally{release.countDown();h.release.countDown();}
            }finally{release.countDown();h.release.countDown();hooks=null;}
        }
    }
    @Test void privateCapacityWriteFailureCompletesBeforeRealBorrowRelease()throws Exception{capacityFailureBeforeRelease("write");}
    @Test void privateCapacityFlushFailureCompletesBeforeRealBorrowRelease()throws Exception{capacityFailureBeforeRelease("flush");}
    static void capacityFailureBeforeRelease(String exit)throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(2)){
            fixture=f;var b=f.seed();f.append(b.runId(),"LARGE_FOR_FAILURE","x".repeat(262144));var h=new Hooks("complete");h.capacityFailure=exit;hooks=h;
            try(var a=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var c=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var probe=f.auth.open(b.owner(),b.publicId(),b.snapshotSha(),0);var server=start()){
                try{
                var actual=server.getBean(RunEventReader.class);var service=server.getBean(PortalRunEventsService.class);var fault=catchThrowable(()->actual.readNext(probe,1));assertThat(fault).isInstanceOf(RunEventReader.CapacityFailure.class);var faults=new AtomicInteger();var timed=org.mockito.Mockito.spy(actual);
                // Negative fault injection on an actual minted borrow, not BUFFER_LIMIT
                // pressure or a capability factory. All real RR/consume/guards/cleanup run.
                // Only WINDOW encoding is cut before its prefix; HELLO encodes normally.
                org.mockito.Mockito.doAnswer(call->{var result=(RunEventReader.WindowEmission)call.callRealMethod();if(!Thread.currentThread().getName().equals("portal-events-writer"))return result;
                    return org.mockito.Mockito.mock(RunEventReader.WindowEmission.class,invoke->{var args=invoke.getArguments().clone();
                        if(invoke.getMethod().getName().equals("consume")){
                            @SuppressWarnings("unchecked") var action=(java.util.function.Consumer<RunEventReader.WindowBorrow>)args[0];
                            args[0]=(java.util.function.Consumer<RunEventReader.WindowBorrow>)view->{var wrapped=org.mockito.Mockito.mock(RunEventReader.WindowBorrow.class,encoding->{if(encoding.getMethod().getName().equals("writePrivatePacket")&&Boolean.FALSE.equals(encoding.getArgument(2))){faults.incrementAndGet();throw fault;}try{return encoding.getMethod().invoke(view,encoding.getArguments());}catch(java.lang.reflect.InvocationTargetException x){throw x.getCause();}});action.accept(wrapped);};
                        }try{return invoke.getMethod().invoke(result,args);}catch(java.lang.reflect.InvocationTargetException x){throw x.getCause();}
                    });
                }).when(timed).readChildWindow(org.mockito.ArgumentMatchers.any(PortalEventsResponse.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.same(f.auth.engine));
                var field=PortalRunEventsService.class.getDeclaredField("reader");field.setAccessible(true);field.set(service,timed);
                var first=callers.submit(()->request(server,b.publicId(),a.ticket,MIME));var second=callers.submit(()->request(server,b.publicId(),c.ticket,MIME));assertThat(h.entered.await(7,TimeUnit.SECONDS)).as(exit).isTrue();assertThat(faults).hasValue(2);assertThat(h.capacityAttempts).hasValue(2);assertThat(h.exits).hasValue(0);
                var bus=server.getBean(ChunkBus.class);
                assertThat(catchThrowable(()->{try(var unexpected=actual.readNext(probe,0).orElseThrow()) {}})).as("both B permits until physical complete exit: %s",exit).isInstanceOf(RunEventReader.CapacityFailure.class);
                assertThat(PortalChildEventsPostgresTest.frames(bus)).isEqualTo(60);assertThatThrownBy(()->bus.subscribe(999)).isInstanceOf(PlatformException.class);
                for(var wire:h.wires){var packets=PortalChildEventsPostgresTest.packets(wire.toByteArray());assertThat(packets.stream().map(PortalChildEventsPostgresTest.Packet::kind).toList()).containsExactlyElementsOf(exit.equals("write")?List.of(1):List.of(1,3));for(var p:packets){assertThat(p.header().path("afterSeq").longValue()).isZero();assertThat(p.header().path("eventSeq").isNull()).isTrue();}if(exit.equals("flush"))assertThat(packets.getLast().metadata().toString()).isEqualTo("{\"code\":\"EVENT_LIMIT\"}");}
                assertThat(h.wires).hasSize(2);h.release.countDown();first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);await(()->h.exits.get()==2);await(()->{try{return PortalChildEventsPostgresTest.frames(bus)==62;}catch(Exception x){throw new AssertionError(x);}});assertThat(faults).hasValue(2);assertThat(h.capacityAttempts).hasValue(2);
                // A long-lived standalone probe may renew between two B captures. Use a new
                // normal holder for the independent post-release admission recovery check.
                try(var fresh=f.auth.open(b.owner(),b.publicId(),b.snapshotSha(),0);var one=actual.readNext(fresh,0).orElseThrow();var two=actual.readNext(fresh,0).orElseThrow();var x=bus.subscribe(998);var y=bus.subscribe(999)){}
                }finally{h.release.countDown();}
            }finally{h.release.countDown();hooks=null;}
        }
    }
    @Test void privateActualResourceHeaderLimitRejectsOversizeAtContainerBoundary()throws Exception{
        try(var f=new PortalChildEventsPostgresTest.Fixture()){fixture=f;var b=f.seed();try(var server=start();var socket=new java.net.Socket(java.net.InetAddress.getLoopbackAddress(),server.getWebServer().getPort())){
            assertThat(server.getBean(org.springframework.boot.autoconfigure.web.ServerProperties.class).getMaxHttpRequestHeaderSize().toBytes()).isEqualTo(32768);socket.setSoTimeout(4000);socket.getOutputStream().write(("GET /admin/runs/"+b.publicId()+"/events HTTP/1.1\r\nHost: localhost\r\nX-Bounded-Probe: "+"x".repeat(33000)+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));String body=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertThat(body).startsWith("HTTP/1.1 400").doesNotContain("APEV","RUN_AUTHORITY_DENIED");
        }}
    }
}
