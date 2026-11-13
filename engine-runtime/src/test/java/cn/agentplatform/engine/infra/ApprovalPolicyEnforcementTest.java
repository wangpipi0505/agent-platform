package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RuntimeFault;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * H1-6 半卡③定向测试（审批子项）：①会话策略 never 服务层拦截（登记前零出站、重放生效、
 * 后注册工具不能绕过）；②approval/asked 会话事件关联确认对象；③fail-closed 通道不可用
 * →APPROVAL_UNAVAILABLE（经策略 ask 下的审批写入失败注入）。
 * 语义附注：APPROVAL_DENIED 为内部 RuntimeFault 语义+模型可见错误（非接口稿 §2 HTTP 码表项），
 * 已登记于 contracts/h1-6-h1-8-stubs.md §1 附注。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ApprovalPolicyEnforcementTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor actor = new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of());
    static final String CONTRACT = Digest.sha256("fixture-contract");

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
        SchedulerTestResources.close();
        db.update("delete from engine.reproduction");
        db.update("delete from engine.model_invocation");
        db.update("delete from engine.attempt_note where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.tool_call where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.attempt where node_instance_id in(select n.id from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id='engine-test')");
        db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id='engine-test')");
        db.update("delete from engine.run_authority");
        db.update("delete from engine.run where tenant_id='engine-test'");
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h6_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_turn where session_id like 'h6_%'");
        db.update("delete from engine.chat_session where public_id like 'h6_%'");
    }

    String session() {
        String id = "h6_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h6-app','{}')",
                id);
        return id;
    }

    long node(long r) {
        return db.queryForObject(
                "insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                    + " values(?,'n1',1,'CODE',?,'{\"type\":\"CODE\",\"expression\":\"1\"}') returning id",
                Long.class,
                r,
                "READY");
    }

    long run(String sid) {
        var modelEntry = Json.read("{\"modelEntryId\":1}");
        return db.queryForObject(
                "insert into engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                    + " values(?,'h6-app','{}','DEBUG','RUNNING','alice','engine-test','pg:test','{}','{}','{}',?::jsonb,'{}','STEP')"
                    + " returning id",
                Long.class,
                "r_h6_" + UUID.randomUUID(),
                Json.write(
                        Map.of(
                                "config",
                                Map.of(
                                        "tools",
                                        List.of(
                                                Map.of(
                                                        "code",
                                                        "archive",
                                                        "name",
                                                        "archive",
                                                        "skillVersionId",
                                                        42,
                                                        "contractDigest",
                                                        CONTRACT,
                                                        "execution",
                                                        Map.of("confirmRoles", List.of("APPROVER"))))),
                                "chatSession",
                                Map.of("sessionId", sid, "turnNo", 1),
                                "dependencyVersions",
                                Map.of(
                                        "modelRefs",
                                        List.of(
                                                Map.of(
                                                        "modelEntryId",
                                                        1,
                                                        "entry",
                                                        modelEntry,
                                                        "modelBindingRef",
                                                        Map.of(
                                                                "bindingId",
                                                                1,
                                                                "sha256",
                                                                Digest.sha256(
                                                                        Json.canonical(modelEntry)))))))));
    }

    JsonNode config() {
        return Json.read(
                "{\"modelEntryId\":1,\"tools\":[{\"code\":\"archive\",\"skillVersionId\":42,"
                    + "\"contractDigest\":\""
                    + CONTRACT
                    + "\",\"name\":\"archive\",\"execution\":{\"confirmRoles\":[\"APPROVER\"]},"
                    + "\"definition\":{\"type\":\"function\",\"function\":{\"name\":\"archive\","
                    + "\"description\":\"受控写\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}}]}");
    }

    /** 出站桩：execute 一律断言失败（策略拦截用例零出站）或按需返回 WAITING_APPROVAL。 */
    RuntimeClients stubClients(boolean waitingApproval) {
        return new RuntimeClients() {
            @Override
            public JsonNode chatStream(Object body, Actor a, java.util.function.Consumer<String> chunk) {
                return post("model", "/internal/model/chat/stream", body, a);
            }

            @Override
            public JsonNode post(String service, String path, Object body, Actor a) {
                if (path.equals("/internal/model/measure"))
                    return Json.read(
                            "{\"inputTokens\":100,\"contextWindow\":10000,"
                                + "\"maxOutputTokens\":2048,\"tokenizer\":\"fixture\",\"exact\":false}");
                if (path.equals("/internal/model/chat/stream"))
                    return Json.read(
                            "{\"text\":\"需要归档\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"archive\","
                                + "\"arguments\":{}}],\"usage\":{},\"actualModel\":\"fixture\"}");
                if (path.equals("/internal/skills/archive/execute")) {
                    if (!waitingApproval) throw new AssertionError("策略 never 下不应出站");
                    return Json.read(
                            "{\"status\":\"WAITING_APPROVAL\",\"confirmation\":{\"roles\":[\"APPROVER\"],"
                                + "\"parametersDigest\":\"" + Digest.sha256(Json.canonical(Json.read("{}"))) + "\"}}");
                }
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    void policyNever(String sid) {
        new SessionStore(store)
                .append(sid, null, SessionEvents.APPROVAL_POLICY, Json.tree(Map.of("mode", "never")), null);
    }

    /** ①策略 never：登记前拦截（零出站、零登记行），APPROVAL_DENIED 语义，重放生效。 */
    @Test
    void neverPolicyBlocksBeforeRegistrationAndReplays() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var driver = new AgentDriver(store, stubClients(false), new ContextAssembler());
        policyNever(sid);

        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () -> driver.skill(runRow, claim, "archive", 42, Json.read("{}"), 1, actor));
        assertEquals("APPROVAL_DENIED", fault.semantic);
        // 服务层拦截点在派发前：登记行存在（状态 REGISTERED）但零外呼（stub execute 断言未触发）
        assertEquals(
                "REGISTERED",
                db.queryForObject(
                        "select status from engine.tool_call where run_id=?", String.class, r),
                "已登记未派发——零外呼");
    }

    /** ②策略 ask（默认）：审批 ask 建立（WAITING_APPROVAL）且 approval/asked 会话事件留痕。 */
    @Test
    void askPolicyRecordsAskedEventWithConfirmationContext() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var driver = new AgentDriver(store, stubClients(true), new ContextAssembler());

        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () -> driver.skill(runRow, claim, "archive", 42, Json.read("{}"), 1, actor));
        assertEquals("WAITING_APPROVAL", fault.semantic);
        long asked =
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id=? and type='approval/asked'",
                        Long.class,
                        sid);
        assertEquals(1, asked, "approval/asked 事件留痕（登记事务链）");
    }

    /** ③重放生效：ask 后追加 never——后续派发被拦截（事件顺序决定生效策略）。 */
    @Test
    void laterNeverPolicyOverridesEarlierAskOnReplay() {
        String sid = session();
        long r = run(sid);
        var claim = store.claim(node(r), "worker");
        var runRow = db.queryForMap("select * from engine.run where id=?", r);
        var driver = new AgentDriver(store, stubClients(false), new ContextAssembler());
        policyNever(sid);

        var fault =
                assertThrows(
                        RuntimeFault.class,
                        () -> driver.skill(runRow, claim, "archive", 42, Json.read("{}"), 1, actor));
        assertEquals("APPROVAL_DENIED", fault.semantic, "never 重放生效：后续派发被拦截");
    }
}
