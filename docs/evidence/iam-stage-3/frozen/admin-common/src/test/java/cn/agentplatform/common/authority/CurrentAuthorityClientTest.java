package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class CurrentAuthorityClientTest {
    @Test void signedPortalExchangePreservesLocalCeilingAndIndependentClientAuthentication()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var token=gateway.exchange(f.portal(),call);var current=engine.authorize(token.value(),call);
            assertThat(current.actor()).isEqualTo(LoopbackAuthority.ceiling());
            assertThat(f.authenticatedClients).containsExactly("gateway","engine");
            assertThat(f.requests).allSatisfy(r->{assertThat(r.has("roles")).isFalse();assertThat(r.has("attributes")).isFalse();assertThat(r.has("caller")).isFalse();});
            assertThat(current.operation()).isEqualTo(token.operation());assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void issuerCannotRestoreLocallyRemovedAdminOrRegion()throws Exception{
        for(boolean role:List.of(true,false))try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.changeTicket=c->{if(role)c.put("roles",List.of("ADMIN","EDITOR"));else c.put("attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("confidential","restricted")));};
            assertThatThrownBy(()->gateway.exchange(f.portal(),LoopbackAuthority.call("gateway","engine"))).isInstanceOf(PlatformException.class);
        }
    }
    @Test void portalCannotHideBroaderInitialCeilingBehindNarrowCurrentRoles()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            f.changeTicket=c->{
                var broad=new Actor("tenant-a","alice",Set.of("ADMIN","EDITOR"),LoopbackAuthority.ceiling().attributes());
                c.put("initial_ceiling",Map.of("tenant_id",broad.tenantId(),"sub",broad.principalId(),"roles",broad.roles(),"attributes",broad.attributes()));
                var op=Json.mapper().convertValue(c.get("operation"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});op.put("ceiling_sha256",AuthorityProtocol.ceiling(broad));c.put("operation",op);
            };
            assertThatThrownBy(()->gateway.exchange(f.portal(),LoopbackAuthority.call("gateway","engine"))).isInstanceOf(PlatformException.class);
        }
    }
    @Test void noCachedTicketAfterScopeRoleVersionGrantOrNetworkFailureAndExplicitRecoveryWorks()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var call=LoopbackAuthority.call("gateway","engine");var token=gateway.exchange(f.portal(),call);var current=engine.authorize(token.value(),call);
            var next=LoopbackAuthority.call("engine","model");var provider=new IamServiceTicketProvider(f.clock,engine);
            assertThat(model.authorize(provider.issue(current,next).value(),next).actor()).isEqualTo(current.actor());
            for(String failure:List.of("scope","role","version","grant","503","json","signature","jwks")){
                switch(failure){case "scope"->f.scopeAllowed=false;case "role"->f.currentRoles=Set.of();case "version"->f.version="opaque-new";case "grant"->f.revoked=true;case "503"->f.unavailable=true;case "json"->f.invalidJson=true;case "signature"->f.badSignature=true;case "jwks"->f.jwksUnavailable=true;}
                assertThatThrownBy(()->provider.issue(current,next)).as(failure).isInstanceOf(PlatformException.class);
                f.scopeAllowed=true;f.currentRoles=Set.of("EDITOR");f.version="v1";f.revoked=false;f.unavailable=false;f.invalidJson=false;f.badSignature=false;f.jwksUnavailable=false;
            }
            assertThat(provider.issue(current,next).operation()).isEqualTo(current.operation());
            f.clock.advance(6);int count=f.requests.size();
            assertThatThrownBy(()->provider.issue(current,next)).isInstanceOf(PlatformException.class);assertThat(f.requests).hasSize(count);
            f.clock.advance(301);assertThatThrownBy(()->engine.resume(current.delegation(),next)).isInstanceOf(PlatformException.class);assertThat(f.requests).hasSize(count);
            assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void rr2NewDelegatedOperationSurvivesOriginalPortalExpiryButForwardCannotResetItsDeadline()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var old=gateway.exchange(f.portal(10),entry);var first=engine.authorize(old.value(),entry);
            f.clock.advance(11);var next=LoopbackAuthority.call("engine","model");
            assertThatThrownBy(()->engine.forward(first,next)).isInstanceOf(PlatformException.class);
            var resumed=engine.resume(first.delegation(),next);var current=model.authorize(resumed.value(),next);
            assertThat(resumed.operation().sourceKind()).isEqualTo("DELEGATED");assertThat(resumed.operation().originKind()).isEqualTo("PORTAL");
            assertThat(current.actor()).isEqualTo(LoopbackAuthority.ceiling());assertThat(resumed.operation().operationId()).isNotEqualTo(old.operation().operationId());
            assertThat(resumed.operation().ceilingSha256()).isEqualTo(old.operation().ceilingSha256());
            assertThat(f.requests.get(f.requests.size()-2).fieldNames()).toIterable().containsExactlyInAnyOrder("grant_ref","operation","target","action","resource","nonce");
            f.revoked=true;assertThatThrownBy(()->engine.resume(first.delegation(),next)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void rr2MissingOperationPolicyOrSignedOperationFieldAndForwardDeadlineChangeFail()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var entry=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),entry).value(),entry);var next=LoopbackAuthority.call("engine","model");
            f.operationPolicy=false;assertThatThrownBy(()->engine.resume(current.delegation(),next)).isInstanceOf(PlatformException.class);f.operationPolicy=true;
            f.changeTicket=c->c.remove("operation");assertThatThrownBy(()->engine.resume(current.delegation(),next)).isInstanceOf(PlatformException.class);
            f.changeTicket=c->{var op=Json.mapper().convertValue(c.get("operation"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});op.put("expires_at",f.clock.instant().plusSeconds(200).getEpochSecond());c.put("operation",op);};
            assertThatThrownBy(()->engine.forward(current,next)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void rr2ResumeCannotReintroduceRolesOutsideOriginalSignedGrantCeiling()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var entry=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(10),entry).value(),entry);
            f.clock.advance(11);f.changeTicket=c->c.put("roles",List.of("EDITOR","ADMIN"));
            assertThatThrownBy(()->engine.resume(current.delegation(),LoopbackAuthority.call("engine","model"))).isInstanceOf(PlatformException.class);
        }
    }
    @Test void authenticatedEngineCannotChooseAnotherTenantSubjectResourceActionOrMissingGrant()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var entry=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),entry).value(),entry);
            var next=LoopbackAuthority.call("engine","model");
            assertThatThrownBy(()->engine.resume(new DelegationRef("missing",current.delegation().subject(),current.delegation().expiresAt()),next)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->engine.resume(new DelegationRef(current.delegation().value(),new SubjectKey("other","other"),current.delegation().expiresAt()),next)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->engine.resume(current.delegation(),new ServiceCall(next.target(),next.action(),new ResourceBinding("run","other","v2")))).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->engine.forward(current,new ServiceCall("model","arbitrary.execute",next.resource()))).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->engine.exchange(f.portal(),entry)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
    @Test void signedReplyTamperNonceTicketJtiCallerResourceAndExpiryAreNotAccepted()throws Exception{
        var changes=new LinkedHashMap<String,Consumer<Map<String,Object>>>();
        changes.put("nonce",c->c.put("nonce","other"));changes.put("jti",c->c.put("ticket_jti","other"));changes.put("caller",c->c.put("subject_caller","monitor"));
        changes.put("resource",c->c.put("resource",Map.of("kind","run","id","other","version_digest","other")));
        changes.put("expiry",c->c.put("exp",0));changes.put("version",c->c.put("authorization_version","older"));
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var entry=LoopbackAuthority.call("gateway","engine");var ticket=gateway.exchange(f.portal(),entry);
            for(var change:changes.entrySet()){
                f.changeDecision=change.getValue();assertThatThrownBy(()->engine.authorize(ticket.value(),entry)).as(change.getKey()).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void approvalUsesRecordedApproverAndRevocationIsLive()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var approved=engine.approver(current.delegation(),new SubjectKey("tenant-a","approver"),call.resource());
            assertThat(approved.actor().roles()).containsExactly("APPROVER");
            assertThatThrownBy(()->engine.approver(current.delegation(),new SubjectKey("tenant-a","alice"),call.resource())).isInstanceOf(PlatformException.class);
            f.approverAllowed=false;assertThatThrownBy(()->engine.approver(current.delegation(),new SubjectKey("tenant-a","approver"),call.resource())).isInstanceOf(PlatformException.class);
        }
    }
    @Test void integrationPolicyUsesIndependentHttpAssertionOnEveryDecisionWithoutRecursion()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");
            var ticket=gateway.exchange(f.integration(),call);assertThat(f.policyCalls).hasValue(1);
            var current=engine.authorize(ticket.value(),call);assertThat(f.policyCalls).hasValue(2);
            assertThat(current.operation().sourceKind()).isEqualTo("INTEGRATION");
            engine.resume(current.delegation(),LoopbackAuthority.call("engine","model"));assertThat(f.policyCalls).hasValue(3);
            assertThat(f.resolverToAuthority).hasValue(0);assertThat(f.businessSends).hasValue(0);
        }
    }
    @Test void integrationResolverMissingInactiveOldVersionWrongTrustDomainAndBindingsDeny()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var ticket=gateway.exchange(f.integration(),call);
            for(String failure:List.of("unavailable","inactive","subject","version","signature","field","binding")){
                switch(failure){case "unavailable"->f.policyUnavailable=true;case "inactive"->f.policyActive=false;case "subject"->f.policySubjectAllowed=false;case "version"->f.policyVersion="7:2";case "signature"->f.wrongPolicyKey=true;case "field"->f.omitPolicyField=true;case "binding"->f.wrongPolicyBinding=true;}
                int calls=f.policyCalls.get();assertThatThrownBy(()->engine.authorize(ticket.value(),call)).as(failure).isInstanceOf(PlatformException.class);assertThat(f.policyCalls).hasValue(calls+1);
                f.policyUnavailable=false;f.policyActive=true;f.policySubjectAllowed=true;f.policyVersion="7:1";f.wrongPolicyKey=false;f.omitPolicyField=false;f.wrongPolicyBinding=false;
            }
            assertThat(engine.authorize(ticket.value(),call).actor()).isEqualTo(LoopbackAuthority.ceiling());assertThat(f.resolverToAuthority).hasValue(0);
            f.resolver.removeContext("/internal/iam/integration-policy/resolve");
            assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void r3ResolverStringActiveCannotBeCoercedIntoPermission()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var ticket=gateway.exchange(f.integration(),call);
            f.wrongPolicyType=true;
            assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);
            assertThat(f.policyCalls).hasValue(2);assertThat(f.resolverToAuthority).hasValue(0);
        }
    }
    @Test void integrationBadMacCrossUseClientAndTtlNeverExchange()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");String token=f.integration();String changed=token.substring(0,token.length()-5)+"AAAAA";
            assertThatThrownBy(()->gateway.exchange(changed,call)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(()->engine.exchange(token,call)).isInstanceOf(PlatformException.class);
            var claims=f.integrationClaims();claims.put("exp",f.clock.instant().plusSeconds(301).getEpochSecond());
            String tooLong=f.integration(claims);assertThatThrownBy(()->gateway.exchange(tooLong,call)).isInstanceOf(PlatformException.class);
            claims=f.integrationClaims();claims.put("token_use","service");String wrong=f.integration(claims);
            assertThatThrownBy(()->gateway.exchange(wrong,call)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
    @Test void workloadCannotBecomeAUserAndContextIsNotInherited()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var app=f.client("app")){
            var call=new ServiceCall("app","integration.origin.check",new ResourceBinding("origin","embed","v1"));var ticket=gateway.client(call);
            assertThat(app.authorizeWorkload(ticket.value(),call).caller()).isEqualTo("gateway");
            assertThatThrownBy(()->app.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);
            var userCall=LoopbackAuthority.call("gateway","app");var current=app.authorize(gateway.exchange(f.portal(),userCall).value(),userCall);
            try(var scope=AuthorityContext.bind(current)){
                assertThat(AuthorityContext.current()).isSameAs(current);
                try(var pool=Executors.newSingleThreadExecutor()){assertThat(pool.submit(()->{try{AuthorityContext.current();return false;}catch(PlatformException e){return true;}}).get(2,TimeUnit.SECONDS)).isTrue();}
            }
            assertThatThrownBy(AuthorityContext::current).isInstanceOf(PlatformException.class);
        }
    }
}
