package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.PortalRunEventsService;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

class PortalRunEventsLifecycleTest {
    static void await(BooleanSupplier condition){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("BOUNDED_WAIT");try{Thread.sleep(10);}catch(InterruptedException e){throw new AssertionError(e);}}}
    static void entered(PortalRunEventsHttpTest.Hooks h)throws Exception{assertThat(h.entered.await(4,TimeUnit.SECONDS)).isTrue();}
    static ThreadPoolExecutor executor(PortalRunEventsService service)throws Exception{var field=PortalRunEventsService.class.getDeclaredField("writers");field.setAccessible(true);return (ThreadPoolExecutor)field.get(service);}
    // Read the actual C-owned holder for deterministic actual-client renewals. No common
    // evidence/capture constructor or foreign service/JAR/target reflection is involved.
    static PortalEventsResponse heldResponse(PortalRunEventsService service){try{var field=PortalRunEventsService.class.getDeclaredField("owners");field.setAccessible(true);var owner=((Set<?>)field.get(service)).iterator().next();var response=owner.getClass().getDeclaredField("response");response.setAccessible(true);return (PortalEventsResponse)response.get(owner);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    static Future<HttpResponse<String>> request(ExecutorService caller,ServletWebServerApplicationContext server,String run,String ticket){return caller.submit(()->PortalRunEventsHttpTest.request(server,run,ticket,""));}
    static int availableFrames(ChunkBus bus)throws Exception{var leases=new ArrayList<AutoCloseable>();try{AutoCloseable l;while((l=bus.reservePortalFrame())!=null)leases.add(l);return leases.size();}finally{for(var l:leases)l.close();}}
    @Test void invalidatedBetweenFreshReadAndConsumeEntryRetainsBothOwnersThroughComplete()throws Exception{guardBoundaryOwners(false);}
    @Test void invalidatedAfterCallbackBeforeFinalGuardRetainsBothOwnersThroughComplete()throws Exception{guardBoundaryOwners(true);}
    static void guardBoundaryOwners(boolean afterCallback)throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(2)){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks(2);h.block="complete";h.secondRelease=new CountDownLatch(1);PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var service=server.getBean(PortalRunEventsService.class);var actual=server.getBean(RunEventReader.class);var timed=org.mockito.Mockito.spy(actual);var cutoffs=new AtomicInteger();
                // Mockito changes only the call timing. Every RR, mint, borrow, guard and
                // resource finally delegates to the actual reader and its actual result.
                org.mockito.Mockito.doAnswer(call->{
                    var result=(RunEventReader.WindowEmission)call.callRealMethod();
                    if(!Thread.currentThread().getName().equals("portal-events-writer"))return result;
                    var holder=(PortalEventsResponse)call.getArgument(0);
                    if(!afterCallback){cutoffs.incrementAndGet();holder.close();return result;}
                    return org.mockito.Mockito.mock(RunEventReader.WindowEmission.class,invoke->{
                        var args=invoke.getArguments().clone();
                        if(invoke.getMethod().getName().equals("consume")){
                            @SuppressWarnings("unchecked") var action=(java.util.function.Consumer<RunEventReader.WindowBorrow>)args[0];
                            args[0]=(java.util.function.Consumer<RunEventReader.WindowBorrow>)view->{action.accept(view);cutoffs.incrementAndGet();holder.close();};
                        }
                        try{return invoke.getMethod().invoke(result,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                    });
                }).when(timed).readWindow(org.mockito.ArgumentMatchers.any(PortalEventsResponse.class),org.mockito.ArgumentMatchers.anyLong());
                var readerField=PortalRunEventsService.class.getDeclaredField("reader");readerField.setAccessible(true);readerField.set(service,timed);
                var first=callers.submit(()->PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"?Last-Event-ID=1"));var second=callers.submit(()->PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"?Last-Event-ID=1"));
                entered(h);assertThat(cutoffs).hasValue(2);var bus=server.getBean(ChunkBus.class);service.close();
                assertThat(catchThrowable(()->{try(var unexpected=actual.readNext(fresh,0).orElseThrow()) {}})).isInstanceOf(RunEventReader.CapacityFailure.class);
                assertThatThrownBy(()->bus.subscribe(100)).isInstanceOf(PlatformException.class);assertThat(availableFrames(bus)).isEqualTo(60);assertThat(h.exits).hasValue(0);
                if(!afterCallback)assertThat(h.writes).hasValue(0);
                h.release.countDown();await(()->h.exits.get()==1);await(()->{try{return availableFrames(bus)==61;}catch(Exception e){throw new AssertionError(e);}});
                try(var one=actual.readNext(fresh,0).orElseThrow()){assertThatThrownBy(()->actual.readNext(fresh,0)).isInstanceOf(RunEventReader.CapacityFailure.class);}
                try(var one=bus.subscribe(100)){assertThatThrownBy(()->bus.subscribe(101)).isInstanceOf(PlatformException.class);}
                h.secondRelease.countDown();first.get(4,TimeUnit.SECONDS);second.get(4,TimeUnit.SECONDS);await(()->h.exits.get()==2);await(()->{try{return availableFrames(bus)==62;}catch(Exception e){throw new AssertionError(e);}});
                try(var one=actual.readNext(fresh,0).orElseThrow();var two=actual.readNext(fresh,0).orElseThrow();var slotOne=bus.subscribe(100);var slotTwo=bus.subscribe(101)){}
            }finally{h.release.countDown();h.secondRelease.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void expiredOriginalTicketHealthyRenewalAfterRealGetterReturnsRereadsBeforeFirstWrite()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newSingleThreadExecutor()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);f.finish(b.runId());PortalRunEventsAuthorityFixture.shortEventsTicket(f.auth);
            String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();h.block="get";PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                f.source.start();var future=request(callers,server,b.publicId(),ticket);entered(h);int checks=Collections.frequency(f.auth.iam.observedOperations(),"portal_events_check");
                f.auth.advance(3);f.auth.renew(heldResponse(server.getBean(PortalRunEventsService.class)));assertThat(Collections.frequency(f.auth.iam.observedOperations(),"portal_events_check")).isGreaterThan(checks);
                h.beforeWrite=()->assertThat(f.source.getters.stream().filter(k->k.equals("state_text")).count()).isGreaterThanOrEqualTo(3);
                assertThat(h.writes).hasValue(0);h.release.countDown();var result=future.get(5,TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).contains("event:C_FIRST","event:RUN_FINISHED");assertThat(h.exits).hasValue(1);
                assertThat(f.auth.iam.observedOperations()).contains("portal_events_check");
            }finally{h.release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void renewedGenerationAfterStatusFlushCannotSendBusinessEventUntilFreshWindow()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);f.finish(b.runId());String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();var once=new AtomicBoolean();PortalRunEventsHttpTest.hooks=h;
            h.beforeWrite=()->{if(h.writes.get()>0)assertThat(f.source.getters.stream().filter(k->k.equals("state_text")).count()).isGreaterThanOrEqualTo(3);};
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                h.afterFlush=()->{if(once.compareAndSet(false,true)){f.auth.advance(3);f.auth.renew(heldResponse(server.getBean(PortalRunEventsService.class)));}};
                f.source.start();var result=PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"");assertThat(result.body()).contains("event:C_FIRST","event:RUN_FINISHED");assertThat(h.exits).hasValue(1);
            }finally{PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void hardBoundDuringActualGetterProducesNoBusinessWrite()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newSingleThreadExecutor()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();h.block="get";PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                var future=request(callers,server,b.publicId(),ticket);entered(h);var response=heldResponse(server.getBean(PortalRunEventsService.class));var first=response.currentCapture();
                for(int i=0;i<18;i++){f.auth.advance(3);f.auth.renew(response);response.currentCapture();}
                assertThat(response.isSuperseded(first)).isTrue();f.auth.advance(2);assertThatThrownBy(response::currentCapture).isInstanceOf(PlatformException.class);h.release.countDown();future.get(4,TimeUnit.SECONDS);
                assertThat(h.writes).hasValue(0);assertThat(h.exits).hasValue(1);
                assertThat(f.db.queryForObject("select status from engine.run where id=?",String.class,b.runId())).isEqualTo("PENDING");
            }finally{h.release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void timeoutOnCompleteAndShutdownKeepTwoActualGetterOwnersUntilEachReturns()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(2)){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks(2);h.block="get";h.secondRelease=new CountDownLatch(1);h.timeout=250;PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var bus=server.getBean(ChunkBus.class);var first=request(callers,server,b.publicId(),ticket);var second=request(callers,server,b.publicId(),ticket);entered(h);
                assertThatThrownBy(()->server.getBean(RunEventReader.class).readNext(fresh,0)).isInstanceOfSatisfying(RunEventReader.CapacityFailure.class,e->assertThat(e.dimension()).isEqualTo("WORKSPACE"));
                assertThatThrownBy(()->bus.subscribe(100)).isInstanceOf(PlatformException.class);assertThat(availableFrames(bus)).isEqualTo(60);
                await(()->h.timeouts.get()>0&&h.notifications.get()>0);server.getBean(PortalRunEventsService.class).close();
                assertThatThrownBy(()->server.getBean(RunEventReader.class).readNext(fresh,0)).isInstanceOf(RunEventReader.CapacityFailure.class);assertThat(availableFrames(bus)).isEqualTo(60);assertThat(h.writes).hasValue(0);
                h.release.countDown();await(()->h.exits.get()>=1);
                try(var one=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow()){assertThatThrownBy(()->server.getBean(RunEventReader.class).readNext(fresh,0)).isInstanceOf(RunEventReader.CapacityFailure.class);}
                assertThat(availableFrames(bus)).isEqualTo(61);
                h.secondRelease.countDown();first.get(4,TimeUnit.SECONDS);second.get(4,TimeUnit.SECONDS);await(()->h.exits.get()==2);
                assertThat(availableFrames(bus)).isEqualTo(62);try(var one=bus.subscribe(100);var two=bus.subscribe(101)){}
            }finally{h.release.countDown();h.secondRelease.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void actualCompleteCallRetainsCurrentReaderFrameAndSlotThroughItsFinally()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newSingleThreadExecutor()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);f.finish(b.runId());String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();h.block="complete";PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var future=request(callers,server,b.publicId(),ticket);entered(h);var bus=server.getBean(ChunkBus.class);server.getBean(PortalRunEventsService.class).close();
                try(var one=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow()){assertThatThrownBy(()->server.getBean(RunEventReader.class).readNext(fresh,0)).isInstanceOf(RunEventReader.CapacityFailure.class);}
                try(var one=bus.subscribe(100)){assertThatThrownBy(()->bus.subscribe(101)).isInstanceOf(PlatformException.class);}
                assertThat(availableFrames(bus)).isEqualTo(61);assertThat(h.exits).hasValue(0);h.release.countDown();future.get(4,TimeUnit.SECONDS);await(()->h.exits.get()==1);assertThat(availableFrames(bus)).isEqualTo(62);
            }finally{h.release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void queuedNeverRunOwnersHoldNoReaderOrFrameAndRealSubmissionRejectionReleasesSlot()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(3)){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();PortalRunEventsHttpTest.hooks=h;var workers=new CountDownLatch(2);var release=new CountDownLatch(1);
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var pool=executor(server.getBean(PortalRunEventsService.class));for(int i=0;i<2;i++)pool.execute(()->{workers.countDown();try{release.await();}catch(InterruptedException ignored){}});assertThat(workers.await(2,TimeUnit.SECONDS)).isTrue();
                var first=request(callers,server,b.publicId(),ticket);var second=request(callers,server,b.publicId(),ticket);await(()->pool.getQueue().size()==2);
                try(var one=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow();var two=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow()){}
                var bus=server.getBean(ChunkBus.class);assertThat(availableFrames(bus)).isEqualTo(62);h.contexts.getFirst().complete();await(()->h.notifications.get()>=1);
                var rejected=PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"");assertThat(rejected.statusCode()).isEqualTo(429);assertThat(rejected.body()).contains("STREAM_CAPACITY");assertThat(availableFrames(bus)).isEqualTo(62);
                h.contexts.get(1).complete();first.get(4,TimeUnit.SECONDS);second.get(4,TimeUnit.SECONDS);release.countDown();await(()->pool.getQueue().isEmpty());assertThat(h.writes).hasValue(1); // Only the bounded rejection envelope; neither queued task ran.
                try(var one=bus.subscribe(100);var two=bus.subscribe(101)){}
            }finally{release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void actualWriteAndFlushBlockingKeepResourcesAfterStopAndSendNoFollowingEvent()throws Exception{
        for(String stage:List.of("write","flush"))try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newSingleThreadExecutor()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);f.finish(b.runId());String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();h.block=stage;PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class);var fresh=f.open(b,0)){
                var future=request(callers,server,b.publicId(),ticket);entered(h);var bus=server.getBean(ChunkBus.class);server.getBean(PortalRunEventsService.class).close();
                try(var one=server.getBean(RunEventReader.class).readNext(fresh,0).orElseThrow()){assertThatThrownBy(()->server.getBean(RunEventReader.class).readNext(fresh,0)).isInstanceOf(RunEventReader.CapacityFailure.class);}
                try(var one=bus.subscribe(100)){assertThatThrownBy(()->bus.subscribe(101)).isInstanceOf(PlatformException.class);}
                assertThat(availableFrames(bus)).isEqualTo(61);h.release.countDown();var result=future.get(4,TimeUnit.SECONDS);assertThat(result.body()).doesNotContain("event:C_FIRST","event:RUN_FINISHED");await(()->h.exits.get()==1);assertThat(availableFrames(bus)).isEqualTo(62);
            }finally{h.release.countDown();PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void twoActivePollersObserveNewTerminalWhileTwoQueuedOwnersWaitWithoutReaderStarvation()throws Exception{
        try(var f=new PortalRunEventWindowPostgresTest.Fixture();var callers=Executors.newFixedThreadPool(4)){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());
            var h=new PortalRunEventsHttpTest.Hooks();PortalRunEventsHttpTest.hooks=h;PortalRunEventsHttpTest.streamSlots=4;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                var tasks=new ArrayList<Future<HttpResponse<String>>>();var pool=executor(server.getBean(PortalRunEventsService.class));
                for(int i=0;i<2;i++)tasks.add(callers.submit(()->PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"?Last-Event-ID=1")));
                await(()->h.flushes.get()>=2);
                for(int target=1;target<=2;target++){
                    boolean queued=false;for(int attempt=0;attempt<5&&!queued;attempt++){
                        var next=callers.submit(()->PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"?Last-Event-ID=1"));int expected=target;
                        await(()->pool.getQueue().size()>=expected||next.isDone());
                        if(next.isDone())assertThat(next.get().statusCode()).isEqualTo(429);
                        else{tasks.add(next);queued=true;}
                    }
                    assertThat(queued).isTrue();
                }
                assertThat(pool.getQueue()).hasSize(2);f.finish(b.runId());
                for(var task:tasks){var result=task.get(5,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).contains("event:RUN_FINISHED").doesNotContain("event:C_FIRST");}
                await(()->pool.getQueue().isEmpty());assertThat(availableFrames(server.getBean(ChunkBus.class))).isEqualTo(62);
            }finally{PortalRunEventsHttpTest.streamSlots=2;PortalRunEventsHttpTest.hooks=null;}
        }
    }
    @Test void supersededRetriesAreBoundedPerRoundAndSuccessfulCursorProgressStartsNextRound()throws Exception{
        for(boolean exhaust:List.of(false,true))try(var f=new PortalRunEventWindowPostgresTest.Fixture()){
            PortalRunEventsHttpTest.fixture=f;var b=f.seed(0);if(!exhaust){f.append(b.runId(),"C_SECOND",Map.of("n",2));f.append(b.runId(),"C_THIRD",Map.of("n",3));}f.finish(b.runId());
            String ticket=PortalRunEventsAuthorityFixture.ticket(f.auth,b.owner(),b.publicId(),b.snapshotSha());var h=new PortalRunEventsHttpTest.Hooks();PortalRunEventsHttpTest.hooks=h;
            try(var server=PortalRunEventsHttpTest.start(PortalRunEventsHttpTest.Receiver.class)){
                h.afterFlush=()->{if(exhaust||Set.of(1,4,7).contains(h.flushes.get())){f.auth.advance(3);f.auth.renew(heldResponse(server.getBean(PortalRunEventsService.class)));}};
                var result=PortalRunEventsHttpTest.request(server,b.publicId(),ticket,"");
                if(exhaust){assertThat(h.flushes).hasValue(3);assertThat(result.body()).doesNotContain("event:C_FIRST","event:RUN_FINISHED");}
                else{assertThat(h.flushes).hasValue(8);assertThat(result.body()).contains("event:C_FIRST","event:C_SECOND","event:C_THIRD","event:RUN_FINISHED");}
                assertThat(availableFrames(server.getBean(ChunkBus.class))).isEqualTo(62);
            }finally{PortalRunEventsHttpTest.hooks=null;}
        }
    }
}
