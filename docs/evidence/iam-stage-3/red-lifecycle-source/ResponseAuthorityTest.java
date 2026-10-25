package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ResponseAuthorityTest {
    static ResponseBinding binding(VerifiedAuthority current,LoopbackAuthority f,String id){
        return new ResponseBinding(id,"sse",current.call().resource(),Digest.sha256("frozen-request"),f.clock.instant().plusSeconds("INTEGRATION".equals(current.operation().sourceKind())?55:35));
    }
    @Test void r1BlockedWriterCannotReleaseQueuedChunkAfterDecisionExpiryAndRevocation()throws Exception{
        for(String failure:List.of("scope","503","expiry"))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var lease=engine.open(current,binding(current,f,"response-1"));var cancelled=new AtomicInteger();var chunks=new AtomicInteger();
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,cancelled::incrementAndGet);var pool=Executors.newSingleThreadExecutor()){
                guard.markResponseAccepted();var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
                var writer=pool.submit(()->{ready.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("release");guard.beforeWrite();chunks.incrementAndGet();}catch(PlatformException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();f.clock.advance(failure.equals("expiry")?41:6);
                if(failure.equals("scope"))f.scopeAllowed=false;if(failure.equals("503"))f.unavailable=true;
                release.countDown();writer.get(2,TimeUnit.SECONDS);
                assertThat(chunks).as(failure).hasValue(0);assertThat(guard.stopped()).isTrue();assertThat(cancelled).hasValue(1);
            }
        }
    }
    @Test void idleWatchdogCancelsAndCloseIsIdempotent()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var count=new AtomicInteger();var guard=new StreamAuthorityGuard(engine,engine.open(current,binding(current,f,"idle")),f.clock,count::incrementAndGet);
            f.clock.advance(6);f.revoked=true;guard.watchdogCheck();
            assertThat(guard.stopped()).isTrue();assertThat(guard.watchdogShutdown()).isTrue();guard.close();guard.close();assertThat(count).hasValue(1);
        }
    }
    @Test void validContinuationChecksOnlineWithoutRenewingOriginalBindingOrSource()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);var lease=engine.open(current,binding(current,f,"response-1"));
            f.clock.advance(6);var renewed=engine.check(lease);
            assertThat(renewed.binding()).isEqualTo(lease.binding());assertThat(renewed.sourceExpiresAt()).isEqualTo(lease.sourceExpiresAt());assertThat(renewed.checkedUntil()).isAfter(lease.checkedUntil());
            var altered=new ResponseBinding("response-2",lease.binding().kind(),lease.binding().resource(),lease.binding().requestSha256(),lease.binding().hardDeadline());
            assertThatThrownBy(()->engine.check(new ResponseLease(altered,lease.sourceKind(),lease.continuationRef(),lease.openingDecisionId(),lease.checkedUntil(),lease.sourceExpiresAt(),null))).isInstanceOf(PlatformException.class);
            f.changeDecision=c->c.put("source_expires_at",lease.sourceExpiresAt().plusSeconds(90).getEpochSecond());
            assertThatThrownBy(()->engine.check(lease)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void rr1ExpiredOrdinaryCurrentFailsWhileOnlyOriginalAcceptedIntegrationResponseCanDrain()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);
            var lease=engine.open(current,binding(current,f,"integration-1"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var material=material(current,lease,1);
                f.clock.advance(21);f.revoked=true;
                assertThatThrownBy(()->MaterialAuthorization.require(material.scope(),current,f.clock)).isInstanceOf(PlatformException.class);
                MaterialAuthorization.requireForResponse(material.scope(),access);guard.beforeMaterialWrite(material,access);
                assertThat(guard.draining()).isTrue();
                assertThatThrownBy(()->engine.forward(current,LoopbackAuthority.call("engine","model"))).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->engine.open(current,binding(current,f,"new-response"))).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->guard.beforeMaterialWrite(material,access)).isInstanceOf(PlatformException.class);
                var next=material(current,lease,2);guard.close();
                assertThatThrownBy(()->MaterialAuthorization.requireForResponse(next.scope(),access)).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void rr1CompletionRequiresAcceptedResponseAndCannotExtendDeadlineOrSwitchOwnerVersionPayload()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"integration-1"));
            try(var unopened=new StreamAuthorityGuard(engine,lease,f.clock,()->{});var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                assertThatThrownBy(unopened::materialAccess).isInstanceOf(PlatformException.class);
                guard.markResponseAccepted();var access=guard.materialAccess();var material=material(current,lease,1);
                assertThatThrownBy(()->guard.beforeMaterialWrite(new ResponseMaterial("tampered",material.scope(),material.responseId()),access)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->guard.beforeMaterialWrite(new ResponseMaterial(material.payload(),material.scope(),"another"),access)).isInstanceOf(PlatformException.class);
                var scope=material.scope();var wrong=new MaterialScopeV1(1,scope.material(),new SubjectKey("other","alice"),scope.sourceDecisionId(),scope.sourceAuthorizationVersion(),scope.initialCeilingSha256(),scope.requiredRoles(),scope.requiredEntitlements(),scope.requiredDataLabels(),scope.parentScopeSha256(),"");
                assertThatThrownBy(()->MaterialAuthorization.requireForResponse(wrong,access)).isInstanceOf(PlatformException.class);
                f.clock.advance(56);assertThatThrownBy(()->guard.beforeMaterialWrite(material,access)).isInstanceOf(PlatformException.class);
                assertThat(guard.stopped()).isTrue();
            }
        }
    }
    @Test void portalNeverHasCompletionPermitAndIntegrationPermitBindingsAreVerifiedAtOpen()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            assertThat(engine.open(current,binding(current,f,"portal")).completionPermit()).isNull();
            current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var integration=current;
            f.changePermit=c->c.put("operation_id","other");
            assertThatThrownBy(()->engine.open(integration,binding(integration,f,"bad-permit"))).isInstanceOf(PlatformException.class);
        }
    }
    static ResponseMaterial material(VerifiedAuthority current,ResponseLease lease,long seq){
        Object payload=Map.of("event","sample-"+seq);
        var ref=new MaterialRef(current.call().target(),"event",current.call().resource().id(),current.call().resource().versionDigest()+":"+seq,AuthorityProtocol.digest(payload));
        var raw=new MaterialScopeV1(1,ref,AuthorityProtocol.subject(current.actor()),current.decisionId(),current.authorizationVersion(),current.operation().ceilingSha256(),current.actor().roles(),Map.of("region",current.actor().attributes().get("region")),current.actor().attributes().get("dataLabels"),List.of(),"");
        var scope=new MaterialScopeV1(1,ref,raw.owner(),raw.sourceDecisionId(),raw.sourceAuthorizationVersion(),raw.initialCeilingSha256(),raw.requiredRoles(),raw.requiredEntitlements(),raw.requiredDataLabels(),List.of(),MaterialAuthorization.scopeDigest(raw));
        return new ResponseMaterial(payload,scope,lease.binding().responseId());
    }
}
