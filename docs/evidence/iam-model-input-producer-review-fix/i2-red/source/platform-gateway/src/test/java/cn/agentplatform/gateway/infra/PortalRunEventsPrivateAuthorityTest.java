package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.application.RunObservationEventsMetadataTest.Fixture;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static cn.agentplatform.gateway.infra.PortalRunEventsPrivateHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real original Opening/G/child; mechanical barriers only schedule actual lifecycle boundaries. */
public class PortalRunEventsPrivateAuthorityTest {
    @Test void cancelledTransferClosesItsLateGWithoutSendingChildOrHttp()throws Exception {child("late-g");}
    @Test void consumeRequiresCompletedOriginalQAndIsSingleUse()throws Exception {child("admission");}
    @Test void sameAuthorityMustImplementBothInterfacesAndRejectForeignHolder()throws Exception {child("authority");}
    @Test void realChildAndGAreCheckedAtHandshakeAndActualWriter()throws Exception {child("proof");}
    @Test void revokedGInterruptsActualReadAndDeliversNoLaterPacket()throws Exception {child("revoked-read");}
    @Test void stuckCallbacksAndBorrowLeaksKeepTwoOwnersAndRejectThird()throws Exception {child("retention");}
    @Test void actualPhysicalCloseMustFinishBeforeEitherOwnerIsReleased()throws Exception {child("physical-close");}
    @Test void shortGDeadlineInterruptsTheActualInput()throws Exception {child("short-g");}
    @FunctionalInterface interface CheckedCondition {boolean get()throws Exception;}
    static void until(CheckedCondition condition,long ms)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(!condition.get()){if(System.nanoTime()-end>=0)throw new AssertionError("bounded condition deadline");Thread.sleep(5);}}
    static CurrentAuthorityClient adapter(Fixture f,InvocationHandler handler,boolean both){return (CurrentAuthorityClient)Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),both?new Class<?>[]{CurrentAuthorityClient.class,ResponseAuthorityClient.class}:new Class<?>[]{CurrentAuthorityClient.class},handler);}
    static Object invoke(Fixture f,Method method,Object[] args)throws Throwable {try{return method.invoke(f.gateway,args);}catch(InvocationTargetException e){throw e.getCause();}}
    static void lateG(Path run)throws Exception {
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run);var worker=Executors.newSingleThreadExecutor()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var actual=new AtomicReference<GatewayPortalEventsResponse>();
            var authority=adapter(f,(proxy,m,args)->{Object result=invoke(f,m,args);if(m.getName().equals("openGatewayPortalEvents")){actual.set((GatewayPortalEventsResponse)result);entered.countDown();assertTrue(release.await(6,TimeUnit.SECONDS));}return result;},true);
            var s=new PortalRunEventsHttp(tls).begin(authority,f.source,RUN,7);s.lookupMetadata();Future<?> future=worker.submit(()->s.consumePrivate(packet->fail("late transfer cannot deliver")));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));s.close();assertFalse(s.awaitClosed(Duration.ZERO));assertEquals(4260096L,PortalEventsPacketBudget.SHARED.reservedBytes());release.countDown();assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertEquals(0,p.eBytes.get());assertFalse(f.iam.observedOperations().contains("portal_events_child_exchange"));
                assertThrows(PlatformException.class,()->actual.get().currentCapture());
                Files.writeString(run.resolve("summary.properties"),"lateGClosedBeforeReturn=true\nchildExchanges=0\neRequestBytes=0\n");
            }finally{release.countDown();closed(s);}
        }
    }

    static void admission(Path run)throws Exception {
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run)) {
            var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);
            try {assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));assertEquals(3211392L,PortalEventsPacketBudget.SHARED.reservedBytes());s.lookupMetadata();s.consumePrivate(packet->{});assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));assertEquals(1,java.util.Collections.frequency(f.iam.observedOperations(),"portal_events_child_exchange"));}finally{closed(s);}
        }
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run);var worker=Executors.newSingleThreadExecutor()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);
            s.beforeOutputForTest(()->{entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}});
            try {var q=worker.submit(s::lookupMetadata);assertTrue(entered.await(1,TimeUnit.SECONDS));assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));assertNull(field(s,"privateFee"));release.countDown();q.get(2,TimeUnit.SECONDS);s.consumePrivate(packet->{});}finally{release.countDown();closed(s);}
        }
        for(var m:PortalRunEventsHttp.Session.class.getMethods())assertNotEquals("consumePrivate",m.getName());
        Files.writeString(run.resolve("summary.properties"),"beforeQAndQRunningDenied=true\nsingleUse=true\npublicPrivateConsumerAbsent=true\n");
    }
    static void authority(Path run)throws Exception {
        try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run)) {
            var onlyCurrent=adapter(f,(proxy,m,args)->invoke(f,m,args),false);var s=new PortalRunEventsHttp(tls).begin(onlyCurrent,f.source,RUN,7);
            try{s.lookupMetadata();assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));assertEquals(0,p.eBytes.get());assertFalse(f.iam.observedOperations().contains("portal_gateway_events_open"));assertNull(field(s,"saved"));assertNull(field(s,"originalPortalSource"));}finally{closed(s);}
        }
        try(var f=new Fixture();var foreign=new Fixture();var p=new Peer(response(200,MIME,packets()),"normal");var tls=tls(p,run)){
            var otherOpening=foreign.begin();var other=foreign.gateway.openGatewayPortalEvents(otherOpening,RunObservationSelectors.events(RUN,"a".repeat(64)),()->{});
            var mixed=adapter(f,(proxy,m,args)->m.getName().equals("openGatewayPortalEvents")?other:invoke(f,m,args),true);var s=new PortalRunEventsHttp(tls).begin(mixed,f.source,RUN,7);
            try{s.lookupMetadata();assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));assertEquals(0,p.eBytes.get());assertFalse(f.iam.observedOperations().contains("portal_events_child_exchange"));}finally{closed(s);otherOpening.close();other.close();}
        }
        Files.writeString(run.resolve("summary.properties"),"sameInstanceDualInterfaceRequired=true\nforeignHolderDeniedByRealCommonAssociation=true\neRequestBytes=0\n");
    }
    static void proof(Path run)throws Exception {
        for(String seam:new String[]{"handshake","writer"})for(boolean revoke:new boolean[]{false,true}){
            try(var f=new Fixture();var p=new Peer(response(200,MIME,packets()),seam.equals("handshake")?"handshake":"normal");var tls=tls(p,run);var worker=Executors.newSingleThreadExecutor()){
                set(f.iam,"childTicketSeconds",2L);var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);s.lookupMetadata();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
                if(seam.equals("writer"))s.beforeOutputForTest(()->{entered.countDown();try{assertTrue(release.await(4,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}});
                var future=worker.submit(()->s.consumePrivate(packet->fail("invalid proof callback")));
                try {assertTrue((seam.equals("writer")?entered:p.eConnected).await(2,TimeUnit.SECONDS));
                    if(revoke){set(f.iam,"gatewayEventsRevoked",true);var g=(GatewayPortalEventsResponse)field(s,"gatewayResponse");assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(g));}else f.iam.advanceClock(Duration.ofSeconds(3));
                    release.countDown();p.release.countDown();assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertEquals(0,p.eBytes.get());
                    Files.writeString(run.resolve(seam+"-"+(revoke?"revoked":"expired")+".properties"),"realProof=true\neRequestBytes="+p.eBytes.get()+"\n");
                }finally{release.countDown();p.release.countDown();closed(s);}
            }
        }
    }
    static void revokedRead(Path run)throws Exception {
        byte[] hello=PortalEventsPacketDecoderTest.hello();byte[] head=utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: 100000\r\n\r\n");
        try(var f=new Fixture();var p=new Peer(PortalEventsPacketDecoderTest.join(head,hello),"hold");var tls=tls(p,run);var worker=Executors.newSingleThreadExecutor()){
            var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);s.lookupMetadata();var first=new CountDownLatch(1);var count=new AtomicInteger();var future=worker.submit(()->s.consumePrivate(packet->{count.incrementAndGet();first.countDown();}));
            try{assertTrue(first.await(2,TimeUnit.SECONDS));until(()->PortalEventsPacketBudget.SHARED.reservedBytes()>67108864L,1000);set(f.iam,"gatewayEventsRevoked",true);var g=(GatewayPortalEventsResponse)field(s,"gatewayResponse");long start=System.nanoTime();assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(g));assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertEquals(1,count.get());Files.writeString(run.resolve("summary.properties"),"callbacks=1\nrevokedWhileActualRead=true\nexitMillis="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+"\n");}finally{p.release.countDown();closed(s);}
        }
    }
    static void retention(Path run)throws Exception {
        for(boolean leak:new boolean[]{false,true})try(var f=new Fixture();var a=new Peer(response(200,MIME,packets()),"normal");var b=new Peer(response(200,MIME,packets()),"normal");var ta=tls(a,run);var tb=tls(b,run);var workers=Executors.newFixedThreadPool(2)){
            var sa=new PortalRunEventsHttp(ta).begin(f.gateway,f.source,RUN,7);sa.lookupMetadata();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var borrows=new java.util.concurrent.ConcurrentLinkedQueue<InputStream>();var calls=new AtomicInteger();
            PortalRunEventsHttp.Session.PacketConsumer consumer=packet->{calls.incrementAndGet();if(leak)borrows.add(packet.metadata());entered.countDown();if(!leak)try{assertTrue(release.await(12,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IOException("callback interrupted");}};
            var fa=workers.submit(()->sa.consumePrivate(consumer));var sb=new PortalRunEventsHttp(tb).begin(f.gateway,f.source,RUN,7);sb.lookupMetadata();var fb=workers.submit(()->sb.consumePrivate(consumer));
            try{assertTrue(entered.await(3,TimeUnit.SECONDS));if(leak){assertThrows(ExecutionException.class,()->fa.get(3,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->fb.get(3,TimeUnit.SECONDS));}sa.close();sb.close();assertFalse(sa.awaitClosed(Duration.ofMillis(10)));assertFalse(sb.awaitClosed(Duration.ofMillis(10)));assertEquals(2,PortalEventsPacketBudget.SHARED.owners());long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>8520192L);int before=f.iam.authorityCalls();assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,RUN,7));assertEquals(before,f.iam.authorityCalls());assertEquals(2,calls.get());
                release.countDown();for(var stream:borrows)stream.close();if(!leak){assertThrows(ExecutionException.class,()->fa.get(3,TimeUnit.SECONDS));assertThrows(ExecutionException.class,()->fb.get(3,TimeUnit.SECONDS));}assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));sa.close();sb.close();assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());Files.writeString(run.resolve((leak?"borrow":"callback")+".properties"),"retainedBytes="+held+"\ncallbacks=2\nthirdDenied=true\nreleasedExactlyOnce=true\n");
            }finally{release.countDown();for(var stream:borrows)stream.close();sa.close();sb.close();assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));}
        }
    }
    static void physicalClose(Path run)throws Exception {
        try(var f=new Fixture();var a=new Peer(response(200,MIME,packets()),"normal");var b=new Peer(response(200,MIME,packets()),"normal");var ta=tls(a,run);var tb=tls(b,run)){
            var sa=new PortalRunEventsHttp(ta).begin(f.gateway,f.source,RUN,7);sa.lookupMetadata();sa.consumePrivate(packet->{});var sb=new PortalRunEventsHttp(tb).begin(f.gateway,f.source,RUN,7);sb.lookupMetadata();sb.consumePrivate(packet->{});
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);int i=0;
            for(var session:new PortalRunEventsHttp.Session[]{sa,sb}){var handle=(PortalEventsTlsConnection)(i++==0?ta:tb).acquire(System.nanoTime()+TimeUnit.SECONDS.toNanos(15));handle.installRawForTest(new java.net.Socket(){@Override public void close()throws IOException{entered.countDown();try{if(!release.await(12,TimeUnit.SECONDS))throw new IOException("physical close gate deadline");}catch(InterruptedException e){throw new IOException("physical close interrupted");}super.close();}});set(session,"handle",handle);set(session,"transportClean",false);}
            try{sa.close();sb.close();assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(sa.awaitClosed(Duration.ofMillis(10)));assertFalse(sb.awaitClosed(Duration.ofMillis(10)));assertEquals(8520192L,PortalEventsPacketBudget.SHARED.reservedBytes());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,RUN,7));release.countDown();assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());Files.writeString(run.resolve("summary.properties"),"mechanicalSocketCloseOnly=true\ntwoOwnersRetained=8520192\nthirdDenied=true\nactualCloseReturnedBeforeRelease=true\n");}finally{release.countDown();sa.close();sb.close();assertTrue(sa.awaitClosed(Duration.ofSeconds(3)));assertTrue(sb.awaitClosed(Duration.ofSeconds(3)));}
        }
    }
    static void shortG(Path run)throws Exception {
        byte[] head=utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: 100000\r\n\r\n");
        try(var f=new Fixture();var p=new Peer(head,"hold");var tls=tls(p,run)){
            set(f.iam,"gatewayEventsOperationSeconds",3L);var s=new PortalRunEventsHttp(tls).begin(f.gateway,f.source,RUN,7);s.lookupMetadata();long start=System.nanoTime();try{assertThrows(PlatformException.class,()->s.consumePrivate(packet->fail()));long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertTrue(ms>=1500&&ms<4500,"actual short G "+ms);Files.writeString(run.resolve("summary.properties"),"configuredGOperationSeconds=3\nactualMillis="+ms+"\ncallbacks=0\n");}finally{p.release.countDown();closed(s);}
        }
    }
    static void run(String group,Path run)throws Exception {switch(group){case "late-g"->lateG(run);case "admission"->admission(run);case "authority"->authority(run);case "proof"->proof(run);case "revoked-read"->revokedRead(run);case "retention"->retention(run);case "physical-close"->physicalClose(run);case "short-g"->shortG(run);default->throw new IllegalArgumentException(group);}}
}
