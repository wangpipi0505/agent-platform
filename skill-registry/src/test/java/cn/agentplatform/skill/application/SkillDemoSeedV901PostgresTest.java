package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.skill.domain.SkillDigest;
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
 * M5-1 skill V901 演示种子验证器（照 A3-2 SkillV321MigrationPostgresTest 先例：专用库
 * agent_platform_v901_test，schemas="skill"，只动本 schema，不触碰其他库）。
 *
 * <p>验收口径（contracts/demo-seed-v901-design.md §2/§4.1/§4.4 + A16）：GUIDE 2 + TOOL 代表集（≥2，
 * 本种子 12：查询/导出/规划 + um-* 读写代表）；TOOL 契约为六维归一化形状且副作用技能声明非 null
 * execution.approval.businessIntentInput、只读技能 guard.attributes 非空（发布预检红线同口径）；
 * sha256/semantic_digest 与 Java 侧 Json.canonical/SkillDigest 回算逐字节一致；插件链一套
 * （plugin_source/plugin/plugin_version/plugin_grant，capabilities 元素 {type,ref,digest}）；
 * 重复执行 V901 迁移两次 → 零行变化（A16“迁移可重复”）；agent.demo_seed_skip=on 跳过。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillDemoSeedV901PostgresTest {

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
        db.execute("drop schema if exists skill cascade");
        var result =
                Flyway.configure()
                        .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                        .schemas("skill")
                        .defaultSchema("skill")
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        System.out.println(
                "[M5-1 skill demo-seed] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        assertEquals("901", schemaVersion(), "skill schema 必须迁移到 V901（种子为链尾迁移）");
    }

    private static String schemaVersion() {
        return db.queryForObject(
                "select coalesce(max(version::integer)::text,'none') from skill.flyway_schema_history where success",
                String.class);
    }

    private static String seedSql() {
        try (var in =
                SkillDemoSeedV901PostgresTest.class.getClassLoader()
                        .getResourceAsStream("db/migration/V901__demo_seed.sql")) {
            assertNotNull(in, "V901__demo_seed.sql 必须在 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** demo 租户全部种子表的内容摘要（A16 幂等断言材料）。 */
    private static Map<String, Object> demoSnapshot() {
        List<String> tables =
                List.of("skill", "skill_version", "plugin_source", "plugin", "plugin_version", "plugin_grant");
        var digest = sha256Digest();
        int total = 0;
        for (String table : tables) {
            // plugin_grant 以 plugin_version_id 外键归属（无 tenant_id 列），整表入摘要；其余按 demo 租户过滤。
            String where = table.equals("plugin_grant") ? "" : " where tenant_id='demo'";
            List<String> rows =
                    db.queryForList("select * from skill." + table + where).stream()
                            .map(row -> new TreeMap<>(row).toString())
                            .sorted()
                            .toList();
            total += rows.size();
            digest.update(("--" + table + ":" + rows.size() + "\n").getBytes(StandardCharsets.UTF_8));
            for (String row : rows) digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return Map.of("rows", total, "sha256", HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void freshChainMigratesTo901AndSeedsDemoSkillsAndPlugins() {
        migrateFresh();

        // —— GUIDE 2（§4.1）：契约五字段 + modelInvocable 显式布尔 + 正文非空。
        for (String code : List.of("satellite-planning-guide", "user-governance-guide")) {
            Map<String, Object> row =
                    db.queryForMap(
                            "select s.kind as kind, v.status as version_status, contract::text as contract,"
                                + " v.sha256 as sha256, v.semantic_digest as semantic_digest"
                                + " from skill.skill s join skill.skill_version v"
                                + " on v.tenant_id=s.tenant_id and v.skill_code=s.code and v.version_no=1"
                                + " where s.tenant_id='demo' and s.code=?",
                            code);
            assertEquals("GUIDE", row.get("kind"), code + " 必须是 GUIDE 技能");
            assertEquals("PUBLISHED", row.get("version_status"), code + " 版本必须为已发布");
            Map<String, Object> contract = Json.read(String.valueOf(row.get("contract")), Map.class);
            // jsonb 存储按键排序回读，形状断言比集合（五字段白名单，顺序无语义）。
            assertEquals(
                    java.util.Set.of("name", "description", "whenToUse", "modelInvocable", "content"),
                    contract.keySet(),
                    code + " GUIDE 契约必须是五字段形状（V321）");
            assertEquals(Boolean.FALSE, contract.get("modelInvocable"), code + " modelInvocable=false");
            assertTrue(
                    String.valueOf(contract.get("content")).contains("## "),
                    code + " SKILL.md 正文必须包含行为知识小节");
            // sha256/semantic_digest 与 Java 侧回算一致（A16 mock↔真实一致）。
            assertEquals(
                    row.get("sha256"),
                    Digest.sha256(Json.canonical(contract)),
                    code + " sha256 必须等于平台 canonical sha256");
            assertEquals(
                    row.get("semantic_digest"),
                    SkillDigest.of(contract),
                    code + " semantic_digest 必须等于 SkillDigest 口径");
        }

        // —— TOOL 代表集 ≥2（本种子 12：lookup/export/plan + um-* 读写代表）。
        int tools =
                db.queryForObject(
                        "select count(*) from skill.skill where tenant_id='demo' and kind='TOOL'",
                        Integer.class);
        assertTrue(tools >= 2, "TOOL 代表集至少 2 个，实际 " + tools);
        List<Map<String, Object>> toolRows =
                db.queryForList(
                        "select s.code, v.contract::text as contract, v.sha256, v.semantic_digest"
                            + " from skill.skill s join skill.skill_version v"
                            + " on v.tenant_id=s.tenant_id and v.skill_code=s.code and v.version_no=1"
                            + " where s.tenant_id='demo' and s.kind='TOOL'");
        int writeSkills = 0;
        for (Map<String, Object> row : toolRows) {
            String code = String.valueOf(row.get("code"));
            Map<String, Object> contract = Json.read(String.valueOf(row.get("contract")), Map.class);
            // 六维归一化形状（SkillCatalog.normalize 落库键）。
            for (String key :
                    List.of(
                            "type", "endpoint", "httpMethod", "invocationKind", "timeoutMs", "inputSchema",
                            "outputSchema", "riskLevel", "execution", "guard", "errorSemantics",
                            "returnSpec", "outputMaxBytes", "reconciliation")) {
                assertTrue(contract.containsKey(key), code + " TOOL 契约缺少 " + key);
            }
            Map<String, Object> execution = (Map<String, Object>) contract.get("execution");
            Map<String, Object> guard = (Map<String, Object>) contract.get("guard");
            boolean sideEffect = Boolean.TRUE.equals(execution.get("sideEffect"));
            if (sideEffect) {
                writeSkills++;
                // 副作用技能发布预检红线：businessIntentInput 非 null（冻结 run.inputs 顶层字段名）。
                Map<String, Object> approval = (Map<String, Object>) execution.get("approval");
                assertNotNull(approval, code + " 副作用技能必须声明 execution.approval");
                Object intent = approval.get("businessIntentInput");
                assertTrue(
                        intent instanceof String text && text.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"),
                        code + " businessIntentInput 必须是合法意图输入字段名");
                // 需确认技能必须声明明确 confirmRoles（CONFIRM_REQUIRED 预检同口径）。
                assertFalse(
                        ((List<?>) execution.get("confirmRoles")).isEmpty(),
                        code + " CONFIRM_REQUIRED 技能必须声明 confirmRoles");
                assertEquals("CONFIRM_REQUIRED", contract.get("riskLevel"), code + " 写技能风险级别");
            } else {
                // 只读技能查询守卫非空（QUERY_GUARD_REQUIRED 预检同口径）。
                assertFalse(
                        ((Map<?, ?>) guard.get("attributes")).isEmpty(),
                        code + " 只读技能必须声明守卫属性");
            }
            assertEquals(
                    row.get("sha256"),
                    Digest.sha256(Json.canonical(contract)),
                    code + " sha256 必须等于平台 canonical sha256");
            assertEquals(
                    row.get("semantic_digest"),
                    SkillDigest.of(contract),
                    code + " semantic_digest 必须等于 SkillDigest 口径");
            // 发布指针在位（照 SkillCatalog.publish 的 current_version_id 推进）。
            Long currentVersionId =
                    db.queryForObject(
                            "select current_version_id from skill.skill where tenant_id='demo' and code=?",
                            Long.class,
                            code);
            assertNotNull(currentVersionId, code + " 发布指针必须补挂");
        }
        assertTrue(writeSkills >= 2, "写技能（businessIntentInput 声明）至少 2 个，实际 " + writeSkills);
        // GUIDE 与 TOOL 双轨互不混形：GUIDE 契约不含 TOOL 专属字段。
        for (String code : List.of("satellite-planning-guide", "user-governance-guide")) {
            String contract =
                    db.queryForObject(
                            "select v.contract::text from skill.skill s join skill.skill_version v"
                                + " on v.tenant_id=s.tenant_id and v.skill_code=s.code and v.version_no=1"
                                + " where s.tenant_id='demo' and s.code=?",
                            String.class,
                            code);
            assertFalse(contract.contains("\"inputSchema\""), code + " GUIDE 契约不得混入 TOOL 字段");
        }

        // —— 插件链一套（§4.4：demo-capability-pack）。
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.plugin_source where tenant_id='demo'"
                            + " and source_type='CAPABILITY_PACKAGE'",
                        Integer.class),
                "1 个 CAPABILITY_PACKAGE 来源（route1 能力包）");
        Map<String, Object> plugin =
                db.queryForMap(
                        "select p.id, p.latest_version_id, s.id as source_id from skill.plugin p"
                            + " join skill.plugin_source s on s.id=p.source_id"
                            + " where p.tenant_id='demo' and p.code='demo-capability-pack'");
        assertNotNull(plugin.get("latest_version_id"), "plugin.latest_version_id 必须补挂");
        Map<String, Object> version =
                db.queryForMap(
                        "select version, manifest::text as manifest, manifest_digest, capabilities::text as"
                            + " capabilities, status from skill.plugin_version"
                            + " where tenant_id='demo' and plugin_id=? and version='1.0.0'",
                        plugin.get("id"));
        assertEquals("ENABLED", version.get("status"), "演示插件版本必须 ENABLED");
        List<Map<String, Object>> capabilities =
                Json.read(String.valueOf(version.get("capabilities")), List.class);
        assertEquals(1, capabilities.size(), "能力清单 1 项（SKILL:planning-space-target-query）");
        assertEquals("SKILL", capabilities.getFirst().get("type"));
        assertEquals("planning-space-target-query", capabilities.getFirst().get("ref"));
        // 能力 digest=被引技能版本 sha256（内容寻址一致）。
        String skillSha =
                db.queryForObject(
                        "select v.sha256 from skill.skill_version v where v.tenant_id='demo'"
                            + " and v.skill_code='planning-space-target-query' and v.version_no=1",
                        String.class);
        assertEquals(
                skillSha,
                capabilities.getFirst().get("digest"),
                "能力元素 digest 必须等于被引技能版本 sha256");
        // 授权 WORKSPACE→satellite-planning-assistant（能力子集 ⊆ 版本能力）。
        Map<String, Object> grant =
                db.queryForMap(
                        "select grantee_type, grantee_code, capabilities::text as capabilities, status"
                            + " from skill.plugin_grant where plugin_version_id=?",
                        plugin.get("id"));
        assertEquals("WORKSPACE", grant.get("grantee_type"));
        assertEquals("satellite-planning-assistant", grant.get("grantee_code"));
        assertEquals("GRANTED", grant.get("status"));
        assertEquals(
                version.get("capabilities"),
                grant.get("capabilities"),
                "授权能力子集与版本能力一致（演示为全集）");
    }

    @Test
    void seedReplayIsIdempotentZeroRowChange() {
        migrateFresh();
        var before = demoSnapshot();
        db.execute(seedSql()); // 重复执行第 1 次 = 重放 V901 迁移体（单个 DO 块）
        db.execute(seedSql()); // 重复执行第 2 次：连放两次后一并比对（幂等断言）
        var after = demoSnapshot();
        System.out.println(
                "[M5-1 skill demo-seed replay] rows before="
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
                            "select count(*) from skill.skill where tenant_id='demo'", Integer.class);
            System.out.println(
                    "[M5-1 skill demo-seed skip] " + SKIP_SETTING + "=on -> demo skill rows=" + rows);
            assertEquals(0, rows, "跳过开关必须阻止技能种子");
        } finally {
            db.execute("alter database " + DATABASE + " reset " + SKIP_SETTING);
        }
        migrateFresh();
        assertTrue(
                db.queryForObject(
                        "select count(*) from skill.skill where tenant_id='demo'", Integer.class)
                        >= 14,
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
