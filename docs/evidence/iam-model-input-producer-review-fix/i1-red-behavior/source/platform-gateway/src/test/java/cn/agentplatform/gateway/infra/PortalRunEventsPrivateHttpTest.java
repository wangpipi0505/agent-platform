package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest.Fixture;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Private TLS input only: packets remain untrusted, with no receipt/material/public output. */
public class PortalRunEventsPrivateHttpTest {
    static final String HOST=PortalRunEventsHttpTest.HOST,RUN=RunObservationEventsMetadataTest.RUN,MIME="application/vnd.agent-platform.portal-events.v1";
    @Test void sameSessionMovesOriginalQToRealGChildAndPrivatePackets() throws Exception { child("normal"); }
    @Test void finitePrivateErrorsReturnOnlyTheirAllowedCodeAndStatus() throws Exception { child("errors"); }
    @Test void legalHeadContinuesAfterChildExpiryAndNewGCurrentGeneration() throws Exception { child("body-expiry"); }
    @Test void ordinarySignedChildHasNoArtificialMinimumLength() throws Exception { child("ordinary"); }
    @Test void rawPrivatePeersRejectUnsafeHeadsErrorsAndFraming() throws Exception { child("wire"); }
    @Test void actualLargeClAndChunkedInputsUseOnePacketAndRejectPartialCommit() throws Exception { child("packets"); }
    @Test void completeHeadHasOneTwoSecondDeadline() throws Exception { child("head-deadline"); }
    @Test void actualBodyIdleExpiresAfterFifteenSeconds() throws Exception { child("idle"); }
    @Test void originalFiftyFiveSecondAnchorStopsAnActivelyDeliveringPeer() throws Exception { child("hard55"); }
    static Path root(){return PortalRunEventsHttpTest.root();}
    static void child(String group) throws Exception {
        Path run=Files.createDirectories(root().resolve(".local/iam-o2b-f2b2-private-http/peers/"+group+"-"+UUID.randomUUID()));
        var words=new ArrayList<>(List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768","-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false","-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),PortalRunEventsPrivateHttpTest.class.getName(),group,run.toString()));
        Files.writeString(run.resolve("client.args"),String.join("\n",words.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList()));
        var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin/java.exe").toString());command.addAll(words);
        Process p=new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();Thread collector=null;var failure=new AtomicReference<Throwable>();
        try {
            Files.writeString(run.resolve("launch.properties"),"pid="+p.pid()+"\ncwd="+root()+"\nargs=client.args\n");
            var out=Files.newOutputStream(run.resolve("client.out"));collector=Thread.ofPlatform().daemon().start(()->{try(out;var in=p.getInputStream()){byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>131072)throw new IOException("bounded child output");out.write(b,0,n);}}catch(Throwable t){failure.set(t);p.destroyForcibly();}});
            assertTrue(p.waitFor(group.equals("hard55")?75:group.equals("idle")?35:60,TimeUnit.SECONDS),"owned child deadline");collector.join(3000);assertFalse(collector.isAlive());assertNull(failure.get());assertEquals(0,p.exitValue(),new String(Files.readAllBytes(run.resolve("client.out")),StandardCharsets.UTF_8));
        } finally {if(p.isAlive())p.destroyForcibly();assertTrue(p.waitFor(3,TimeUnit.SECONDS));if(collector!=null){collector.join(3000);assertFalse(collector.isAlive());}Files.writeString(run.resolve("exit.properties"),"pid="+p.pid()+"\nexit="+p.exitValue()+"\nalive="+p.isAlive()+"\ncollectorAlive="+(collector!=null&&collector.isAlive())+"\n");}
    }
    static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[] response(int status,String type,byte[] body){return PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 "+status+" Finite\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\n\r\n"),body);}
    static byte[] packets() throws Exception{return PortalEventsPacketDecoderTest.join(PortalEventsPacketDecoderTest.hello(),PortalEventsPacketDecoderTest.packet(3,1,"{\"code\":\"BUFFER_LIMIT\"}",""));}
    static PortalEventsTls tls(Peer p,Path run)throws Exception{return new PortalEventsTls(URI.create("https://"+HOST+":"+p.listener.getLocalPort()),new byte[]{127,0,0,1},PortalRunEventsHttpTest.ca(run,"root"));}
    static Object field(Object receiver,String name)throws Exception{var f=receiver.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(receiver);}
    static void set(Object receiver,String name,Object value)throws Exception{var f=receiver.getClass().getDeclaredField(name);f.setAccessible(true);f.set(receiver,value);}
    static void closed(PortalRunEventsHttp.Session s){s.close();assertTrue(s.awaitClosed(Duration.ofSeconds(3)));assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());}
    static final class Peer implements AutoCloseable {
        final SSLServerSocket listener;final Thread worker;final byte[] eResponse;final String mode;
        final AtomicInteger connections=new AtomicInteger(),eBytes=new AtomicInteger();
        final CountDownLatch qClosed=new CountDownLatch(1),eConnected=new CountDownLatch(1),eRequest=new CountDownLatch(1),release=new CountDownLatch(1),eClosed=new CountDownLatch(1);
        volatile SSLSocket socket;volatile String qRequest="",request="";volatile Throwable failure;volatile Runnable beforeBody;
        Peer(byte[] response,String mode)throws Exception {
            eResponse=response;this.mode=mode;var c=SSLContext.getInstance("TLSv1.3","SunJSSE");var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(PortalRunEventsHttpTest.fixtures(),"f2a-test-only".toCharArray());
            var original=Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
            c.init(new KeyManager[]{new PortalEventsTlsTest.FixedKey(original,"normal",new Properties())},null,null);
            listener=(SSLServerSocket)c.getServerSocketFactory().createServerSocket(0,2,InetAddress.getByAddress(new byte[]{127,0,0,1}));listener.setEnabledProtocols(new String[]{"TLSv1.3"});listener.setEnabledCipherSuites(new String[]{PortalEventsTls.CIPHER});listener.setSoTimeout(5000);
            worker=Thread.ofPlatform().daemon().start(()->{
                try {
                    try(var q=(SSLSocket)listener.accept()){socket=q;connections.incrementAndGet();q.setSoTimeout(5000);q.startHandshake();qRequest=readHead(q,false);q.getOutputStream().write(PortalRunEventsHttpTest.normal());q.getOutputStream().flush();observeClose(q);qClosed.countDown();}
                    try(var e=(SSLSocket)listener.accept()){socket=e;connections.incrementAndGet();e.setSoTimeout(65000);eConnected.countDown();if(mode.equals("handshake"))assertTrue(release.await(5,TimeUnit.SECONDS));e.startHandshake();request=readHead(e,true);eRequest.countDown();if(request.isEmpty())return;
                        if(eResponse!=null){
                            if(mode.equals("drip")) {int at=new String(eResponse,StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;e.getOutputStream().write(eResponse,0,at);e.getOutputStream().flush();while(at<eResponse.length){var prefix=java.nio.ByteBuffer.wrap(eResponse,at,24);int len=24+prefix.getInt(at+16)+prefix.getInt(at+20)+44;e.getOutputStream().write(eResponse,at,len);e.getOutputStream().flush();at+=len;Thread.sleep(1000);}}
                            else if(mode.equals("split")) {int at=new String(eResponse,StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")+4;e.getOutputStream().write(eResponse,0,at);e.getOutputStream().flush();if(beforeBody!=null)beforeBody.run();e.getOutputStream().write(eResponse,at,eResponse.length-at);}
                            else e.getOutputStream().write(eResponse);
                            e.getOutputStream().flush();
                        }
                        if(mode.equals("hold"))assertTrue(release.await(65,TimeUnit.SECONDS));
                        observeClose(e);eClosed.countDown();
                    }
                }catch(SocketTimeoutException|SocketException expected){}catch(Throwable t){failure=t;}finally{qClosed.countDown();eRequest.countDown();eClosed.countDown();}
            });
        }
        String readHead(SSLSocket s,boolean e)throws IOException{var b=new ByteArrayOutputStream();int last=0,x;while((x=s.getInputStream().read())!=-1){if(e)eBytes.incrementAndGet();b.write(x);last=(last<<8)|x;if(last==0x0d0a0d0a||b.size()>32768)break;}return b.toString(StandardCharsets.US_ASCII);}
        static void observeClose(SSLSocket s)throws IOException{try{assertEquals(-1,s.getInputStream().read());}catch(SocketException expected){}}
        public void close()throws Exception {release.countDown();listener.close();if(socket!=null)socket.close();worker.join(5000);assertFalse(worker.isAlive());if(failure!=null)throw new AssertionError(failure);}
    }
    static void normal(Path run)throws Exception { normal(run,true); }
    static void normal(Path run,boolean padded)throws Exception {
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run)){
            if(padded)f.iam.ticketMutation(c->{if(c.containsKey("portal_events_child"))c.put("private_test_padding","x".repeat(32));});
            String source=f.source;var s=new PortalRunEventsHttp(tls).begin(f.gateway,source,RUN,7);var facts=s.lookupMetadata();assertTrue(p.qClosed.await(1,TimeUnit.SECONDS));assertNull(field(s,"handle"));assertNull(field(s,"timer"));assertEquals(3211392L,PortalEventsPacketBudget.SHARED.reservedBytes());var n=new AtomicInteger();
            try {s.consumePrivate(packet->{assertEquals(n.getAndIncrement(),packet.ordinal());try(var b=packet.metadata()){assertTrue(b.read()>=0);}});assertEquals(2,n.get());assertEquals(4260096L,PortalEventsPacketBudget.SHARED.reservedBytes());assertThrows(PlatformException.class,facts::runId);assertTrue(p.request.startsWith("GET /admin/runs/"+RUN+"/events?Last-Event-ID=7 HTTP/1.1\r\n"));assertTrue(p.request.contains("Accept: "+MIME+"\r\n"));assertFalse(p.request.contains(source));assertEquals(2,p.connections.get());
                String child=p.request.substring(p.request.indexOf("Authorization: Bearer ")+22).split("\r\n",2)[0];assertTrue(child.length()>8000&&child.length()<=16384,"actual signed child length "+child.length());if(padded)assertTrue(child.length()>=8495,"padded signed child length "+child.length());assertTrue(p.request.length()<=32768);assertEquals(1,Collections.frequency(f.iam.observedOperations(),"portal_events_child_exchange"));
                Files.writeString(run.resolve("summary.properties"),"actualChildBytes="+child.length()+"\nactualRequestHeadBytes="+p.request.length()+"\nconnections=2\nqPhysicalExitBeforeE=true\ncallbacks="+n.get()+"\nprivateUntrustedOnly=true\n");
            }finally{closed(s);}
        }
    }
    static String error(String code){return "{\"code\":\""+code+"\",\"message\":\"server detail never escapes\",\"data\":null,\"traceId\":null}";}
    static void errors(Path run)throws Exception {
        String[][] vectors={{"413","EVENT_LIMIT"},{"413","BUFFER_LIMIT"},{"429","STREAM_CAPACITY"},{"400","EVENT_CURSOR"},{"403","RUN_AUTHORITY_DENIED"},{"404","RUN_NOT_FOUND"}};
        int index=0;
        for(var v:vectors)try(var f=new Fixture();var p=new Peer(response(Integer.parseInt(v[0]),"application/json",utf(error(v[1]))),"normal");var tls=tls(p,run)) {
            var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);s.lookupMetadata();var callbacks=new AtomicInteger();
            try {var failure=assertThrows(PlatformException.class,()->s.consumePrivate(packet->callbacks.incrementAndGet()));
                Files.writeString(run.resolve("mapping-"+(index++)+".properties"),"wireStatus="+v[0]+"\neRequestBytes="+p.eBytes.get()+"\nlocalStatus="+failure.status()+"\nlocalCode="+failure.code()+"\ncallbacks="+callbacks.get()+"\n");
                assertTrue(p.eBytes.get()>0);assertEquals(Integer.parseInt(v[0]),failure.status());assertEquals(v[1],failure.code());assertFalse(failure.getMessage().contains("server detail"));assertNull(failure.getCause());assertEquals(0,callbacks.get());
            }finally{closed(s);}
        }
    }
    static void bodyExpiry(Path run)throws Exception {
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"split");var tls=tls(p,run)){
            set(f.iam,"childTicketSeconds",2L);var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);s.lookupMetadata();var callbacks=new AtomicInteger();var generationChanged=new AtomicBoolean();var childExpired=new AtomicBoolean();
            p.beforeBody=()->{try {PortalRunEventsPrivateAuthorityTest.until(()->{synchronized(s){return Boolean.TRUE.equals(field(s,"bodyPhase"));}},1000);var g=(cn.agentplatform.common.authority.GatewayPortalEventsResponse)field(s,"gatewayResponse");var old=g.currentCapture();Thread.sleep(3200);generationChanged.set(g.isSuperseded(old));var c=(cn.agentplatform.common.authority.HttpCurrentAuthorityClient.PortalEventsChildAuthorization)field(s,"child");assertThrows(PlatformException.class,c::requireVerified);childExpired.set(true);}catch(Exception e){throw new AssertionError(e);}};
            long started=System.nanoTime();try{s.consumePrivate(packet->callbacks.incrementAndGet());assertEquals(2,callbacks.get());assertTrue(generationChanged.get());assertTrue(childExpired.get());Files.writeString(run.resolve("summary.properties"),"legalHead=true\nactualWaitMillis="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+"\nchildExpired=true\nnewGGeneration=true\ncallbacks="+callbacks.get()+"\n");}finally{closed(s);}
        }
    }
    static byte[] raw(String head,byte[] body){return PortalEventsPacketDecoderTest.join(utf(head),body);}
    static byte[] chunked(byte[] body){return raw("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nTransfer-Encoding: chunked\r\n\r\n",PortalEventsPacketDecoderTest.join(utf(Integer.toHexString(body.length)+"\r\n"),body,utf("\r\n0\r\n\r\n")));}
    static void wire(Path run)throws Exception {
        byte[] good=packets();String ok="HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: "+good.length+"\r\n";var bad=new LinkedHashMap<String,byte[]>();
        bad.put("sse",response(200,"text/event-stream",good));bad.put("json-success",response(200,"application/json",good));bad.put("mime-parameter",response(200,MIME+";charset=UTF-8",good));
        for(int status:new int[]{100,101,103,401,302,500})bad.put("status-"+status,response(status,"application/json",utf(error("RUN_AUTHORITY_DENIED"))));
        for(String h:new String[]{"Content-Encoding: gzip","Content-Encoding: identity","Set-Cookie: a=b","Location: /elsewhere","Trailer: X-Fact","WWW-Authenticate: Bearer","Proxy-Authenticate: Basic","Content-Type: "+MIME,"Transfer-Encoding: chunked"," folded","X-Control: a\u0001b"})bad.put("header-"+bad.size(),raw(ok+h+"\r\n\r\n",good));
        for(String cl:new String[]{"-1","+1","01","9223372036854775808","0,0"})bad.put("cl-"+bad.size(),raw("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: "+cl+"\r\n\r\n",good));
        bad.put("duplicate-cl",raw(ok+"Content-Length: "+good.length+"\r\n\r\n",good));bad.put("no-framing",raw("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\n\r\n",good));bad.put("te-unknown",raw("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nTransfer-Encoding: gzip\r\n\r\n",good));
        bad.put("trailer",raw("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nTransfer-Encoding: chunked\r\n\r\n",PortalEventsPacketDecoderTest.join(utf(Integer.toHexString(good.length)+"\r\n"),good,utf("\r\n0\r\nX-Trailer: denied\r\n\r\n"))));
        for(int status:new int[]{400,403,404,413,429})bad.put("unknown-code-"+status,response(status,"application/json",utf(error("OTHER"))));
        for(String json:new String[]{error("EVENT_LIMIT").replace("\"code\":\"EVENT_LIMIT\"","\"code\":\"EVENT_LIMIT\",\"code\":\"EVENT_LIMIT\""),error("EVENT_LIMIT")+"{}",error("EVENT_LIMIT").replace("null,\"traceId\"","{},\"traceId\""),error("EVENT_LIMIT").replace("server detail never escapes","\\uD800")})bad.put("strict-json-"+bad.size(),response(413,"application/json",utf(json)));
        bad.put("utf16",response(413,"application/json",error("EVENT_LIMIT").getBytes(StandardCharsets.UTF_16BE)));bad.put("error-cl8193",response(413,"application/json",utf(error("EVENT_LIMIT")+" ".repeat(8193-utf(error("EVENT_LIMIT")).length))));
        byte[] oversized=utf(error("EVENT_LIMIT")+" ".repeat(8193-utf(error("EVENT_LIMIT")).length));bad.put("error-chunk8193",raw("HTTP/1.1 413 Finite\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",PortalEventsPacketDecoderTest.join(utf(Integer.toHexString(oversized.length)+"\r\n"),oversized,utf("\r\n0\r\n\r\n"))));
        String many=ok+"X: 1\r\n".repeat(62)+"\r\n";bad.put("headers64",raw(many,good));String base=ok+"X-Padding: ";int padding=8192-utf(base+"\r\n\r\n").length;bad.put("head8193",raw(base+"x".repeat(padding+1)+"\r\n\r\n",good));
        int n=0;for(var vector:bad.entrySet())try(var f=new Fixture();var p=new Peer(vector.getValue(),"normal");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();var callbacks=new AtomicInteger();try{var failure=assertThrows(PlatformException.class,()->session.consumePrivate(packet->callbacks.incrementAndGet()),vector.getKey());assertEquals(503,failure.status(),vector.getKey());assertNull(failure.getCause());assertTrue(p.eBytes.get()>0);if(!vector.getKey().equals("trailer"))assertEquals(0,callbacks.get(),vector.getKey());Files.writeString(run.resolve("vector-"+(n++)+".properties"),"name="+vector.getKey()+"\nstatus="+failure.status()+"\ncallbacks="+callbacks.get()+"\n");}finally{closed(session);}
        }
        for(String head:new String[]{ok+"X: 1\r\n".repeat(61)+"\r\n",base+"x".repeat(padding)+"\r\n\r\n"})try(var f=new Fixture();var p=new Peer(raw(head,good),"normal");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();try{var count=new AtomicInteger();session.consumePrivate(packet->count.incrementAndGet());assertEquals(2,count.get());}finally{closed(session);}
        }
        Files.writeString(run.resolve("summary.properties"),"rejectedVectors="+bad.size()+"\nheaders63Accepted=true\nhead8192Accepted=true\nhead8193Rejected=true\n");
    }
    static void packetInputs(Path run)throws Exception {
        String window=PortalEventsPacketDecoderTest.window(false,"{\"padding\":\""+"x".repeat(20000)+"\"}");byte[] large=PortalEventsPacketDecoderTest.join(PortalEventsPacketDecoderTest.hello(),PortalEventsPacketDecoderTest.packet(2,1,window,""));assertTrue(large.length>8192);
        for(byte[] wire:new byte[][]{response(200,MIME,large),chunked(large)})try(var f=new Fixture();var p=new Peer(wire,"normal");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();var count=new AtomicInteger();try{session.consumePrivate(packet->{assertEquals(count.getAndIncrement(),packet.ordinal());assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()<8000000L,"F1 scan has exited before callback");assertSame(packet,fieldUnchecked(session,"decoder","packet"));try(var metadata=packet.metadata()){assertTrue(metadata.read()>=0);}});assertEquals(2,count.get());}finally{closed(session);}
        }
        byte[] bad=PortalEventsPacketDecoderTest.hello();bad[bad.length-1]^=1;byte[] truncated=Arrays.copyOf(PortalEventsPacketDecoderTest.hello(),30);int i=0;
        for(byte[] body:new byte[][]{bad,truncated,PortalEventsPacketDecoderTest.join(PortalEventsPacketDecoderTest.hello(),bad)})try(var f=new Fixture();var p=new Peer(response(200,MIME,body),"normal");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();var count=new AtomicInteger();try{assertThrows(PlatformException.class,()->session.consumePrivate(packet->count.incrementAndGet()));assertEquals(i++==2?1:0,count.get());}finally{closed(session);}
        }
        try(var f=new Fixture();var p=new Peer(response(200,MIME,PortalEventsPacketDecoderTest.hello()),"normal");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();try{var count=new AtomicInteger();session.consumePrivate(packet->count.incrementAndGet());assertEquals(1,count.get());}finally{closed(session);}
        }
        Files.writeString(run.resolve("summary.properties"),"largeActualBodyBytes="+large.length+"\nclAndChunked=true\nscanReleasedBeforeCallback=true\nonePacketBorrow=true\nbadCommitAndTruncationRejected=true\nhelloEofHasNoTerminalAssertion=true\n");
    }
    static Object fieldUnchecked(Object receiver,String first,String second){try{return field(field(receiver,first),second);}catch(Exception e){throw new AssertionError(e);}}
    static void deadline(Path run,boolean idle)throws Exception {
        byte[] head=idle?utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: 100000\r\n\r\n"):null;
        try(var f=new Fixture();var p=new Peer(head,"hold");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();long start=System.nanoTime();try{assertThrows(PlatformException.class,()->session.consumePrivate(packet->fail("deadline callback")));long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertTrue(ms>=(idle?14000:1500)&&ms<(idle?18000:3500),"actual deadline "+ms);assertNull(field(session,"handle"));assertNull(field(session,"timer"));Files.writeString(run.resolve("summary.properties"),"phase="+(idle?"body":"head")+"\nactualMillis="+ms+"\nphysicalAndTimerExitBeforeReturn=true\n");}finally{p.release.countDown();closed(session);}
        }
    }
    static void hard55(Path run)throws Exception {
        var bodies=new ArrayList<byte[]>();bodies.add(PortalEventsPacketDecoderTest.hello());for(int n=1;n<=60;n++)bodies.add(PortalEventsPacketDecoderTest.packet(2,n,PortalEventsPacketDecoderTest.window(false,"{}"),""));byte[] body=PortalEventsPacketDecoderTest.join(bodies.toArray(byte[][]::new));
        try(var f=new Fixture();var p=new Peer(response(200,MIME,body),"drip");var tls=tls(p,run)){
            var session=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);session.lookupMetadata();long t0=(Long)field(session,"t0");var callbacks=new AtomicInteger();try{assertThrows(PlatformException.class,()->session.consumePrivate(packet->callbacks.incrementAndGet()));long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t0);assertTrue(ms>=54500&&ms<57500,"actual original anchor "+ms);assertTrue(callbacks.get()>=50&&callbacks.get()<=56);int count=callbacks.get();Thread.sleep(100);assertEquals(count,callbacks.get());assertNull(field(session,"handle"));assertNull(field(session,"timer"));Files.writeString(run.resolve("summary.properties"),"actualOriginalAnchorMillis="+ms+"\ncallbacks="+count+"\ncontinuedBodyDeliveryAvoidedIdle=true\nt0Unmodified=true\nphysicalAndTimerExit=true\n");}finally{closed(session);}
        }
    }
    public static void main(String[] args)throws Exception {Path run=Path.of(args[1]);switch(args[0]){case "normal"->normal(run);case "ordinary"->normal(run,false);case "wire"->wire(run);case "packets"->packetInputs(run);case "head-deadline"->deadline(run,false);case "idle"->deadline(run,true);case "hard55"->hard55(run);case "errors"->errors(run);case "body-expiry"->bodyExpiry(run);default->PortalRunEventsPrivateAuthorityTest.run(args[0],run);}System.out.println("F2B2_GROUP_OK="+args[0]);}
}
