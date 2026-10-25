package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.net.ssl.*;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.HandlerMapping;
import static org.junit.jupiter.api.Assertions.*;
import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
/** Real embedded Tomcat HTTP/1.1 NIO and same issuer/receiver/private TLS chain. */
public class PortalRunEventsSseLifecycleTest {
    @Test void originalRequestBootstrapAndContinuousCursorTerminal() throws Exception { PortalRunEventsSseTest.child("sequence"); }
    @Test void privateEofDoesNotInventTerminal() throws Exception { PortalRunEventsSseTest.child("eof"); }
    @Test void realBackpressureAbortRetainsCursorAndActuallyExits() throws Exception { PortalRunEventsSseTest.child("backpressure"); }
    @Test void resetPrecedesStatusAndDoesNotAdvanceCursor() throws Exception { PortalRunEventsSseTest.child("reset"); }
    @Test void allSpacePgTypeSurvivesActualServletOutput() throws Exception { PortalRunEventsSseTest.child("spaces"); }
    @Test void generationAtGetterAndEachWriteBoundaryNeverPublishesOldTail() throws Exception { PortalRunEventsSseTest.child("generation"); }
    @Test void revokeAndPartialUnknownNeverAdvanceCursor() throws Exception { PortalRunEventsSseTest.child("revoke-partial"); }
    @Test void firstErrorAndLateFirstWindowCannotReviveRegistration() throws Exception { PortalRunEventsSseTest.child("bootstrap-failure"); }
    @Test void originalIdleDeadlineSurvivesSpuriousWakeupsAndStoppedReader() throws Exception { PortalRunEventsSseTest.child("idle"); }
    @Test void twoHeavyHelpersKeepWholeOwnersAndThirdAdmissionClosed() throws Exception { PortalRunEventsSseTest.child("heavy-retention"); }
    @Test void stalledCompletionKeepsOwnersBeforeOldHttpCleanup() throws Exception { PortalRunEventsSseTest.child("completion-retention"); }
    @Test void abortIsOrderedAfterActualReadyWriteAndRetainsFeesWhileStalled() throws Exception { PortalRunEventsSseTest.child("abort-order"); }
    @Test void lateAbortCannotTouchReusedKeepaliveRequest() throws Exception { PortalRunEventsSseTest.child("keepalive"); }
    @Test void realNotReadyNotificationResumesTheSameFrame() throws Exception { PortalRunEventsSseTest.child("backpressure-resume"); }
    @Test void exactLongMaxCursorAndHighPrecisionJsonReachRealServletBytes() throws Exception { PortalRunEventsSseTest.child("long-and-precision"); }
    @Test void inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner() throws Exception { PortalRunEventsSseTest.child("light-shell"); }
    @Test void actualPendingFlushRequiresDrainAndFreshGenerationBeforeCursor() throws Exception { PortalRunEventsSseTest.child("pending-flush"); }
    static byte[] wire(SequencePeer peer,byte[] hello,String child,ObjectNode original,byte[] payload,String mode)throws Exception {
        int count=mode.equals("backpressure")||mode.equals("pending-flush")?20:mode.equals("sequence")?2:1;
        var parts=new ArrayList<byte[]>();parts.add(hello);
        for(int i=1;i<=count;i++) {
            var m=original.deepCopy();
            ((ObjectNode)m.get("window")).put("afterSeq",i-1).put("watermark",count);
            ((ObjectNode)m.get("state")).put("event_seq",count);
            ((ObjectNode)m.get("eventIdentity")).put("seq",i);
            if(mode.equals("long-id")) { ((ObjectNode)m.get("eventIdentity")).put("seq",Long.MAX_VALUE);((ObjectNode)m.get("window")).put("watermark",Long.MAX_VALUE);((ObjectNode)m.get("state")).put("event_seq",Long.MAX_VALUE); }
            if(mode.equals("sequence")) {
                ((ObjectNode)m.get("window")).put("status","SUCCEEDED");((ObjectNode)m.get("state")).put("status","SUCCEEDED");
            }
            if(mode.equals("reset")) { ((ObjectNode)m.get("window")).put("oldest",3).put("watermark",3);((ObjectNode)m.get("state")).put("event_seq",3); }
            ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));
            PortalRunEventsSseTest.payload(m,payload,mode.equals("sequence")?" "+"界".repeat(31):mode.equals("spaces")?" ".repeat(32):"EVENT");
            parts.add(peer.packet(2,JSON.writeValueAsBytes(m),payload,child,m));
        }
        if(mode.equals("sequence")) {
            var m=original.deepCopy();
            ((ObjectNode)m.get("window")).put("afterSeq",count).put("watermark",count).put("status","SUCCEEDED");
            ((ObjectNode)m.get("state")).put("event_seq",count).put("status","SUCCEEDED");
            m.put("hasEvent",false);m.putNull("eventScope");m.putNull("eventIdentity");
            ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));rehash(m);
            parts.add(peer.packet(2,JSON.writeValueAsBytes(m),EMPTY,child,m));
        }
        return PortalEventsPacketDecoderTest.join(parts.toArray(byte[][]::new));
    }
    static final class Server implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();
        final AtomicReference<PortalRunEventsSse.Handle> handle=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicReference<PortalRunEventsHttp.Session> session=new AtomicReference<>();
        final PortalEventsTls tls;
        final Path run;
        final List<String> trace=Collections.synchronizedList(new ArrayList<>());
        Server(Path run,Fixture f,PortalEventsTls tls,BiConsumer<String,Long> hook)throws Exception {
            this.tls=tls;
            this.run=run;
            Path base=Files.createDirectories(run.resolve("tomcat"));tomcat.setBaseDir(base.toString());tomcat.setPort(0);
            var connector=tomcat.getConnector();connector.setProperty("address","127.0.0.1");connector.setProperty("socket.txBufSize","1024");connector.setProperty("socket.appWriteBufSize","8192");
            connector.setProperty("maxThreads","8");connector.setProperty("minSpareThreads","2");
            var context=tomcat.addContext("",base.toString());context.getPipeline().addValve(PortalRunEventsSse.contextValve());
            var adapter=new PortalRunEventsSse((phase,value)->{trace.add(phase+"="+value);if(hook!=null)hook.accept(phase,value);});
            var servlet=Tomcat.addServlet(context,"events",new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
                    try {
                        var s=new PortalRunEventsHttp(tls).begin(f.observed,f.source,f.run,0);session.set(s);handle.set(adapter.start(request,response,s));
                    } catch(Throwable t) { failure.set(t); }
                }
            });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/admin/runs/*","events");tomcat.start();
            var ping=Tomcat.addServlet(context,"ping",new HttpServlet() { @Override protected void doGet(HttpServletRequest r,HttpServletResponse s)throws IOException { s.setContentLength(4);s.getOutputStream().write(utf("pong")); } });
            context.addServletMappingDecoded("/ping","ping");
        }
        Socket request(String run)throws Exception {
            return request(run,false);
        }
        Socket request(String run,boolean keepAlive)throws Exception {
            var socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",tomcat.getConnector().getLocalPort()));socket.setSoTimeout(12000);
            socket.getOutputStream().write(utf("GET /admin/runs/"+run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: "+(keepAlive?"keep-alive":"close")+"\r\n\r\n"));socket.getOutputStream().flush();return socket;
        }
        boolean waitTrace(String prefix,long millis)throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end) { synchronized(trace) { if(trace.stream().anyMatch(x->x.startsWith(prefix)))return true; }Thread.sleep(10); }return false;
        }
        PortalRunEventsSse.Handle handle()throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(handle.get()==null&&System.nanoTime()<end) { if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(10); }assertNotNull(handle.get());return handle.get(); }
        public void close()throws Exception {
            Files.write(run.resolve("sse-trace-finally.txt"),trace,StandardCharsets.UTF_8);
            var h=handle.get();if(h!=null) { h.close();assertTrue(h.awaitClosed(Duration.ofSeconds(4))); }
            tls.close();tomcat.stop();tomcat.destroy();
        }
    }
    static void scenario(String mode,Path run)throws Exception {
        switch(mode) {
            case "generation","revoke-partial" -> { freshness(mode,run);return; }
            case "bootstrap-failure" -> { bootstrap(run);return; }
            case "idle" -> { idle(run);return; }
            case "keepalive" -> { keepalive(run);return; }
            case "long-and-precision" -> { scenario("long-id",Files.createDirectories(run.resolve("long")));scenario("precision",Files.createDirectories(run.resolve("precision")));return; }
            case "light-shell" -> { lightShell(run);return; }
            case "pending-flush" -> { pendingFlush(run);return; }
            case "heavy-retention","completion-retention","abort-order" -> { retentionScenario(mode,run);return; }
        }
        byte[] raw=mode.startsWith("backpressure")?utf("\""+"x".repeat(262142)+"\""):mode.equals("precision")?utf(" { \"n\" : -0.00, \"value\": 1.23456789012345678901234567890e+20 } "):utf("null");
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},mode,raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
            if(mode.startsWith("backpressure")) {
                assertTrue(server.waitTrace("ready-false",8000)||server.waitTrace("flush-wait",1000),"real stopped-reader readiness");
                var handle=server.handle();long prior=handle.after();long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>83886080L);
                if(mode.equals("backpressure-resume")) {
                    socket.setReceiveBufferSize(262144);byte[] body=socket.getInputStream().readAllBytes();assertTrue(body.length>262144);
                    assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(1,handle.after());assertTrue(server.trace.stream().filter(x->x.startsWith("callback-entry")).count()>1);
                } else { handle.close();assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(prior,handle.after()); }
            } else {
                byte[] body=socket.getInputStream().readAllBytes();var handle=server.handle();assertTrue(handle.awaitClosed(Duration.ofSeconds(4)));
                String text=new String(body,StandardCharsets.UTF_8);
                if(mode.equals("reset")) { assertTrue(text.contains("CURSOR_EXPIRED"));assertFalse(text.contains("stream_status"));assertFalse(text.contains("id: 1"));assertEquals(0,handle.after()); }
                else { assertTrue(text.contains("event: stream_status"));
                assertTrue(text.contains(mode.equals("precision")?"data: {\"n\":-0.00,\"value\":1.23456789012345678901234567890e+20}":"data: null"));
                assertEquals(mode.equals("long-id")?Long.MAX_VALUE:mode.equals("sequence")?2:1,handle.after());assertEquals(mode.equals("sequence"),handle.terminal());
                }
                assertTrue(server.trace.contains("setter-return=0"));assertTrue(server.trace.contains("callback-entry=1"));
                assertTrue(server.trace.indexOf("setter-return=0")<server.trace.indexOf("callback-entry=1"));
                if(mode.equals("sequence")) { assertTrue(text.contains("id: 2"));assertTrue(text.contains("event:  界")); }
                if(mode.equals("spaces"))assertTrue(text.contains("event: "+" ".repeat(32)+"\n"));
            }
            assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());assertNull(server.failure.get());
            Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
            Files.writeString(run.resolve("sse.properties"),"after="+server.handle().after()+"\nterminal="+server.handle().terminal()+"\nactualWorkerExit=true\nownerBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
        }
    }
    static String line(InputStream in)throws IOException {
        var out=new ByteArrayOutputStream();int previous=-1,b;
        while((b=in.read())!=-1) { if(previous=='\r'&&b=='\n')return out.toString(StandardCharsets.US_ASCII).substring(0,out.size()-1);out.write(b);previous=b;if(out.size()>8192)throw new IOException("bounded response line"); }
        throw new EOFException();
    }
    static byte[] chunked(Socket socket)throws IOException {
        var in=socket.getInputStream();assertTrue(line(in).startsWith("HTTP/1.1 200"));
        String h;boolean chunked=false;while(!(h=line(in)).isEmpty())if(h.equalsIgnoreCase("Transfer-Encoding: chunked"))chunked=true;
        assertTrue(chunked);var body=new ByteArrayOutputStream();
        while(true) { int n=Integer.parseInt(line(in),16);if(n==0) { assertEquals("",line(in));return body.toByteArray(); }if(n>1048576||body.size()+n>2097152)throw new IOException("bounded SSE response");body.write(in.readNBytes(n));assertEquals("",line(in)); }
    }
    static void keepalive(Path run)throws Exception {
        var atExit=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{ if(phase.equals("worker-body-exit")) { atExit.countDown();pause(release); } });var socket=server.request(f.run,true)) {
            try {
                assertTrue(atExit.await(5,TimeUnit.SECONDS));assertTrue(new String(chunked(socket),StandardCharsets.UTF_8).contains("data: null"));
                assertTrue(server.waitTrace("token-disarmed",5000));var h=server.handle();assertFalse(h.awaitClosed(Duration.ZERO));assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>0);
                socket.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));socket.getOutputStream().flush();h.close();
                String next=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(next.startsWith("HTTP/1.1 200"));assertTrue(next.endsWith("pong"));
                release.countDown();assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("keepalive.properties"),"sameSocket=true\nlateAbortAfterRealDisarm=true\nnextRequestPong=true\nworkerHeldFeesUntilActualExit=true\n");
            } finally { release.countDown(); }
        }
    }
    static void pause(CountDownLatch release) {
        try { if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("owned pause deadline"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
    }
    static void freshness(String mode,Path run)throws Exception {
        var points=mode.equals("generation")?List.of("before-getter","after-getter","before-ready","before-write","after-write","before-flush"):List.of("revoke","partial");
        for(String point:points) {
            Path vector=Files.createDirectory(run.resolve(point));var changed=new AtomicBoolean();var frames=new AtomicInteger();
            byte[] raw=utf("\""+"x".repeat(240000)+"\"");
            try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"eof",raw);var tls=peer.tls()) {
                BiConsumer<String,Long> hook=(phase,value)->{
                    if(phase.equals("frame-complete"))frames.incrementAndGet();
                    boolean trigger=point.contains("getter")?phase.equals(point):frames.get()==1&&(point.equals("revoke")||point.equals("partial")?phase.equals("after-write"):phase.equals(point));
                    if(trigger&&changed.compareAndSet(false,true)) {
                        if(point.equals("partial"))throw new UncheckedIOException(new IOException("partial-unknown sentinel"));
                        var g=currentGateway.get();
                        if(point.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(g)); }
                        else { var previous=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(previous)); }
                    }
                };
                try(var server=new Server(vector,f,tls,hook);var socket=server.request(f.run)) {
                    try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
                    assertTrue(changed.get(),point);
                    var handle=server.handle.get();
                    if(handle!=null) { assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(0,handle.after()); }
                    else { assertNotNull(server.failure.get());assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5))); }
                    assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
                    Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
                }
            }
        }
    }
    static void bootstrap(Path run)throws Exception {
        for(String mode:List.of("bad-receipt","late")) {
            Path vector=Files.createDirectory(run.resolve(mode));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},mode,utf("null"));var tls=peer.tls();var server=new Server(vector,f,tls,(phase,value)->{if(mode.equals("late")&&phase.equals("window")) { entered.countDown();pause(release); }});var socket=server.request(f.run)) {
                try {
                    if(mode.equals("late")) { assertTrue(entered.await(5,TimeUnit.SECONDS));server.session.get().close();assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>83886080L);release.countDown(); }
                    try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
                    assertNull(server.handle.get());assertNotNull(server.failure.get());
                    assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5)));assertFalse(server.trace.stream().anyMatch(x->x.startsWith("setter-return")));
                    assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
                } finally { release.countDown(); }
            }
        }
    }
    static void idle(Path run)throws Exception {
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
            var h=server.handle();assertTrue(server.waitTrace("ready-idle",5000));long start=System.nanoTime();
            for(int i=0;i<20;i++) { h.wakeForTest();Thread.sleep(30); }
            assertTrue(h.awaitClosed(Duration.ofSeconds(20)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue(elapsed>=13000&&elapsed<19000,"actual original 15s idle, no peer timeout or reset by wake");assertFalse(h.terminal());
            Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);Files.writeString(run.resolve("idle.properties"),"spuriousWakes=20\nelapsedMs="+elapsed+"\ncontainerAsyncTimeout=0\n");
        }
    }
    static void lightShell(Path run)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var frames=new AtomicInteger();var hit=new AtomicBoolean();
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{
            if(phase.equals("frame-complete"))frames.incrementAndGet();
            if(phase.equals("ready-idle")&&frames.get()==2&&hit.compareAndSet(false,true)) { entered.countDown();pause(release); }
        });var socket=server.request(f.run)) {
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(PortalEventsPacketBudget.SHARED.reservedBytes()>=83886080L&&System.nanoTime()<end)Thread.sleep(10);
                long scan=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(scan>=67108864L&&scan<83886080L,"old C1 gone, next actual F1 read charged");
                server.session.get().close();assertFalse(server.session.get().awaitClosed(Duration.ZERO));
                assertEquals(6169798L,PortalEventsPacketBudget.SHARED.reservedBytes(),"original HTTP/private/SSE/Owner retained before completion");
                release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("light-shell.properties"),"nextF1Bytes="+scan+"\noldMaterialExited=true\nouterCallbackStillPaused=true\nretainedBeforeCompletion=6169798\nfinalBytes=0\n");
            } finally { release.countDown(); }
        }
    }
    static void pendingFlush(Path run)throws Exception {
        for(String outcome:List.of("drain","generation","revoke")) {
            Path vector=Files.createDirectory(run.resolve(outcome));byte[] raw=utf("\""+"x".repeat(8000)+"\"");
            try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"pending-flush",raw);var tls=peer.tls();var server=new Server(vector,f,tls,null);var socket=server.request(f.run)) {
                assertTrue(server.waitTrace("flush-wait",8000),"actual nonblocking flush left bytes pending");
                var h=server.handle();long before=h.after();assertTrue(before<20);
                if(outcome.equals("generation")) { var g=currentGateway.get();var old=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(old)); }
                if(outcome.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(currentGateway.get())); }
                assertEquals(before,h.after());socket.setReceiveBufferSize(262144);
                try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
                assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(outcome.equals("drain")?20:before,h.after());
                Files.writeString(vector.resolve("pending-flush.properties"),"outcome="+outcome+"\nactualFlushWait=true\npublicAfterWhilePending="+before+"\npublicAfterExit="+h.after()+"\n");
                Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
            }
        }
    }
    static void retentionScenario(String mode,Path run)throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var hitA=new AtomicBoolean();var hitB=new AtomicBoolean();
        String trigger=mode.equals("heavy-retention")?"frame-complete":mode.equals("completion-retention")?"completion-entry":"before-write";
        BiConsumer<String,Long> a=(phase,value)->{if(phase.equals(trigger)&&hitA.compareAndSet(false,true)) { entered.countDown();pause(release); }};
        BiConsumer<String,Long> b=(phase,value)->{if(phase.equals(trigger)&&hitB.compareAndSet(false,true)) { entered.countDown();pause(release); }};
        try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var ta=pa.tls();var tb=pb.tls();var sa=new Server(run.resolve("a"),f,ta,a);var sb=new Server(run.resolve("b"),f,tb,b);var ca=sa.request(f.run);var cb=sb.request(f.run)) {
            try {
                assertTrue(entered.await(7,TimeUnit.SECONDS));
                long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertEquals(2,PortalEventsPacketBudget.SHARED.owners());
                assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
                if(mode.equals("heavy-retention")) { sa.session.get().close();sb.session.get().close();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.session.get().awaitClosed(Duration.ZERO)); }
                if(mode.equals("completion-retention")) {
                    sa.session.get().close();sb.session.get().close();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.handle().awaitClosed(Duration.ofMillis(40)));
                }
                if(mode.equals("abort-order")) {
                    var abortA=Thread.ofPlatform().start(sa.handle()::close);var abortB=Thread.ofPlatform().start(sb.handle()::close);
                    Thread.sleep(100);assertTrue(abortA.isAlive());assertTrue(abortB.isAlive());assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());
                    release.countDown();abortA.join(5000);abortB.join(5000);assertFalse(abortA.isAlive());assertFalse(abortB.isAlive());
                }
                release.countDown();
                assertTrue(sa.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(sb.handle().awaitClosed(Duration.ofSeconds(6)));
                assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("retention.properties"),"mode="+mode+"\nheldBytes="+held+"\nthirdDenied=true\nactualWorkersExited=true\nfinalBytes=0\n");
                Files.write(run.resolve("a-trace.txt"),sa.trace,StandardCharsets.UTF_8);Files.write(run.resolve("b-trace.txt"),sb.trace,StandardCharsets.UTF_8);
            } finally { release.countDown(); }
        }
    }
    static final class SequencePeer implements AutoCloseable {
        final Fixture f;
        final SSLServerSocket listener;
        final Thread worker;
        final Path run;
        volatile SSLSocket socket;
        volatile Throwable failure;
        int connections; long ordinal;
        String receipt;
        PortalEventsResponse engineResponse;
        SequencePeer(Fixture f, Path run, Consumer<ObjectNode> mutation, String mode, byte[] payload) throws Exception {
            this.f = f;
            this.run = run;
            var c = SSLContext.getInstance("TLSv1.3", "SunJSSE");
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(PortalRunEventsHttpTest.fixtures(), "f2a-test-only".toCharArray());
            var key = Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
            c.init(new KeyManager[] {
                new PortalEventsTlsTest.FixedKey(key, "normal", new Properties())
            }, null, null);
            listener = (SSLServerSocket)c.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
                127, 0, 0, 1
            }));
            listener.setEnabledProtocols(new String[] {
                "TLSv1.3"
            });
            listener.setEnabledCipherSuites(new String[] {
                PortalEventsTls.CIPHER
            });
            listener.setSoTimeout(5000);
            worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    try (var q = (SSLSocket)listener.accept()) {
                        socket = q;
                        connections++;
                        q.setSoTimeout(5000);
                        q.startHandshake();
                        head(q);
                        q.getOutputStream().write(f.q());
                        q.getOutputStream().flush();
                    }
                    try (var e = (SSLSocket)listener.accept()) {
                        socket = e;
                        connections++;
                        e.setSoTimeout(mode.equals("idle")?25000:10000);
                        e.startHandshake();
                        String h = head(e);
                        String bearer = h.substring(h.indexOf("Authorization: Bearer ") + 22).split("\r\n", 2)[0];
                        // Real mapped receiver frame. Public bind(currentOnly) cannot supply this initial provenance.
                        var previousSecurity = SecurityContextHolder.getContext();
                        var previousRequest = RequestContextHolder.getRequestAttributes();
                        var request = new MockHttpServletRequest("GET", "/admin/runs/" + f.run + "/events");
                        var response = new MockHttpServletResponse();
                        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/runs/{id}/events");
                        var handler = new org.springframework.web.method.HandlerMethod(new PortalEventsMaterialAuthorityTest(), PortalEventsMaterialAuthorityTest.class.getMethod("handler"));
                        var gate = new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
                        try (var decoder = new TrustedJwtDecoder(f.iam.config("engine"), f.iam.clock(), true)) {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(new JwtAuthenticationToken(decoder.decode(bearer), List.of()));
                            SecurityContextHolder.setContext(context);
                            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                            assertTrue(gate.preHandle(request, response, handler));
                            var binding = RunObservationSelectors.events(f.run, f.accepted);
                            var retained = AuthorityContext.requireInitialFor(AuthorityContext.current().actor(), binding);
                            engineResponse = f.engine.openPortalEventsChild(retained, 0, () -> {
                            });
                            receipt = f.engine.portalEventsChildReceipt(engineResponse);
                            var capture = engineResponse.currentCapture();
                            String child = capture.responseId();
                            var model = f.original.deepCopy();
                            mutation.accept(model);
                            byte[] m = JSON.writeValueAsBytes(model), p = payload == null ? asset().payload() : payload;
                            String signed = mode.equals("bad-receipt") ? receipt.substring(0, receipt.lastIndexOf('.')+1) + (receipt.charAt(receipt.lastIndexOf('.')+1)=='a'?"b":"a") + receipt.substring(receipt.lastIndexOf('.')+2) : receipt;
                            byte[] hello = JSON.writeValueAsBytes(Map.of("receipt", signed));
                            String helloChild = mode.equals("wrong-child") ? "foreign-child" : child;
                            byte[] first=packet(1, hello, EMPTY, helloChild, model);
                            byte[] wire=wire(this,first,child,model,p,mode);
                            e.getOutputStream().write(mode.equals("idle")?PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: "+(wire.length+1)+"\r\n\r\n"),wire):response(wire, MIME));
                            e.getOutputStream().flush();
                            try {
                                e.getInputStream().read();
                            }
                            catch (SocketException expected) {
                            }
                        }
                        finally {
                            gate.afterCompletion(request, response, handler, null);
                            RequestContextHolder.setRequestAttributes(previousRequest);
                            SecurityContextHolder.setContext(previousSecurity);
                        }
                    }
                }
                catch (SocketException expected) {
                    if (engineResponse == null) failure = expected;
                }
                catch (Throwable t) {
                    failure = t;
                }
            });
        }
        byte[] packet(int kind, byte[] m, byte[] p, String child, JsonNode model) throws Exception {
            var h = JSON.createObjectNode();
            h.put("childResponseId", child);
            h.put("runId", f.run);
            h.put("resourceSha256", f.accepted);
            h.put("initialCursor", 0);
            h.put("afterSeq", model.has("window")?model.get("window").get("afterSeq").asLong():0);
            h.put("payloadBytes", p.length);
            h.put("metadataSha256", sha(m));
            h.put("payloadSha256", sha(p));
            if (kind == 2 && model.get("hasEvent").asBoolean()) {
                h.set("eventSeq", model.get("eventIdentity").get("seq"));
                h.set("eventType", model.get("eventIdentity").get("type"));
            }
            else {
                h.putNull("eventSeq");
                h.putNull("eventType");
            }
            return PortalEventsPacketDecoderTest.packet(kind, kind == 1 ? 0 : ++ordinal, m, p, JSON.writeValueAsString(h));
        }
        PortalEventsTls tls() throws Exception {
            return new PortalEventsTls(URI.create("https://"+HOST+":"+listener.getLocalPort()), new byte[] {
                127, 0, 0, 1
            }, PortalRunEventsHttpTest.ca(run, "root"));
        }
        public void close() throws Exception {
            listener.close();
            if (socket != null) socket.close();
            worker.join(5000);
            assertFalse(worker.isAlive());
            if (engineResponse != null) engineResponse.close();
            if (failure != null) throw new AssertionError("receiver peer", failure);
        }
    }

}
