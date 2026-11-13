package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunRecoveryCoordinator;
import cn.agentplatform.engine.harness.loop.RecoveryFact;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * H1-8（台账 §7.2）：恢复事实出口——同一运行重复取得同一恢复事实（验收口径）；角色投影
 * （USER 仅来源/原因/摘要）；契约桩 contracts/h1-6-h1-8-stubs.md §3。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RecoveryFactExitTest {

    static JdbcTemplate db;
    static TraceStore store;

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.model_invocation where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
    }

    long run(String status, boolean withSession) {
        var snapshot =
                withSession
                        ? Json.tree(Map.of("chatSession", Map.of("sessionId", "h8_" + UUID.randomUUID())))
                        : Json.tree(Map.of());
        long id =
                db.queryForObject(
                        "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'h8-app','{}','DEBUG',?,'alice','engine-test','pg:test','{}','{}','{}',?::jsonb,'{}','STEP')"
                            + " returning id",
                        Long.class,
                        "r_h8_" + UUID.randomUUID(),
                        status,
                        Json.write(snapshot));
        // 会话行（占水位）
        if (withSession) {
            String sid = Json.tree(snapshot).path("chatSession").path("sessionId").asText();
            db.update(
                    "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                        + " values(?,'engine-test','alice','h8-app','{}')",
                    sid);
            db.update(
                    "insert into engine.session_event(session_id,seq,type,data) values"
                        + "((select public_id from engine.chat_session where public_id=?),1,'user/message','{}'::jsonb)",
                    sid);
        }
        return id;
    }

    @Test
    void externalAdoptionYieldsStableAutoFactWithCheckpointAndReusedSteps() {
        long r = run("RUNNING", false);
        long node =
                db.queryForObject(
                        "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                            + " values(?,'n1',1,'CODE','RUNNING','{\"type\":\"CODE\"}') returning id",
                        Long.class,
                        r);
        var claim = store.claim(node, "worker");
        // 提交一个节点（复用步骤事实）
        store.commit(r, node, TraceStore.number(claim, "attemptId"), claim.get("lease_owner").toString(), Map.of("v", 1));
        // 外部事实采纳事件（H1-6 链路写）
        store.event(r, "EXTERNAL_FACT_ADOPTED", Map.of("callId", 1));

        var coordinator = new RunRecoveryCoordinator(store, null);
        var fact = coordinator.recoveryFact(r, true);
        assertEquals("AUTO", fact.recoverySource());
        assertEquals("EXTERNAL_RESULT_UNKNOWN", fact.reasonCode());
        assertEquals(publicOf(r), fact.checkpoint().runRefs().get(0));
        assertEquals(1, fact.reusedSteps().size());
        assertNotNull(fact.reusedSteps().get(0).resultDigest());

        // 同一事实可重复取得（重启后一致）
        var again = coordinator.recoveryFact(r, true);
        assertEquals(fact.recoverySource(), again.recoverySource());
        assertEquals(fact.reasonCode(), again.reasonCode());
        assertEquals(fact.checkpoint(), again.checkpoint());
        assertEquals(fact.reusedSteps(), again.reusedSteps());

        // USER 投影：仅来源/原因/摘要
        var userView = fact.projectFor(false);
        assertEquals("AUTO", userView.recoverySource());
        assertEquals("EXTERNAL_RESULT_UNKNOWN", userView.reasonCode());
    }

    @Test
    void manualResumeYieldsManualFact() {
        long r = run("WAITING_STEP", false);
        db.update(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE','WAITING_STEP','{\"type\":\"CODE\"}')",
                r);
        store.event(r, "RESUMED", Map.of("requestId", "m1"));
        var coordinator = new RunRecoveryCoordinator(store, null);
        var fact = coordinator.recoveryFact(r, true);
        assertEquals("MANUAL", fact.recoverySource());
        assertEquals("MANUAL_REQUEST", fact.reasonCode());
        assertTrue(fact.blockedReasons().contains("WAITING_STEP"), "阻塞原因含 WAITING 节点状态");
    }

    private String publicOf(long r) {
        return db.queryForObject("select public_id from engine.run where id=?", String.class, r);
    }
}
