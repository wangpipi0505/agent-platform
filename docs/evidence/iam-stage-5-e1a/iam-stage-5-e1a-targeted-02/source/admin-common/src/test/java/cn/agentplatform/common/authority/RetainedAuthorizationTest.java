package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.lang.reflect.Modifier;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import static org.assertj.core.api.Assertions.*;

class RetainedAuthorizationTest {
    static Map<String,Object> claims(String token){
        return Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8)),new TypeReference<Map<String,Object>>(){});
    }
    @Test void receivedSignedTicketInitialCannotDisagreeWithItsOperationHash()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var original=gateway.exchange(f.portal(),call).value();
            var c=claims(original);
            c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of("EDITOR"),
                "attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("confidential","restricted"))));
            assertThatThrownBy(()->engine.authorize(f.sign(c,f.key,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void singleSidedOriginCannotBeSilentlyIgnored()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var c=claims(gateway.exchange(f.portal(),call).value());
            c.put("origin_authentication_id","oa1:ticket-only");
            f.changeDecision=m->m.remove("origin_authentication_id");
            assertThatThrownBy(()->engine.authorize(f.sign(c,f.key,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void samePortalAuthenticationCannotChangeOriginalRolesOnAnotherGrant()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var call=LoopbackAuthority.call("gateway","engine");var source=f.portal();gateway.exchange(source,call);
            var c=claims(source);c.put("roles",Set.of("EDITOR"));
            assertThatThrownBy(()->gateway.exchange(f.sign(c,f.portalKey,"at+jwt"),call)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void legacyIntegrationSourceMustHaveFixedVerifiedIssuerAndBoundedJti()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway")){
            var call=LoopbackAuthority.call("gateway","engine");
            var c=f.integrationClaims();c.put("iss","https://untrusted.invalid");
            assertThatThrownBy(()->gateway.exchange(f.integration(c),call)).isInstanceOf(PlatformException.class);
        }
    }
    static Actor initialAB(){
        return new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("confidential","restricted")));
    }
    static void currentA(Map<String,Object> c){
        if(c.containsKey("initial_ceiling"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("confidential","restricted","current-obligation")));
    }
    @Test void sameMappingRetainsFullInitialWhileCurrentNarrowsWithExactlyOneQuery()throws Exception{
        try(var f=new LoopbackAuthority()){
            f.portalActor(initialAB());
            try(var gateway=f.client("gateway");var engine=f.client("engine")){
                var call=LoopbackAuthority.call("gateway","engine");var ticket=gateway.exchange(f.portal(),call);
                f.changeDecision=RetainedAuthorizationTest::currentA;
                int before=f.authorityCalls();var retained=engine.authorizeRetained(ticket.value(),call);
                assertThat(f.authorityCalls()).isEqualTo(before+1);
                assertThat(retained.hasInitialProof()).isTrue();retained.requireVerified();
                assertThat(retained.current().actor().attributes().get("region")).containsExactly("A");
                assertThat(retained.requireInitialCeiling()).isEqualTo(initialAB());
                assertThat(AuthorityProtocol.ceiling(retained.requireInitialCeiling())).isEqualTo(retained.current().operation().ceilingSha256());
                assertThat(retained.current().actor().attributes().get("dataLabels")).contains("confidential","restricted","current-obligation");
                assertThatThrownBy(()->retained.requireInitialCeiling().attributes().get("region").add("C")).isInstanceOf(UnsupportedOperationException.class);
                assertThatThrownBy(()->retained.requireInitialCeiling().attributes().put("region",Set.of())).isInstanceOf(UnsupportedOperationException.class);
                assertThat(retained.toString()).doesNotContain("alice",retained.requireOriginAuthenticationId(),retained.current().decisionId(),ticket.value());
                before=f.authorityCalls();assertThat(engine.authorize(ticket.value(),call).actor()).isEqualTo(retained.current().actor());
                assertThat(f.authorityCalls()).isEqualTo(before+1);
            }
        }
    }
    @Test void legacyDefaultAndPublicValuesCannotMintInitialProof()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var count=new java.util.concurrent.atomic.AtomicInteger();
            CurrentAuthorityClient legacy=new CurrentAuthorityClient(){
                public VerifiedAuthority authorize(String ticket,ServiceCall c){count.incrementAndGet();return current;}
                public ServiceTicket client(ServiceCall c){throw new UnsupportedOperationException();}
                public ServiceTicket exchange(String s,ServiceCall c){throw new UnsupportedOperationException();}
                public ServiceTicket resume(DelegationRef d,ServiceCall c){throw new UnsupportedOperationException();}
                public ServiceTicket forward(VerifiedAuthority a,ServiceCall c){throw new UnsupportedOperationException();}
                public WorkloadAuthority authorizeWorkload(String s,ServiceCall c){throw new UnsupportedOperationException();}
                public VerifiedAuthority approver(DelegationRef d,SubjectKey s,ResourceBinding r){throw new UnsupportedOperationException();}
            };
            for(var value:List.of(RetainedAuthorization.currentOnly(current),legacy.authorizeRetained("not-a-proof",call))){
                assertThat(value.current()).isSameAs(current);assertThat(value.hasInitialProof()).isFalse();
                assertThatThrownBy(value::requireInitialCeiling).isInstanceOf(PlatformException.class);
                assertThatThrownBy(value::requireOriginAuthenticationId).isInstanceOf(PlatformException.class);
                assertThatThrownBy(value::requireVerified).isInstanceOf(PlatformException.class);
            }
            assertThat(count).hasValue(1);
            assertThat(Arrays.stream(RetainedAuthorization.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers()))).isTrue();
            assertThat(Arrays.stream(RetainedAuthorization.class.getMethods()).filter(m->m.getName().equals("requireVerified")).allMatch(m->m.getParameterCount()==0)).isTrue();
            assertThatThrownBy(()->Json.read("{\"initial\":{\"roles\":[\"ADMIN\"]}}",RetainedAuthorization.class)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void originBothAbsentIsCurrentOnlyAndEveryPartialOrMalformedPairDenies()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var c=claims(gateway.exchange(f.portal(),call).value());String origin=(String)c.get("origin_authentication_id");
            c.remove("origin_authentication_id");f.changeDecision=m->m.remove("origin_authentication_id");
            var old=engine.authorizeRetained(f.sign(c,f.key,"at+jwt"),call);
            assertThat(old.hasInitialProof()).isFalse();assertThatThrownBy(old::requireInitialCeiling).isInstanceOf(PlatformException.class);
            for(boolean ticketSide:List.of(true,false))for(Object invalid:Arrays.asList(null,""," ","x".repeat(129),37,true,"different-origin")){
                c.put("origin_authentication_id",origin);
                if(ticketSide){if(invalid==null)c.remove("origin_authentication_id");else c.put("origin_authentication_id",invalid);}
                f.changeDecision=m->{if(!ticketSide){if(invalid==null)m.remove("origin_authentication_id");else m.put("origin_authentication_id",invalid);}};
                String token=f.sign(c,f.key,"at+jwt");
                assertThatThrownBy(()->engine.authorizeRetained(token,call)).as("ticketSide=%s invalid=%s",ticketSide,invalid).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void signedTicketAndDecisionTamperingCannotMintProof()throws Exception{
        var changes=new LinkedHashMap<String,Consumer<Map<String,Object>>>();
        changes.put("initial",c->c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of("EDITOR"),"attributes",initialAB().attributes())));
        changes.put("subject",c->c.put("sub","bob"));changes.put("tenant",c->c.put("tenant_id","other"));
        changes.put("grant",c->c.put("grant_ref","other"));changes.put("grant-expiry",c->c.put("grant_expires_at",1700000500L));
        changes.put("version",c->c.put("authorization_version","v2"));
        changes.put("root",c->c.put("resource",Map.of("kind","run","id","other","version_digest",Digest.sha256("other"))));
        changes.put("operation-id",c->changeOperation(c,"operation_id","other"));
        changes.put("operation-root",c->changeOperation(c,"resource",Map.of("kind","run","id","other","version_digest",Digest.sha256("other"))));
        changes.put("ceiling-hash",c->changeOperation(c,"ceiling_sha256",Digest.sha256("other")));
        changes.put("labels-removed",c->c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of())));
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");String good=gateway.exchange(f.portal(),call).value();
            for(boolean ticketSide:List.of(true,false))for(var change:changes.entrySet()){
                var c=claims(good);if(ticketSide)change.getValue().accept(c);
                f.changeDecision=ticketSide?m->{}:change.getValue();String token=f.sign(c,f.key,"at+jwt");
                assertThatThrownBy(()->engine.authorizeRetained(token,call)).as("%s ticketSide=%s",change.getKey(),ticketSide).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            }
            for(String field:List.of("ticket_jti","subject_caller","nonce","request_sha256")){
                f.changeDecision=c->c.put(field,"wrong");
                assertThatThrownBy(()->engine.authorizeRetained(good,call)).as(field).isInstanceOf(PlatformException.class);
            }
            f.changeDecision=c->{};f.badSignature=true;
            assertThatThrownBy(()->engine.authorizeRetained(good,call)).isInstanceOf(PlatformException.class);
        }
    }
    @SuppressWarnings("unchecked") static void changeOperation(Map<String,Object> c,String field,Object value){
        var op=new LinkedHashMap<>((Map<String,Object>)c.get("operation"));op.put(field,value);c.put("operation",op);
    }
    @Test void originalAuthenticationSurvivesDistinctGrantsForwardAndRealResumeButFreshJtiDiffers()throws Exception{
        for(boolean integration:List.of(false,true))try(var f=new LoopbackAuthority()){
            f.portalActor(LoopbackAuthority.ceiling());
            try(var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
                var call=LoopbackAuthority.call("gateway","engine");String source=integration?f.integration():f.portal(10);
                var a=engine.authorizeRetained(gateway.exchange(source,call).value(),call);
                var other=new ServiceCall(call.target(),call.action(),new ResourceBinding("run","r-other",Digest.sha256("other")));
                var b=engine.authorizeRetained(gateway.exchange(source,other).value(),other);
                assertThat(a.requireOriginAuthenticationId()).hasSize(68).isEqualTo(b.requireOriginAuthenticationId());
                assertThat(a.current().delegation().value()).isNotEqualTo(b.current().delegation().value());
                assertThat(a.current().operation().operationId()).isNotEqualTo(b.current().operation().operationId());
                assertThat(a.current().call().resource()).isNotEqualTo(b.current().call().resource());
                var next=LoopbackAuthority.call("engine","model");
                var forwarded=model.authorizeRetained(engine.forward(a.current(),next).value(),next);
                assertThat(forwarded.requireOriginAuthenticationId()).isEqualTo(a.requireOriginAuthenticationId());
                var binding=new ResponseBinding("observer","sse",call.resource(),Digest.sha256("request"),f.clock.instant().plusSeconds(integration?50:10));
                var observer=engine.open(a.current(),binding);
                String origin=a.requireOriginAuthenticationId();Actor initial=a.requireInitialCeiling();
                f.clock.advance(integration?21:11);
                var resumed=model.authorizeRetained(engine.resume(a.current().delegation(),next).value(),next);
                assertThat(resumed.current().operation().sourceKind()).isEqualTo("DELEGATED");
                assertThat(resumed.requireOriginAuthenticationId()).isEqualTo(origin);assertThat(resumed.requireInitialCeiling()).isEqualTo(initial);
                assertThatThrownBy(()->engine.check(observer)).isInstanceOf(PlatformException.class);
                String fresh=integration?f.integration():f.portal();
                var freshProof=engine.authorizeRetained(gateway.exchange(fresh,call).value(),call);
                assertThat(freshProof.requireOriginAuthenticationId()).isNotEqualTo(origin);
            }
        }
    }
    @Test void verifiedSourceRepresentationDoesNotResetInitialOrPolicyAnchor()throws Exception{
        for(boolean integration:List.of(false,true))try(var f=new LoopbackAuthority()){
            f.portalActor(LoopbackAuthority.ceiling());
            try(var gateway=f.client("gateway");var engine=f.client("engine")){
                var call=LoopbackAuthority.call("gateway","engine");String source=integration?f.integration():f.portal();
                var first=engine.authorizeRetained(gateway.exchange(source,call).value(),call);
                var original=claims(source);var reordered=new TreeMap<>(original);
                String variant=integration?f.integration(reordered):f.sign(reordered,f.portalKey,"at+jwt");
                assertThat(variant).isNotEqualTo(source);
                var second=engine.authorizeRetained(gateway.exchange(variant,call).value(),call);
                assertThat(second.requireOriginAuthenticationId()).isEqualTo(first.requireOriginAuthenticationId());
                assertThat(second.requireInitialCeiling()).isEqualTo(first.requireInitialCeiling());
                for(String field:List.of("exp","tenant_id","sub",integration?"origin":"scope")){
                    var changed=new LinkedHashMap<>(original);changed.put(field,field.equals("exp")?f.clock.instant().plusSeconds(19).getEpochSecond():"changed");
                    String invalid=integration?f.integration(changed):f.sign(changed,f.portalKey,"at+jwt");
                    assertThatThrownBy(()->gateway.exchange(invalid,call)).as(field).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
                }
                if(integration){
                    f.policyVersion="7:2";int calls=f.policyCalls();int grants=f.grantCount();
                    assertThatThrownBy(()->gateway.exchange(variant,call)).isInstanceOf(PlatformException.class);
                    assertThat(f.policyCalls()).isEqualTo(calls+1);assertThat(f.grantCount()).isEqualTo(grants);
                    assertThatThrownBy(()->engine.authorizeRetained(gatewayTicket(f,first),call)).isInstanceOf(PlatformException.class);
                }
            }
        }
    }
    static String gatewayTicket(LoopbackAuthority f,RetainedAuthorization proof){
        return f.ticket(f.operations.get(proof.current().operation().operationId()),"gateway",proof.current().call());
    }
    @Test void fixtureChecksFixedSourceDomainsTypesAndTimeBeforeComputingOrigin()throws Exception{
        for(boolean integration:List.of(false,true))try(var f=new LoopbackAuthority()){
            String source=integration?f.integration():f.portal();var original=claims(source);
            for(String field:List.of("iss","jti","aud","iat","nbf","exp")){
                for(Object bad:Arrays.asList(null,field.equals("iat")||field.equals("nbf")?f.clock.instant().plusSeconds(1).getEpochSecond():37,field.equals("jti")?"j".repeat(129):"wrong")){
                    var c=new LinkedHashMap<>(original);if(bad==null)c.remove(field);else c.put(field,bad);
                    if(field.equals("nbf")&&bad==null)continue; // Optional nbf retains the original finite profile.
                    String token=integration?f.integration(c):f.sign(c,f.portalKey,"at+jwt");
                    assertThatThrownBy(()->f.verifySubject(token,integration)).as("%s %s",field,bad).isInstanceOf(Exception.class);
                }
            }
            var c=new LinkedHashMap<>(original);c.put("iat",f.clock.instant().plusSeconds(1).getEpochSecond());
            String future=integration?f.integration(c):f.sign(c,f.portalKey,"at+jwt");
            assertThatThrownBy(()->f.verifySubject(future,integration)).isInstanceOf(Exception.class);
            if(!integration)assertThatThrownBy(()->f.verifySubject(f.sign(original,f.portalKey,"JWT"),false)).isInstanceOf(Exception.class);
        }
    }
    @Test void proofUsesSameClientClockAndEarliestSignedDeadline()throws Exception{
        for(String deadline:List.of("ticket","current","operation","grant"))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var c=claims(gateway.exchange(f.portal(),call).value());
            long expires=f.clock.instant().plusSeconds(2).getEpochSecond();
            if(!deadline.equals("current"))c.put("exp",expires);
            if(deadline.equals("operation")||deadline.equals("grant"))changeOperation(c,"expires_at",expires);
            if(deadline.equals("grant"))c.put("grant_expires_at",expires);
            f.changeDecision=m->{if(!deadline.equals("ticket"))m.put("exp",expires);
                if(deadline.equals("operation")||deadline.equals("grant"))changeOperation(m,"expires_at",expires);
                if(deadline.equals("grant"))m.put("grant_expires_at",expires);};
            var proof=engine.authorizeRetained(f.sign(c,f.key,"at+jwt"),call);proof.requireVerified();
            f.clock.advance(2);assertThatThrownBy(proof::requireVerified).as(deadline).isInstanceOf(PlatformException.class);
            assertThatThrownBy(proof::requireInitialCeiling).isInstanceOf(PlatformException.class);
        }
    }
}
