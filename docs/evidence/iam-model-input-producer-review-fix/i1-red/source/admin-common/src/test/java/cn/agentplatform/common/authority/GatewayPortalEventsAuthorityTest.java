package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Normal authenticated authority/JWKS HTTP. No metadata HTTP, gateway SSE or PG claim. */
class GatewayPortalEventsAuthorityTest {
    static final String RUN=PortalEventsAuthorityProfileTest.RUN, SHA=PortalEventsAuthorityProfileTest.SHA;
    static final SubjectKey OWNER=PortalEventsAuthorityProfileTest.OWNER;
    static final ResourceBinding RESOURCE=RunObservationSelectors.events(RUN,SHA);
    static final class Session implements AutoCloseable {
        final LoopbackAuthority iam;final HttpCurrentAuthorityClient gateway,engine;final String source;
        Session()throws Exception{this(120);}
        Session(long ttl)throws Exception{iam=new LoopbackAuthority();PortalEventsAuthorityProfileTest.setup(iam);gateway=iam.client("gateway");engine=iam.client("engine");source=iam.portal(ttl);}
        HttpCurrentAuthorityClient.GatewayEventsOpening begin(){return gateway.beginGatewayPortalEvents(source,RUN,9);}
        GatewayPortalEventsResponse open(){try(var opening=begin()){return gateway.openGatewayPortalEvents(opening,RESOURCE,()->{});}}
        @Override public void close(){engine.close();gateway.close();iam.close();}
    }
    static void denied(Runnable work){assertThatThrownBy(work::run).isInstanceOf(RuntimeException.class);}
    static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Object runtime(HttpCurrentAuthorityClient client)throws Exception{return field(client,"eventsRuntime");}
    static int slots(Object runtime)throws Exception{synchronized(runtime){return ((Set<?>)field(runtime,"slots")).size();}}
    static ThreadPoolExecutor workers(Object runtime)throws Exception{return (ThreadPoolExecutor)field(runtime,"workers");}
    static ScheduledThreadPoolExecutor scheduler(Object runtime)throws Exception{return (ScheduledThreadPoolExecutor)field(runtime,"scheduler");}
    static void until(Callable<Boolean> condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.call()&&System.nanoTime()<end)Thread.yield();assertThat(condition.call()).isTrue();}
    static void block(CountDownLatch latch){boolean interrupted=false;while(latch.getCount()!=0){try{latch.await();}catch(InterruptedException ignored){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();}
    static MaterialScopeV1 material(Set<String> roles,Set<String> region){
        var ref=new MaterialRef("engine","event",RUN,SHA+":1",Digest.sha256("actual-common-material"));
        var raw=new MaterialScopeV1(1,ref,OWNER,"historical-observation","historical-version",Digest.sha256("historical-ceiling"),roles,Map.of("region",region),Set.of("initial"),List.of(),"");
        return new MaterialScopeV1(1,ref,OWNER,raw.sourceDecisionId(),raw.sourceAuthorizationVersion(),raw.initialCeilingSha256(),roles,raw.requiredEntitlements(),raw.requiredDataLabels(),raw.parentScopeSha256(),MaterialAuthorization.scopeDigest(raw));
    }
    static Object invoke(Object receiver,String method,Class<?>[] signature,Object... args) {
        try { return receiver.getClass().getMethod(method,signature).invoke(receiver,args); }
        catch (NoSuchMethodException missing) { throw new AssertionError("Fixed private G admission unavailable: "+method,missing); }
        catch (InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    @Test
    void realPortalAndQueryMustOpenIndependentGatewayEventsAdmission() throws Exception {
        try(var iam=new LoopbackAuthority()) {
            PortalEventsAuthorityProfileTest.setup(iam);
            try(var gateway=iam.client("gateway")) {
                var source=iam.portal(120);
                // A real signed Q succeeds before the missing G behavior is exercised.
                var query=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);
                query.requireVerified();
                assertThat(iam.authenticatedClients).contains("gateway");
                try(var opening=(AutoCloseable)invoke(gateway,"beginGatewayPortalEvents",
                        new Class<?>[]{String.class,String.class,long.class},source,RUN,7L)) {
                    var response=(AutoCloseable)invoke(gateway,"openGatewayPortalEvents",
                            new Class<?>[]{opening.getClass(),ResourceBinding.class,Runnable.class},
                            opening,RunObservationSelectors.events(RUN,SHA),(Runnable)()->{});
                    try(response) {
                        var capture=invoke(response,"currentCapture",new Class<?>[0]);
                        assertThat(invoke(capture,"subject",new Class<?>[0])).isEqualTo(OWNER);
                        assertThat(invoke(capture,"initialCursor",new Class<?>[0])).isEqualTo(7L);
                    }
                }
            }
        }
    }
    @Test void queryUsesRealSubjectAndIsNotGatewayPermission()throws Exception{
        try(var s=new Session();var opening=s.begin()){
            opening.queryAuthorization().requireVerified();assertThat(opening.publicRunId()).isEqualTo(RUN);
            assertThat(opening.queryAuthorization().ticket().operation().subject()).isEqualTo(OWNER);
            s.iam.denyObservation(RUN);denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));
            denied(opening::queryAuthorization);until(()->slots(runtime(s.gateway))==0);
        }
    }
    @Test void originalPortalValidationAndSourceAndRunCannotBeReplaced()throws Exception{
        try(var s=new Session()){
            denied(()->s.gateway.beginGatewayPortalEvents("bad.jwt.text",RUN,0));
            denied(()->s.gateway.beginGatewayPortalEvents(s.source,RUN,-1));
            denied(()->s.gateway.beginGatewayPortalEvents(s.source,"r-invalid",0));
            try(var opening=s.begin()){
                denied(()->s.gateway.openGatewayPortalEvents(opening,RunObservationSelectors.events("r_"+"b".repeat(32),SHA),()->{}));
                denied(()->s.gateway.openGatewayPortalEvents(opening,RunObservationSelectors.events(RUN,Digest.sha256("tamper")),()->{}));
            }
            until(()->slots(runtime(s.gateway))==0);
        }
    }
    @Test void ordinaryClientsCannotMintGAndPublicPurposeAndCatalogStayClosed()throws Exception{
        try(var s=new Session()){
            var call=new ServiceCall("gateway",RunObservationSelectors.GATEWAY_EVENTS_ACTION,RESOURCE);
            denied(()->s.gateway.exchange(s.source,call));denied(()->s.gateway.client(call));
            denied(()->RunObservationSelectors.requirePurpose(call,"gateway","delegated","PORTAL","PORTAL"));
            assertThat(new ServiceRoutePolicy().routes()).noneMatch(r->r.action().equals(call.action()));
            denied(()->s.engine.beginGatewayPortalEvents(s.source,RUN,0));
        }
    }
    @Test void openingIsOneUseSameOwnerAndTransfersExactlyItsOriginalSlot()throws Exception{
        try(var s=new Session();var foreign=s.iam.client("gateway");var opening=s.begin()){
            var runtime=runtime(s.gateway);var slot=field(opening,"slot");assertThat(slots(runtime)).isEqualTo(1);
            denied(()->foreign.openGatewayPortalEvents(opening,RESOURCE,()->{}));
            try(var parent=s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{})){
                assertThat(slots(runtime)).isEqualTo(1);assertThat(((Set<?>)field(runtime,"slots")).contains(slot)).isTrue();
                denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));denied(opening::queryAuthorization);
                opening.close();assertThat(parent.currentCapture().subject()).isEqualTo(OWNER);
                denied(()->foreign.checkGatewayPortalEvents(parent));
            }
            until(()->slots(runtime)==0);
        }
    }
    @Test void abandonedOpeningAutomaticallyExpiresAndClearsOriginalToken()throws Exception{
        try(var s=new Session()){
            var opening=s.begin();s.iam.clock.advance(5);
            until(()->slots(runtime(s.gateway))==0);assertThat(field(opening,"source")).isNull();denied(opening::queryAuthorization);
            s.iam.clock.advance(-5);denied(opening::queryAuthorization);
        }
    }
    @Test void openingCapacityIs64AndCloseReleasesEachReservation()throws Exception{
        try(var s=new Session()){
            var openings=new ArrayList<HttpCurrentAuthorityClient.GatewayEventsOpening>();
            try{for(int i=0;i<64;i++)openings.add(s.begin());assertThat(slots(runtime(s.gateway))).isEqualTo(64);
                assertThatThrownBy(s::begin).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(429));
            }finally{openings.forEach(HttpCurrentAuthorityClient.GatewayEventsOpening::close);}
            until(()->slots(runtime(s.gateway))==0);s.iam.clock.advance(6);try(var ignored=s.begin()){assertThat(slots(runtime(s.gateway))).isEqualTo(1);}
        }
    }
    @Test void trueOpeningBudgetIncludesQueryAndGNetworkAndCannotBeRefreshed()throws Exception{
        try(var s=new Session();var opening=s.begin()){
            var began=(Instant)field(opening,"observedAt");s.iam.clock.advance(3);
            try(var parent=s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{})){
                var evidence=field(field(parent,"parent"),"evidence");assertThat(field(evidence,"hardDeadline")).isEqualTo(began.plusSeconds(55));
                for(int i=0;i<12;i++){s.iam.clock.advance(4);s.gateway.checkGatewayPortalEvents(parent);}
                s.iam.clock.advance(4);denied(()->s.gateway.checkGatewayPortalEvents(parent));
            }
        }
        try(var s=new Session();var opening=s.begin()){s.iam.clock.advance(5);denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));}
    }
    @Test void sourceGrantAndOperationIndependentlyCapHardLifetime()throws Exception{
        for(String bound:List.of("source","grant","operation"))try(var s=new Session(bound.equals("source")?12:120)){
            if(bound.equals("grant"))s.iam.gatewayEventsGrantSeconds=12;if(bound.equals("operation"))s.iam.gatewayEventsOperationSeconds=12;
            try(var parent=s.open()){
                var capture=parent.currentCapture();for(int i=0;i<2;i++){s.iam.clock.advance(4);s.gateway.checkGatewayPortalEvents(parent);}
                assertThat(parent.isSuperseded(capture)).isTrue();s.iam.clock.advance(4);denied(()->s.gateway.checkGatewayPortalEvents(parent));
            }
        }
    }
    @Test void freshCaptureAndMaterialAndSupersessionRemainOwnerBoundAcrossThreads()throws Exception{
        try(var s=new Session();var parent=s.open();var other=s.open();var thread=Executors.newSingleThreadExecutor()){
            var old=parent.currentCapture();old.requireMaterial(material(Set.of("EDITOR"),Set.of("B")));assertThat(parent.isSuperseded(old)).isFalse();
            assertThat(thread.submit(()->parent.currentCapture().subject()).get(2,TimeUnit.SECONDS)).isEqualTo(OWNER);
            denied(()->other.requireLive(old));denied(()->other.isSuperseded(old));
            s.iam.currentRoles=Set.of();s.iam.eventsCurrentAttributes=Map.of("region",Set.of("A"),"dataLabels",Set.of("initial","new"));s.iam.version="g-fresh";
            s.gateway.checkGatewayPortalEvents(parent);assertThat(parent.isSuperseded(old)).isTrue();denied(old::subject);
            var fresh=parent.currentCapture();assertThat(parent.isSuperseded(fresh)).isFalse();denied(()->fresh.requireMaterial(material(Set.of("EDITOR"),Set.of("B"))));fresh.requireMaterial(material(Set.of(),Set.of("A")));
            parent.close();denied(()->parent.isSuperseded(fresh));denied(fresh::subject);
        }
    }
    @Test void signedGatewayDecisionAndContinuationMutationsNeverMint()throws Exception{
        List<Consumer<Map<String,Object>>> mutations=List.of(c->c.put("nonce","wrong"),c->c.put("request_sha256",Digest.sha256("wrong")),c->c.put("aud","urn:agent-platform:service:engine"),c->c.put("profile","portal-run-events-response.v1"),c->c.put("exp",0L),c->c.put("ticket_jti","wrong"),c->c.put("parent_response_id","forbidden"),c->c.put("origin_authentication_id","wrong"));
        for(var mutation:mutations)try(var s=new Session();var opening=s.begin()){
            s.iam.changeDecision=c->{if("portal-gateway-events-response.v1".equals(c.get("profile")))mutation.accept(c);};denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));
        }
        for(String name:List.of("token_use","aud","exp","immutable"))try(var s=new Session();var opening=s.begin()){
            s.iam.changeEventsContinuation=c->{if(name.equals("exp"))c.put(name,s.iam.clock.instant().plusSeconds(56).getEpochSecond());else if(name.equals("immutable"))c.put(name,Map.of());else c.put(name,"wrong");};denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));
        }
    }
    @Test void wrongTicketActionTargetSourceSubjectAndInitialCeilingAreDenied()throws Exception{
        for(String field:List.of("action","target","tenant_id","caller","scope","origin_authentication_id","initial_ceiling","operation"))try(var s=new Session();var opening=s.begin()){
            s.iam.changeTicket=c->{if(RunObservationSelectors.GATEWAY_EVENTS_ACTION.equals(c.get("action"))){if(Set.of("initial_ceiling","operation").contains(field))c.put(field,Map.of());else c.put(field,"wrong");}};
            denied(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{}));
        }
    }
    @Test void internalSignatureOutageAndEnvelopeTypeDoNotBecomeBadOriginalPortal()throws Exception{
        try(var s=new Session();var opening=s.begin()){
            s.iam.badSignature=true;assertThatThrownBy(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{})).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
        }
        try(var s=new Session();var opening=s.begin()){
            s.iam.eventsDecisionType="wrong+jwt";assertThatThrownBy(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{})).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
        }
    }
    @Test void blockedLateGOpenRetainsSlotUntilPhysicalExitAndCannotResurrect()throws Exception{
        try(var s=new Session();var thread=Executors.newSingleThreadExecutor();var opening=s.begin()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var cancels=new AtomicInteger();
            s.gateway.eventsLifecycle=p->{if(p.equals("gateway-open-returned")){entered.countDown();block(release);}};
            var future=thread.submit(()->s.gateway.openGatewayPortalEvents(opening,RESOURCE,cancels::incrementAndGet));
            try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();opening.close();assertThat(slots(runtime(s.gateway))).isEqualTo(1);denied(()->{try{future.get(1,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});}
            finally{release.countDown();}
            until(()->slots(runtime(s.gateway))==0);assertThat(cancels).hasValue(1);
        }
    }
    @Test void queuedOpeningNeverRunsAfterExpiryAndShutdownOwnsCancellation()throws Exception{
        try(var s=new Session();var thread=Executors.newSingleThreadExecutor()){
            try(var ignored=s.begin()){}until(()->slots(runtime(s.gateway))==0);
            var runtime=runtime(s.gateway);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
            for(int i=0;i<2;i++)workers(runtime).execute(()->{entered.countDown();block(release);});
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();int attempts=s.iam.requestAttempts();
            var future=thread.submit(s::begin);
            try{until(()->workers(runtime).getQueue().size()==1);s.iam.clock.advance(6);denied(()->{try{future.get(2,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}});assertThat(s.iam.requestAttempts()).isEqualTo(attempts);}
            finally{release.countDown();}
            until(()->slots(runtime)==0);s.gateway.close();assertThat(workers(runtime).isTerminated()).isTrue();assertThat(scheduler(runtime).isTerminated()).isTrue();
        }
    }
    @Test void opaqueProofsHaveNoPublicConstructorsOrAuthorityDelegateGetters(){
        for(Class<?> type:List.of(HttpCurrentAuthorityClient.GatewayEventsOpening.class,HttpCurrentAuthorityClient.PortalEventsChildAuthorization.class,GatewayPortalEventsResponse.class)){
            assertThat(type.getDeclaredConstructors()).noneMatch(c->Modifier.isPublic(c.getModifiers()));
            assertThat(type.getMethods()).noneMatch(m->List.of(VerifiedAuthority.class,Actor.class,HttpCurrentAuthorityClient.RetainedAuthorization.class,PortalEventsResponse.class).contains(m.getReturnType()));
        }
    }
    @Test void gatewayGrantMayDifferFromItsIndependentQueryCandidate()throws Exception{
        try(var s=new Session()){
            s.iam.changeTicket=c->{if(RunObservationSelectors.ACTION.equals(c.get("action")))c.put("roles",Set.of());};
            try(var parent=s.open()){parent.currentCapture().requireMaterial(material(Set.of("EDITOR"),Set.of("A")));}
        }
    }
    @Test void newObserverAuthenticationDoesNotHaveToEqualHistoricalRunAuthentication()throws Exception{
        try(var s=new Session();var historical=s.open()){
            String freshSource=s.iam.portal(120);try(var opening=s.gateway.beginGatewayPortalEvents(freshSource,RUN,9);var observer=s.gateway.openGatewayPortalEvents(opening,RESOURCE,()->{})){
                var a=(JsonNode)field(field(field(historical,"parent"),"evidence"),"immutable");var b=(JsonNode)field(field(field(observer,"parent"),"evidence"),"immutable");
                assertThat(a.get("origin_authentication_id")).isNotEqualTo(b.get("origin_authentication_id"));assertThat(observer.currentCapture().resource()).isEqualTo(historical.currentCapture().resource());
            }
        }
    }
}
