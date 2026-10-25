package cn.agentplatform.gateway.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.gateway.infra.PublishedRunHttp;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Raw peers exercise the fixed GET wire policy only; they are not IAM/PG chain success evidence. */
class RunObservationGetTransportTest {
    static final String RUN="r_"+"a".repeat(32);
    static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static byte[] response(int status,String headers,byte[] body){var out=new ByteArrayOutputStream();out.writeBytes(utf("HTTP/1.1 "+status+" Finite\r\nContent-Type: application/json\r\n"+headers+"\r\n"));out.writeBytes(body);return out.toByteArray();}
    static byte[] fixed(int status,String body){byte[] b=utf(body);return response(status,"Content-Length: "+b.length+"\r\n",b);}
    static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
    @Test void realChunkedMultiHeaderResponseUsesOnlyFixedPathAndOneTicket()throws Exception {
        try(var p=new Peer(response(200,"X-One: 1\r\nX-Two: 2\r\nTransfer-Encoding: chunked\r\n",utf("2\r\n{}\r\n0\r\n\r\n")));var h=new PublishedRunHttp()){
            var c=new PublishedRunHttp.Cancellation();var r=h.getRunStatus(p.origin(),RUN,"finite-service-ticket",deadline(),c,()->{});assertThat(r.body()).isEqualTo(utf("{}"));
            assertThat(p.request()).startsWith("GET /admin/runs/"+RUN+" HTTP/1.1");assertThat(p.request()).contains("Authorization: Bearer finite-service-ticket","Accept-Encoding: identity").doesNotContain("Cookie:","X-User-","Portal");assertThat(p.accepts.get()).isEqualTo(1);assertThat(c.installedHandles()).isEqualTo(1);
        }
    }
    @Test void metadataAndStatusActualReadLimitsAcceptExactAndRejectPlusOne()throws Exception {
        for(boolean metadata:List.of(true,false)){int limit=metadata?8192:2*1024*1024;
            for(int extra:List.of(0,1))try(var p=new Peer(response(200,"Transfer-Encoding: chunked\r\n",chunk(new byte[limit+extra])));var h=new PublishedRunHttp()){
                if(extra==0)assertThat((metadata?h.getRunObservationMetadata(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}):h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{})).body()).hasSize(limit);
                else unavailable(()->{if(metadata)h.getRunObservationMetadata(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{});else h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{});});
                assertThat(p.accepts.get()).isEqualTo(1);
            }
        }
    }
    static byte[] chunk(byte[] bytes){var out=new ByteArrayOutputStream();out.writeBytes(utf(Integer.toHexString(bytes.length)+"\r\n"));out.writeBytes(bytes);out.writeBytes(utf("\r\n0\r\n\r\n"));return out.toByteArray();}
    @Test void retainedErrorsRequireStrictFiniteStatusCodeEnvelopePairs()throws Exception {
        var cases=new LinkedHashMap<String,Integer>();cases.put("RUN_STATUS_TOO_LARGE",403);cases.put("AUTHORITY_EXPIRED",403);cases.put("AUTHORITY_CONTEXT_MISSING",403);cases.put("RUN_AUTHORITY_DENIED",403);cases.put("MATERIAL_SCOPE_MISSING_OR_CHANGED",403);cases.put("SYS-AUTH-403",403);cases.put("AUTHORITY_DENIED",403);cases.put("RUN_NOT_FOUND",404);
        for(var e:cases.entrySet())try(var p=new Peer(fixed(e.getValue(),Json.write(new ApiResponse<>(e.getKey(),"private upstream message",null,"upstream-trace"))));var h=new PublishedRunHttp()){
            assertThatThrownBy(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{})).isInstanceOfSatisfying(PlatformException.class,x->{assertThat(x.status()).isEqualTo(e.getValue());assertThat(x.code()).isEqualTo(e.getKey());assertThat(x.getMessage()).doesNotContain("private upstream","upstream-trace");});assertThat(p.accepts.get()).isEqualTo(1);
        }
        for(String body:List.of("{}","",Json.write(new ApiResponse<>("UNKNOWN","x",null,null)),"{\"code\":\"RUN_NOT_FOUND\",\"message\":\"x\",\"data\":{},\"traceId\":null}","{\"code\":\"RUN_NOT_FOUND\",\"code\":\"RUN_NOT_FOUND\",\"message\":\"x\",\"data\":null,\"traceId\":null}",Json.write(new ApiResponse<>("RUN_NOT_FOUND","x",null,null))+" {}"))try(var p=new Peer(fixed(404,body));var h=new PublishedRunHttp()){unavailable(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}));}
        try(var p=new Peer(fixed(403,Json.write(new ApiResponse<>("RUN_METADATA_TOO_LARGE","x",null,null))));var h=new PublishedRunHttp()){unavailable(()->h.getRunObservationMetadata(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}));}
    }
    @Test void redirectsChallengesUnknownAndUnsafeWireNeverRetryOrForwardHeaders()throws Exception {
        var bodies=new ArrayList<byte[]>();for(int s:List.of(301,302,307,400,401,409,500,502,503))bodies.add(response(s,"Location: http://127.0.0.1:1/private\r\nWWW-Authenticate: Basic realm=finite\r\nContent-Length: 0\r\n",new byte[0]));
        for(String h:List.of("Content-Encoding: gzip\r\n","Set-Cookie: private=yes\r\n","Location: /x\r\n","Trailer: Private\r\n","Content-Type: application/json\r\n","X-Long: "+"a".repeat(8192)+"\r\n"))bodies.add(response(200,h+"Content-Length: 2\r\n",utf("{}")));
        bodies.add(response(200,"Transfer-Encoding: chunked\r\n",utf("2\r\n{}\r\n0\r\nX-Trailer: private\r\n\r\n")));
        bodies.add(response(200,"Transfer-Encoding: chunked\r\n",utf("2;"+"x".repeat(8192)+"\r\n{}\r\n0\r\n\r\n")));
        bodies.add(response(200,"Content-Length: 4\r\n",utf("{}")));
        bodies.add(response(200,"X-H: a\r\n".repeat(65)+"Content-Length: 2\r\n",utf("{}")));
        for(byte[] b:bodies)try(var p=new Peer(b);var h=new PublishedRunHttp()){unavailable(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}));assertThat(p.accepts.get()).isEqualTo(1);}
    }
    @Test void totalDeadlineCancellationAndOwnedCloseStopActualHandles()throws Exception {
        try(var p=new Peer(null);var h=new PublishedRunHttp()){
            var cancel=new PublishedRunHttp.Cancellation();long begin=System.nanoTime();unavailable(()->h.getRunStatus(p.origin(),RUN,"t",begin+TimeUnit.MILLISECONDS.toNanos(100),cancel,()->{}));assertThat(Duration.ofNanos(System.nanoTime()-begin)).isLessThan(Duration.ofSeconds(2));assertThat(cancel.cancelledHandles()).isPositive();assertThat(p.accepts.get()).isEqualTo(1);
        }
        try(var p=new Peer(null);var h=new PublishedRunHttp();var worker=Executors.newSingleThreadExecutor()){
            var cancel=new PublishedRunHttp.Cancellation();var f=worker.submit(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),cancel,()->{}));assertThat(p.accepted.await(2,TimeUnit.SECONDS)).isTrue();cancel.cancel();assertThatThrownBy(()->f.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(PlatformException.class);assertThat(cancel.cancelledHandles()).isPositive();assertThat(p.accepts.get()).isEqualTo(1);
        }
        try(var p=new Peer(null);var h=new PublishedRunHttp();var worker=Executors.newSingleThreadExecutor()){
            var f=worker.submit(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}));assertThat(p.accepted.await(2,TimeUnit.SECONDS)).isTrue();h.close();assertThatThrownBy(()->f.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(PlatformException.class);assertThat(p.accepts.get()).isEqualTo(1);
        }
    }
    @Test void checkAfterActualConnectCanDenyBeforeAnyRequestByte()throws Exception {
        try(var p=new Peer(null);var h=new PublishedRunHttp()){
            var calls=new AtomicInteger();unavailable(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{if(calls.incrementAndGet()>=3)throw new PlatformException(503,"FINITE_DENIAL","safe");}));assertThat(p.accepted.await(2,TimeUnit.SECONDS)).isTrue();assertThat(p.request()).isEmpty();assertThat(p.accepts.get()).isEqualTo(1);
        }
    }
    @Test void strictErrorsRejectNonUtf8Encoding()throws Exception {
        String error=Json.write(new ApiResponse<>("RUN_NOT_FOUND","x",null,null));var samples=new ArrayList<byte[]>();for(var charset:List.of(StandardCharsets.UTF_16,StandardCharsets.UTF_16LE,java.nio.charset.Charset.forName("UTF-32BE")))samples.add(error.getBytes(charset));
        var bad=new ByteArrayOutputStream();bad.write(utf("{\"code\":\"RUN_NOT_FOUND\",\"message\":\""));bad.write(0xc3);bad.write(utf("\",\"data\":null,\"traceId\":null}"));samples.add(bad.toByteArray());
        for(byte[] body:samples)try(var p=new Peer(response(404,"Content-Length: "+body.length+"\r\n",body));var h=new PublishedRunHttp()){unavailable(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),new PublishedRunHttp.Cancellation(),()->{}));}
    }
    @Test void overflowAbortsBeforeClosingUnreadChunkStream()throws Exception {
        int size=2*1024*1024+1;var partial=new ByteArrayOutputStream();partial.write(utf(Integer.toHexString(size)+"\r\n"));partial.write(new byte[size]);
        try(var p=new Peer(response(200,"Transfer-Encoding: chunked\r\n",partial.toByteArray()),true);var h=new PublishedRunHttp()){
            long begin=System.nanoTime();var cancel=new PublishedRunHttp.Cancellation();unavailable(()->h.getRunStatus(p.origin(),RUN,"t",deadline(),cancel,()->{}));long elapsed=System.nanoTime()-begin;
            System.out.println("O1C_OVERFLOW_ABORT="+Json.write(Map.of("elapsedMs",TimeUnit.NANOSECONDS.toMillis(elapsed),"requests",p.accepts.get(),"cancelledHandles",cancel.cancelledHandles())));
            assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(1));assertThat(p.accepts.get()).isEqualTo(1);
        }
    }
    static void unavailable(Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));}
    static final class Peer implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final AtomicInteger accepts=new AtomicInteger();final CountDownLatch accepted=new CountDownLatch(1),read=new CountDownLatch(1);final Thread thread;volatile Socket socket;volatile String request="";
        Peer(byte[] response)throws Exception {this(response,false);}
        Peer(byte[] response,boolean holdAfterResponse)throws Exception {server.bind(new InetSocketAddress("127.0.0.1",0));thread=Thread.ofPlatform().daemon(true).start(()->{try(var s=server.accept()){socket=s;accepts.incrementAndGet();accepted.countDown();s.setSoTimeout(3000);var bytes=new ByteArrayOutputStream();int b;while((b=s.getInputStream().read())!=-1){bytes.write(b);if(bytes.size()>=4&&bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;if(bytes.size()>32768)break;}request=bytes.toString(StandardCharsets.US_ASCII);read.countDown();if(response!=null){s.getOutputStream().write(response);s.getOutputStream().flush();}if(response==null||holdAfterResponse){try{s.getInputStream().read();}catch(IOException ignored){}}}catch(IOException e){read.countDown();}});}
        URI origin(){return URI.create("http://127.0.0.1:"+server.getLocalPort());}
        String request()throws Exception{read.await(4,TimeUnit.SECONDS);return request;}
        public void close()throws Exception {server.close();if(socket!=null)socket.close();thread.join(4000);assertThat(thread.isAlive()).isFalse();System.out.println("O1C_RAW_PEER_CLOSED="+accepts.get());}
    }
}
