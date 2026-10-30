package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ENGINE_PG_TEST", matches="true")
class ParticipantEnvironmentPostgresTest {
    @Test void ownPgParticipantsDominateEngineModeAndMissingOrUnconfirmedFactsStayUnknown() {
        var ds = new DriverManagerDataSource(EngineTestDatabase.url(), EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
        var db = new JdbcTemplate(ds); var trace = new TraceStore(db, new DataSourceTransactionManager(ds));
        var runs = new RunService(trace, null, null, null);
        var actor = new Actor("ex17b-" + UUID.randomUUID(), "alice", Set.of("EDITOR"), Map.of());
        String publicId = "r_" + UUID.randomUUID();
        long run = db.queryForObject("insert into engine.run(public_id,app_code,eval_candidate,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                + " values(?,'ex17b','{}','EVALUATION','SUCCEEDED','alice',?,'pg:test','{}','{}','{}',?::jsonb,'{}','FULL') returning id", Long.class,
                publicId, actor.tenantId(), Json.write(Map.of("runtime", Map.of("sourceProtocol", 2, "environment", "REAL", "demoMode", false))));
        db.update("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition,output) values(?,'local',0,'END','SUCCEEDED','{\"type\":\"END\"}','{}')", run);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("REAL");
        long node = db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition,output) values(?,'llm',1,'LLM','SUCCEEDED','{\"type\":\"LLM\"}','{}') returning id", Long.class, run);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
        var source = new LinkedHashMap<String,Object>(Map.of("schemaVersion", 1, "producer", "model", "producerEnvironment", "DEMO", "executionPath", "MODEL_HTTP",
                "tenantId", actor.tenantId(), "principalId", "alice", "runId", publicId, "invocationRef", "test:main", "factId", 17));
        long invocation = db.queryForObject("insert into engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,final_request,response,status) values(?,?,1,'MAIN','{}',?::jsonb,?::jsonb,'SUCCEEDED') returning id",
                Long.class, run, node, Json.write(Map.of("invocationRef", "test:main")), Json.write(Map.of("sourceEvidence", source, "text", "{\"environment\":\"REAL\"}")));
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("DEMO");
        source.put("producerEnvironment", "REAL");
        db.update("update engine.model_invocation set response=?::jsonb where id=?", Json.write(Map.of("sourceEvidence", source)), invocation);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("REAL");
        db.update("update engine.model_invocation set is_mock=true where id=?", invocation);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("DEMO");
        db.update("update engine.model_invocation set is_mock=false where id=?", invocation);
        db.update("update engine.model_invocation set response='{\"text\":\"REAL\",\"structured\":{\"sourceEvidence\":{\"producerEnvironment\":\"REAL\"}}}' where id=?", invocation);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
        db.update("update engine.model_invocation set response=?::jsonb,status='DISPATCHED' where id=?", Json.write(Map.of("sourceEvidence", source)), invocation);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
        source.put("producerEnvironment", "DEMO");
        db.update("update engine.model_invocation set response=?::jsonb where id=?", Json.write(Map.of("sourceEvidence", source)), invocation);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("DEMO");
        source.put("producerEnvironment", "REAL");
        db.update("update engine.model_invocation set response=?::jsonb,status='SUCCEEDED' where id=?", Json.write(Map.of("sourceEvidence", source)), invocation);
        long toolNode = db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition,output) values(?,'tool',2,'SKILL','SUCCEEDED','{\"type\":\"SKILL\"}','{}') returning id", Long.class, run);
        long attempt = db.queryForObject("insert into engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot) values(?,1,'SUCCEEDED',?,'pg:test','{}') returning id", Long.class, toolNode, Digest.sha256(publicId));
        long call = db.queryForObject("insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,status,is_mock,skill_code,args,result,attempt_id) values(?,?,1,9,?,?,'SUCCEEDED',false,'tool','{}','{}',?) returning id", Long.class, run, toolNode, Digest.sha256("args"), Digest.sha256("call" + publicId), attempt);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
        var skillSource = new LinkedHashMap<String,Object>(Map.of("schemaVersion", 1, "producer", "skill", "producerEnvironment", "REAL", "executionPath", "HTTP",
                "tenantId", actor.tenantId(), "principalId", "alice", "runId", publicId, "callId", call, "attemptId", attempt, "skillVersionId", 9));
        db.update("update engine.tool_call set result=?::jsonb where id=?", Json.write(Map.of("sourceEvidence", skillSource)), call);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("REAL");
        db.update("update engine.tool_call set is_mock=true where id=?", call);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("DEMO");
        db.update("update engine.run set snapshot=jsonb_set(snapshot,'{runtime,sourceProtocol}','1') where id=?", run);
        assertThat(Json.tree(runs.status(publicId, actor).get("sourceEvidence")).path("environment").asText()).isEqualTo("UNKNOWN");
    }
}
