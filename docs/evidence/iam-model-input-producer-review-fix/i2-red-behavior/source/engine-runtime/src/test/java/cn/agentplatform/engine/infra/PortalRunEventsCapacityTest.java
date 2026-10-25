package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortalRunEventsCapacityTest {
    @Test void freshQueuedFirstReadCapacityFailureBeforeCommitMapsToGenericHttp413()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=java.util.concurrent.Executors.newSingleThreadExecutor()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();h.block="complete";PortalRunEventsHttpTest.hooks=h;var workers=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var service=server.getBean(cn.agentplatform.engine.application.PortalRunEventsService.class);var pool=PortalRunEventsLifecycleTest.executor(service);
                for(int i=0;i<2;i++)pool.execute(()->{workers.countDown();boolean done=false;while(!done)try{done=release.await(100,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}});
                assertThat(workers.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var future=callers.submit(()->PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"?Last-Event-ID=1"));PortalRunEventsLifecycleTest.await(()->pool.getQueue().size()==1);
                assertThat(h.writes).hasValue(0); // Opening's real successful preflight did not commit.
                f.append(b.runId(),"C_LARGE_FRESH","x".repeat(262143));release.countDown();PortalRunEventsLifecycleTest.entered(h);
                var bus=server.getBean(ChunkBus.class);assertThat(PortalRunEventsLifecycleTest.availableFrames(bus)).isEqualTo(61);
                try(var one=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow();var two=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow()){}
                try(var one=bus.subscribe(100)){assertThatThrownBy(()->bus.subscribe(101)).isInstanceOf(PlatformException.class);}
                assertThat(h.exits).hasValue(0);h.release.countDown();var result=future.get(4,java.util.concurrent.TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(413);assertThat(result.headers().firstValue("content-type").orElse("")).startsWith("application/json");
                assertThat(Json.read(result.body()).path("code").asText()).isEqualTo("EVENT_LIMIT");assertThat(Json.read(result.body()).path("message").asText()).isEqualTo("事件观察不可用");
                assertThat(result.body()).doesNotContain("stream_reset","EVENT_PAYLOAD","有限事件材料容量不足");
                PortalRunEventsLifecycleTest.await(()->h.exits.get()==1);PortalRunEventsLifecycleTest.await(()->{try{return PortalRunEventsLifecycleTest.availableFrames(bus)==62;}catch(Exception e){throw new AssertionError(e);}});
                try(var one=bus.subscribe(100);var two=bus.subscribe(101)){}
            }finally{release.countDown();h.release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void fullFrameExactMaximumAndPlusOneUseSingleExactBacking()throws Exception{
        // Domain event_seq is currently int, so long19 framing is exercised on the actual
        // encoder backing, without fabricating a signed event or exposing a proof constructor.
        Class<?> type=Class.forName("cn.agentplatform.engine.application.PortalRunEventsService$Frame");var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);var frame=constructor.newInstance();
        var ascii=type.getDeclaredMethod("ascii",String.class);ascii.setAccessible(true);var write=type.getDeclaredMethod("write",byte[].class,int.class,int.class);write.setAccessible(true);
        ascii.invoke(frame,"id:"+Long.MAX_VALUE+"\nevent:"+"A".repeat(64)+"\ndata:");var payload=new byte[262144];Arrays.fill(payload,(byte)'x');write.invoke(frame,payload,0,payload.length);ascii.invoke(frame,"\n\n");
        var bytes=type.getDeclaredField("bytes");bytes.setAccessible(true);var count=type.getDeclaredField("count");count.setAccessible(true);
        assertThat(((byte[])bytes.get(frame)).length).isEqualTo(262245);assertThat(count.getInt(frame)).isEqualTo(262245);
        assertThatThrownBy(()->ascii.invoke(frame,"x")).isInstanceOfSatisfying(InvocationTargetException.class,e->assertThat(e.getCause()).isInstanceOfSatisfying(PlatformException.class,p->assertThat(p.code()).isEqualTo("EVENT_LIMIT")));
    }
    @Test void portalFramesAndOldResponseReservationsShareOneSixteenMiBPoolAndSlots()throws Exception{
        try(var bus=new ChunkBus(2,8192)){
            var stopped=new java.util.concurrent.atomic.AtomicInteger();var a=bus.reservePortalSlot(stopped::incrementAndGet);var b=bus.reservePortalSlot(stopped::incrementAndGet);
            assertThatThrownBy(()->bus.subscribe(1)).isInstanceOf(PlatformException.class);a.close();try(var legacy=bus.subscribe(1)){assertThatThrownBy(()->bus.reservePortalSlot(()->{})).isInstanceOf(PlatformException.class);}
            var leases=new ArrayList<AutoCloseable>();try(var legacy=bus.reserveResponse()){
                AutoCloseable lease;while((lease=bus.reservePortalFrame())!=null)leases.add(lease);
                assertThat(leases).hasSize((16*1024*1024-ChunkBus.MAX_EVENT_BYTES)/ChunkBus.FRAME_RESERVATION_BYTES);
                assertThat(bus.reserveResponse()).isNull();
            }finally{for(var lease:leases)lease.close();}
            assertThat(PortalRunEventsLifecycleTest.availableFrames(bus)).isEqualTo(62);bus.close();assertThat(stopped).hasValue(1);b.close();b.close();
        }
    }
    @Test void realHttpUtf8PayloadAtExactBudgetAndPlusOneResetRemainFinite()throws Exception{
        for(int extra:List.of(0,1))try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String type="A".repeat(32);String payload="中"+"x".repeat(262139+extra);
            assertThat(Json.write(payload).getBytes(StandardCharsets.UTF_8)).hasSize(262144+extra);f.append(b.runId(),type,payload);f.finish(b.runId());
            String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                var result=PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"");assertThat(result.statusCode()).isEqualTo(200);
                if(extra==0){assertThat(result.body()).contains("id:2\nevent:"+type+"\ndata:"+Json.write(payload)+"\n\n","event:RUN_FINISHED");}
                else{assertThat(result.body()).contains("event:stream_reset","\"reason\":\"EVENT_LIMIT\"").doesNotContain("event:"+type);}
                assertThat(PortalRunEventsLifecycleTest.availableFrames(server.getBean(ChunkBus.class))).isEqualTo(62);
            }
        }
    }
    @Test void stateCapAndCombinedRootCapFailBeforeStateTextGetter()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){var b=f.seed(0);
            long refs=f.db.queryForObject("select octet_length(version_refs::text) from engine.run where id=?",Long.class,b.runId());
            for(String dimension:List.of("STATE_ROW","ROOT_BYTES"))try(var r=f.open(b,0)){
                f.source.start();f.source.change=(key,value)->key.equals("state_bytes")?1048577L:key.equals("snapshot_bytes")&&dimension.equals("ROOT_BYTES")?8388608L:value;
                if(dimension.equals("ROOT_BYTES"))f.source.change=(key,value)->key.equals("snapshot_bytes")?8388608L-refs:value;
                assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOfSatisfying(RunEventReader.CapacityFailure.class,e->assertThat(e.dimension()).isEqualTo(dimension));
                assertThat(f.source.getters).doesNotContain("state_text");
            }
        }
    }
}
