package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Finite protocol fixture, never a production authority or PG substitute. The policy server is a
 * separate HTTP handler and trust domain; all INTEGRATION decisions query that server exactly once.
 */
public final class LoopbackAuthority implements AutoCloseable {
    final MutableClock clock=new MutableClock();
    final RSAKey key=LoopbackIssuer.key("authority"),portalKey=LoopbackIssuer.key("portal"),policyKey=LoopbackIssuer.key("policy");
    final byte[] integrationKey=new byte[32];
    final HttpServer server,resolver;
    final HttpClient policyHttp=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final Map<String,String> secrets=new HashMap<>();
    final Map<String,Grant> grants=new LinkedHashMap<>();
    final Map<String,Operation> operations=new LinkedHashMap<>();
    final Map<String,Decision> decisions=new LinkedHashMap<>();
    final Map<String,Continuation> continuations=new LinkedHashMap<>();
    private record Observation(SubjectKey owner,ResourceBinding resource){}
    private final Map<String,Observation> observations=new HashMap<>();
    private final Set<String> deniedObservations=new HashSet<>();
    private static final String EVENTS_ACTION = "proxy.engine.get.RunController.events";
    /** Trusted fixture setup only, after production admission; never called by an HTTP request. */
    public void registerObservation(SubjectKey owner,String run,String acceptedSha){
        var binding=new Observation(Objects.requireNonNull(owner),RunObservationSelectors.status(run,acceptedSha));
        var previous=observations.putIfAbsent(run,binding);if(previous!=null&&!previous.equals(binding))throw new IllegalArgumentException("IMMUTABLE_OBSERVATION_BINDING");
    }
    public void denyObservation(String run){deniedObservations.add(run);}
    final List<JsonNode> requests=new ArrayList<>();
    final List<String> authenticatedClients=new ArrayList<>();
    final AtomicInteger businessSends=new AtomicInteger(),policyCalls=new AtomicInteger(),resolverToAuthority=new AtomicInteger();
    boolean revoked,scopeAllowed=true,approverAllowed=true,operationPolicy=true,unavailable,invalidJson,badSignature,emptyEnvelope;
    Set<String> currentRoles=Set.of("EDITOR");
    long sessionGrantSeconds=300;
    Runnable observeAuthorityJwks=()->{};
    Set<String> approverRoles=Set.of("APPROVER");
    boolean jwksUnavailable;
    boolean policyActive=true,policySubjectAllowed=true,policyUnavailable,wrongPolicyKey,omitPolicyField,wrongPolicyBinding,wrongPolicyType;
    String version="v1",policyVersion="7:1";
    String lastPermit;
    Consumer<Map<String,Object>> changeDecision=c->{};
    Consumer<Map<String,Object>> changeTicket=c->{};
    Consumer<Map<String,Object>> changePermit=c->{};
    int serial;
    URI externalPolicy;
    public void externalPolicy(URI endpoint){if(!"http".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||!PolicyRequestVerifier.PATH.equals(endpoint.getPath()))throw new IllegalArgumentException("FINITE_LOOPBACK_ONLY");externalPolicy=endpoint;}
    public void clearFiniteGrants(){grants.clear();operations.clear();decisions.clear();requests.clear();observations.clear();deniedObservations.clear();changeDecision=c->{};}
    public void narrowAdminAtResourceCheck(){var count=new AtomicInteger();changeDecision=c->{if(c.containsKey("roles")&&count.incrementAndGet()>=2)c.put("roles",Set.of());};}
    Actor explicitPortalActor;
    public void portalActor(Actor actor){explicitPortalActor=actor;currentRoles=actor.roles();}
    record Grant(String id,Actor ceiling,ResourceBinding resource,Instant expires,String origin,Instant sourceExpires,String version,String policyVersion,Map<String,Object> integration,String originAuthenticationId,ServiceCall originalCall) {}
    record Operation(String id,Grant grant,String source,Instant issued,Instant expires) {}
    record Decision(String id,Operation operation,ServiceCall call,String caller,Instant expires,String ticketJti) {}
    record Continuation(String id,Decision opening,ResponseBinding binding) {}
    record PolicyResolveRequest(int schemaVersion,long credentialId,String expectedTenantId,String expectedAppCode,
        String expectedBizSystemCode,String userId,String expectedPrincipalId,String expectedPolicyVersion,String nonce) {}
    record PolicyResolveResult(int schemaVersion,long credentialId,String tenantId,String appCode,String bizSystemCode,String userId,
        String principalId,boolean active,boolean subjectAllowed,Set<String> roleCeiling,Map<String,Set<String>> attributeCeiling,
        Set<String> dataLabels,Set<String> allowedOrigins,String policyVersion,String policyDigest,String nonce) {}
    public LoopbackAuthority() throws Exception {
        new java.security.SecureRandom().nextBytes(integrationKey);
        for(String c:AuthorityProperties.SERVICES)secrets.put(c,UUID.randomUUID().toString());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        resolver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",x->{observeAuthorityJwks.run();send(x,jwksUnavailable?503:200,new JWKSet(key.toPublicJWK()).toString());});
        server.createContext("/portal-jwks",x->send(x,200,new JWKSet(portalKey.toPublicJWK()).toString()));
        server.createContext("/token",this::handle);server.createContext("/decision",this::handle);
        server.createContext("/",x->{businessSends.incrementAndGet();send(x,404,"no-business-route");});
        resolver.createContext("/internal/iam/integration-policy/resolve",this::resolve);
        server.start();resolver.start();
    }
    public String issuer(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public String policyIssuer(){return "http://127.0.0.1:"+resolver.getAddress().getPort();}
    public AuthorityProperties config(String service){
        return new AuthorityProperties("iam",service,"gateway".equals(service)?new AuthorityProperties.Portal(true,issuer()+"/portal",issuer()+"/portal-jwks","portal-gateway","portal-web",
            explicitPortalActor==null?Set.of("EDITOR"):explicitPortalActor.roles(),Set.of("region","dataLabels"),explicitPortalActor==null?Map.of("region",Set.of("A"),"dataLabels",Set.of("restricted")):explicitPortalActor.attributes(),Set.of("platform.access")):null,
            new AuthorityProperties.Authority(issuer(),issuer()+"/jwks",issuer()+"/token",issuer()+"/decision",service,"env:AGENT_SECRET_IAM_"+service.toUpperCase(Locale.ROOT)+"_CLIENT","RS256","urn:agent-platform:service:"+service,60,5,1000,2000),
            "gateway".equals(service)?new AuthorityProperties.Integration(true,"test-only-ref"):null,null,null,false,null);
    }
    public HttpCurrentAuthorityClient client(String service){
        var c=config(service);return new HttpCurrentAuthorityClient(c,clock,new WorkloadCredentialResolver(c,name->secrets.get(service),true),true);
    }
    static ServiceCall call(String caller,String target){
        var route=new ServiceRoutePolicy().routes().stream().filter(r->caller.equals(r.caller())&&target.equals(r.target())&&"delegated".equals(r.mode())).findFirst().orElseThrow();
        return new ServiceCall(target,route.action(),new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));
    }
    static Actor ceiling(){return new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",Set.of("A"),"dataLabels",Set.of("confidential","restricted")));}
    public String portal(){return portal(40);}
    public String portal(long ttl){
        var c=base("user","portal-gateway",ttl);c.put("iss",issuer()+"/portal");c.put("sub","alice");c.put("tenant_id","tenant-a");
        c.put("client_id","portal-web");c.put("roles",Set.of("ADMIN","EDITOR"));c.put("attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("confidential")));c.put("scope","platform.access");
        if(explicitPortalActor!=null){c.put("tenant_id",explicitPortalActor.tenantId());c.put("sub",explicitPortalActor.principalId());c.put("roles",explicitPortalActor.roles());c.put("attributes",explicitPortalActor.attributes());}
        return sign(c,portalKey,"at+jwt");
    }
    public Map<String,Object> integrationClaims(){
        var c=base("integration","agent-platform-embed",20);c.put("sub","alice");c.put("tenant_id","tenant-a");
        c.put("app_code","app-a");c.put("biz_system_code","biz-a");c.put("user_id","alice");c.put("credential_id",7L);c.put("policy_digest",Digest.sha256("policy-v1"));c.put("origin","https://embed.example.invalid");
        return c;
    }
    String integration(){return integration(integrationClaims());}
    public String integration(Map<String,Object> c){
        try{var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("JWT")).build(),new Payload(Json.write(c)));jwt.sign(new MACSigner(integrationKey));return jwt.serialize();}
        catch(Exception e){throw new AssertionError(e);}
    }
    public Map<String,Object> base(String use,String audience,long ttl){
        var c=new LinkedHashMap<String,Object>();c.put("iss",issuer());c.put("aud",audience);c.put("token_use",use);
        c.put("iat",clock.instant().getEpochSecond());c.put("nbf",clock.instant().getEpochSecond());c.put("exp",clock.instant().plusSeconds(ttl).getEpochSecond());c.put("jti","id-"+(++serial));return c;
    }
    public String sign(Map<String,Object> claims,RSAKey key,String typ){
        try{var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(new JOSEObjectType(typ)).build(),new Payload(Json.write(claims)));jwt.sign(new RSASSASigner(key));return jwt.serialize();}
        catch(Exception e){throw new AssertionError(e);}
    }
    /** Private child-process stdin only in the D3a0 smoke; never log or pass on a command line. */
    public String testClientSecret(String service){if(!Set.of("app","gateway").contains(service))throw new IllegalArgumentException("FINITE_CLIENT_ONLY");return secrets.get(service);}
    public Clock clock(){return clock;}
    public void advanceClock(Duration elapsed){clock.advance(elapsed.getSeconds());}
    public RSAKey policySigningKey(){return policyKey;}
    public RSAKey authoritySigningKey(){return key;}
    public int grantCount(){return grants.size();}
    public int authorityCalls(){return requests.size();}
    /** Diagnostic names only; never expose the recorded source tokens or service tickets. */
    public List<String> observedOperations(){return requests.stream().map(r->r.path("operation").asText()).toList();}
    private final AtomicInteger requestAttempts=new AtomicInteger();
    public int requestAttempts(){return requestAttempts.get();}
    public void unavailable(boolean value){unavailable=value;}
    public void revoked(boolean value){revoked=value;}
    public void decisionMutation(Consumer<Map<String,Object>> mutation){changeDecision=mutation;}
    public void ticketMutation(Consumer<Map<String,Object>> mutation){changeTicket=mutation;}
    public int policyCalls(){return policyCalls.get();}
    public int resolverToAuthorityCalls(){return resolverToAuthority.get();}
    void handle(HttpExchange x){
        requestAttempts.incrementAndGet();
        try{
            if(unavailable){send(x,503,"unavailable-test");return;}
            if(emptyEnvelope){send(x,200,"{}");return;}
            if(invalidJson){send(x,200,"{invalid");return;}
            var request=AuthorityResponseVerifier.object(x.getRequestBody().readNBytes(65537));
            if(requests.size()>=128)throw AuthorityProtocol.denied();
            requests.add(request);
            String auth=x.getRequestHeaders().getFirst("Authorization");
            if(auth!=null&&auth.startsWith("Bearer "))resolverToAuthority.incrementAndGet();
            String caller=secrets.entrySet().stream().filter(e->("Basic "+Base64.getEncoder().encodeToString((e.getKey()+":"+e.getValue()).getBytes(StandardCharsets.UTF_8))).equals(auth)).map(Map.Entry::getKey).findFirst().orElseThrow();
            authenticatedClients.add(caller);
            var call=new ServiceCall(AuthorityProtocol.text(request,"target"),AuthorityProtocol.text(request,"action"),AuthorityProtocol.resource(request.get("resource")));
            String operation=AuthorityProtocol.text(request,"operation");
            var c=base("current-decision","urn:agent-platform:service:"+caller,5);
            c.putAll(AuthorityProtocol.call(call));c.put("nonce",AuthorityProtocol.text(request,"nonce"));c.put("request_sha256",AuthorityProtocol.digest(request));
            c.put("client_id",caller);c.put("active",true);c.put("current",true);
            try{dispatch(operation,request,caller,call,c);}catch(Exception denied){c.put("active",false);c.put("reason","fixture-denied");}
            changeDecision.accept(c);
            send(x,200,Json.write(Map.of("assertion",sign(c,badSignature?portalKey:key,"agent-authority-decision+jwt"))));
        }catch(Exception e){send(x,503,"fixture-unverifiable");}
    }
    void dispatch(String type,JsonNode request,String caller,ServiceCall call,Map<String,Object> reply)throws Exception {
        if(type.equals("response_check")){
            var continuation=continuations.get(AuthorityProtocol.text(request,"continuation_ref"));if(continuation==null)throw AuthorityProtocol.denied();
            AuthorityProtocol.same(continuation.opening.call.target(),caller);AuthorityProtocol.same(Json.tree(continuation.binding),request.get("binding"));
            current(continuation.opening.operation.grant);activeOperation(continuation.opening.operation);
            lease(reply,continuation,null);return;
        }
        if(type.equals("response_open")){
            var decision=decisions.get(AuthorityProtocol.text(request,"decision_id"));
            if(decision==null||!decision.expires.isAfter(clock.instant()))throw AuthorityProtocol.denied();
            AuthorityProtocol.same(decision.call.target(),caller);AuthorityProtocol.same(decision.call,call);
            current(decision.operation.grant);activeOperation(decision.operation);
            var binding=Json.read(request.get("binding").toString(),ResponseBinding.class);AuthorityProtocol.same(call.resource(),binding.resource());
            var max=clock.instant().plusMillis("sse".equals(binding.kind())?55000:610000);
            if(binding.hardDeadline().isAfter(max)||(!decision.operation.source.equals("INTEGRATION")&&binding.hardDeadline().isAfter(decision.operation.expires)))throw AuthorityProtocol.denied();
            var proof=base("response-continuation","urn:agent-platform:service:"+caller,55);
            proof.put("exp",binding.hardDeadline().getEpochSecond());proof.put("binding",binding);proof.put("opening_decision_id",decision.id);
            proof.put("operation_id",decision.operation.id);proof.put("source_kind",decision.operation.source);proof.put("source_expires_at",decision.operation.expires.getEpochSecond());
            proof.put("consumer",caller);proof.put("caller",decision.caller);proof.put("action",decision.call.action());
            proof.put("ticket_jti",decision.ticketJti);proof.put("parent_response_id","parent-"+binding.responseId());
            var continuation=new Continuation(sign(proof,key,"agent-response-continuation+jwt"),decision,binding);continuations.put(continuation.id,continuation);
            lease(reply,continuation,decision);return;
        }
        if(type.equals("client")){
            route(caller,call,"workload");reply.put("ticket",workload(caller,call));return;
        }
        if(type.equals("authorize_workload")){
            var ticket=service(AuthorityProtocol.text(request,"service_ticket"));AuthorityProtocol.same("workload",AuthorityProtocol.text(ticket,"authority_mode"));AuthorityProtocol.call(ticket,call);
            AuthorityProtocol.same(caller,call.target());route(AuthorityProtocol.text(ticket,"caller"),call,"workload");
            reply.put("ticket_jti",AuthorityProtocol.text(ticket,"jti"));reply.put("subject_caller",AuthorityProtocol.text(ticket,"caller"));reply.put("authority_mode","workload");return;
        }
        boolean local="run.advance".equals(call.action());
        boolean session="portal.session.read".equals(call.action());
        boolean observation=RunObservationSelectors.STATUS_ACTION.equals(call.action());
        boolean events = EVENTS_ACTION.equals(call.action());
        if (events && !Set.of("exchange", "authorize").contains(type)) throw AuthorityProtocol.denied();
        if(observation&&!Set.of("exchange","authorize").contains(type))throw AuthorityProtocol.denied();
        if(session&&(!"gateway".equals(caller)||!"gateway".equals(call.target())||!Set.of("exchange","authorize").contains(type)))throw AuthorityProtocol.denied();
        if(local&&!Set.of("resume","authorize").contains(type))throw AuthorityProtocol.denied();
        Operation op;String subjectCaller=caller;
        if(type.equals("exchange")||type.equals("exchange_integration")){
            if(!caller.equals("gateway")||grants.size()>=(explicitPortalActor==null?2:32))throw AuthorityProtocol.denied();
            String token=AuthorityProtocol.text(request,"subject_token");boolean integration=type.equals("exchange_integration");
            JsonNode subject=verifySubject(token,integration);
            if(session){if(integration)throw AuthorityProtocol.denied();requireSessionResource(call,subject);}
            if(observation){if(integration)throw AuthorityProtocol.denied();requireObservationStatus(call,caller,"PORTAL","PORTAL",subject);}
            if (events) {
                if (integration) throw AuthorityProtocol.denied();
                requireObservationEvents(call, caller, "PORTAL", "PORTAL", subject);
            }
            if(RunObservationSelectors.ACTION.equals(call.action())){
                if(integration)throw AuthorityProtocol.denied();
                requireObservationQuery(call,caller,"PORTAL","PORTAL",subject);
            }
            Map<String,Object> details=Json.mapper().convertValue(subject,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            String sourceHash=Digest.sha256(token);
            // A verified authentication survives JWT text/header/JSON representation changes.
            // Keep its first version/ceiling in the existing finite Grant store; no new cache.
            Grant original=originalAuthentication(subject);
            PolicyResolveResult actual=integration&&externalPolicy!=null?externalPolicy(details,original==null?null:original.policyVersion,original==null?null:original.ceiling):null;
            if(actual!=null)details.put("source_token_sha256",sourceHash); // Diagnostic binding, never authentication identity.
            Actor ceiling=original!=null?original.ceiling:actual==null?(explicitPortalActor==null?ceiling():explicitPortalActor):initialIntegrationCeiling(subject,actual);
            String originId="oa1:"+AuthorityProtocol.digest(List.of("origin-authentication.v1",AuthorityProtocol.text(subject,"iss"),AuthorityProtocol.text(subject,"jti")));
            var grant=new Grant("grant-"+(++serial),ceiling,call.resource(),clock.instant().plusSeconds(session?sessionGrantSeconds:300),integration?"INTEGRATION":"PORTAL",AuthorityProtocol.time(subject,"exp"),original==null?version:original.version,integration?(original!=null?original.policyVersion:actual==null?policyVersion:actual.policyVersion()):null,details,originId,call);
            // The initial external result is the only policy read for this exchange. Subsequent
            // decisions query with this persisted grant version, never with caller-supplied overrides.
            if(actual==null)current(grant);else if(revoked||!scopeAllowed)throw AuthorityProtocol.denied();
            grants.put(grant.id,grant);op=operation(grant,grant.origin,session?earlier(grant.sourceExpires,grant.expires):grant.sourceExpires);
            if(integration){for(String k:List.of("tenant_id","sub","app_code","biz_system_code","user_id","credential_id","policy_digest"))reply.put(k,subject.get(k));reply.put("subject_token_sha256",AuthorityProtocol.digest(token));}
        }else if(type.equals("resume")){
            var grant=grants.get(AuthorityProtocol.text(request,"grant_ref"));if(grant==null||!operationPolicy)throw AuthorityProtocol.denied();
            if(local)requireLocalGrant(caller,call,grant);
            current(grant);AuthorityProtocol.same(grant.resource,call.resource());op=operation(grant,"DELEGATED",earlier(clock.instant().plusSeconds(90),grant.expires));
        }else if(type.equals("forward")){
            var d=decisions.get(AuthorityProtocol.text(request,"decision_id"));if(d==null||!d.expires.isAfter(clock.instant()))throw AuthorityProtocol.denied();
            AuthorityProtocol.same(caller,d.call.target());AuthorityProtocol.same(d.operation.id,AuthorityProtocol.text(request,"operation_id"));AuthorityProtocol.same(d.operation.grant.id,AuthorityProtocol.text(request,"grant_ref"));
            op=d.operation;current(op.grant);activeOperation(op);AuthorityProtocol.same(op.grant.resource,call.resource());
        }else if(type.equals("authorize")){
            var ticket=service(AuthorityProtocol.text(request,"service_ticket"));AuthorityProtocol.same("delegated",AuthorityProtocol.text(ticket,"authority_mode"));
            AuthorityProtocol.call(ticket,call);AuthorityProtocol.same(caller,call.target());
            op=operations.get(AuthorityProtocol.text(ticket.get("operation"),"operation_id"));if(op==null)throw AuthorityProtocol.denied();
            if(RunObservationSelectors.ACTION.equals(call.action())){
                AuthorityProtocol.same(call,op.grant.originalCall);
                requireObservationQuery(call,AuthorityProtocol.text(ticket,"caller"),op.source,op.grant.origin,Json.tree(op.grant.integration));
            }
            if(observation){
                AuthorityProtocol.same(call,op.grant.originalCall);
                requireObservationStatus(call,AuthorityProtocol.text(ticket,"caller"),op.source,op.grant.origin,Json.tree(op.grant.integration));
            }
            if (events) {
                AuthorityProtocol.same(call, op.grant.originalCall);
                requireObservationEvents(call, AuthorityProtocol.text(ticket, "caller"), op.source,
                        op.grant.origin, Json.tree(op.grant.integration));
            }
            if(session){
                AuthorityProtocol.same("PORTAL",op.source);AuthorityProtocol.same("PORTAL",op.grant.origin);
                AuthorityProtocol.same(call,op.grant.originalCall);
                requireSessionResource(call,Json.tree(op.grant.integration));
                AuthorityProtocol.same("gateway",AuthorityProtocol.text(ticket,"caller"));
            }
            current(op.grant);activeOperation(op);AuthorityProtocol.same(op.grant.version,AuthorityProtocol.text(ticket,"authorization_version"));
            subjectCaller=AuthorityProtocol.text(ticket,"caller");if(local){requireLocalGrant(subjectCaller,call,op.grant);AuthorityProtocol.same("DELEGATED",op.source);}else route(subjectCaller,call,"delegated");
            reply.put("ticket_jti",AuthorityProtocol.text(ticket,"jti"));decision(reply,op,call,subjectCaller);return;
        }else if(type.equals("approver")){
            var grant=grants.get(AuthorityProtocol.text(request,"grant_ref"));if(grant==null||!approverAllowed)throw AuthorityProtocol.denied();
            current(grant);AuthorityProtocol.same(Json.tree(new SubjectKey("tenant-a","approver")),request.get("approver"));
            op=operation(new Grant(grant.id,new Actor("tenant-a","approver",approverRoles,grant.ceiling.attributes()),call.resource(),grant.expires,grant.origin,grant.sourceExpires,grant.version,grant.policyVersion,grant.integration,grant.originAuthenticationId,grant.originalCall),"DELEGATED",clock.instant().plusSeconds(30));
            decision(reply,op,call,caller);return;
        }else throw AuthorityProtocol.denied();
        if(!local)route(caller,call,"delegated");reply.put("ticket",ticket(op,caller,call));
    }
    JsonNode verifySubject(String token,boolean integration)throws Exception {
        var jws=JWSObject.parse(token);var c=AuthorityResponseVerifier.object(jws.getPayload().toBytes());
        if(integration){
            if(!JWSAlgorithm.HS256.equals(jws.getHeader().getAlgorithm())||!jws.verify(new MACVerifier(integrationKey)))throw AuthorityProtocol.denied();
            AuthorityProtocol.same(new JOSEObjectType("JWT"),jws.getHeader().getType());
            AuthorityProtocol.same(externalPolicy==null?issuer():ServiceTokens.ISSUER,AuthorityProtocol.text(c,"iss"));
            AuthorityProtocol.same("integration",AuthorityProtocol.text(c,"token_use"));AuthorityProtocol.same("agent-platform-embed",AuthorityProtocol.text(c,"aud"));
            if(AuthorityProtocol.time(c,"exp").isAfter(AuthorityProtocol.time(c,"iat").plusSeconds(300)))throw AuthorityProtocol.denied();
            if(externalPolicy==null){AuthorityProtocol.same("app-a",AuthorityProtocol.text(c,"app_code"));AuthorityProtocol.same("biz-a",AuthorityProtocol.text(c,"biz_system_code"));AuthorityProtocol.same("alice",AuthorityProtocol.text(c,"user_id"));
            if(!c.path("credential_id").isIntegralNumber()||c.path("credential_id").longValue()!=7)throw AuthorityProtocol.denied();
            AuthorityProtocol.same(Digest.sha256("policy-v1"),AuthorityProtocol.text(c,"policy_digest"));}
            else {
                // This finite legacy HMAC source has its own fixed issuer; it is not a production IAM.
                AuthorityProtocol.same(new JOSEObjectType("JWT"),jws.getHeader().getType());
                AuthorityProtocol.same(ServiceTokens.ISSUER,AuthorityProtocol.text(c,"iss"));
                bounded(c,"jti",128);bounded(c,"tenant_id",128);bounded(c,"app_code",64);bounded(c,"biz_system_code",64);bounded(c,"user_id",128);bounded(c,"sub",64);bounded(c,"origin",2048);
                AuthorityProtocol.hash(AuthorityProtocol.text(c,"policy_digest"));
                if(!c.path("credential_id").isIntegralNumber()||!c.path("credential_id").canConvertToLong()||c.path("credential_id").longValue()<1)throw AuthorityProtocol.denied();
                var issued=AuthorityProtocol.time(c,"iat");var expires=AuthorityProtocol.time(c,"exp");
                if(issued.isAfter(clock.instant())||!expires.isAfter(issued)||(c.has("nbf")&&AuthorityProtocol.time(c,"nbf").isAfter(clock.instant())))throw AuthorityProtocol.denied();
                sourceIntegrationActor(c); // Strict claims and reserved identity bindings before any PG query.
            }
        }else{
            if(!JWSAlgorithm.RS256.equals(jws.getHeader().getAlgorithm())||!jws.verify(new RSASSAVerifier(portalKey.toRSAPublicKey())))throw AuthorityProtocol.denied();
            AuthorityProtocol.same(new JOSEObjectType("at+jwt"),jws.getHeader().getType());
            AuthorityProtocol.same(issuer()+"/portal",AuthorityProtocol.text(c,"iss"));AuthorityProtocol.same("portal-gateway",AuthorityProtocol.text(c,"aud"));
            AuthorityProtocol.same("user",AuthorityProtocol.text(c,"token_use"));AuthorityProtocol.same("portal-web",AuthorityProtocol.text(c,"client_id"));
        }
        bounded(c,"jti",128);
        var issued=AuthorityProtocol.time(c,"iat");var expires=AuthorityProtocol.time(c,"exp");
        if(issued.isAfter(clock.instant())||!expires.isAfter(issued)||(c.has("nbf")&&AuthorityProtocol.time(c,"nbf").isAfter(clock.instant())))throw AuthorityProtocol.denied();
        if(!(integration&&externalPolicy!=null)){AuthorityProtocol.same(explicitPortalActor==null?"tenant-a":explicitPortalActor.tenantId(),AuthorityProtocol.text(c,"tenant_id"));AuthorityProtocol.same(explicitPortalActor==null?"alice":explicitPortalActor.principalId(),AuthorityProtocol.text(c,"sub"));}
        if(!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();return c;
    }
    private static String bounded(JsonNode c,String key,int max){String value=AuthorityProtocol.text(c,key);if(value.length()>max)throw AuthorityProtocol.denied();return value;}
    private static Actor sourceIntegrationActor(JsonNode c){
        var actor=AuthorityProtocol.actor(c);String biz=AuthorityProtocol.text(c,"biz_system_code"),user=AuthorityProtocol.text(c,"user_id");
        AuthorityProtocol.same(IntegrationClaims.principal(biz,user),actor.principalId());
        AuthorityProtocol.same(Set.of(biz),actor.attributes().get("bizSystemCode"));AuthorityProtocol.same(Set.of(user),actor.attributes().get("userId"));
        return actor;
    }
    private Grant originalAuthentication(JsonNode subject){
        String issuer=AuthorityProtocol.text(subject,"iss"),jti=AuthorityProtocol.text(subject,"jti");
        var original=grants.values().stream().filter(g->issuer.equals(g.integration.get("iss"))&&jti.equals(g.integration.get("jti"))).findFirst().orElse(null);
        if(original!=null){
            var first=Json.tree(original.integration);
            // Same identity may only be another representation, never another binding, lifetime
            // or source ceiling. Actor parsing compares role/attribute sets independent of JSON order.
            for(String k:List.of("iss","jti","tenant_id","sub","credential_id","app_code","biz_system_code","user_id","origin","policy_digest","token_use","aud","iat","nbf","exp"))AuthorityProtocol.same(first.get(k),subject.get(k));
            if(original.origin.equals("INTEGRATION")&&externalPolicy!=null)AuthorityProtocol.same(sourceIntegrationActor(first),sourceIntegrationActor(subject));
            else if(first.has("roles")||first.has("attributes")||subject.has("roles")||subject.has("attributes"))
                AuthorityProtocol.same(AuthorityProtocol.actor(first),AuthorityProtocol.actor(subject));
            // Freeze every remaining verified source claim, including portal client/scope. JSON
            // object key order is immaterial; role/attribute sets were compared canonically above.
            var firstRest=first.deepCopy();var nextRest=subject.deepCopy();
            for(String k:List.of("roles","attributes","source_token_sha256")){
                ((com.fasterxml.jackson.databind.node.ObjectNode)firstRest).remove(k);
                ((com.fasterxml.jackson.databind.node.ObjectNode)nextRest).remove(k);
            }
            AuthorityProtocol.same(firstRest,nextRest);
        }
        return original;
    }
    private static Actor initialIntegrationCeiling(JsonNode subject,PolicyResolveResult policy){
        var source=sourceIntegrationActor(subject);var upper=policyActor(policy);
        AuthorityProtocol.same(AuthorityProtocol.subject(source),AuthorityProtocol.subject(upper));
        var roles=new TreeSet<>(source.roles());roles.retainAll(upper.roles());roles.removeIf(IntegrationClaims::platformRole);
        var attributes=new TreeMap<String,Set<String>>();
        // AuthorityProtocol.attrs admits region as the permission attribute; reserved ownership
        // is exact and dataLabels are obligations. Missing/empty permissions stay empty.
        for(var entry:source.attributes().entrySet()){
            String name=entry.getKey();if(Set.of("dataLabels","bizSystemCode","userId").contains(name))continue;
            var values=new TreeSet<>(entry.getValue());values.retainAll(upper.attributes().getOrDefault(name,Set.of()));attributes.put(name,values);
        }
        for(String name:List.of("bizSystemCode","userId")){AuthorityProtocol.same(source.attributes().get(name),upper.attributes().get(name));attributes.put(name,source.attributes().get(name));}
        var labels=new TreeSet<>(source.attributes().getOrDefault("dataLabels",Set.of()));labels.addAll(upper.attributes().getOrDefault("dataLabels",Set.of()));attributes.put("dataLabels",labels);
        var result=new Actor(source.tenantId(),source.principalId(),roles,attributes);
        AuthorityProtocol.within(source,result);AuthorityProtocol.within(upper,result);return result;
    }
    JsonNode service(String token)throws Exception {
        var jwt=JWSObject.parse(token);if(!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())))throw AuthorityProtocol.denied();
        var c=AuthorityResponseVerifier.object(jwt.getPayload().toBytes());if(!AuthorityProtocol.time(c,"exp").isAfter(clock.instant()))throw AuthorityProtocol.expired();return c;
    }
    void current(Grant grant)throws Exception {
        if(revoked||!scopeAllowed||grant==null||!grant.expires.isAfter(clock.instant())||!grant.version.equals(version))throw AuthorityProtocol.denied();
        if(!"portal.session.read".equals(grant.originalCall.action())&&!(externalPolicy!=null&&grant.origin.equals("INTEGRATION"))&&!currentRoles.containsAll(grant.ceiling.roles()))throw AuthorityProtocol.denied();
        if(grant.origin.equals("INTEGRATION"))policy(grant);
    }
    void activeOperation(Operation op){if(!op.expires.isAfter(clock.instant()))throw AuthorityProtocol.expired();}
    Operation operation(Grant g,String source,Instant expiry){
        if(operations.size()>=64)throw AuthorityProtocol.denied();var op=new Operation("op-"+(++serial),g,source,clock.instant(),expiry);operations.put(op.id,op);return op;
    }
    Map<String,Object> operationClaims(Operation op){
        var g=op.grant;return Map.of("operation_id",op.id,"source_kind",op.source,"origin_kind",g.origin,"issued_at",op.issued.getEpochSecond(),"expires_at",op.expires.getEpochSecond(),
            "grant_ref",g.id,"tenant_id",g.ceiling.tenantId(),"sub",g.ceiling.principalId(),"resource",AuthorityProtocol.resource(g.resource),"ceiling_sha256",AuthorityProtocol.ceiling(g.ceiling));
    }
    void identity(Map<String,Object> c,Operation op){
        var g=op.grant;c.put("tenant_id",g.ceiling.tenantId());c.put("sub",g.ceiling.principalId());c.put("roles",g.ceiling.roles());c.put("attributes",g.ceiling.attributes());
        c.put("grant_ref",g.id);c.put("grant_expires_at",g.expires.getEpochSecond());c.put("authorization_version",g.version);c.put("authority_mode","delegated");c.put("operation",operationClaims(op));
        c.put("initial_ceiling",Map.of("tenant_id",g.ceiling.tenantId(),"sub",g.ceiling.principalId(),"roles",g.ceiling.roles(),"attributes",g.ceiling.attributes()));
        c.put("origin_authentication_id",g.originAuthenticationId);
    }
    String ticket(Operation op,String caller,ServiceCall call){
        var c=base("service","urn:agent-platform:service:"+call.target(),60);identity(c,op);c.putAll(AuthorityProtocol.call(call));c.put("scope",call.action());c.put("caller",caller);c.put("client_id",caller);
        c.put("exp",earlier(clock.instant().plusSeconds(60),op.expires).getEpochSecond());changeTicket.accept(c);return sign(c,key,"at+jwt");
    }
    String workload(String caller,ServiceCall call){
        var c=base("service","urn:agent-platform:service:"+call.target(),60);c.putAll(AuthorityProtocol.call(call));c.put("scope",call.action());c.put("caller",caller);c.put("client_id",caller);c.put("sub",caller);c.put("authority_mode","workload");return sign(c,key,"at+jwt");
    }
    void decision(Map<String,Object> c,Operation op,ServiceCall call,String caller){
        identity(c,op);c.put("subject_caller",caller);var expiry=earlier(clock.instant().plusSeconds(5),op.expires);c.put("exp",expiry.getEpochSecond());
        if("portal.session.read".equals(call.action()))c.put("roles",currentRoles);
        decisions.put(c.get("jti").toString(),new Decision(c.get("jti").toString(),op,call,caller,expiry,Objects.toString(c.get("ticket_jti"),"approval-"+c.get("jti"))));
    }
    void lease(Map<String,Object> c,Continuation ref,Decision opening){
        var d=ref.opening;c.put("binding",ref.binding);c.put("source_kind",d.operation.source);c.put("source_expires_at",d.operation.expires.getEpochSecond());c.put("opening_decision_id",d.id);
        c.put("operation_id",d.operation.id);c.put("continuation_ref",ref.id);
        c.put("ticket_jti",d.ticketJti);
        c.put("exp",earlier(earlier(clock.instant().plusSeconds(5),ref.binding.hardDeadline()),d.operation.expires).getEpochSecond());
        if(opening!=null&&d.operation.source.equals("INTEGRATION")){
            var p=base("response-completion","urn:agent-platform:service:"+d.call.target(),55);identity(p,d.operation);
            p.put("exp",ref.binding.hardDeadline().getEpochSecond());p.put("binding",ref.binding);p.put("source_kind","INTEGRATION");
            p.put("opening_decision_id",d.id);p.put("operation_id",d.operation.id);p.put("caller",d.caller);p.put("producer",d.call.target());p.put("consumer",d.call.target());p.put("action",d.call.action());
            p.put("parent_response_id","parent-"+ref.binding.responseId());p.put("ceiling_sha256",AuthorityProtocol.ceiling(d.operation.grant.ceiling));
            p.put("material_kind","sse".equals(ref.binding.kind())?"event":"model-result");p.put("material_id",d.call.resource().id());p.put("material_version",d.call.resource().versionDigest());p.put("initial_cursor",0);
            changePermit.accept(p);lastPermit=sign(p,key,"agent-response-material+jwt");c.put("completion_permit",lastPermit);
        }
    }
    static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static void requireObservationQuery(ServiceCall call,String caller,String source,String origin,JsonNode verifiedSource){
        RunObservationSelectors.requirePurpose(call,caller,"delegated",source,origin);
        var tuple=Json.read(call.resource().id());
        if(!tuple.isArray()||tuple.size()!=5||!tuple.get(3).isTextual())throw AuthorityProtocol.denied();
        AuthorityProtocol.same(call.resource(),RunObservationSelectors.metadata(new SubjectKey(
            AuthorityProtocol.text(verifiedSource,"tenant_id"),AuthorityProtocol.text(verifiedSource,"sub")),tuple.get(3).textValue()));
    }
    private void requireObservationStatus(ServiceCall call,String caller,String source,String origin,JsonNode verifiedSource){
        RunObservationSelectors.requirePurpose(call,caller,"delegated",source,origin);
        var known=observations.get(call.resource().id());
        if(known==null||deniedObservations.contains(call.resource().id()))throw AuthorityProtocol.denied();
        AuthorityProtocol.same(known.owner(),new SubjectKey(AuthorityProtocol.text(verifiedSource,"tenant_id"),AuthorityProtocol.text(verifiedSource,"sub")));
        AuthorityProtocol.same(known.resource(),call.resource());
    }
    private void requireObservationEvents(ServiceCall call, String caller, String source,
            String origin, JsonNode verifiedSource) {
        // This registry is trusted fixture setup; no browser field creates a run observation.
        AuthorityProtocol.same("engine", call.target());
        AuthorityProtocol.same("gateway", caller);
        AuthorityProtocol.same(EVENTS_ACTION, call.action());
        AuthorityProtocol.same("PORTAL", source);
        AuthorityProtocol.same("PORTAL", origin);
        var known = observations.get(call.resource().id());
        if (known == null || deniedObservations.contains(call.resource().id())) {
            throw AuthorityProtocol.denied();
        }
        AuthorityProtocol.same(known.owner(), new SubjectKey(AuthorityProtocol.text(verifiedSource, "tenant_id"),
                AuthorityProtocol.text(verifiedSource, "sub")));
        AuthorityProtocol.same(known.resource(), call.resource());
    }
    private static void requireLocalGrant(String caller,ServiceCall call,Grant grant){
        if(!ServiceRoutePolicy.localRunAdvance(caller,call,"delegated","DELEGATED",grant.origin)
            || !"engine".equals(grant.originalCall.target())
            || !"proxy.engine.post.RunController.run".equals(grant.originalCall.action())
            || !Objects.equals(grant.originalCall.resource(),call.resource()))throw AuthorityProtocol.denied();
    }
    static void route(String caller,ServiceCall call,String mode){
        if(new ServiceRoutePolicy().routes().stream().noneMatch(r->Objects.equals(caller,r.caller())&&Objects.equals(call.target(),r.target())&&Objects.equals(call.action(),r.action())&&mode.equals(r.mode())))throw AuthorityProtocol.denied();
    }
    private static void requireSessionResource(ServiceCall call,JsonNode verifiedSource){
        AuthorityProtocol.same("user",AuthorityProtocol.text(verifiedSource,"token_use"));
        if(!PortalClaimMapper.scopes(verifiedSource.path("scope").textValue()).contains("platform.access"))throw AuthorityProtocol.denied();
        var resource=new ResourceBinding("portal-session",
            AuthorityProtocol.digest(List.of("portal-session.subject.v1",AuthorityProtocol.text(verifiedSource,"iss"),AuthorityProtocol.text(verifiedSource,"tenant_id"),AuthorityProtocol.text(verifiedSource,"sub"))),
            AuthorityProtocol.digest(List.of("portal-session.authentication.v1",AuthorityProtocol.text(verifiedSource,"iss"),AuthorityProtocol.text(verifiedSource,"client_id"),AuthorityProtocol.text(verifiedSource,"jti"),AuthorityProtocol.time(verifiedSource,"iat").getEpochSecond(),AuthorityProtocol.time(verifiedSource,"exp").getEpochSecond())));
        AuthorityProtocol.same(resource,call.resource());
    }
    void policy(Grant g)throws Exception {
        if(externalPolicy!=null){externalPolicy(g.integration,g.policyVersion,g.ceiling);return;}
        policyCalls.incrementAndGet();String nonce=UUID.randomUUID().toString();
        var request=new PolicyResolveRequest(1,7,"tenant-a","app-a","biz-a","alice","alice",g.policyVersion,nonce);
        String body=Json.write(request);var assertion=base("policy-query","app-policy-resolver",30);
        assertion.put("iss",policyIssuer()+"/policy-issuer");assertion.put("sub","iam-policy-resolver");assertion.put("caller","iam-policy-resolver");assertion.put("action","integration.policy.resolve");
        assertion.put("bodySha256",Digest.sha256(body));assertion.put("nonce",nonce);
        var r=HttpRequest.newBuilder(URI.create(policyIssuer()+"/internal/iam/integration-policy/resolve")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+sign(assertion,wrongPolicyKey?key:policyKey,"agent-policy-request+jwt"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=policyHttp.send(r,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200||response.body().length()>65536)throw AuthorityProtocol.denied();
        var result=AuthorityResponseVerifier.object(response.body().getBytes(StandardCharsets.UTF_8));
        for(String k:List.of("schemaVersion","credentialId","tenantId","appCode","bizSystemCode","userId","principalId","active","subjectAllowed","roleCeiling","attributeCeiling","dataLabels","allowedOrigins","policyVersion","policyDigest","nonce"))if(!result.has(k))throw AuthorityProtocol.denied();
        if(!result.path("schemaVersion").isIntegralNumber()||!result.path("credentialId").isIntegralNumber()
                ||!result.path("active").isBoolean()||!result.path("subjectAllowed").isBoolean()
                ||result.path("schemaVersion").intValue()!=1||result.path("credentialId").longValue()!=7
                ||!result.path("active").booleanValue()||!result.path("subjectAllowed").booleanValue())throw AuthorityProtocol.denied();
        AuthorityProtocol.same(nonce,AuthorityProtocol.text(result,"nonce"));AuthorityProtocol.same(g.policyVersion,AuthorityProtocol.text(result,"policyVersion"));
        AuthorityProtocol.same("tenant-a",AuthorityProtocol.text(result,"tenantId"));AuthorityProtocol.same("alice",AuthorityProtocol.text(result,"principalId"));
        AuthorityProtocol.same("app-a",AuthorityProtocol.text(result,"appCode"));AuthorityProtocol.same("biz-a",AuthorityProtocol.text(result,"bizSystemCode"));AuthorityProtocol.same("alice",AuthorityProtocol.text(result,"userId"));
        AuthorityProtocol.same(g.integration.get("policy_digest"),AuthorityProtocol.text(result,"policyDigest"));
        if(!AuthorityProtocol.strings(result.get("allowedOrigins")).contains(g.integration.get("origin")))throw AuthorityProtocol.denied();
        if(!AuthorityProtocol.strings(result.get("roleCeiling")).containsAll(g.ceiling.roles())||!AuthorityProtocol.attrs(result.get("attributeCeiling")).getOrDefault("region",Set.of()).containsAll(g.ceiling.attributes().get("region")))throw AuthorityProtocol.denied();
        if(!g.ceiling.attributes().getOrDefault("dataLabels",Set.of()).containsAll(AuthorityProtocol.strings(result.get("dataLabels"))))throw AuthorityProtocol.denied();
    }
    static Actor policyActor(PolicyResolveResult p){var attrs=new HashMap<>(p.attributeCeiling());attrs.put("dataLabels",p.dataLabels());return new Actor(p.tenantId(),p.principalId(),p.roleCeiling(),attrs);}
    PolicyResolveResult externalPolicy(Map<String,Object> details,String expectedVersion,Actor ceiling)throws Exception {
        policyCalls.incrementAndGet();String nonce=UUID.randomUUID().toString();var d=Json.tree(details);
        var request=new PolicyResolveRequest(1,d.path("credential_id").longValue(),AuthorityProtocol.text(d,"tenant_id"),AuthorityProtocol.text(d,"app_code"),AuthorityProtocol.text(d,"biz_system_code"),AuthorityProtocol.text(d,"user_id"),AuthorityProtocol.text(d,"sub"),expectedVersion,nonce);
        String body=Json.write(request);var assertion=base("policy-query","app-policy-resolver",30);
        long now=Instant.now().getEpochSecond();assertion.put("iat",now);assertion.put("nbf",now);assertion.put("exp",now+30);
        assertion.put("iss",policyIssuer()+"/policy-issuer");assertion.put("sub","iam-policy-resolver");assertion.put("caller","iam-policy-resolver");assertion.put("target","app-policy-resolver");assertion.put("action","integration.policy.resolve");assertion.put("nonce",nonce);assertion.put("bodySha256",Digest.sha256(body));
        var response=policyHttp.send(HttpRequest.newBuilder(externalPolicy).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json").header("Authorization","Bearer "+sign(assertion,policyKey,"agent-policy-request+jwt")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200||response.body().length()>65536)throw AuthorityProtocol.denied();
        var n=AuthorityResponseVerifier.object(response.body().getBytes(StandardCharsets.UTF_8));
        var fields=Set.of("schemaVersion","credentialId","tenantId","appCode","bizSystemCode","userId","principalId","active","subjectAllowed","roleCeiling","attributeCeiling","dataLabels","allowedOrigins","policyVersion","policyDigest","nonce");
        if(n.size()!=fields.size())throw AuthorityProtocol.denied();for(String k:fields)if(!n.has(k))throw AuthorityProtocol.denied();
        if(!n.path("schemaVersion").isIntegralNumber()||n.path("schemaVersion").intValue()!=1||!n.path("credentialId").isIntegralNumber()||n.path("credentialId").longValue()!=request.credentialId()||!n.path("active").isBoolean()||!n.path("active").booleanValue()||!n.path("subjectAllowed").isBoolean()||!n.path("subjectAllowed").booleanValue())throw AuthorityProtocol.denied();
        AuthorityProtocol.same(request.expectedTenantId(),AuthorityProtocol.text(n,"tenantId"));AuthorityProtocol.same(request.expectedAppCode(),AuthorityProtocol.text(n,"appCode"));AuthorityProtocol.same(request.expectedBizSystemCode(),AuthorityProtocol.text(n,"bizSystemCode"));AuthorityProtocol.same(request.userId(),AuthorityProtocol.text(n,"userId"));AuthorityProtocol.same(request.expectedPrincipalId(),AuthorityProtocol.text(n,"principalId"));AuthorityProtocol.same(nonce,AuthorityProtocol.text(n,"nonce"));
        String pv=AuthorityProtocol.text(n,"policyVersion");if(expectedVersion!=null)AuthorityProtocol.same(expectedVersion,pv);
        AuthorityProtocol.same(AuthorityProtocol.text(d,"policy_digest"),AuthorityProtocol.text(n,"policyDigest"));
        var origins=AuthorityProtocol.strings(n.get("allowedOrigins"));if(!origins.contains(AuthorityProtocol.text(d,"origin")))throw AuthorityProtocol.denied();
        var p=new PolicyResolveResult(1,request.credentialId(),request.expectedTenantId(),request.expectedAppCode(),request.expectedBizSystemCode(),request.userId(),request.expectedPrincipalId(),true,true,AuthorityProtocol.strings(n.get("roleCeiling")),AuthorityProtocol.attrs(n.get("attributeCeiling")),AuthorityProtocol.strings(n.get("dataLabels")),origins,pv,AuthorityProtocol.text(n,"policyDigest"),nonce);
        if(ceiling!=null)AuthorityProtocol.within(policyActor(p),ceiling);
        return p;
    }
    void resolve(HttpExchange x){
        try{
            if(policyUnavailable){send(x,503,"resolver-unavailable");return;}
            String body=new String(x.getRequestBody().readNBytes(65537),StandardCharsets.UTF_8);
            String auth=x.getRequestHeaders().getFirst("Authorization");
            var assertion=AuthorityResponseVerifier.verifyPinned(auth.substring(7),"agent-policy-request+jwt","policy-query","app-policy-resolver",30,policyKey.toPublicJWK(),policyIssuer()+"/policy-issuer",clock).claims();
            AuthorityProtocol.same("iam-policy-resolver",AuthorityProtocol.text(assertion,"caller"));AuthorityProtocol.same("iam-policy-resolver",AuthorityProtocol.text(assertion,"sub"));AuthorityProtocol.same("integration.policy.resolve",AuthorityProtocol.text(assertion,"action"));
            AuthorityProtocol.same(Digest.sha256(body),AuthorityProtocol.text(assertion,"bodySha256"));
            var r=Json.read(body,PolicyResolveRequest.class);AuthorityProtocol.same(r.nonce(),AuthorityProtocol.text(assertion,"nonce"));
            if(r.schemaVersion()!=1||r.credentialId()!=7||!r.expectedTenantId().equals("tenant-a")||!r.expectedAppCode().equals("app-a")||!r.expectedBizSystemCode().equals("biz-a")||!r.expectedPrincipalId().equals("alice")||!r.userId().equals("alice"))throw AuthorityProtocol.denied();
            var result=new PolicyResolveResult(1,7,"tenant-a","app-a","biz-a","alice","alice",policyActive,policySubjectAllowed,Set.of("EDITOR"),Map.of("region",Set.of("A")),Set.of("confidential","restricted"),Set.of("https://embed.example.invalid"),policyVersion,Digest.sha256("policy-v1"),wrongPolicyBinding?"wrong":r.nonce());
            var n=(com.fasterxml.jackson.databind.node.ObjectNode)Json.tree(result);if(omitPolicyField)n.remove("policyDigest");if(wrongPolicyType)n.put("active","true");send(x,200,n.toString());
        }catch(Exception e){send(x,403,"policy-assertion-rejected");}
    }
    static void send(HttpExchange x,int code,String text){
        try{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(code,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}}catch(Exception ignored){}finally{x.close();}
    }
    @Override public void close(){server.stop(0);resolver.stop(0);policyHttp.close();}
}
