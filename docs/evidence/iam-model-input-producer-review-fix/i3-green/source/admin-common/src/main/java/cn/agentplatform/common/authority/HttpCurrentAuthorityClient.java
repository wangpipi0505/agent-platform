package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Object eventsMutex = new Object();
    private EventsRuntime eventsRuntime;
    // Package-only observation points for deterministic lifecycle tests; these mint no authority.
    java.util.function.Consumer<String> eventsLifecycle = point -> {};
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
    @Override public GatewayObservationQueryAuthorization exchangeGatewayRunObservationQuery(String source,SubjectKey subject,String run){
        var call=new ServiceCall("engine",RunObservationSelectors.ACTION,RunObservationSelectors.metadata(subject,run));
        var proof=observationExchange(source,call);
        AuthorityProtocol.same(subject,proof.evidence.verified().ticket().operation().subject());
        return new GatewayObservationQueryAuthorization(proof,run);
    }
    @Override public GatewayStatusAuthorization exchangeGatewayRunStatus(String source,ResourceBinding resource){
        if(resource==null)throw AuthorityProtocol.denied();
        AuthorityProtocol.same(RunObservationSelectors.status(resource.id(),resource.versionDigest()),resource);
        return new GatewayStatusAuthorization(observationExchange(source,new ServiceCall("engine",RunObservationSelectors.STATUS_ACTION,resource)));
    }
    private ObservationProof observationExchange(String source,ServiceCall call){
        if(closed||!"iam".equals(config.mode())||!"gateway".equals(config.serviceId()))throw BoundedIamHttp.unavailable();
        final org.springframework.security.oauth2.jwt.Jwt original;
        try{original=decoder.decode(source);}catch(org.springframework.security.oauth2.jwt.JwtException invalid){throw invalidPortal();}
        if(!"user".equals(original.getClaimAsString("token_use")))throw invalidPortal();
        try{
            var exchange=portalExchange(source,call);var ticket=exchange.verified().ticket();
            RunObservationSelectors.requirePurpose(call,"gateway","delegated",ticket.operation().sourceKind(),ticket.operation().originKind());
            AuthorityProtocol.same(new SubjectKey(original.getClaimAsString("tenant_id"),original.getSubject()),ticket.operation().subject());
            origin(exchange.verified().claims());
            var result=new ObservationProof(this,exchange,AuthorityProtocol.digest(source));result.check();return result;
        }catch(org.springframework.security.oauth2.jwt.JwtException internal){
            if(!original.getExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            // Internal service-ticket/JWK verification is never evidence of a bad original Portal.
            throw BoundedIamHttp.unavailable();
        }catch(PlatformException failure){
            if(!original.getExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            if(failure.status()==401||"AUTHORITY_EXPIRED".equals(failure.code()))throw BoundedIamHttp.unavailable();
            throw failure;
        }
    }
    /** Exchange-only local evidence: no public actor/current/DTO factory and no online re-authorization. */
    private static final class ObservationProof {
        final HttpCurrentAuthorityClient owner;final PortalExchange evidence;final String sourceDigest;
        final Instant internalExpiry;final long sourceBudget,internalBudget;PlatformException failure;
        ObservationProof(HttpCurrentAuthorityClient owner,PortalExchange evidence,String sourceDigest){
            this.owner=owner;this.evidence=evidence;this.sourceDigest=sourceDigest;
            var ticket=evidence.verified().ticket();
            internalExpiry=Collections.min(List.of(AuthorityProtocol.time(evidence.current(),"exp"),ticket.expiresAt(),ticket.operation().expiresAt(),AuthorityProtocol.time(evidence.verified().claims(),"grant_expires_at"),evidence.observedAt().plusSeconds(owner.config.authority().maxDecisionSeconds())));
            sourceBudget=PortalSessionAuthorization.budget(evidence.observedAt(),evidence.sourceExpiresAt());
            internalBudget=PortalSessionAuthorization.budget(evidence.observedAt(),internalExpiry);
        }
        synchronized void check(){
            if(failure!=null)throw failure;
            try{long elapsed=System.nanoTime()-evidence.startedNanos();var now=owner.clock.instant();
                if(!evidence.sourceExpiresAt().isAfter(now)||elapsed<0||elapsed>=sourceBudget)throw invalidPortal();
                if(owner.closed||!internalExpiry.isAfter(now)||elapsed>=internalBudget)throw BoundedIamHttp.unavailable();
            }catch(PlatformException denied){failure=denied;throw denied;}
        }
        ServiceTicket ticket(){return evidence.verified().ticket();}
    }
    public static final class GatewayObservationQueryAuthorization {
        private final ObservationProof proof;private final String run;
        private GatewayObservationQueryAuthorization(ObservationProof proof,String run){this.proof=proof;this.run=run;}
        public ServiceTicket ticket(){proof.check();return proof.ticket();}
        public void requireVerified(){proof.check();}
        @Override public String toString(){return "GatewayObservationQueryAuthorization[private-proof]";}
    }
    public static final class GatewayStatusAuthorization {
        private final ObservationProof proof;
        private GatewayStatusAuthorization(ObservationProof proof){this.proof=proof;}
        public ServiceTicket ticket(){proof.check();return proof.ticket();}
        public void requireVerified(){proof.check();}
        public void requireSameQuery(GatewayObservationQueryAuthorization query){
            proof.check();query.proof.check();
            if(proof.owner!=query.proof.owner)throw AuthorityProtocol.denied();
            AuthorityProtocol.same(proof.sourceDigest,query.proof.sourceDigest);
            AuthorityProtocol.same(origin(proof.evidence.verified().claims()),origin(query.proof.evidence.verified().claims()));
            var main=proof.ticket().operation();var q=query.proof.ticket().operation();
            AuthorityProtocol.same(main.subject(),q.subject());AuthorityProtocol.same(main.resource().id(),query.run);
            if(main.grantRef().equals(q.grantRef())||main.operationId().equals(q.operationId()))throw AuthorityProtocol.denied();
        }
        @Override public String toString(){return "GatewayStatusAuthorization[private-proof]";}
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
        return authorizeRetained(serviceTicket,call,sessionExchange,null);
    }
    private RetainedAuthorization authorizeRetained(String serviceTicket,ServiceCall call,PortalExchange sessionExchange,VerifiedTicket gatewayTicket){
        final JsonNode input;final String ticketJti;final Instant ticketExpires;
        if(gatewayTicket==null){
            var jwt=decoder.decode(serviceTicket);
            if(!"delegated".equals(jwt.getClaims().get("authority_mode")))throw AuthorityProtocol.denied();
            input=AuthorityResponseVerifier.object(Base64.getUrlDecoder().decode(serviceTicket.split("\\.")[1]));
            ticketJti=jwt.getId();ticketExpires=jwt.getExpiresAt();
        }else{
            // Only openGatewayPortalEvents supplies this already verified, private fixed G ticket.
            input=gatewayTicket.claims();ticketJti=AuthorityProtocol.text(input,"jti");ticketExpires=gatewayTicket.ticket().expiresAt();
            RunObservationSelectors.requireGatewayPortalEventsCall(call,config.serviceId(),"delegated",input.path("operation").path("source_kind").asText(),input.path("operation").path("origin_kind").asText());
        }
        AuthorityProtocol.call(input,call);
        if(sessionExchange!=null){
            var deadline=AuthorityProtocol.time(sessionExchange.current(),"exp");
            long elapsed=System.nanoTime()-sessionExchange.startedNanos();
            if(!sessionExchange.sourceExpiresAt().isAfter(clock.instant()))throw invalidPortal();
            if(!deadline.isAfter(clock.instant())||elapsed<0||elapsed>=PortalSessionAuthorization.budget(sessionExchange.observedAt(),deadline))throw AuthorityProtocol.expired();
        }
        var observedAt=clock.instant();long startedNanos=System.nanoTime();
        var current=query("authorize",call,Map.of("service_ticket",serviceTicket),false,gatewayTicket!=null);
        AuthorityProtocol.same(ticketJti,AuthorityProtocol.text(current,"ticket_jti"));
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
        if(ticketExpires.isAfter(operation.expiresAt()))throw AuthorityProtocol.denied();
        // The online query may consume the last part of the ticket's lifetime.
        if(!ticketExpires.isAfter(clock.instant()))throw AuthorityProtocol.expired();
        var child=verifiedChildReceiver(input,current,call);
        // Both fields are from this exact verified ticket/decision pair. A partial rollout may
        // omit both, but malformed or single-sided evidence must never become current-only.
        if(!input.has("origin_authentication_id")&&!current.has("origin_authentication_id"))return RetainedAuthorization.currentOnly(result);
        String origin=origin(input);AuthorityProtocol.same(origin,origin(current));
        var retained=new RetainedAuthorization(this,result,initial,origin,ticketJti,ticketExpires,
                AuthorityProtocol.text(current,"nonce"),AuthorityProtocol.text(current,"request_sha256"),clock,observedAt,startedNanos);
        retained.child=child;return retained;
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
        private final HttpCurrentAuthorityClient mintingOwner;
        private final VerifiedAuthority current;
        private final Actor initial;
        private final String originAuthenticationId,ticketJti,nonce,requestSha256;
        private final Instant ticketExpiresAt;
        private final Clock verificationClock;
        private final Instant observedAt;
        private final long startedNanos;
        private ChildReceiverEvidence child;
        private RetainedAuthorization(HttpCurrentAuthorityClient mintingOwner,VerifiedAuthority current,Actor initial,String originAuthenticationId,
                String ticketJti,Instant ticketExpiresAt,String nonce,String requestSha256,Clock verificationClock,Instant observedAt,long startedNanos){
            this.mintingOwner=mintingOwner;this.current=Objects.requireNonNull(current);
            this.initial=initial==null?null:new Actor(initial.tenantId(),initial.principalId(),initial.roles(),initial.attributes());
            this.originAuthenticationId=originAuthenticationId;this.ticketJti=ticketJti;this.ticketExpiresAt=ticketExpiresAt;
            this.nonce=nonce;this.requestSha256=requestSha256;this.verificationClock=verificationClock;
            this.observedAt=observedAt;this.startedNanos=startedNanos;
        }
        public static RetainedAuthorization currentOnly(VerifiedAuthority current){
            return new RetainedAuthorization(null,current,null,null,null,null,null,null,null,null,0);
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
        return verifiedTicket(envelope,call,mode,upper,delegation,false);
    }
    private VerifiedTicket verifiedTicket(JsonNode envelope,ServiceCall call,String mode,Actor upper,DelegationRef delegation,boolean gatewayEvents){
        String raw=AuthorityProtocol.text(envelope,"ticket");
        var c=((gatewayEvents||sessionCall(call))?verifier.verifySession(raw,"at+jwt","service","urn:agent-platform:service:"+call.target(),config.authority().maxTicketSeconds()):verifier.verify(raw,"at+jwt","service","urn:agent-platform:service:"+call.target(),config.authority().maxTicketSeconds())).claims();
        AuthorityProtocol.call(c,call);AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"caller"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.same(call.action(),AuthorityProtocol.text(c,"scope"));
        AuthorityProtocol.same(mode,AuthorityProtocol.text(c,"authority_mode"));
        if(gatewayEvents)RunObservationSelectors.requireGatewayPortalEventsCall(call,config.serviceId(),mode,c.path("operation").path("source_kind").asText(),c.path("operation").path("origin_kind").asText());
        else if(!ServiceRoutePolicy.localRunAdvance(config.serviceId(),call,mode,c.path("operation").path("source_kind").asText(),c.path("operation").path("origin_kind").asText()) && new ServiceRoutePolicy().routes().stream().noneMatch(r->config.serviceId().equals(r.caller())&&call.target().equals(r.target())&&call.action().equals(r.action())&&mode.equals(r.mode())))throw AuthorityProtocol.denied();
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
        if((gatewayEvents||sessionCall(call))&&!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
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
        return query(operation,call,fields,token,false);
    }
    private JsonNode query(String operation,ServiceCall call,Map<String,Object> fields,boolean token,boolean fixedEvents){
        if("run.advance".equals(call.action()) && (!Set.of("resume","authorize").contains(operation)
                || !ServiceRoutePolicy.localRunAdvance(config.serviceId(),call,"delegated","DELEGATED","PORTAL")))throw AuthorityProtocol.denied();
        var request=new LinkedHashMap<String,Object>(fields);request.put("operation",operation);request.putAll(AuthorityProtocol.call(call));
        String nonce=UUID.randomUUID().toString();request.put("nonce",nonce);
        var requestedAt=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var bytes=http.postJsonAuthenticated(URI.create(token?config.authority().tokenUri():config.authority().decisionUri()),request,credentials.resolve());
        var outer=AuthorityResponseVerifier.object(bytes);
        var c=((fixedEvents||sessionCall(call))?verifier.verifySession(outer.path("assertion").textValue(),"agent-authority-decision+jwt","current-decision",config.authority().audience(),config.authority().maxDecisionSeconds()):verifier.verify(AuthorityProtocol.text(outer,"assertion"),"agent-authority-decision+jwt","current-decision",config.authority().audience(),config.authority().maxDecisionSeconds())).claims();
        AuthorityProtocol.same(nonce,AuthorityProtocol.text(c,"nonce"));AuthorityProtocol.same(AuthorityProtocol.digest(request),AuthorityProtocol.text(c,"request_sha256"));
        AuthorityProtocol.same(config.serviceId(),AuthorityProtocol.text(c,"client_id"));AuthorityProtocol.call(c,call);
        if(AuthorityProtocol.time(c,"iat").isBefore(requestedAt))throw BoundedIamHttp.unavailable();
        if(!c.path("active").isBoolean()||!c.path("current").isBoolean())throw BoundedIamHttp.unavailable();
        if(!c.path("active").booleanValue()||!c.path("current").booleanValue())throw AuthorityProtocol.denied();
        // Only after signature AND nonce/request/client/call/current validation is elapsed evidence
        // a trusted authorization deadline. It never leaves query as a successful expired decision.
        if((fixedEvents||sessionCall(call))&&!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
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
    private static final String EVENTS_PROFILE = "portal-run-events-response.v1";
    private static final String GATEWAY_EVENTS_PROFILE="portal-gateway-events-response.v1";
    private static final String CHILD_EVENTS_PROFILE="portal-engine-events-child-response.v1";
    private static JsonNode eventsObject(JsonNode node,String key){var value=node==null?null:node.get(key);if(value==null||!value.isObject())throw AuthorityProtocol.denied();return value;}

    /** One short opening owns one existing runtime slot, including every physical network call. */
    public static final class GatewayEventsOpening implements AutoCloseable {
        private final HttpCurrentAuthorityClient owner;
        private final EventsSlot slot;
        private final String run;
        private final long cursor,startedNanos;
        private final Instant observedAt;
        private String source;
        private GatewayObservationQueryAuthorization query;
        private Actor sourceCeiling;
        private boolean transferring,transferred;
        private PlatformException failure;
        private GatewayEventsOpening(HttpCurrentAuthorityClient owner,EventsSlot slot,String source,String run,long cursor){
            this.owner=owner;this.slot=slot;this.source=source;this.run=run;this.cursor=cursor;
            startedNanos=System.nanoTime();observedAt=owner.clock.instant();
        }
        private synchronized long remaining(){
            if(failure!=null)throw failure;
            if(owner.closed||slot.stopped)throw BoundedIamHttp.unavailable();
            if(transferred)throw AuthorityProtocol.denied();
            long left=TimeUnit.SECONDS.toNanos(5)-(System.nanoTime()-startedNanos);
            if(left<=0||owner.clock.instant().isBefore(observedAt)||!observedAt.plusSeconds(5).isAfter(owner.clock.instant()))throw AuthorityProtocol.expired();
            if(query!=null)query.requireVerified();return left;
        }
        public synchronized GatewayObservationQueryAuthorization queryAuthorization(){remaining();if(query==null)throw AuthorityProtocol.denied();return query;}
        public synchronized String publicRunId(){remaining();return run;}
        public synchronized long effectiveCursor(){remaining();return cursor;}
        private void tick(){try{remaining();}catch(PlatformException terminal){stop(terminal);}}
        private void stop(PlatformException reason){synchronized(this){if(transferred)return;if(failure==null)failure=reason;source=null;}var task=slot.openingTask;if(task!=null)task.cancel(true);slot.stop();}
        @Override public void close(){stop(AuthorityProtocol.expired());}
        @Override public String toString(){return "GatewayEventsOpening[private-proof]";}
    }
    @Override public GatewayEventsOpening beginGatewayPortalEvents(String source,String run,long cursor){
        if(closed||!"iam".equals(config.mode())||!"gateway".equals(config.serviceId()))throw BoundedIamHttp.unavailable();
        if(cursor<0)throw AuthorityProtocol.denied();
        // Validation of run uses no caller identity; true subject is decoded inside the owned task.
        RunObservationSelectors.events(run,"0".repeat(64));
        var runtime=eventsRuntime();var slot=runtime.reserve(()->{});
        var opening=new GatewayEventsOpening(this,slot,source,run,cursor);slot.gatewayOpening=opening;
        slot.deadlineNanos=opening.startedNanos+TimeUnit.SECONDS.toNanos(5);
        var task=runtime.task(slot,()->{
            final org.springframework.security.oauth2.jwt.Jwt original;
            try{original=decoder.decode(source);}catch(org.springframework.security.oauth2.jwt.JwtException invalid){throw invalidPortal();}
            if(!"user".equals(original.getClaimAsString("token_use")))throw invalidPortal();
            var mapped=new PortalClaimMapper(config.portal()).map(original);
            if(!mapped.scopes().contains("platform.access"))throw AuthorityProtocol.denied();
            var call=new ServiceCall("engine",RunObservationSelectors.ACTION,RunObservationSelectors.metadata(AuthorityProtocol.subject(mapped.actor()),run));
            synchronized(opening){opening.sourceCeiling=mapped.actor();}
            var envelope=query("exchange",call,Map.of("subject_token",source,"gateway_events_opening",true),true,true);
            var ticket=verifiedTicket(envelope,call,"delegated",mapped.actor(),null);
            AuthorityProtocol.same(AuthorityProtocol.subject(mapped.actor()),ticket.ticket().operation().subject());
            RunObservationSelectors.requirePurpose(call,"gateway","delegated",ticket.ticket().operation().sourceKind(),ticket.ticket().operation().originKind());
            origin(ticket.claims());
            var exchange=new PortalExchange(ticket,envelope,original.getExpiresAt(),opening.observedAt,opening.startedNanos);
            var q=new GatewayObservationQueryAuthorization(new ObservationProof(this,exchange,AuthorityProtocol.digest(source)),run);
            q.requireVerified();eventsLifecycle.accept("gateway-query-returned");opening.remaining();return q;
        },null);
        task.deadlineNanos=slot.deadlineNanos;slot.openingTask=task;
        try{runtime.execute(task);var q=awaitEvents(task,opening.remaining());synchronized(opening){opening.remaining();opening.query=q;}return opening;}
        catch(RuntimeException failure){opening.stop(failure instanceof PlatformException p?p:BoundedIamHttp.unavailable());throw failure;}
    }
    static final class GatewayEventsParent {
        final HttpCurrentAuthorityClient owner;final PortalEventsResponse holder;final PortalEventsEvidence evidence;
        private boolean consumed,childReserved,receiptAttempted;private JsonNode childAssociation;private String childOpenDigest;
        private GatewayEventsParent(HttpCurrentAuthorityClient owner,PortalEventsResponse holder,PortalEventsEvidence evidence){this.owner=owner;this.holder=holder;this.evidence=evidence;}
        synchronized void consume(){if(consumed)throw AuthorityProtocol.denied();consumed=true;}
    }
    @Override public GatewayPortalEventsResponse openGatewayPortalEvents(GatewayEventsOpening opening,ResourceBinding resource,Runnable cancel){
        if(opening==null||opening.owner!=this||cancel==null||resource==null)throw AuthorityProtocol.denied();
        final String source;final long budget;
        synchronized(opening){
            budget=opening.remaining();if(opening.transferring||opening.query==null)throw AuthorityProtocol.denied();
            AuthorityProtocol.same(RunObservationSelectors.events(opening.run,resource.versionDigest()),resource);
            opening.transferring=true;source=opening.source;opening.source=null;
            synchronized(opening.slot){if(opening.slot.stopped)throw AuthorityProtocol.expired();opening.slot.cancel=cancel;}
        }
        var slot=opening.slot;var runtime=slot.runtime;
        var task=runtime.task(slot,()->{
            opening.remaining();var q=opening.query.proof;
            var call=new ServiceCall("gateway",RunObservationSelectors.GATEWAY_EVENTS_ACTION,resource);
            var envelope=query("exchange",call,Map.of("subject_token",source,"query_ticket",q.ticket().value()),true,true);
            var verified=verifiedTicket(envelope,call,"delegated",opening.sourceCeiling,null,true);
            if(verified.ticket().operation().expiresAt().isAfter(q.evidence.sourceExpiresAt()))throw AuthorityProtocol.denied();
            if(!verified.ticket().expiresAt().isAfter(clock.instant()))throw AuthorityProtocol.expired();
            var initial=authorizeRetained(verified.ticket().value(),call,null,verified);initial.requireVerified();
            AuthorityProtocol.same(origin(q.evidence.verified().claims()),initial.originAuthenticationId);
            AuthorityProtocol.same(q.ticket().operation().subject(),initial.current.operation().subject());
            if(q.ticket().operation().grantRef().equals(initial.current.delegation().value())||q.ticket().operation().operationId().equals(initial.current.operation().operationId()))throw AuthorityProtocol.denied();
            var hard=Collections.min(List.of(opening.observedAt.plusSeconds(55),q.evidence.sourceExpiresAt(),initial.current.operation().expiresAt(),initial.current.delegation().expiresAt()));
            var binding=new PortalEventsBinding(UUID.randomUUID().toString(),resource,opening.cursor,PortalEventsBinding.observationRequestSha256(initial.current.operation().subject(),initial.originAuthenticationId,resource,opening.cursor),hard);
            var immutable=immutableEvents(initial,binding,GATEWAY_EVENTS_PROFILE);
            ((com.fasterxml.jackson.databind.node.ObjectNode)immutable).set("gateway_query",Json.read(Json.write(Map.of(
                    "ticket_jti",AuthorityProtocol.text(q.evidence.verified().claims(),"jti"),"grant_ref",q.ticket().operation().grantRef(),
                    "operation_id",q.ticket().operation().operationId(),"source_token_sha256",q.sourceDigest,
                    "nonce",AuthorityProtocol.text(q.evidence.current(),"nonce"),"request_sha256",AuthorityProtocol.text(q.evidence.current(),"request_sha256")))));
            var admission=new PortalEventsAdmission(this,initial,binding,immutable,opening.observedAt,opening.startedNanos,hard,false);
            opening.remaining();
            long started=System.nanoTime();var observed=clock.instant();
            var claims=eventsQuery("portal_gateway_events_open",call,Map.of("decision_id",initial.current.decisionId(),"binding",binding,"profile",GATEWAY_EVENTS_PROFILE,"immutable",immutable));
            eventsLifecycle.accept("gateway-open-returned");
            opening.remaining();return openingEventsEvidence(admission,claims,observed,started,slot);
        },null);
        task.deadlineNanos=opening.startedNanos+TimeUnit.SECONDS.toNanos(5);slot.openingTask=task;
        try{
            runtime.execute(task);var evidence=awaitEvents(task,budget);
            synchronized(opening){opening.remaining();evidence.remainingNanos();var holder=new PortalEventsResponse(evidence);
                synchronized(slot){if(slot.stopped||closed)throw BoundedIamHttp.unavailable();slot.response=holder;slot.gatewayOpening=null;opening.transferred=true;}
                return new GatewayPortalEventsResponse(new GatewayEventsParent(this,holder,evidence));}
        }catch(RuntimeException failure){task.cancel(true);opening.stop(failure instanceof PlatformException p?p:BoundedIamHttp.unavailable());throw failure;}
    }
    @Override public void checkGatewayPortalEvents(GatewayPortalEventsResponse response){
        if(response==null)throw AuthorityProtocol.denied();checkPortalEvents(response.requireParent(this).holder);
    }
    public static final class PortalEventsChildAuthorization {
        private final HttpCurrentAuthorityClient owner;private final GatewayEventsParent parent;private final VerifiedTicket verified;
        private final JsonNode association;private final Instant expires;private final long anchor,budget;
        private PlatformException failure;
        private PortalEventsChildAuthorization(HttpCurrentAuthorityClient owner,GatewayEventsParent parent,VerifiedTicket verified,JsonNode association,Instant observed,long started){
            this.owner=owner;this.parent=parent;this.verified=verified;this.association=association;
            expires=Collections.min(List.of(verified.ticket().expiresAt(),AuthorityProtocol.time(association,"hard_deadline")));
            anchor=started;budget=PortalSessionAuthorization.budget(observed,expires);
        }
        public ServiceTicket ticket(){requireVerified();return verified.ticket();}
        public synchronized void requireVerified(){
            if(failure!=null)throw failure;
            try{parent.holder.remainingNanos(owner);long elapsed=System.nanoTime()-anchor;if(elapsed<0||elapsed>=budget||!expires.isAfter(owner.clock.instant()))throw AuthorityProtocol.expired();}
            catch(PlatformException terminal){failure=terminal;throw terminal;}
        }
        @Override public String toString(){return "PortalEventsChildAuthorization[private-proof]";}
    }
    @Override public PortalEventsChildAuthorization exchangePortalEventsChild(String source,GatewayPortalEventsResponse response){
        if(response==null)throw AuthorityProtocol.denied();var parent=response.requireParent(this);
        synchronized(parent){if(parent.childReserved)throw AuthorityProtocol.denied();parent.childReserved=true;}
        var evidence=parent.evidence;
        AuthorityProtocol.same(AuthorityProtocol.text(evidence.immutable.get("gateway_query"),"source_token_sha256"),AuthorityProtocol.digest(source));
        return eventsWork(evidence.slot,parent.holder.remainingNanos(this),()->{
            parent.holder.remainingNanos(this);long started=System.nanoTime();var observed=clock.instant();
            var call=new ServiceCall("engine",RunObservationSelectors.EVENTS_ACTION,evidence.binding.resource());
            var envelope=eventsQuery("portal_events_child_exchange",call,Map.of("subject_token",source,"parent_continuation",evidence.continuation,"parent_immutable",evidence.immutable));
            eventsLifecycle.accept("child-exchange-returned");
            var verified=verifiedTicket(envelope,call,"delegated",null,null);
            var marker=eventsObject(verified.claims(),"portal_events_child");
            var association=eventsObject(marker,"association");
            AuthorityProtocol.same(AuthorityProtocol.digest(association),AuthorityProtocol.text(envelope,"child_association_sha256"));
            verifyAssociation(association,evidence.immutable,verified.claims());String childOpen=AuthorityProtocol.text(marker,"child_open");verifyChildOpen(childOpen,association);
            synchronized(parent){parent.holder.remainingNanos(this);parent.childAssociation=association.deepCopy();parent.childOpenDigest=AuthorityProtocol.digest(childOpen);}
            var proof=new PortalEventsChildAuthorization(this,parent,verified,association,observed,started);proof.requireVerified();return proof;
        });
    }
    private record ChildReceiverEvidence(JsonNode association,String childOpen){}
    private ChildReceiverEvidence verifiedChildReceiver(JsonNode ticket,JsonNode current,ServiceCall call){
        for(String marker:List.of("parent_response_id","parent_response","child_id","child_open","child_association","child_association_sha256","child_receipt"))
            if(ticket.has(marker)||current.has(marker))throw AuthorityProtocol.denied();
        boolean a=ticket.has("portal_events_child"),b=current.has("portal_events_child");
        if(!a&&!b)return null;
        if(!a||!b)throw AuthorityProtocol.denied();AuthorityProtocol.same(ticket.get("portal_events_child"),current.get("portal_events_child"));
        RunObservationSelectors.requirePortalEventsResponseCall(call,AuthorityProtocol.text(ticket,"caller"),"delegated",ticket.path("operation").path("source_kind").asText(),ticket.path("operation").path("origin_kind").asText());
        var marker=eventsObject(ticket,"portal_events_child");var association=eventsObject(marker,"association");
        verifyAssociation(association,association.get("parent"),ticket);String proof=AuthorityProtocol.text(marker,"child_open");verifyChildOpen(proof,association);
        return new ChildReceiverEvidence(association.deepCopy(),proof);
    }
    private static void verifyAssociation(JsonNode association,JsonNode parent,JsonNode ticket){
        AuthorityProtocol.same("portal-events-child-association.v1",AuthorityProtocol.text(association,"schema"));
        AuthorityProtocol.same(parent,association.get("parent"));AuthorityProtocol.same(GATEWAY_EVENTS_PROFILE,AuthorityProtocol.text(parent,"profile"));
        var engine=eventsObject(association,"engine");
        for(String name:List.of("jti","grant_ref","grant_expires_at","authorization_version","operation","initial_ceiling","origin_authentication_id","action","target","caller","resource"))AuthorityProtocol.same(ticket.get(name),engine.get(name));
        AuthorityProtocol.same("PORTAL",AuthorityProtocol.text(parent,"source_kind"));AuthorityProtocol.same("PORTAL",AuthorityProtocol.text(parent,"origin_kind"));
        AuthorityProtocol.same(parent.get("subject"),Json.tree(AuthorityProtocol.subject(AuthorityProtocol.actor(ticket))));
        AuthorityProtocol.same(parent.get("origin_authentication_id"),ticket.get("origin_authentication_id"));
        AuthorityProtocol.same(parent.get("resource"),ticket.get("resource"));
        AuthorityProtocol.same(parent.path("gateway_query").get("source_token_sha256"),association.get("source_token_sha256"));
        var binding=Json.read(eventsObject(association,"child_binding").toString(),PortalEventsBinding.class);
        var g=Json.read(parent.get("binding").toString(),PortalEventsBinding.class);
        AuthorityProtocol.same(g.resource(),binding.resource());AuthorityProtocol.same(g.effectiveCursor(),binding.effectiveCursor());AuthorityProtocol.same(g.observationRequestSha256(),binding.observationRequestSha256());
        AuthorityProtocol.same(binding.hardDeadline(),AuthorityProtocol.time(association,"hard_deadline"));
        if(binding.responseId().equals(g.responseId())||binding.hardDeadline().isAfter(g.hardDeadline())||binding.hardDeadline().isAfter(AuthorityProtocol.time(ticket.get("operation"),"expires_at"))||binding.hardDeadline().isAfter(AuthorityProtocol.time(ticket,"grant_expires_at")))throw AuthorityProtocol.denied();
        if(Objects.equals(parent.get("grant_ref"),ticket.get("grant_ref"))||Objects.equals(parent.path("operation").get("operationId"),ticket.path("operation").get("operation_id")))throw AuthorityProtocol.denied();
    }
    private JsonNode verifyChildOpen(String proof,JsonNode association){
        var c=verifier.verifySession(proof,"agent-portal-events-child-open+jwt","portal-events-child-open","urn:agent-platform:service:engine",55).claims();
        requireAssociationToken(c,association);return c;
    }
    private void requireAssociationToken(JsonNode claims,JsonNode association){
        AuthorityProtocol.same(AuthorityProtocol.digest(association),AuthorityProtocol.text(claims,"association_sha256"));
        AuthorityProtocol.same(association.path("parent").path("binding").path("responseId").asText(),AuthorityProtocol.text(claims,"parent_response_id"));
        AuthorityProtocol.same(association.path("child_binding").path("responseId").asText(),AuthorityProtocol.text(claims,"child_response_id"));
        AuthorityProtocol.same(AuthorityProtocol.time(association,"hard_deadline"),AuthorityProtocol.time(claims,"exp"));
        if(!AuthorityProtocol.time(claims,"exp").isAfter(clock.instant())||AuthorityProtocol.time(claims,"iat").isAfter(clock.instant()))throw AuthorityProtocol.expired();
    }
    @Override public PortalEventsResponse openPortalEventsChild(RetainedAuthorization initial,long cursor,Runnable cancel){
        if(closed||!"iam".equals(config.mode())||!"engine".equals(config.serviceId()))throw BoundedIamHttp.unavailable();
        if(initial==null||initial.mintingOwner!=this||initial.child==null||cancel==null)throw AuthorityProtocol.denied();
        var first=AuthorityContext.claimPortalEventsOpening(initial);sameAdmission(first,initial);
        if(first.mintingOwner!=this||first.child==null)throw AuthorityProtocol.denied();AuthorityProtocol.same(first.child,initial.child);
        initial.requireVerified();var child=initial.child;
        var binding=Json.read(child.association.get("child_binding").toString(),PortalEventsBinding.class);AuthorityProtocol.same(cursor,binding.effectiveCursor());
        var hard=Collections.min(List.of(binding.hardDeadline(),first.observedAt.plusSeconds(55),first.current.operation().expiresAt(),first.current.delegation().expiresAt()));
        var immutable=immutableEvents(initial,binding,CHILD_EVENTS_PROFILE);
        ((com.fasterxml.jackson.databind.node.ObjectNode)immutable).set("child_association",child.association);
        ((com.fasterxml.jackson.databind.node.ObjectNode)immutable).put("child_open_sha256",AuthorityProtocol.digest(child.childOpen));
        var admission=new PortalEventsAdmission(this,initial,binding,immutable,first.observedAt,first.startedNanos,hard,true);
        admission.requireOpening(initial);var runtime=eventsRuntime();var slot=runtime.reserve(cancel);
        long budget=Math.min(TimeUnit.SECONDS.toNanos(5)-(System.nanoTime()-first.startedNanos),PortalSessionAuthorization.budget(initial.observedAt,initial.current.expiresAt())-(System.nanoTime()-initial.startedNanos));
        var task=runtime.task(slot,()->{
            long started=System.nanoTime();var observed=clock.instant();
            var claims=eventsQuery("portal_events_child_open",initial.current.call(),Map.of("profile",CHILD_EVENTS_PROFILE,"decision_id",initial.current.decisionId(),"binding",binding,"immutable",immutable,"child_open",child.childOpen));
            eventsLifecycle.accept("child-open-returned");
            var evidence=openingEventsEvidence(admission,claims,observed,started,slot);
            String receipt=AuthorityProtocol.text(claims,"child_receipt");verifyChildReceipt(receipt,child.association,immutable);
            slot.childReceipt=receipt;return evidence;
        },null);
        task.deadlineNanos=System.nanoTime()+Math.max(0,budget);slot.deadlineNanos=task.deadlineNanos;slot.openingTask=task;
        try{runtime.execute(task);var evidence=awaitEvents(task,budget);admission.requireOpening(initial);evidence.remainingNanos();var response=new PortalEventsResponse(evidence);
            synchronized(slot){if(slot.stopped||closed)throw BoundedIamHttp.unavailable();slot.response=response;slot.engineChild=true;}return response;
        }catch(RuntimeException failure){task.cancel(true);slot.stop();throw failure;}
    }
    private JsonNode verifyChildReceipt(String receipt,JsonNode association,JsonNode expectedImmutable){
        if(receipt==null||receipt.length()>4096||!receipt.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw AuthorityProtocol.denied();
        var c=verifier.verifySession(receipt,"agent-portal-events-child-receipt+jwt","portal-events-child-receipt","urn:agent-platform:service:gateway",55).claims();
        requireAssociationToken(c,association);
        for(String f:List.of("engine_opening_decision_id","engine_admission_nonce"))if(AuthorityProtocol.text(c,f).length()>128)throw AuthorityProtocol.denied();
        AuthorityProtocol.hash(AuthorityProtocol.text(c,"engine_admission_request_sha256"));AuthorityProtocol.hash(AuthorityProtocol.text(c,"child_immutable_sha256"));
        if(expectedImmutable!=null){
            AuthorityProtocol.same(AuthorityProtocol.digest(expectedImmutable),AuthorityProtocol.text(c,"child_immutable_sha256"));
            for(var pair:Map.of("engine_opening_decision_id","opening_decision_id","engine_admission_nonce","admission_nonce","engine_admission_request_sha256","admission_request_sha256").entrySet())AuthorityProtocol.same(expectedImmutable.get(pair.getValue()),c.get(pair.getKey()));
        }
        return c;
    }
    void acceptGatewayChildReceipt(GatewayPortalEventsResponse response,String receipt){
        var parent=response.requireParent(this);final JsonNode association;
        synchronized(parent){if(parent.receiptAttempted||parent.childAssociation==null)throw AuthorityProtocol.denied();parent.receiptAttempted=true;association=parent.childAssociation;}
        eventsWork(parent.evidence.slot,parent.holder.remainingNanos(this),()->{
            parent.holder.remainingNanos(this);var claims=verifyChildReceipt(receipt,association,null);
            // Rebuild from the already verified E ticket and fixed association, never a caller DTO.
            // The new receiver decision/nonce/raw request hash must reproduce its signed immutable.
            AuthorityProtocol.same(AuthorityProtocol.digest(expectedChildImmutable(association,parent.childOpenDigest,claims)),AuthorityProtocol.text(claims,"child_immutable_sha256"));
            parent.holder.remainingNanos(this);return null;
        });
    }
    private JsonNode expectedChildImmutable(JsonNode association,String childOpenDigest,JsonNode receipt){
        var engine=eventsObject(association,"engine");var op=engine.get("operation");
        var initial=AuthorityProtocol.actor(engine.get("initial_ceiling"));var resource=AuthorityProtocol.resource(engine.get("resource"));
        var operation=AuthorityProtocol.operation(op,initial,resource,AuthorityProtocol.text(engine,"grant_ref"),clock);
        var immutable=new LinkedHashMap<String,Object>();
        immutable.put("profile",CHILD_EVENTS_PROFILE);immutable.put("binding",association.get("child_binding"));immutable.put("consumer","engine");
        immutable.put("caller",AuthorityProtocol.text(engine,"caller"));immutable.put("action",AuthorityProtocol.text(engine,"action"));immutable.put("resource",engine.get("resource"));immutable.put("subject",AuthorityProtocol.subject(initial));
        immutable.put("source_kind","PORTAL");immutable.put("origin_kind","PORTAL");immutable.put("operation",operation);
        immutable.put("grant_ref",AuthorityProtocol.text(engine,"grant_ref"));immutable.put("grant_expires_at",AuthorityProtocol.time(engine,"grant_expires_at").getEpochSecond());immutable.put("source_expires_at",operation.expiresAt().getEpochSecond());
        immutable.put("opening_decision_id",AuthorityProtocol.text(receipt,"engine_opening_decision_id"));immutable.put("opening_authorization_version",AuthorityProtocol.text(engine,"authorization_version"));immutable.put("ticket_jti",AuthorityProtocol.text(engine,"jti"));
        immutable.put("origin_authentication_id",AuthorityProtocol.text(engine,"origin_authentication_id"));immutable.put("initial_ceiling_sha256",AuthorityProtocol.ceiling(initial));immutable.put("admission_nonce",AuthorityProtocol.text(receipt,"engine_admission_nonce"));immutable.put("admission_request_sha256",AuthorityProtocol.text(receipt,"engine_admission_request_sha256"));
        immutable.put("hard_deadline",AuthorityProtocol.time(association,"hard_deadline").getEpochSecond());immutable.put("child_association",association);immutable.put("child_open_sha256",childOpenDigest);
        return Json.read(Json.write(immutable));
    }
    @Override public String portalEventsChildReceipt(PortalEventsResponse response){
        if(response==null)throw AuthorityProtocol.denied();response.remainingNanos(this);
        var runtime=eventsRuntime();String receipt=null;
        for(var slot:runtime.snapshot())synchronized(slot){if(slot.response==response){
            if(!slot.engineChild||slot.childReceipt==null)throw AuthorityProtocol.denied();receipt=slot.childReceipt;break;
        }}
        if(receipt==null)throw AuthorityProtocol.denied();response.remainingNanos(this);return receipt;
    }
    private <T> T eventsWork(EventsSlot slot,long budget,Callable<T> work){
        var task=slot.runtime.task(slot,work,null);task.deadlineNanos=System.nanoTime()+Math.max(0,budget);
        try{slot.runtime.execute(task);return awaitEvents(task,budget);}catch(RuntimeException failure){task.cancel(true);throw failure;}
    }

    /** Historical current is never refreshed into a permission; only its immutable anchor survives. */
    private static final class PortalEventsAdmission {
        private final HttpCurrentAuthorityClient owner;
        private final VerifiedAuthority opening;
        private final JsonNode immutable;
        private final PortalEventsBinding binding;
        private final Instant hardDeadline;
        private final long anchorNanos, hardBudgetNanos;
        private final boolean receiverOpening;

        private PortalEventsAdmission(HttpCurrentAuthorityClient owner, RetainedAuthorization mapping,
                RetainedAuthorization first, PortalEventsBinding binding) {
            this.owner = owner;
            this.opening = mapping.current;
            this.binding = binding;
            this.anchorNanos = first.startedNanos;
            hardDeadline = Collections.min(List.of(first.observedAt.plusSeconds(55),
                    first.current.operation().expiresAt(), first.current.delegation().expiresAt(),
                    binding.hardDeadline()));
            hardBudgetNanos = PortalSessionAuthorization.budget(first.observedAt, hardDeadline);
            immutable = owner.immutableEvents(mapping, binding);
            receiverOpening=true;
        }

        private PortalEventsAdmission(HttpCurrentAuthorityClient owner,RetainedAuthorization mapping,PortalEventsBinding binding,
                JsonNode immutable,Instant observedAt,long anchor,Instant hard,boolean receiverOpening){
            this.owner=owner;opening=mapping.current;this.binding=binding;this.immutable=immutable;
            anchorNanos=anchor;hardDeadline=hard;hardBudgetNanos=PortalSessionAuthorization.budget(observedAt,hard);
            this.receiverOpening=receiverOpening;
        }

        private void requireOpening(RetainedAuthorization mapping) {
            if (owner.closed) throw BoundedIamHttp.unavailable();
            if (mapping.current != opening || mapping.mintingOwner != owner) throw AuthorityProtocol.denied();
            if(receiverOpening)AuthorityContext.requireFirstBound(mapping);
            mapping.requireVerified();
            long elapsed = System.nanoTime() - mapping.startedNanos;
            long currentBudget = Math.min(Duration.ofSeconds(5).toNanos(),
                    PortalSessionAuthorization.budget(mapping.observedAt, mapping.current.expiresAt()));
            if (elapsed < 0 || elapsed >= currentBudget) throw AuthorityProtocol.expired();
            requireHard();
        }

        private void requireHard() {
            long elapsed = System.nanoTime() - anchorNanos;
            if (!hardDeadline.isAfter(owner.clock.instant()) || elapsed < 0 || elapsed >= hardBudgetNanos) {
                throw AuthorityProtocol.expired();
            }
        }
    }

    /** Package-visible type for Java's cross-file seam; only this outer class can construct it. */
    static final class PortalEventsEvidence {
        final HttpCurrentAuthorityClient owner;
        final PortalEventsBinding binding;
        final VerifiedAuthority current;
        final Instant hardDeadline;
        final long anchorNanos, hardBudgetNanos, checkedStartedNanos, checkedBudgetNanos;
        final String continuation;
        final JsonNode immutable;
        final EventsSlot slot;
        private boolean consumed;

        private PortalEventsEvidence(PortalEventsAdmission admission, VerifiedAuthority current,
                String continuation, JsonNode immutable, Instant observedAt, long startedNanos,
                EventsSlot slot) {
            owner = admission.owner;
            binding = admission.binding;
            hardDeadline = admission.hardDeadline;
            anchorNanos = admission.anchorNanos;
            hardBudgetNanos = admission.hardBudgetNanos;
            this.current = current;
            this.continuation = continuation;
            this.immutable = immutable.deepCopy();
            this.slot = slot;
            checkedStartedNanos = startedNanos;
            checkedBudgetNanos = Math.min(Duration.ofSeconds(5).toNanos(),
                    PortalSessionAuthorization.budget(observedAt, current.expiresAt()));
        }

        private PortalEventsEvidence(PortalEventsEvidence previous, VerifiedAuthority current,
                Instant observedAt, long startedNanos) {
            owner = previous.owner;
            binding = previous.binding;
            hardDeadline = previous.hardDeadline;
            anchorNanos = previous.anchorNanos;
            hardBudgetNanos = previous.hardBudgetNanos;
            continuation = previous.continuation;
            immutable = previous.immutable;
            slot = previous.slot;
            this.current = current;
            checkedStartedNanos = startedNanos;
            checkedBudgetNanos = Math.min(Duration.ofSeconds(5).toNanos(),
                    PortalSessionAuthorization.budget(observedAt, current.expiresAt()));
        }

        synchronized void consume() {
            if (consumed) throw AuthorityProtocol.denied();
            consumed = true;
        }

        long remainingNanos() {
            if (owner.closed) throw BoundedIamHttp.unavailable();
            AuthorityProtocol.requireCurrent(current, owner.clock);
            long now = System.nanoTime();
            long hardElapsed = now - anchorNanos;
            long checkedElapsed = now - checkedStartedNanos;
            if (!hardDeadline.isAfter(owner.clock.instant()) || hardElapsed < 0 || checkedElapsed < 0) {
                throw AuthorityProtocol.expired();
            }
            long remaining = Math.min(hardBudgetNanos - hardElapsed, checkedBudgetNanos - checkedElapsed);
            remaining = Math.min(remaining, PortalSessionAuthorization.budget(owner.clock.instant(),
                    Collections.min(List.of(hardDeadline, current.expiresAt()))));
            if (remaining <= 0) throw AuthorityProtocol.expired();
            return remaining;
        }
    }

    @Override
    public PortalEventsResponse openPortalEvents(RetainedAuthorization initial, PortalEventsBinding binding,
            Runnable cancel) {
        if (closed || !"iam".equals(config.mode()) || !"engine".equals(config.serviceId())) {
            throw BoundedIamHttp.unavailable();
        }
        if (initial == null || initial.mintingOwner != this || binding == null || cancel == null) {
            throw AuthorityProtocol.denied();
        }
        if(initial.child!=null)throw AuthorityProtocol.denied();
        var first = AuthorityContext.claimPortalEventsOpening(initial);
        if (first.mintingOwner != this || !first.hasInitialProof()) throw AuthorityProtocol.denied();
        sameAdmission(first, initial);
        var current = initial.current;
        RunObservationSelectors.requirePortalEventsResponseCall(current.call(), current.caller(), "delegated",
                current.operation().sourceKind(), current.operation().originKind());
        AuthorityProtocol.same(current.call().resource(), binding.resource());
        AuthorityProtocol.same(PortalEventsBinding.observationRequestSha256(AuthorityProtocol.subject(current.actor()),
                initial.originAuthenticationId, current.call().resource(), binding.effectiveCursor()),
                binding.observationRequestSha256());
        var admission = new PortalEventsAdmission(this, initial, first, binding);
        admission.requireOpening(initial);
        var runtime = eventsRuntime();
        var slot = runtime.reserve(cancel);
        long started = System.nanoTime();
        var observed = clock.instant();
        long budget = Math.min(PortalSessionAuthorization.budget(initial.observedAt, current.expiresAt())
                        - (started - initial.startedNanos),
                admission.hardBudgetNanos - (started - admission.anchorNanos));
        var task = runtime.task(slot, () -> {
            var claims = eventsQuery("portal_events_open", current.call(),
                    Map.of("decision_id", current.decisionId(), "binding", binding, "profile", EVENTS_PROFILE));
            return openingEventsEvidence(admission, claims, observed, started, slot);
        }, null);
        slot.deadlineNanos = started + Math.max(0, budget);
        task.deadlineNanos = slot.deadlineNanos;
        slot.openingTask = task;
        runtime.execute(task);
        try {
            var evidence = awaitEvents(task, budget);
            admission.requireOpening(initial);
            evidence.remainingNanos();
            var response = new PortalEventsResponse(evidence);
            slot.response = response;
            if (closed || slot.stopped) {
                response.close();
                throw BoundedIamHttp.unavailable();
            }
            return response;
        } catch (RuntimeException failure) {
            task.cancel(true);
            slot.stop();
            throw failure;
        }
    }

    private void sameAdmission(RetainedAuthorization first, RetainedAuthorization current) {
        AuthorityProtocol.same(first.current.call(), current.current.call());
        AuthorityProtocol.same(first.current.caller(), current.current.caller());
        AuthorityProtocol.same(first.current.operation(), current.current.operation());
        AuthorityProtocol.same(first.current.delegation(), current.current.delegation());
        AuthorityProtocol.same(first.initial, current.initial);
        AuthorityProtocol.same(first.originAuthenticationId, current.originAuthenticationId);
        AuthorityProtocol.same(first.ticketJti, current.ticketJti);
        AuthorityProtocol.same(first.ticketExpiresAt, current.ticketExpiresAt);
    }

    private JsonNode eventsQuery(String operation, ServiceCall call, Map<String, Object> fields) {
        if (closed) throw BoundedIamHttp.unavailable();
        var request = new LinkedHashMap<String, Object>(fields);
        request.put("operation", operation);
        request.putAll(AuthorityProtocol.call(call));
        String nonce = UUID.randomUUID().toString();
        request.put("nonce", nonce);
        var requestedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var bytes = http.postJsonAuthenticated(URI.create(config.authority().decisionUri()), request,
                credentials.resolve());
        var outer = AuthorityResponseVerifier.object(bytes);
        // Reuse only the cryptographic verifier primitive that normalizes all key/signature failures.
        // Its elapsed-envelope mode grants no permission: this fixed profile checks fresh expiry below.
        var claims = verifier.verifySession(outer.path("assertion").textValue(),
                "agent-authority-decision+jwt", "current-decision", config.authority().audience(),
                config.authority().maxDecisionSeconds()).claims();
        AuthorityProtocol.same(nonce, AuthorityProtocol.text(claims, "nonce"));
        AuthorityProtocol.same(AuthorityProtocol.digest(request), AuthorityProtocol.text(claims, "request_sha256"));
        AuthorityProtocol.same(config.serviceId(), AuthorityProtocol.text(claims, "client_id"));
        AuthorityProtocol.call(claims, call);
        if (AuthorityProtocol.time(claims, "iat").isBefore(requestedAt)
                || !claims.path("active").isBoolean() || !claims.path("current").isBoolean()) {
            throw BoundedIamHttp.unavailable();
        }
        if (!claims.path("active").booleanValue() || !claims.path("current").booleanValue()) {
            throw AuthorityProtocol.denied();
        }
        if (!AuthorityProtocol.time(claims, "exp").isAfter(clock.instant())) throw AuthorityProtocol.expired();
        return claims;
    }

    private JsonNode immutableEvents(RetainedAuthorization initial, PortalEventsBinding binding) {
        return immutableEvents(initial,binding,EVENTS_PROFILE);
    }
    private JsonNode immutableEvents(RetainedAuthorization initial, PortalEventsBinding binding,String profile) {
        var current = initial.current;
        var immutable = new LinkedHashMap<String, Object>();
        immutable.put("profile", profile);
        immutable.put("binding", binding);
        immutable.put("consumer", GATEWAY_EVENTS_PROFILE.equals(profile)?"gateway":"engine");
        immutable.put("caller", current.caller());
        immutable.put("action", current.call().action());
        immutable.put("resource", AuthorityProtocol.resource(current.call().resource()));
        immutable.put("subject", AuthorityProtocol.subject(current.actor()));
        immutable.put("source_kind", "PORTAL");
        immutable.put("origin_kind", "PORTAL");
        immutable.put("operation", Json.tree(current.operation()));
        immutable.put("grant_ref", current.delegation().value());
        immutable.put("grant_expires_at", current.delegation().expiresAt().getEpochSecond());
        immutable.put("source_expires_at", current.operation().expiresAt().getEpochSecond());
        immutable.put("opening_decision_id", current.decisionId());
        immutable.put("opening_authorization_version", current.authorizationVersion());
        immutable.put("ticket_jti", initial.ticketJti);
        immutable.put("origin_authentication_id", initial.originAuthenticationId);
        immutable.put("initial_ceiling_sha256", AuthorityProtocol.ceiling(initial.initial));
        immutable.put("admission_nonce", initial.nonce);
        immutable.put("admission_request_sha256", initial.requestSha256);
        // The public deadline can exceed the conservative local one; neither signed deadline extends it.
        immutable.put("hard_deadline", binding.hardDeadline().getEpochSecond());
        // Normalize local integer node widths to the exact JSON representation verified on the wire.
        return Json.read(Json.write(immutable));
    }

    private PortalEventsEvidence openingEventsEvidence(PortalEventsAdmission admission, JsonNode claims,
            Instant observedAt, long startedNanos, EventsSlot slot) {
        var expected = admission.immutable;
        String continuation = AuthorityProtocol.text(claims, "continuation_ref");
        verifyEventsContinuation(continuation, expected);
        var current = eventsCurrent(claims, admission.opening, expected, continuation);
        return new PortalEventsEvidence(admission, current, continuation, expected, observedAt, startedNanos, slot);
    }

    private void verifyEventsContinuation(String continuation, JsonNode expected) {
        final JsonNode proof;
        try {
            String profile=AuthorityProtocol.text(expected,"profile");
            String use=GATEWAY_EVENTS_PROFILE.equals(profile)?"portal-gateway-events-continuation":CHILD_EVENTS_PROFILE.equals(profile)?"portal-engine-events-child-continuation":"portal-events-continuation";
            proof = verifier.verify(continuation, "agent-"+use+"+jwt",
                    use, config.authority().audience(), 55).claims();
        } catch (PlatformException invalidSignatureOrKey) {
            throw BoundedIamHttp.unavailable();
        }
        if(CHILD_EVENTS_PROFILE.equals(AuthorityProtocol.text(expected,"profile")))AuthorityProtocol.same(AuthorityProtocol.digest(expected),AuthorityProtocol.text(proof,"immutable_sha256"));
        else AuthorityProtocol.same(expected, proof.get("immutable"));
        AuthorityProtocol.same(AuthorityProtocol.time(expected, "hard_deadline"), AuthorityProtocol.time(proof, "exp"));
        if(!AuthorityProtocol.time(proof,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();
        for (String forbidden : List.of("parent_response_id", "completion_permit", "service_ticket")) {
            if (proof.has(forbidden)) throw AuthorityProtocol.denied();
        }
    }

    private VerifiedAuthority eventsCurrent(JsonNode claims, VerifiedAuthority original, JsonNode immutable,
            String continuation) {
        String profile=AuthorityProtocol.text(immutable,"profile");
        if(!Set.of(EVENTS_PROFILE,GATEWAY_EVENTS_PROFILE,CHILD_EVENTS_PROFILE).contains(profile))throw AuthorityProtocol.denied();
        AuthorityProtocol.same(profile, AuthorityProtocol.text(claims, "profile"));
        AuthorityProtocol.same(immutable, claims.get("immutable"));
        AuthorityProtocol.same(continuation, AuthorityProtocol.text(claims, "continuation_ref"));
        for (String forbidden : List.of("parent_response_id", "completion_permit", "parent_response")) {
            if (claims.has(forbidden)) throw AuthorityProtocol.denied();
        }
        var current = authority(claims, original.call());
        if (current.authorizationVersion().length() > 128 || current.decisionId().length() > 128) {
            throw AuthorityProtocol.denied();
        }
        if(GATEWAY_EVENTS_PROFILE.equals(profile))RunObservationSelectors.requireGatewayPortalEventsCall(current.call(),current.caller(),"delegated",current.operation().sourceKind(),current.operation().originKind());
        else RunObservationSelectors.requirePortalEventsResponseCall(current.call(), current.caller(), "delegated",
                current.operation().sourceKind(), current.operation().originKind());
        AuthorityProtocol.same(original.caller(), current.caller());
        AuthorityProtocol.same(original.operation(), current.operation());
        AuthorityProtocol.same(original.delegation(), current.delegation());
        AuthorityProtocol.same(AuthorityProtocol.subject(original.actor()), AuthorityProtocol.subject(current.actor()));
        AuthorityProtocol.same(AuthorityProtocol.text(immutable, "origin_authentication_id"), origin(claims));
        AuthorityProtocol.same(AuthorityProtocol.text(immutable, "ticket_jti"), AuthorityProtocol.text(claims, "ticket_jti"));
        AuthorityProtocol.same(AuthorityProtocol.text(immutable, "initial_ceiling_sha256"),
                AuthorityProtocol.ceiling(AuthorityProtocol.actor(claims.get("initial_ceiling"))));
        AuthorityProtocol.same(current.expiresAt(), AuthorityProtocol.time(claims, "checked_until"));
        if (current.expiresAt().isAfter(AuthorityProtocol.time(immutable, "hard_deadline"))) {
            throw AuthorityProtocol.denied();
        }
        // Authorization version/roles/attributes belong to this newly signed decision, not the opening.
        return current;
    }

    private PortalEventsEvidence renewedEventsEvidence(PortalEventsEvidence previous) {
        previous.remainingNanos();
        long started = System.nanoTime();
        var observed = clock.instant();
        String profile=AuthorityProtocol.text(previous.immutable,"profile");
        String operation=GATEWAY_EVENTS_PROFILE.equals(profile)?"portal_gateway_events_check":CHILD_EVENTS_PROFILE.equals(profile)?"portal_events_child_check":"portal_events_check";
        var claims = eventsQuery(operation, previous.current.call(), Map.of(
                "profile", profile, "binding", previous.binding,
                "continuation_ref", previous.continuation));
        verifyEventsContinuation(previous.continuation, previous.immutable);
        var current = eventsCurrent(claims, previous.current, previous.immutable, previous.continuation);
        var next = new PortalEventsEvidence(previous, current, observed, started);
        next.remainingNanos();
        return next;
    }

    @Override
    public void checkPortalEvents(PortalEventsResponse response) {
        if (response == null) throw AuthorityProtocol.denied();
        var task = response.renew(this);
        try {
            awaitEvents(task, response.remainingNanos(this));
        } catch (RuntimeException failure) {
            response.stop(failure instanceof PlatformException p ? p : BoundedIamHttp.unavailable());
            throw failure;
        }
    }

    EventsTask<Void> renewEvents(PortalEventsResponse response, PortalEventsEvidence previous) {
        var runtime = eventsRuntime();
        eventsLifecycle.accept("renew-deadline");
        // Expiry/owner checks may throw. Do them before this unpublished task reserves slot work.
        long deadline = System.nanoTime() + previous.remainingNanos();
        var task = runtime.<Void>task(previous.slot, () -> {
            try {
                var next = renewedEventsEvidence(previous);
                response.install(previous, next);
                return null;
            } catch (RuntimeException failure) {
                response.stop(failure instanceof PlatformException p ? p : BoundedIamHttp.unavailable());
                throw failure;
            }
        }, response);
        task.deadlineNanos = deadline;
        return task;
    }

    void executeEvents(EventsTask<?> task) {
        task.slot.runtime.execute(task);
    }

    void cancelEvents(EventsSlot slot) {
        slot.stop();
    }

    private static <T> T awaitEvents(Future<T> task, long nanos) {
        try {
            if (nanos <= 0) throw AuthorityProtocol.expired();
            long start = System.nanoTime();
            var result = task.get(nanos, TimeUnit.NANOSECONDS);
            if (task instanceof EventsTask<?> owned) {
                long left = nanos - (System.nanoTime() - start);
                if (left <= 0 || !owned.exited.await(left, TimeUnit.NANOSECONDS)) throw new TimeoutException();
            }
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw BoundedIamHttp.unavailable();
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw AuthorityProtocol.expired();
        } catch (CancellationException cancelled) {
            throw BoundedIamHttp.unavailable();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof PlatformException p) throw p;
            throw BoundedIamHttp.unavailable();
        }
    }

    private EventsRuntime eventsRuntime() {
        synchronized (eventsMutex) {
            if (closed) throw BoundedIamHttp.unavailable();
            if (eventsRuntime == null) eventsRuntime = new EventsRuntime();
            return eventsRuntime;
        }
    }

    /** Explicit capacity policy: 64 slots, two physical workers, one bounded queue and one timer. */
    final class EventsRuntime {
        private final Set<EventsSlot> slots = new HashSet<>();
        private final AtomicInteger threadIds = new AtomicInteger();
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), r -> new Thread(r, "iam-portal-events-worker-" + threadIds.incrementAndGet()));
        private volatile Thread watchdogThread;
        private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                r -> {
                    var thread = new Thread(r, "iam-portal-events-watchdog");
                    watchdogThread = thread;
                    return thread;
                });

        private EventsRuntime() {
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
        }

        private synchronized EventsSlot reserve(Runnable cancel) {
            if (closed) throw BoundedIamHttp.unavailable();
            if (slots.size() >= 64) throw new PlatformException(429, "STREAM_CAPACITY", "观察响应容量已满");
            var slot = new EventsSlot(this, cancel);
            slots.add(slot);
            return slot;
        }

        private synchronized List<EventsSlot> snapshot() { return List.copyOf(slots); }
        private synchronized void released(EventsSlot slot) { slots.remove(slot); }

        private <T> EventsTask<T> task(EventsSlot slot, Callable<T> work, PortalEventsResponse response) {
            slot.addWork();
            return new EventsTask<>(slot, work, response);
        }

        private void execute(EventsTask<?> task) {
            try {
                // Registration, timer handoff and queue submission share the terminal-state monitor.
                // finish/cancel cannot retire a reservation and then let execute create new queue work.
                synchronized (task) {
                    if (task.finished || task.isCancelled() || task.submitted) return;
                    if (task.deadlineNanos != Long.MAX_VALUE) {
                        var timeout = scheduler.schedule(() -> {
                            if (!task.isDone()) {
                                if (task.response != null) task.response.stop(AuthorityProtocol.expired());
                                task.cancel(true);
                                if (task.response == null) task.slot.stop();
                            }
                        }, Math.max(0, task.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                        eventsLifecycle.accept("timer-created");
                        task.timeout = timeout;
                    }
                    task.submitted = true;
                    workers.execute(task);
                }
            }
            catch (RuntimeException rejected) {
                task.cancel(false);
                throw BoundedIamHttp.unavailable();
            }
        }

        private void executeCancellation(EventsTask<Void> task) {
            synchronized (task) {
                if (task.submitted) return;
                try {
                    workers.execute(task);
                    task.submitted = true;
                    return;
                } catch (RejectedExecutionException saturatedOrClosed) {
                    // Never block the sole deadline thread on a user callback. Its slot retains this
                    // unsubmitted task; a later tick retries delivery only, never authority HTTP.
                    if (Thread.currentThread() == watchdogThread) return;
                    task.submitted = true;
                    // A rejected cancellation has not happened. The stopping caller owns its delivery;
                    // no escape thread/queue, and the callback still retains its physical reservation.
                }
            }
            task.run();
        }

        private void tick() {
            for (var slot : snapshot()) {
                if (slot.stopped) {
                    slot.stop();
                    continue;
                }
                var opening=slot.gatewayOpening;
                if(opening!=null){opening.tick();continue;}
                var response = slot.response;
                if (response != null) {
                    response.tick();
                } else {
                    EventsTask<?> openingTask=null;EventsTask<Void> cancellation=null;
                    synchronized(slot){
                        // A completed Opening may have transferred while this tick read its slot.
                        if(slot.response!=null||slot.gatewayOpening!=null)continue;
                        if (closed || System.nanoTime() >= slot.deadlineNanos) {
                            openingTask=slot.openingTask;cancellation=slot.stoppedTask();
                        }
                    }
                    if(openingTask!=null)openingTask.cancel(true);
                    if(cancellation!=null)executeCancellation(cancellation);
                }
            }
        }

        private void close() {
            scheduler.shutdownNow();
            for (var slot : snapshot()) {
                var response = slot.response;
                if (response != null) response.stop(BoundedIamHttp.unavailable());
                else {
                    if(slot.gatewayOpening!=null)slot.gatewayOpening.stop(BoundedIamHttp.unavailable());
                    if (slot.openingTask != null) slot.openingTask.cancel(true);
                }
                // Holder stop publication does not establish cancellation submission. Help deliver
                // the slot's one shared callback before shutting down the worker executor.
                slot.stop();
            }
            workers.shutdown();
        }
    }

    static final class EventsSlot {
        private final EventsRuntime runtime;
        private Runnable cancel;
        private int work;
        private volatile boolean stopped;
        private volatile long deadlineNanos = Long.MAX_VALUE;
        private volatile EventsTask<?> openingTask;
        private volatile PortalEventsResponse response;
        private volatile GatewayEventsOpening gatewayOpening;
        private boolean engineChild;
        private String childReceipt;
        private EventsTask<Void> cancellation;

        private EventsSlot(EventsRuntime runtime, Runnable cancel) {
            this.runtime = runtime;
            this.cancel = cancel;
        }
        private synchronized void addWork() {if(stopped)throw BoundedIamHttp.unavailable();work++; }
        private void finished() {
            boolean release;
            synchronized (this) {
                work--;
                release = stopped && work == 0;
            }
            if (release) runtime.released(this);
        }
        private void stop() {
            runtime.executeCancellation(stoppedTask());
        }
        private EventsTask<Void> stoppedTask() {
            synchronized (this) {
                if (!stopped) {
                    // Reserve before publishing stop; even caller-run rejection handling keeps capacity.
                    work++;
                    stopped = true;
                    cancellation = new EventsTask<>(this, () -> {
                        try { cancel.run(); }
                        catch (RuntimeException ignored) { /* best-effort owned cancellation */ }
                        return null;
                    }, null);
                }
                return cancellation;
            }
        }
    }

    /** Future.done/cancel is not physical exit. Running work owns its slot until this run's finally. */
    static final class EventsTask<T> extends FutureTask<T> {
        private final EventsSlot slot;
        private final PortalEventsResponse response;
        private long deadlineNanos = Long.MAX_VALUE;
        private volatile ScheduledFuture<?> timeout;
        private final CountDownLatch exited = new CountDownLatch(1);
        private boolean started, finished, submitted;

        private EventsTask(EventsSlot slot, Callable<T> callable, PortalEventsResponse response) {
            super(callable);
            this.slot = slot;
            this.response = response;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (finished) return;
                started = true;
            }
            try { super.run(); }
            finally { finish(); }
        }

        @Override
        public boolean cancel(boolean interrupt) {
            boolean cancelled;
            boolean queued;
            synchronized (this) {
                cancelled = super.cancel(interrupt);
                queued = !started;
                if (queued) slot.runtime.workers.remove(this);
            }
            if (queued) finish();
            return cancelled;
        }

        private void finish() {
            synchronized (this) {
                if (finished) return;
                finished = true;
                if (timeout != null) timeout.cancel(false);
            }
            if (response != null) response.renewFinished(this);
            slot.finished();
            exited.countDown();
        }
    }

    @Override
    public void close() {
        EventsRuntime runtime;
        synchronized (eventsMutex) {
            closed = true;
            runtime = eventsRuntime;
        }
        if (runtime != null) runtime.close();
        decoder.close();
        http.close();
        if (runtime != null) {
            try {
                runtime.workers.awaitTermination(3, TimeUnit.SECONDS);
                runtime.scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
