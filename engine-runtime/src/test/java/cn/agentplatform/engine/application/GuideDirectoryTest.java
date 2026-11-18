package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.GuideDirectoryController;
import cn.agentplatform.engine.infra.EngineTestDatabase;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * R-18（A3-2 engine 侧 GUIDE 目录注册，台账 §7.6b）：engine 启动/按需经 RuntimeClients 拉
 * skill 服务 GUIDE 目录缓存；engine 侧可查（GET /v1/skills?kind=GUIDE 等价口径）；guideRefs
 * 消费路径（ResourceManifestService 存在性核验升级：缓存可用=OK/MISSING 实核，N/A=留痕）。
 * skill 服务不可达=N/A 留痕不阻塞（保留最后已知缓存），与卡面验收口径一致。
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class GuideDirectoryTest {

    static JdbcTemplate db;
    static TraceStore store;
    static final Actor builder = new Actor("engine-test", "alice", Set.of("BUILDER"), Map.of());

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
    }

    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        db.update("delete from engine.chat_session where app_code='h39-app'");
    }

    /** skill 服务桩：/admin/skills?kind=GUIDE 返回两条 GUIDE 公共视图（含当前版本契约）。 */
    static RuntimeClients skillStub(JsonNode directory, Atomic spoke) {
        return new RuntimeClients() {
            @Override
            public JsonNode get(String service, String path, Actor actor) {
                spoke.hit = true;
                if (service.equals("skill") && path.startsWith("/admin/skills")) {
                    if (directory == null)
                        throw new PlatformException(502, "SKILL_UNAVAILABLE", "故障注入：skill 服务不可达");
                    assertTrue(path.contains("kind=GUIDE"), "拉取必须带 kind=GUIDE 过滤: " + path);
                    return directory;
                }
                throw new AssertionError("意外出站: " + service + path);
            }
        };
    }

    /** 出站观测标记。 */
    static final class Atomic {
        boolean hit;
    }

    /** skill 服务 GUIDE 目录响应（公共视图形状：code/name/kind + 版本契约 definition）。 */
    static JsonNode directoryResponse() {
        return Json.read(
                "{\"data\":["
                        + "{\"code\":\"satellite-ops-guide\",\"name\":\"卫星操作指南\",\"kind\":\"GUIDE\","
                        + "\"status\":\"PUBLISHED\",\"currentVersionId\":11,\"currentVersion\":1,"
                        + "\"definition\":{\"name\":\"卫星操作指南\",\"description\":\"过境窗口与云量阈值操作口径\","
                        + "\"whenToUse\":\"规划观测序列时\",\"modelInvocable\":false,"
                        + "\"content\":\"# 卫星操作指南\\n过境窗口优先级…\"}},"
                        + "{\"code\":\"diff-report-guide\",\"name\":\"差异报告指南\",\"kind\":\"GUIDE\","
                        + "\"status\":\"PUBLISHED\",\"currentVersionId\":12,\"currentVersion\":1,"
                        + "\"definition\":{\"name\":\"差异报告指南\",\"description\":\"差异报告默认附置信度差值列\","
                        + "\"modelInvocable\":true,\"content\":\"# 差异报告指南\\n列结构…\"}}"
                        + "]}");
    }

    // === ① 拉取+缓存：refresh 后 engine 侧目录可查有数据（kind=GUIDE 过滤已出站） ==========

    @Test
    void refreshCachesGuideDirectoryAndEngineSideEndpointServesIt() throws Exception {
        var spoke = new Atomic();
        var directory = new GuideDirectory(skillStub(directoryResponse(), spoke));
        var view = directory.refresh(builder);
        assertTrue(spoke.hit, "拉取必须经 RuntimeClients 出站");
        assertEquals("OK", view.get("status"));
        assertEquals(2, ((Number) view.get("total")).intValue(), "engine 侧 GUIDE 目录有数据");
        assertEquals("OK", directory.health().get("status"));

        // engine 侧可查（等价口径）：GET /v1/skills?kind=GUIDE 返回缓存数据。
        var jwt =
                Jwt.withTokenValue("verified-internal-peer")
                        .header("alg", "HS256")
                        .subject("alice")
                        .claim("tenant_id", "engine-test")
                        .claim("roles", List.of("EDITOR"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(180))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var mvc =
                MockMvcBuilders.standaloneSetup(new GuideDirectoryController(directory))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        var response =
                mvc.perform(get("/v1/skills").param("kind", "GUIDE")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var body = Json.read(response.getContentAsString()).path("data");
        assertEquals("GUIDE", body.path("kind").asText());
        assertEquals(2, body.path("total").asInt());
        assertEquals(
                "satellite-ops-guide",
                body.path("skills").get(0).path("code").asText(),
                "engine 侧 GUIDE 目录条目在位");
        assertEquals(
                "过境窗口与云量阈值操作口径",
                body.path("skills").get(0).path("definition").path("description").asText());

        // kind 白名单：TOOL 不在 engine 侧目录承载（走工具环）。
        var rejected =
                mvc.perform(get("/v1/skills").param("kind", "TOOL")).andReturn().getResponse();
        assertEquals(400, rejected.getStatus());
        assertEquals("GUIDE_KIND_ONLY", Json.read(rejected.getContentAsString()).path("code").asText());
        SecurityContextHolder.clearContext();
    }

    // === ② skill 服务不可达：N/A 留痕不阻塞（refresh 不抛、旧缓存保留、可查仍 200） ========

    @Test
    void unreachableSkillServiceLeavesNaTrailAndKeepsLastKnownCache() {
        var spoke = new Atomic();
        var directory = new GuideDirectory(skillStub(directoryResponse(), spoke));
        directory.refresh(builder);
        assertEquals("OK", directory.health().get("status"));

        // 故障注入：skill 服务不可达（stub 返回 502）→ refresh 吞为 N/A，不抛异常、不清缓存。
        var broken = new GuideDirectory(skillStub(null, spoke));
        broken.refresh(builder); // 无历史缓存
        assertEquals("N/A", broken.health().get("status"));
        assertTrue(
                String.valueOf(broken.health().get("detail")).contains("SKILL_UNAVAILABLE"),
                "留痕含异常码: " + broken.health().get("detail"));
        assertEquals(0, broken.size());
        assertTrue(broken.view().get("detail").toString().contains("N/A"), "N/A 留痕在目录视图中可见");

        // 先 OK 后故障：保留最后已知目录（不放大空目录）。
        var degraded = new GuideDirectory(new RuntimeClients() {
            private boolean down;
            @Override
            public JsonNode get(String service, String path, Actor actor) {
                if (down) throw new PlatformException(502, "SKILL_UNAVAILABLE", "故障注入");
                down = true;
                return directoryResponse();
            }
        });
        degraded.refresh(builder);
        assertEquals(2, degraded.size(), "首拉成功");
        degraded.refresh(builder);
        assertEquals("N/A", degraded.health().get("status"));
        assertEquals(2, degraded.size(), "N/A 时保留最后已知缓存");
        assertFalse(degraded.available(), "缓存可用性=false：消费路径回落 N/A 留痕口径");
    }

    // === ③ 消费路径：manifest guideRefs 实核（缓存可用=OK/MISSING；N/A=留痕口径不变） =======

    @Test
    void manifestGuideRefsResolvedAgainstEngineSideDirectory() {
        var spoke = new Atomic();
        var directory = new GuideDirectory(skillStub(directoryResponse(), spoke));
        directory.refresh(builder);

        // 缓存可用：已注册 GUIDE 引用=OK；未注册引用=MISSING（阻断装载）。
        var wired = new ResourceManifestService(store);
        wired.guideDirectory(directory);
        pinnedSession(
                "{\"skills\":{\"guideRefs\":[\"satellite-ops-guide@1\",\"ghost-guide@1\"]}}");
        var out = wired.manifest("h39-app", null, null, builder);
        var resources = (List<?>) out.get("resources");
        var refs = new LinkedHashMap<String, String>();
        for (var r : resources) {
            var entry = (ResourceManifestService.ResourceEntry) r;
            if (entry.kind().equals("SKILL") && entry.ref().contains("guide"))
                refs.put(entry.ref(), entry.status());
        }
        assertEquals("OK", refs.get("satellite-ops-guide@1"), "已注册 GUIDE 引用缓存命中");
        assertEquals("MISSING", refs.get("ghost-guide@1"), "未注册 GUIDE 引用实核 MISSING");
        assertEquals(false, out.get("loadable"), "MISSING 引用阻断装载");
        var notLoadable = (Map<?, ?>) out.get("resourceNotLoadable");
        assertTrue(
                ((List<?>) notLoadable.get("objects")).stream()
                        .anyMatch(o -> o.toString().contains("ghost-guide@1")),
                "不可装载定位对象=未注册 GUIDE 引用");

        // 缓存 N/A（未注入/不可达）：保持既有 N/A 留痕口径，不放大装载判定。
        var legacy = new ResourceManifestService(store);
        pinnedSession("{\"skills\":{\"guideRefs\":[\"satellite-ops-guide@1\"]}}");
        var unchanged = legacy.manifest("h39-app", null, null, builder);
        assertEquals(true, unchanged.get("loadable"), "目录 N/A 时保持既有口径");
        assertTrue(
                ((List<?>) unchanged.get("resources"))
                        .stream()
                        .map(r -> (ResourceManifestService.ResourceEntry) r)
                        .anyMatch(
                                e ->
                                        e.ref().equals("satellite-ops-guide@1")
                                                && e.detail().contains("N/A")),
                        "N/A 留痕 detail 在位");
    }

    // === ④ 提示装配消费：SKILL_CATALOG 段正文投影（五字段摘要；N/A/空目录=空串不伪造） =======

    @Test
    void catalogSectionProjectsGuideEntriesForPromptAssembly() {
        var spoke = new Atomic();
        var directory = new GuideDirectory(skillStub(directoryResponse(), spoke));
        assertEquals("", directory.catalogSection(), "未拉取（N/A）=空串，不伪造目录");
        directory.refresh(builder);
        String section = directory.catalogSection();
        assertTrue(section.contains("satellite-ops-guide"), "条目 code 在段正文中");
        assertTrue(section.contains("过境窗口与云量阈值操作口径"), "五字段 description 在段正文中");
        assertTrue(section.contains("whenToUse: 规划观测序列时"), "whenToUse 摘要在段正文中");
        assertTrue(section.contains("modelInvocable: false"), "modelInvocable 摘要在段正文中");
    }

    /** 夹具：钉住发布快照的会话（manifest 载体，照 ResourceManifestTest 口径）。 */
    void pinnedSession(String manifestJson) {
        db.update(
                "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot,config_release_id,config_digest)"
                        + " values(?,'engine-test','alice','h39-app',?::jsonb,'rel-h39',?)",
                "h39_" + UUID.randomUUID(),
                Json.write(Map.of("config", Json.read(manifestJson, JsonNode.class))),
                Digest.sha256("rel-h39"));
    }
}
