package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M5-1 engine V901 演示种子验证器（照 A3-2/A3-6 先例：专用库 agent_platform_v901_test，schemas="engine"，
 * 只动本 schema，不触碰其他库；FlywayLegacyUpgradeEvidenceTest 模式）。
 *
 * <p>验收口径（contracts/demo-seed-v901-design.md §2/§4.4 + A16）：memory_entry 2 条（1 PRIVATE/PREFERENCE
 * 用户偏好 + 1 SHARED/EXPERIENCE 卫星规划经验，source_task_id 空、status=ACTIVE、expires_at 空）；
 * 运行时事实不预置（session_event/chat_session/task 家族 demo 租户零行）；重复执行 V901 迁移两次 →
 * 零行变化（前后行数与内容摘要一致）；agent.demo_seed_skip=on 跳过。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class EngineDemoSeedV901PostgresTest {

    private static final String DATABASE = "agent_platform_v901_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String SKIP_SETTING = "agent.demo_seed_skip";

    private static JdbcTemplate db;

    @BeforeAll
    static void prepareDedicatedDatabase() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        db = new JdbcTemplate(new DriverManagerDataSource(HOST, "agent_v12", password));
    }

    @AfterAll
    static void resetSkipSwitch() {
        db.execute("alter database " + DATABASE + " reset " + SKIP_SETTING);
    }

    private static void migrateFresh() {
        db.execute("drop schema if exists engine cascade");
        var result =
                Flyway.configure()
                        .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                        .schemas("engine")
                        .defaultSchema("engine")
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        System.out.println(
                "[M5-1 engine demo-seed] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        assertEquals("901", schemaVersion(), "engine schema 必须迁移到 V901（种子为链尾迁移）");
    }

    private static String schemaVersion() {
        return db.queryForObject(
                "select coalesce(max(version::integer)::text,'none') from engine.flyway_schema_history where success",
                String.class);
    }

    private static String seedSql() {
        try (var in =
                EngineDemoSeedV901PostgresTest.class.getClassLoader()
                        .getResourceAsStream("db/migration/V901__demo_seed.sql")) {
            assertNotNull(in, "V901__demo_seed.sql 必须在 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** demo 租户 memory_entry 内容摘要（A16 幂等断言材料）。 */
    private static Map<String, Object> demoSnapshot() {
        var digest = sha256Digest();
        List<String> rows =
                db.queryForList("select * from engine.memory_entry where tenant_id='demo'").stream()
                        .map(row -> new TreeMap<>(row).toString())
                        .sorted()
                        .toList();
        digest.update(("--memory_entry:" + rows.size() + "\n").getBytes(StandardCharsets.UTF_8));
        for (String row : rows) digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
        return Map.of("rows", rows.size(), "sha256", HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void freshChainMigratesTo901AndSeedsDemoMemories() {
        migrateFresh();

        // —— memory_entry 2 条（§4.4）：1 PRIVATE/PREFERENCE + 1 SHARED/EXPERIENCE。
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.memory_entry where tenant_id='demo'", Integer.class),
                "演示记忆必须恰好 2 条");
        Map<String, Object> pref =
                db.queryForMap(
                        "select scope, kind, owner_id, content, source_kind, source_task_id, version,"
                            + " status, expires_at, workspace_code from engine.memory_entry"
                            + " where tenant_id='demo' and memory_id='demo-mem-pref-0001'");
        assertEquals("PRIVATE", pref.get("scope"));
        assertEquals("PREFERENCE", pref.get("kind"));
        assertEquals("satellite-planning-assistant", pref.get("workspace_code"));
        Map<String, Object> exp =
                db.queryForMap(
                        "select scope, kind, owner_id, content, source_kind, source_task_id, version,"
                            + " status, expires_at, workspace_code from engine.memory_entry"
                            + " where tenant_id='demo' and memory_id='demo-mem-exp-0001'");
        assertEquals("SHARED", exp.get("scope"));
        assertEquals("EXPERIENCE", exp.get("kind"));
        assertEquals("satellite-planning-assistant", exp.get("workspace_code"));
        for (Map<String, Object> row : List.of(pref, exp)) {
            assertEquals("ACTIVE", row.get("status"), "种子记忆必须 ACTIVE");
            assertEquals("USER_EXPLICIT", row.get("source_kind"), "用户显式保存→ACTIVE（V231 口径）");
            assertNull(row.get("source_task_id"), "示例记忆非任务派生（§4.4）");
            assertNull(row.get("expires_at"), "示例记忆不过期（§4.4）");
            assertEquals(1, ((Number) row.get("version")).intValue());
            assertTrue(
                    String.valueOf(row.get("content")).length() > 10,
                    "示例记忆正文非空");
        }
        // 检索主索引路径可命中（tenant/workspace/scope/status：库稿 §4.4 权限过滤先行）。
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.memory_entry where tenant_id='demo'"
                            + " and workspace_code='satellite-planning-assistant' and scope='PRIVATE'"
                            + " and status='ACTIVE'",
                        Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from engine.memory_entry where tenant_id='demo'"
                            + " and workspace_code='satellite-planning-assistant' and scope='SHARED'"
                            + " and status='ACTIVE'",
                        Integer.class));

        // —— 运行时事实不预置（设计稿 §2 明确排除）：demo 租户零行（session_event 无租户列，经会话归属过滤）。
        for (String table : List.of("chat_session", "task")) {
            assertEquals(
                    0,
                    db.queryForObject(
                            "select count(*) from engine." + table + " where tenant_id='demo'",
                            Integer.class),
                    table + " 是运行时事实，不得预置");
        }
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from engine.session_event where session_id in"
                            + " (select public_id from engine.chat_session where tenant_id='demo')",
                        Integer.class),
                "session_event 是运行时事实，不得预置");
    }

    @Test
    void seedReplayIsIdempotentZeroRowChange() {
        migrateFresh();
        var before = demoSnapshot();
        db.execute(seedSql()); // 重复执行第 1 次 = 重放 V901 迁移体（单个 DO 块）
        db.execute(seedSql()); // 重复执行第 2 次：连放两次后一并比对（幂等断言）
        var after = demoSnapshot();
        System.out.println(
                "[M5-1 engine demo-seed replay] rows before="
                        + before.get("rows")
                        + " after="
                        + after.get("rows")
                        + " sha before="
                        + before.get("sha256")
                        + " after="
                        + after.get("sha256"));
        assertEquals(before, after, "重复执行 V901 种子必须零行变化（行数与内容摘要一致）");
    }

    @Test
    void skipSwitchLeavesDemoRowsUnplanted() {
        db.execute("alter database " + DATABASE + " set " + SKIP_SETTING + " = 'on'");
        try {
            migrateFresh();
            int rows =
                    db.queryForObject(
                            "select count(*) from engine.memory_entry where tenant_id='demo'",
                            Integer.class);
            System.out.println(
                    "[M5-1 engine demo-seed skip] " + SKIP_SETTING + "=on -> demo memory rows=" + rows);
            assertEquals(0, rows, "跳过开关必须阻止记忆种子");
        } finally {
            db.execute("alter database " + DATABASE + " reset " + SKIP_SETTING);
        }
        migrateFresh();
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.memory_entry where tenant_id='demo'", Integer.class),
                "开关复位后种子必须重新生效");
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
