package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ResponseAuthorityTest {
    @Test void reviewI1RenewalPreservesCursorAcceptanceAndFinalReservation()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"renew-cursor"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var first=material(current,lease,1);guard.beforeMaterialWrite(first,access);
                f.clock.advance(6);guard.beforeWrite();assertThatThrownBy(()->guard.beforeMaterialWrite(first,access)).isInstanceOf(PlatformException.class);
                guard.beforeMaterialWrite(material(current,lease,2),access);f.clock.advance(15);f.revoked=true;
                guard.beforeMaterialWrite(material(current,lease,3),access);assertThat(guard.draining()).isTrue();
                assertThatThrownBy(()->guard.beforeMaterialWrite(material(current,lease,2),access)).isInstanceOf(PlatformException.class);
            }
        }
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var first=engine.authorize(gateway.exchange(f.integration(),entry).value(),entry);var call=LoopbackAuthority.call("engine","model");var current=model.authorize(engine.forward(first,call).value(),call);
            var lease=model.open(current,new ResponseBinding("renew-final","model",call.resource(),Digest.sha256("request"),f.clock.instant().plusSeconds(55)));
            try(var guard=new StreamAuthorityGuard(model,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var result=modelMaterial(current,lease,"final");guard.beforeMaterialWrite(result,access);
                f.clock.advance(6);var renewed=model.check(lease);rejectHolder(model,renewed,f);
                assertThatThrownBy(()->guard.beforeMaterialWrite(result,access)).isInstanceOf(PlatformException.class);assertThatThrownBy(guard::beforeWrite).isInstanceOf(PlatformException.class);
                guard.close();assertThatThrownBy(()->model.check(renewed)).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void reviewI1ActiveRenewedAndClosedLeasesCannotClaimAnotherHolder()throws Exception{
        for(boolean integration:List.of(false,true))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(integration?f.integration():f.portal(),call).value(),call);
            var lease=engine.open(current,binding(current,f,"single"));var cancelled=new AtomicInteger();
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,cancelled::incrementAndGet);var pool=Executors.newSingleThreadExecutor()){
                guard.markResponseAccepted();assertThat(pool.submit(()->{guard.beforeWrite();return true;}).get(2,TimeUnit.SECONDS)).isTrue();
                rejectHolder(engine,lease,f);
                f.clock.advance(6);var renewed=engine.check(lease);guard.beforeWrite();rejectHolder(engine,renewed,f);
                guard.close();guard.close();assertThat(cancelled).hasValue(1);assertThat(guard.watchdogShutdown()).isTrue();rejectHolder(engine,lease,f);
                assertThatThrownBy(()->engine.check(renewed)).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void reviewI1ClosedSseLeaseCannotReplayConsumedSequence()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"sse-once"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){guard.markResponseAccepted();guard.beforeMaterialWrite(material(current,lease,1),guard.materialAccess());}
            assertThatThrownBy(()->{try(var replay=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){replay.markResponseAccepted();f.clock.advance(21);f.revoked=true;replay.beforeMaterialWrite(material(current,lease,1),replay.materialAccess());}}).isInstanceOf(PlatformException.class);
        }
    }
    @Test void reviewI1ClosedModelLeaseCannotReplayFinal()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var first=engine.authorize(gateway.exchange(f.integration(),entry).value(),entry);var call=LoopbackAuthority.call("engine","model");var current=model.authorize(engine.forward(first,call).value(),call);
            var lease=model.open(current,new ResponseBinding("model-once","model",call.resource(),Digest.sha256("request"),f.clock.instant().plusSeconds(55)));
            try(var guard=new StreamAuthorityGuard(model,lease,f.clock,()->{})){guard.markResponseAccepted();guard.beforeMaterialWrite(modelMaterial(current,lease,"final"),guard.materialAccess());}
            assertThatThrownBy(()->{try(var replay=new StreamAuthorityGuard(model,lease,f.clock,()->{})){replay.markResponseAccepted();f.clock.advance(21);f.unavailable=true;replay.beforeMaterialWrite(modelMaterial(current,lease,"final"),replay.materialAccess());}}).isInstanceOf(PlatformException.class);
        }
    }
    @Test void reviewI1PublicCopiesCannotClaimOrLaunderSamePermit()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"copy"));
            var copy=new ResponseLease(lease.binding(),lease.sourceKind(),lease.continuationRef(),lease.openingDecisionId(),lease.checkedUntil(),lease.sourceExpiresAt(),lease.completionPermit());
            assertThat(copy).isEqualTo(lease);assertThat(copy.hashCode()).isEqualTo(lease.hashCode());assertThat(copy.toString()).isEqualTo("ResponseLease[redacted]");
            rejectHolder(engine,copy,f);assertThatThrownBy(()->engine.check(copy)).isInstanceOf(PlatformException.class);
            for(String field:List.of("binding","source","continuation","checked")){
                var changed=new ResponseLease(field.equals("binding")?binding(current,f,"other"):lease.binding(),field.equals("source")?"PORTAL":lease.sourceKind(),field.equals("continuation")?"other":lease.continuationRef(),lease.openingDecisionId(),field.equals("checked")?lease.checkedUntil().plusSeconds(90):lease.checkedUntil(),lease.sourceExpiresAt(),lease.completionPermit());
                rejectHolder(engine,changed,f);assertThatThrownBy(()->engine.check(changed)).isInstanceOf(PlatformException.class);
            }
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){guard.markResponseAccepted();rejectHolder(engine,copy,f);}rejectHolder(engine,copy,f);
        }
    }
    @Test void reviewI1FailedConstructorTerminatesClaimAndShutsScheduler()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);var lease=engine.open(current,binding(current,f,"failed-constructor"));
            var scheduler=new ScheduledThreadPoolExecutor(1);scheduler.shutdownNow();
            assertThatThrownBy(()->new StreamAuthorityGuard(engine,lease,f.clock,()->{},scheduler)).isInstanceOf(RejectedExecutionException.class);
            assertThat(scheduler.isShutdown()).isTrue();rejectHolder(engine,lease,f);
            var live=new ScheduledThreadPoolExecutor(1);
            assertThatThrownBy(()->new StreamAuthorityGuard(null,lease,f.clock,()->{},live)).isInstanceOf(NullPointerException.class);
            try{assertThat(live.isShutdown()).isTrue();}finally{live.shutdownNow();}
        }
    }
    static void rejectHolder(ResponseAuthorityClient client,ResponseLease lease,LoopbackAuthority f){
        var scheduler=new ScheduledThreadPoolExecutor(1);
        try{assertThatThrownBy(()->{try(var duplicate=new StreamAuthorityGuard(client,lease,f.clock,()->{},scheduler)){duplicate.beforeWrite();}}).isInstanceOf(PlatformException.class);assertThat(scheduler.isShutdown()).isTrue();}finally{scheduler.shutdownNow();}
    }
    @Test void modelCompletionAcceptsOriginalFinalOnceAndRejectsConflictingReplay()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var first=engine.authorize(gateway.exchange(f.integration(),entry).value(),entry);
            var call=LoopbackAuthority.call("engine","model");var current=model.authorize(engine.forward(first,call).value(),call);
            var b=new ResponseBinding("model-response","model",call.resource(),Digest.sha256("exact-frozen-provider-request"),f.clock.instant().plusSeconds(55));var lease=model.open(current,b);
            try(var guard=new StreamAuthorityGuard(model,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();
                f.clock.advance(21);f.unavailable=true;f.jwksUnavailable=true;
                var finalMaterial=modelMaterial(current,lease,"original");guard.beforeMaterialWrite(finalMaterial,access);
                assertThat(guard.draining()).isTrue();
                assertThatThrownBy(()->guard.beforeMaterialWrite(modelMaterial(current,lease,"conflicting"),access)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->guard.beforeMaterialWrite(finalMaterial,access)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(guard::beforeWrite).isInstanceOf(PlatformException.class);
                guard.close();assertThat(guard.stopped()).isTrue();assertThat(guard.watchdogShutdown()).isTrue();
            }
        }
    }
    static ResponseMaterial modelMaterial(VerifiedAuthority current,ResponseLease lease,String text){
        Object payload=Map.of("final",text);var call=current.call();
        var ref=new MaterialRef(call.target(),"model-result",call.resource().id(),call.resource().versionDigest(),AuthorityProtocol.digest(payload));
        var raw=new MaterialScopeV1(1,ref,AuthorityProtocol.subject(current.actor()),current.decisionId(),current.authorizationVersion(),current.operation().ceilingSha256(),current.actor().roles(),Map.of("region",current.actor().attributes().get("region")),current.actor().attributes().get("dataLabels"),List.of(),"");
        var scope=new MaterialScopeV1(1,ref,raw.owner(),raw.sourceDecisionId(),raw.sourceAuthorizationVersion(),raw.initialCeilingSha256(),raw.requiredRoles(),raw.requiredEntitlements(),raw.requiredDataLabels(),List.of(),MaterialAuthorization.scopeDigest(raw));
        return new ResponseMaterial(payload,scope,lease.binding().responseId());
    }
    @Test void closeMarksStopBeforeWaitingForAnInFlightRenewal()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);var lease=engine.open(current,binding(current,f,"closing"));
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CountDownLatch(1);var writes=new AtomicInteger();
            ResponseAuthorityClient blocked=new ResponseAuthorityClient(){
                public ResponseLease open(VerifiedAuthority a,ResponseBinding b){return engine.open(a,b);}
                public ResponseLease check(ResponseLease l){entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("release renewal");}catch(InterruptedException e){Thread.currentThread().interrupt();throw AuthorityProtocol.denied();}return engine.check(l);}
            };
            try(var guard=new StreamAuthorityGuard(blocked,lease,f.clock,closed::countDown);var pool=Executors.newFixedThreadPool(2)){
                f.clock.advance(6);
                var write=pool.submit(()->{try{guard.beforeWrite();writes.incrementAndGet();}catch(PlatformException ignored){}});
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var closer=pool.submit(guard::close);boolean stoppedPromptly;
                try{stoppedPromptly=closed.await(150,TimeUnit.MILLISECONDS);}finally{release.countDown();}
                closer.get(2,TimeUnit.SECONDS);write.get(2,TimeUnit.SECONDS);
                assertThat(stoppedPromptly).isTrue();assertThat(writes).hasValue(0);
            }
        }
    }
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
            var extended=new ResponseBinding(lease.binding().responseId(),lease.binding().kind(),lease.binding().resource(),lease.binding().requestSha256(),lease.binding().hardDeadline().plusSeconds(1));
            assertThatThrownBy(()->engine.check(new ResponseLease(extended,lease.sourceKind(),lease.continuationRef(),lease.openingDecisionId(),lease.checkedUntil(),lease.sourceExpiresAt(),null))).isInstanceOf(PlatformException.class);
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
            var unopenedLease=engine.open(current,binding(current,f,"unopened-independent"));
            try(var unopened=new StreamAuthorityGuard(engine,unopenedLease,f.clock,()->{});var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
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
    @Test void integrationOpenAndCheckUsePolicyThenDrainingDoesNotQueryOrSendAgain()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);
            var lease=engine.open(current,binding(current,f,"live"));assertThat(f.policyCalls).hasValue(3);
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                guard.markResponseAccepted();f.clock.advance(6);guard.beforeWrite();assertThat(f.policyCalls).hasValue(4);
                f.policyActive=false;f.clock.advance(6);guard.beforeWrite();assertThat(guard.draining()).isTrue();assertThat(f.policyCalls).hasValue(5);
                var access=guard.materialAccess();guard.beforeMaterialWrite(material(current,lease,1),access);
                guard.beforeMaterialWrite(material(current,lease,2),access);assertThat(f.policyCalls).hasValue(5);assertThat(f.businessSends).hasValue(0);
            }
        }
    }
    @Test void unacceptedIntegrationAndDelegatedResponsesCannotDrain()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);
            try(var guard=new StreamAuthorityGuard(engine,engine.open(current,binding(current,f,"not-sent")),f.clock,()->{})){
                f.clock.advance(6);f.revoked=true;assertThatThrownBy(guard::beforeWrite).isInstanceOf(PlatformException.class);assertThat(guard.stopped()).isTrue();
            }
        }
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var original=engine.authorize(gateway.exchange(f.portal(10),call).value(),call);
            f.clock.advance(11);var current=engine.authorize(gateway.resume(original.delegation(),call).value(),call);
            assertThat(current.operation().sourceKind()).isEqualTo("DELEGATED");
            try(var guard=new StreamAuthorityGuard(engine,engine.open(current,binding(current,f,"delegated")),f.clock,()->{})){
                guard.markResponseAccepted();f.clock.advance(6);f.unavailable=true;
                assertThatThrownBy(guard::beforeWrite).isInstanceOf(PlatformException.class);assertThat(guard.stopped()).isTrue();assertThat(guard.draining()).isFalse();
            }
        }
    }
    @Test void signedPermitBindingMatrixAndContinuationMisuseFail()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);
            for(String field:List.of("source_kind","sub","tenant_id","authorization_version","operation_id","opening_decision_id","consumer","caller","action","ceiling_sha256")){
                f.changePermit=c->c.put(field,"wrong");
                assertThatThrownBy(()->engine.open(current,binding(current,f,field))).as(field).isInstanceOf(PlatformException.class);
            }
            f.changePermit=c->{};var lease=engine.open(current,binding(current,f,"valid"));
            assertThatThrownBy(()->engine.authorize(lease.continuationRef(),call)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            assertThatThrownBy(()->gateway.exchange(lease.continuationRef(),call)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            f.clock.advance(6);f.changeDecision=c->c.put("operation_id","replaced");
            assertThatThrownBy(()->engine.check(lease)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void validManifestDigestDoesNotAllowWrongVersionOwnerCeilingOrLabels()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"manifest"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var material=material(current,lease,1);var s=material.scope();
                MaterialAuthorization.require(s,current,f.clock);
                for(String field:List.of("version","owner","ceiling","roles","labels","producer")){
                    var ref=s.material();
                    if(field.equals("version"))ref=new MaterialRef(ref.producer(),ref.kind(),ref.id(),"other-version:1",ref.payloadSha256());
                    if(field.equals("producer"))ref=new MaterialRef("other",ref.kind(),ref.id(),ref.version(),ref.payloadSha256());
                    var raw=new MaterialScopeV1(1,ref,field.equals("owner")?new SubjectKey("tenant-b","alice"):s.owner(),s.sourceDecisionId(),s.sourceAuthorizationVersion(),
                        field.equals("ceiling")?Digest.sha256("other"):s.initialCeilingSha256(),field.equals("roles")?Set.of("ADMIN"):s.requiredRoles(),s.requiredEntitlements(),field.equals("labels")?Set.of():s.requiredDataLabels(),s.parentScopeSha256(),"");
                    var altered=new MaterialScopeV1(1,raw.material(),raw.owner(),raw.sourceDecisionId(),raw.sourceAuthorizationVersion(),raw.initialCeilingSha256(),raw.requiredRoles(),raw.requiredEntitlements(),raw.requiredDataLabels(),raw.parentScopeSha256(),MaterialAuthorization.scopeDigest(raw));
                    assertThatThrownBy(()->MaterialAuthorization.requireForResponse(altered,access)).as(field).isInstanceOf(PlatformException.class);
                }
                var secondLease=engine.open(current,binding(current,f,"second"));
                try(var other=new StreamAuthorityGuard(engine,secondLease,f.clock,()->{})){
                    other.markResponseAccepted();assertThatThrownBy(()->guard.beforeMaterialWrite(material,other.materialAccess())).isInstanceOf(PlatformException.class);
                }
            }
        }
    }
    @Test void signedCompletionPermitCannotBeReplayedAsAnyServiceOrNewResponseCredential()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);
            engine.open(current,binding(current,f,"completion"));
            assertThatThrownBy(()->engine.authorize(f.lastPermit,call)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            assertThatThrownBy(()->gateway.exchange(f.lastPermit,call)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            assertThatThrownBy(()->engine.resume(new DelegationRef(f.lastPermit,current.delegation().subject(),current.delegation().expiresAt()),LoopbackAuthority.call("engine","model"))).isInstanceOf(PlatformException.class);
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
