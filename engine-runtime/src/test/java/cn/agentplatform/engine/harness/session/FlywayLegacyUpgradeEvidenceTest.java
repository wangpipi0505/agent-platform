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
        String version =
                db.queryForObject(
                        "select coalesce(max(version),'none') from engine.flyway_schema_history"
                            + " where success",
                        String.class);
        System.out.println("[H1-1 dual-path] engine schema at version " + version);
        assertNotNull(version);
    }
}
