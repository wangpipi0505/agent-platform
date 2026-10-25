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
    @Test void originalRequestBootstrapAndContinuousCursorTerminal() throws Exception { child("sequence"); }
    @Test void privateEofDoesNotInventTerminal() throws Exception { child("eof"); }
    @Test void realBackpressureAbortRetainsCursorAndActuallyExits() throws Exception { child("backpressure"); }
    @Test void resetPrecedesStatusAndDoesNotAdvanceCursor() throws Exception { child("reset"); }
    @Test void allSpacePgTypeSurvivesActualServletOutput() throws Exception { child("spaces"); }
    @Test void generationAtGetterAndEachWriteBoundaryNeverPublishesOldTail() throws Exception { for(String point:List.of("before-getter","after-getter","before-ready","before-write","after-write","before-flush"))child("generation-"+point); }
    @Test void revokeAndPartialUnknownNeverAdvanceCursor() throws Exception { for(String point:List.of("revoke","partial"))child("freshness-"+point); }
    @Test void firstErrorAndLateFirstWindowCannotReviveRegistration() throws Exception { for(String point:List.of("bad-receipt","late"))child("bootstrap-"+point); }
    @Test void originalIdleDeadlineSurvivesSpuriousWakeupsAndStoppedReader() throws Exception { child("idle"); }
    @Test void twoHeavyHelpersKeepWholeOwnersAndThirdAdmissionClosed() throws Exception { child("heavy-retention"); }
    @Test void stalledCompletionKeepsOwnersBeforeOldHttpCleanup() throws Exception { child("completion-retention"); }
    @Test void abortIsOrderedAfterActualReadyWriteAndRetainsFeesWhileStalled() throws Exception { child("abort-order"); }
    @Test void lateAbortCannotTouchReusedKeepaliveRequest() throws Exception { child("keepalive"); }
    @Test void realNotReadyNotificationResumesTheSameFrame() throws Exception { child("backpressure-resume"); }
    @Test void exactLongMaxCursorAndHighPrecisionJsonReachRealServletBytes() throws Exception { child("long-id");child("precision"); }
    @Test void inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner() throws Exception { child("light-shell"); }
    @Test void actualPendingFlushRequiresDrainAndFreshGenerationBeforeCursor() throws Exception { for(String point:List.of("drain","generation","revoke"))child("pending-flush-"+point); }
    @Test void stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit() throws Exception { child("action-retention"); }
    @Test void sameProcessorOnAnotherSocketCannotReceiveOldAbort() throws Exception { child("processor-reuse"); }
    @Test void noFutureWritableCallbackIsNeededForTheOwnedIdleDeadline() throws Exception { child("not-ready-idle"); }
    @Test void controlledProfileAndSingleLifetimeInstanceFailClosed() throws Exception { child("bad-profile"); }
    @Test void forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion() throws Exception { child("forced-release"); }
    static Path runs() throws IOException { return Files.createDirectories(root().resolve(".local/iam-o2b-f2c2a-fixes/peers")); }
    static void child(String group) throws Exception {
        Path run = Files.createDirectory(runs().resolve(group + "-" + UUID.randomUUID()));
        var args = List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
        "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0", "-Djdk.tls.server.enableStatusRequestExtension=false",
        "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PortalRunEventsSseLifecycleTest.class.getName(),
        group, run.toString());
        Files.writeString(run.resolve("client.args"), String.join("\n", args));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin/java.exe").toString());
        command.addAll(args);
        Process p = new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
        Files.writeString(run.resolve("launch.properties"), "pid=" + p.pid() + "\ncwd=" + root() + "\n");
        var failure = new AtomicReference<Throwable>();
        Thread collector = Thread.ofPlatform().daemon().start(() -> {
            try (var out = Files.newOutputStream(run.resolve("client.out")); var in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n, total = 0;
                while ((n = in.read(b)) != -1) {
                    if ((total += n) > 131072) throw new IOException("bounded child output");
                    out.write(b, 0, n);
                }
            }
            catch (Throwable t) {
                failure.set(t);
                p.destroyForcibly();
            }
        });
        try {
            assertTrue(p.waitFor(95, TimeUnit.SECONDS), "owned child deadline");
            collector.join(3000);
            assertFalse(collector.isAlive());
            assertNull(failure.get());
            assertEquals(0, p.exitValue(), new String(Files.readAllBytes(run.resolve("client.out")), StandardCharsets.UTF_8));
        }
        finally {
            if (p.isAlive()) p.destroyForcibly();
            assertTrue(p.waitFor(3, TimeUnit.SECONDS));
            collector.join(3000);
            assertFalse(collector.isAlive());
            Files.writeString(run.resolve("exit.properties"), "pid=" + p.pid() + "\nexit=" + p.exitValue() + "\nalive=" + p.isAlive() + "\ncollectorAlive=" + collector.isAlive() + "\n");
        }
    }

    @Test void q403FailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-q403"); }
    @Test void qTransportFailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-qtransport"); }
    @Test void transferredServletCannotClaimTheOriginalRequestFromWorker()throws Exception { child("fix-offthread"); }
    @Test void originalRequestScopeAndDispatcherAreRequiredBeforeQuery()throws Exception { child("fix-dispatcher");child("fix-missing-token"); }
    @Test void uncertainProcessorConstructionBurnsItsReservedBundle()throws Exception { child("fix-burn"); }
    @Test void automaticPhantomNotificationCanReturnActualFactoryCapacity()throws Exception { child("fix-reuse"); }
    @Test void actualProcessorFactoryRefusesBeyondFixedStrongPopulation()throws Exception { child("fix-cap"); }
    public static void main(String[] args)throws Exception { scenario(args[0],Path.of(args[1])); }
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
    static final class Exchange {
        final AtomicReference<PortalRunEventsSse.Handle> handle=new AtomicReference<>();
        final AtomicReference<PortalRunEventsHttp.Session> session=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final List<String> trace=Collections.synchronizedList(new ArrayList<>());
        PortalRunEventsSse.Handle handle()throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(handle.get()==null&&System.nanoTime()<end) { if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(10); }
            assertNotNull(handle.get());return handle.get();
        }
    }
    static final class Server implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();
        org.apache.catalina.Context context;
        final PortalRunEventsSse.ControlledProtocol protocol=new PortalRunEventsSse.ControlledProtocol();
        final List<Exchange> exchanges=new ArrayList<>();
        final List<PortalEventsTls> endpoints;
        final AtomicInteger next=new AtomicInteger();
        final Exchange first=new Exchange();
        final AtomicReference<PortalRunEventsSse.Handle> handle=first.handle;
        final AtomicReference<PortalRunEventsHttp.Session> session=first.session;
        final AtomicReference<Throwable> failure=first.failure;
        final List<String> trace=first.trace;
        final Path run;
        String invocation="normal";
        Server(Path run,Fixture f,PortalEventsTls tls,BiConsumer<String,Long> hook)throws Exception {
            this(run,f,List.of(tls),Collections.singletonList(hook));
        }
        Server(Path run,Fixture f,List<PortalEventsTls> endpoints,List<BiConsumer<String,Long>> hooks)throws Exception {
            this.run=run;this.endpoints=endpoints;exchanges.add(first);for(int i=1;i<endpoints.size();i++)exchanges.add(new Exchange());
            Path base=Files.createDirectories(run.resolve("tomcat"));tomcat.setBaseDir(base.toString());
            var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);
            connector.setProperty("address","127.0.0.1");connector.setProperty("socket.txBufSize","1024");connector.setProperty("socket.appWriteBufSize","8192");
            connector.setProperty("minSpareThreads","2");
            context=tomcat.addContext("",base.toString());context.getPipeline().addValve(PortalRunEventsSse.contextValve());
            var servlet=Tomcat.addServlet(context,"events",new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
                    int index=next.getAndIncrement();var exchange=exchanges.get(index);var hook=hooks.get(index);
                    var adapter=new PortalRunEventsSse((phase,value)->{exchange.trace.add(phase+"="+value);if(hook!=null)hook.accept(phase,value);});
                    try { var owned=new PortalRunEventsHttp(endpoints.get(index)).begin(f.observed,f.source,f.run,0);exchange.session.set(owned);
                        if(invocation.equals("offthread")) {
                            Runnable call=()->{ try { exchange.handle.set(adapter.start(request,response,owned)); }catch(Throwable t) { exchange.failure.set(t); } };
                            Thread other=Thread.ofPlatform().start(call);other.join(6000);if(other.isAlive()) { owned.close();other.interrupt();other.join(6000); }assertFalse(other.isAlive(),"bounded off-thread probe actually exited");
                        } else if(invocation.equals("dispatcher"))request.getRequestDispatcher("/reentry").include(request,response);
                        else exchange.handle.set(adapter.start(request,response,owned)); }
                    catch(Throwable t) { exchange.failure.set(t); }
                }
            });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/admin/runs/*","events");
            var reentry=Tomcat.addServlet(context,"reentry",new HttpServlet() { @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException { var exchange=exchanges.get(next.get()-1);try { exchange.handle.set(new PortalRunEventsSse().start(request,response,exchange.session.get())); }catch(Throwable t) { exchange.failure.set(t); } } });reentry.setAsyncSupported(true);context.addServletMappingDecoded("/reentry","reentry");
            Tomcat.addServlet(context,"ping",new HttpServlet() { @Override protected void doGet(HttpServletRequest r,HttpServletResponse out)throws IOException { out.setContentLength(4);out.getOutputStream().write(utf("pong")); } });context.addServletMappingDecoded("/ping","ping");
            tomcat.start();assertEquals(1024,protocol.getMaxConnections());assertEquals(64,protocol.getMaxThreads());assertEquals(64,protocol.getProcessorCache());
        }
        Socket request(String run)throws Exception { return request(run,false); }
        Socket request(String run,boolean keepAlive)throws Exception {
            var socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",tomcat.getConnector().getLocalPort()));socket.setSoTimeout(12000);
            socket.getOutputStream().write(utf("GET /admin/runs/"+run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: "+(keepAlive?"keep-alive":"close")+"\r\n\r\n"));socket.getOutputStream().flush();return socket;
        }
        boolean waitTrace(String prefix,long millis)throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end) { synchronized(trace) { if(trace.stream().anyMatch(x->x.startsWith(prefix)))return true; }Thread.sleep(10); }return false;
        }
        PortalRunEventsSse.Handle handle()throws Exception { return first.handle(); }
        public void close()throws Exception {
            for(int i=0;i<exchanges.size();i++) {
                var exchange=exchanges.get(i);Files.write(run.resolve("sse-trace-finally-"+i+".txt"),exchange.trace,StandardCharsets.UTF_8);
                var h=exchange.handle.get();if(h!=null) { h.close();assertTrue(h.awaitClosed(Duration.ofSeconds(5))); }
            }
            for(var tls:endpoints)tls.close();tomcat.stop();tomcat.destroy();
            Files.writeString(run.resolve("protocol.properties"),"implementation="+protocol.getClass().getName()+"\ncreated="+protocol.creationsForTest()+"\nrecycled="+protocol.recyclesForTest()+"\nwaiting="+protocol.getWaitingProcessorCount()+"\nbaseline="+PortalEventsPacketBudget.SHARED.connectorBaselineBytes()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\nowners="+PortalEventsPacketBudget.SHARED.owners()+"\n");
        }
    }
    static void scenario(String mode,Path run)throws Exception {
        if(mode.startsWith("fix-q")) { startupFailure(mode,run);return; }
        if(mode.equals("fix-offthread")||mode.equals("fix-dispatcher")||mode.equals("fix-missing-token")) { startupScope(mode.substring(4),run);return; }
        if(mode.equals("fix-cap")||mode.equals("fix-burn")||mode.equals("fix-reuse")) { allocationCap(mode,run);return; }
        if(mode.startsWith("generation-")||mode.startsWith("freshness-")) { freshness(mode,run);return; }
        if(mode.startsWith("bootstrap-")) { bootstrap(mode.substring(10),run);return; }
        if(mode.startsWith("pending-flush-")) { pendingFlush(mode.substring(14),run);return; }
        switch(mode) {
            case "action-retention" -> { actionRetention(run);return; }
            case "processor-reuse" -> { processorReuse(run);return; }
            case "bad-profile" -> {
                var protocol=new PortalRunEventsSse.ControlledProtocol();protocol.setMaxThreads(65);
                assertThrows(IllegalStateException.class,protocol::start);assertThrows(IllegalStateException.class,PortalRunEventsSse.ControlledProtocol::new);
                assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());return;
            }
            case "not-ready-idle" -> { notReadyIdle(run);return; }
            case "forced-release" -> { forcedRelease(run);return; }
            case "idle" -> { idle(run);return; }
            case "keepalive" -> { keepalive(run);return; }
            case "light-shell" -> { lightShell(run);return; }
            case "heavy-retention","completion-retention","abort-order" -> { retentionScenario(mode,run);return; }
        }
        byte[] raw=mode.startsWith("backpressure")?utf("\""+"x".repeat(262142)+"\""):mode.equals("precision")?utf(" { \"n\" : -0.00, \"value\": 1.23456789012345678901234567890e+20 } "):utf("null");
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},mode,raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
            if(mode.startsWith("backpressure")) {
                assertTrue(server.waitTrace("ready-false",8000)||server.waitTrace("flush-wait",1000),"real stopped-reader readiness");
                var handle=server.handle();long prior=handle.after();long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>83886080L);
                if(mode.equals("backpressure-resume")) {
                    socket.setReceiveBufferSize(262144);byte[] body=socket.getInputStream().readAllBytes();assertTrue(body.length>262144);
                    assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(1,handle.after());assertTrue(traceCount(server.trace,"callback-entry")>1);
                } else { handle.close();assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(prior,handle.after()); }
            } else {
                byte[] body=socket.getInputStream().readAllBytes();var handle=server.handle();assertTrue(handle.awaitClosed(Duration.ofSeconds(4)));assertTrue(handle.detachedForTest());assertTrue(handle.inertTokenForTest());
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
            assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());assertNull(server.failure.get());
            Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
            Files.writeString(run.resolve("sse.properties"),"after="+server.handle().after()+"\nterminal="+server.handle().terminal()+"\nactualWorkerExit=true\nownerBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
        }
    }
    static void startupFailure(String mode,Path run)throws Exception {
        String selected=mode.substring(4);
        try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},selected,utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},selected,utf("null"));var c=new SequencePeer(f,Files.createDirectories(run.resolve("c")),m->{},"eof",utf("null"));var ta=a.tls();var tb=b.tls();var tc=c.tls();var server=new Server(run,f,List.of(ta,tb,tc),Arrays.asList(null,null,null))) {
            try {
                for(int i=0;i<2;i++) {
                    try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
                    var x=server.exchanges.get(i);assertNotNull(x.failure.get());assertNull(x.handle.get());assertFalse(traceCount(x.trace,"setter-return")>0);assertFalse(traceCount(x.trace,"window")>0);
                    Files.writeString(run.resolve("before-cleanup-"+i+".properties"),"owners="+PortalEventsPacketBudget.SHARED.owners()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
                    assertTrue(x.session.get().awaitClosed(Duration.ofSeconds(5)),"start owns failed Q Session before test cleanup");assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(1048576,PortalEventsPacketBudget.SHARED.reservedBytes());
                }
                try(var socket=server.request(f.run)) { assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("data: null")); }
                assertTrue(server.exchanges.get(2).handle().awaitClosed(Duration.ofSeconds(5)));
                Files.writeString(run.resolve("startup.properties"),"repeatFailures=2\nsubsequentNormalStart=true\n");
            } finally { for(var x:server.exchanges)if(x.session.get()!=null)x.session.get().close(); }
        }
    }
    static void startupScope(String mode,Path run)throws Exception {
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"scope",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null)) {
            server.invocation=mode;
            if(mode.equals("missing-token"))for(var valve:server.context.getPipeline().getValves())if(valve.getClass().getName().contains("PortalRunEventsSse$ContextValve"))server.context.getPipeline().removeValve(valve);
            try {
                try(var socket=server.request(f.run)) { try { socket.getInputStream().readAllBytes(); }catch(SocketTimeoutException boundedOldProbe) { Files.writeString(run.resolve("old-probe-timeout.txt"),"real connector probe reached bounded read deadline\n"); } }
                assertEquals(0,peer.connections,"reject before Q");assertNotNull(server.failure.get());assertNull(server.handle.get(),"original REQUEST only");
                assertFalse(traceCount(server.trace,"bootstrap-wait")>0);assertFalse(traceCount(server.trace,"setter-return")>0);assertEquals(0,peer.connections,"reject before Q");
                assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5)));assertEquals(1048576,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("scope.properties"),"mode="+mode+"\nqConnections="+peer.connections+"\nsetter=0\n");
            } finally { if(server.session.get()!=null)server.session.get().close(); }
        }
    }
    static void allocationCap(String mode,Path run)throws Exception {
        Tomcat tomcat=new Tomcat();var protocol=new PortalRunEventsSse.ControlledProtocol();var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);tomcat.setBaseDir(Files.createDirectories(run.resolve("tomcat")).toString());tomcat.addContext("",run.toString());
        org.apache.coyote.Processor[] held=new org.apache.coyote.Processor[1537];
        try {
            tomcat.start();
            if(mode.equals("fix-burn")) {
                protocol.failConstructionForTest(1);assertThrows(IllegalStateException.class,protocol::createProcessor);
                protocol.failConstructionForTest(2);assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(2,protocol.burnedForTest());
                for(int i=0;i<1534;i++)held[i]=protocol.createProcessor();assertThrows(IllegalStateException.class,protocol::createProcessor);
                assertEquals(1536,protocol.allocationsForTest());assertEquals(2,protocol.burnedForTest());
                Files.writeString(run.resolve("burn.properties"),"burned=2\ntracked=1534\nownedAllocations=1536\nrefusalBeforeExtraAllocation=true\n");return;
            }
            for(int i=0;i<1535;i++)held[i]=protocol.createProcessor();
            assertTrue(protocol.waitingIdentityForTest(held[0]));held[0].recycle();assertTrue(protocol.waitingIdentityForTest(held[0]));
            var start=new CountDownLatch(1);var success=new AtomicInteger();var denied=new AtomicInteger();var unexpected=new AtomicReference<Throwable>();
            Thread[] threads=new Thread[2];for(int j=0;j<2;j++) { final int index=1535+j;threads[j]=Thread.ofPlatform().start(()->{try { start.await();held[index]=protocol.createProcessor();success.incrementAndGet(); }catch(IllegalStateException expected) { denied.incrementAndGet(); }catch(Throwable t) { unexpected.set(t); }}); }
            start.countDown();for(var thread:threads) { thread.join(5000);assertFalse(thread.isAlive()); }
            Files.writeString(run.resolve("cap.properties"),"strongInitial=1535\nraceSuccess="+success+"\nraceDenied="+denied+"\ncreated="+protocol.creationsForTest()+"\n");
            assertNull(unexpected.get());assertEquals(1,success.get(),"only last allocation slot admitted");assertEquals(1,denied.get());assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(1536,protocol.creationsForTest());assertEquals(1536,protocol.allocationsForTest());
            if(mode.equals("fix-reuse")) {
                java.lang.ref.Reference.reachabilityFence(held);Arrays.fill(held,null);
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
                boolean explicit=protocol.returnedForTest()==0;if(explicit)System.gc(); // Authorized once, isolated witness only.
                end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
                long returned=protocol.returnedForTest();
                if(returned>0)held[0]=protocol.createProcessor();
                Files.writeString(run.resolve("reference-witness.properties"),"explicitGcCalls="+(explicit?1:0)+"\nautomaticNotifications="+returned+"\nactualFactoryReuse="+(held[0]!=null)+"\nobservation="+(returned>0?"OBSERVED":"INCONCLUSIVE")+"\n");
            }
        } finally { java.lang.ref.Reference.reachabilityFence(held);tomcat.stop();tomcat.destroy(); }
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
                assertTrue(server.waitTrace("token-disarmed",5000));assertTrue(server.handle().inertTokenForTest());var h=server.handle();assertFalse(h.awaitClosed(Duration.ZERO));assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>0);
                socket.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));socket.getOutputStream().flush();h.close();
                String next=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(next.startsWith("HTTP/1.1 200"));assertTrue(next.endsWith("pong"));
                release.countDown();assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("keepalive.properties"),"sameSocket=true\nlateAbortAfterRealDisarm=true\nnextRequestPong=true\nworkerHeldFeesUntilActualExit=true\n");
            } finally { release.countDown(); }
        }
    }
    static void pause(CountDownLatch release) {
        try { if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("owned pause deadline"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
    }
    static void freshness(String mode,Path run)throws Exception {
        var points=List.of(mode.substring(mode.indexOf('-')+1));
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
                    assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());
                    Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
                }
            }
        }
    }
    static void bootstrap(String selected,Path run)throws Exception {
        for(String mode:List.of(selected)) {
            Path vector=Files.createDirectory(run.resolve(mode));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},mode,utf("null"));var tls=peer.tls();var server=new Server(vector,f,tls,(phase,value)->{if(mode.equals("late")&&phase.equals("window")) { entered.countDown();pause(release); }});var socket=server.request(f.run)) {
                try {
                    if(mode.equals("late")) { assertTrue(entered.await(5,TimeUnit.SECONDS));server.session.get().close();assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>83886080L);release.countDown(); }
                    try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
                    assertNull(server.handle.get());assertNotNull(server.failure.get());
                    assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5)));assertFalse((traceCount(server.trace,"setter-return")>0));
                    assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
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
    static long traceCount(List<String> trace,String prefix) { synchronized(trace) { return trace.stream().filter(x->x.startsWith(prefix)).count(); } }
    static void lightShell(Path run)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var frames=new AtomicInteger();var hit=new AtomicBoolean();
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{
            if(phase.equals("frame-complete"))frames.incrementAndGet();
            if(phase.equals("ready-idle")&&frames.get()==2&&hit.compareAndSet(false,true)) { entered.countDown();pause(release); }
        });var socket=server.request(f.run)) {
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<end) { long observed=PortalEventsPacketBudget.SHARED.reservedBytes();if(observed>=67108864L&&observed<83886080L)break;Thread.sleep(10); }
                long scan=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(scan>=67108864L&&scan<83886080L,"old C1 gone, next actual F1 read charged");
                server.session.get().close();assertFalse(server.session.get().awaitClosed(Duration.ZERO));
                assertEquals(7218374L,PortalEventsPacketBudget.SHARED.reservedBytes(),"original HTTP/private/SSE/Owner retained before completion");
                release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());
                Files.writeString(run.resolve("light-shell.properties"),"nextF1Bytes="+scan+"\noldMaterialExited=true\nouterCallbackStillPaused=true\nretainedBeforeCompletion=7218374\nfinalBytes=1048576\n");
            } finally { release.countDown(); }
        }
    }
    static void pendingFlush(String selected,Path run)throws Exception {
        for(String outcome:List.of(selected)) {
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
        try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(a,b));var ca=server.request(f.run);var cb=server.request(f.run)) {
            var sa=server.exchanges.get(0);var sb=server.exchanges.get(1);
            try {
                assertTrue(entered.await(7,TimeUnit.SECONDS));
                long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertEquals(2,PortalEventsPacketBudget.SHARED.owners());
                assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
                if(mode.equals("heavy-retention")) { sa.session.get().close();sb.session.get().close();sa.handle().interruptReaderForTest();sb.handle().interruptReaderForTest();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.session.get().awaitClosed(Duration.ZERO)); }
                if(mode.equals("completion-retention")) { sa.session.get().close();sb.session.get().close();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.handle().awaitClosed(Duration.ofMillis(40))); }
                if(mode.equals("abort-order")) {
                    var abortA=Thread.ofPlatform().start(sa.handle()::close);var abortB=Thread.ofPlatform().start(sb.handle()::close);
                    Thread.sleep(100);assertTrue(abortA.isAlive());assertTrue(abortB.isAlive());assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());
                    release.countDown();abortA.join(5000);abortB.join(5000);assertFalse(abortA.isAlive());assertFalse(abortB.isAlive());
                }
                release.countDown();assertTrue(sa.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(sb.handle().awaitClosed(Duration.ofSeconds(6)));
                assertEquals(1048576L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
                Files.writeString(run.resolve("retention.properties"),"mode="+mode+"\nheldBytes="+held+"\nthirdDenied=true\nactualWorkersExited=true\nfinalOwnedBytes=0\nconnectorBaseline=1048576\n");
                Files.write(run.resolve("a-trace.txt"),sa.trace,StandardCharsets.UTF_8);Files.write(run.resolve("b-trace.txt"),sb.trace,StandardCharsets.UTF_8);
            } finally { release.countDown(); }
        }
    }
    static void actionRetention(Path run)throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-close-now")) { entered.countDown();pause(release); }};
        byte[] raw=utf("\""+"x".repeat(262142)+"\"");
        try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));var ca=server.request(f.run);var cb=server.request(f.run)) {
            var a=server.exchanges.get(0);var b=server.exchanges.get(1);
            Thread aa=null,bb=null;
            try {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(System.nanoTime()<end) { if((traceCount(a.trace,"ready-false")>0)&&(traceCount(b.trace,"ready-false")>0))break;Thread.sleep(10); }
                aa=Thread.ofPlatform().start(a.handle()::close);bb=Thread.ofPlatform().start(b.handle()::close);
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(aa.isAlive());assertTrue(bb.isAlive());
                assertFalse(a.handle().awaitClosed(Duration.ofMillis(40)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(40)));
                assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
                long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>=13388172L);
                release.countDown();aa.join(5000);bb.join(5000);assertFalse(aa.isAlive());assertFalse(bb.isAlive());
                assertTrue(a.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(b.handle().awaitClosed(Duration.ofSeconds(6)));
                assertEquals(1,traceCount(a.trace,"close-now-return"));assertEquals(1,traceCount(b.trace,"close-now-return"));
                Files.writeString(run.resolve("action-retention.properties"),"heldBytes="+held+"\nthirdDenied=true\nactualCloseNowEach=1\nactualActionThreadsExited=true\n");
            } finally { release.countDown();if(aa!=null)aa.join(5000);if(bb!=null)bb.join(5000); }
        }
    }
    static void processorReuse(Path run)throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{if(phase.equals("worker-body-exit")) { entered.countDown();pause(release); }});var first=server.request(f.run)) {
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));first.getInputStream().readAllBytes();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(server.protocol.recyclesForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
                assertTrue(server.protocol.recyclesForTest()>0);long old=server.protocol.processorForTest();Thread.sleep(100);
                try(var next=new Socket("127.0.0.1",server.tomcat.getConnector().getLocalPort())) {
                    next.setSoTimeout(5000);next.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\nGET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));next.getOutputStream().flush();
                    server.handle().close();String body=new String(next.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(2,body.split("pong",-1).length-1);
                    assertEquals(old,server.protocol.processorForTest());assertEquals(1,server.protocol.creationsForTest());
                    assertEquals(0,traceCount(server.trace,"before-close-now"));
                }
                release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));
                Files.writeString(run.resolve("reuse.properties"),"sameProcessorId="+old+"\ndifferentSocket=true\npipelinedPingResponses=2\nlateActionCalls=0\n");
            } finally { release.countDown(); }
        }
    }
    static void notReadyIdle(Path run)throws Exception {
        byte[] raw=utf("\""+"x".repeat(262142)+"\"");
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
            assertTrue(server.waitTrace("ready-false",5000));var h=server.handle();long before=h.after(),start=System.nanoTime();
            assertTrue(h.awaitClosed(Duration.ofSeconds(22)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            assertTrue(elapsed>=12000&&elapsed<21000);assertEquals(before,h.after());assertEquals(1,traceCount(server.trace,"close-now-return"));
            Files.writeString(run.resolve("not-ready-idle.properties"),"noClientReadAfterReadyFalse=true\nelapsedMs="+elapsed+"\ncloseNowCalls=1\nafter="+h.after()+"\n");
        }
    }
    static void forcedRelease(Path run)throws Exception {
        byte[] raw=utf("\""+"x".repeat(262142)+"\"");
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var owner=new AtomicReference<Server>();
        var sockets=new ConcurrentHashMap<String,org.apache.tomcat.util.net.SocketWrapperBase<?>>();
        BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-write")) { var actual=owner.get().protocol.currentSocketForTest();if(actual!=null)sockets.putIfAbsent(Thread.currentThread().getName()+"-"+System.identityHashCode(actual),actual); }if(phase.equals("recycle-invalidated")) { entered.countDown();pause(release); }};
        try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls() ) {
            var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));
            owner.set(server);
            try(var ca=server.request(f.run);var cb=server.request(f.run)) {
                var a=server.exchanges.get(0);var b=server.exchanges.get(1);a.handle();b.handle();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end&&!((traceCount(a.trace,"ready-false")>0)&&(traceCount(b.trace,"ready-false")>0)))Thread.sleep(10);
                var actual=new HashSet<>(sockets.values());assertEquals(2,actual.size());
                var closers=actual.stream().map(socket->Thread.ofPlatform().start(socket::close)).toList();
                try {
                    assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,server.protocol.recyclesForTest());
                    a.session.get().close();b.session.get().close();a.handle().close();b.handle().close();
                    assertFalse(a.handle().awaitClosed(Duration.ofMillis(50)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(50)));
                    assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
                    assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
                    release.countDown();for(var closer:closers) { closer.join(5000);assertFalse(closer.isAlive()); }
                    assertEquals(0,server.protocol.getWaitingProcessorCount());assertTrue(server.protocol.recyclesForTest()>=2);assertTrue(a.handle().inertTokenForTest());assertTrue(b.handle().inertTokenForTest());
                    a.handle().close();b.handle().close();assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
                    assertFalse((traceCount(a.trace,"completion-entry")>0));assertFalse((traceCount(b.trace,"completion-entry")>0));
                    Files.writeString(run.resolve("forced-release.properties"),"actualSocketCloseEach=true\nbarrierBeforeDelegateRecycle=true\nrecycled="+server.protocol.recyclesForTest()+"\nwaiting=0\ncompletionNotInvented=true\nownersRetained=2\nlateActionCalls=0\n");
                } finally { release.countDown();for(var closer:closers)closer.join(5000); }
            } finally { for(int i=0;i<server.exchanges.size();i++)Files.write(run.resolve("forced-trace-"+i+".txt"),server.exchanges.get(i).trace,StandardCharsets.UTF_8);server.tomcat.stop();server.tomcat.destroy(); }
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
                        if(mode.equals("q403")) { q.getOutputStream().write(utf("HTTP/1.1 403 Denied\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));q.getOutputStream().flush();return; }
                        if(mode.equals("qtransport"))return;
                        q.getOutputStream().write(f.q());
                        q.getOutputStream().flush();
                    }
                    try (var e = (SSLSocket)listener.accept()) {
                        socket = e;
                        connections++;
                        e.setSoTimeout(25000);
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
                    if (engineResponse == null&&!mode.equals("scope")) failure = expected;
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
