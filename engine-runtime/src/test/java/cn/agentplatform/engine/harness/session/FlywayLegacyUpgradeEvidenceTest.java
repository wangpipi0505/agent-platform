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

        // T2-8（V231 记忆+外溢同批）：两张表随单迁移交付（库稿 §3 总览行；§4.4/§4.5）
        for (String expected : new String[] {"memory_entry", "spill_artifact"})
            assertTrue(names.contains(expected), expected + " 必须随 V231 交付");

        // memory_entry 关键列逐条在位（库稿 §4.4；source_task_id FK→V230 task）
        var memoryColumns =
                db.queryForList(
                        "select column_name from information_schema.columns"
                            + " where table_schema='engine' and table_name='memory_entry'");
        var memoryCols = memoryColumns.stream().map(r -> String.valueOf(r.get("column_name"))).toList();
        for (String expected :
                new String[] {
                    "memory_id", "tenant_id", "workspace_code", "scope", "owner_id", "kind",
                    "content", "tags", "source_task_id", "source_kind", "version", "status",
                    "expires_at", "created_at", "updated_at"
                })
            assertTrue(memoryCols.contains(expected), "memory_entry." + expected + " 缺失");

        // spill_artifact 关键列逐条在位（库稿 §4.5 V231 部分；V232 schedule_task 为 P2 预留不建）
        var spillColumns =
                db.queryForList(
                        "select column_name from information_schema.columns"
                            + " where table_schema='engine' and table_name='spill_artifact'");
        var spillCols = spillColumns.stream().map(r -> String.valueOf(r.get("column_name"))).toList();
        for (String expected :
                new String[] {
                    "spill_id", "session_id", "call_id", "kind", "storage_ref",
                    "bytes", "sha256", "preview", "expires_at", "created_at"
                })
            assertTrue(spillCols.contains(expected), "spill_artifact." + expected + " 缺失");

        // memory_entry 枚举 CHECK：scope/kind/source_kind/status 全值在位（库稿 §4.4）
        for (String[] checkAndValues :
                new String[][] {
                    {"memory_entry_scope_check", "PRIVATE", "SHARED"},
                    {"memory_entry_kind_check", "PREFERENCE", "EXPERIENCE"},
                    {"memory_entry_source_kind_check", "USER_EXPLICIT", "MODEL_EXTRACTED"},
                    {"memory_entry_status_check", "ACTIVE", "DRAFT", "REVOKED"}
                }) {
            String def = constraintDef(db, checkAndValues[0], "memory_entry");
            for (int i = 1; i < checkAndValues.length; i++)
                assertTrue(
                        def.contains("'" + checkAndValues[i] + "'"),
                        checkAndValues[0] + " 缺枚举值 " + checkAndValues[i]);
        }

        // memory_entry.version > 0（更新 CAS 期望版本号）
        String versionCheck = constraintDef(db, "memory_entry_version_check", "memory_entry");
        assertTrue(versionCheck.contains("version"), "memory_entry_version_check 缺失");

        // spill_artifact：spill_id 形状 + kind 枚举 + sha256 形状（库稿 §4.5）
        String spillIdShape = constraintDef(db, "spill_artifact_id_shape", "spill_artifact");
        assertTrue(spillIdShape.contains("s_"), "spill_artifact_id_shape 缺失");
        String spillKind = constraintDef(db, "spill_artifact_kind_check", "spill_artifact");
        for (String expected : new String[] {"TOOL_RESULT", "ATTACHMENT"})
            assertTrue(spillKind.contains("'" + expected + "'"), "spill_artifact.kind 缺枚举值 " + expected);
        String shaShape = constraintDef(db, "spill_artifact_sha256_shape", "spill_artifact");
        assertTrue(shaShape.contains("sha256"), "spill_artifact_sha256_shape 缺失");

        // FK 闭合：memory_entry.source_task_id→task、spill_artifact.session_id→chat_session、
        // spill_artifact.call_id→tool_call（前序迁移交付的被引表，基线天然含 V230）
        var fkDefs =
                db.queryForList(
                        "select pg_get_constraintdef(c.oid) as def from pg_constraint c"
                            + " join pg_class t on t.oid=c.conrelid"
                            + " where t.relname in ('memory_entry','spill_artifact')"
                            + " and c.contype='f'");
        var fkText = fkDefs.stream().map(r -> String.valueOf(r.get("def"))).toList();
        assertTrue(
                fkText.stream().anyMatch(d -> d.contains("task(id)")),
                "memory_entry.source_task_id 必须外键到 engine.task(id)");
        assertTrue(
                fkText.stream().anyMatch(d -> d.contains("chat_session(public_id)")),
                "spill_artifact.session_id 必须外键到 engine.chat_session(public_id)");
        assertTrue(
                fkText.stream().anyMatch(d -> d.contains("tool_call(id)")),
                "spill_artifact.call_id 必须外键到 engine.tool_call(id)");

        String version =
                db.queryForObject(
                        "select coalesce(max(version),'none') from engine.flyway_schema_history"
                            + " where success",
                        String.class);
        System.out.println("[T2-8 dual-path] engine schema at version " + version);
        assertNotNull(version);
        assertEquals("231", version, "engine schema 必须迁移到 V231");
    }

    private static String constraintDef(JdbcTemplate db, String conname, String relname) {
        return db.queryForObject(
                "select pg_get_constraintdef(c.oid) from pg_constraint c"
                    + " join pg_class t on t.oid=c.conrelid"
                    + " where t.relname='"
                    + relname
                    + "' and c.conname='"
                    + conname
                    + "'",
                String.class);
    }
}
