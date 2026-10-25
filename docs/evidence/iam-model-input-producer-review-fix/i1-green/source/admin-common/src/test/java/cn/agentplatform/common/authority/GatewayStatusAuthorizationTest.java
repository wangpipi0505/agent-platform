package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;

class GatewayStatusAuthorizationTest {
    LoopbackAuthority iam;HttpCurrentAuthorityClient gateway;String source;
    static final String RUN="r_"+"a".repeat(32),OTHER="r_"+"b".repeat(32),SHA=Digest.sha256("accepted");
    static final SubjectKey OWNER=new SubjectKey("o1c-common","alice");
    @BeforeEach void start()throws Exception {iam=new LoopbackAuthority();iam.portalActor(new Actor(OWNER.tenantId(),OWNER.principalId(),Set.of("EDITOR"),Map.of("region",Set.of("A"),"dataLabels",Set.of("initial"))));gateway=iam.client("gateway");source=iam.portal();iam.registerObservation(OWNER,RUN,SHA);iam.registerObservation(OWNER,OTHER,SHA);}
    @AfterEach void close(){gateway.close();iam.close();System.out.println("O1C_AUTH_RESOURCES_CLOSED=true");}
    @Test void realIndependentFixedQueryAndMainHavePrivateProofsAndSameOriginalSource(){
        var q=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);var m=gateway.exchangeGatewayRunStatus(source,RunObservationSelectors.status(RUN,SHA));m.requireSameQuery(q);
        assertThat(q.ticket().operation().grantRef()).isNotEqualTo(m.ticket().operation().grantRef());assertThat(q.ticket().operation().operationId()).isNotEqualTo(m.ticket().operation().operationId());
        assertThat(iam.observedOperations()).containsExactly("exchange","exchange");assertThat(iam.requests).allSatisfy(r->{assertThat(r.path("subject_token").asText()).isEqualTo(source);assertThat(r.path("target").asText()).isEqualTo("engine");});
        for(var type:List.of(HttpCurrentAuthorityClient.GatewayObservationQueryAuthorization.class,HttpCurrentAuthorityClient.GatewayStatusAuthorization.class))assertThat(type.getDeclaredConstructors()).allSatisfy(c->assertThat(Modifier.isPrivate(c.getModifiers())).isTrue());
    }
    @Test void localCurrentExpiryIs503AndOriginalSourceExpiryIs401(){
        var q=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);iam.advanceClock(Duration.ofSeconds(6));expect(q::requireVerified,503);
        iam.advanceClock(Duration.ofSeconds(35));expect(()->gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN),401);
    }
    @Test void closedOwnerInvalidatesQueryAndMainWithoutNewCalls(){
        var q=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);var m=gateway.exchangeGatewayRunStatus(source,RunObservationSelectors.status(RUN,SHA));int count=iam.authorityCalls();gateway.close();expect(q::requireVerified,503);expect(m::requireVerified,503);assertThat(iam.authorityCalls()).isEqualTo(count);
    }
    @Test void internalTicketJwtFailureNeverEscapesAsOriginalPortal401(){
        iam.ticketMutation(c->c.put("aud","wrong-engine"));expect(()->gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN),503);assertThat(iam.authorityCalls()).isEqualTo(1);
    }
    @Test void sameOwnerDifferentRunAndDifferentOriginalAuthenticationCannotSwapMain(){
        var q=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);var m=gateway.exchangeGatewayRunStatus(source,RunObservationSelectors.status(OTHER,SHA));expect(()->m.requireSameQuery(q),403);
        var different=gateway.exchangeGatewayRunStatus(iam.portal(),RunObservationSelectors.status(RUN,SHA));expect(()->different.requireSameQuery(q),403);
    }
    @Test void wrongSelectorSubjectAndSnapshotAreDeniedBySignedExchange(){
        expect(()->gateway.exchangeGatewayRunObservationQuery(source,new SubjectKey("other","alice"),RUN),403);
        expect(()->gateway.exchangeGatewayRunStatus(source,RunObservationSelectors.status(RUN,Digest.sha256("wrong"))),403);
        assertThat(iam.observedOperations()).containsExactly("exchange","exchange");
    }
    @Test void sourceAndInternalLifetimeLatchSurviveWallRollback()throws Exception {
        var q=gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN);iam.advanceClock(Duration.ofSeconds(6));expect(q::requireVerified,503);iam.advanceClock(Duration.ofSeconds(-6));expect(q::requireVerified,503);
        var shortSource=iam.portal(1);var m=gateway.exchangeGatewayRunStatus(shortSource,RunObservationSelectors.status(RUN,SHA));Thread.sleep(1100);iam.advanceClock(Duration.ofSeconds(-5));expect(m::requireVerified,401);
    }
    @Test void unknownJwtAndVerifierUnavailableAreDifferent(){
        expect(()->gateway.exchangeGatewayRunObservationQuery("bad",OWNER,RUN),401);iam.jwksUnavailable=true;expect(()->gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN),503);assertThat(iam.authorityCalls()).isEqualTo(1);
    }
    @Test void signedWrongPurposeCallerDelegationAndRequestSourceFailClosed(){
        for(String field:List.of("target","action","scope","caller","client_id","authority_mode","operation.source_kind","origin_authentication_id")){
            iam.clearFiniteGrants();iam.registerObservation(OWNER,RUN,SHA);
            iam.ticketMutation(c->{if(field.equals("operation.source_kind")){var operation=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));operation.put("source_kind","DELEGATED");c.put("operation",operation);}else c.put(field,field.equals("origin_authentication_id")?"":"wrong-purpose");});
            expect(()->gateway.exchangeGatewayRunObservationQuery(source,OWNER,RUN),403);assertThat(iam.authorityCalls()).isEqualTo(1);
        }
    }
    static void expect(Runnable action,int status){assertThatThrownBy(action::run).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(status));}
}
