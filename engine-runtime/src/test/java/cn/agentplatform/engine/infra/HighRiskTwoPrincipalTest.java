package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.ApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.*;
import java.util.*;

/** 真实 engine_test 独有行；请求身份为过滤链夹具，签名 HTTP 对端仅验证本地核权协议，不称真实 IAM 联验。 */
@EnabledIfEnvironmentVariable(named="ENGINE_PG_TEST",matches="true")
class HighRiskTwoPrincipalTest {
    static JdbcTemplate db;
    static TraceStore store;
    String tenant;
    Actor owner, bob, charlie;
    final List<Long> ownedRuns=new ArrayList<>();
    record Pending(long run,long node,long call,String publicId,ToolCalls calls) {}

    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(EngineTestDatabase.url(),EngineTestDatabase.user(),EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(ds);store=new TraceStore(db,new DataSourceTransactionManager(ds));
    }
    @BeforeEach void identity() {
        tenant="high-risk-"+UUID.randomUUID();
        owner=new Actor(tenant,"owner",Set.of("EDITOR"),Map.of());
        bob=new Actor(tenant,"bob",Set.of("APPROVER","AUDITOR"),Map.of());
        charlie=new Actor(tenant,"charlie",Set.of("APPROVER"),Map.of());
    }
    @AfterEach void cleanOwned() {
        SecurityContextHolder.clearContext();
        for(long run:ownedRuns) {
            db.update("delete from engine.action_plan where run_id=?",run);
            db.update("delete from engine.run_event where run_id=?",run);
            db.update("delete from engine.attempt_note where node_instance_id in(select id from engine.node_instance where run_id=?)",run);
            db.update("delete from engine.tool_call where run_id=?",run);
            db.update("delete from engine.attempt where node_instance_id in(select id from engine.node_instance where run_id=?)",run);
            db.update("delete from engine.node_instance where run_id=?",run);
            db.update("delete from engine.run where id=?",run);
        }
    }
    static void requestIdentity(Actor actor) {
        var jwt=Jwt.withTokenValue("test-verified-approval").header("alg","HS256").subject(actor.principalId())
                .claim("tenant_id",actor.tenantId()).claim("roles",actor.roles()).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    Pending pending() {
        String publicId="r_high_"+UUID.randomUUID();
        long run=db.queryForObject("insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode) values(?,'high','{}','DEBUG','RUNNING',?,?,'pg:test/high','{}','{}','{}','{}','{}','STEP') returning id",
                Long.class,publicId,owner.principalId(),tenant);
        ownedRuns.add(run);
        db.update("update engine.run set snapshot=?::jsonb where id=?",Json.write(Map.of("config",Map.of("tools",List.of(Map.of(
                "code","danger","skillVersionId",42,"contractDigest",Digest.sha256("high-contract"),
                "execution",Map.of("confirmRoles",List.of("APPROVER","AUDITOR"),"sideEffect",true,
                        "approval",Map.of("mode","PREPARE_EXECUTE","risk","HIGH"))))))),run);
        long node=db.queryForObject("insert into engine.node_instance(run_id,step_seq,node_type,status,definition) values(?,1,'CODE','READY','{\"type\":\"CODE\",\"expression\":\"1\"}') returning id",Long.class,run);
        var lease=store.claim(node,"first-worker");
        var calls=new ToolCalls(store);
        long call=TraceStore.number(calls.register(register(publicId,run,node,lease),owner),"callId");
        calls.requestApproval(call,Json.tree(Map.of("roles",List.of("APPROVER","AUDITOR"),
                "parametersDigest",Digest.sha256(Json.canonical(Map.of("target","x"))),
                "actionPlan",Map.of("summary","高危变更","target","x","diff",Map.of("enabled",true),"risk","HIGH"))),owner);
        db.update("update engine.run set status='WAITING_APPROVAL' where id=?",run);
        return new Pending(run,node,call,publicId,calls);
    }
    JsonNode register(String id,long run,long node,Map<String,Object> lease) {
        return Json.tree(Map.of("runId",id,"nodeInstanceId",node,"attemptId",lease.get("attemptId"),"callSeq",1,
                "skillVersionId",42,"skillCode","danger","arguments",Map.of("target","x"),
                "idempotencyKey",Digest.sha256(run+":"+node+":1:42"),"leaseOwner",lease.get("lease_owner")));
    }
    Map<String,Object> plan(Pending p) {return db.queryForMap("select * from engine.action_plan where call_id=?",p.call());}
    ObjectNode decision(Pending p,boolean approved,String request) {
        var context=Json.read(db.queryForObject("select approval_context::text from engine.node_instance where id=?",String.class,p.node()));
        return (ObjectNode)Json.tree(Map.of("approved",approved,"requestId",request,"snapshotDigest",Digest.sha256(Json.canonical(context)),"doubleConfirmed",true));
    }
    Map<String,Object> vote(Pending p,Actor actor,boolean approved,String request) {
        requestIdentity(actor);
        return new ApprovalService(store).approve(p.publicId(),p.node(),decision(p,approved,request),actor);
    }

    @Test void twoRolesAndClientDoubleCheckStillNeedTwoDifferentVerifiedPrincipals() {
        var p=pending();
        assertEquals("WAITING_APPROVAL",vote(p,bob,true,"first").get("status"));
        assertEquals("WAITING_APPROVAL",db.queryForObject("select status from engine.node_instance where id=?",String.class,p.node()));
        assertEquals("PREPARED",plan(p).get("status"));
        assertEquals("WAITING_APPROVAL",vote(p,bob,true,"same-person").get("status"));
        assertEquals(1,TraceStore.json(plan(p).get("approvals")).size());
        assertEquals("RUNNING",vote(p,charlie,true,"second-person").get("status"));
        var stored=plan(p);
        assertEquals("APPROVED",stored.get("status"));assertEquals(false,stored.get("double_confirmed"));
        assertEquals(2,TraceStore.json(stored.get("approvals")).size());
        assertEquals("TWO_PRINCIPALS_V1",TraceStore.json(stored.get("plan")).path("approvalPolicy").asText());
        assertEquals(stored.get("digest"),Digest.sha256(Json.canonical(TraceStore.json(stored.get("plan")))));
    }

    @Test void forgedIdentityVetoExpiryAndLegacyPlanCannotAuthorizeExecutionOrRewriteFrozenKeys() {
        var p=pending();
        assertEquals("WAITING_APPROVAL",vote(p,bob,true,"first").get("status"));
        requestIdentity(bob);
        var forged=decision(p,true,"spoof");forged.put("secondPrincipalId","charlie");
        var spoof=assertThrows(PlatformException.class,()->new ApprovalService(store).approve(p.publicId(),p.node(),forged,bob));
        assertEquals(400,spoof.status());
        assertThrows(PlatformException.class,()->new ApprovalService(store).approve(p.publicId(),p.node(),decision(p,true,"actor-spoof"),charlie));
        vote(p,charlie,false,"veto");
        assertEquals("REJECTED",plan(p).get("status"));assertNull(plan(p).get("execute_granted_at"));
        var expired=pending();
        var context=(ObjectNode)Json.read(db.queryForObject("select approval_context::text from engine.node_instance where id=?",String.class,expired.node()));
        context.put("expiresAt",Instant.now().minusSeconds(1).toString());
        db.update("update engine.node_instance set approval_context=?::jsonb where id=?",Json.write(context),expired.node());
        assertEquals("APPROVAL_EXPIRED",assertThrows(PlatformException.class,()->vote(expired,bob,true,"expired")).code());
        assertNull(plan(expired).get("execute_granted_at"));
        var legacy=pending();
        var stored=plan(legacy);var body=(ObjectNode)TraceStore.json(stored.get("plan")).deepCopy();
        String expires=body.path("expiresAt").asText();body.remove(List.of("approvalPolicy","idempotencyKey","expiresAt"));
        String key=Digest.sha256("EXECUTE:"+legacy.call()+":"+Digest.sha256(Json.canonical(body)));
        body.put("idempotencyKey",key);body.put("expiresAt",expires);String digest=Digest.sha256(Json.canonical(body));
        db.update("update engine.action_plan set plan=?::jsonb,digest=?,execute_key=?,approval_policy='LEGACY_DECLARATION',approvals='[]' where id=?",Json.write(body),digest,key,stored.get("id"));
        var legacyContext=(ObjectNode)Json.read(db.queryForObject("select approval_context::text from engine.node_instance where id=?",String.class,legacy.node()));
        legacyContext.set("actionPlan",body);legacyContext.put("actionPlanDigest",digest);
        db.update("update engine.node_instance set approval_context=?::jsonb where id=?",Json.write(legacyContext),legacy.node());
        db.update("update engine.tool_call set approval_context=?::jsonb where id=?",Json.write(legacyContext),legacy.call());
        assertEquals("ACTION_PLAN_REPREPARE_REQUIRED",assertThrows(PlatformException.class,()->vote(legacy,bob,true,"legacy")).code());
        assertEquals(key,plan(legacy).get("execute_key"));assertEquals(digest,plan(legacy).get("digest"));
    }

    @Test void executeRequiresOriginalOnlineAuthorityAndSignedPeerRevocationIsNotReplacedByRoleSnapshots() throws Exception {
        var p=pending();
        assertEquals("WAITING_APPROVAL",vote(p,bob,true,"first").get("status"));
        vote(p,charlie,true,"second");
        var lease=store.claim(p.node(),"execute-worker");
        p.calls().register(register(p.publicId(),p.run(),p.node(),lease),owner);
        var request=Json.tree(Map.of("owner",lease.get("lease_owner"),"attemptId",lease.get("attemptId"),"executeKey",plan(p).get("execute_key")));
        var missing=assertThrows(PlatformException.class,()->p.calls().claim(p.call(),request,owner));
        assertEquals(503,missing.status());assertEquals("APPROVAL_CURRENT_AUTHORITY_REQUIRED",missing.code());
        assertEquals("REGISTERED",db.queryForObject("select status from engine.tool_call where id=?",String.class,p.call()));
        assertNull(plan(p).get("execute_granted_at"));
        try(var peer=new LoopbackAuthority();var gateway=peer.client("gateway");var engine=peer.client("engine")) {
            var route=new ServiceRoutePolicy().routes().stream().filter(r->"gateway".equals(r.caller())&&"engine".equals(r.target())&&"delegated".equals(r.mode())).findFirst().orElseThrow();
            var call=new ServiceCall("engine",route.action(),new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));
            var original=engine.authorize(gateway.exchange(peer.portal(),call).value(),call);
            var subject=new SubjectKey("tenant-a","approver");
            assertEquals(subject.principalId(),engine.approver(original.delegation(),subject,call.resource()).actor().principalId());
            peer.revoked(true);
            assertThrows(PlatformException.class,()->engine.approver(original.delegation(),subject,call.resource()));
        }
    }
}
