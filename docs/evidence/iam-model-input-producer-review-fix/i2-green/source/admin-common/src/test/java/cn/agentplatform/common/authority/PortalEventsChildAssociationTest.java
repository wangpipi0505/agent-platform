package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSObject;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static cn.agentplatform.common.authority.GatewayPortalEventsAuthorityTest.*;

/** Actual signed HTTP plus package receiver frame only; no real engine HTTP or SSE assertion. */
class PortalEventsChildAssociationTest {
    static final class ChildSession implements AutoCloseable {
        final Session s;final GatewayPortalEventsResponse parent;final String ticket;
        final HttpCurrentAuthorityClient.RetainedAuthorization initial;
        ChildSession()throws Exception{this(60);}
        ChildSession(long ttl)throws Exception{this(ttl,iam->{});}
        ChildSession(long ttl,Consumer<LoopbackAuthority> setup)throws Exception{s=new Session();s.iam.childTicketSeconds=ttl;setup.accept(s.iam);parent=s.open();ticket=s.gateway.exchangePortalEventsChild(s.source,parent).ticket().value();initial=initial();}
        HttpCurrentAuthorityClient.RetainedAuthorization initial(){return s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call());}
        PortalEventsResponse open(){var current=initial();try(var frame=AuthorityContext.bindRetained(current,this::initial)){return s.engine.openPortalEventsChild(current,9,()->{});}}
        @Override public void close(){parent.close();s.close();}
    }
    static JsonNode rawSigned(LoopbackAuthority iam,String service,ServiceCall call,Map<String,Object> fields)throws Exception{
        var request=new LinkedHashMap<String,Object>(fields);request.putAll(AuthorityProtocol.call(call));request.put("nonce",UUID.randomUUID().toString());
        String basic=Base64.getEncoder().encodeToString((service+":"+iam.secrets.get(service)).getBytes(StandardCharsets.UTF_8));
        try(var http=HttpClient.newHttpClient()){
            var response=http.send(HttpRequest.newBuilder(URI.create(iam.issuer()+"/decision")).header("Authorization","Basic "+basic).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);var outer=Json.read(response.body());
            var c=AuthorityResponseVerifier.verifyPinned(AuthorityProtocol.text(outer,"assertion"),"agent-authority-decision+jwt","current-decision","urn:agent-platform:service:"+service,5,iam.key.toPublicJWK(),iam.issuer(),iam.clock).claims();
            AuthorityProtocol.same(request.get("nonce"),AuthorityProtocol.text(c,"nonce"));AuthorityProtocol.same(AuthorityProtocol.digest(request),AuthorityProtocol.text(c,"request_sha256"));AuthorityProtocol.same(service,AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.call(c,call);return c;
        }
    }
    static Map<String,Object> childExchangeRequest(Session s,GatewayPortalEventsResponse parent)throws Exception{
        var evidence=field(field(parent,"parent"),"evidence");return Map.of("operation","portal_events_child_exchange","subject_token",s.source,"parent_continuation",field(evidence,"continuation"),"parent_immutable",field(evidence,"immutable"));
    }
    @Test void actualChildOpenExportsReceiptThroughProductionSeamAndBothHopsRenew() throws Exception {
        try(var iam=new LoopbackAuthority()){
            PortalEventsAuthorityProfileTest.setup(iam);
            try(var gateway=iam.client("gateway");var engine=iam.client("engine")){
                String source=iam.portal(120);
                try(var opening=gateway.beginGatewayPortalEvents(source,GatewayPortalEventsAuthorityTest.RUN,9);
                        var parent=gateway.openGatewayPortalEvents(opening,RunObservationSelectors.events(GatewayPortalEventsAuthorityTest.RUN,GatewayPortalEventsAuthorityTest.SHA),()->{})){
                    var authorization=gateway.exchangePortalEventsChild(source,parent);
                    String ticket=authorization.ticket().value();
                    var initial=engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call());
                    try(var frame=AuthorityContext.bindRetained(initial,()->engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call()));
                            var child=engine.openPortalEventsChild(initial,9,()->{})){
                        String receipt=engine.portalEventsChildReceipt(child);
                        assertThat(receipt).hasSizeLessThanOrEqualTo(4096);
                        parent.acceptChildReceipt(receipt);
                        gateway.checkGatewayPortalEvents(parent);engine.checkPortalEvents(child);
                        assertThat(parent.currentCapture().initialCursor()).isEqualTo(9);
                        assertThat(child.currentCapture().initialCursor()).isEqualTo(9);
                        assertThat(iam.observedOperations()).contains("portal_gateway_events_open","portal_events_child_exchange","portal_events_child_open","portal_gateway_events_check","portal_events_child_check");
                    }
                }
            }
        }
    }
    @Test void noFrameCurrentOnlyForeignClientAndWrongCursorCannotOpen()throws Exception{
        try(var c=new ChildSession();var foreign=c.s.iam.client("engine")){
            denied(()->c.s.engine.openPortalEventsChild(c.initial,9,()->{}));
            try(var frame=AuthorityContext.bind(c.initial.current())){denied(()->c.s.engine.openPortalEventsChild(c.initial,9,()->{}));}
            var currentOnly=HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(c.initial.current());
            try(var frame=AuthorityContext.bindRetained(currentOnly,()->currentOnly)){denied(()->c.s.engine.openPortalEventsChild(currentOnly,9,()->{}));}
            try(var frame=AuthorityContext.bindRetained(c.initial,c::initial)){denied(()->foreign.openPortalEventsChild(c.initial,9,()->{}));denied(()->c.s.engine.openPortalEventsChild(c.initial,8,()->{}));denied(()->c.s.engine.openPortalEventsChild(c.initial,9,()->{}));}
            assertThat(c.s.iam.observedOperations()).doesNotContain("portal_events_child_open");
        }
    }
    @Test void standaloneAndChildProfilesAreMutuallyExclusiveAndReceiptExportIsModeBound()throws Exception{
        try(var c=new ChildSession()){
            try(var frame=AuthorityContext.bindRetained(c.initial,c::initial)){
                denied(()->c.s.engine.openPortalEvents(c.initial,PortalEventsAuthorityProfileTest.binding(c.initial,c.s.iam,"illegal-standalone"),()->{}));
            }
            var ticket=c.s.gateway.exchange(c.s.source,PortalEventsAuthorityProfileTest.call()).value();var standalone=c.s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call());
            try(var frame=AuthorityContext.bindRetained(standalone,()->c.s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call()))){
                denied(()->c.s.engine.openPortalEventsChild(standalone,9,()->{}));
                try(var response=c.s.engine.openPortalEvents(standalone,PortalEventsAuthorityProfileTest.binding(standalone,c.s.iam,"original-A"),()->{})){denied(()->c.s.engine.portalEventsChildReceipt(response));}
            }
            assertThat(c.s.iam.observedOperations()).contains("portal_events_open").doesNotContain("portal_events_child_open");
        }
    }
    @Test void receiverClaimsOnceAndIssuerRejectsSameTicketInFreshAndForeignFrames()throws Exception{
        try(var c=new ChildSession();var other=c.s.iam.client("engine");var child=c.open()){
            var fresh=c.initial();try(var frame=AuthorityContext.bindRetained(fresh,c::initial)){denied(()->c.s.engine.openPortalEventsChild(fresh,9,()->{}));}
            var foreign=other.authorizeRetained(c.ticket,PortalEventsAuthorityProfileTest.call());
            try(var frame=AuthorityContext.bindRetained(foreign,()->other.authorizeRetained(c.ticket,PortalEventsAuthorityProfileTest.call()))){denied(()->other.openPortalEventsChild(foreign,9,()->{}));}
            assertThat(c.s.iam.observedOperations().stream().filter(o->o.equals("portal_events_child_open")).count()).isEqualTo(3);
            assertThat(child.currentCapture().subject()).isEqualTo(OWNER);
        }
    }
    @Test void localDoubleChildRaceHasOneActualExchangeAndNoRetryAfterFailure()throws Exception{
        try(var s=new Session();var parent=s.open();var threads=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);Callable<Boolean> attempt=()->{gate.await();try{s.gateway.exchangePortalEventsChild(s.source,parent);return true;}catch(RuntimeException denied){return false;}};
            var a=threads.submit(attempt);var b=threads.submit(attempt);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
            assertThat(s.iam.observedOperations().stream().filter(o->o.equals("portal_events_child_exchange")).count()).isEqualTo(1);denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));
        }
        try(var s=new Session();var parent=s.open()){
            denied(()->s.gateway.exchangePortalEventsChild(s.iam.portal(120),parent));denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));
        }
    }
    @Test void issuerAtomicChildReservationRejectsConcurrentAuthenticatedCrossFrameReplay()throws Exception{
        try(var s=new Session();var parent=s.open();var threads=Executors.newFixedThreadPool(2)){
            var request=childExchangeRequest(s,parent);var gate=new CountDownLatch(1);
            Callable<Boolean> attempt=()->{gate.await();return rawSigned(s.iam,"gateway",PortalEventsAuthorityProfileTest.call(),request).path("active").booleanValue();};
            var a=threads.submit(attempt);var b=threads.submit(attempt);gate.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
            // This is issuer concurrency verification only; no raw result is converted to a local cap.
            denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));
        }
    }
    @Test void parentContinuationAndRunAndSourceCannotBeCrossedByAuthenticatedReceiver()throws Exception{
        try(var s=new Session();var parent=s.open();var other=s.open()){
            var a=new LinkedHashMap<>(childExchangeRequest(s,parent));var b=childExchangeRequest(s,other);a.put("parent_immutable",b.get("parent_immutable"));
            assertThat(rawSigned(s.iam,"gateway",PortalEventsAuthorityProfileTest.call(),a).path("active").booleanValue()).isFalse();
            a=new LinkedHashMap<>(childExchangeRequest(s,parent));a.put("subject_token",s.iam.portal(120));assertThat(rawSigned(s.iam,"gateway",PortalEventsAuthorityProfileTest.call(),a).path("active").booleanValue()).isFalse();
            var wrongCall=new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,RunObservationSelectors.events(RUN,Digest.sha256("wrong")));
            assertThat(rawSigned(s.iam,"gateway",wrongCall,childExchangeRequest(s,parent)).path("active").booleanValue()).isFalse();
        }
    }
    @Test void actualTicketAndAuthorizeNeedMatchingPrivateChildMarkerOnBothSides()throws Exception{
        for(String side:List.of("ticket-missing","current-missing","current-mismatch"))try(var s=new Session();var parent=s.open()){
            if(side.equals("ticket-missing"))s.iam.changeTicket=c->{if(c.containsKey("portal_events_child"))c.remove("portal_events_child");};
            if(side.equals("ticket-missing")){denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));continue;}
            var proof=s.gateway.exchangePortalEventsChild(s.source,parent);String ticket=proof.ticket().value();
            s.iam.changeDecision=c->{if(c.containsKey("portal_events_child")){if(side.equals("current-missing"))c.remove("portal_events_child");else c.put("portal_events_child",Map.of());}};
            denied(()->s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call()));
        }
    }
    @Test void signedChildAssociationCannotChangeParentChildActionCeilingSourceCursorOrDeadline()throws Exception{
        for(String field:List.of("parent","engine","source_token_sha256","hard_deadline","child_binding"))try(var s=new Session();var parent=s.open()){
            s.iam.changeTicket=c->{if(c.get("portal_events_child") instanceof JsonNode marker){var changed=marker.deepCopy();var association=(ObjectNode)changed.get("association");if(field.equals("source_token_sha256"))association.put(field,Digest.sha256("wrong"));else if(field.equals("hard_deadline"))association.put(field,s.iam.clock.instant().plusSeconds(56).getEpochSecond());else association.set(field,Json.tree(Map.of()));c.put("portal_events_child",changed);}};
            denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));
        }
    }
    @Test void signedChildOpenWrongTypeAudienceUseDigestAndExpiryCannotAuthorizeReceiver()throws Exception{
        for(String field:List.of("typ","aud","token_use","association_sha256","exp"))try(var s=new Session();var parent=s.open()){
            s.iam.changeTicket=c->{if(c.get("portal_events_child") instanceof JsonNode marker){try{
                var copy=marker.deepCopy();String token=copy.path("child_open").asText();var payload=Json.read(JWSObject.parse(token).getPayload().toString());var claims=Json.mapper().convertValue(payload,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                if(!field.equals("typ"))claims.put(field,field.equals("exp")?s.iam.clock.instant().plusSeconds(56).getEpochSecond():"wrong");
                ((ObjectNode)copy).put("child_open",s.iam.sign(claims,s.iam.key,field.equals("typ")?"wrong+jwt":"agent-portal-events-child-open+jwt"));c.put("portal_events_child",copy);
            }catch(Exception failure){throw new AssertionError(failure);}}};
            denied(()->s.gateway.exchangePortalEventsChild(s.source,parent));
        }
    }
    @Test void actualReceiptExportRequiresActualClientAndChildModeAndLiveHolder()throws Exception{
        try(var c=new ChildSession();var child=c.open();var foreign=c.s.iam.client("engine")){
            denied(()->foreign.portalEventsChildReceipt(child));denied(()->c.s.gateway.portalEventsChildReceipt(child));
            String receipt=c.s.engine.portalEventsChildReceipt(child);assertThat(c.s.engine.portalEventsChildReceipt(child)).isEqualTo(receipt);
            child.close();denied(()->c.s.engine.portalEventsChildReceipt(child));
        }
    }
    @Test void gatewayReceiptHasOneAttemptAndCannotBorrowAnotherParentsOrChildsReceipt()throws Exception{
        try(var a=new ChildSession();var child=a.open()){
            String receipt=a.s.engine.portalEventsChildReceipt(child);a.parent.acceptChildReceipt(receipt);denied(()->a.parent.acceptChildReceipt(receipt));
        }
        try(var s=new Session();var a=s.open();var b=s.open()){
            String ta=s.gateway.exchangePortalEventsChild(s.source,a).ticket().value(),tb=s.gateway.exchangePortalEventsChild(s.source,b).ticket().value();
            var ia=s.engine.authorizeRetained(ta,PortalEventsAuthorityProfileTest.call());var ib=s.engine.authorizeRetained(tb,PortalEventsAuthorityProfileTest.call());
            PortalEventsResponse childA,childB;
            try(var frame=AuthorityContext.bindRetained(ia,()->s.engine.authorizeRetained(ta,PortalEventsAuthorityProfileTest.call()))){childA=s.engine.openPortalEventsChild(ia,9,()->{});}
            try(var frame=AuthorityContext.bindRetained(ib,()->s.engine.authorizeRetained(tb,PortalEventsAuthorityProfileTest.call()))){childB=s.engine.openPortalEventsChild(ib,9,()->{});}
            try(childA;childB){denied(()->a.acceptChildReceipt(s.engine.portalEventsChildReceipt(childB)));denied(()->b.acceptChildReceipt(s.engine.portalEventsChildReceipt(childA)));}
        }
    }
    @Test void receiptNegativeResigningCannotChangeTypeAudienceAssociationOrHardDeadline()throws Exception{
        for(String field:List.of("typ","aud","association_sha256","parent_response_id","child_response_id","exp","engine_admission_request_sha256"))try(var c=new ChildSession();var child=c.open()){
            // Re-signing is solely a negative adversarial fixture; all successful caps use normal HTTP.
            var payload=Json.read(JWSObject.parse(c.s.engine.portalEventsChildReceipt(child)).getPayload().toString());
            var claims=Json.mapper().convertValue(payload,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            if(!field.equals("typ"))claims.put(field,field.equals("exp")?c.s.iam.clock.instant().plusSeconds(56).getEpochSecond():"wrong");
            var changed=c.s.iam.sign(claims,c.s.iam.key,field.equals("typ")?"wrong+jwt":"agent-portal-events-child-receipt+jwt");denied(()->c.parent.acceptChildReceipt(changed));
        }
        try(var c=new ChildSession();var child=c.open()){denied(()->c.parent.acceptChildReceipt("a".repeat(4097)));denied(()->c.parent.acceptChildReceipt(c.s.engine.portalEventsChildReceipt(child)));}
    }
    @Test void childServiceJwtExpiryDoesNotRenewOrReopenButExistingHolderCanCheck()throws Exception{
        try(var c=new ChildSession(1);var child=c.open()){
            String receipt=c.s.engine.portalEventsChildReceipt(child);c.parent.acceptChildReceipt(receipt);c.s.iam.clock.advance(2);
            denied(c::initial);c.s.gateway.checkGatewayPortalEvents(c.parent);c.s.engine.checkPortalEvents(child);
            assertThat(c.s.engine.portalEventsChildReceipt(child)).isEqualTo(receipt);
            for(int i=0;i<10;i++){c.s.iam.clock.advance(4);c.s.gateway.checkGatewayPortalEvents(c.parent);c.s.engine.checkPortalEvents(child);}
            var epoch=(Instant)field(field(field(c.parent,"parent"),"evidence"),"hardDeadline");
            assertThat((Instant)field(field(child,"evidence"),"hardDeadline")).isBeforeOrEqualTo(epoch);
            c.s.iam.clock.advance(13);denied(()->c.s.engine.checkPortalEvents(child));denied(()->c.s.gateway.checkGatewayPortalEvents(c.parent));
            assertThat(c.s.iam.requests.stream().filter(r->Set.of("portal_gateway_events_check","portal_events_child_check").contains(r.path("operation").asText()))).allSatisfy(r->assertThat(r.has("service_ticket")).isFalse());
        }
    }
    @Test void ownFreshVersionsAndRolesAreIndependentAndOneHopCannotRescueAnother()throws Exception{
        try(var s=new Session();var parent=s.open()){
            s.iam.version="engine-v2";String ticket=s.gateway.exchangePortalEventsChild(s.source,parent).ticket().value();var initial=s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call());
            try(var frame=AuthorityContext.bindRetained(initial,()->s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call()));var child=s.engine.openPortalEventsChild(initial,9,()->{})){
                parent.acceptChildReceipt(s.engine.portalEventsChildReceipt(child));var oldG=parent.currentCapture();var oldE=child.currentCapture();
                s.iam.version="g-v3";s.iam.currentRoles=Set.of();s.gateway.checkGatewayPortalEvents(parent);assertThat(parent.isSuperseded(oldG)).isTrue();assertThat(child.isSuperseded(oldE)).isFalse();
                s.iam.engineEventsRevoked=true;s.gateway.checkGatewayPortalEvents(parent);denied(()->s.engine.checkPortalEvents(child));assertThat(parent.currentCapture().subject()).isEqualTo(OWNER);
            }
        }
        try(var c=new ChildSession();var child=c.open()){
            c.s.iam.gatewayEventsRevoked=true;c.s.engine.checkPortalEvents(child);denied(()->c.s.gateway.checkGatewayPortalEvents(c.parent));assertThat(child.currentCapture().subject()).isEqualTo(OWNER);
        }
    }
    @Test void issuerParentCapacityCannotBeEvadedByManyActualGatewayOwnersAndNoEarlyEviction()throws Exception{
        try(var s=new Session()){
            var clients=new ArrayList<HttpCurrentAuthorityClient>();var parents=new ArrayList<GatewayPortalEventsResponse>();
            try{
                // No holder is used to produce material here; retained issuer records stay consumed
                // even after the corresponding local holder closes, so client slots cannot evade 64.
                for(int i=0;i<64;i++){var client=s.iam.client("gateway");clients.add(client);try(var opening=client.beginGatewayPortalEvents(s.source,RUN,9)){var parent=client.openGatewayPortalEvents(opening,RESOURCE,()->{});parents.add(parent);parent.close();}client.close();}
                try(var extra=s.iam.client("gateway")){denied(()->extra.beginGatewayPortalEvents(s.source,RUN,9));}
                assertThat(((Map<?,?>)field(s.iam,"gatewayEventsParents"))).hasSize(64);
                s.iam.clock.advance(56);
                try(var fresh=s.iam.client("gateway");var opening=fresh.beginGatewayPortalEvents(s.source,RUN,9);var parent=fresh.openGatewayPortalEvents(opening,RESOURCE,()->{})){assertThat(parent.currentCapture().subject()).isEqualTo(OWNER);}
            }finally{parents.forEach(GatewayPortalEventsResponse::close);clients.forEach(HttpCurrentAuthorityClient::close);}
        }
    }
    @Test void expiredChildExchangeProofStaysTerminalAfterWallRollback()throws Exception{
        try(var s=new Session();var parent=s.open()){
            s.iam.childTicketSeconds=1;var proof=s.gateway.exchangePortalEventsChild(s.source,parent);s.iam.clock.advance(2);denied(proof::requireVerified);s.iam.clock.advance(-2);denied(proof::requireVerified);
        }
    }
    @Test void receiptCannotChangeValidAdmissionHashWhileKeepingItsImmutableDigest()throws Exception{
        try(var c=new ChildSession();var child=c.open()){
            var payload=Json.read(JWSObject.parse(c.s.engine.portalEventsChildReceipt(child)).getPayload().toString());
            var claims=Json.mapper().convertValue(payload,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            claims.put("engine_admission_request_sha256",Digest.sha256("different-actual-receiver-request"));
            var bad=c.s.iam.sign(claims,c.s.iam.key,"agent-portal-events-child-receipt+jwt");denied(()->c.parent.acceptChildReceipt(bad));
        }
    }
    @Test void standaloneCannotIgnoreAnySignedChildMarkerEvenWhenCurrentOmitsIt()throws Exception{
        for(String marker:List.of("parent_response_id","child_id","child_open","child_association","child_receipt"))try(var s=new Session()){
            s.iam.changeTicket=c->c.put(marker,"not-standalone");
            denied(()->{String ticket=s.gateway.exchange(s.source,PortalEventsAuthorityProfileTest.call()).value();var initial=s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call());
                try(var frame=AuthorityContext.bindRetained(initial,()->s.engine.authorizeRetained(ticket,PortalEventsAuthorityProfileTest.call()));var ignored=s.engine.openPortalEvents(initial,PortalEventsAuthorityProfileTest.binding(initial,s.iam,"must-not-open"),()->{})){}
            });
        }
    }
    @Test void engineGrantAndOperationShrinkParentHardWithoutResettingEitherHop()throws Exception{
        for(String limit:List.of("grant","operation"))try(var c=new ChildSession(60,iam->{if(limit.equals("grant"))iam.engineEventsGrantSeconds=12;else iam.engineEventsOperationSeconds=12;});var child=c.open()){
            c.parent.acceptChildReceipt(c.s.engine.portalEventsChildReceipt(child));
            assertThat((Instant)field(field(child,"evidence"),"hardDeadline")).isEqualTo(c.s.iam.clock.instant().plusSeconds(12));
            for(int i=0;i<2;i++){c.s.iam.clock.advance(4);c.s.engine.checkPortalEvents(child);c.s.gateway.checkGatewayPortalEvents(c.parent);}
            c.s.iam.clock.advance(4);denied(()->c.s.engine.checkPortalEvents(child));c.s.gateway.checkGatewayPortalEvents(c.parent);
        }
    }
    @Test void lateChildExchangeCannotResurrectClosedParentOrFreeActiveWorkEarly()throws Exception{
        try(var s=new Session();var parent=s.open();var thread=Executors.newSingleThreadExecutor()){
            var request=childExchangeRequest(s,parent);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            s.gateway.eventsLifecycle=p->{if(p.equals("child-exchange-returned")){entered.countDown();block(release);}};
            var future=thread.submit(()->s.gateway.exchangePortalEventsChild(s.source,parent));
            try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();parent.close();assertThat(slots(runtime(s.gateway))).isEqualTo(1);assertThat(future.isDone()).isFalse();}
            finally{release.countDown();}
            assertThatThrownBy(()->future.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);until(()->slots(runtime(s.gateway))==0);
            assertThat(rawSigned(s.iam,"gateway",PortalEventsAuthorityProfileTest.call(),request).path("active").booleanValue()).isFalse();assertThat(s.iam.businessSends).hasValue(0);
        }
    }
    @Test void lateActualChildOpenHoldsItsSlotUntilExitAndIssuerDoesNotReopen()throws Exception{
        try(var c=new ChildSession();var thread=Executors.newSingleThreadExecutor();var closer=Executors.newSingleThreadExecutor()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            c.s.engine.eventsLifecycle=p->{if(p.equals("child-open-returned")){entered.countDown();block(release);}};
            var future=thread.submit(c::open);
            Future<?> shutdown=null;
            try{assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();shutdown=closer.submit(c.s.engine::close);assertThatThrownBy(()->future.get(2,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThat(slots(runtime(c.s.engine))).isEqualTo(1);}
            finally{release.countDown();}
            if(shutdown!=null)shutdown.get(3,TimeUnit.SECONDS);until(()->slots(runtime(c.s.engine))==0);
            try(var receiver=c.s.iam.client("engine")){
                var current=receiver.authorizeRetained(c.ticket,PortalEventsAuthorityProfileTest.call());
                try(var frame=AuthorityContext.bindRetained(current,()->receiver.authorizeRetained(c.ticket,PortalEventsAuthorityProfileTest.call()))){denied(()->receiver.openPortalEventsChild(current,9,()->{}));}
            }
            assertThat(c.s.iam.businessSends).hasValue(0);
        }
    }
}
