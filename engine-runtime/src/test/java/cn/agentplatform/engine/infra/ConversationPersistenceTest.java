package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.time.*;
import java.util.*;

/** 仅写本测试独有租户与行；RunService 作为已持久会话受理边界的协议对端，不冒充整次运行验收。 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ConversationPersistenceTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    TraceStore store;
    String tenant, session;
    final String code = "chat";
    Actor actor;
    final List<Long> createdRuns = new ArrayList<>();

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource(EngineTestDatabase.url(),
                EngineTestDatabase.user(), EngineTestDatabase.password());
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void ownedRows() {
        tenant = "conversation-" + UUID.randomUUID();
        session = "s_" + UUID.randomUUID().toString().replace("-", "");
        actor = new Actor(tenant, "alice", Set.of("EDITOR"), Map.of());
        store = new TraceStore(db, manager);
        db.update("insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,next_turn) values(?,?,?,?,?::jsonb,3)",
                session, tenant, "alice", code,
                Json.write(Map.of("type", "CHAT", "versionNo", 7, "config", Map.of("context", Map.of("maxTurns", 2)))));
    }

    @AfterEach void cleanOwnedRows() {
        db.update("delete from engine.chat_turn where session_id=?", session);
        db.update("delete from engine.chat_session where public_id=? and tenant_id=?", session, tenant);
        for (long id : createdRuns) {
            db.update("delete from engine.model_invocation where run_id=?", id);
            db.update("delete from engine.node_instance where run_id=?", id);
            db.update("delete from engine.run where id=?", id);
        }
        createdRuns.clear();
    }

    long run(String runTenant, String principal, String text) {
        Long id = db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,output,mode) values(?,?,7,'CHAT','SUCCEEDED',?,?,?,'{}',?::jsonb,'{}','{}','{}',?::jsonb,'FULL') returning id",
                Long.class, "r_" + UUID.randomUUID(), code, principal, runTenant, "pg:test/conversation",
                Json.write(Map.of("roles", actor.roles(), "attributes", actor.attributes())), Json.write(Map.of("text", text)));
        createdRuns.add(id); return id;
    }

    String publicId(long run) { return db.queryForObject("select public_id from engine.run where id=?", String.class, run); }

    void turn(int number, String request, long run) {
        db.update("insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id) values(?,?,?,?::jsonb,?)",
                session, number, request, Json.write(Map.of("message", "记住42")), publicId(run));
    }

    Map<String, Object> nextRun() {
        return Map.of("tenant_id", tenant, "principal_id", "alice", "app_code", code,
                "snapshot", Json.tree(Map.of("chatSession", Map.of("sessionId", session, "turnNo", 2))),
                "guard_ctx", Json.tree(Map.of("roles", actor.roles(), "attributes", actor.attributes())));
    }

    @Test void restartUsesOwnedPersistedHistoryAndRejectsForeignRunPointersWithoutInventingSingleRunHistory() {
        long original = run(tenant, "alice", "已记住42"); turn(1, "first", original);
        var restarted = new TraceStore(db, manager);
        var sessions = new ChatSessions(restarted, null, null);
        assertEquals(7, Json.tree(sessions.get(code, session, actor).get("versionNo")).asInt());
        assertEquals(1, Json.tree(sessions.get(code, session, actor).get("turns")).size());
        assertThrows(PlatformException.class, () -> sessions.get(code, session, new Actor(tenant, "bob", Set.of("ADMIN"), Map.of())));
        assertThrows(PlatformException.class, () -> sessions.get(code, session, new Actor(tenant + "-foreign", "alice", Set.of("ADMIN"), Map.of())));
        var history = ChatHistory.load(restarted, nextRun());
        assertEquals("记住42", history.get(0).get("content")); assertEquals("已记住42", history.get(1).get("content"));
        assertTrue(ChatHistory.load(restarted, Map.of("snapshot", Json.object())).isEmpty());
        long foreign = run(tenant + "-foreign", "bob", "不得泄漏的回答");
        db.update("update engine.chat_turn set run_public_id=? where session_id=? and turn_no=1", publicId(foreign), session);
        var error = assertThrows(PlatformException.class, () -> ChatHistory.load(restarted, nextRun()));
        assertEquals(403, error.status()); assertEquals("CHAT_HISTORY_SCOPE_CHANGED", error.code());
    }

    @Test void frozenTurnLimitRejectsBeforeReservationAndAcceptedRequestStillReplays() {
        long original = run(tenant, "alice", "已完成"); turn(1, "accepted", original); turn(2, "second", original);
        var clients = new RuntimeClients() {
            @Override public JsonNode get(String service, String path, Actor actor) { return Json.read("{\"type\":\"CHAT\",\"versionNo\":99}"); }
        };
        RunService runs = mock(RunService.class);
        when(runs.createReservedSessionTurn(eq(code), any(), any(), eq(actor), isNull()))
                .thenReturn(Map.of("runId", publicId(original), "status", "PENDING"));
        when(runs.status(publicId(original), actor)).thenReturn(Map.of("runId", publicId(original), "status", "SUCCEEDED"));
        var sessions = new ChatSessions(store, clients, runs);
        var error = assertThrows(PlatformException.class, () -> sessions.turn(code, session,
                Json.read("{\"requestId\":\"third\",\"inputs\":{\"message\":\"新一轮\"}}"), actor));
        assertEquals(429, error.status()); assertEquals("CHAT_TURN_LIMIT", error.code());
        assertEquals(2, db.queryForObject("select count(*) from engine.chat_turn where session_id=?", Integer.class, session));
        assertEquals(3, db.queryForObject("select next_turn from engine.chat_session where public_id=?", Integer.class, session));
        verify(runs, never()).reserveSessionTurn();
        assertEquals(publicId(original), sessions.turn(code, session,
                Json.read("{\"requestId\":\"accepted\",\"inputs\":{\"message\":\"记住42\"}}"), actor).get("runId"));
        assertEquals(2, ((Number) sessions.get(code, session, actor).get("maxTurns")).intValue());
    }

    void usage(long run, boolean known) {
        Long node = db.queryForObject("insert into engine.node_instance(run_id,step_seq,node_type,status,definition) values(?,1,'AGENT','SUCCEEDED','{}') returning id", Long.class, run);
        var response = new LinkedHashMap<String, Object>();
        response.put("actualModel", Map.of("modelName", "model-a", "providerId", 7));
        if (known) response.put("usage", Map.of("totalTokens", 6));
        db.update("insert into engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,response,status,usage_accounted,updated_at) values(?,?,0,'MAIN','{}',?::jsonb,'SUCCEEDED',?,now())",
                run, node, Json.write(response), known);
    }

    @Test void usageGroupsKeepMissingTokensUnknownAndExposeOnlyTheApplicableQuotaWindow() {
        usage(run(tenant, "alice", "known"), true); usage(run(tenant, "alice", "missing"), false);
        usage(run(tenant + "-foreign", "alice", "other"), true);
        var quota = new RunQuota(db, Clock.systemUTC());
        quota.quotaEnabled(true); quota.windowSeconds(3600); quota.appTokens(5);
        var group = quota.aggregate(actor, "app", 3600).getFirst();
        assertEquals(6, ((Number) group.get("tokens")).longValue());
        assertEquals(2, ((Number) group.get("invocations")).longValue());
        assertEquals(1, ((Number) group.get("usageMissingInvocations")).longValue());
        assertEquals(5, ((Number) group.get("limitTokens")).longValue());
        assertEquals("EXCEEDED", group.get("limitStatus"));
        assertEquals("WINDOW_MISMATCH", quota.aggregate(actor, "app", 60).getFirst().get("limitStatus"));
        for (String by : List.of("user", "model")) {
            var other = quota.aggregate(actor, by, 3600).getFirst();
            assertEquals(6, ((Number) other.get("tokens")).longValue());
            assertEquals("NOT_APPLICABLE", other.get("limitStatus"));
        }
    }
}
