package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.infra.AppPublicationStore;
import cn.agentplatform.app.infra.AssistantExperienceRepository;
import cn.agentplatform.app.infra.ConfigReleaseRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.app.infra.IntegrationProfileRepository;
import cn.agentplatform.app.infra.ReleasePins;
import cn.agentplatform.app.infra.RuleAssetRepository;
import cn.agentplatform.app.infra.WorkspaceManifestRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A3-1（app V122 工作区 manifest）：专属空库 agent_platform_v122_test 上的自建链验证。
 *
 * <p>门控照 {@code RuleAssetsPostgresTest}（A3-3 兄弟卡）的 PG 测试风格
 * （{@code @EnabledIfEnvironmentVariable}；不建库、不改共享库）：{@code A31_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_v122_test}
 * 必须显式给出，缺失整类跳过；账号口令沿用 {@code AGENT_DB_USER}/{@code AGENT_DB_PASSWORD}（TestDatabase 坐标）。
 * Flyway 在 @BeforeAll 对空库应用 app-management 全迁移链（schemas="app"）——V122 编号小于已合入的
 * V123，空库路径按版本号自然有序（…→V121→V122→V123），不依赖 outOfOrder。
 * 覆盖：迁移交付物（workspace_manifest 表/五必键 CHECK/digest UNIQUE/不可变触发器 +
 * integration_config_release 成对扩列）、CRUD+CAS 409+校验聚合、digest 去重、发布预检 manifest
 * 引用核验（规则本地 PASS/BLOCKED、技能与插件跨服务 N/A）、发布固化成对列。
 */
@EnabledIfEnvironmentVariable(named = "A31_DB_URL",
    matches = "jdbc:postgresql://127[.]0[.]0[.]1:25432/agent_platform_v122_test")
class WorkspaceManifestPostgresTest {

    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000L, 36);
    static final AtomicInteger SEQ = new AtomicInteger();

    static JdbcTemplate db;
    static DataSourceTransactionManager txm;
    static TransactionTemplate tx;
    static WorkspaceManifestRepository repository;
    static WorkspaceManifests manifests;
    static RuleAssets rules;
    static IntegrationConfigReleases releases;

    @BeforeAll
    static void database() {
        var ds = new DriverManagerDataSource(System.getenv("A31_DB_URL"),
            TestDatabase.user(), TestDatabase.password());
        var result = Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
            .locations("classpath:db/migration").load().migrate();
        System.out.println("[A3-1] url=" + System.getenv("A31_DB_URL")
            + " -> migratedTo=" + result.targetSchemaVersion
            + " (migrations applied this run: " + result.migrationsExecuted + ")");
        db = new JdbcTemplate(ds);
        txm = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(txm);
        repository = new WorkspaceManifestRepository(db);
        manifests = new WorkspaceManifests(repository, new ConfigWriteLog(db));
        rules = new RuleAssets(new RuleAssetRepository(db, txm), new ConfigWriteLog(db));
        var writes = new ConfigWriteLog(db);
        var experienceRepository = new AssistantExperienceRepository(db, txm);
        var experiences = new AssistantExperiences(experienceRepository, writes);
        var profileRepository = new IntegrationProfileRepository(db, txm);
        var profiles = new IntegrationProfiles(profileRepository, experienceRepository, writes);
        releases = new IntegrationConfigReleases(new ConfigReleaseRepository(db, txm),
            experienceRepository, experiences, profileRepository, profiles,
            new ConnectionProfileRepository(db, txm), writes,
            new PublishedMetadataService(db));
    }

    private Actor actor() {
        return new Actor("a31-" + RUN + "-" + SEQ.incrementAndGet(),
            "op-" + UUID.randomUUID(), Set.of("ADMIN"), Map.of());
    }

    private String app(Actor a) {
        String code = "app-" + RUN + "-" + SEQ.incrementAndGet();
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
            + " values(?,?,'工作区manifest测试应用','CHAT','DRAFT',?)", a.tenantId(), code,
            a.principalId());
        return code;
    }

    /** 形状对齐契约 contracts/manifest.schema.json 的最小 manifest（五必键+可选 rules/flowRef）。 */
    private Map<String, Object> validManifest(String guideRef, String ruleRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metadata", Map.of("name", "订单助手", "category", "客服", "tags", List.of("orders")));
        m.put("model", Map.of("defaultBinding",
            Map.of("providerCode", "openai", "modelName", "gpt-4o")));
        m.put("skills", Map.of("guideRefs", List.of(guideRef),
            "toolGrants", List.of(Map.of("skillCode", "detect-a", "version", 3))));
        m.put("rules", List.of(ruleRef));
        m.put("memoryPolicy", Map.of("kinds", List.of("PREFERENCE"),
            "readScopes", List.of("PRIVATE"), "writeScopes", List.of("PRIVATE"), "topK", 5));
        m.put("contextPolicy", Map.of("maxTurns", 200, "contextBudget", 60000,
            "compactionThreshold", 0.85, "historyWindow", 20));
        m.put("flowRef", "flow-1");
        m.put("permission", Map.of("roles", List.of("USER", "OPERATOR")));
        return m;
    }

    private Map<String, Object> putBody(Map<String, Object> manifest, int baseRevision) {
        return new LinkedHashMap<>(Map.of(
            "requestId", "req-a31-" + UUID.randomUUID(),
            "manifest", manifest,
            "baseRevision", baseRevision));
    }

    private void rawManifest(Actor a, String appCode, int revision, String manifestJson) {
        db.update("insert into app.workspace_manifest(tenant_id, app_code, revision, manifest,"
            + " digest, created_by) values (?, ?, ?, ?::jsonb, ?, ?)", a.tenantId(), appCode,
            revision, manifestJson, Digest.sha256(manifestJson + "@" + revision), a.principalId());
    }

    // ---------- 迁移交付物（空库自建链后逐项核对，照 FlywayLegacyUpgradeEvidenceTest 口径） ----------

    @Test
    void v122DeliversWorkspaceManifestAndPairedReleaseColumns() {
        var tables = db.queryForList(
            "select tablename from pg_tables where schemaname='app' order by tablename").stream()
            .map(r -> String.valueOf(r.get("tablename"))).toList();
        assertTrue(tables.contains("workspace_manifest"), "workspace_manifest 必须随 V122 交付");

        // revision CHECK>0、manifest 五必键+1MB+顶层 object、digest char(64) 正则（库稿 §4.6）
        assertTrue(constraintDef("workspace_manifest_revision_check", "workspace_manifest")
            .contains("> 0"), "revision 必须钉 >0");
        String manifestCheck = constraintDef("workspace_manifest_manifest_check",
            "workspace_manifest");
        assertTrue(manifestCheck.contains("1048576"), "manifest 必须钉 1MB 上限: " + manifestCheck);
        assertTrue(manifestCheck.contains("jsonb_typeof"), "manifest 必须限定顶层 jsonb object");
        for (String key : List.of("metadata", "model", "skills", "contextPolicy", "permission")) {
            assertTrue(manifestCheck.contains("'" + key + "'"), "manifest 缺必键 " + key);
        }
        assertTrue(constraintDef("workspace_manifest_digest_check", "workspace_manifest")
            .contains("64"), "digest 必须钉 64 位十六进制");

        // digest UNIQUE(tenant_id, app_code, digest)（同内容不重复出版本的库层兜底）
        var uniques = db.queryForList("""
            select conname from pg_constraint c join pg_class t on t.oid=c.conrelid
             where t.relname='workspace_manifest' and c.contype='u'
            """).stream().map(r -> String.valueOf(r.get("conname"))).toList();
        assertTrue(uniques.contains("workspace_manifest_digest_unique"),
            "digest 唯一键缺失: " + uniques);

        // 组合 PK (tenant_id, app_code, revision)
        var pk = db.queryForList("""
            select kcu.column_name from information_schema.table_constraints tc
             join information_schema.key_column_usage kcu
               on kcu.constraint_name=tc.constraint_name and kcu.constraint_schema=tc.constraint_schema
             where tc.constraint_schema='app' and tc.table_name='workspace_manifest'
               and tc.constraint_type='PRIMARY KEY' order by kcu.ordinal_position
            """, String.class);
        assertEquals(List.of("tenant_id", "app_code", "revision"), pk,
            "workspace_manifest 组合 PK 必须是 (tenant_id, app_code, revision)");

        // 不可变触发器随表交付
        var triggers = db.queryForList(
            "select tgname from pg_trigger where tgrelid = 'app.workspace_manifest'::regclass"
                + " and not tgisinternal").stream()
            .map(r -> String.valueOf(r.get("tgname"))).toList();
        assertTrue(triggers.contains("immutable_workspace_manifest"),
            "immutable_workspace_manifest 触发器缺失: " + triggers);

        // integration_config_release 成对扩列 + 成对 CHECK + digest 正则
        var columns = db.queryForList("""
            select column_name from information_schema.columns
             where table_schema='app' and table_name='integration_config_release'
            """).stream().map(r -> String.valueOf(r.get("column_name"))).toList();
        assertTrue(columns.contains("manifest_revision") && columns.contains("manifest_digest"),
            "integration_config_release 必须扩列 manifest_revision/manifest_digest");
        String pairCheck = constraintDef("integration_config_release_manifest_pair",
            "integration_config_release");
        assertTrue(pairCheck.contains("NULL"), "成对 CHECK 缺失: " + pairCheck);
        assertTrue(constraintDef("integration_config_release_manifest_digest_check",
            "integration_config_release").contains("64"), "manifest_digest 必须钉 64 位十六进制");

        // 库层负例：revision=0、非 object、缺必键、超过 1MB、不可变触发器、成对破坏
        Actor a = actor();
        String code = "raw-" + RUN;
        assertThrows(DataAccessException.class, () -> db.update(
            "insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest,"
                + " created_by) values (?, ?, 0, '{}'::jsonb, ?, ?)",
            a.tenantId(), code, Digest.sha256("rev0"), a.principalId()),
            "revision=0 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> db.update(
            "insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest,"
                + " created_by) values (?, ?, 1, '[]'::jsonb, ?, ?)",
            a.tenantId(), code, Digest.sha256("array"), a.principalId()),
            "非 object 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> db.update(
            "insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest,"
                + " created_by) values (?, ?, 1, '{\"metadata\":{}}'::jsonb, ?, ?)",
            a.tenantId(), code, Digest.sha256("missing"), a.principalId()),
            "缺四必键的 manifest 必须被 CHECK 拒绝");
        assertThrows(DataAccessException.class, () -> db.update(
            "insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest,"
                + " created_by) select ?, ?, 1,"
                + " ('{\"metadata\":\"' || repeat('x', 1200000) || '\"}')::jsonb, ?, ?",
            a.tenantId(), code, Digest.sha256("big"), a.principalId()),
            "超过 1MB 的 manifest 必须被 CHECK 拒绝");
        rawManifest(a, code, 1, Json.write(validManifest("analyze-flow@2", "enabled@1")));
        assertThrows(DataAccessException.class, () -> db.update(
            "update app.workspace_manifest set digest = ? where tenant_id = ? and app_code = ?",
            "0".repeat(64), a.tenantId(), code), "修订链必须被不可变触发器拒绝 UPDATE");
        assertThrows(DataAccessException.class, () -> db.update(
            "delete from app.workspace_manifest where tenant_id = ? and app_code = ?",
            a.tenantId(), code), "修订链必须被不可变触发器拒绝 DELETE");
        String releaseId = rawReleaseRow(a);
        assertThrows(DataAccessException.class, () -> db.update(
            "update app.integration_config_release set manifest_revision = 9"
                + " where tenant_id = ? and release_id = ?",
            a.tenantId(), releaseId), "manifest 成对列只置一列必须被 CHECK 拒绝");

        // V122 先于 V123 应用（空库自然有序），且全链到 123
        String version = db.queryForObject(
            "select coalesce(max(version),'none') from app.flyway_schema_history where success",
            String.class);
        assertEquals("123", version, "app schema 必须迁移到 V123");
        var ranks = db.queryForList("""
            select version, installed_rank from app.flyway_schema_history
             where version in ('122','123') and success
            """);
        long r122 = rankOf(ranks, "122");
        long r123 = rankOf(ranks, "123");
        assertTrue(r122 > 0 && r123 > 0 && r122 < r123, "V122 必须先于 V123 应用: " + ranks);
    }

    private long rankOf(List<Map<String, Object>> rows, String version) {
        return rows.stream().filter(r -> version.equals(String.valueOf(r.get("version"))))
            .mapToLong(r -> ((Number) r.get("installed_rank")).longValue()).findFirst().orElse(-1);
    }

    // ---------- CRUD 往返 + CAS 409 + 校验聚合 ----------

    @Test
    void crudRoundTripCasConflictAndValidationIssues() {
        Actor a = actor();
        String code = app(a);
        Map<String, Object> v1 = validManifest("analyze-flow@2", "no-direct-delete@1");

        var first = manifests.put(a, code, putBody(v1, 0));
        assertEquals(1, ((Number) first.get("revision")).intValue(), "首次保存追加 revision 1");
        assertEquals(Digest.sha256(Json.canonical(v1)), first.get("digest"),
            "digest 必须是规范化 JSON 的内容寻址 sha256");
        assertEquals(true, ((Map<?, ?>) first.get("validation")).get("ok"));

        var read = manifests.get(a, code);
        assertEquals(1, ((Number) read.get("revision")).intValue());
        assertEquals(Json.canonical(v1), Json.canonical(read.get("manifest")), "往返内容一致");
        assertEquals(a.principalId(), read.get("createdBy"));

        // 变更内容 + 正确 baseRevision → revision 2；修订链不可变，只追加
        Map<String, Object> v2 = validManifest("analyze-flow@3", "no-direct-delete@1");
        var second = manifests.put(a, code, putBody(v2, 1));
        assertEquals(2, ((Number) second.get("revision")).intValue());
        assertNotEquals(first.get("digest"), second.get("digest"));

        // CAS：过期 baseRevision → 409 MANIFEST_REVISION_CONFLICT
        PlatformException stale = assertThrows(PlatformException.class,
            () -> manifests.put(a, code, putBody(validManifest("analyze-flow@4", "x@1"), 0)));
        assertEquals(409, stale.status());
        assertEquals("MANIFEST_REVISION_CONFLICT", stale.code());
        assertTrue(stale.getMessage().contains("baseRevision"), stale.getMessage());
        // 超前 baseRevision 同样拒绝
        PlatformException ahead = assertThrows(PlatformException.class,
            () -> manifests.put(a, code, putBody(validManifest("analyze-flow@4", "x@1"), 5)));
        assertEquals("MANIFEST_REVISION_CONFLICT", ahead.code());

        // 版本列表完整且有序
        var versions = manifests.versions(a, code);
        assertEquals(List.of(1, 2), ((List<?>) versions.get("revisions")).stream()
            .map(r -> ((Number) ((Map<?, ?>) r).get("revision")).intValue()).toList());
        assertEquals(first.get("digest"),
            ((Map<?, ?>) ((List<?>) versions.get("revisions")).get(0)).get("digest"));

        // 校验对齐冻结 Schema 五必键：缺 permission + guideRefs 形状非法 → 400 聚合 issues，不落修订
        Map<String, Object> bad = new LinkedHashMap<>(validManifest("analyze-flow@2", "x@1"));
        bad.remove("permission");
        @SuppressWarnings("unchecked")
        Map<String, Object> badSkills = new LinkedHashMap<>((Map<String, Object>) bad.get("skills"));
        badSkills.put("guideRefs", List.of("missing-version"));
        bad.put("skills", badSkills);
        PlatformException invalid = assertThrows(PlatformException.class,
            () -> manifests.put(a, code, putBody(bad, 2)));
        assertEquals(400, invalid.status());
        assertEquals("MANIFEST_INVALID", invalid.code());
        assertTrue(invalid.getMessage().contains("permission")
            && invalid.getMessage().contains("guideRefs"),
            "校验失败必须聚合指明 path: " + invalid.getMessage());
        assertEquals(2, ((List<?>) manifests.versions(a, code).get("revisions")).size(),
            "校验失败的保存不得追加修订");

        // 应用不存在 → 404；应用没有 manifest → 404 MANIFEST_NOT_FOUND
        assertEquals("CONFIG_REF_NOT_FOUND", assertThrows(PlatformException.class,
            () -> manifests.get(a, "no-such-app-" + RUN)).code());
        String emptyApp = app(a);
        assertEquals("MANIFEST_NOT_FOUND", assertThrows(PlatformException.class,
            () -> manifests.get(a, emptyApp)).code());
    }

    // ---------- digest 去重（同内容不重复出版本，UNIQUE 兜底） ----------

    @Test
    void sameContentDoesNotCreateNewRevision() {
        Actor a = actor();
        String code = app(a);
        Map<String, Object> content = validManifest("analyze-flow@2", "no-direct-delete@1");
        var first = manifests.put(a, code, putBody(content, 0));
        assertEquals(1, ((Number) first.get("revision")).intValue());

        // 同内容不同键序（规范化摘要稳定）：不追加修订，返回既有 revision
        Map<String, Object> reordered = new LinkedHashMap<>();
        List.of("permission", "contextPolicy", "skills", "model", "metadata")
            .forEach(k -> reordered.put(k, content.get(k)));
        reordered.put("rules", content.get("rules"));
        reordered.put("flowRef", content.get("flowRef"));
        reordered.put("memoryPolicy", content.get("memoryPolicy"));
        var second = manifests.put(a, code, putBody(reordered, 1));
        assertEquals(1, ((Number) second.get("revision")).intValue(), "同内容不得重复出版本");
        assertEquals(true, second.get("deduplicated"));
        assertEquals(first.get("digest"), second.get("digest"));
        assertEquals(1, ((List<?>) manifests.versions(a, code).get("revisions")).size(),
            "digest 去重后版本链仍只有 1 个修订");

        // 库层 UNIQUE 兜底：并发/绕过应用层写同 digest 必须被唯一键拒绝
        assertThrows(DataAccessException.class, () -> db.update(
            "insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest,"
                + " created_by) values (?, ?, 2, ?::jsonb, ?, ?)",
            a.tenantId(), code, Json.write(content), second.get("digest"), a.principalId()),
            "UNIQUE(tenant, app, digest) 必须兜底拒绝重复内容");
    }

    // ---------- 发布预检 manifest 引用核验（规则本地 / 技能插件 N/A） ----------

    @Test
    void publishPrecheckVerifiesRuleRefsLocallyAndMarksCrossServiceNa() {
        Actor a = actor();
        String appCode = app(a);
        // 规则夹具：enabled@1 已发布（主档 ENABLED）、drafted@1 仅草稿、disabled@1 发布后被停用。
        // 各占独立 stage：同 scope 的 ENFORCED 互斥（A3-3）不进入本卡用例的干扰面。
        rules.create(a, appCode, ruleBody(appCode, "enabled", "plan", "涉及删除必须确认"));
        rules.publish(a, "enabled", 1, ruleActionBody());
        rules.create(a, appCode, ruleBody(appCode, "drafted", "verify", "导出前必须确认"));
        rules.create(a, appCode, ruleBody(appCode, "disabled", "act", "直删客户必须确认"));
        rules.publish(a, "disabled", 1, ruleActionBody());
        rules.change(a, "disabled", "disable", ruleActionBody());

        // 无 manifest → 整组 SKIPPED，不阻断（存量发布口径不变）
        var service = precheckService(a.tenantId());
        var emptyChecks = new ArrayList<Map<String, Object>>();
        assertNull(service.manifestChecks(emptyChecks, appCode), "无 manifest 不产生阻断");
        assertEquals("SKIPPED", emptyChecks.getFirst().get("result"));

        // 好引用：规则 PASS（本地核验），技能/插件 N/A（跨服务如实标注），无阻断
        rawManifest(a, appCode, 1, Json.write(validManifest("analyze-flow@2", "enabled@1")));
        var goodChecks = new ArrayList<Map<String, Object>>();
        assertNull(service.manifestChecks(goodChecks, appCode));
        assertEquals(3, goodChecks.size(), "manifest 存在时应产出 规则/技能/插件 三组检查");
        assertEquals("manifest 规则引用", goodChecks.get(0).get("group"));
        assertEquals("PASS", goodChecks.get(0).get("result"));
        assertEquals("manifest 技能引用", goodChecks.get(1).get("group"));
        assertEquals("N/A", goodChecks.get(1).get("result"));
        assertTrue(String.valueOf(goodChecks.get(1).get("detail")).contains("skill-registry"),
            "N/A 条目必须如实说明跨服务依赖");
        assertEquals("manifest 插件引用", goodChecks.get(2).get("group"));
        assertEquals("N/A", goodChecks.get(2).get("result"));

        // 坏引用：缺失/未启用/未发布逐个指明对象并进入阻断清单
        rawManifest(a, appCode, 2, Json.write(validManifest("analyze-flow@2", "ghost-rule@1")));
        var missingChecks = new ArrayList<Map<String, Object>>();
        AppPublicationService.ManifestBlock missing = service.manifestChecks(missingChecks, appCode);
        assertNotNull(missing, "失效规则引用必须产生阻断清单");
        assertEquals(2, missing.manifestRevision(), "阻断清单必须携带当前头修订");
        assertTrue(missing.refs().get(0).contains("ghost-rule"),
            "阻断必须指明对象: " + missing.refs());
        assertEquals("BLOCKED", missingChecks.get(0).get("result"));

        rawManifest(a, appCode, 3, Json.write(validManifest("analyze-flow@2", "disabled@1")));
        AppPublicationService.ManifestBlock disabled =
            service.manifestChecks(new ArrayList<>(), appCode);
        assertNotNull(disabled);
        assertTrue(disabled.refs().get(0).contains("未启用"),
            "未启用规则必须被指明: " + disabled.refs());

        rawManifest(a, appCode, 4, Json.write(validManifest("analyze-flow@2", "drafted@1")));
        AppPublicationService.ManifestBlock draft =
            service.manifestChecks(new ArrayList<>(), appCode);
        assertNotNull(draft);
        assertTrue(draft.refs().stream().anyMatch(r -> r.contains("未发布")),
            "未发布版本必须被指明: " + draft.refs());
    }

    // ---------- 发布固化 manifest_revision/manifest_digest 成对列 ----------

    @Test
    void publishPinsManifestHeadIntoConfigReleaseAsPairedColumns() {
        String tenant = "a31-pin-" + RUN;
        Actor a = new Actor(tenant, "op-a31", Set.of("ADMIN"), Map.of());
        authenticate(tenant);
        try {
            // 有 manifest 的应用：配置发布创建即固化当前头修订成对列（服务端取数）
            var f1 = releaseFixture(a);
            var head = manifests.put(a, f1.appCode(),
                putBody(validManifest("analyze-flow@2", "enabled@1"), 0));
            var pinned = releases.create(releaseBody(f1));
            assertEquals(((Number) head.get("revision")).intValue(),
                ((Number) pinned.get("manifestRevision")).intValue(), "必须固化当前头修订");
            assertEquals(head.get("digest"), pinned.get("manifestDigest"));
            var row = releases.get(String.valueOf(pinned.get("releaseId")));
            assertEquals(pinned.get("manifestRevision"), row.get("manifestRevision"));
            assertEquals(head.get("digest"), row.get("manifestDigest"));
            var stored = db.queryForList(
                "select manifest_revision, trim(trailing from manifest_digest) as manifest_digest"
                    + " from app.integration_config_release where tenant_id=? and release_id=?",
                tenant, String.valueOf(pinned.get("releaseId")));
            assertEquals(1, stored.size());
            assertEquals(((Number) head.get("revision")).intValue(),
                ((Number) stored.get(0).get("manifest_revision")).intValue());
            assertEquals(head.get("digest"), stored.get(0).get("manifest_digest"));

            // 无 manifest 的存量口径：两列同空（成对 CHECK 兜底）
            var unpinned = releases.create(releaseBody(releaseFixture(a)));
            assertNull(unpinned.get("manifestRevision"));
            assertNull(unpinned.get("manifestDigest"));
            assertEquals(0, db.queryForObject(
                "select count(*) from app.integration_config_release where tenant_id=?"
                    + " and release_id=? and manifest_revision is not null",
                Integer.class, tenant, String.valueOf(unpinned.get("releaseId"))).intValue());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ---------- 辅助 ----------

    /** 发布预检服务：manifest 引用核验只依赖 db 与租户，其余 Port 能力不被该方法触碰。 */
    private AppPublicationService precheckService(String tenant) {
        AppPublicationService.Port port = new AppPublicationService.Port() {
            @Override public JdbcTemplate db() { return db; }
            @Override public TransactionTemplate tx() { return tx; }
            @Override public String tenantId() { return tenant; }
            @Override public String engineUrl() { return "http://127.0.0.1:9"; }
            @Override public String modelUrl() { return "http://127.0.0.1:9"; }
            @Override public String monitorUrl() { return "http://127.0.0.1:9"; }
            @Override public String skillUrl() { return "http://127.0.0.1:9"; }
            @Override public String gateMode() { return "OFF"; }
            @Override public boolean waiverEnabled() { return false; }
            @Override public String publicationTarget() { return "PRODUCTION"; }
            @Override public boolean pinRecordEnabled() { return false; }
            @Override public String pinEnvironment() { return "production"; }
            @Override public ReleasePins pins() { return null; }
            @Override public AppPublicationStore store() {
                throw new UnsupportedOperationException();
            }
            @Override public Map<String, Object> load(String code) {
                throw new UnsupportedOperationException();
            }
            @Override public Map<String, Object> loadForUpdate(String code) {
                throw new UnsupportedOperationException();
            }
            @Override public Map<String, Object> templateConfig(Map<String, Object> config) {
                throw new UnsupportedOperationException();
            }
            @Override public List<Map<String, Object>> frozenModels(Map<String, Object> app,
                long rev, String hash) { return List.of(); }
            @Override public void attachModels(Map<String, Object> snapshot,
                List<Map<String, Object>> refs) { }
            @Override public void collectModelIds(Object value, Set<Long> ids) { }
            @Override public void assertCapabilitySource(Map<String, Object> app) { }
            @Override public Map<String, Object> remote(String base, String path) {
                throw new UnsupportedOperationException();
            }
            @Override public Map<String, Object> remotePost(String base, String path,
                Map<String, Object> body) { throw new UnsupportedOperationException(); }
            @Override public void log(String code, String action, String from, String to,
                Object detail) { }
        };
        return new AppPublicationService(port);
    }

    private Map<String, Object> ruleBody(String appCode, String codeName, String stage,
            String trigger) {
        return new LinkedHashMap<>(Map.of(
            "requestId", "req-a31-rule-" + UUID.randomUUID(),
            "code", codeName,
            "name", "规则" + codeName,
            "nature", "ENFORCED",
            "scope", Map.of("app", appCode, "stage", stage),
            "content", Map.of("trigger", trigger, "action", "block", "requirement", "必须人工确认")));
    }

    private Map<String, Object> ruleActionBody() {
        return new LinkedHashMap<>(Map.of("requestId", "req-a31-" + UUID.randomUUID()));
    }

    /** 配置发布夹具（照 ConfigReleaseFlowTest）：已发布应用版本 v1 + APPROVED 体验 + APPROVED 档案。 */
    record Fixture(String appCode, int appVersion, int experienceRev, String profileId) {}

    private Fixture releaseFixture(Actor a) {
        String tenant = a.tenantId();
        String appCode = "app-" + RUN + "-" + SEQ.incrementAndGet();
        Integer appId = db.queryForObject("""
            insert into app.application(tenant_id, code, name, type, status, current_version_no, created_by)
            values (?, ?, '配置发布固化测试', 'CHAT', 'PUBLISHED', 1, ?) returning id
            """, Integer.class, tenant, appCode, a.principalId());
        Integer draftId = db.queryForObject("""
            insert into app.app_draft(tenant_id, app_id, app_code, draft_rev)
            values (?, ?, ?, 1) returning id
            """, Integer.class, tenant, appId, appCode);
        db.update("""
            insert into app.app_version(tenant_id, app_code, version_no, app_id, app_draft_id,
              draft_rev, candidate_sha256, snapshot, eval_report_id, published_by)
            values (?, ?, 1, ?, ?, 1, ?, '{}'::jsonb, 'eval-a31', ?)
            """, tenant, appCode, appId, draftId, Digest.sha256("candidate-" + appCode),
            a.principalId());
        var writes = new ConfigWriteLog(db);
        var experienceRepository = new AssistantExperienceRepository(db, txm);
        var experiences = new AssistantExperiences(experienceRepository, writes);
        Map<String, Object> expBody = new LinkedHashMap<>();
        expBody.put("title", "固化测试助手");
        expBody.put("welcomeText", "你好");
        expBody.put("capabilityDescriptions", List.of());
        expBody.put("promptRefs", List.of());
        expBody.put("requestId", "req-a31-exp-" + UUID.randomUUID());
        int expRev = ((Number) experiences.create(appCode, expBody).get("experienceRev")).intValue();
        experiences.approve(appCode,
            Map.of("requestId", "req-a31-exp-ok-" + UUID.randomUUID(), "expectedRev", expRev));
        var profiles = new IntegrationProfiles(new IntegrationProfileRepository(db, txm),
            experienceRepository, writes);
        String profileId = "ip-a31-" + RUN + "-" + SEQ.incrementAndGet();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileId", profileId);
        profile.put("appCode", appCode);
        profile.put("bizSystemCode", "crm");
        profile.put("environment", "production");
        profile.put("mode", "L2");
        profile.put("allowedOrigins", List.of("https://crm.example.com"));
        profile.put("experienceRef", Map.of("appCode", appCode, "experienceRev", expRev));
        profile.put("requestId", "req-a31-ip-" + UUID.randomUUID());
        profiles.create(profile);
        profiles.approve(profileId,
            Map.of("requestId", "req-a31-ip-ok-" + UUID.randomUUID(), "expectedRev", 1));
        return new Fixture(appCode, 1, expRev, profileId);
    }

    private Map<String, Object> releaseBody(Fixture f) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("releaseId", "rel-a31-" + RUN + "-" + SEQ.incrementAndGet());
        body.put("appCode", f.appCode());
        body.put("appVersion", f.appVersion());
        body.put("experienceRev", f.experienceRev());
        body.put("integrationProfileId", f.profileId());
        body.put("integrationProfileRev", 1);
        body.put("connectionProfileRefs", List.of());
        body.put("activationScope", "production");
        body.put("requestId", "req-a31-rel-" + UUID.randomUUID());
        return body;
    }

    /** 供成对 CHECK 负例使用的最小配置发布行（直插，manifest 两列同空；引用行同事务语义由夹具保证）。 */
    private String rawReleaseRow(Actor a) {
        String code = "rawrel-" + RUN;
        Integer appId = db.queryForObject("""
            insert into app.application(tenant_id, code, name, type, status, current_version_no, created_by)
            values (?, ?, '成对CHECK负例', 'CHAT', 'PUBLISHED', 1, ?) returning id
            """, Integer.class, a.tenantId(), code, a.principalId());
        Integer draftId = db.queryForObject("""
            insert into app.app_draft(tenant_id, app_id, app_code, draft_rev)
            values (?, ?, ?, 1) returning id
            """, Integer.class, a.tenantId(), appId, code);
        db.update("""
            insert into app.app_version(tenant_id, app_code, version_no, app_id, app_draft_id,
              draft_rev, candidate_sha256, snapshot, eval_report_id, published_by)
            values (?, ?, 1, ?, ?, 1, ?, '{}'::jsonb, 'eval-x', ?)
            """, a.tenantId(), code, appId, draftId, Digest.sha256("c-" + code), a.principalId());
        db.update("""
            insert into app.assistant_experience(tenant_id, app_code, current_rev, status, created_by)
            values (?, ?, 1, 'APPROVED', ?)
            """, a.tenantId(), code, a.principalId());
        db.update("""
            insert into app.assistant_experience_revision(tenant_id, app_code, experience_rev,
              title, declaration_sha256, created_by)
            values (?, ?, 1, '成对CHECK负例', ?, ?)
            """, a.tenantId(), code, Digest.sha256("exp-" + code), a.principalId());
        String profileId = "ipx-" + code;
        db.update("""
            insert into app.integration_profile(tenant_id, profile_id, app_code, current_rev, status, created_by)
            values (?, ?, ?, 1, 'APPROVED', ?)
            """, a.tenantId(), profileId, code, a.principalId());
        db.update("""
            insert into app.integration_profile_revision(tenant_id, profile_id, profile_rev,
              biz_system_code, environment, mode, allowed_origins, context_schema,
              credential_policy_ref, experience_ref, declaration_sha256, created_by)
            values (?, ?, 1, 'crm', 'production', 'L2', '[]'::jsonb, '{}'::jsonb, '', '{}'::jsonb, ?, ?)
            """, a.tenantId(), profileId, Digest.sha256("decl-" + code), a.principalId());
        String releaseId = "relx-" + RUN + "-" + SEQ.incrementAndGet();
        db.update("""
            insert into app.integration_config_release(tenant_id, release_id, app_code, app_version,
              candidate_sha256, experience_rev, integration_profile_id, integration_profile_rev,
              connection_profile_refs, activation_scope, digest, created_by)
            values (?, ?, ?, 1, ?, 1, ?, 1, '[]'::jsonb, 'production', ?, ?)
            """, a.tenantId(), releaseId, code, Digest.sha256("cand-" + code), profileId,
            Digest.sha256("rel-" + releaseId), a.principalId());
        return releaseId;
    }

    private static String constraintDef(String conname, String relname) {
        return db.queryForObject(
            "select pg_get_constraintdef(c.oid) from pg_constraint c"
                + " join pg_class t on t.oid=c.conrelid"
                + " where t.relname='" + relname + "' and c.conname='" + conname + "'",
            String.class);
    }

    private static void authenticate(String tenant) {
        var jwt = org.springframework.security.oauth2.jwt.Jwt
            .withTokenValue("verified-a31-config-admin").header("alg", "HS256")
            .subject("op-a31").claim("tenant_id", tenant).claim("roles", List.of("ADMIN"))
            .issuedAt(java.time.Instant.now())
            .expiresAt(java.time.Instant.now().plusSeconds(600)).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                    jwt));
    }
}
