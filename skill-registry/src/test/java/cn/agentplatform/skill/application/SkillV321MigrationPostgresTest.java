package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A3-2 迁移交付卡双路径验证器（skill V321，库稿 §4.8；照 engine FlywayLegacyUpgradeEvidenceTest
 * 模式与 skill-registry PG 测试先例，schemas="skill"）：
 *
 * <p>空库路径 = drop schema 后全链 migrate（agent_platform_v321_test 专用库，空库自建链）；
 * 存量升级路径 = 先迁移到 V320 再跑全链，验证 skill.kind 扩列对存量行的 DEFAULT 'TOOL' 回填
 * 与五表交付。25432 专用 PG，不触碰 dev/engine_test/v122_test/v230_test/v231_test 库。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillV321MigrationPostgresTest {
    private static final String DATABASE = "agent_platform_v321_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;

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

    private static void resetSchema() {
        db.execute("drop schema if exists skill cascade");
    }

    /** 本卡只验 V321 交付：target 钉在 321（先例 SkillPublishDigestPostgresTest .target("314")），
     * 链尾追加 V322+ 不回溯改写本卡的断言对象。 */
    private static void migrateFull() {
        Flyway.configure()
                .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .target("321")
                .load()
                .migrate();
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static String schemaVersion() {
        return db.queryForObject(
                "select coalesce(max(version),'none') from skill.flyway_schema_history where success",
                String.class);
    }

    private static List<String> tables() {
        return db.queryForList(
                "select tablename from pg_tables where schemaname='skill' order by tablename",
                String.class);
    }

    private static String constraintDef(String conname, String relname) {
        return db.queryForObject(
                "select pg_get_constraintdef(c.oid) from pg_constraint c"
                    + " join pg_class t on t.oid=c.conrelid"
                    + " where t.relnamespace = (select oid from pg_namespace where nspname='skill')"
                    + " and t.relname='"
                    + relname
                    + "' and c.conname='"
                    + conname
                    + "'",
                String.class);
    }

    private static List<String> uniqueColumns(String table, String constraint) {
        return db.queryForList(
                "select kcu.column_name from information_schema.table_constraints tc"
                    + " join information_schema.key_column_usage kcu"
                    + " on kcu.constraint_name=tc.constraint_name"
                    + " and kcu.constraint_schema=tc.constraint_schema"
                    + " where tc.constraint_schema='skill' and tc.table_name='"
                    + table
                    + "' and tc.constraint_name='"
                    + constraint
                    + "' and tc.constraint_type='UNIQUE' order by kcu.ordinal_position",
                String.class);
    }

    private static void assertEnum(String table, String constraint, String... values) {
        String def = constraintDef(constraint, table);
        assertNotNull(def, table + "." + constraint + " 缺失");
        for (String value : values)
            assertTrue(def.contains("'" + value + "'"), constraint + " 缺枚举值 " + value);
    }

    @Test
    void freshChainMigratesTo321AndDeliversDualTrackObjects() {
        resetSchema();
        var result =
                Flyway.configure()
                        .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                        .schemas("skill")
                        .defaultSchema("skill")
                        .locations("classpath:db/migration")
                        .target("321")
                        .load()
                        .migrate();
        System.out.println(
                "[A3-2 dual-path fresh] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        assertEquals("321", schemaVersion(), "skill schema 必须迁移到 V321");

        // 六对象在位：skill.kind 扩列 + 插件五表（库稿 §3 总览行）。
        var names = tables();
        for (String expected :
                new String[] {"plugin_source", "plugin", "plugin_version", "plugin_grant", "plugin_discovery"})
            assertTrue(names.contains(expected), expected + " 必须随 V321 交付");
        var skillColumns =
                db.queryForList(
                        "select column_name from information_schema.columns"
                            + " where table_schema='skill' and table_name='skill'",
                        String.class);
        assertTrue(skillColumns.contains("kind"), "skill.kind 扩列缺失");
        String kindDefault =
                db.queryForObject(
                        "select column_default from information_schema.columns"
                            + " where table_schema='skill' and table_name='skill' and column_name='kind'",
                        String.class);
        assertTrue(
                kindDefault != null && kindDefault.contains("'TOOL'"),
                "skill.kind 必须带 DEFAULT 'TOOL'，存量语义零变化");
        assertEnum("skill", "skill_kind_check", "TOOL", "GUIDE");

        // UNIQUE 键逐一对齐库稿 §4.8。
        assertEquals(
                List.of("tenant_id", "code"),
                uniqueColumns("plugin_source", "plugin_source_tenant_id_code_key"),
                "plugin_source UNIQUE(tenant_id,code) 不符");
        assertEquals(
                List.of("tenant_id", "code"),
                uniqueColumns("plugin", "plugin_tenant_id_code_key"),
                "plugin UNIQUE(tenant_id,code) 不符");
        assertEquals(
                List.of("tenant_id", "plugin_id", "version"),
                uniqueColumns("plugin_version", "plugin_version_tenant_id_plugin_id_version_key"),
                "plugin_version UNIQUE(tenant_id,plugin_id,version) 不符");
        assertEquals(
                List.of("plugin_version_id", "grantee_type", "grantee_code"),
                uniqueColumns("plugin_grant", "plugin_grant_plugin_version_id_grantee_type_grantee_code_key"),
                "plugin_grant UNIQUE(plugin_version_id,grantee_type,grantee_code) 不符");
        assertEquals(
                List.of("source_id", "batch_no"),
                uniqueColumns("plugin_discovery", "plugin_discovery_source_id_batch_no_key"),
                "plugin_discovery UNIQUE(source_id,batch_no) 不符");

        // CHECK 枚举逐表核对（库稿 §4.8）。
        assertEnum(
                "plugin_source",
                "plugin_source_source_type_check",
                "REGISTRY",
                "GIT",
                "DIRECTORY",
                "CAPABILITY_PACKAGE");
        assertEnum("plugin_source", "plugin_source_status_check", "ACTIVE", "DISABLED");
        assertEnum("plugin", "plugin_status_check", "ACTIVE", "DISABLED");
        assertEnum(
                "plugin_version",
                "plugin_version_status_check",
                "DISCOVERED",
                "ENABLED",
                "DISABLED",
                "REVOKED");
        assertEnum("plugin_grant", "plugin_grant_grantee_type_check", "TENANT", "WORKSPACE");
        assertEnum("plugin_grant", "plugin_grant_status_check", "GRANTED", "REVOKED");
        assertEnum(
                "plugin_discovery",
                "plugin_discovery_status_check",
                "QUEUED",
                "RUNNING",
                "SUCCEEDED",
                "FAILED");

        // FK 闭合（同迁移内交付的被引表；latest_version_id 可空 FK 后补约束）。
        var fks =
                db.queryForList(
                        "select pg_get_constraintdef(c.oid) as def from pg_constraint c"
                            + " join pg_class t on t.oid=c.conrelid"
                            + " where t.relnamespace = (select oid from pg_namespace where nspname='skill')"
                            + " and t.relname in ('plugin','plugin_version','plugin_grant','plugin_discovery')"
                            + " and c.contype='f'");
        var fkText = fks.stream().map(r -> String.valueOf(r.get("def"))).collect(Collectors.toList());
        assertTrue(fkText.stream().anyMatch(d -> d.contains("plugin_source(id)")), "来源 FK 缺失");
        assertTrue(fkText.stream().anyMatch(d -> d.contains("plugin(id)")), "插件主表 FK 缺失");
        assertTrue(
                fkText.stream().anyMatch(d -> d.contains("plugin_version(id)")),
                "版本/后补 FK 缺失（含 plugin.latest_version_id→plugin_version）");

        // 摘要与上限：manifest_digest 形状、manifest 256KB、discovery 成对认领与终态投影约束。
        String digestShape = constraintDef("plugin_version_manifest_digest_check", "plugin_version");
        assertTrue(digestShape.contains("64"), "manifest_digest 形状 CHECK 缺失");
        String manifestCap = constraintDef("plugin_version_manifest_check", "plugin_version");
        assertTrue(manifestCap.contains("262144"), "manifest 256KB 上限缺失");
        String claimPair = constraintDef("plugin_discovery_claim_pair", "plugin_discovery");
        assertNotNull(claimPair, "认领成对 CHECK 缺失");
        assertNotNull(
                constraintDef("plugin_discovery_changes_shape", "plugin_discovery"),
                "changes 形状 CHECK 缺失");
        assertNotNull(
                constraintDef("plugin_discovery_changes_on_success", "plugin_discovery"),
                "changes 只随成功终态 CHECK 缺失");
        assertNotNull(
                constraintDef("plugin_discovery_error_projection", "plugin_discovery"),
                "失败投影只在 FAILED CHECK 缺失");
        String terminalClear =
                constraintDef("plugin_discovery_terminal_claim_clear", "plugin_discovery");
        assertNotNull(terminalClear, "终态清空认领 CHECK 缺失");
    }

    @Test
    void legacyV320UpgradePathBackfillsKindAndAddsPluginTables() {
        resetSchema();
        migrateTo("320");
        System.out.println(
                "[A3-2 dual-path legacy] url="
                        + HOST
                        + " stepped to V320 (存量形状：无 kind 列、无插件五表)");
        // 存量形状：V320 没有 kind 列与插件五表。
        var before =
                db.queryForList(
                        "select column_name from information_schema.columns"
                            + " where table_schema='skill' and table_name='skill'",
                        String.class);
        assertFalse(before.contains("kind"), "V320 不应已有 kind 列");
        assertFalse(tables().contains("plugin_source"), "V320 不应已有插件来源表");
        db.update(
                "insert into skill.skill(tenant_id,code,name) values('v321_legacy','legacy_tool','legacy_tool')");
        db.update(
                "insert into skill.skill_version(tenant_id,skill_code,version_no,contract,sha256,semantic_digest)"
                    + " values('v321_legacy','legacy_tool',1,'{\"type\":\"HTTP\"}','"
                    + "a".repeat(64)
                    + "','"
                    + "a".repeat(64)
                    + "')");

        migrateFull();
        assertEquals("321", schemaVersion(), "存量升级必须到达 V321");
        // 存量行按 DEFAULT 'TOOL' 回填，语义零变化（库稿 §4.8/§6 迁移纪律）。
        String backfilled =
                db.queryForObject(
                        "select kind from skill.skill where tenant_id='v321_legacy' and code='legacy_tool'",
                        String.class);
        assertEquals("TOOL", backfilled, "存量技能必须回填 kind=TOOL");
        var names = tables();
        for (String expected :
                new String[] {"plugin_source", "plugin", "plugin_version", "plugin_grant", "plugin_discovery"})
            assertTrue(names.contains(expected), "存量升级后 " + expected + " 必须在位");
    }

    @Test
    void pluginDataPlaneConstraintsRejectMalformedRows() {
        resetSchema();
        migrateFull();
        // kind 枚举负例。
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.skill(tenant_id,code,name,kind)"
                            + " values('v321_neg','neg_kind','neg_kind','MODULE')"),
                "kind 越界值必须被 CHECK 拒绝");
        // 来源登记 + 版本行的合法基线。
        long sourceId =
                db.queryForObject(
                        "insert into skill.plugin_source(tenant_id,code,name,source_type,location,created_by)"
                            + " values('v321_neg','src_neg','来源','DIRECTORY','dir://x','tester') returning id",
                        Long.class);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_source(tenant_id,code,name,source_type,location,created_by)"
                            + " values('v321_neg','src_neg','重复','GIT','git://x','tester')"),
                "UNIQUE(tenant_id,code) 必须拒绝重复来源");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_source(tenant_id,code,name,source_type,location,created_by)"
                            + " values('v321_neg','src_bad','来源','MOUNT','dir://x','tester')"),
                "source_type 越界值必须被 CHECK 拒绝");
        long pluginId =
                db.queryForObject(
                        "insert into skill.plugin(tenant_id,code,name,source_id,created_by)"
                            + " values('v321_neg','plug_neg','插件',?,'tester') returning id",
                        Long.class,
                        sourceId);
        String digest = "b".repeat(64);
        String capabilities =
                "[{\"type\":\"SKILL\",\"ref\":\"alpha\",\"digest\":\"" + digest + "\"}]";
        long versionId =
                db.queryForObject(
                        "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,manifest,"
                            + "manifest_digest,capabilities)"
                            + " values('v321_neg',?,?,'1.0.0','{}',?,?::jsonb) returning id",
                        Long.class,
                        pluginId,
                        sourceId,
                        digest,
                        capabilities);
        // manifest_digest 正则、manifest 大小、能力元素形状负例。
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,manifest,"
                            + "manifest_digest,capabilities)"
                            + " values('v321_neg',?,?,'1.0.1','{}','not-hex',?::jsonb)",
                        pluginId,
                        sourceId,
                        capabilities),
                "manifest_digest 非法形状必须被 CHECK 拒绝");
        String big = "{\"pad\":\"" + "x".repeat(262144) + "\"}";
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,manifest,"
                            + "manifest_digest,capabilities)"
                            + " values('v321_neg',?,?,'1.0.2',?::jsonb,?,?::jsonb)",
                        pluginId,
                        sourceId,
                        big,
                        digest,
                        capabilities),
                "manifest 超过 256KB 必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,manifest,"
                            + "manifest_digest,capabilities)"
                            + " values('v321_neg',?,?,'1.0.3','{}',?,?::jsonb)",
                        pluginId,
                        sourceId,
                        "[{\"type\":\"NETWORK\",\"ref\":\"alpha\",\"digest\":\"" + digest + "\"}]"),
                "能力元素 type 越界必须被 CHECK 拒绝");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_version(tenant_id,plugin_id,source_id,version,manifest,"
                            + "manifest_digest,capabilities)"
                            + " values('v321_neg',?,?,'1.0.4','{}',?,?::jsonb)",
                        pluginId,
                        sourceId,
                        "[{\"type\":\"SKILL\",\"ref\":\"alpha\",\"digest\":\"short\"}]"),
                "能力元素 digest 非法形状必须被 CHECK 拒绝");
        // 版本快照不可变触发器（迁移纪律：不可变新表随建表附触发器）。plpgsql RAISE EXCEPTION
        // 经 Spring 映射为 UncategorizedSQLException，统一按 DataAccessException 断言。
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "update skill.plugin_version set manifest=?::jsonb where id=?",
                        "{\"tampered\":true}",
                        versionId),
                "版本快照 manifest 必须不可改");
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("delete from skill.plugin_version where id=?", versionId),
                "版本行不可删除（历史运行保留原始版本，终态走 REVOKED）");
        // 授权：身份不可变 + 不可删除；grantee_type 枚举负例。
        long grantId =
                db.queryForObject(
                        "insert into skill.plugin_grant(plugin_version_id,grantee_type,grantee_code,"
                            + "capabilities,granted_by)"
                            + " values(?, 'WORKSPACE','app_1',?::jsonb,'tester') returning id",
                        Long.class,
                        versionId,
                        capabilities);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "update skill.plugin_grant set grantee_code='app_2' where id=?", grantId),
                "授权身份必须不可改（撤权走状态迁移）");
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("delete from skill.plugin_grant where id=?", grantId),
                "授权记录不可删除");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_grant(plugin_version_id,grantee_type,grantee_code,"
                            + "capabilities,granted_by)"
                            + " values(?, 'USER','u_1',?::jsonb,'tester')",
                        versionId,
                        capabilities),
                "grantee_type 越界值必须被 CHECK 拒绝");
        // 发现批次：UNIQUE(source,batch) + 认领成对 + 终态清空 + 失败/差异投影的迁移时机。
        db.update(
                "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no)"
                    + " values('disc_" + "1".repeat(32) + "','v321_neg',?,1)",
                sourceId);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no)"
                            + " values('disc_" + "2".repeat(32) + "','v321_neg',?,1)",
                        sourceId),
                "UNIQUE(source_id,batch_no) 必须拒绝重复批次");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no,claim_owner)"
                            + " values('disc_" + "3".repeat(32) + "','v321_neg',?,2,'worker')",
                        sourceId),
                "认领必须与租约成对出现");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no,status,changes)"
                            + " values('disc_" + "4".repeat(32) + "','v321_neg',?,2,'RUNNING','{}')",
                        sourceId),
                "changes 只能随 SUCCEEDED 终态提交");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no,status,error_code)"
                            + " values('disc_" + "5".repeat(32) + "','v321_neg',?,2,'QUEUED','E_X')",
                        sourceId),
                "失败投影只允许 FAILED 终态携带");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> db.update(
                        "insert into skill.plugin_discovery(discovery_id,tenant_id,source_id,batch_no,status,"
                            + "claim_owner,lease_until)"
                            + " values('disc_" + "6".repeat(32) + "','v321_neg',?,2,'SUCCEEDED','worker',now())",
                        sourceId),
                "终态必须清空认领对");
    }

    @Test
    void validPluginCapabilitiesFunctionGuardsElementShape() {
        resetSchema();
        migrateFull();
        var row =
                db.queryForMap(
                        "select skill.valid_plugin_capabilities(?::jsonb) as ok,"
                                + " skill.valid_plugin_capabilities(?::jsonb) as bad_type,"
                                + " skill.valid_plugin_capabilities(?::jsonb) as bad_digest,"
                                + " skill.valid_plugin_capabilities(?::jsonb) as not_array,"
                                + " skill.valid_plugin_capabilities(?::jsonb) as empty_ok",
                        "[{\"type\":\"GUIDE_BUNDLE\",\"ref\":\"b1\",\"digest\":\"" + "c".repeat(64) + "\"}]",
                        "[{\"type\":\"PROMPT\",\"ref\":\"b1\",\"digest\":\"" + "c".repeat(64) + "\"}]",
                        "[{\"type\":\"SKILL\",\"ref\":\"b1\",\"digest\":\"zz\"}]",
                        "{\"type\":\"SKILL\"}",
                        "[]");
        assertEquals(Boolean.TRUE, row.get("ok"), "合法能力元素应通过");
        assertEquals(Boolean.FALSE, row.get("bad_type"), "非法 type 应拒绝");
        assertEquals(Boolean.FALSE, row.get("bad_digest"), "非法 digest 应拒绝");
        assertEquals(Boolean.FALSE, row.get("not_array"), "非数组应拒绝");
        assertEquals(Boolean.TRUE, row.get("empty_ok"), "空数组是合法的能力清单");
    }
}
