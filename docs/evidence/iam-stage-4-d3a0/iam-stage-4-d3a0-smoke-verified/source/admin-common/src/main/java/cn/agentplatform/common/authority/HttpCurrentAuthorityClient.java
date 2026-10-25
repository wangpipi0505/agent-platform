package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Fixed custom adapter profile: signed decision envelopes bind the exact request digest and nonce.
 * The authority owns persisted grants, ceilings and operation policy. No local permission cache exists.
 */
public final class HttpCurrentAuthorityClient implements CurrentAuthorityClient,ResponseAuthorityClient,AutoCloseable {
    private final AuthorityProperties config;
    private final Clock clock;
    private final WorkloadCredentialResolver credentials;
    private final BoundedIamHttp http;
    private final AuthorityResponseVerifier verifier;
    private final TrustedJwtDecoder decoder;
    public HttpCurrentAuthorityClient(AuthorityProperties config,Clock clock,WorkloadCredentialResolver credentials,boolean testProfile){
        config.validate(testProfile);this.config=config;this.clock=Objects.requireNonNull(clock);this.credentials=Objects.requireNonNull(credentials);
        http=new BoundedIamHttp(config,testProfile);verifier=new AuthorityResponseVerifier(config,clock,http);decoder=new TrustedJwtDecoder(config,clock,testProfile);
    }
    @Override public ServiceTicket client(ServiceCall call){return ticket(query("client",call,Map.of(),true),call,"workload",null,null);}
    @Override public ServiceTicket exchange(String subjectToken,ServiceCall call){
        if(subjectToken==null||subjectToken.length()>16384||subjectToken.split("\\.").length!=3)throw AuthorityProtocol.denied();
        // Unverified use only selects a fixed mutually exclusive profile. It never supplies permission.
        var input=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(subjectToken.split("\\.")[1]));
        if("integration".equals(input.path("token_use").textValue())){
            if(!"gateway".equals(config.serviceId())||config.integration()==null||!config.integration().enabled())throw AuthorityProtocol.denied();
            var c=query("exchange_integration",call,Map.of("subject_token",subjectToken),true);
            AuthorityProtocol.same(AuthorityProtocol.digest(subjectToken),AuthorityProtocol.text(c,"subject_token_sha256"));
            for(String k:List.of("tenant_id","sub","app_code","biz_system_code","user_id","credential_id","policy_digest"))
                if(!input.has(k)||!Objects.equals(input.get(k),c.get(k)))throw AuthorityProtocol.denied();
            var result=ticket(c,call,"delegated",null,null);
            if(!"INTEGRATION".equals(result.operation().sourceKind())||result.operation().expiresAt().isAfter(AuthorityProtocol.time(input,"exp")))throw AuthorityProtocol.denied();
            AuthorityProtocol.same(AuthorityProtocol.text(input,"tenant_id"),result.operation().subject().tenantId());
            AuthorityProtocol.same(AuthorityProtocol.text(input,"sub"),result.operation().subject().principalId());
            return result;
        }
        var jwt=decoder.decode(subjectToken);
        if(!"user".equals(jwt.getClaims().get("token_use")))throw AuthorityProtocol.denied();
        var mapped=new PortalClaimMapper(config.portal()).map(jwt);
        if(!mapped.scopes().contains("platform.access"))throw AuthorityProtocol.denied();
        var result=ticket(query("exchange",call,Map.of("subject_token",subjectToken),true),call,"delegated",mapped.actor(),null);
        var signed=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(result.value().split("\\.")[1]));
        AuthorityProtocol.within(mapped.actor(),AuthorityProtocol.actor(signed.get("initial_ceiling")));
        if(!"PORTAL".equals(result.operation().sourceKind())||result.operation().expiresAt().isAfter(jwt.getExpiresAt()))throw AuthorityProtocol.denied();
        return result;
    }
    @Override public ServiceTicket resume(DelegationRef delegation,ServiceCall call){
        requireDelegation(delegation);
        var result=ticket(query("resume",call,Map.of("grant_ref",delegation.value()),true),call,"delegated",null,delegation);
        if(!"DELEGATED".equals(result.operation().sourceKind()))throw AuthorityProtocol.denied();
        return result;
    }
    @Override public ServiceTicket forward(VerifiedAuthority authority,ServiceCall call){
        AuthorityProtocol.requireCurrent(authority,clock);
        var result=ticket(query("forward",call,Map.of("decision_id",authority.decisionId(),"grant_ref",authority.delegation().value(),"operation_id",authority.operation().operationId()),true),call,"delegated",authority.actor(),authority.delegation());
        AuthorityProtocol.same(authority.operation(),result.operation());
        return result;
    }
    @Override public VerifiedAuthority authorize(String serviceTicket,ServiceCall call){
        var jwt=decoder.decode(serviceTicket);
        if(!"delegated".equals(jwt.getClaims().get("authority_mode")))throw AuthorityProtocol.denied();
        var input=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(serviceTicket.split("\\.")[1]));
        AuthorityProtocol.call(input,call);
        var current=query("authorize",call,Map.of("service_ticket",serviceTicket),false);
        AuthorityProtocol.same(jwt.getId(),AuthorityProtocol.text(current,"ticket_jti"));
        AuthorityProtocol.same(AuthorityProtocol.text(input,"caller"),AuthorityProtocol.text(current,"subject_caller"));
        var result=authority(current,call);
        AuthorityProtocol.same(AuthorityProtocol.text(input,"grant_ref"),result.delegation().value());
        AuthorityProtocol.same(AuthorityProtocol.text(input,"authorization_version"),result.authorizationVersion());
        AuthorityProtocol.within(AuthorityProtocol.actor(input),result.actor());
        AuthorityProtocol.same(AuthorityProtocol.operation(input.get("operation"),AuthorityProtocol.actor(input),call.resource(),result.delegation().value(),clock),result.operation());
        return result;
    }
    @Override public WorkloadAuthority authorizeWorkload(String serviceTicket,ServiceCall call){
        var jwt=decoder.decode(serviceTicket);
        if(!"workload".equals(jwt.getClaims().get("authority_mode")))throw AuthorityProtocol.denied();
        var input=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(serviceTicket.split("\\.")[1]));AuthorityProtocol.call(input,call);
        var result=query("authorize_workload",call,Map.of("service_ticket",serviceTicket),false);
        AuthorityProtocol.same(jwt.getId(),AuthorityProtocol.text(result,"ticket_jti"));
        AuthorityProtocol.same("workload",AuthorityProtocol.text(result,"authority_mode"));
        AuthorityProtocol.same(jwt.getClaimAsString("caller"),AuthorityProtocol.text(result,"subject_caller"));
        for(String k:List.of("tenant_id","sub","grant_ref","roles","attributes","operation"))if(result.has(k))throw AuthorityProtocol.denied();
        return new WorkloadAuthority(jwt.getClaimAsString("caller"),call,AuthorityProtocol.text(result,"jti"),AuthorityProtocol.time(result,"iat"),AuthorityProtocol.time(result,"exp"));
    }
    @Override public VerifiedAuthority approver(DelegationRef delegation,SubjectKey approver,ResourceBinding approval){
        requireDelegation(delegation);
        var call=new ServiceCall(config.serviceId(),"approval.current",approval);
        var c=query("approver",call,Map.of("grant_ref",delegation.value(),"approver",approver),false);
        var result=authority(c,call);
        AuthorityProtocol.same(approver,AuthorityProtocol.subject(result.actor()));
        AuthorityProtocol.same(delegation.value(),result.delegation().value());
        return result;
    }
    private ServiceTicket ticket(JsonNode envelope,ServiceCall call,String mode,Actor upper,DelegationRef delegation){
        String raw=AuthorityProtocol.text(envelope,"ticket");
        var c=verifier.verify(raw,"at+jwt","service","urn:agent-platform:service:"+call.target(),config.authority().maxTicketSeconds()).claims();
        AuthorityProtocol.call(c,call);AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"caller"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.same(call.action(),AuthorityProtocol.text(c,"scope"));
        AuthorityProtocol.same(mode,AuthorityProtocol.text(c,"authority_mode"));
        if(new ServiceRoutePolicy().routes().stream().noneMatch(r->config.serviceId().equals(r.caller())&&call.target().equals(r.target())&&call.action().equals(r.action())&&mode.equals(r.mode())))throw AuthorityProtocol.denied();
        if("workload".equals(mode)){
            AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"sub"));
            for(String k:List.of("tenant_id","grant_ref","roles","attributes","operation"))if(c.has(k))throw AuthorityProtocol.denied();
            return new ServiceTicket(raw,AuthorityProtocol.time(c,"exp"),null);
        }
        var actor=AuthorityProtocol.actor(c);if(upper!=null)AuthorityProtocol.within(upper,actor);
        String grant=AuthorityProtocol.text(c,"grant_ref");AuthorityProtocol.text(c,"authorization_version");
        var grantExpiry=AuthorityProtocol.time(c,"grant_expires_at");
        var operation=AuthorityProtocol.operation(c.get("operation"),actor,call.resource(),grant,clock);
        verifyCeiling(c,actor,operation,upper);
        if(delegation!=null){AuthorityProtocol.same(delegation.value(),grant);AuthorityProtocol.same(delegation.subject(),AuthorityProtocol.subject(actor));AuthorityProtocol.same(delegation.expiresAt(),grantExpiry);}
        if(AuthorityProtocol.time(c,"exp").isAfter(operation.expiresAt())||operation.expiresAt().isAfter(grantExpiry))throw AuthorityProtocol.denied();
        return new ServiceTicket(raw,AuthorityProtocol.time(c,"exp"),operation);
    }
    private VerifiedAuthority authority(JsonNode c,ServiceCall call){
        AuthorityProtocol.same("delegated",AuthorityProtocol.text(c,"authority_mode"));
        var actor=AuthorityProtocol.actor(c);String grant=AuthorityProtocol.text(c,"grant_ref");
        var delegation=new DelegationRef(grant,AuthorityProtocol.subject(actor),AuthorityProtocol.time(c,"grant_expires_at"));
        var op=AuthorityProtocol.operation(c.get("operation"),actor,call.resource(),grant,clock);
        verifyCeiling(c,actor,op,null);
        var expiry=AuthorityProtocol.time(c,"exp");if(expiry.isAfter(op.expiresAt())||op.expiresAt().isAfter(delegation.expiresAt()))throw AuthorityProtocol.denied();
        var result=new VerifiedAuthority(actor,AuthorityProtocol.text(c,"subject_caller"),call,delegation,AuthorityProtocol.text(c,"authorization_version"),AuthorityProtocol.time(c,"iat"),expiry,AuthorityProtocol.text(c,"jti"),op);
        AuthorityProtocol.requireCurrent(result,clock);return result;
    }
    private void verifyCeiling(JsonNode claims,Actor actor,OperationBinding operation,Actor localUpper){
        // Signed grant ceiling is immutable across resume. A current candidate may only narrow it.
        var initial=AuthorityProtocol.actor(claims.get("initial_ceiling"));
        AuthorityProtocol.same(operation.ceilingSha256(),AuthorityProtocol.ceiling(initial));
        AuthorityProtocol.within(initial,actor);
        if(localUpper!=null)AuthorityProtocol.within(localUpper,actor);
    }
    private void requireDelegation(DelegationRef d){if(d==null||d.value()==null||d.value().isBlank()||d.subject()==null)throw AuthorityProtocol.denied();if(!d.expiresAt().isAfter(clock.instant()))throw AuthorityProtocol.expired();}
    private JsonNode query(String operation,ServiceCall call,Map<String,Object> fields,boolean token){
        var request=new LinkedHashMap<String,Object>(fields);request.put("operation",operation);request.putAll(AuthorityProtocol.call(call));
        String nonce=UUID.randomUUID().toString();request.put("nonce",nonce);
        var requestedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var bytes=http.postJsonAuthenticated(URI.create(token?config.authority().tokenUri():config.authority().decisionUri()),request,credentials.resolve());
        var outer=AuthorityResponseVerifier.object(bytes);
        var c=verifier.verify(AuthorityProtocol.text(outer,"assertion"),"agent-authority-decision+jwt","current-decision",config.authority().audience(),config.authority().maxDecisionSeconds()).claims();
        AuthorityProtocol.same(nonce,AuthorityProtocol.text(c,"nonce"));AuthorityProtocol.same(AuthorityProtocol.digest(request),AuthorityProtocol.text(c,"request_sha256"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.call(c,call);
        if(AuthorityProtocol.time(c,"iat").isBefore(requestedAt))throw BoundedIamHttp.unavailable();
        if(!c.path("active").isBoolean()||!c.path("current").isBoolean())throw BoundedIamHttp.unavailable();
        if(!c.path("active").booleanValue()||!c.path("current").booleanValue())throw AuthorityProtocol.denied();
        return c;
    }
    @Override public ResponseLease open(VerifiedAuthority authority,ResponseBinding binding){
        AuthorityProtocol.requireCurrent(authority,clock);
        if(binding==null||binding.responseId()==null||binding.responseId().isBlank()||!Set.of("sse","model").contains(binding.kind()))throw AuthorityProtocol.denied();
        AuthorityProtocol.hash(binding.requestSha256());
        AuthorityProtocol.same(authority.call().resource(),binding.resource());
        if(!binding.hardDeadline().isAfter(clock.instant()))throw AuthorityProtocol.expired();
        if(binding.hardDeadline().isAfter(clock.instant().plusSeconds("sse".equals(binding.kind())?55:610)))throw AuthorityProtocol.denied();
        if(!"INTEGRATION".equals(authority.operation().sourceKind())&&binding.hardDeadline().isAfter(authority.operation().expiresAt()))throw AuthorityProtocol.denied();
        var c=query("response_open",authority.call(),Map.of("decision_id",authority.decisionId(),"binding",binding),false);
        AuthorityProtocol.same(authority.operation().operationId(),AuthorityProtocol.text(c,"operation_id"));
        AuthorityProtocol.same(authority.decisionId(),AuthorityProtocol.text(c,"opening_decision_id"));
        var lease=lease(c,binding,null,authority);
        AuthorityProtocol.same(authority.operation().sourceKind(),lease.sourceKind());AuthorityProtocol.same(authority.operation().expiresAt(),lease.sourceExpiresAt());
        return lease;
    }
    @Override public ResponseLease check(ResponseLease lease){
        lease.requireVerifiedLive();
        var c=query("response_check",new ServiceCall(config.serviceId(),"response.continue",lease.binding().resource()),
                Map.of("continuation_ref",lease.continuationRef(),"binding",lease.binding()),false);
        return lease(c,lease.binding(),lease,null);
    }
    private ResponseLease lease(JsonNode c,ResponseBinding binding,ResponseLease previous,VerifiedAuthority opening){
        AuthorityProtocol.same(Json.tree(binding),c.get("binding"));
        String source=AuthorityProtocol.text(c,"source_kind"),continuation=AuthorityProtocol.text(c,"continuation_ref"),decision=AuthorityProtocol.text(c,"opening_decision_id");
        // The continuation is an immutable response-only proof, never a current user/service token.
        var proof=verifier.verify(continuation,"agent-response-continuation+jwt","response-continuation",config.authority().audience(),611).claims();
        AuthorityProtocol.same(Json.tree(binding),proof.get("binding"));
        AuthorityProtocol.same(AuthorityProtocol.text(c,"operation_id"),AuthorityProtocol.text(proof,"operation_id"));
        AuthorityProtocol.same(AuthorityProtocol.text(c,"ticket_jti"),AuthorityProtocol.text(proof,"ticket_jti"));
        AuthorityProtocol.same(decision,AuthorityProtocol.text(proof,"opening_decision_id"));
        AuthorityProtocol.same(source,AuthorityProtocol.text(proof,"source_kind"));
        AuthorityProtocol.same(AuthorityProtocol.time(c,"source_expires_at"),AuthorityProtocol.time(proof,"source_expires_at"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(proof,"consumer"));
        AuthorityProtocol.same(binding.hardDeadline(),AuthorityProtocol.time(proof,"exp"));
        if(opening!=null){
            AuthorityProtocol.same(opening.caller(),AuthorityProtocol.text(proof,"caller"));
            AuthorityProtocol.same(opening.call().action(),AuthorityProtocol.text(proof,"action"));
            AuthorityProtocol.same(opening.operation().operationId(),AuthorityProtocol.text(proof,"operation_id"));
        }
        var sourceExpiry=AuthorityProtocol.time(c,"source_expires_at");var checked=AuthorityProtocol.time(c,"exp");
        if(!Set.of("PORTAL","INTEGRATION","DELEGATED").contains(source)||checked.isAfter(binding.hardDeadline()))throw AuthorityProtocol.denied();
        ResponseMaterialPermit permit=previous==null?null:previous.completionPermit();
        if(previous!=null){
            AuthorityProtocol.same(previous.sourceKind(),source);AuthorityProtocol.same(previous.sourceExpiresAt(),sourceExpiry);
            AuthorityProtocol.same(previous.continuationRef(),continuation);AuthorityProtocol.same(previous.openingDecisionId(),decision);
            if(c.has("completion_permit"))throw AuthorityProtocol.denied();
        }else if("INTEGRATION".equals(source)){
            String raw=AuthorityProtocol.text(c,"completion_permit");
            var verified=verifier.verify(raw,"agent-response-material+jwt","response-completion",config.authority().audience(),611);
            permit=new ResponseMaterialPermit(raw,verified,config.authority().issuer(),config.authority().audience(),binding,opening,AuthorityProtocol.text(proof,"parent_response_id"),clock);
        }else if(c.has("completion_permit"))throw AuthorityProtocol.denied();
        if(!"INTEGRATION".equals(source)&&checked.isAfter(sourceExpiry))throw AuthorityProtocol.denied();
        return previous==null?ResponseLease.opened(binding,source,continuation,decision,checked,sourceExpiry,permit):previous.renewed(checked);
    }
    @Override public void close(){decoder.close();http.close();}
}
