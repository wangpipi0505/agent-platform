package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class GatewayRunAuthorizationTest {
    private static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("published-v1"));
    private static final ServiceCall RUN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
    @Test void signedCaptureKeepsMainBindingAndWorksWithoutThreadLocalsOrAuthorityRefresh()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            String portal=f.portal();var proof=gateway.exchangeGatewayPublishedRun(portal,ROOT);var ticket=proof.ticket();
            var claims=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(ticket.value().split("\\.")[1]));
            assertThat(proof.operation()).isEqualTo(ticket.operation());assertThat(proof.operation().subject()).isEqualTo(new SubjectKey("tenant-a","alice"));
            assertThat(proof.operation().sourceKind()).isEqualTo("PORTAL");assertThat(proof.operation().originKind()).isEqualTo("PORTAL");
            assertThat(proof.operation().resource()).isEqualTo(ROOT);AuthorityProtocol.call(claims,RUN);
            assertThat(AuthorityProtocol.actor(claims.get("initial_ceiling"))).isEqualTo(LoopbackAuthority.ceiling());
            assertThat(AuthorityProtocol.text(claims,"origin_authentication_id")).startsWith("oa1:");
            var current=engine.authorize(ticket.value(),RUN);assertThat(current.operation()).isEqualTo(proof.operation());
            assertThat(current.actor()).isEqualTo(LoopbackAuthority.ceiling());
            assertThatThrownBy(()->gateway.authorize(ticket.value(),RUN)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            int calls=f.authorityCalls();SecurityContextHolder.clearContext();assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{assertThat(worker.submit(()->{proof.requireVerified();return proof.operation();}).get(2,TimeUnit.SECONDS)).isEqualTo(ticket.operation());}
            finally{worker.shutdownNow();assertThat(worker.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
            proof.requireVerified();assertThat(f.authorityCalls()).isEqualTo(calls);assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void signedCurrentExpiresWhileActualTicketRemainsValidAndClockRollbackCannotReviveProof()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            f.changeDecision=c->c.put("exp",f.clock.instant().plusSeconds(1).getEpochSecond());
            var proof=gateway.exchangeGatewayPublishedRun(f.portal(),ROOT);var ticket=proof.ticket();f.clock.advance(1);
            assertThat(ticket.expiresAt()).isAfter(f.clock.instant());assertThat(ticket.operation().expiresAt()).isAfter(f.clock.instant());
            assertThat(engine.authorize(ticket.value(),RUN).operation()).isEqualTo(ticket.operation());
            int count=f.authorityCalls();assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
            f.clock.advance(-1);assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);assertThat(f.authorityCalls()).isEqualTo(count);
        }
    }
    @Test void laterTicketJwkVerificationCannotReturnProofAfterSignedCurrentExpiry()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            AtomicInteger keys=new AtomicInteger();Instant start=f.clock.instant();
            f.server.removeContext("/jwks");f.server.createContext("/jwks",x->{
                // Actual verifier fetches once for signed query, then once for signed ticket.
                if(keys.incrementAndGet()==2)f.clock.advance(6);
                LoopbackAuthority.send(x,200,new JWKSet(f.key.toPublicJWK()).toString());
            });
            try{assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(f.portal(),ROOT)).isInstanceOf(PlatformException.class);}
            finally{assertThat(keys).hasValue(2);assertThat(f.requests).hasSize(1);assertThat(f.requests.getFirst().path("operation").asText()).isEqualTo("exchange");
                assertThat(f.clock.instant()).isEqualTo(start.plusSeconds(6));assertThat(f.operations.values().iterator().next().expires()).isAfter(f.clock.instant());
                f.server.removeContext("/jwks");f.server.createContext("/jwks",x->LoopbackAuthority.send(x,200,new JWKSet(f.key.toPublicJWK()).toString()));}
        }
    }
    @ParameterizedTest @ValueSource(strings={"source","ticket","operation","grant"})
    void signedDeadlineMinimumRejectsWithoutBreakingTicketOperationGrantOrdering(String boundary)throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            if(!boundary.equals("source"))f.changeTicket=c->{
                long limit=f.clock.instant().plusSeconds(1).getEpochSecond();c.put("exp",limit);
                if(!boundary.equals("ticket")){var op=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));op.put("expires_at",limit);c.put("operation",op);}
                if(boundary.equals("grant"))c.put("grant_expires_at",limit);
            };
            var proof=gateway.exchangeGatewayPublishedRun(f.portal(boundary.equals("source")?1:40),ROOT);var ticket=proof.ticket();
            assertThat(ticket.expiresAt()).isBeforeOrEqualTo(ticket.operation().expiresAt());f.clock.advance(1);
            assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);f.clock.advance(-1);
            assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
        }
    }
    @Test void closeInvalidatesExistingCaptureAndPreventsNewExchange()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var proof=gateway.exchangeGatewayPublishedRun(f.portal(),ROOT);int count=f.authorityCalls();gateway.close();
            assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(f.portal(),ROOT)).isInstanceOf(PlatformException.class);assertThat(f.authorityCalls()).isEqualTo(count);
        }
    }
    @Test void closeDuringSignedExchangeDoesNotMintCapture()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var existing=gateway.exchangeGatewayPublishedRun(f.portal(),ROOT);
            AtomicInteger keys=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);
            f.server.removeContext("/jwks");f.server.createContext("/jwks",x->{
                try{if(keys.incrementAndGet()==2){entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("fixture-release");}LoopbackAuthority.send(x,200,new JWKSet(f.key.toPublicJWK()).toString());}
                catch(Exception e){LoopbackAuthority.send(x,503,"fixture-closed");}finally{if(keys.get()>=2)exited.countDown();}
            });
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try{String portal=f.portal();var exchange=workers.submit(()->gateway.exchangeGatewayPublishedRun(portal,ROOT));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                var closing=workers.submit(gateway::close);
                // The closed flag must be published before close waits for any JDK transport.
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean rejected=false;
                while(System.nanoTime()<until&&!rejected){try{existing.requireVerified();java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}catch(PlatformException expected){rejected=true;}}
                assertThat(rejected).isTrue();release.countDown();closing.get(3,TimeUnit.SECONDS);
                assertThatThrownBy(()->exchange.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            }finally{release.countDown();workers.shutdownNow();assertThat(workers.awaitTermination(4,TimeUnit.SECONDS)).isTrue();assertThat(exited.await(2,TimeUnit.SECONDS)).isTrue();}
        }
    }
    @Test void monotonicBudgetExpiresEvenWhenWallClockRollsBackBeforeFirstRecheck()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.changeDecision=c->c.put("exp",f.clock.instant().plusSeconds(1).getEpochSecond());
            var proof=gateway.exchangeGatewayPublishedRun(f.portal(),ROOT);f.clock.advance(-1);
            // A real elapsed-time boundary tests System.nanoTime; no production ticker hook.
            assertThat(new CountDownLatch(1).await(1100,TimeUnit.MILLISECONDS)).isFalse();
            assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
        }
    }
    @Test void separateCapturesUseFreshNonceOperationAndGrant()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            String portal=f.portal();var a=gateway.exchangeGatewayPublishedRun(portal,ROOT);var b=gateway.exchangeGatewayPublishedRun(portal,ROOT);
            assertThat(a.operation().operationId()).isNotEqualTo(b.operation().operationId());assertThat(a.operation().grantRef()).isNotEqualTo(b.operation().grantRef());
            assertThat(f.requests.get(0).path("nonce")).isNotEqualTo(f.requests.get(1).path("nonce"));a.requireVerified();b.requireVerified();
        }
    }
    @Test void finiteApiRejectsOtherServiceModeRootAndNonPortalSourcesBeforeAuthority()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            String portal=f.portal();assertThatThrownBy(()->engine.exchangeGatewayPublishedRun(portal,ROOT)).isInstanceOf(PlatformException.class);
            var c=f.config("gateway");var dev=new AuthorityProperties("test",c.serviceId(),c.portal(),c.authority(),c.integration(),null,null,false,null);
            try(var wrong=new HttpCurrentAuthorityClient(dev,f.clock,new WorkloadCredentialResolver(dev,n->f.secrets.get("gateway"),true),true)){
                assertThatThrownBy(()->wrong.exchangeGatewayPublishedRun(portal,ROOT)).isInstanceOf(PlatformException.class);
            }
            for(var root:List.of(new ResourceBinding("run","app-a@1",ROOT.versionDigest()),new ResourceBinding("app-version","app-a@0",ROOT.versionDigest()),new ResourceBinding("app-version","app-a@01",ROOT.versionDigest()),new ResourceBinding("app-version","../a@1",ROOT.versionDigest()),new ResourceBinding("app-version","a@9223372036854775808",ROOT.versionDigest()),new ResourceBinding("app-version","a@1","wrong")))
                assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(portal,root)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(portal,null)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(f.integration(),ROOT)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            for(String use:List.of("user-no-access","service","workload")){
                var claims=f.base(use.equals("user-no-access")?"user":use,"portal-gateway",40);claims.put("iss",f.issuer()+"/portal");claims.put("sub","alice");claims.put("tenant_id","tenant-a");claims.put("client_id","portal-web");claims.put("roles",Set.of("EDITOR"));claims.put("attributes",Map.of("region",List.of("A"),"dataLabels",List.of("restricted")));claims.put("scope","other");
                String token=f.sign(claims,f.portalKey,"at+jwt");assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(token,ROOT)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            }
            assertThat(f.authorityCalls()).isZero();
        }
    }
    @ParameterizedTest @ValueSource(strings={"nonce","hash","inactive","current","issuer","audience","caller","target","action","subject","initial","version","operation","missing","expiry","source","origin"})
    void signedTamperingNeverMintsCapture(String failure)throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            switch(failure){
                case "nonce"->f.changeDecision=c->c.put("nonce","wrong");case "hash"->f.changeDecision=c->c.put("request_sha256",Digest.sha256("wrong"));
                case "inactive"->f.changeDecision=c->c.put("active",false);case "current"->f.changeDecision=c->c.put("current",false);
                case "issuer"->f.changeTicket=c->c.put("iss","https://wrong.invalid");case "audience"->f.changeTicket=c->c.put("aud","urn:agent-platform:service:gateway");
                case "caller"->f.changeTicket=c->c.put("caller","monitor");case "target"->f.changeTicket=c->c.put("target","app");
                case "action"->f.changeTicket=c->c.put("action","other");case "subject"->f.changeTicket=c->c.put("sub","other");
                case "initial"->f.changeTicket=c->c.put("initial_ceiling",Map.of());case "version"->f.changeTicket=c->c.remove("authorization_version");
                case "operation"->f.changeTicket=c->c.remove("operation");case "missing"->f.changeTicket=c->c.remove("grant_expires_at");
                case "expiry"->f.changeTicket=c->c.put("grant_expires_at",f.clock.instant().getEpochSecond());
                case "origin"->f.changeTicket=c->c.remove("origin_authentication_id");
                case "source"->f.changeTicket=c->{var op=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));op.put("source_kind","DELEGATED");c.put("operation",op);};
            }
            assertThatThrownBy(()->gateway.exchangeGatewayPublishedRun(f.portal(),ROOT)).as(failure).isInstanceOf(PlatformException.class);
        }
    }
}
