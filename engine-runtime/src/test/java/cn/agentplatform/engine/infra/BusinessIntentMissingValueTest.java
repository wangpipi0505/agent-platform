package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.UUID;

/**
 * H1-6 半卡②定向用例③（台账验收：意图三条用例之"缺值首发"）：技能契约声明了
 * businessIntentInput、但冻结 run.inputs 缺该字段值的首发请求 → BUSINESS_INTENT_REQUIRED
 * 参数错误（步骤 FAILED），**不进入待对账**（无 action_plan 业务键落库）。
 * 用例①（并发受理一次）与②（同意图改参数冲突）由既有 BusinessIdempotencyAcrossCallsTest
 * 覆盖（concurrentCallsGrantOnce…/sameIntentDifferentPayloadConflicts…），此处不重复。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class BusinessIntentMissingValueTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());

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
        cn.agentplatform.engine.infra.TracePostgresTest.connect();
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.action_plan where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run_authority");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run where tenant_id='engine-test'");
    }

    /** 夹具：run（inputs 可控是否含意图字段）+ 节点认领 + 声明了意图字段的 tool_call 登记行。 */
    long call(boolean withIntentValue) {
        TracePostgresTest fixture = new TracePostgresTest();
        fixture.clean();
        long r = fixture.run();
        var claim = store.claim(fixture.node(r, "READY"), "worker");
        long attempt = TraceStore.number(claim, "attemptId");
        var inputs = withIntentValue ? Map.of("x", 1, "intentField", "order-9001") : Map.of("x", 1);
        db.update("update engine.run set inputs=?::jsonb where id=?", Json.write(inputs), r);
        String contract = Digest.sha256("frozen-contract");
        String argsDigest = Digest.sha256(Json.canonical(Map.of("x", 1)));
        return db.queryForObject(
                "insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,"
                    + "args_digest,args_ref,idempotency_key,status,skill_code,args,attempt_id,"
                    + "skill_contract_sha256)"
                    + " values(?,(select id from engine.node_instance where run_id=? limit 1),1,42,"
                    + "?, 'pg:fixture', ?, 'REGISTERED', 'archive', ?::jsonb, ?, ?) returning id",
                Long.class,
                r,
                r,
                argsDigest,
                Digest.sha256(r + ":n:1:42"),
                Json.write(Map.of("x", 1)),
                attempt,
                contract);
    }

    @Test
    void missingIntentValueFirstRequestFailsAsParamWithoutReconciliationEntry() {
        long callId = call(false);
        var ex =
                assertThrows(
                        PlatformException.class,
                        () -> BusinessIdempotency.prepare(store, callId, runOf(callId), "intentField"));
        assertTrue(
                String.valueOf(ex.getMessage()).contains("缺少显式业务意图输入"),
                "缺值首发必须是参数错误: " + ex.getMessage());
        // 不进待对账：未落任何业务键（action_plan 业务作用域为零行）
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.action_plan where run_id in(select id from engine.run where tenant_id='engine-test') and business_key is not null",
                        Integer.class));
    }

    @Test
    void declaredIntentValuePreparesBinding() {
        long callId = call(true);
        var binding = BusinessIdempotency.prepare(store, callId, runOf(callId), "intentField");
        assertNotNull(binding);
        assertNotNull(binding.key());
        assertEquals("order-9001", binding.intent());
    }

    private long runOf(long callId) {
        return db.queryForObject("select run_id from engine.tool_call where id=?", Long.class, callId);
    }
}
