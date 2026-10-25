package cn.agentplatform.gateway.infra;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.agentplatform.gateway.infra.PortalEventsPacketDecoderTest.*;
import static org.assertj.core.api.Assertions.*;

class PortalEventsPacketBudgetTest {
    @Test void twoOwnersThirdRejectedAndCloseRestoresSlots(){
        var b=PortalEventsPacketBudget.SHARED;try(var a=b.acquire();var c=b.acquire()){
            assertThat(b.owners()).isEqualTo(2);assertThatThrownBy(b::acquire).isInstanceOf(IllegalStateException.class);
            assertThat(b.reservedBytes()).isEqualTo(a.reservedBytes()+c.reservedBytes());
        }assertThat(b.owners()).isZero();assertThat(b.reservedBytes()).isZero();
    }
    @Test void oneOwnerHasOneDecoderForItsEntireLifetime()throws Exception{
        try(var o=PortalEventsPacketBudget.SHARED.acquire()){
            var d=new PortalEventsPacketDecoder(o);assertThatThrownBy(()->new PortalEventsPacketDecoder(o)).isInstanceOf(IllegalStateException.class);d.close();assertThatThrownBy(()->new PortalEventsPacketDecoder(o)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void packetMustCloseBeforeNextReadAndBorrowRejectsClose()throws Exception{
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            var in=new ByteArrayInputStream(join(hello(),packet(2,1,window(false,"{}"),"")));var p=d.readNext(in).orElseThrow();long live=o.reservedBytes();
            assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IllegalStateException.class);
            var borrow=p.metadata();live=o.reservedBytes();assertThatThrownBy(p::close).isInstanceOf(IllegalStateException.class);assertThatThrownBy(d::close).isInstanceOf(IllegalStateException.class);assertThatThrownBy(o::close).isInstanceOf(IllegalStateException.class);assertThat(o.reservedBytes()).isEqualTo(live);
            assertThat(borrow.read()).isEqualTo('{');borrow.close();assertThatThrownBy(borrow::read).isInstanceOf(IOException.class);p.close();p.close();assertThatThrownBy(p::metadata).isInstanceOf(IllegalStateException.class);assertThatThrownBy(p::header).isInstanceOf(IllegalStateException.class);
            d.readNext(in).orElseThrow().close();
        }assertThat(PortalEventsPacketBudget.SHARED.reservedBytes()).isZero();
    }
    @Test void inputIsBoundOnceAndClosingDecoderDoesNotCloseUpstream()throws Exception{
        AtomicBoolean closed=new AtomicBoolean();var in=new ByteArrayInputStream(hello()){@Override public void close(){closed.set(true);}};
        try(var o=PortalEventsPacketBudget.SHARED.acquire()){
            var d=new PortalEventsPacketDecoder(o);d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(new ByteArrayInputStream(hello()))).isInstanceOf(IllegalStateException.class);d.close();assertThat(closed).isFalse();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void blockedInputAndInterruptDoNotReleaseUntilRealExit()throws Exception{
        var b=PortalEventsPacketBudget.SHARED;var o=b.acquire();var d=new PortalEventsPacketDecoder(o);long base=o.reservedBytes();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Throwable> failed=new AtomicReference<>();
        InputStream in=new InputStream(){@Override public int read()throws IOException{entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}throw new IOException("physical read exited");}};
        Thread t=new Thread(()->{try{d.readNext(in);}catch(Throwable e){failed.set(e);}});
        try{t.start();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();long active=o.reservedBytes();assertThat(active).isGreaterThanOrEqualTo(base+PortalEventsPacketBudget.SCAN_BYTES);t.interrupt();assertThatThrownBy(d::close).isInstanceOf(IllegalStateException.class);assertThatThrownBy(o::close).isInstanceOf(IllegalStateException.class);assertThat(o.reservedBytes()).isEqualTo(active);}
        finally{release.countDown();t.join(5000);assertThat(t.isAlive()).isFalse();}
        assertThat(failed.get()).isInstanceOf(IOException.class);assertThat(o.reservedBytes()).isEqualTo(base);d.close();o.close();assertThat(b.reservedBytes()).isZero();
    }
    @Test void realWireAllocationIsPrechargedAndNoNextPacketIsRead()throws Exception{
        byte[] h=hello(),both=join(h,packet(2,1,window(false,"{}"),""));AtomicInteger consumed=new AtomicInteger();AtomicBoolean charged=new AtomicBoolean();
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            long base=o.reservedBytes();InputStream in=new ByteArrayInputStream(both){@Override public synchronized int read(){charged.set(o.reservedBytes()>=base+PortalEventsPacketBudget.SCAN_BYTES);int x=super.read();if(x>=0)consumed.incrementAndGet();return x;}@Override public synchronized int read(byte[] b,int off,int n){assertThat(n).isLessThanOrEqualTo(8192);int x=super.read(b,off,n);if(x>0)consumed.addAndGet(x);return x;}};
            var p=d.readNext(in).orElseThrow();assertThat(charged).isTrue();assertThat(consumed).hasValue(h.length);
            Field wire=p.getClass().getDeclaredField("wire");wire.setAccessible(true);byte[] backing=(byte[])wire.get(p);assertThat(backing).hasSize(h.length);assertThat(o.reservedBytes()).isGreaterThanOrEqualTo(base+backing.length+24);System.out.println("F1_WIRE_ALLOCATION bytes="+backing.length+" charged="+(o.reservedBytes()-base)+" prechargedScan="+charged.get());p.close();assertThat(wire.get(p)).isNull();assertThat(o.reservedBytes()).isEqualTo(base);d.readNext(in).orElseThrow().close();
        }
    }
    @Test void invalidHugeLengthNeverAllocatesOrReadsDeclaredBody()throws Exception{
        byte[] h=hello();ByteBuffer.wrap(h).putInt(20,-1);AtomicInteger consumed=new AtomicInteger();
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            long base=o.reservedBytes();var in=new ByteArrayInputStream(h){@Override public synchronized int read(){int n=super.read();if(n>=0)consumed.incrementAndGet();return n;}@Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,len);if(n>0)consumed.addAndGet(n);return n;}};
            assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);assertThat(consumed).hasValue(24);assertThat(o.reservedBytes()).isEqualTo(base);
        }
    }
    @Test void ownerLimitIsExactGlobalLedgerIsSharedAndFailedAllocationRollsBack()throws Exception{
        var b=PortalEventsPacketBudget.SHARED;try(var a=b.acquire();var c=b.acquire()){
            long base=a.reservedBytes();long available=PortalEventsPacketBudget.OWNER_BYTES-base-128;
            try(var one=a.reserve(available);var two=c.reserve(available)){
                assertThat(a.reservedBytes()).isEqualTo(PortalEventsPacketBudget.OWNER_BYTES);assertThat(b.reservedBytes()).isEqualTo(PortalEventsPacketBudget.GLOBAL_BYTES);
                assertThatThrownBy(()->a.reserve(1)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->c.reserve(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);assertThat(b.reservedBytes()).isEqualTo(PortalEventsPacketBudget.GLOBAL_BYTES);
                assertThatThrownBy(a::close).isInstanceOf(IllegalStateException.class);
            }
            assertThat(a.reservedBytes()).isEqualTo(base);assertThat(c.reservedBytes()).isEqualTo(base);
            try(var block=a.reserve(PortalEventsPacketBudget.OWNER_BYTES-base-128)){
                var decoder=new PortalEventsPacketDecoder(a);AtomicInteger reads=new AtomicInteger();InputStream in=new InputStream(){public int read(){reads.incrementAndGet();return -1;}};
                assertThatThrownBy(()->decoder.readNext(in)).isInstanceOf(IllegalStateException.class);assertThat(reads).hasValue(0);assertThat(a.reservedBytes()).isEqualTo(PortalEventsPacketBudget.OWNER_BYTES);decoder.close();
            }
        }assertThat(b.reservedBytes()).isZero();
    }
    @Test void selectedJacksonProviderActuallyAllocatesBoundedReaderBackingAndNoSharedCaches()throws Exception{
        Method method=PortalEventsPacketDecoder.class.getDeclaredMethod("factory",int.class,int.class,int.class,int.class);method.setAccessible(true);
        var factory=(com.fasterxml.jackson.core.JsonFactory)method.invoke(null,80,262144,1048576,1000);
        assertThat(factory.version().toString()).isEqualTo("2.19.0");
        for(var feature:new com.fasterxml.jackson.core.JsonFactory.Feature[]{com.fasterxml.jackson.core.JsonFactory.Feature.CANONICALIZE_FIELD_NAMES,com.fasterxml.jackson.core.JsonFactory.Feature.INTERN_FIELD_NAMES,com.fasterxml.jackson.core.JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING})assertThat(factory.isEnabled(feature)).isFalse();
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var paid=o.reserve(PortalEventsPacketBudget.SCAN_BYTES);var parser=factory.createParser(new StringReader("{\"x\":\""+"x".repeat(1048576)+"\"}"))){
            assertThat(parser.getClass().getName()).isEqualTo("com.fasterxml.jackson.core.json.ReaderBasedJsonParser");Field input=parser.getClass().getDeclaredField("_inputBuffer");input.setAccessible(true);assertThat((char[])input.get(parser)).hasSize(4000);
            while(parser.nextToken()!=null)if(parser.currentToken()==com.fasterxml.jackson.core.JsonToken.VALUE_STRING){parser.finishToken();assertThat(parser.getTextLength()).isEqualTo(1048576);assertThat(parser.getTextCharacters().length).isGreaterThanOrEqualTo(1048576);System.out.println("F1_PROVIDER_BACKING parser="+parser.getClass().getName()+" inputChars="+((char[])input.get(parser)).length+" aggregatedChars="+parser.getTextCharacters().length+" scanCharge="+PortalEventsPacketBudget.SCAN_BYTES);}
            assertThat(o.reservedBytes()).isGreaterThan(PortalEventsPacketBudget.SCAN_BYTES);
        }
        // This provider probe inspects the actual private production factory; no injectable production hook.
        var mx=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if(mx.isThreadAllocatedMemorySupported()){
            mx.setThreadAllocatedMemoryEnabled(true);String metadata=window(false,"{\"x\":"+quote("x".repeat(1048576))+"}");byte[] body=join(hello(),packet(2,1,metadata,""));
            try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(body);d.readNext(in).orElseThrow().close();long before=mx.getThreadAllocatedBytes(Thread.currentThread().threadId());d.readNext(in).orElseThrow().close();long allocated=mx.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;System.out.println("F1_REAL_SCAN_ALLOCATION threadAllocatedBytes="+allocated+" wireFixtureBytes="+body.length+" proofKind=allocation-observation-not-live-heap-bound");assertThat(allocated).isGreaterThan(body.length);assertThat(allocated).isLessThan(PortalEventsPacketBudget.SCAN_BYTES+body.length);}
        }
    }
}
