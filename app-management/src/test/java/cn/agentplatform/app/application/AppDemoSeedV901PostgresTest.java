package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
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
 * M5-1 app V901 演示种子验证器（照 A3-2 双路径先例：专用库 agent_platform_v901_test，schemas="app"）。
 *
 * <p>验收口径（contracts/demo-seed-v901-design.md §3.1/§5 + A16）：种子行在位（application 2/app_version 2/
 * manifest 2/ICR 2 且 ACTIVE+指针 2/rule 3）；激活指针建立后运行时 active() 读取路径（指针 join 发布行
 * where status='ACTIVE'）对两工作区各返回一条且 manifest_revision/manifest_digest 成对可回读；
 * 重复执行 V901 迁移两次 → 零行变化（前后行数与内容摘要一致，A16“迁移可重复”）；生产跳过开关
 * agent.demo_seed_skip=on 时整段 no-op。manifest/candidate 摘要用平台真实 canonical 序列化（Json.canonical）
 * 回算，证明种子预计算常量与 Java 内容寻址口径逐字节一致（A16 mock↔真实一致）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AppDemoSeedV901PostgresTest {

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
        // 跳过开关是库级设置：无论测试以何种顺序结束，恢复默认（未设置=执行种子）。
        db.execute("alter database " + DATABASE + " reset " + SKIP_SETTING);
    }

    private static void migrateFresh() {
        db.execute("drop schema if exists app cascade");
        var result =
                Flyway.configure()
                        .dataSource(HOST, "agent_v12", System.getenv("AGENT_DB_PASSWORD"))
                        .schemas("app")
                        .defaultSchema("app")
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
        System.out.println(
                "[M5-1 app demo-seed] url="
                        + HOST
                        + " -> migratedTo="
                        + result.targetSchemaVersion
                        + " (migrations applied this run: "
                        + result.migrationsExecuted
                        + ")");
        assertEquals("901", schemaVersion(), "app schema 必须迁移到 V901（种子为链尾迁移）");
    }

    private static String schemaVersion() {
        return db.queryForObject(
                "select coalesce(max(version::integer)::text,'none') from app.flyway_schema_history where success",
                String.class);
    }

    /** 读取 classpath 上的 V901 种子迁移全文（单个 DO 块），供幂等复跑。 */
    private static String seedSql() {
        try (var in =
                AppDemoSeedV901PostgresTest.class.getClassLoader()
                        .getResourceAsStream("db/migration/V901__demo_seed.sql")) {
            assertNotNull(in, "V901__demo_seed.sql 必须在 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** demo 租户全部种子表的内容摘要：总行数 + 逐表行文本（列名排序、Java 内排序保证稳定）统一 SHA-256。 */
    private static Map<String, Object> demoSnapshot() {
        List<String> tables =
                List.of(
                        "application",
                        "app_draft",
                        "app_version",
                        "skill_reference",
                        "assistant_experience",
                        "assistant_experience_revision",
                        "integration_profile",
                        "integration_profile_revision",
                        "workspace_manifest",
                        "integration_config_release",
                        "integration_config_release_activation",
                        "rule_asset",
                        "rule_asset_version");
        var digest = sha256Digest();
        int total = 0;
        for (String table : tables) {
            List<String> rows =
                    db.queryForList("select * from app." + table + " where tenant_id='demo'").stream()
                            .map(row -> new TreeMap<>(row).toString())
                            .sorted()
                            .toList();
            total += rows.size();
            digest.update(("--" + table + ":" + rows.size() + "\n").getBytes(StandardCharsets.UTF_8));
            for (String row : rows) digest.update((row + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return Map.of("rows", total, "sha256", HexFormat.of().formatHex(digest.digest()));
    }

    /** 运行时 active() 读取路径（ConfigReleaseRepository.active 同款 SQL）：指针 join 发布行 where ACTIVE。 */
    private List<Map<String, Object>> activeReleases() {
        return db.queryForList(
                "select r.release_id, r.app_code, r.app_version, r.candidate_sha256, r.activation_scope,"
                    + " r.status, r.manifest_revision, trim(trailing from r.manifest_digest) as manifest_digest"
                    + " from app.integration_config_release_activation a"
                    + " join app.integration_config_release r"
                    + "   on r.tenant_id = a.tenant_id and r.release_id = a.release_id"
                    + " where a.tenant_id = 'demo' and a.scope = r.activation_scope and r.status = 'ACTIVE'"
                    + " order by r.app_code");
    }

    @Test
    void freshChainMigratesTo901AndSeedsDemoAssets() {
        migrateFresh();

        // —— 关键行在位（§2 种子范围总表）。
        assertEquals(2, count("application where tenant_id='demo'"), "两个演示工作区必须各 1 行");
        for (String code : List.of("satellite-planning-assistant", "user-governance-assistant")) {
            Map<String, Object> app =
                    db.queryForMap(
                            "select status, current_version_no from app.application"
                                + " where tenant_id='demo' and code=?",
                            code);
            assertEquals("PUBLISHED", app.get("status"), code + " 必须为已发布应用");
            assertEquals(1, ((Number) app.get("current_version_no")).intValue());
        }
        assertEquals(
                2, count("app_version where tenant_id='demo'"), "app_version 每工作区 1 个已发布版本");
        for (String code : List.of("satellite-planning-assistant", "user-governance-assistant")) {
            Map<String, Object> version =
                    db.queryForMap(
                            "select version_no, draft_rev, snapshot::text as snapshot from app.app_version"
                                + " where tenant_id='demo' and app_code=? and version_no=1",
                            code);
            assertEquals(1, ((Number) version.get("version_no")).intValue());
            assertTrue(
                    String.valueOf(version.get("snapshot")).contains("\"manifestPin\""),
                    code + " 版本快照必须钉住 manifest 头修订");
        }
        assertEquals(
                2,
                count("workspace_manifest where tenant_id='demo' and revision=1"),
                "每工作区 1 行 manifest（revision=1）");
        // manifest 分区齐全（§3 示例：metadata/model/skills/plugins/rules/memoryPolicy/contextPolicy/permission）。
        // 注：jsonb 键存在算子经 pgjdbc 需写作 ??（单个 ? 会与 JDBC 占位符冲突，?? 才是“键存在”）。
        // 期望行数：五个必键两工作区都在；plugins 仅卫星工作区（§3 注记②/governance 同构无流程插件）；
        // rules/memoryPolicy/flowRef：governance 另带 flowRef（schema 注记①：无流程引用省略键）。
        Map<String, Integer> expectedManifestKeys =
                new TreeMap<>(
                        Map.of(
                                "metadata", 2,
                                "model", 2,
                                "skills", 2,
                                "plugins", 1,
                                "rules", 2,
                                "memoryPolicy", 2,
                                "contextPolicy", 2,
                                "permission", 2));
        for (Map.Entry<String, Integer> expected : expectedManifestKeys.entrySet()) {
            assertEquals(
                    expected.getValue(),
                    db.queryForObject(
                            "select count(*) from app.workspace_manifest where tenant_id='demo'"
                                + " and manifest ?? ?",
                            Integer.class,
                            expected.getKey()),
                    "manifest 分区 " + expected.getKey() + " 行数不符");
        }
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from app.workspace_manifest where tenant_id='demo'"
                            + " and manifest ?? 'flowRef'",
                        Integer.class),
                "flowRef 仅流程工作区（user-governance-assistant）");
        assertEquals(3, count("rule_asset where tenant_id='demo'"), "示例规则 3 条");
        assertEquals(
                3,
                count("rule_asset_version where tenant_id='demo' and status='PUBLISHED'"),
                "规则版本 3 条（PUBLISHED）");
        for (String[] rule :
                new String[][] {
                    {"satellite-cloud-limit", "ENFORCED"},
                    {"um-permission-match", "ENFORCED"},
                    {"um-account-revision", "GUIDANCE"}
                }) {
            String nature =
                    db.queryForObject(
                            "select nature from app.rule_asset where tenant_id='demo' and code=?",
                            String.class,
                            rule[0]);
            assertEquals(rule[1], nature, rule[0] + " nature 不符（§4.3）");
        }

        // —— 激活链路：发布 APPROVED→ACTIVE + 指针（§3.1 四步，§3.1 步骤3 直接落 APPROVED 终态）。
        assertEquals(
                2,
                count("integration_config_release where tenant_id='demo'"),
                "每工作区 1 条配置发布");
        assertEquals(
                2,
                count("integration_config_release where tenant_id='demo' and status='ACTIVE'"),
                "两条发布必须经激活置 ACTIVE");
        assertEquals(
                2,
                count("integration_config_release_activation where tenant_id='demo'"),
                "两工作区各 1 行激活指针");
        for (Map<String, Object> row : activeReleases()) {
            String code = String.valueOf(row.get("app_code"));
            assertEquals("ACTIVE", row.get("status"), code + " 发布必须 ACTIVE");
            assertEquals(1, ((Number) row.get("manifest_revision")).intValue(), code + " manifest_revision=1");
            String digest = String.valueOf(row.get("manifest_digest")).trim();
            assertEquals(64, digest.length(), code + " manifest_digest 必须随发布成对固化");
            String manifestDigest =
                    db.queryForObject(
                            "select trim(trailing from digest) from app.workspace_manifest"
                                + " where tenant_id='demo' and app_code=? and revision=1",
                            String.class,
                            code);
            assertEquals(manifestDigest, digest, code + " 发布钉住的 digest 必须与 manifest 头修订一致");
        }
        // 指针 scope 与发布 activation_scope 一致（§3.1：作用域内指向唯一 ACTIVE）。
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from app.integration_config_release_activation a"
                            + " join app.integration_config_release r on r.tenant_id=a.tenant_id"
                            + " and r.release_id=a.release_id and r.activation_scope=a.scope"
                            + " where a.tenant_id='demo'",
                        Integer.class),
                "指针 scope 必须与发布 activation_scope 一致");

        // —— digest 内容寻址：用平台真实 canonical 序列化回算，与预计算常量逐字节一致（A16 口径）。
        for (String code : List.of("satellite-planning-assistant", "user-governance-assistant")) {
            Map<String, Object> row =
                    db.queryForMap(
                            "select manifest::text as manifest, trim(trailing from digest) as digest"
                                + " from app.workspace_manifest where tenant_id='demo' and app_code=?"
                                + " and revision=1",
                            code);
            Map<String, Object> manifest = Json.read(String.valueOf(row.get("manifest")), Map.class);
            assertEquals(
                    row.get("digest"),
                    Digest.sha256(Json.canonical(manifest)),
                    code + " manifest digest 必须等于平台 canonical sha256（内容寻址）");
        }
        // candidate_sha256 同口径回算（AppService.sha=sha256(canonical(candidate))；candidate=snapshot 装配前缀）。
        for (String code : List.of("satellite-planning-assistant", "user-governance-assistant")) {
            Map<String, Object> version =
                    db.queryForMap(
                            "select snapshot::text as snapshot, candidate_sha256 from app.app_version"
                                + " where tenant_id='demo' and app_code=? and version_no=1",
                            code);
            Map<String, Object> snapshot = Json.read(String.valueOf(version.get("snapshot")), Map.class);
            var candidate = new TreeMap<String, Object>();
            for (String key :
                    List.of(
                            "appCode", "type", "name", "description", "domain", "bizCategory", "tags",
                            "config", "definition")) candidate.put(key, snapshot.get(key));
            assertEquals(
                    String.valueOf(version.get("candidate_sha256")),
                    Digest.sha256(Json.canonical(candidate)),
                    code + " candidate_sha256 必须等于平台 canonical sha256");
        }
    }

    @Test
    void seedReplayIsIdempotentZeroRowChange() {
        migrateFresh();
        var before = demoSnapshot();
        // 重复执行迁移 = 重放 V901 迁移体（单个 DO 块）：连放两次后一并比对（幂等断言）。
        db.execute(seedSql()); // 重复执行第 1 次
        db.execute(seedSql()); // 重复执行第 2 次
        var after = demoSnapshot();
        System.out.println(
                "[M5-1 app demo-seed replay] rows before="
                        + before.get("rows")
                        + " after="
                        + after.get("rows")
                        + " sha before="
                        + before.get("sha256")
                        + " after="
                        + after.get("sha256"));
        assertEquals(before, after, "重复执行 V901 种子必须零行变化（行数与内容摘要一致）");
        // active() 读取路径在复跑后不变（§3.1 验收补充）。
        assertEquals(2, activeReleases().size(), "复跑后 active() 仍对两工作区各返回一条 ACTIVE 发布");
    }

    @Test
    void skipSwitchLeavesDemoRowsUnplanted() {
        // 生产跳过：库级设置 on → 全新迁移成功但 demo 租户零种子行。
        db.execute("alter database " + DATABASE + " set " + SKIP_SETTING + " = 'on'");
        try {
            migrateFresh();
            System.out.println(
                    "[M5-1 app demo-seed skip] "
                            + SKIP_SETTING
                            + "=on -> demo application rows="
                            + count("application where tenant_id='demo'"));
            assertEquals(0, count("application where tenant_id='demo'"), "跳过开关必须阻止 application 种子");
            assertEquals(
                    0,
                    count("integration_config_release where tenant_id='demo'"),
                    "跳过开关必须阻止发布种子");
            assertEquals(0, count("rule_asset where tenant_id='demo'"), "跳过开关必须阻止规则种子");
        } finally {
            db.execute("alter database " + DATABASE + " reset " + SKIP_SETTING);
        }
        migrateFresh(); // 恢复默认后重新落地种子，保证库处于可复验状态
        assertEquals(2, count("application where tenant_id='demo'"), "开关复位后种子必须重新生效");
    }

    private static int count(String where) {
        return db.queryForObject("select count(*) from app." + where, Integer.class);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
