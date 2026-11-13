package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.harness.plan.PlanMode;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.harness.plan.PlanValidator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * T2-4 定向测试（台账 §7.3）：Plan 模式——plan:policy 注入/exit_plan_mode 渐进披露/
 * 结构化优先校验器（DAG/能力引用/权限）/会话模式重放。
 * 验收（主方案 §5.6/A01）：同会话并发 Plan+Agent 互不串扰（任务级 mode 固定于 task.mode，
 * 会话交互模式独立——双轨解耦断言）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class PlanModeTest {

    static JdbcTemplate db;
    static SessionStore sessions;
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
        sessions = new SessionStore(store);
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
        db.execute("ALTER TABLE engine.session_event DISABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.session_event where session_id like 'h24_%'");
        db.execute("ALTER TABLE engine.session_event ENABLE TRIGGER session_event_append_only_guard");
        db.update("delete from engine.chat_session where public_id like 'h24_%'");
    }

    String session() {
        String id = "h24_" + UUID.randomUUID();
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
                    + " values(?,'engine-test','alice','h24-app','{}')",
                id);
        return id;
    }

    @Test
    void planModeEventsReplayLastWinsAndLogOnly() {
        String sid = session();
        PlanMode.setActive(sessions, sid, true);
        PlanMode.setActive(sessions, sid, false);
        PlanMode.setActive(sessions, sid, true);
        assertTrue(PlanMode.isActive(sessions, sid), "最后一条 plan/mode 生效（重放正确）");
        // log-only：不入模型转录——消息投影不含 plan/mode
        var projection = sessions.deriveMessages(sid, null);
        assertEquals(0, projection.messages().size(), "plan/mode 不入模型转录");
    }

    @Test
    void planPolicySectionInjectsFromManifestOrDefault() {
        String builtin = PlanMode.policySection(Json.object());
        assertTrue(builtin.contains("exit_plan_mode"), "缺省文案引导调用 exit_plan_mode");
        var manifest = Json.tree(Map.of("planPolicy", "部署方自定义规划纪律文案"));
        assertEquals("部署方自定义规划纪律文案", PlanMode.policySection(manifest));
    }

    @Test
    void exitPlanModeDisclosureOnlyWhenActive() {
        var declared = List.of(Json.read("{\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}"));
        // 非规划模式：声明不变
        assertEquals(1, PlanMode.discloseForPlanStep(declared, false).size());
        // 规划模式：附加 exit_plan_mode（只加不减）
        var disclosed = PlanMode.discloseForPlanStep(declared, true);
        assertEquals(2, disclosed.size());
        assertEquals(
                "exit_plan_mode",
                disclosed.get(1).path("function").path("name").asText());
        // 非规划模式调用守卫
        assertThrows(
                PlatformException.class,
                () -> PlanMode.requirePlanActive(false, "h24_x"));
        PlanMode.requirePlanActive(true, "h24_x"); // 不抛
    }

    @Test
    void structuredValidatorChecksDagCapabilityAndShapes() {
        var ok =
                PlanValidator.validate(
                        Json.read(
                                "{\"steps\":[{\"stepId\":\"a\",\"kind\":\"SKILL\",\"inputBindings\":{\"dataset\":\"input\"},\"targetRef\":\"skill:lookup@1#"
                                    + "a".repeat(64)
                                    + "\",\"dependsOn\":[]},{\"stepId\":\"b\",\"kind\":\"AGENT\",\"inputBindings\":{},\"targetRef\":\"agent:recon@2\",\"dependsOn\":[\"a\"]}]}"),
                        Set.of("lookup"));
        assertTrue(ok.valid(), "合法双步骤应通过: " + ok.diagnostics());
        // 环
        var cycle =
                PlanValidator.validate(
                        Json.read(
                                "{\"steps\":[{\"stepId\":\"a\",\"kind\":\"AGENT\",\"targetRef\":\"agent:x\",\"dependsOn\":[\"b\"]},"
                                    + "{\"stepId\":\"b\",\"kind\":\"AGENT\",\"targetRef\":\"agent:x\",\"dependsOn\":[\"a\"]}]}"),
                        null);
        assertFalse(cycle.valid());
        assertTrue(cycle.diagnostics().stream().anyMatch(d -> "DEPENDENCY_CYCLE".equals(d.get("code"))));
        // 能力引用不在授权范围
        var notAllowed =
                PlanValidator.validate(
                        Json.read(
                                "{\"steps\":[{\"stepId\":\"a\",\"kind\":\"SKILL\",\"targetRef\":\"skill:rogue@1#"
                                    + "a".repeat(64)
                                    + "\",\"dependsOn\":[]}]}"),
                        Set.of("lookup"));
        assertFalse(notAllowed.valid());
        assertTrue(
                notAllowed.diagnostics().stream()
                        .anyMatch(d -> "CAPABILITY_NOT_ALLOWED".equals(d.get("code"))));
        // 形状不符（SKILL 缺 digest）
        var badShape =
                PlanValidator.validate(
                        Json.read(
                                "{\"steps\":[{\"stepId\":\"a\",\"kind\":\"SKILL\",\"targetRef\":\"skill:lookup@1\",\"dependsOn\":[]}]}"),
                        null);
        assertTrue(
                badShape.diagnostics().stream()
                        .anyMatch(d -> "TARGET_REF_SHAPE".equals(d.get("code"))));
    }

    /** 双轨解耦：task.mode 受理固定（T2-2）+会话交互模式独立——并发 Plan/Agent 互不串扰。 */
    @Test
    void sessionInteractionModeDecoupledFromTaskMode() {
        String sid = session();
        // 会话激活规划交互模式
        PlanMode.setActive(sessions, sid, true);
        assertTrue(PlanMode.isActive(sessions, sid));
        // 同会话受理 AGENT 任务（任务级 mode=AGENT 受理即固定——T2-2 语义，互不影响会话模式）
        var tasks = new cn.agentplatform.engine.application.Tasks(store);
        var agentTask =
                tasks.create(
                        "h24-app",
                        Json.tree(
                                Map.of(
                                        "requestId",
                                        "r-decouple-" + UUID.randomUUID(),
                                        "mode",
                                        "AGENT",
                                        "goal",
                                        "直接执行")),
                        new Actor("engine-test", "alice", Set.of("EDITOR"), Map.of()));
        assertEquals("AGENT", agentTask.get("mode"), "任务级 mode 不受会话 plan/mode 影响");
        // 会话模式保持激活（互不串扰）
        assertTrue(PlanMode.isActive(sessions, sid));
        long tid =
                db.queryForObject(
                        "select id from engine.task where public_id=?", Long.class, agentTask.get("taskId"));
        db.update("delete from engine.task_step where task_id=?", tid);
        db.update("delete from engine.task_event where task_id=?", tid);
        db.update("delete from engine.task where id=?", tid);
    }
}
