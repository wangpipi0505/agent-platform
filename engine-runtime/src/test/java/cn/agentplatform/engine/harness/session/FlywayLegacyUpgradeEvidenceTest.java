package cn.agentplatform.engine.harness.session;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.engine.infra.EngineTestDatabase;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 迁移交付卡双路径验证器（H1-1 起建立，后续迁移卡复用）：
 * 对 EngineTestDatabase.url()（可用 ENGINE_PG_URL 显式覆盖到存量库）执行 Flyway migrate，
 * 校验本迁移对象存在并输出终态版本作证据。
 *
 * <p>用法：空库路径=drop schema 后直接跑（显式 URL 未给出时 25432 专用库守卫仍生效）；
 * 存量升级路径={@code ENGINE_PG_URL=<存量库JDBC URL> H1_MIGRATE_EVIDENCE_TABLES=session_event}
 * 运行本类。库坐标显式给定时由调用方负责该库已准备（台账 §4.1：禁隐式跨库）。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class FlywayLegacyUpgradeEvidenceTest {

    @Test
    void migrateAndAssertDeliveredObjects() {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        var db = new JdbcTemplate(ds);
        var flyway =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(ds)
                        .schemas("engine")
                        .defaultSchema("engine")
                        .locations("classpath:db/migration")
                        .load();
        var result = flyway.migrate();
        System.out.println(
                "[H1-1 dual-path] url="
                        + EngineTestDatabase.url()
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (initial migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        var tables =
                db.queryForList(
                        "select tablename from pg_tables where schemaname='engine'"
                            + " order by tablename");
        var names = tables.stream().map(r -> String.valueOf(r.get("tablename"))).toList();
        for (String expected : new String[] {"session_event", "agent_inbox", "subagent_catalog"})
            assertTrue(names.contains(expected), expected + " 必须随 V229 交付");
        var columns =
                db.queryForList(
                        "select column_name from information_schema.columns"
                            + " where table_schema='engine' and table_name='chat_session'");
        var cols = columns.stream().map(r -> String.valueOf(r.get("column_name"))).toList();
        for (String expected : new String[] {"origin", "parent_session_id", "delegation_depth", "event_watermark"})
            assertTrue(cols.contains(expected), "chat_session." + expected + " 扩列缺失");

        // T2-1（V230 任务+计划同批）：九张表随单迁移交付（库稿 §3）
        for (String expected :
                new String[] {
                    "task", "task_dependency", "task_event", "task_dispatch", "subtask_receipt",
                    "plan_revision", "plan_step", "plan_confirmation", "task_step"
                })
            assertTrue(names.contains(expected), expected + " 必须随 V230 交付");

        // 互引 FK 同迁移闭合：第⑤步补的 task.selected_plan_revision_id FK 必须存在且指向 plan_revision
        var fk =
                db.queryForMap(
                        "select count(*) as n from information_schema.table_constraints"
                            + " where constraint_schema='engine' and constraint_name='fk_task_selected_plan'"
                            + " and constraint_type='FOREIGN KEY'");
        assertEquals(1L, ((Number) fk.get("n")).longValue(), "fk_task_selected_plan 约束缺失");

        // task.state CHECK：13 值齐全且 RESUMABLE 不入枚举（评审十轮 P1-2）
        String stateCheck =
                db.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                            + " join pg_class t on t.oid=c.conrelid"
                            + " where t.relname='task' and c.conname='task_state_check'",
                        String.class);
        assertNotNull(stateCheck, "task_state_check 缺失");
        for (String expected :
                new String[] {
                    "ACCEPTED", "PLANNING", "PLAN_READY", "NEEDS_INPUT", "WAITING_DEPENDENCIES",
                    "QUEUED", "RUNNING", "WAITING_APPROVAL", "WAITING_EXTERNAL",
                    "WAITING_RECONCILIATION", "SUCCEEDED", "FAILED", "CANCELLED"
                })
            assertTrue(stateCheck.contains("'" + expected + "'"), "task.state 缺枚举值 " + expected);
        assertFalse(stateCheck.contains("RESUMABLE"), "RESUMABLE 不得入 task.state 枚举");

        // task_event.type CHECK：10 类事件目录（契约 task-states §4）
        String eventCheck =
                db.queryForObject(
                        "select pg_get_constraintdef(c.oid) from pg_constraint c"
                            + " join pg_class t on t.oid=c.conrelid"
                            + " where t.relname='task_event' and c.conname='task_event_type_check'",
                        String.class);
        assertNotNull(eventCheck, "task_event_type_check 缺失");
        for (String expected :
                new String[] {
                    "TASK_ACCEPTED", "PLAN_VALIDATED", "STEP_READY", "STEP_STARTED", "STEP_FINISHED",
                    "SUBTASK_REGISTERED", "SUBTASK_SETTLED", "RECONCILIATION_REQUIRED",
                    "TASK_RESUMED", "TASK_FINISHED"
                })
            assertTrue(eventCheck.contains("'" + expected + "'"), "task_event.type 缺枚举值 " + expected);

        // plan_confirmation：组合 PK (plan_revision_id, confirm_seq) + UNIQUE(tenant_id, confirmed_by,
        // confirm_request_id)（幂等键按确认主体范围去重，对齐平台 (tenant, principal, request_id) 惯例）
        var pkCols =
                db.queryForList(
                        "select kcu.column_name from information_schema.table_constraints tc"
                            + " join information_schema.key_column_usage kcu"
                            + " on kcu.constraint_name=tc.constraint_name"
                            + " and kcu.constraint_schema=tc.constraint_schema"
                            + " where tc.constraint_schema='engine' and tc.table_name='plan_confirmation'"
                            + " and tc.constraint_type='PRIMARY KEY' order by kcu.ordinal_position",
                        String.class);
        assertEquals(
                java.util.List.of("plan_revision_id", "confirm_seq"),
                pkCols,
                "plan_confirmation 组合 PK 列不符");

        var uniqueCols =
                db.queryForList(
                        "select kcu.column_name from information_schema.table_constraints tc"
                            + " join information_schema.key_column_usage kcu"
                            + " on kcu.constraint_name=tc.constraint_name"
                            + " and kcu.constraint_schema=tc.constraint_schema"
                            + " where tc.constraint_schema='engine' and tc.table_name='plan_confirmation'"
                            + " and tc.constraint_type='UNIQUE' order by kcu.ordinal_position",
                        String.class);
        assertEquals(
                java.util.List.of("tenant_id", "confirmed_by", "confirm_request_id"),
                uniqueCols,
                "plan_confirmation 幂等 UNIQUE 列不符");

        String version =
                db.queryForObject(
                        "select coalesce(max(version),'none') from engine.flyway_schema_history"
                            + " where success",
                        String.class);
        System.out.println("[T2-1 dual-path] engine schema at version " + version);
        assertNotNull(version);
        assertEquals("230", version, "engine schema 必须迁移到 V230");
    }
}
