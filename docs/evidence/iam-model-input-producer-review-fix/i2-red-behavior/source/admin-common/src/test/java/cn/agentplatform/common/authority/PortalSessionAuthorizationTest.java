package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortalSessionAuthorizationTest {
    // Reflect only to keep the feature's missing API a recorded behavior failure, never compile-red.
    static Object session(CurrentAuthorityClient client,String token) throws Exception {
        try{return CurrentAuthorityClient.class.getMethod("portalSession",String.class).invoke(client,token);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    static Object invoke(Object proof,String method)throws Exception {
        try{return proof.getClass().getMethod(method).invoke(proof);}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    @Test void signedPortalSessionUsesExactlyExchangeAndAuthorizeAndProjectsFourFields()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var proof=session(gateway,f.portal());
            var view=Json.tree(invoke(proof,"view"));
            assertThat(view.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("tenantId","principalId","roles","expiresAt");
            assertThat(view.path("tenantId").asText()).isEqualTo("tenant-a");
            assertThat(view.path("principalId").asText()).isEqualTo("alice");
            assertThat(view.path("roles").toString()).isEqualTo("[\"EDITOR\"]");
            assertThat(view.path("expiresAt").asText()).isEqualTo(f.clock.instant().plusSeconds(40).toString());
            invoke(proof,"requireVerified");
            assertThat(f.requests).extracting(r->r.path("operation").asText()).containsExactly("exchange","authorize");
            assertThat(f.requests).allSatisfy(r->{assertThat(r.path("target").asText()).isEqualTo("gateway");assertThat(r.path("action").asText()).isEqualTo("portal.session.read");});
            assertThat(f.businessSends).hasValue(0);assertThat(f.policyCalls).hasValue(0);
        }
    }
    @Test void unsupportedDefaultCannotMintSessionFromPublicAuthority()throws Exception {
        var fallback=(CurrentAuthorityClient)Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),new Class<?>[]{CurrentAuthorityClient.class},(proxy,method,args)->{
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args);
            throw new AssertionError("A public VA/currentOnly cannot mint a session proof");
        });
        assertThatThrownBy(()->session(fallback,"unused")).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
    }
    @Test void authorizedRoleIntersectionMayBeEmptyAndGrantMayExpireBeforePortal()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.currentRoles=Set.of();f.sessionGrantSeconds=20;
            var view=Json.tree(invoke(session(gateway,f.portal(60)),"view"));
            assertThat(view.path("roles")).isEmpty();
            assertThat(view.path("expiresAt").asText()).isEqualTo(f.clock.instant().plusSeconds(20).toString());
            assertThat(f.operations.values()).allSatisfy(op->assertThat(op.expires()).isBeforeOrEqualTo(op.grant().expires()));
        }
    }
    @Test void invalidPortalAndIntegrationAre401ButUnavailableAuthorityIs503()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            for(String token:List.of("invalid",f.integration(),f.portal(0)))
                assertStatus(401,()->session(gateway,token));
            f.unavailable=true;assertStatus(503,()->session(gateway,f.portal()));
            f.unavailable=false;f.badSignature=true;assertStatus(503,()->session(gateway,f.portal()));
            assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void signedAuthorizeCannotChangeInitialSubjectResourceGrantOriginOperationOrTicket()throws Exception {
        for(String fault:List.of("subject","resource","grant","grant-expiry","origin","operation","ticket","initial","roles")){
            try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
                f.changeDecision=c->{if(!c.containsKey("ticket_jti"))return;
                    switch(fault){
                        case "subject"->c.put("sub","mallory");
                        case "resource"->c.put("resource",Map.of("kind","portal-session","id",Digest.sha256("other"),"version_digest",Digest.sha256("other-auth")));
                        case "grant"->c.put("grant_ref","other");
                        case "grant-expiry"->c.put("grant_expires_at",f.clock.instant().plusSeconds(200).getEpochSecond());
                        case "origin"->c.put("origin_authentication_id","other-authentication");
                        case "operation"->{var op=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));op.put("operation_id","other");c.put("operation",op);}
                        case "ticket"->c.put("ticket_jti","other-ticket");
                        case "initial"->c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of(),"attributes",LoopbackAuthority.ceiling().attributes()));
                        case "roles"->c.put("roles",Set.of("ADMIN","EDITOR"));
                    }
                };
                assertStatus(403,()->session(gateway,f.portal()));assertThat(f.businessSends).hasValue(0);
            }
        }
    }
    @Test void latestAuthorizeWindowSurvivesSpentExchangeWindowWithoutExtendingSessionExpiry()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var original=f.clock.instant();
            f.changeTicket=c->f.clock.advance(4);
            f.changeDecision=c->{if(c.containsKey("ticket_jti"))f.clock.advance(2);};
            var proof=session(gateway,f.portal());
            assertThat(f.clock.instant()).isEqualTo(original.plusSeconds(6));
            assertThat(Json.tree(invoke(proof,"view")).path("expiresAt").asText()).isEqualTo(original.plusSeconds(40).toString());
            invoke(proof,"requireVerified");
            assertThat(f.requests).hasSize(2);
        }
    }
    @Test void sourceAndAuthorizationDeadlinesInvalidatePermanentlyWithDistinctStatus()throws Exception {
        for(String deadline:List.of("source","grant","ticket","current")){
            try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
                if(deadline.equals("grant"))f.sessionGrantSeconds=2;
                if(deadline.equals("ticket"))f.changeTicket=c->c.put("exp",f.clock.instant().plusSeconds(2).getEpochSecond());
                var proof=session(gateway,f.portal(deadline.equals("source")?2:40));
                long advance=deadline.equals("current")?6:2;f.clock.advance(advance);
                int status=deadline.equals("source")?401:403;
                assertStatus(status,()->invoke(proof,"requireVerified"));
                f.clock.advance(-advance);
                assertStatus(status,()->invoke(proof,"requireVerified"));
            }
        }
    }
    @Test void ownerCloseAndConsumedReleaseCannotBeReusedAndProofHasNoPublicMint()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var proof=session(gateway,f.portal());invoke(proof,"requireVerified");
            assertStatus(403,()->invoke(proof,"requireVerified"));
            f.grants.clear();var closing=session(gateway,f.portal());gateway.close();
            assertStatus(503,()->invoke(closing,"requireVerified"));
            assertThat(proof.getClass().getConstructors()).isEmpty();
            assertThat(proof.getClass().getDeclaredFields()).noneMatch(field->field.getType()==String.class);
        }
    }
    @Test void monotonicLatestAuthorizeBudgetCannotBeExtendedByClockRollback()throws Exception {
        try(var f=new LoopbackAuthority()){
            var config=f.config("gateway");
            var ownerClock=java.time.Clock.offset(f.clock,java.time.Duration.ofMillis(500));
            try(var gateway=new HttpCurrentAuthorityClient(config,ownerClock,new WorkloadCredentialResolver(config,name->f.secrets.get("gateway"),true),true)){
                f.changeDecision=c->{if(c.containsKey("ticket_jti"))c.put("exp",f.clock.instant().plusSeconds(1).getEpochSecond());};
                var proof=session(gateway,f.portal());
                f.clock.advance(-1);
                // Real nano time is deliberately not injectable. Spend only the 500ms signed budget.
                new java.util.concurrent.CountDownLatch(1).await(600,java.util.concurrent.TimeUnit.MILLISECONDS);
                assertStatus(403,()->invoke(proof,"requireVerified"));
            }
        }
    }
    @Test void signedElapsedExchangeAndAuthorizeDecisionsAre403BeforeAnyRelease()throws Exception {
        for(boolean exchange:List.of(false,true))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            if(exchange)f.changeTicket=c->f.clock.advance(6);
            else f.changeDecision=c->{if(c.containsKey("ticket_jti"))f.clock.advance(6);};
            assertStatus(403,()->session(gateway,f.portal()));
            assertThat(f.requests).hasSize(exchange?1:2);assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void ordinaryVerifierAndPinnedVerifierKeepExpiredSignedEvidenceUnavailable()throws Exception {
        try(var f=new LoopbackAuthority()){
            var config=f.config("gateway");
            var c=f.base("current-decision",config.authority().audience(),5);
            var token=f.sign(c,f.key,"agent-authority-decision+jwt");f.clock.advance(6);
            assertStatus(503,()->AuthorityResponseVerifier.verifyPinned(token,"agent-authority-decision+jwt","current-decision",config.authority().audience(),5,f.key,config.authority().issuer(),f.clock));
            try(var http=new BoundedIamHttp(config,true)){
                var verifier=new AuthorityResponseVerifier(config,f.clock,http);
                assertStatus(503,()->verifier.verify(token,"agent-authority-decision+jwt","current-decision",config.authority().audience(),5));
            }
        }
    }
    @Test void exchangeMustStillBeCurrentAfterTicketDecoderBeforeAuthorizeRequest()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var lookups=new java.util.concurrent.atomic.AtomicInteger();
            f.observeAuthorityJwks=()->{if(lookups.incrementAndGet()==3)f.clock.advance(6);};
            assertStatus(403,()->session(gateway,f.portal()));
            assertThat(lookups).hasValue(3);
            assertThat(f.requests).extracting(r->r.path("operation").asText()).containsExactly("exchange");
        }
    }
    @Test void verifiedPortalExpiryDuringExchangeAndAuthorizeRemains401()throws Exception {
        for(int stage:List.of(1,3,4))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var lookups=new java.util.concurrent.atomic.AtomicInteger();
            if(stage!=4)f.observeAuthorityJwks=()->{if(lookups.incrementAndGet()==stage)f.clock.advance(2);};
            else f.changeDecision=c->{if(c.containsKey("ticket_jti"))f.clock.advance(2);};
            assertStatus(401,()->session(gateway,f.portal(2)));
            assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void sessionVerifierStillRejectsFutureAndMalformedSignedDatesAsUnavailable()throws Exception {
        for(boolean future:List.of(true,false))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.changeDecision=c->{if(c.containsKey("ticket_jti")){if(future)c.put("iat",f.clock.instant().plusSeconds(1).getEpochSecond());else c.put("exp",1.5);}};
            assertStatus(503,()->session(gateway,f.portal()));
        }
    }
    @Test void ticketAndGrantExpiryDuringPrivateJwksVerificationAre403()throws Exception {
        for(boolean grant:List.of(true,false))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            if(grant)f.sessionGrantSeconds=2;else f.changeTicket=c->c.put("exp",f.clock.instant().plusSeconds(2).getEpochSecond());
            var count=new java.util.concurrent.atomic.AtomicInteger();f.observeAuthorityJwks=()->{if(count.incrementAndGet()==2)f.clock.advance(2);};
            assertStatus(403,()->session(gateway,f.portal()));assertThat(f.requests).hasSize(1);
        }
    }
    @Test void emptyUnsignedAuthorityEnvelopeIsUnavailableInsteadOfAnIdentityDenial()throws Exception {
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.emptyEnvelope=true;assertStatus(503,()->session(gateway,f.portal()));
        }
    }
    @Test void signedElapsedCurrentAndTicketRequireExpiryAfterNotBefore()throws Exception {
        var softly=new org.assertj.core.api.SoftAssertions();
        var observations=new ArrayList<Map<String,Object>>();
        for(String kind:List.of("current","ticket"))for(long nbfOffset:List.of(1L,2L)){
            try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
                var started=f.clock.instant();
                java.util.function.Consumer<Map<String,Object>> malformed=c->{
                    long issued=((Number)c.get("iat")).longValue();
                    c.put("exp",issued+1);c.put("nbf",issued+nbfOffset);
                    // Both dates have elapsed when verified; future-nbf rejection must not mask I1.
                    f.clock.advance(3);
                };
                if(kind.equals("ticket"))f.changeTicket=malformed;
                else f.changeDecision=c->{if(c.containsKey("ticket_jti"))malformed.accept(c);};
                int status;
                try{session(gateway,f.portal(40));status=200;}
                catch(PlatformException rejected){status=rejected.status();}
                String label=kind+(nbfOffset==1?"-nbf-equals-exp":"-nbf-after-exp");
                var operations=f.requests.stream().map(r->r.path("operation").asText()).toList();
                boolean portalLive=started.plusSeconds(40).isAfter(f.clock.instant());
                boolean grantLive=f.grants.values().iterator().next().expires().isAfter(f.clock.instant());
                observations.add(Map.of("variant",label,"iat",started.getEpochSecond(),"exp",started.plusSeconds(1).getEpochSecond(),
                    "nbf",started.plusSeconds(nbfOffset).getEpochSecond(),"now",f.clock.instant().getEpochSecond(),
                    "portalStillValid",portalLive,"grantStillValid",grantLive,"actualStatus",status,"operations",operations));
                softly.assertThat(status).as(label).isEqualTo(503);
                softly.assertThat(f.clock.instant()).as(label+" elapsed clock").isEqualTo(started.plusSeconds(3));
                softly.assertThat(portalLive&&grantLive).as(label+" original source/grant still live").isTrue();
                softly.assertThat(operations).as(label+" real protocol requests").containsExactlyElementsOf(kind.equals("ticket")?List.of("exchange"):List.of("exchange","authorize"));
                softly.assertThat(f.businessSends.get()).as(label+" no business sends").isZero();
            }
        }
        // Save all four observations before the aggregate assertion, including a genuine red run.
        var process=ProcessHandle.current();
        var dir=java.nio.file.Path.of("..",".local","iam-d4a-time-order-fix","variants").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("variants-"+process.pid()+".json"),Json.write(Map.of("pid",process.pid(),
            "processStart",process.info().startInstant().orElseThrow().toString(),"at",java.time.Instant.now().toString(),
            "executedVariants",observations.size(),"observations",observations)),java.nio.file.StandardOpenOption.CREATE_NEW);
        assertThat(observations).hasSize(4);
        softly.assertAll();
    }
    interface Checked {Object run()throws Exception;}
    static void assertStatus(int status,Checked call){assertThatThrownBy(call::run).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(status));}
}
