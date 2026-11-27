package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.api.ChatSessionController;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 真engine_test PG与真实受理/上下文链；模型为确定性协议peer，不称真实外部指代已验。 */
@EnabledIfEnvironmentVariable(named="ENGINE_PG_TEST",matches="true")
class EvaluationConversationSessionTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    @BeforeAll static void database() {
        var source=new DriverManagerDataSource(EngineTestDatabase.url(),EngineTestDatabase.user(),EngineTestDatabase.password());
        Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
        db=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void identity(String tenant,String principal,boolean service) {
        var jwt=Jwt.withTokenValue("verified-internal-peer").header("alg","HS256").subject(principal).claim("tenant_id",tenant)
                .claim("roles",service?List.of("SERVICE","EDITOR"):List.of("ADMIN"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(180)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    private JsonNode call(MockMvc mvc,Map<String,Object> body,int expected) throws Exception {
        var response=mvc.perform(post("/internal/evaluations/turn").contentType("application/json").content(Json.write(body))).andReturn().getResponse();
        assertEquals(expected,response.getStatus(),response.getContentAsString()); return Json.read(response.getContentAsString());
    }
    private MockMvc mvc(ChatSessions sessions){return MockMvcBuilders.standaloneSetup(new ChatSessionController(sessions)).setControllerAdvice(new PlatformErrors()).build();}

    @Test void persistedCandidateTurnsKeepRealHistoryOwnershipOrderAndEvaluationAdmissionAcrossRestart() throws Exception {
        String tenant="eval-session-"+UUID.randomUUID(), code="chat", report="er_"+UUID.randomUUID();
        identity(tenant,"evaluator",true); Actor actor=Actor.current();
        var base=new LinkedHashMap<String,Object>();
        base.put("appCode",code); base.put("type","CHAT"); base.put("name","冻结候选"); base.put("description",""); base.put("domain",""); base.put("bizCategory",""); base.put("tags",List.of());
        base.put("config",Map.of("modelEntryId",1,"context",Map.of("maxTurns",2,"historyTurns",2,"historyTokens",2000))); base.put("definition",Map.of());
        base.put("candidateSha256",Digest.sha256(Json.canonical(base))); base.put("draftRev",1);
        var entry=Map.of("modelEntryId",1);
        base.put("dependencyVersions",Map.of("modelRefs",List.of(Map.of("modelEntryId",1,"entry",entry,"modelBindingRef",Map.of("bindingId",1,"sha256",Digest.sha256(Json.canonical(entry)))))));
        var observed=new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var clients=new RuntimeClients(){
            @Override public JsonNode get(String service,String path,Actor actor){assertFalse(path.contains("/published"),"评测会话不得偷换成published");return Json.tree(base);}
            @Override public JsonNode post(String service,String path,Object body,Actor actor){return Json.read("{\"inputTokens\":100,\"contextWindow\":10000,\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");}
            @Override public JsonNode chatStream(Object body,Actor actor,java.util.function.Consumer<String> chunk){observed.add(Json.tree(body));return Json.tree(Map.of("text",observed.size()==1?"已记住42":"42","toolCalls",List.of(),"usage",Map.of("totalTokens",10)));}
        };
        var store=new TraceStore(db,manager); var driver=new AgentDriver(store,clients,new ContextAssembler());
        // 只领取此测试租户的实际PG运行，不启动全局poll或触及专库内其他测试遗留作业。
        var repository=new RunScheduleRepository(store){
            @Override public cn.agentplatform.engine.domain.RunDispatch claimNext(cn.agentplatform.engine.domain.RunDispatch.Queue queue,String owner,java.time.Duration lease){
                for(Long id:db.queryForList("select id from engine.run where tenant_id=? and status in ('PENDING','RUNNING') order by id",Long.class,tenant)){
                    var claimed=claimSpecific(id,queue,owner,lease); if(claimed!=null)return claimed;
                }
                return null;
            }
        };
        var scheduler=new RunScheduler(repository,1,1,200,90000,5000); var runs=new RunService(store,clients,new FlowDriver(store,driver),driver,scheduler);
        try {
            var endpoint=mvc(new ChatSessions(store,clients,runs));
            var request=new LinkedHashMap<String,Object>();
            request.put("appCode",code); request.put("draftRev",1); request.put("candidateSha256",base.get("candidateSha256")); request.put("datasetVersion","two-turns"); request.put("evalReportId",report); request.put("caseId","reference"); request.put("frozenCandidate",base);
            request.put("turnNo",1); request.put("inputs",Map.of("message","记住42"));
            var first=call(endpoint,request,200).path("data"); String session=first.path("conversationId").asText(), firstRun=first.path("runId").asText();
            assertFalse(session.isBlank()); await(runs,firstRun,actor);
            var publicTurn=endpoint.perform(post("/admin/apps/"+code+"/sessions/"+session+"/turns").contentType("application/json").content("{\"requestId\":\"must-not-be-task\",\"inputs\":{\"message\":\"forbidden\"}}")).andReturn().getResponse();
            assertEquals(403,publicTurn.getStatus(),"评测会话不能从公共会话入口降为TASK/FULL");
            assertEquals("EVALUATION",db.queryForObject("select trigger_type from engine.run where public_id=?",String.class,firstRun));
            assertTrue(db.queryForObject("select length(request_id) from engine.run where public_id=?",Integer.class,firstRun)<=48);
            var replay=call(endpoint,request,200).path("data"); assertEquals(firstRun,replay.path("runId").asText()); assertEquals(1,observed.size());
            // 新ChatSessions/TraceStore实例仅从PG恢复原候选与历史，不读取当前published。
            endpoint=mvc(new ChatSessions(new TraceStore(db,manager),clients,runs));
            request.put("conversationId",session); request.put("turnNo",2); request.put("inputs",Map.of("message","它是多少"));
            var second=call(endpoint,request,200).path("data"); await(runs,second.path("runId").asText(),actor);
            assertEquals(2,observed.size()); assertTrue(observed.get(1).path("messages").toString().contains("记住42")); assertTrue(observed.get(1).path("messages").toString().contains("已记住42"));
            assertEquals("EVALUATION",observed.get(1).path("callKind").asText());
            assertEquals(2,db.queryForObject("select count(*) from engine.chat_turn where session_id=?",Integer.class,session));
            var altered=new LinkedHashMap<>(request); altered.put("inputs",Map.of("message","换输入")); assertEquals("REQUEST_CONFLICT",call(endpoint,altered,409).path("code").asText());
            identity(tenant,"different-current-user",true); call(endpoint,request,404);
            identity(tenant,"evaluator",false); call(endpoint,request,403);
            identity(tenant,"evaluator",true);
            request.put("turnNo",3); call(endpoint,request,429);
            RunService iam = spy(runs);
            doReturn(true).when(iam).iamEnabled();
            call(mvc(new ChatSessions(store,clients,iam)),request,403);
            assertEquals(2,db.queryForObject("select count(*) from engine.chat_turn where session_id=?",Integer.class,session));
        } finally {scheduler.close(); clients.close();}
    }
    private void await(RunService runs,String run,Actor actor) throws Exception {
        for(int i=0;i<300;i++){if("SUCCEEDED".equals(runs.status(run,actor).get("status")))return;Thread.sleep(10);}
        assertEquals("SUCCEEDED",runs.status(run,actor).get("status"));
    }
}
