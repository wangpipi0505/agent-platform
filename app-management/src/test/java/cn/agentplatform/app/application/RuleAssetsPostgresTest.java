package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.RuleAssetRepository;
import cn.agentplatform.app.infra.RuleAssetRepository.Conflict;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A3-3（app V123 规则资产）：专属空库 agent_platform_v123_test 上的自建链验证。
 *
 * <p>门控照模块既有 PG 测试风格（@EnabledIfEnvironmentVariable；不建库、不改共享库）：
 * {@code A33_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_v123_test} 必须显式给出，
 * 缺失整类跳过；账号口令沿用 {@code AGENT_DB_USER}/{@code AGENT_DB_PASSWORD}（TestDatabase 坐标）。
 * Flyway 照 AssistantExperienceRevisionPostgresTest 的方式在 @BeforeAll 对空库应用
 * app-management 全迁移链（schemas="app"），随后验证迁移交付物 + 规则 CRUD 往返 +
 * 同 scope ENFORCED 互斥阻断 + 版本链 sha256 与不可变触发器。
 */
@EnabledIfEnvironmentVariable(named = "A33_DB_URL",
    matches = "jdbc:postgresql://127[.]0[.]0[.]1:25432/agent_platform_v123_test")
class RuleAssetsPostgresTest {

    static JdbcTemplate db;
    static RuleAssetRepository repository;
    static RuleAssets rules;

    @BeforeAll
    static void database() {
        var ds = new DriverManagerDataSource(System.getenv("A33_DB_URL"),
            TestDatabase.user(), TestDatabase.password());
        var result = Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
            .locations("classpath:db/migration").load().migrate();
        System.out.println("[A3-3] url=" + System.getenv("A33_DB_URL")
            + " -> migratedTo=" + result.targetSchemaVersion
            + " (migrations applied this run: " + result.migrationsExecuted + ")");
        db = new JdbcTemplate(ds);
        repository = new RuleAssetRepository(db, new DataSourceTransactionManager(ds));
        rules = new RuleAssets(repository, new ConfigWriteLog(db));
    }

    private Actor actor() {
        return new Actor("a33-" + UUID.randomUUID(), "op-" + UUID.randomUUID(),
            Set.of("ADMIN"), Map.of());
    }

    private void app(Actor a, String code) {
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
            + " values(?,'orders','Orders','CHAT','DRAFT',?)", a.tenantId(), a.principalId());
    }

    private Map<String, Object> body(String code, String nature, String stage, String trigger) {
        return new java.util.LinkedHashMap<>(Map.of(
            "requestId", "req-a33-" + UUID.randomUUID(),
            "code", code,
            "name", "规则" + code,
            "nature", nature,
            "scope", Map.of("app", "orders", "stage", stage),
            "content", Map.of("trigger", trigger, "action", "block", "requirement", "必须人工确认")));
    }

    // ---------- 迁移交付物（空库自建链后逐项核对，照 FlywayLegacyUpgradeEvidenceTest 口径） ----------

    @Test
    void v123DeliversRuleAssetTablesConstraintsAndImmutabilityTrigger() {
        var tables = db.queryForList(
            "select tablename from pg_tables where schemaname='app' order by tablename").stream()
            .map(r -> String.valueOf(r.get("tablename"))).toList();
        assertTrue(tables.contains("rule_asset"), "rule_asset 必须随 V123 交付");
        assertTrue(tables.contains("rule_asset_version"), "rule_asset_version 必须随 V123 交付");

        // nature CHECK：GUIDANCE/ENFORCED 两值齐全（库稿 §4.7）
        String natureCheck = constraintDef("rule_asset_nature_check", "rule_asset");
        for (String expected : new String[] {"GUIDANCE", "ENFORCED"})
            assertTrue(natureCheck.contains("'" + expected + "'"), "nature 缺枚举值 " + expected);
        String statusCheck = constraintDef("rule_asset_status_check", "rule_asset");
        for (String expected : new String[] {"DRAFT", "ENABLED", "DISABLED", "ARCHIVED"})
            assertTrue(statusCheck.contains("'" + expected + "'"), "status 缺枚举值 " + expected);
        String versionStatusCheck = constraintDef("rule_asset_version_status_check", "rule_asset_version");
        for (String expected : new String[] {"DRAFT", "PUBLISHED", "ARCHIVED"})
            assertTrue(versionStatusCheck.contains("'" + expected + "'"),
                "version.status 缺枚举值 " + expected);

        // sha256 char(64) 正则
        String shaShape = constraintDef("rule_asset_version_sha256_check", "rule_asset_version");
        assertTrue(shaShape.contains("64"), "sha256 必须钉 64 位十六进制");

        // scope/content jsonb 类型约束
        String scopeType = constraintDef("rule_asset_scope_check", "rule_asset");
        assertTrue(scopeType.contains("jsonb_typeof"), "scope 必须限定 jsonb 对象");
        String contentType = constraintDef("rule_asset_version_content_check", "rule_asset_version");
        assertTrue(contentType.contains("jsonb_typeof"), "content 必须限定 jsonb 对象");

        // 组合 PK
        var pk = db.queryForList("""
            select kcu.column_name from information_schema.table_constraints tc
             join information_schema.key_column_usage kcu
               on kcu.constraint_name=tc.constraint_name and kcu.constraint_schema=tc.constraint_schema
             where tc.constraint_schema='app' and tc.table_name='rule_asset_version'
               and tc.constraint_type='PRIMARY KEY' order by kcu.ordinal_position
            """, String.class);
        assertEquals(List.of("tenant_id", "rule_code", "version_no"), pk,
            "rule_asset_version 组合 PK 必须是 (tenant_id, rule_code, version_no)");

        // 不可变触发器随表交付
        var triggers = db.queryForList(
            "select tgname from pg_trigger where tgrelid = 'app.rule_asset_version'::regclass"
                + " and not tgisinternal").stream()
            .map(r -> String.valueOf(r.get("tgname"))).toList();
        assertTrue(triggers.contains("immutable_rule_asset_version"),
            "immutable_rule_asset_version 触发器缺失: " + triggers);

        String version = db.queryForObject(
            "select coalesce(max(version),'none') from app.flyway_schema_history where success",
            String.class);
        // R-12 处置：链尾随 M5-1 V901 种子演进；断言锁下限=V123 已交付。
        assertTrue(
            Integer.parseInt(version) >= 123,
            "app schema 必须迁移到 V123 或更高（现链尾含 V901 种子）");
    }

    // ---------- CRUD 往返 ----------

    @Test
    void crudRoundTripListDraftPublishAndEnableDisable() {
        Actor a = actor();
        app(a, "orders");
        String code = "r-" + UUID.randomUUID().toString().substring(0, 8);

        var created = rules.create(a, "orders", body(code, "GUIDANCE", "plan", "涉及删除必须确认"));
        assertEquals("DRAFT", created.get("status"));
        assertEquals(1, ((Number) created.get("currentVersionNo")).intValue());

        // 往返：列表按 scope.app 过滤
        var list = rules.list(a, "orders");
        assertTrue(list.stream().anyMatch(r -> code.equals(r.get("code"))), "新建规则必须在列表中");
        assertTrue(list.stream().allMatch(r -> "orders".equals(
            ((Map<?, ?>) r.get("scope")).get("app"))), "列表只含该应用范围下的规则");

        // 草稿保存：v1 仍是 DRAFT → 原地改写
        var draft1 = Map.of("trigger", "改后触发条件", "action", "warn", "requirement", "提示后放行");
        var saved1 = rules.saveDraft(a, code, draftBody(draft1));
        assertEquals(1, ((Number) saved1.get("versionNo")).intValue());

        // 发布 v1 → 版本 PUBLISHED、主档 ENABLED（1109 §5.1 已启用规则提供给智能体选用）
        var published = rules.publish(a, code, 1, publishBody());
        assertEquals("PUBLISHED", published.get("status"));

        // 已发布后再存草稿 → 追加 v2（不可变版本链）
        var draft2 = Map.of("trigger", "第二次变更", "action", "warn", "requirement", "提示后放行");
        var saved2 = rules.saveDraft(a, code, draftBody(draft2));
        assertEquals(2, ((Number) saved2.get("versionNo")).intValue());
        var published2 = rules.publish(a, code, 2, publishBody());
        assertEquals("PUBLISHED", published2.get("status"));

        var versions = rules.versions(a, code);
        assertEquals(List.of(1, 2), versions.stream()
            .map(v -> ((Number) v.get("versionNo")).intValue()).toList(), "版本链必须 1→2 完整");
        assertEquals("PUBLISHED", versions.get(0).get("status"));
        assertEquals("PUBLISHED", versions.get(1).get("status"));
        assertNotNull(versions.get(0).get("publishedBy"), "发布版本必须记录发布人（审计）");

        // 启停往返
        assertEquals("DISABLED", rules.change(a, code, "disable", publishBody()).get("status"));
        assertEquals("ENABLED", rules.change(a, code, "enable", publishBody()).get("status"));
    }

    // ---------- 互斥冲突阻断（同 scope 两条 ENFORCED） ----------

    @Test
    void enforcedMutexConflictBlocksPublishAndNamesTheRulePair() {
        Actor a = actor();
        app(a, "orders");
        String first = "e-" + UUID.randomUUID().toString().substring(0, 8);
        String second = "e-" + UUID.randomUUID().toString().substring(0, 8);
        String third = "g-" + UUID.randomUUID().toString().substring(0, 8);

        rules.create(a, "orders", body(first, "ENFORCED", "act", "直删客户必须确认"));
        rules.create(a, "orders", body(second, "ENFORCED", "act", "导出前必须确认"));
        rules.create(a, "orders", body(third, "GUIDANCE", "act", "建议附带脱敏提示"));

        // 第一条强制规则发布成功
        var ok = rules.publish(a, first, 1, publishBody());
        assertEquals("PUBLISHED", ok.get("status"));

        // 预检结果含规则对：second 与 first 同 scope（app+stage 相同）互斥
        var head = repository.head(a, second).orElseThrow();
        List<Conflict> conflicts = repository.enforcedConflicts(a, second, head.scopeJson());
        assertEquals(1, conflicts.size(), "同 scope 只应有 first 一条冲突");
        assertEquals(first, conflicts.get(0).conflictsWith(), "冲突条目必须指明规则对");
        assertEquals(second, conflicts.get(0).ruleCode());

        // 发布被阻断：409 + 消息指明规则对；second 版本仍是 DRAFT、主档仍是 DRAFT
        PlatformException blocked =
            assertThrows(PlatformException.class, () -> rules.publish(a, second, 1, publishBody()));
        assertEquals(409, blocked.status());
        assertEquals("RULE_MUTEX_BLOCKED", blocked.code());
        assertTrue(blocked.getMessage().contains(first) && blocked.getMessage().contains(second),
            "阻断消息必须包含规则对: " + blocked.getMessage());
        assertEquals("DRAFT", repository.head(a, second).orElseThrow().status());
        assertEquals("DRAFT",
            repository.version(a, second, 1).orElseThrow().status(),
            "被阻断的版本不得落为 PUBLISHED");

        // 同 scope 的 GUIDANCE 不参与互斥：发布成功
        var guidance = rules.publish(a, third, 1, publishBody());
        assertEquals("PUBLISHED", guidance.get("status"));

        // 停用 first 后 second 可以发布（互斥只约束同时启用）
        rules.change(a, first, "disable", publishBody());
        assertEquals("PUBLISHED", rules.publish(a, second, 1, publishBody()).get("status"));

        // 启用同样受互斥约束：first 重新启用被 second 挡下
        PlatformException enableBlocked =
            assertThrows(PlatformException.class, () -> rules.change(a, first, "enable", publishBody()));
        assertEquals("RULE_MUTEX_BLOCKED", enableBlocked.code());
    }

    // ---------- 版本链 sha256 ----------

    @Test
    void versionChainSha256IsCanonicalDigestOfContentAndPublishedRowsAreImmutable() {
        Actor a = actor();
        app(a, "orders");
        String code = "s-" + UUID.randomUUID().toString().substring(0, 8);

        var content1 = Map.of("trigger", "T1", "action", "A1", "requirement", "R1");
        var createBody = body(code, "ENFORCED", "verify", "T1");
        createBody.put("content", content1);
        rules.create(a, "orders", createBody);
        var v1 = repository.version(a, code, 1).orElseThrow();
        assertEquals(Digest.sha256(Json.canonical(content1)), v1.sha256(),
            "sha256 必须是 content 规范化 JSON 的 SHA-256");
        assertTrue(v1.sha256().matches("^[0-9a-f]{64}$"));

        rules.publish(a, code, 1, publishBody());

        var content2 = Map.of("requirement", "R2", "action", "A2", "trigger", "T2");
        rules.saveDraft(a, code, draftBody(content2));
        var v2 = repository.version(a, code, 2).orElseThrow();
        // content2 键插入顺序不同，规范化摘要仍稳定：sha256 覆盖内容而不是键序
        assertEquals(Digest.sha256(Json.canonical(content2)), v2.sha256());
        assertNotEquals(v1.sha256(), v2.sha256(), "内容变化必须改变 sha256");

        // 不可变触发器：PUBLISHED 行禁 UPDATE/DELETE（库层拦截，绕过应用层也不行）
        assertThrows(DataAccessException.class, () -> db.update(
            "update app.rule_asset_version set sha256 = ? where tenant_id = ? and rule_code = ? and version_no = 1",
            "0".repeat(64), a.tenantId(), code),
            "已发布版本必须被 immutable_rule_asset_version 拒绝更新");
        assertThrows(DataAccessException.class, () -> db.update(
            "delete from app.rule_asset_version where tenant_id = ? and rule_code = ? and version_no = 1",
            a.tenantId(), code),
            "已发布版本必须被 immutable_rule_asset_version 拒绝删除");
        // 链上数据未被动过
        assertEquals(Digest.sha256(Json.canonical(content1)),
            repository.version(a, code, 1).orElseThrow().sha256());
        // DRAFT 行仍可改（草稿保存语义）
        assertDoesNotThrow(() -> rules.saveDraft(a, code,
            draftBody(Map.of("trigger", "T3", "action", "A3", "requirement", "R3"))));
    }

    // ---------- 辅助 ----------

    private Map<String, Object> draftBody(Map<String, ?> content) {
        return new java.util.LinkedHashMap<>(Map.of(
            "requestId", "req-a33-" + UUID.randomUUID(), "content", content));
    }

    private Map<String, Object> publishBody() {
        return new java.util.LinkedHashMap<>(Map.of(
            "requestId", "req-a33-" + UUID.randomUUID()));
    }

    private static String constraintDef(String conname, String relname) {
        return db.queryForObject(
            "select pg_get_constraintdef(c.oid) from pg_constraint c"
                + " join pg_class t on t.oid=c.conrelid"
                + " where t.relname='" + relname + "' and c.conname='" + conname + "'",
            String.class);
    }
}
