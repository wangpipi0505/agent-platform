package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A3-5 发现执行器验证（库稿 §4.8 执行器段 + 台账 §7.4 A3-5 验收；照 A3-2 PG 测试先例）：
 * 认领原子性（并发恰一人成功、代次单调）、租约接管（过期后 gen2 接管、gen1 提交拒绝留痕）、
 * 失败保留目录（目录与 enable 状态不变、error 可查询）、成功展示差异（added/changed/removed
 * 落库+recordDiscoveredVersion 消费+批次大于上次成功）、旧批次拒绝（终态原因可查询）、
 * 来源停用拦截（trigger 拒绝/在途终态失败/目录保留）、enable 差异确认门、授权与停用闭环
 * （A10 机制级子集）、pulse 接管与续租。触发派发（202→异步执行）轮询验证，不依赖 Spring 调度。
 *
 * <p>专用库 agent_platform_v321b_test 自建（不触碰共享 dev 库与 v321_test），空库路径 drop
 * schema 后全链 migrate； leases 接管用 SQL 直接拨租约时间，不 sleep。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class PluginDiscoveryExecutorTest {
    private static final String DATABASE = "agent_platform_v321b_test";
    private static final String HOST = "jdbc:postgresql://127.0.0.1:25432/" + DATABASE;
    private static final String TENANT = "plugin-discovery-tests";

    private static JdbcTemplate db;
    private static DataSourceTransactionManager manager;

    private PluginGovernance governance;
    private StubScanner stub;
    private PluginDiscoveryExecutor executor;
    private PluginDiscoveryExecutor rival;

    /** 可编程桩：result=来源完整批次快照；failure=扫描异常；onScan=认领后扫描时触发的副作用。 */
    static final class StubScanner implements PluginSourceScanner {
        List<DiscoveredPlugin> result = List.of();
        RuntimeException failure;
        Runnable onScan;

        @Override
        public List<DiscoveredPlugin> scan(
                String tenantId, long sourceId, String sourceType, String location, long batchNo) {
            if (onScan != null) onScan.run();
            if (failure != null) throw failure;
            return result;
        }
    }

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
    void prepareActorAndServices() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("discovery-tester")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN", "EDITOR"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        governance = new PluginGovernance(db, manager);
        stub = new StubScanner();
        executor = new PluginDiscoveryExecutor(db, manager, governance, stub);
        rival = new PluginDiscoveryExecutor(db, manager, governance, stub);
    }

    // ---------------------------------------------------------------- 场景

    @Test
    void triggerAcceptsQueuedAndAsyncExecutorReachesTerminalState() throws Exception {
        long sourceId = registerSource();
        // 触发接线用默认演示桩执行器（其余用例注入可编程桩）
        var demoExecutor =
                new PluginDiscoveryExecutor(db, manager, governance, new DemoPluginSourceScanner());
        var accepted = demoExecutor.trigger(sourceId);
        assertEquals("QUEUED", accepted.get("status"), "受理返回 202 语义的 QUEUED");
        assertTrue(accepted.get("discoveryId").toString().matches("disc_[0-9a-f]{32}"));
        assertEquals(1L, ((Number) accepted.get("batchNo")).longValue());

        // 异步执行（实例内工作线程，无 Spring 调度依赖）：轮询终态
        String discoveryId = accepted.get("discoveryId").toString();
        long deadline = System.nanoTime() + 10_000_000_000L;
        String state = status(discoveryId);
        while (!"SUCCEEDED".equals(state) && !"FAILED".equals(state) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            state = status(discoveryId);
        }
        assertEquals("SUCCEEDED", state, "演示桩扫描应在异步线程内完成发布");
        // 目录消费 recordDiscoveredVersion：demo 桩插件入库且 manifest.demo=true
        var manifest =
                Json.read(
                        db.queryForObject(
                                "select v.manifest::text from skill.plugin_version v"
                                        + " join skill.plugin p on p.id=v.plugin_id"
                                        + " where p.tenant_id=? and p.code=?",
                                String.class,
                                TENANT,
                                "demo-" + sourceId),
                        Object.class);
        assertTrue(manifest instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("demo")), "demo:true 明示演示数据");
        assertNotNull(
                db.queryForObject(
                        "select last_discovered_at from skill.plugin_source where id=?",
                        Object.class,
                        sourceId),
                "成功发布回写来源 last_discovered_at");
    }

    @Test
    void concurrentClaimHasExactlyOneWinnerAndGenerationStaysMonotonic() throws Exception {
        long sourceId = registerSource();
        String discoveryId = queued(sourceId);
        var claimants =
                List.of(
                        executor,
                        rival,
                        new PluginDiscoveryExecutor(db, manager, governance, stub),
                        new PluginDiscoveryExecutor(db, manager, governance, stub));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Map<String, Object>>>();
            for (var claimant : claimants)
                results.add(pool.submit(() -> claimant.execute(discoveryId)));
            int winners = 0;
            for (var future : results) {
                Map<String, Object> view;
                try {
                    view = future.get();
                } catch (ExecutionException race) {
                    throw new AssertionError("认领不得抛异常（未取得认领应跳过）", race);
                }
                if (!Boolean.TRUE.equals(view.get("skipped"))) winners++;
            }
            assertEquals(1, winners, "原子认领：并发执行器恰一人取得认领并执行");
        }
        assertEquals("SUCCEEDED", status(discoveryId), "认领者完成发布");
        assertEquals(1L, generation(discoveryId), "并发认领代次只推进一次");
        // 空批次合法：差异全空、本来源目录无新增
        assertEquals(
                0L,
                db.queryForObject(
                        "select count(*) from skill.plugin p where p.tenant_id=? and p.source_id=?",
                        Long.class,
                        TENANT,
                        sourceId));
    }

    @Test
    void leaseTakeoverRejectsLateHolderWithoutOverwrite() {
        long sourceId = registerSource();
        String lateX = uniqueCode("lease");
        String takeoverY = uniqueCode("takeover");
        String lateZ = uniqueCode("replay");
        String discoveryId = queued(sourceId);
        // gen1 认领（借 claimOwner() 以本实例身份持有，模拟执行中失联）
        governance.markRunning(discoveryId, executor.claimOwner(), Instant.now().plusSeconds(60));
        assertEquals("RUNNING", status(discoveryId));
        assertEquals(1L, generation(discoveryId), "首次认领代次 1");

        expireLease(discoveryId);
        // 租约失效但代次仍匹配：提交前校验租约，失效持有者被拒且不写终态（行留待接管）
        stub.result = List.of(plugin(lateX));
        var expired =
                assertThrows(
                        PlatformException.class,
                        () -> executor.complete(discoveryId, 1, List.of(plugin(lateX))),
                        "租约失效的持有者不得提交");
        assertEquals("DISCOVERY_LEASE_EXPIRED", expired.code());
        assertEquals("RUNNING", status(discoveryId), "租约门拒绝不写终态，行留待接管");
        assertFalse(catalogCodes().contains(lateX), "失效提交不落目录");

        // 接管者执行期间（RUNNING gen2），迟到 gen1 提交：拒绝留痕不覆盖
        var lateRef = new AtomicReference<PlatformException>();
        stub.onScan =
                () -> {
                    try {
                        executor.complete(discoveryId, 1, List.of(plugin(lateZ)));
                    } catch (PlatformException failure) {
                        lateRef.set(failure);
                    }
                };
        stub.result = List.of(plugin(takeoverY));
        var second = rival.execute(discoveryId);
        assertEquals("SUCCEEDED", second.get("status"), "租约失效后他实例原子接管");
        assertEquals(2L, ((Number) second.get("claimGeneration")).longValue(), "接管代次递增");
        assertNotNull(lateRef.get(), "接管执行期间的迟到提交必须被拒");
        assertEquals("DISCOVERY_CLAIM_STALE", lateRef.get().code(), "认领代次不匹配拒绝");
        assertFalse(catalogCodes().contains(lateZ), "迟到结果不落目录");
        assertTrue(catalogCodes().contains(takeoverY), "接管者结果保留");
        assertEquals(
                takeoverY,
                db.queryForObject(
                        "select changes->'added'->0->>'plugin' from skill.plugin_discovery"
                                + " where discovery_id=?",
                        String.class,
                        discoveryId),
                "changes 为接管者批次");
        stub.onScan = null;
        // 终态后重放提交：同一批次终态只提交一次（状态机诊断优先）
        var replay =
                assertThrows(
                        PlatformException.class, () -> rival.complete(discoveryId, 2, List.of()));
        assertEquals("DISCOVERY_STATE", replay.code());
        // 迟到实例的失败声明同样拒绝（认领代次校验贯穿）
        var staleFail =
                assertThrows(
                        PlatformException.class,
                        () -> executor.fail(discoveryId, 1, "LATE", "迟到失败声明"));
        assertEquals("DISCOVERY_CLAIM_STALE", staleFail.code());
    }

    @Test
    void staleBatchIsRejectedWithQueryableReasonAndCatalogPreserved() {
        long sourceId = registerSource();
        String b1 = uniqueCode("staleb1");
        String b2 = uniqueCode("staleb2");
        String older = queued(sourceId); // 批次 1：ghost 认领后失联
        governance.markRunning(older, "ghost", Instant.now().plusSeconds(60));
        assertEquals(1L, generation(older));

        expireLease(older);
        String newer = queued(sourceId); // 批次 2：先发布成功
        stub.result = List.of(plugin(b2));
        assertEquals("SUCCEEDED", rival.execute(newer).get("status"));

        stub.result = List.of(plugin(b1));
        var takeover = rival.execute(older); // 接管旧批次并尝试发布
        assertEquals("FAILED", takeover.get("status"), "批次号不大于上次成功批次：拒绝发布");
        assertEquals("DISCOVERY_BATCH_STALE", errorCode(older));
        assertTrue(
                errorSummary(older).contains("保留原目录"),
                "旧批次终态原因可查询且声明保留原目录");
        assertFalse(catalogCodes().contains(b1), "旧批次结果不落目录");
        assertTrue(catalogCodes().contains(b2), "新批次目录保留");
        assertEquals("SUCCEEDED", status(newer), "新批次结果不受旧批次迟到影响");
    }

    @Test
    void failurePreservesCatalogEnabledStateAndErrorQueryable() {
        long sourceId = registerSource();
        String keep = uniqueCode("keep");
        String ok = queued(sourceId);
        stub.result = List.of(plugin(keep));
        assertEquals("SUCCEEDED", executor.execute(ok).get("status"));
        long versionId = versionIdOf(keep, "1.0.0");
        assertEquals("ENABLED", governance.enableVersion(versionId).get("status"));

        String bad = queued(sourceId);
        stub.failure = new RuntimeException("目录扫描超时");
        var failed = executor.execute(bad);
        assertEquals("FAILED", failed.get("status"), "扫描失败→失败终态");
        assertEquals("DISCOVERY_SCAN_FAILED", errorCode(bad));
        assertTrue(errorSummary(bad).contains("目录扫描超时"), "失败原因可展示");
        // 失败保留原目录与启用状态
        assertEquals("ENABLED", db.queryForObject(
                "select v.status from skill.plugin_version v where v.tenant_id=? and v.id=?",
                String.class, TENANT, versionId), "失败后目录与 enable 状态不变");
        assertEquals(
                1L,
                db.queryForObject(
                        "select count(*) from skill.plugin p where p.tenant_id=? and p.source_id=?",
                        Long.class,
                        TENANT,
                        sourceId),
                "失败批次不写目录");
        assertFalse(failed.containsKey("changes"), "失败终态不带差异");

        // QUEUED 行未认领也可失败（代次以 0 核验）
        String pending = queued(sourceId);
        executor.fail(pending, 0, "SCAN_TIMEOUT", "受理后即失败");
        assertEquals("FAILED", status(pending));
    }

    @Test
    void successfulDiffLandsAddedChangedRemovedAndConsumesRecordDiscoveredVersion() {
        long sourceId = registerSource();
        String diffX = uniqueCode("diff");
        String diffZ = uniqueCode("gone");
        // 批次 1：新增 X 1.0.0 与 Z 1.0.0
        String batch1 = queued(sourceId);
        stub.result = List.of(plugin(diffX), plugin(diffZ));
        var done1 = executor.execute(batch1);
        assertEquals("SUCCEEDED", done1.get("status"));
        var changes1 = (Map<?, ?>) done1.get("changes");
        assertEquals(
                List.of(diffX, diffZ),
                ((List<?>) changes1.get("added")).stream().map(e -> ((Map<?, ?>) e).get("plugin")).toList(),
                "批次 1 差异=两个新增");
        assertTrue(((List<?>) changes1.get("changed")).isEmpty());
        assertTrue(((List<?>) changes1.get("removed")).isEmpty());
        assertEquals(
                2L,
                db.queryForObject(
                        "select count(*) from skill.plugin where tenant_id=? and source_id=?",
                        Long.class,
                        TENANT,
                        sourceId),
                "目录更新消费 recordDiscoveredVersion：两插件入库");

        // 批次 2：X 升级 1.1.0（changed），Z 缺失（removed）
        String batch2 = queued(sourceId);
        stub.result = List.of(pluginUpgraded(diffX, "1.1.0"));
        var done2 = executor.execute(batch2);
        assertEquals("SUCCEEDED", done2.get("status"));
        assertEquals(2L, ((Number) done2.get("batchNo")).longValue(), "批次号大于上次成功批次");
        var changes2 = (Map<?, ?>) done2.get("changes");
        assertTrue(((List<?>) changes2.get("added")).isEmpty());
        assertEquals(1, ((List<?>) changes2.get("changed")).size(), "已知插件新版本号=changed");
        assertEquals(
                "1.0.0",
                ((Map<?, ?>) ((List<?>) changes2.get("changed")).getFirst()).get("previousVersion"),
                "changed 条目携带前版本");
        assertEquals(
                List.of(diffZ),
                ((List<?>) changes2.get("removed")).stream().map(e -> ((Map<?, ?>) e).get("plugin")).toList(),
                "扫描缺失的已知插件=removed");
        // 目录：X 双版本保留（快照不可变），latest=1.1.0；Z 行不删（removed 仅记录）
        assertEquals(
                "1.1.0",
                db.queryForObject(
                        "select v.version from skill.plugin p"
                            + " join skill.plugin_version v on v.id=p.latest_version_id"
                            + " where p.tenant_id=? and p.code=?",
                        String.class,
                        TENANT,
                        diffX));
        assertEquals(
                2L,
                db.queryForObject(
                        "select count(*) from skill.plugin_version v join skill.plugin p"
                            + " on p.id=v.plugin_id where p.tenant_id=? and p.code=?",
                        Long.class,
                        TENANT,
                        diffX));
        assertEquals(
                1L,
                db.queryForObject(
                        "select count(*) from skill.plugin_version v join skill.plugin p"
                            + " on p.id=v.plugin_id where p.tenant_id=? and p.code=?",
                        Long.class,
                        TENANT,
                        diffZ),
                "removed 不删历史行（快照不可变，停用走治理）");

        // 批次 3：来源恢复完整快照且全部同摘要（Z 回归为既有版本重放）→ 空差异仍成功
        String batch3 = queued(sourceId);
        stub.result = List.of(pluginUpgraded(diffX, "1.1.0"), plugin(diffZ));
        var done3 = executor.execute(batch3);
        assertEquals("SUCCEEDED", done3.get("status"));
        var changes3 = (Map<?, ?>) done3.get("changes");
        assertTrue(((List<?>) changes3.get("added")).isEmpty());
        assertTrue(((List<?>) changes3.get("changed")).isEmpty());
        assertTrue(((List<?>) changes3.get("removed")).isEmpty(), "同摘要重放=无差异");
        assertEquals(3L, ((Number) done3.get("batchNo")).longValue());

        // 差异展示过的版本可启用（批次 1 added、批次 2 changed）
        assertEquals("ENABLED", governance.enableVersion(versionIdOf(diffX, "1.0.0")).get("status"));
        assertEquals(
                "ENABLED", governance.enableVersion(versionIdOf(diffX, "1.1.0")).get("status"));
    }

    @Test
    void sourceDisableBlocksTriggerFailsInflightAndPreservesCatalog() {
        long sourceId = registerSource();
        String keep = uniqueCode("keep");
        String batch1 = queued(sourceId);
        stub.result = List.of(plugin(keep));
        assertEquals("SUCCEEDED", executor.execute(batch1).get("status"));

        // 停用来源：新触发被拦截（数据面 409）
        governance.disableSource(sourceId);
        var blocked =
                assertThrows(
                        PlatformException.class,
                        () -> governance.triggerDiscovery(sourceId),
                        "停用来源不得触发发现");
        assertEquals("PLUGIN_SOURCE_DISABLED", blocked.code());

        // 在途 QUEUED 行：执行时来源级停用拦截 → 失败终态原因，目录保留
        governance.enableSource(sourceId);
        String pending = queued(sourceId);
        governance.disableSource(sourceId);
        var failed = executor.execute(pending);
        assertEquals("FAILED", failed.get("status"));
        assertEquals("PLUGIN_SOURCE_DISABLED", errorCode(pending));
        assertTrue(errorSummary(pending).contains("保留原目录"));
        assertEquals(
                1L,
                db.queryForObject(
                        "select count(*) from skill.plugin p where p.tenant_id=? and p.source_id=?",
                        Long.class,
                        TENANT,
                        sourceId),
                "停用拦截不触碰目录");

        // 已认领后停用（扫描期间停用）：发布门终态失败，目录保留
        governance.enableSource(sourceId);
        String inflight = queued(sourceId);
        stub.result = List.of(plugin(uniqueCode("mid")));
        stub.onScan = () -> governance.disableSource(sourceId);
        var mid = executor.execute(inflight);
        assertEquals("FAILED", mid.get("status"), "认领后来源停用：发布门保留原目录并落终态原因");
        assertEquals("PLUGIN_SOURCE_DISABLED", errorCode(inflight));
        assertEquals(
                1L,
                db.queryForObject(
                        "select count(*) from skill.plugin p where p.tenant_id=? and p.source_id=?",
                        Long.class,
                        TENANT,
                        sourceId),
                "发布门停用拦截不落新插件");
        assertEquals(
                "FAILED",
                db.queryForObject(
                        "select status from skill.plugin_discovery where discovery_id=?",
                        String.class,
                        inflight));
        stub.onScan = null;
    }

    @Test
    void enableRequiresDiffShownOnceSourceHasDiscoveryHistory() {
        long skeletonSource = registerSource();
        String skeleton = uniqueCode("skeleton");
        String shown = uniqueCode("shown");
        String hidden = uniqueCode("hidden");
        // 骨架数据面直写（来源无发现历史）：保留 A3-2 语义，可启用
        long skeletonVersion =
                governance.recordDiscoveredVersion(
                        skeletonSource,
                        skeleton,
                        "骨架插件",
                        "1.0.0",
                        Map.of("name", skeleton),
                        List.of(capability(skeleton)));
        assertEquals("ENABLED", governance.enableVersion(skeletonVersion).get("status"));

        long sourceId = registerSource();
        String batch1 = queued(sourceId);
        stub.result = List.of(plugin(shown));
        assertEquals("SUCCEEDED", executor.execute(batch1).get("status"));
        // 执行器发布的版本随成功批次展示过差异 → 可启用
        assertEquals(
                "ENABLED", governance.enableVersion(versionIdOf(shown, "1.0.0")).get("status"));
        // 来源已有发现历史：骨架直写版本未随成功批次展示差异 → 拒绝启用
        long hiddenVersion =
                governance.recordDiscoveredVersion(
                        sourceId,
                        hidden,
                        "隐藏插件",
                        "1.0.0",
                        Map.of("name", hidden),
                        List.of(capability(hidden)));
        var blocked =
                assertThrows(
                        PlatformException.class,
                        () -> governance.enableVersion(hiddenVersion),
                        "差异未展示不能启用（主方案 §6.2 差异确认）");
        assertEquals("PLUGIN_DIFF_NOT_SHOWN", blocked.code());
    }

    @Test
    void grantRevokeAndDisableCloseTheGovernanceLoop() {
        long sourceId = registerSource();
        String loop = uniqueCode("loop");
        String batch1 = queued(sourceId);
        stub.result = List.of(plugin(loop));
        assertEquals("SUCCEEDED", executor.execute(batch1).get("status"));
        long versionId = versionIdOf(loop, "1.0.0");
        assertEquals("ENABLED", governance.enableVersion(versionId).get("status"));

        var granted =
                governance.grant(
                        versionId,
                        Map.of(
                                "granteeType",
                                "WORKSPACE",
                                "granteeCode",
                                "app_42",
                                "capabilities",
                                List.of(capability(loop))));
        assertEquals("GRANTED", granted.get("status"));
        var revoked = governance.revoke(granted.get("grantId").toString(), Map.of("reason", "工作区下线"));
        assertEquals("REVOKED", revoked.get("status"), "撤权=状态迁移（下次解析生效的数据面状态）");
        assertNotNull(revoked.get("revokedAt"));
        assertEquals("工作区下线", revoked.get("revokeReason"));
        // 撤权后同子集重新授权翻回 GRANTED（同库层行）
        var reGranted =
                governance.grant(
                        versionId,
                        Map.of(
                                "granteeType",
                                "WORKSPACE",
                                "granteeCode",
                                "app_42",
                                "capabilities",
                                List.of(capability(loop))));
        assertEquals("GRANTED", reGranted.get("status"));
        assertEquals(granted.get("grantId"), reGranted.get("grantId"));

        // 停用拦截后续调用（授权面）：停用后不可再授权
        governance.disableVersion(versionId, Map.of("reason", "疑似包含越权能力"));
        var grantBlocked =
                assertThrows(
                        PlatformException.class,
                        () ->
                                governance.grant(
                                        versionId,
                                        Map.of(
                                                "granteeType",
                                                "TENANT",
                                                "granteeCode",
                                                TENANT,
                                                "capabilities",
                                                List.of(capability(loop)))),
                        "停用版本不得授权");
        assertEquals("PLUGIN_STATE", grantBlocked.code());
        assertEquals(
                "DISABLED",
                db.queryForObject(
                        "select status from skill.plugin_version where tenant_id=? and id=?",
                        String.class,
                        TENANT,
                        versionId));
    }

    @Test
    void pulseTakesOverAbandonedRowsAndRenewsOwnLeases() {
        long sourceId = registerSource();
        String pulseX = uniqueCode("pulse");
        // 遗弃行接管：ghost 认领后租约过期 → 他实例 pulse 认领并发布
        String abandoned = queued(sourceId);
        governance.markRunning(abandoned, "ghost", Instant.now().plusSeconds(60));
        expireLease(abandoned);
        stub.result = List.of(plugin(pulseX));
        int processed = rival.pulse();
        assertTrue(processed >= 1, "pulse 认领并执行可执行行");
        assertEquals("SUCCEEDED", status(abandoned), "pulse 接管遗弃行完成发布");

        // 续租：自有临期 RUNNING 由原实例续租，不重扫不推进代次
        String own = queued(sourceId);
        governance.markRunning(own, executor.claimOwner(), Instant.now().plusSeconds(5));
        long genBefore = generation(own);
        executor.pulse();
        assertEquals("RUNNING", status(own), "未到期 RUNNING 由原实例续租而非接管");
        assertEquals(genBefore, generation(own), "续租不推进认领代次");
        var lease =
                db.queryForObject(
                        "select extract(epoch from (lease_until - now())) from skill.plugin_discovery"
                                + " where discovery_id=?",
                        Double.class,
                        own);
        assertNotNull(lease);
        assertTrue(lease > 100, "续租把租约推满（" + lease + "s），临期 5s 已续");

        // 未到期他人持有：pulse 不动
        String theirs = queued(sourceId);
        governance.markRunning(theirs, "other-instance", Instant.now().plusSeconds(60));
        rival.pulse();
        assertEquals("RUNNING", status(theirs), "他实例有效租约不被打扰");
        assertEquals(1L, generation(theirs), "有效租约下不产生接管代次");
    }

    @Test
    void publishConflictRollsBackAndFailsWithoutCatalogChange() {
        long sourceId = registerSource();
        String conflict = uniqueCode("conflict");
        String batch1 = queued(sourceId);
        stub.result = List.of(plugin(conflict));
        assertEquals("SUCCEEDED", executor.execute(batch1).get("status"));

        // 同版本异摘要：合并冲突 → 发布事务整体回滚 → 失败终态保留原目录
        String batch2 = queued(sourceId);
        stub.result =
                List.of(
                        new DiscoveredPlugin(
                                conflict,
                                "冲突插件",
                                "1.0.0",
                                Map.of("name", conflict, "tampered", true),
                                List.of(capability(conflict))));
        var failed = executor.execute(batch2);
        assertEquals("FAILED", failed.get("status"), "合并冲突按失败终态处置");
        assertEquals("PLUGIN_VERSION_CONFLICT", errorCode(batch2));
        assertEquals(
                "1.0.0",
                db.queryForObject(
                        "select v.version from skill.plugin p"
                            + " join skill.plugin_version v on v.id=p.latest_version_id"
                            + " where p.tenant_id=? and p.code=?",
                        String.class,
                        TENANT,
                        conflict),
                "目录保留批次 1 结果");
        assertEquals(
                1L,
                db.queryForObject(
                        "select count(*) from skill.plugin_version v join skill.plugin p"
                            + " on p.id=v.plugin_id where p.tenant_id=? and p.code=?",
                        Long.class,
                        TENANT,
                        conflict));
    }

    // ---------------------------------------------------------------- 工具

    private long registerSource() {
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "name", "内部插件源",
                                "sourceType", "DIRECTORY",
                                "location", "file:///srv/plugins"));
        body.put("code", "src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        return ((Number) governance.createSource(body).get("id")).longValue();
    }

    /** 数据面受理（无派发）：测试同步直调 execute，批次形状与 trigger 一致。 */
    private String queued(long sourceId) {
        return governance.triggerDiscovery(sourceId).get("discoveryId").toString();
    }

    /** 每次调用唯一的插件编码：插件编码租户内唯一（换源归并），用例间不得相互归并。 */
    private static String uniqueCode(String stem) {
        return stem + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 版本能力清单：一个 SKILL 能力，digest 由编码+版本确定性派生（授权子集可重建）。 */
    private static Map<String, Object> capability(String code) {
        return Map.of("type", "SKILL", "ref", code, "digest", Digest.sha256(code));
    }

    private static DiscoveredPlugin plugin(String code) {
        return new DiscoveredPlugin(
                code,
                "插件 " + code,
                "1.0.0",
                Map.of("name", code, "version", "1.0.0"),
                List.of(capability(code)));
    }

    private static DiscoveredPlugin pluginUpgraded(String code, String version) {
        return new DiscoveredPlugin(
                code,
                "插件 " + code,
                version,
                Map.of("name", code, "version", version),
                List.of(Map.of("type", "SKILL", "ref", code, "digest", Digest.sha256(code + "@" + version))));
    }

    /** 直接拨租约过期（不 sleep）：claim 条件的 lease_until<=now() 立即成立。 */
    private void expireLease(String discoveryId) {
        db.update(
                "update skill.plugin_discovery set lease_until=now()-interval '1 second'"
                        + " where discovery_id=?",
                discoveryId);
    }

    private String status(String discoveryId) {
        return db.queryForObject(
                "select status from skill.plugin_discovery where discovery_id=?",
                String.class,
                discoveryId);
    }

    private long generation(String discoveryId) {
        return db.queryForObject(
                "select claim_generation from skill.plugin_discovery where discovery_id=?",
                Long.class,
                discoveryId);
    }

    private String errorCode(String discoveryId) {
        return db.queryForObject(
                "select error_code from skill.plugin_discovery where discovery_id=?",
                String.class,
                discoveryId);
    }

    private String errorSummary(String discoveryId) {
        return db.queryForObject(
                "select error_summary from skill.plugin_discovery where discovery_id=?",
                String.class,
                discoveryId);
    }

    private long versionIdOf(String pluginCode, String version) {
        return db.queryForObject(
                "select v.id from skill.plugin_version v join skill.plugin p on p.id=v.plugin_id"
                        + " where v.tenant_id=? and p.code=? and v.version=?",
                Long.class,
                TENANT,
                pluginCode,
                version);
    }

    private List<String> catalogCodes() {
        return db.queryForList(
                "select code from skill.plugin where tenant_id=? order by code", String.class, TENANT);
    }
}
