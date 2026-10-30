package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 报告/数据集/checkpoint真PG；只把既有引擎HTTP边界作为协议peer，不冒充外部模型指代验收。 */
@EnabledIfEnvironmentVariable(named="AGENT_DB_PASSWORD",matches=".+")
class ConversationEvaluationPersistenceTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    String tenant,code;
    Dependency peer;

    @BeforeAll static void database() {
        var source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(source).schemas("monitor").defaultSchema("monitor").locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
    }
    @BeforeEach void fixture() {
        tenant="conversation-eval-"+UUID.randomUUID(); code="chat_"+UUID.randomUUID().toString().replace("-","");
        identity(tenant,"evaluator"); peer=new Dependency(candidate("CHAT",2));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void identity(String tenant,String principal) {
        var jwt=Jwt.withTokenValue("verified-test-actor").header("alg","HS256").subject(principal).claim("tenant_id",tenant)
                .claim("roles",List.of("EDITOR")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(180)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    private EvaluationService service() {
        return new EvaluationService(db,manager,"peer-app","peer-engine") {
            @Override public Map<String,Object> call(String method,String url,Map<String,Object> body) { return peer.call(method,url,body); }
        };
    }
    private Map<String,Object> candidate(String type,int turns) {
        var candidate=new LinkedHashMap<String,Object>();
        candidate.put("appCode",code); candidate.put("type",type); candidate.put("name","冻结多轮"); candidate.put("description","");
        candidate.put("domain",""); candidate.put("bizCategory",""); candidate.put("tags",List.of());
        candidate.put("config",Map.of("context",Map.of("maxTurns",turns,"historyTurns",2,"historyTokens",2000))); candidate.put("definition",Map.of());
        candidate.put("candidateSha256",Digest.sha256(Json.canonical(candidate))); candidate.put("draftRev",1); return candidate;
    }
    private Map<String,Object> request(List<Map<String,Object>> cases) {
        return Map.of("appCode",code,"draftRev",1,"candidateSha256",peer.candidate.get("candidateSha256"),"datasetVersion","v"+UUID.randomUUID(),"cases",cases);
    }
    private Map<String,Object> conversation(int turns) {
        List<Map<String,Object>> values=new ArrayList<>();
        for(int i=1;i<=turns;i++) values.add(Map.of("inputs",Map.of("message",i==1?"记住42":"它是多少"),"expected",i==1?"已记住42":"42"));
        return Map.of("id","reference","mode","CONVERSATION","safety",true,"turns",values);
    }

    @Test void completedTurnsAreCheckpointedBeforeFollowingTurnAndRestartUsesSameCandidateAndVerifiedOwner() {
        peer.pendingSecond=true;
        var report=assertDoesNotThrow(()->service().evaluate(request(List.of(conversation(2)))));
        assertEquals("RUNNING",report.get("status"));
        String id=report.get("id").toString(); var first=Json.tree(report).path("results").get(0).path("turns").get(0).deepCopy();
        assertEquals("SUCCEEDED",first.path("status").asText()); assertEquals(1,peer.calls.getOrDefault(1,0));
        assertTrue(peer.firstPersistedBeforeSecond,"下一轮外调前，前轮真实结果已在PG中");
        assertEquals("UNKNOWN",Json.tree(report).path("publicationEvidence").path("environment").asText());
        identity(tenant,"another-current-user");
        assertEquals(403,assertThrows(PlatformException.class,()->service().resume(id)).status());
        identity(tenant+"-other","evaluator");
        assertEquals(404,assertThrows(PlatformException.class,()->service().resume(id)).status());
        identity(tenant,"evaluator");
        db.update("update monitor.evaluation_report set execution_token=?::uuid,execution_until=now()+interval '1 minute' where tenant_id=? and id=?",UUID.randomUUID().toString(),tenant,id);
        assertEquals("EVALUATION_EXECUTION_ACTIVE",assertThrows(PlatformException.class,()->service().resume(id)).code());
        db.update("update monitor.evaluation_report set execution_until=now()-interval '1 second' where tenant_id=? and id=?",tenant,id);
        peer.pendingSecond=false; peer.candidate=candidate("CHAT",1); // 恢复不读取这个后续改稿。
        var recovered=assertDoesNotThrow(()->service().resume(id));
        assertEquals("ACCEPTED",recovered.get("status")); assertEquals(1,peer.candidateReads);
        assertEquals(1,peer.calls.getOrDefault(1,0)); assertEquals(2,peer.calls.getOrDefault(2,0));
        assertEquals(first,Json.tree(recovered).path("results").get(0).path("turns").get(0));
        assertEquals("DEMO",Json.tree(recovered).path("publicationEvidence").path("environment").asText(),"首轮DEMO不能被末轮REAL覆盖");
        int sent=peer.calls.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals("ACCEPTED",service().resume(id).get("status")); assertEquals(sent,peer.calls.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void frozenTurnLimitsAndExplicitShapeRejectBeforeDispatchWhileLegacySingleCaseKeepsItsEndpoint() {
        var tooMany=request(List.of(conversation(3)));
        assertEquals("EVALUATION_TURNS_INVALID",assertThrows(PlatformException.class,()->service().evaluate(tooMany)).code());
        assertTrue(peer.calls.isEmpty());
        assertEquals(0,db.queryForObject("select count(*) from monitor.evaluation_report where tenant_id=?",Integer.class,tenant));
        var ambiguous=new LinkedHashMap<>(conversation(2)); ambiguous.put("inputs",Map.of()); ambiguous.put("expected","forged");
        assertEquals("EVALUATION_CONVERSATION_INVALID",assertThrows(PlatformException.class,()->service().evaluate(request(List.of(ambiguous)))).code());
        var injected=new LinkedHashMap<>(conversation(2)); injected.put("conversationId","someone-elses-session");
        assertEquals("EVALUATION_CONVERSATION_INVALID",assertThrows(PlatformException.class,()->service().evaluate(request(List.of(injected)))).code());
        peer.candidate=candidate("FLOW",2);
        assertEquals("EVALUATION_CHAT_REQUIRED",assertThrows(PlatformException.class,()->service().evaluate(request(List.of(conversation(2))))).code());
        peer.candidate=candidate("CHAT",2);
        var legacy=service().evaluate(request(List.of(Map.of("id","legacy","inputs",Map.of("message","单轮"),"expected","ok","safety",true))));
        assertEquals("ACCEPTED",legacy.get("status")); assertEquals(1,peer.singleCalls); assertTrue(peer.calls.isEmpty());
    }

    private final class Dependency {
        Map<String,Object> candidate;
        final Map<Integer,Integer> calls=new HashMap<>();
        int candidateReads,singleCalls;
        boolean pendingSecond,firstPersistedBeforeSecond=true;
        Dependency(Map<String,Object> candidate){this.candidate=candidate;}
        Map<String,Object> call(String method,String url,Map<String,Object> body) {
            if("GET".equals(method)){candidateReads++;return Json.read(Json.write(candidate),Map.class);}
            if(url.endsWith("/internal/evaluations/run")){singleCalls++; return Map.of("runId","single-"+body.get("caseId"),"status","SUCCEEDED","output","ok");}
            assertTrue(url.endsWith("/internal/evaluations/turn"),url);
            int turn=((Number)body.get("turnNo")).intValue(); calls.merge(turn,1,Integer::sum);
            String report=body.get("evalReportId").toString();
            var persisted=Json.read(db.queryForObject("select results::text from monitor.evaluation_report where tenant_id=? and id=?",String.class,tenant,report));
            assertEquals(1,persisted.get(0).path("conversationProtocol").asInt(),"首次外调前冻结协议，不能恢复时追认");
            if(turn==2) firstPersistedBeforeSecond &= "SUCCEEDED".equals(persisted.get(0).path("turns").get(0).path("status").asText());
            var frozen=Json.tree(body.get("frozenCandidate"));
            String stored=db.queryForObject("select candidate_sha256 from monitor.evaluation_report where tenant_id=? and id=?",String.class,tenant,report);
            assertEquals(stored,frozen.path("candidateSha256").asText());
            var result=new LinkedHashMap<String,Object>();
            result.put("runId","run-"+report+"-"+turn); result.put("status",turn==2 && pendingSecond?"RUNNING":"SUCCEEDED");
            result.put("output",turn==1?"已记住42":"42"); result.put("environment",turn==1?"DEMO":"REAL"); result.put("demoMode",turn==1);
            for(String key:List.of("appCode","draftRev","candidateSha256","datasetVersion","evalReportId","caseId")) result.put(key,body.get(key));
            result.put("conversationId","session-"+report); result.put("turnNo",turn);
            // 协议peer提供本次冻结的producer来源；业务与权限断言沿原值保留。
            var source=new LinkedHashMap<String,Object>(); source.put("schemaVersion",2); source.put("scope","PLATFORM_PARTICIPANT_MODES");
            source.put("runId",result.get("runId")); source.put("tenantId",tenant); source.put("principalId","evaluator");
            source.put("environment",result.get("environment")); source.put("engineEnvironment",result.get("environment"));
            source.put("participants",List.of()); source.put("externalTruth","NOT_ASSESSED"); source.put("executionScope","ENGINE_LOCAL_ONLY");
            source.put("evidenceSha256",Digest.sha256(Json.canonical(source))); result.put("sourceEvidence",source); return result;
        }
    }
}
