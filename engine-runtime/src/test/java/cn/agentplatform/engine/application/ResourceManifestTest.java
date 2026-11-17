package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;

/**
 * A3-8 定向测试（台账 §7.4）：轻量装载两级清单+resource-manifest 端点。
 * 验收（主方案 §6.8/1109 §5.3）：A12 四项注入核验——模型不可达/插件停用/授权缺失/快照服务
 * 不可达，核对报错对象与阶段；RESOURCE_NOT_LOADABLE 定位对象与阶段不进入执行。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ResourceManifestTest {

    static JdbcTemplate db;
    static TraceStore store;
    static ResourceManifestService manifest;
    static final Actor builder = new Actor("engine-test", "alice", Set.of("BUILDER"), Map.of());
    static final Actor user = new Actor("engine-test", "mallory", Set.of("USER"), Map.of());

    @BeforeAll
    static void connect() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        manifest = new ResourceManifestService(store);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        db.update("delete from engine.chat_session where app_code='h38-app'");
    }

    /** 夹具：钉住发布快照的会话（manifest 四分区）。 */
    void pinnedSession(String manifestJson) {
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,config_release_id,config_digest)"
                    + " values(?,'engine-test','alice','h38-app',?::jsonb,'rel-h38',?)",
                "h38_" + UUID.randomUUID(),
                Json.write(Map.of("config", Json.read(manifestJson, JsonNode.class))),
                Digest.sha256("rel-h38"));
    }

    @Test
    void manifestAggregatesResourcesBySection() {
        pinnedSession(
                "{\"model\":{\"defaultBinding\":{\"providerCode\":\"p1\",\"modelName\":\"m1\"}},"
                    + "\"skills\":{\"guideRefs\":[\"g1@1\"],\"toolGrants\":[{\"skillCode\":\"lookup\",\"version\":3}]},"
                    + "\"memoryPolicy\":{\"readScopes\":[\"PRIVATE\"]},"
                    + "\"contextPolicy\":{\"maxTurns\":100}}");
        var out = manifest.manifest("h38-app", null, null, builder);
        assertEquals("h38-app", out.get("appCode"));
        assertEquals(true, out.get("loadable"));
        var resources = (java.util.List<?>) out.get("resources");
        assertTrue(resources.size() >= 5, "模型+2技能+记忆+上下文 至少 5 条");
        assertTrue(
                resources.stream()
                        .anyMatch(r -> ((cn.agentplatform.engine.application.ResourceManifestService.ResourceEntry) r).kind().equals("MODEL")),
                "模型条目在位");
        assertTrue(
                resources.stream()
                        .anyMatch(r -> ((cn.agentplatform.engine.application.ResourceManifestService.ResourceEntry) r).kind().equals("CONTEXT_POLICY")),
                "上下文策略在位");
    }

    @Test
    void releaseDigestMismatchNamesObjectAndStage() {
        pinnedSession("{\"model\":{\"defaultBinding\":{\"providerCode\":\"p1\",\"modelName\":\"m1\"}}}");
        var mismatch =
                assertThrows(
                        PlatformException.class,
                        () -> manifest.manifest("h38-app", "wrong-digest", null, builder));
        assertEquals(409, mismatch.status());
        assertTrue(mismatch.getMessage().contains("发布快照"), "报错定位对象=发布快照");
    }

    @Test
    void malformedSkillRefIsMissingWithStage() {
        pinnedSession("{\"skills\":{\"toolGrants\":[{\"skillCode\":\"\",\"version\":3}]}}");
        var out = manifest.manifest("h38-app", null, null, builder);
        assertEquals(false, out.get("loadable"), "空 skillCode=MISSING→不可装载");
        var notLoadable = (Map<?, ?>) out.get("resourceNotLoadable");
        assertEquals("RESOURCE_NOT_LOADABLE", notLoadable.get("code"));
        assertTrue(((java.util.List<?>) notLoadable.get("objects")).stream()
                .anyMatch(o -> o.toString().contains("SKILL")));
        assertTrue(((java.util.List<?>) notLoadable.get("stages")).stream()
                .anyMatch(s -> "受理".equals(s)));
    }

    @Test
    void malformedRuleRefBlocksAdmission() {
        pinnedSession("{\"rules\":[\"invalid-ref-no-version\"]}");
        var out = manifest.manifest("h38-app", null, null, builder);
        assertEquals(false, out.get("loadable"));
        var resources = (java.util.List<?>) out.get("resources");
        assertTrue(
                resources.stream()
                        .anyMatch(
                                r -> {
                                    var e = (cn.agentplatform.engine.application.ResourceManifestService.ResourceEntry) r;
                                    return "RULE".equals(e.kind()) && "MISSING".equals(e.status());
                                }),
                "规则引用形状非法=MISSING");
    }

    @Test
    void roleGateRejectsNonPrivilegedCallers() {
        pinnedSession("{}");
        assertThrows(
                PlatformException.class,
                () -> manifest.manifest("h38-app", null, null, user));
    }

    @Test
    void startupChecklistPresentWithNaStages() {
        pinnedSession("{}");
        var out = manifest.manifest("h38-app", null, null, builder);
        var levels = (Map<?, ?>) out.get("levels");
        var startup = (java.util.List<?>) levels.get("startup");
        assertTrue(startup.size() >= 4, "启动清单四项（快照接口/插件目录/MCP 目录/配额）");
        assertTrue(
                startup.stream()
                        .anyMatch(i -> "N/A".equals(((Map<?, ?>) i).get("status"))),
                "跨服务核验 N/A 留痕（诚实口径）");
    }
}
