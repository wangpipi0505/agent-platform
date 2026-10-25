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
    static byte[] wire(SequencePeer peer,byte[] hello,String child,ObjectNode original,byte[] payload,String mode)throws Exception {
        int count=mode.equals("backpressure")?20:mode.equals("sequence")?2:1;
        var parts=new ArrayList<byte[]>();parts.add(hello);
        for(int i=1;i<=count;i++) {
            var m=original.deepCopy();
            ((ObjectNode)m.get("window")).put("afterSeq",i-1).put("watermark",count);
            ((ObjectNode)m.get("state")).put("event_seq",count);
            ((ObjectNode)m.get("eventIdentity")).put("seq",i);
            if(mode.equals("sequence")) {
                ((ObjectNode)m.get("window")).put("status","SUCCEEDED");((ObjectNode)m.get("state")).put("status","SUCCEEDED");
            }
            ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));
            PortalRunEventsSseTest.payload(m,payload,mode.equals("sequence")?" 界".repeat(16):"EVENT");
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
        final List<String> trace=Collections.synchronizedList(new ArrayList<>());
        Server(Path run,Fixture f,PortalEventsTls tls,BiConsumer<String,Long> hook)throws Exception {
            Path base=Files.createDirectories(run.resolve("tomcat"));tomcat.setBaseDir(base.toString());tomcat.setPort(0);
            var connector=tomcat.getConnector();connector.setProperty("address","127.0.0.1");connector.setProperty("socket.txBufSize","1024");connector.setProperty("socket.appWriteBufSize","8192");
            connector.setProperty("maxThreads","8");connector.setProperty("minSpareThreads","2");
            var context=tomcat.addContext("",base.toString());context.getPipeline().addValve(PortalRunEventsSse.contextValve());
            var adapter=new PortalRunEventsSse((phase,value)->{trace.add(phase+"="+value);if(hook!=null)hook.accept(phase,value);});
            var servlet=Tomcat.addServlet(context,"events",new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
                    try {
                        var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,f.run,0);session.set(s);handle.set(adapter.start(request,response,s));
                    } catch(Throwable t) { failure.set(t); }
                }
            });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/admin/runs/*","events");tomcat.start();
        }
        Socket request(String run)throws Exception {
            var socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",tomcat.getConnector().getLocalPort()));socket.setSoTimeout(12000);
            socket.getOutputStream().write(utf("GET /admin/runs/"+run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));socket.getOutputStream().flush();return socket;
        }
        boolean waitTrace(String prefix,long millis)throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end) { synchronized(trace) { if(trace.stream().anyMatch(x->x.startsWith(prefix)))return true; }Thread.sleep(10); }return false;
        }
        PortalRunEventsSse.Handle handle()throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(handle.get()==null&&System.nanoTime()<end) { if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(10); }assertNotNull(handle.get());return handle.get(); }
        public void close()throws Exception {
            var h=handle.get();if(h!=null) { h.close();assertTrue(h.awaitClosed(Duration.ofSeconds(4))); }
            tomcat.stop();tomcat.destroy();
        }
    }
    static void scenario(String mode,Path run)throws Exception {
        byte[] raw=mode.equals("backpressure")?utf("\""+"x".repeat(262142)+"\""):utf("null");
        try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},mode,raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
            if(mode.equals("backpressure")) {
                assertTrue(server.waitTrace("ready-false",8000)||server.waitTrace("flush-wait",1000),"real stopped-reader readiness");
                var handle=server.handle();long prior=handle.after();long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>83886080L);
                handle.close();assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(prior,handle.after());
            } else {
                byte[] body=socket.getInputStream().readAllBytes();var handle=server.handle();assertTrue(handle.awaitClosed(Duration.ofSeconds(4)));
                String text=new String(body,StandardCharsets.UTF_8);assertTrue(text.contains("event: stream_status"));assertTrue(text.contains("data: null"));
                assertEquals(mode.equals("sequence")?2:1,handle.after());assertEquals(mode.equals("sequence"),handle.terminal());
                assertTrue(server.trace.contains("setter-return=0"));assertTrue(server.trace.contains("callback-entry=1"));
                assertTrue(server.trace.indexOf("setter-return=0")<server.trace.indexOf("callback-entry=1"));
                if(mode.equals("sequence")) { assertTrue(text.contains("id: 2"));assertTrue(text.contains("event:  界")); }
            }
            assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());assertNull(server.failure.get());
            Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
            Files.writeString(run.resolve("sse.properties"),"after="+server.handle().after()+"\nterminal="+server.handle().terminal()+"\nactualWorkerExit=true\nownerBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
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
                        e.setSoTimeout(10000);
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
                            e.getOutputStream().write(response(wire, MIME));
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
