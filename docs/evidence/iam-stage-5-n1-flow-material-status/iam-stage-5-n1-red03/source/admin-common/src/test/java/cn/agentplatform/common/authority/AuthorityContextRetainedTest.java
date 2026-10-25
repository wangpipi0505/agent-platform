package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Frame lifecycle checks are isolated from servlet routing so failures cannot hide in HTTP 403. */
class AuthorityContextRetainedTest {
    @Test void successfulRefreshReplacesSnapshotAndOldIdentityFailureRetiresTheCurrentFrame()throws Exception{
        try(var f=new LoopbackAuthority()){
            f.portalActor(RetainedAuthorizationTest.initialAB());
            try(var gateway=f.client("gateway");var engine=f.client("engine")){
                var call=LoopbackAuthority.call("gateway","engine");String ticket=gateway.exchange(f.portal(),call).value();
                f.changeDecision=RetainedAuthorizationTest::currentA;
                var initial=engine.authorizeRetained(ticket,call);
                try(var frame=AuthorityContext.bindRetained(initial,()->engine.authorizeRetained(ticket,call))){
                    int queries=f.authorityCalls();AuthorityContext.requireBound(initial);assertThat(f.authorityCalls()).isEqualTo(queries);
                    f.clock.advance(1);
                    var next=AuthorityContext.requireInitialFor(initial.current().actor(),call.resource());
                    assertThat(f.authorityCalls()).isEqualTo(queries+1);assertThat(next).isNotSameAs(initial);
                    assertThat(next.current().decisionId()).isNotEqualTo(initial.current().decisionId());
                    assertThat(next.requireInitialCeiling()).isEqualTo(initial.requireInitialCeiling());
                    assertThat(next.requireOriginAuthenticationId()).isEqualTo(initial.requireOriginAuthenticationId());
                    AuthorityContext.requireBound(next);
                    assertThat(next.current().actor().attributes().get("region")).containsExactly("A");
                    assertThatThrownBy(()->AuthorityContext.requireBound(initial)).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(()->AuthorityContext.requireBound(next)).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
                }
            }
        }
    }
    @Test void everyRefreshFailureRemainsTerminalAfterTheCauseIsRemoved()throws Exception{
        for(String failure:List.of("403","503","expired","wrong-root","wider-caller","origin","initial","downgrade","wider-current"))try(var f=new LoopbackAuthority()){
            f.portalActor(RetainedAuthorizationTest.initialAB());
            try(var gateway=f.client("gateway");var engine=f.client("engine")){
                var call=LoopbackAuthority.call("gateway","engine");String ticket=gateway.exchange(f.portal(),call).value();
                f.changeDecision=RetainedAuthorizationTest::currentA;
                var captured=engine.authorizeRetained(ticket,call);
                var downgrade=new java.util.concurrent.atomic.AtomicBoolean(false);
                try(var frame=AuthorityContext.bindRetained(captured,()->downgrade.get()?RetainedAuthorization.currentOnly(captured.current()):engine.authorizeRetained(ticket,call))){
                    switch(failure){
                        case "403"->f.revoked=true;case "503"->f.unavailable=true;case "expired"->f.clock.advance(5);
                        case "origin"->f.changeDecision=c->c.put("origin_authentication_id","different");
                        case "initial"->f.changeDecision=c->c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of("EDITOR"),"attributes",LoopbackAuthority.ceiling().attributes()));
                        case "downgrade"->downgrade.set(true);
                        case "wider-current"->f.changeDecision=c->{};
                    }
                    var actor=failure.equals("wider-caller")?RetainedAuthorizationTest.initialAB():captured.current().actor();
                    var resource=failure.equals("wrong-root")?new ResourceBinding("run","other",Digest.sha256("other")):call.resource();
                    assertThatThrownBy(()->AuthorityContext.requireInitialFor(actor,resource)).as(failure).isInstanceOf(PlatformException.class);
                    f.revoked=false;f.unavailable=false;f.changeDecision=RetainedAuthorizationTest::currentA;downgrade.set(false);
                    if(failure.equals("expired"))f.clock.advance(-5);
                    int calls=f.authorityCalls();
                    assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(()->AuthorityContext.requireFor(captured.current().actor(),call.resource())).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(()->AuthorityContext.requireInitialFor(captured.current().actor(),call.resource())).isInstanceOf(PlatformException.class);
                    assertThatThrownBy(()->AuthorityContext.requireBound(captured)).isInstanceOf(PlatformException.class);
                    assertThat(f.authorityCalls()).isEqualTo(calls);
                }
            }
        }
    }
    @Test void currentOnlyBindingAndMissingProofCannotUpgradeThroughRefresh()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");String ticket=gateway.exchange(f.portal(),call).value();
            var proof=engine.authorizeRetained(ticket,call);var current=proof.current();
            try(var old=AuthorityContext.bind(current)){
                assertThat(AuthorityContext.current()).isSameAs(current);
                assertThatThrownBy(()->AuthorityContext.requireInitialFor(current.actor(),call.resource())).isInstanceOf(PlatformException.class);
                assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
            }
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            try(var old=AuthorityContext.bindRetained(RetainedAuthorization.currentOnly(current),()->{calls.incrementAndGet();return proof;})){
                assertThatThrownBy(()->AuthorityContext.requireInitialFor(current.actor(),call.resource())).isInstanceOf(PlatformException.class);
                assertThat(calls).hasValue(0);
            }
            try(var old=AuthorityContext.bind(current)){
                assertThatThrownBy(()->AuthorityContext.requireBound(proof)).isInstanceOf(PlatformException.class);
            }
            var c=RetainedAuthorizationTest.claims(ticket);c.remove("origin_authentication_id");f.changeDecision=m->m.remove("origin_authentication_id");
            String legacy=f.sign(c,f.key,"at+jwt");var ordinary=engine.authorizeRetained(legacy,call);
            try(var old=AuthorityContext.bindRetained(ordinary,()->engine.authorizeRetained(legacy,call))){
                assertThat(AuthorityContext.requireFor(current.actor(),call.resource()).actor()).isEqualTo(current.actor());
                assertThatThrownBy(()->AuthorityContext.requireInitialFor(current.actor(),call.resource())).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void expiryFoundByLocalBoundOrCurrentReadRetiresFrameEvenIfClockLaterMovesBack()throws Exception{
        for(boolean bound:List.of(true,false))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");String ticket=gateway.exchange(f.portal(),call).value();
            var proof=engine.authorizeRetained(ticket,call);
            try(var frame=AuthorityContext.bindRetained(proof,()->engine.authorizeRetained(ticket,call))){
                int calls=f.authorityCalls();f.clock.advance(5);
                assertThatThrownBy(()->{if(bound)AuthorityContext.requireBound(proof);else AuthorityContext.current();}).isInstanceOf(PlatformException.class);
                f.clock.advance(-5);
                assertThatThrownBy(()->AuthorityContext.requireBound(proof)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
                assertThat(f.authorityCalls()).isEqualTo(calls);
            }
        }
    }
    @Test void closedNonLifoFramesAndDifferentThreadsCannotReuseCapturedProof()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");String ticket=gateway.exchange(f.portal(),call).value();
            var a=engine.authorizeRetained(ticket,call);var b=engine.authorizeRetained(ticket,call);
            try(var outer=AuthorityContext.bindRetained(a,()->engine.authorizeRetained(ticket,call))){
                try(var inner=AuthorityContext.bindRetained(b,()->engine.authorizeRetained(ticket,call))){
                    AuthorityContext.requireBound(b);
                }
                AuthorityContext.requireBound(a);
                try(var executor=Executors.newSingleThreadExecutor()){
                    executor.submit(()->{
                        assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
                        assertThatThrownBy(()->AuthorityContext.requireBound(a)).isInstanceOf(PlatformException.class);
                        assertThatThrownBy(()->AuthorityContext.requireInitialFor(a.current().actor(),call.resource())).isInstanceOf(PlatformException.class);
                    }).get(3,TimeUnit.SECONDS);
                }
                AuthorityContext.requireBound(a);
                try(var inner=AuthorityContext.bindRetained(b,()->engine.authorizeRetained(ticket,call))){
                    outer.close();AuthorityContext.requireBound(b);
                }
                assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
            }
            assertThatThrownBy(()->AuthorityContext.requireBound(a)).isInstanceOf(PlatformException.class);
            try(var nextRequest=AuthorityContext.bindRetained(b,()->engine.authorizeRetained(ticket,call))){
                assertThatThrownBy(()->AuthorityContext.requireBound(a)).isInstanceOf(PlatformException.class);
                assertThatThrownBy(()->AuthorityContext.requireBound(b)).isInstanceOf(PlatformException.class);
            }
        }
    }
}
