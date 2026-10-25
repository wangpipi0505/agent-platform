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
        for(boolean exchange:List.of(true,false))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
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
    interface Checked {Object run()throws Exception;}
    static void assertStatus(int status,Checked call){assertThatThrownBy(call::run).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(status));}
}
