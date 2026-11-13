package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.PlatformException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/**
 * A3-2 插件治理数据面往返验证（库稿 §4.8 五表 + 接口稿 §5.6 端点；主方案 §6.2 流程）：
 * 来源登记/列表往返、发现触发 202 QUEUED 与批次单调、发现结果写入口幂等（同摘要重放安全）、
 * 发现状态机迁移（QUEUED→RUNNING→SUCCEEDED/FAILED + 认领代次校验 + 来源 last_discovered_at）、
 * 版本启停（enable/disable 带原因，REVOKED 终态）与授权/撤权（能力子集校验、重新授权不变式）。
 * 发现执行器（认领/接管/批次串行发布）属 A3-5 消费卡——本类只验证数据面落库正确。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PluginGovernancePostgresTest {
    private static final String DATABASE = "agent_platform_v321_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String TENANT = "plugin-v321-tests";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;

    private PluginGovernance governance;
    private String code;

    @BeforeAll
    static void migrateDedicatedDatabase() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres", "agent_v12", password));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, DATABASE)
                == 0) admin.execute("create database " + DATABASE);
        var ds = new DriverManagerDataSource(HOST, "agent_v12", password);
        new JdbcTemplate(ds).execute("drop schema if exists skill cascade");
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void prepareActorAndService() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("plugin-tester")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        governance = new PluginGovernance(db, manager);
        code = "src_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, Object> sourceBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", "内部插件源");
        body.put("sourceType", "DIRECTORY");
        body.put("location", "file:///srv/plugins");
        return body;
    }

    private static Map<String, Object> capability(String type, String ref) {
        return Map.<String, Object>of("type", type, "ref", ref, "digest", Digest.sha256(ref));
    }

    private long registerSource() {
        // 同一用例内可能登记多个来源：每次独立编码，UNIQUE(tenant_id,code) 不构成用例内自冲突。
        var body = sourceBody();
        body.put("code", "src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return ((Number) governance.createSource(body).get("id")).longValue();
    }

    /** 每个用例独立插件编码：插件编码租户内唯一、与来源解耦（库稿 §4.8），避免用例间相互归并。 */
    private String pluginCode() {
        return "plug_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 版本能力清单：一个 SKILL 能力 + 一个 GUIDE_BUNDLE 能力。 */
    private List<Map<String, Object>> versionCapabilities() {
        return List.of(capability("SKILL", "report-export"), capability("GUIDE_BUNDLE", "ops-guides"));
    }

    // ---------------------------------------------------------------- 来源

    @Test
    void sourceRegistrationRoundTripAndDuplicates() {
        var created = governance.createSource(sourceBody());
        assertEquals(code, created.get("code"));
        assertEquals("DIRECTORY", created.get("sourceType"));
        assertEquals("ACTIVE", created.get("status"));
        assertEquals("plugin-tester", created.get("createdBy"));
        var fetched = governance.source(((Number) created.get("id")).longValue());
        assertEquals(created.get("code"), fetched.get("code"));
        assertTrue(
                governance.listSources().stream().anyMatch(s -> code.equals(s.get("code"))),
                "来源列表必须包含新登记来源");

        var duplicate = assertThrows(PlatformException.class, () -> governance.createSource(sourceBody()));
        assertEquals(409, duplicate.status());
        assertEquals("PLUGIN_SOURCE_CONFLICT", duplicate.code());
        var badType = sourceBody();
        badType.put("sourceType", "MOUNT");
        assertEquals(400, assertThrows(PlatformException.class, () -> governance.createSource(badType)).status());
        var badCode = sourceBody();
        badCode.put("code", "1bad");
        assertThrows(PlatformException.class, () -> governance.createSource(badCode));
    }

    // ---------------------------------------------------------------- 发现

    @Test
    void discoveryTriggerAcceptsQueuedAndBatchNumbersMonotonicallyIncrease() {
        long sourceId = registerSource();
        var accepted = governance.triggerDiscovery(sourceId);
        assertEquals("QUEUED", accepted.get("status"), "受理返回 202 语义的 QUEUED");
        assertTrue(accepted.get("discoveryId").toString().matches("disc_[0-9a-f]{32}"));
        assertEquals(1L, ((Number) accepted.get("batchNo")).longValue());
        var second = governance.triggerDiscovery(sourceId);
        assertEquals(2L, ((Number) second.get("batchNo")).longValue(), "批次号同来源单调递增");

        var query = governance.discovery(sourceId, accepted.get("discoveryId").toString());
        assertEquals("QUEUED", query.get("status"));
        assertFalse(query.containsKey("error"), "未完成时省略失败投影");
        assertFalse(query.containsKey("changes"), "未完成时省略结果字段");

        var mismatch =
                assertThrows(
                        PlatformException.class,
                        () -> governance.discovery(sourceId + 1, accepted.get("discoveryId").toString()),
                        "跨来源查询发现记录必须拒绝");
        assertEquals(404, mismatch.status());
    }

    @Test
    void discoveryStateTransitionsLandCorrectlyAndStampSource() {
        long sourceId = registerSource();
        var accepted = governance.triggerDiscovery(sourceId);
        String discoveryId = accepted.get("discoveryId").toString();
        long batch = ((Number) accepted.get("batchNo")).longValue();

        governance.markRunning(discoveryId, "executor-1", Instant.now().plusSeconds(60));
        var running = governance.discovery(sourceId, discoveryId);
        assertEquals("RUNNING", running.get("status"));
        assertNotNull(running.get("startedAt"));
        long generation =
                db.queryForObject(
                        "select claim_generation from skill.plugin_discovery where discovery_id=?",
                        Long.class,
                        discoveryId);
        assertEquals(1L, generation, "首次认领代次为 1");
        assertEquals(
                "executor-1",
                db.queryForObject(
                        "select claim_owner from skill.plugin_discovery where discovery_id=?",
                        String.class,
                        discoveryId));

        String reporter = pluginCode();
        long versionId =
                governance.recordDiscoveredVersion(
                        sourceId,
                        reporter,
                        "报表插件",
                        "1.0.0",
                        Map.<String, Object>of("name", "reporter", "version", "1.0.0"),
                        versionCapabilities());

        var changes =
                Map.<String, Object>of(
                        "added", List.of(Map.of("plugin", reporter, "version", "1.0.0", "versionId", versionId)),
                        "changed", List.of(),
                        "removed", List.of());
        // 认领代次不匹配：迟到实例无法覆盖结果（库稿 §4.8 提交前校验认领代次）。
        var stale =
                assertThrows(
                        PlatformException.class,
                        () -> governance.complete(discoveryId, generation + 1, changes));
        assertEquals("DISCOVERY_CLAIM_STALE", stale.code());
        governance.complete(discoveryId, generation, changes);
        var done = governance.discovery(sourceId, discoveryId);
        assertEquals("SUCCEEDED", done.get("status"));
        assertNotNull(done.get("finishedAt"));
        assertTrue(done.get("changes") instanceof Map<?, ?>, "成功终态携带差异");
        assertEquals(
                0L,
                db.queryForObject(
                        "select count(*) from skill.plugin_discovery where discovery_id=? and (claim_owner is not null or lease_until is not null)",
                        Long.class,
                        discoveryId),
                "终态清空认领对");
        assertNotNull(
                db.queryForObject(
                        "select last_discovered_at from skill.plugin_source where id=?", Object.class, sourceId),
                "成功发布必须回写来源 last_discovered_at");
        assertEquals(
                reporter,
                db.queryForObject(
                        "select code from skill.plugin where tenant_id=? and id=(select plugin_id from skill.plugin_version where id=?)",
                        String.class,
                        TENANT,
                        versionId),
                "发现结果写入口已归并插件主行");

        var failure = governance.triggerDiscovery(sourceId);
        String failedId = failure.get("discoveryId").toString();
        governance.markRunning(failedId, "executor-2", Instant.now().plusSeconds(60));
        long failedGeneration =
                db.queryForObject(
                        "select claim_generation from skill.plugin_discovery where discovery_id=?",
                        Long.class,
                        failedId);
        governance.fail(failedId, failedGeneration, "SCAN_TIMEOUT", "目录扫描超时");
        var failureView = governance.discovery(sourceId, failedId);
        assertEquals("FAILED", failureView.get("status"));
        var error = (Map<?, ?>) failureView.get("error");
        assertEquals("SCAN_TIMEOUT", error.get("code"));
        assertEquals("目录扫描超时", error.get("message"));
        assertFalse(failureView.containsKey("changes"), "失败终态不带差异");
        assertTrue(done.containsKey("batchNo"), "查询投影携带批次号");
        assertEquals(
                0L,
                db.queryForObject(
                        "select count(*) from skill.plugin_discovery where discovery_id=? and error_code is null",
                        Long.class,
                        failedId),
                "失败终态必须携带可查询原因");
        assertEquals(batch, ((Number) done.get("batchNo")).longValue());
    }

    @Test
    void recordDiscoveredVersionIsIdempotentForSameDigestAndConflictingOtherwise() {
        long sourceId = registerSource();
        String reporter = pluginCode();
        var manifest = Map.<String, Object>of("name", "reporter", "version", "2.0.0");
        long first =
                governance.recordDiscoveredVersion(
                        sourceId, reporter, "报表插件", "2.0.0", manifest, versionCapabilities());
        long replay =
                governance.recordDiscoveredVersion(
                        sourceId, reporter, "报表插件", "2.0.0", manifest, versionCapabilities());
        assertEquals(first, replay, "同摘要重放幂等（执行器重试安全）");
        var changed =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.recordDiscoveredVersion(
                                        sourceId,
                                        reporter,
                                        "报表插件",
                                        "2.0.0",
                                        Map.<String, Object>of("name", "reporter", "version", "2.0.0", "extra", true),
                                        versionCapabilities()),
                        "同版本异摘要必须冲突");
        assertEquals("PLUGIN_VERSION_CONFLICT", changed.code());
        var tooLarge =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.recordDiscoveredVersion(
                                        sourceId,
                                        reporter,
                                        "报表插件",
                                        "3.0.0",
                                        Map.<String, Object>of("pad", "x".repeat(300000)),
                                        versionCapabilities()),
                        "manifest 超 256KB 必须前置拒绝");
        assertEquals("PLUGIN_MANIFEST_TOO_LARGE", tooLarge.code());
        var badCapability =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.recordDiscoveredVersion(
                                        sourceId,
                                        reporter,
                                        "报表插件",
                                        "4.0.0",
                                        Map.<String, Object>of("name", "reporter"),
                                        List.of(Map.of("type", "PROMPT", "ref", "x", "digest", Digest.sha256("x")))),
                        "能力元素越界必须拒绝");
        assertEquals("INVALID_PLUGIN_CAPABILITY", badCapability.code());
    }

    // ---------------------------------------------------------------- 版本与授权

    @Test
    void versionEnableDisableRoundTripAndRevokedTerminality() {
        long sourceId = registerSource();
        long versionId =
                governance.recordDiscoveredVersion(
                        sourceId,
                        pluginCode(),
                        "报表插件",
                        "1.1.0",
                        Map.<String, Object>of("name", "reporter"),
                        versionCapabilities());
        var enabled = governance.enableVersion(versionId);
        assertEquals("ENABLED", enabled.get("status"));
        assertNotNull(enabled.get("enabledAt"));
        var disabled =
                governance.disableVersion(versionId, Map.of("reason", "疑似包含越权能力，停用复核"));
        assertEquals("DISABLED", disabled.get("status"));
        assertEquals("疑似包含越权能力，停用复核", disabled.get("disabledReason"));
        var reEnabled = governance.enableVersion(versionId);
        assertEquals("ENABLED", reEnabled.get("status"), "停用后可复核再启用");

        var disableWithoutReason =
                assertThrows(
                        PlatformException.class,
                        () -> governance.disableVersion(versionId, Map.<String, Object>of()),
                        "停用必须填写原因（接口稿 §5.6）");
        assertEquals(400, disableWithoutReason.status());

        governance.revokeVersion(versionId, "来源撤下该插件");
        assertEquals(
                "REVOKED",
                db.queryForObject(
                        "select status from skill.plugin_version where tenant_id=? and id=?",
                        String.class,
                        TENANT,
                        versionId));
        var enableRevoked =
                assertThrows(
                        PlatformException.class,
                        () -> governance.enableVersion(versionId),
                        "REVOKED 为终态不可启用");
        assertEquals("PLUGIN_STATE", enableRevoked.code());
        var grantRevoked =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.grant(
                                        versionId,
                                        Map.of(
                                                "granteeType",
                                                "WORKSPACE",
                                                "granteeCode",
                                                "app_1",
                                                "capabilities",
                                                versionCapabilities())),
                        "REVOKED 终态不可再授权");
        assertEquals(409, grantRevoked.status());
        // GET /admin/plugins?status=&source= 过滤（接口稿 §5.6）。
        assertTrue(
                governance.listPlugins("REVOKED", sourceId).stream()
                        .anyMatch(v -> versionId == ((Number) v.get("versionId")).longValue()),
                "status+source 过滤命中");
        assertTrue(governance.listPlugins("ENABLED", sourceId).isEmpty(), "终态后不再出现在 ENABLED 过滤");
    }

    @Test
    void grantsRequireEnabledVersionAndSubsetCapabilities() {
        long sourceId = registerSource();
        long versionId =
                governance.recordDiscoveredVersion(
                        sourceId,
                        pluginCode(),
                        "报表插件",
                        "1.2.0",
                        Map.<String, Object>of("name", "reporter"),
                        versionCapabilities());
        // 治理流程：未启用版本不可授权（校验启用→授权）。
        var grantBeforeEnable =
                assertThrows(PlatformException.class, () -> governance.grant(versionId, grantBody()));
        assertEquals("PLUGIN_STATE", grantBeforeEnable.code());
        governance.enableVersion(versionId);

        var granted = governance.grant(versionId, grantBody());
        assertEquals("GRANTED", granted.get("status"));
        assertEquals("WORKSPACE", granted.get("granteeType"));
        assertEquals("app_42", granted.get("granteeCode"));
        assertEquals("plugin-tester", granted.get("grantedBy"));
        var duplicate =
                assertThrows(
                        PlatformException.class,
                        () -> governance.grant(versionId, grantBody()),
                        "同授权对象重复授予必须冲突");
        assertEquals("PLUGIN_GRANT_EXISTS", duplicate.code());
        var notSubset =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.grant(
                                        versionId,
                                        Map.of(
                                                "granteeType",
                                                "WORKSPACE",
                                                "granteeCode",
                                                "app_43",
                                                "capabilities",
                                                List.of(capability("SKILL", "not-declared")))),
                        "授权能力必须为版本能力清单子集");
        assertEquals("PLUGIN_GRANT_NOT_SUBSET", notSubset.code());
        var emptyGrant = grantBody();
        emptyGrant.put("capabilities", List.of());
        assertThrows(
                PlatformException.class, () -> governance.grant(versionId, emptyGrant), "空能力子集必须拒绝");

        var revoked = governance.revoke(granted.get("grantId").toString(), Map.of("reason", "工作区下线"));
        assertEquals("REVOKED", revoked.get("status"));
        assertNotNull(revoked.get("revokedAt"));
        assertEquals("工作区下线", revoked.get("revokeReason"));
        // 撤权下次解析生效：同能力子集重新授权翻回 GRANTED（库层 UNIQUE 不允许第二行）。
        var reGranted = governance.grant(versionId, grantBody());
        assertEquals("GRANTED", reGranted.get("status"));
        assertEquals(granted.get("grantId"), reGranted.get("grantId"), "重新授权复用同一行");
        // 冻结能力范围防静默扩大：换能力子集的重新授权拒绝（原行能力集合被触发器冻结）。
        governance.revoke(reGranted.get("grantId").toString(), Map.of("reason", "调整"));
        var widened =
                Map.<String, Object>of(
                        "granteeType",
                        "WORKSPACE",
                        "granteeCode",
                        "app_42",
                        "capabilities",
                        List.of(capability("SKILL", "report-export")));
        var widenConflict =
                assertThrows(
                        PlatformException.class,
                        () -> governance.grant(versionId, widened),
                        "历史授权能力范围冻结，换子集必须冲突");
        assertEquals("PLUGIN_GRANT_EXISTS", widenConflict.code());
        var unknown =
                assertThrows(
                        PlatformException.class,
                        () -> governance.revoke("999999", Map.of()),
                        "撤权未知授权必须 404");
        assertEquals("PLUGIN_GRANT_NOT_FOUND", unknown.code());
    }

    @Test
    void rediscoveringSamePluginUnderNewSourceMovesCurrentSourceRef() {
        long firstSource = registerSource();
        String shared = pluginCode();
        governance.recordDiscoveredVersion(
                firstSource,
                shared,
                "报表插件",
                "1.0.0",
                Map.<String, Object>of("name", "reporter"),
                versionCapabilities());
        long secondSource = registerSource();
        long moved =
                governance.recordDiscoveredVersion(
                        secondSource,
                        shared,
                        "报表插件",
                        "1.1.0",
                        Map.<String, Object>of("name", "reporter"),
                        versionCapabilities());
        var row =
                db.queryForMap(
                        "select source_id,latest_version_id from skill.plugin where tenant_id=? and id="
                            + "(select plugin_id from skill.plugin_version where id=?)",
                        TENANT,
                        moved);
        assertEquals(secondSource, ((Number) row.get("source_id")).longValue(), "换源=更新当前来源引用");
        assertEquals(moved, ((Number) row.get("latest_version_id")).longValue(), "latest_version_id 指向新版本");
        // 历史版本仍指向发现时来源，可溯源（库稿 §4.8：冗余记录发现时的来源）。
        assertEquals(
                firstSource,
                db.queryForObject(
                        "select v.source_id from skill.plugin_version v where v.tenant_id=? and v.version='1.0.0'"
                            + " and v.plugin_id=(select plugin_id from skill.plugin_version where id=?)",
                        Long.class,
                        TENANT,
                        moved),
                "历史版本保留发现时来源");
    }

    private Map<String, Object> grantBody() {
        var body = new LinkedHashMap<String, Object>();
        body.put("granteeType", "WORKSPACE");
        body.put("granteeCode", "app_42");
        body.put("capabilities", versionCapabilities());
        return body;
    }

    @Test
    void nonAdminActorsAreRejectedAcrossDataPlane() {
        long sourceId = registerSource();
        var editor =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor-1")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("EDITOR"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(editor));
        assertEquals(
                403,
                assertThrows(PlatformException.class, () -> governance.createSource(sourceBody())).status(),
                "非 ADMIN 不得登记来源");
        assertEquals(
                403,
                assertThrows(PlatformException.class, () -> governance.triggerDiscovery(sourceId)).status(),
                "非 ADMIN 不得触发发现");
        assertEquals(
                403,
                assertThrows(PlatformException.class, () -> governance.listPlugins(null, null)).status(),
                "非 ADMIN 不得读取版本清单");
    }

    @Test
    void tenantBoundaryIsEnforcedOnEveryReadAndWrite() {
        long sourceId = registerSource();
        var otherTenant =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("other-admin")
                        .claim("tenant_id", "plugin-v321-other")
                        .claim("roles", List.of("ADMIN", "EDITOR"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(otherTenant));
        assertEquals(
                404,
                assertThrows(
                                PlatformException.class,
                                () -> governance.source(sourceId),
                                "跨租户读取必须 404")
                        .status());
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> governance.triggerDiscovery(sourceId)).status());
        // 跨租户授权版本同样不可达：先取回本租户视角的版本，换身份后必须 404。
        SecurityContextHolder.getContext().setAuthentication(owner());
        long versionId =
                governance.recordDiscoveredVersion(
                        sourceId,
                        pluginCode(),
                        "报表插件",
                        "9.9.9",
                        Map.<String, Object>of("name", "reporter"),
                        versionCapabilities());
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(otherTenant));
        assertEquals(
                404,
                assertThrows(PlatformException.class, () -> governance.enableVersion(versionId)).status());
    }

    private JwtAuthenticationToken owner() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("plugin-tester")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        return new JwtAuthenticationToken(jwt);
    }
}
