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
    /** Data only: possessing or constructing this DTO never authorizes an output. */
    public record SessionView(String tenantId,String principalId,Set<String> roles,Instant expiresAt) {
        public SessionView { roles=Set.copyOf(roles); }
    }
    /** Only the fixed portal exchange + online authorize path below can mint this output proof. */
    public static final class PortalSessionAuthorization {
        private final HttpCurrentAuthorityClient owner;
        private final SessionView view;
        private final Instant sourceExpires,authorizationExpires;
        private final long startedNanos,sourceBudget,authorizationBudget;
        private PlatformException failure;
        private boolean released;
        private PortalSessionAuthorization(HttpCurrentAuthorityClient owner,SessionView view,
                Instant sourceExpires,Instant authorizationExpires,Instant observedAt,long startedNanos){
            this.owner=owner;this.view=view;this.sourceExpires=sourceExpires;
            this.authorizationExpires=authorizationExpires;this.startedNanos=startedNanos;
            sourceBudget=budget(observedAt,sourceExpires);
            authorizationBudget=budget(observedAt,authorizationExpires);
        }
        private static long budget(Instant start,Instant end){
            var duration=Duration.between(start,end);
            if(duration.isNegative()||duration.isZero())return 0;
            try{return duration.toNanos();}catch(ArithmeticException longLifetime){return Long.MAX_VALUE;}
        }
        private void check(){
            if(failure!=null)throw failure;
            try{
                var now=owner.clock.instant();long elapsed=System.nanoTime()-startedNanos;
                // Preserve source-vs-authorization status even after a wall-clock rollback.
                if(!sourceExpires.isAfter(now)||elapsed<0||elapsed>=sourceBudget)throw invalidPortal();
                if(owner.closed)throw BoundedIamHttp.unavailable();
                if(released||!authorizationExpires.isAfter(now)||elapsed>=authorizationBudget)throw AuthorityProtocol.expired();
            }catch(PlatformException denied){failure=denied;throw denied;}
        }
        public synchronized SessionView view(){check();return view;}
        /** One release only. Serialize first; invoke immediately before the actual output write. */
        public synchronized void requireVerified(){check();released=true;}
        @Override public String toString(){return "PortalSessionAuthorization[private-proof]";}
    }
    private final AuthorityProperties config;
    private final Clock clock;
    private final WorkloadCredentialResolver credentials;
    private final BoundedIamHttp http;
    private final AuthorityResponseVerifier verifier;
    private final TrustedJwtDecoder decoder;
    private volatile boolean closed;
    public HttpCurrentAuthorityClient(AuthorityProperties config,Clock clock,WorkloadCredentialResolver credentials,boolean testProfile){
        config.validate(testProfile);this.config=config;this.clock=Objects.requireNonNull(clock);this.credentials=Objects.requireNonNull(credentials);
        http=new BoundedIamHttp(config,testProfile);verifier=new AuthorityResponseVerifier(config,clock,http);decoder=new TrustedJwtDecoder(config,clock,testProfile);
    }
    private static PlatformException invalidPortal(){return new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");}
    @Override public PortalSessionAuthorization portalSession(String originalPortalToken){
        if(closed||!"iam".equals(config.mode())||!"gateway".equals(config.serviceId()))throw BoundedIamHttp.unavailable();
        final org.springframework.security.oauth2.jwt.Jwt source;
        try{source=decoder.decode(originalPortalToken);}
        catch(org.springframework.security.oauth2.jwt.JwtException bad){throw invalidPortal();}
        if(!"user".equals(source.getClaimAsString("token_use")))throw invalidPortal();
        var mapped=new PortalClaimMapper(config.portal()).map(source);
        if(!mapped.scopes().contains("platform.access"))throw AuthorityProtocol.denied();
        var resource=new ResourceBinding("portal-session",
                AuthorityProtocol.digest(List.of("portal-session.subject.v1",source.getIssuer().toString(),mapped.actor().tenantId(),mapped.actor().principalId())),
                AuthorityProtocol.digest(List.of("portal-session.authentication.v1",source.getIssuer().toString(),source.getClaimAsString("client_id"),source.getId(),source.getIssuedAt().getEpochSecond(),source.getExpiresAt().getEpochSecond())));
        var call=new ServiceCall("gateway","portal.session.read",resource);
        PortalExchange exchange=null;
        try{
            exchange=portalExchange(originalPortalToken,call);
            var initial=exchange.verified();var ticket=initial.ticket();
            var exchangeExpiry=Collections.min(List.of(AuthorityProtocol.time(exchange.current(),"exp"),ticket.expiresAt(),ticket.operation().expiresAt(),AuthorityProtocol.time(initial.claims(),"grant_expires_at")));
            if(!source.getExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            long elapsed=System.nanoTime()-exchange.startedNanos();
            if(!exchangeExpiry.isAfter(clock.instant())||elapsed<0||elapsed>=PortalSessionAuthorization.budget(exchange.observedAt(),exchangeExpiry))throw AuthorityProtocol.expired();
            // The exchange decision is consumed here. Only the newest authorize window gates output.
            var retained=authorizeRetained(ticket.value(),call,exchange);
            if(!retained.hasInitialProof())throw AuthorityProtocol.denied();
            retained.requireVerified();var current=retained.current();
            AuthorityProtocol.same("PORTAL",current.operation().sourceKind());
            AuthorityProtocol.same("PORTAL",current.operation().originKind());
            AuthorityProtocol.same(AuthorityProtocol.subject(mapped.actor()),AuthorityProtocol.subject(current.actor()));
            AuthorityProtocol.same(ticket.operation(),current.operation());
            AuthorityProtocol.same(call,current.call());
            AuthorityProtocol.same("gateway",current.caller());
            AuthorityProtocol.same(AuthorityProtocol.text(initial.claims(),"grant_ref"),current.delegation().value());
            AuthorityProtocol.same(AuthorityProtocol.time(initial.claims(),"grant_expires_at"),current.delegation().expiresAt());
            AuthorityProtocol.same(AuthorityProtocol.actor(initial.claims().get("initial_ceiling")),retained.requireInitialCeiling());
            AuthorityProtocol.same(origin(initial.claims()),retained.requireOriginAuthenticationId());
            AuthorityProtocol.same(AuthorityProtocol.text(initial.claims(),"jti"),retained.ticketJti);
            AuthorityProtocol.same(ticket.expiresAt(),retained.ticketExpiresAt);
            AuthorityProtocol.within(mapped.actor(),current.actor());
            var roles=new TreeSet<>(mapped.actor().roles());roles.retainAll(current.actor().roles());
            var view=new SessionView(current.actor().tenantId(),current.actor().principalId(),roles,
                    Collections.min(List.of(source.getExpiresAt(),current.delegation().expiresAt())));
            var deadline=Collections.min(List.of(ticket.expiresAt(),current.operation().expiresAt(),current.delegation().expiresAt(),current.expiresAt(),retained.observedAt.plusSeconds(config.authority().maxDecisionSeconds())));
            var proof=new PortalSessionAuthorization(this,view,source.getExpiresAt(),deadline,retained.observedAt,retained.startedNanos);
            proof.view();return proof;
        }catch(org.springframework.security.oauth2.jwt.JwtException bad){
            if(!source.getExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            if(exchange!=null&&!exchange.verified().ticket().expiresAt().isAfter(clock.instant()))throw AuthorityProtocol.expired();
            throw BoundedIamHttp.unavailable();
        }catch(PlatformException bad){
            if(!source.getExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            throw bad;
        }
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
        return portalExchange(subjectToken,call).verified().ticket();
    }
    private record VerifiedTicket(ServiceTicket ticket,JsonNode claims) {}
    private record PortalExchange(VerifiedTicket verified,JsonNode current,Instant sourceExpiresAt,
            Instant observedAt,long startedNanos) {}
    private PortalExchange portalExchange(String subjectToken,ServiceCall call){
        long startedNanos=System.nanoTime();var observedAt=clock.instant();
        var jwt=decoder.decode(subjectToken);
        if(!"user".equals(jwt.getClaims().get("token_use")))throw AuthorityProtocol.denied();
        var mapped=new PortalClaimMapper(config.portal()).map(jwt);
        if(!mapped.scopes().contains("platform.access"))throw AuthorityProtocol.denied();
        var current=query("exchange",call,Map.of("subject_token",subjectToken),true);
        var verified=verifiedTicket(current,call,"delegated",mapped.actor(),null);var result=verified.ticket();
        AuthorityProtocol.within(mapped.actor(),AuthorityProtocol.actor(verified.claims().get("initial_ceiling")));
        if(!"PORTAL".equals(result.operation().sourceKind())||result.operation().expiresAt().isAfter(jwt.getExpiresAt()))throw AuthorityProtocol.denied();
        return new PortalExchange(verified,current,jwt.getExpiresAt(),observedAt,startedNanos);
    }
    @Override public GatewayRunAuthorization exchangeGatewayPublishedRun(String originalPortalToken,ResourceBinding publishedRoot){
        if(closed||!"iam".equals(config.mode())||!"gateway".equals(config.serviceId()))throw AuthorityProtocol.denied();
        if(publishedRoot==null||!"app-version".equals(publishedRoot.kind()))throw AuthorityProtocol.denied();
        int at=publishedRoot.id().lastIndexOf('@');
        if(at<1||!publishedRoot.id().substring(at+1).matches("[1-9][0-9]{0,18}"))throw AuthorityProtocol.denied();
        MetadataSelectors.code(publishedRoot.id().substring(0,at));
        try{Long.parseLong(publishedRoot.id().substring(at+1));}catch(NumberFormatException bad){throw AuthorityProtocol.denied();}
        AuthorityProtocol.hash(publishedRoot.versionDigest());
        var exchange=portalExchange(originalPortalToken,new ServiceCall("engine","proxy.engine.post.RunController.run",publishedRoot));
        origin(exchange.verified().claims());
        var proof=new GatewayRunAuthorization(this,exchange);
        // A late ticket/JWK verification cannot spend an already-expired current decision.
        proof.requireVerified();
        return proof;
    }
    /**
     * Same private portal/query/ticket verification, never a public authority wrapper. This proof
     * belongs to its creating client and can be checked on another thread without rebinding a
     * request context. It cannot renew, refresh or extend a signed operation or response window.
     * Close invalidates it as soon as the owner starts closing; this makes no promise that the
     * underlying JDK HTTP client can hard-cancel every in-flight network operation.
     */
    public static final class GatewayRunAuthorization {
        private final HttpCurrentAuthorityClient owner;
        private final PortalExchange evidence;
        private final Clock verificationClock;
        private final Instant grantExpiresAt,expiresAt;
        private final long budgetNanos;
        private boolean failed;
        private GatewayRunAuthorization(HttpCurrentAuthorityClient owner,PortalExchange evidence){
            this.owner=owner;this.evidence=evidence;this.verificationClock=owner.clock;
            this.grantExpiresAt=AuthorityProtocol.time(evidence.verified().claims(),"grant_expires_at");
            this.expiresAt=Collections.min(List.of(AuthorityProtocol.time(evidence.current(),"exp"),
                    ticket().expiresAt(),operation().expiresAt(),grantExpiresAt,evidence.sourceExpiresAt()));
            // Anchor before portal/query/JWK verification, not at return or on subsequent checks.
            // This may conservatively shorten the signed window; wall-clock rollback cannot add it back.
            var budget=Duration.between(evidence.observedAt(),expiresAt);
            var maximum=Duration.ofSeconds(owner.config.authority().maxDecisionSeconds());
            if(budget.compareTo(maximum)>0)budget=maximum;
            this.budgetNanos=budget.isNegative()?0:budget.toNanos();
        }
        public ServiceTicket ticket(){return evidence.verified().ticket();}
        public OperationBinding operation(){return ticket().operation();}
        public synchronized void requireVerified(){
            if(failed)throw AuthorityProtocol.expired();
            try{
                if(owner.closed)throw AuthorityProtocol.denied();
                var now=verificationClock.instant();
                long elapsed=System.nanoTime()-evidence.startedNanos();
                if(!expiresAt.isAfter(now)||elapsed<0||elapsed>=budgetNanos)throw AuthorityProtocol.expired();
            }catch(RuntimeException failure){failed=true;throw failure;}
        }
        @Override public String toString(){return "GatewayRunAuthorization[private-proof]";}
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
        return authorizeRetained(serviceTicket,call).current();
    }
    @Override public RetainedAuthorization authorizeRetained(String serviceTicket,ServiceCall call){
        return authorizeRetained(serviceTicket,call,null);
    }
    private RetainedAuthorization authorizeRetained(String serviceTicket,ServiceCall call,PortalExchange sessionExchange){
        var jwt=decoder.decode(serviceTicket);
        if(!"delegated".equals(jwt.getClaims().get("authority_mode")))throw AuthorityProtocol.denied();
        var input=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(serviceTicket.split("\\.")[1]));
        AuthorityProtocol.call(input,call);
        if(sessionExchange!=null){
            var deadline=AuthorityProtocol.time(sessionExchange.current(),"exp");
            long elapsed=System.nanoTime()-sessionExchange.startedNanos();
            if(!sessionExchange.sourceExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            if(!deadline.isAfter(clock.instant())||elapsed<0||elapsed>=PortalSessionAuthorization.budget(sessionExchange.observedAt(),deadline))throw AuthorityProtocol.expired();
        }
        var observedAt=clock.instant();long startedNanos=System.nanoTime();
        var current=query("authorize",call,Map.of("service_ticket",serviceTicket),false);
        AuthorityProtocol.same(jwt.getId(),AuthorityProtocol.text(current,"ticket_jti"));
        AuthorityProtocol.same(AuthorityProtocol.text(input,"caller"),AuthorityProtocol.text(current,"subject_caller"));
        var result=authority(current,call);
        AuthorityProtocol.same(AuthorityProtocol.text(input,"grant_ref"),result.delegation().value());
        AuthorityProtocol.same(AuthorityProtocol.text(input,"authorization_version"),result.authorizationVersion());
        AuthorityProtocol.within(AuthorityProtocol.actor(input),result.actor());
        var operation=AuthorityProtocol.operation(input.get("operation"),AuthorityProtocol.actor(input),call.resource(),result.delegation().value(),clock);
        AuthorityProtocol.same(operation,result.operation());
        verifyCeiling(input,AuthorityProtocol.actor(input),operation,null);
        var initial=AuthorityProtocol.actor(input.get("initial_ceiling"));
        AuthorityProtocol.same(initial,AuthorityProtocol.actor(current.get("initial_ceiling")));
        AuthorityProtocol.same(AuthorityProtocol.time(input,"grant_expires_at"),result.delegation().expiresAt());
        if(jwt.getExpiresAt().isAfter(operation.expiresAt()))throw AuthorityProtocol.denied();
        // The online query may consume the last part of the ticket's lifetime.
        if(!jwt.getExpiresAt().isAfter(clock.instant()))throw AuthorityProtocol.expired();
        // Both fields are from this exact verified ticket/decision pair. A partial rollout may
        // omit both, but malformed or single-sided evidence must never become current-only.
        if(!input.has("origin_authentication_id")&&!current.has("origin_authentication_id"))return RetainedAuthorization.currentOnly(result);
        String origin=origin(input);AuthorityProtocol.same(origin,origin(current));
        return new RetainedAuthorization(result,initial,origin,jwt.getId(),jwt.getExpiresAt(),
                AuthorityProtocol.text(current,"nonce"),AuthorityProtocol.text(current,"request_sha256"),clock,observedAt,startedNanos);
    }
    private static String origin(JsonNode c){
        String value=AuthorityProtocol.text(c,"origin_authentication_id");
        if(value.length()>128)throw AuthorityProtocol.denied();return value;
    }
    /**
     * Same-verification snapshot, not a durable authorization capability. Business transactions
     * must additionally requireBound on the live request frame. No caller can attach an initial
     * ceiling, origin or clock to a public VA; only the strict mapping above mints this proof.
     */
    public static final class RetainedAuthorization {
        private final VerifiedAuthority current;
        private final Actor initial;
        private final String originAuthenticationId,ticketJti,nonce,requestSha256;
        private final Instant ticketExpiresAt;
        private final Clock verificationClock;
        private final Instant observedAt;
        private final long startedNanos;
        private RetainedAuthorization(VerifiedAuthority current,Actor initial,String originAuthenticationId,
                String ticketJti,Instant ticketExpiresAt,String nonce,String requestSha256,Clock verificationClock,Instant observedAt,long startedNanos){
            this.current=Objects.requireNonNull(current);
            this.initial=initial==null?null:new Actor(initial.tenantId(),initial.principalId(),initial.roles(),initial.attributes());
            this.originAuthenticationId=originAuthenticationId;this.ticketJti=ticketJti;this.ticketExpiresAt=ticketExpiresAt;
            this.nonce=nonce;this.requestSha256=requestSha256;this.verificationClock=verificationClock;
            this.observedAt=observedAt;this.startedNanos=startedNanos;
        }
        public static RetainedAuthorization currentOnly(VerifiedAuthority current){
            return new RetainedAuthorization(current,null,null,null,null,null,null,null,null,0);
        }
        public VerifiedAuthority current(){return current;}
        public boolean hasInitialProof(){return initial!=null;}
        public Actor requireInitialCeiling(){requireVerified();return initial;}
        public String requireOriginAuthenticationId(){requireVerified();return originAuthenticationId;}
        public void requireVerified(){
            if(!hasInitialProof())throw AuthorityProtocol.denied();
            AuthorityProtocol.requireCurrent(current,verificationClock);
            if(!ticketExpiresAt.isAfter(verificationClock.instant()))throw AuthorityProtocol.expired();
        }
        @Override public String toString(){return "RetainedAuthorization[initialProof="+hasInitialProof()+"]";}
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
        return verifiedTicket(envelope,call,mode,upper,delegation).ticket();
    }
    private VerifiedTicket verifiedTicket(JsonNode envelope,ServiceCall call,String mode,Actor upper,DelegationRef delegation){
        String raw=AuthorityProtocol.text(envelope,"ticket");
        var c=(sessionCall(call)?verifier.verifySession(raw,"at+jwt","service","urn:agent-platform:service:"+call.target(),config.authority().maxTicketSeconds()):verifier.verify(raw,"at+jwt","service","urn:agent-platform:service:"+call.target(),config.authority().maxTicketSeconds())).claims();
        AuthorityProtocol.call(c,call);AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"caller"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.same(call.action(),AuthorityProtocol.text(c,"scope"));
        AuthorityProtocol.same(mode,AuthorityProtocol.text(c,"authority_mode"));
        if(!ServiceRoutePolicy.localRunAdvance(config.serviceId(),call,mode,c.path("operation").path("source_kind").asText(),c.path("operation").path("origin_kind").asText()) && new ServiceRoutePolicy().routes().stream().noneMatch(r->config.serviceId().equals(r.caller())&&call.target().equals(r.target())&&call.action().equals(r.action())&&mode.equals(r.mode())))throw AuthorityProtocol.denied();
        if("workload".equals(mode)){
            AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"sub"));
            for(String k:List.of("tenant_id","grant_ref","roles","attributes","operation"))if(c.has(k))throw AuthorityProtocol.denied();
            return new VerifiedTicket(new ServiceTicket(raw,AuthorityProtocol.time(c,"exp"),null),c);
        }
        var actor=AuthorityProtocol.actor(c);if(upper!=null)AuthorityProtocol.within(upper,actor);
        String grant=AuthorityProtocol.text(c,"grant_ref");AuthorityProtocol.text(c,"authorization_version");
        var grantExpiry=AuthorityProtocol.time(c,"grant_expires_at");
        var operation=AuthorityProtocol.operation(c.get("operation"),actor,call.resource(),grant,clock);
        verifyCeiling(c,actor,operation,upper);
        if(delegation!=null){AuthorityProtocol.same(delegation.value(),grant);AuthorityProtocol.same(delegation.subject(),AuthorityProtocol.subject(actor));AuthorityProtocol.same(delegation.expiresAt(),grantExpiry);}
        if(AuthorityProtocol.time(c,"exp").isAfter(operation.expiresAt())||operation.expiresAt().isAfter(grantExpiry))throw AuthorityProtocol.denied();
        if(sessionCall(call)&&!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
        return new VerifiedTicket(new ServiceTicket(raw,AuthorityProtocol.time(c,"exp"),operation),c);
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
        if("run.advance".equals(call.action()) && (!Set.of("resume","authorize").contains(operation)
                || !ServiceRoutePolicy.localRunAdvance(config.serviceId(),call,"delegated","DELEGATED","PORTAL")))throw AuthorityProtocol.denied();
        var request=new LinkedHashMap<String,Object>(fields);request.put("operation",operation);request.putAll(AuthorityProtocol.call(call));
        String nonce=UUID.randomUUID().toString();request.put("nonce",nonce);
        var requestedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var bytes=http.postJsonAuthenticated(URI.create(token?config.authority().tokenUri():config.authority().decisionUri()),request,credentials.resolve());
        var outer=AuthorityResponseVerifier.object(bytes);
        var c=(sessionCall(call)?verifier.verifySession(outer.path("assertion").textValue(),"agent-authority-decision+jwt","current-decision",config.authority().audience(),config.authority().maxDecisionSeconds()):verifier.verify(AuthorityProtocol.text(outer,"assertion"),"agent-authority-decision+jwt","current-decision",config.authority().audience(),config.authority().maxDecisionSeconds())).claims();
        AuthorityProtocol.same(nonce,AuthorityProtocol.text(c,"nonce"));AuthorityProtocol.same(AuthorityProtocol.digest(request),AuthorityProtocol.text(c,"request_sha256"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.call(c,call);
        if(AuthorityProtocol.time(c,"iat").isBefore(requestedAt))throw BoundedIamHttp.unavailable();
        if(!c.path("active").isBoolean()||!c.path("current").isBoolean())throw BoundedIamHttp.unavailable();
        if(!c.path("active").booleanValue()||!c.path("current").booleanValue())throw AuthorityProtocol.denied();
        // Only after signature AND nonce/request/client/call/current validation is elapsed evidence
        // a trusted authorization deadline. It never leaves query as a successful expired decision.
        if(sessionCall(call)&&!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
        return c;
    }
    private boolean sessionCall(ServiceCall call){return "gateway".equals(config.serviceId())&&"gateway".equals(call.target())&&"portal.session.read".equals(call.action());}
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
    @Override public void close(){closed=true;decoder.close();http.close();}
}
