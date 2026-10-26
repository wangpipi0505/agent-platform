package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.model.api.ModelController;
import cn.agentplatform.model.infra.FixtureEgressGuard;
import cn.agentplatform.model.infra.FixtureViewMarker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AP-S06 失败路径先行：fixture 演示数据隔离。
 * 设计不变量——生产/默认模式（agent.demo-mode=false）下：
 * 1) 禁止新建 fixture 命名约定的 provider/entry；
 * 2) fixture 数据不参与真实调用选择（chat/measure/freeze/validate 一律 409）；
 * 3) provider/entry 管理面回包必须带 source=FIXTURE 标记；
 * 4) 清理动作仅命中 fixture 行且写审计，托管数据不可被误删。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class FixtureIsolationTest {
    static JdbcTemplate db;
    static String tenant;
    Actor admin = new Actor(tenant, "fixture-admin", Set.of("ADMIN"), Map.of());
    Actor editor = new Actor(tenant, "fixture-editor", Set.of("EDITOR"), Map.of());

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("model").defaultSchema("model").load().migrate();
        db = new JdbcTemplate(ds);
        tenant = "fixture-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (db == null) return;
        // 测试行按租户硬清理；binding_snapshot/call_log 仅由本用例创建，先删引用再删实体。
        db.update(
                "delete from model.binding_snapshot where tenant_id=?", tenant);
        db.update("delete from model.call_log where tenant_id=?", tenant);
        db.update(
                "delete from model.entry e using model.provider p where p.id=e.provider_id and"
                    + " p.tenant_id=?",
                tenant);
        db.update("delete from model.provider where tenant_id=?", tenant);
        db.update("delete from model.fixture_audit where tenant_id=?", tenant);
    }

    private long provider(String code) {
        return db.queryForObject(
                "insert into model.provider(tenant_id,code,name,base_url,trust_labels,status,created_by)"
                        + " values(?,?,?,?,'[]'::jsonb,'ENABLED',?) returning id",
                Long.class,
                tenant,
                code,
                "测试供应商",
                "http://127.0.0.1:9/v1",
                "fixture-admin");
    }

    private long entry(long providerId) {
        return db.queryForObject(
                "insert into model.entry(tenant_id,provider_id,model_name,display_name,context_window,"
                        + "max_output_tokens,capabilities,status,created_by)"
                        + " values(?,?,'test-model','测试模型',8,4,'[\"chat\"]'::jsonb,'ENABLED',?)"
                        + " returning id",
                Long.class,
                tenant,
                providerId,
                "fixture-admin");
    }

    private long binding(long entryId, long providerId, String sha) {
        db.update(
                "insert into model.binding_snapshot(tenant_id,model_entry_id,provider_id,sha256,snapshot)"
                        + " values(?,?,?,?,?::jsonb) on conflict(tenant_id,sha256) do nothing",
                tenant,
                entryId,
                providerId,
                sha,
                "{\"id\":" + entryId + "}");
        return db.queryForObject(
                "select id from model.binding_snapshot where tenant_id=? and sha256=?",
                Long.class,
                tenant,
                sha);
    }

    private MockHttpServletRequest post(String uri, String body) {
        var request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    private record Outcome(MockHttpServletResponse response, boolean chained) {}

    private Outcome run(MockHttpServletRequest request, boolean demoMode) {
        var guard = new FixtureEgressGuard(db, demoMode);
        var response = new MockHttpServletResponse();
        var passed = new AtomicBoolean(false);
        try {
            guard.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        passed.set(true);
                        if (res instanceof MockHttpServletResponse mock) mock.getWriter().flush();
                    });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Outcome(response, passed.get());
    }

    @Test
    void fixtureCodeDetectionFollowsNamingConvention() {
        assertThat(FixturePolicy.isFixtureProviderCode("long_fixture_abc")).isTrue();
        assertThat(FixturePolicy.isFixtureProviderCode("frontend_fixture_provider")).isTrue();
        assertThat(FixturePolicy.isFixtureProviderCode("smoke_model_ac2a")).isTrue();
        assertThat(FixturePolicy.isFixtureProviderCode("deepseek_cloud")).isFalse();
        assertThat(FixturePolicy.isFixtureProviderCode("managed_prod_openai")).isFalse();
    }

    @Test
    void nonDemoModeRejectsFixtureProviderCreation() {
        String body =
                "{\"code\":\"fixture_create_guard\",\"name\":\"演示\",\"baseUrl\":\"http://127.0.0.1:9/v1\","
                        + "\"trustLabels\":[\"INTERNAL\"]}";
        var rejected = run(post("/admin/model-providers", body), false);
        assertThat(rejected.chained()).isFalse();
        assertThat(rejected.response().getStatus()).isEqualTo(409);
        try {
            assertThat(rejected.response().getContentAsString()).contains("SYS-MODEL-FIXTURE");
        } catch (java.io.UnsupportedEncodingException never) {
            throw new AssertionError(never);
        }

        // demo 模式放行（由下游校验兜底），生产模式对托管命名不拦截。
        assertThat(run(post("/admin/model-providers", body), true).chained()).isTrue();
        assertThat(
                        run(
                                        post(
                                                "/admin/model-providers",
                                                "{\"code\":\"managed_real\",\"name\":\"真实\","
                                                        + "\"baseUrl\":\"http://api.example.com/v1\","
                                                        + "\"trustLabels\":[\"INTERNAL\"]}"),
                                        false)
                                .chained())
                .isTrue();
    }

    @Test
    void nonDemoModeBlocksFixtureEgressButAllowsManagedEntries() {
        long fixtureProvider = provider("fixture_egress_guard");
        long fixtureEntry = entry(fixtureProvider);
        long managedProvider = provider("managed_cloud_probe");
        long managedEntry = entry(managedProvider);

        assertThat(
                        run(post("/internal/model/chat", "{\"modelEntryId\":" + fixtureEntry + "}"), false)
                                .chained())
                .isFalse();
        assertThat(
                        run(post("/internal/model/measure", "{\"modelEntryId\":" + fixtureEntry + "}"), false)
                                .chained())
                .isFalse();
        assertThat(
                        run(
                                        post("/internal/model-bindings/freeze", "{\"modelEntryId\":" + fixtureEntry + "}"),
                                        false)
                                .chained())
                .isFalse();

        // 冻结引用路径同样被拦截：binding_snapshot 指回 fixture 供应商。
        long bid = binding(fixtureEntry, fixtureProvider, "a".repeat(64));
        assertThat(
                        run(
                                        post(
                                                "/internal/model/chat",
                                                "{\"modelBindingRef\":{\"bindingId\":" + bid
                                                        + ",\"sha256\":\"" + "a".repeat(64) + "\"}}"),
                                        false)
                                .chained())
                .isFalse();

        // 托管条目、读取端点与 demo 模式不受影响。
        assertThat(
                        run(post("/internal/model/chat", "{\"modelEntryId\":" + managedEntry + "}"), false)
                                .chained())
                .isTrue();
        assertThat(
                        run(post("/internal/model-entries/" + fixtureEntry, ""), false).chained())
                .isTrue();
        assertThat(
                        run(post("/internal/model/chat", "{\"modelEntryId\":" + fixtureEntry + "}"), true)
                                .chained())
                .isTrue();
    }

    @Test
    void purgeRequiresAdminTouchesOnlyFixtureRowsAndWritesAudit() {
        long fixturePlain = provider("fixture_purge_plain");
        long fixturePlainEntry = entry(fixturePlain);
        long fixtureBound = provider("smoke_model_purge_bound");
        long fixtureBoundEntry = entry(fixtureBound);
        binding(fixtureBoundEntry, fixtureBound, "b".repeat(64));
        long managed = provider("managed_purge_keep");
        long managedEntry = entry(managed);

        var service = new ModelFixtureService(db, new FixturePolicy(false));
        assertThatThrownBy(() -> service.purge(editor))
                .isInstanceOfSatisfying(
                        PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));

        var summary = service.summary(admin);
        assertThat(summary.get("demoMode")).isEqualTo(false);
        // 汇总只统计 fixture 行：2 个演示供应商 + 托管行不计入。
        assertThat(((Number) summary.get("providerCount")).intValue()).isEqualTo(2);

        var result = service.purge(admin);
        assertThat(((Number) result.get("purged")).intValue()).isEqualTo(2);
        assertThat(((Number) result.get("entriesRemoved")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("entriesKept")).intValue()).isEqualTo(1);

        assertThat(
                        db.queryForObject(
                                "select deleted from model.provider where id=?", Boolean.class, fixturePlain))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "select status from model.provider where id=?", String.class, fixtureBound))
                .isEqualTo("DISABLED");
        assertThat(
                        db.queryForObject(
                                "select status from model.entry where id=?", String.class, fixtureBoundEntry))
                .isEqualTo("DISABLED");
        assertThat(
                        db.queryForList(
                                        "select 1 from model.entry where id=?",
                                        fixturePlainEntry)
                                .isEmpty())
                .isTrue();
        // 托管数据绝不可被清理误删。
        assertThat(
                        db.queryForObject(
                                "select deleted from model.provider where id=?", Boolean.class, managed))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "select status from model.entry where id=?", String.class, managedEntry))
                .isEqualTo("ENABLED");

        Integer audits =
                db.queryForObject(
                        "select count(*) from model.fixture_audit where tenant_id=? and action='PURGE_PROVIDERS'",
                        Integer.class,
                        tenant);
        assertThat(audits).isEqualTo(1);
        assertThat(((Number) service.summary(admin).get("providerCount")).intValue()).isEqualTo(0);
    }

    @Test
    void viewMarkerFlagsFixtureProvidersAndEntries() throws Exception {
        var marker = new FixtureViewMarker(new FixturePolicy(false));
        var providers =
                List.of(
                        Map.<String, Object>of(
                                "id", 1, "code", "fixture_marked", "name", "x", "baseUrl", "http://a"),
                        Map.<String, Object>of(
                                "id", 2, "code", "managed_marked", "name", "y", "baseUrl", "http://b"));
        var annotated = marker.annotate(ApiResponse.ok(providers));
        assertThat(annotated.data() instanceof List<?> list && list.get(0) instanceof Map<?, ?> first
                        && "FIXTURE".equals(((Map<?, ?>) first).get("source")))
                .isTrue();
        var managedRow = (Map<?, ?>) ((List<?>) annotated.data()).get(1);
        assertThat(managedRow.get("source")).isEqualTo("MANAGED");

        var entries =
                List.<Object>of(
                        Map.<String, Object>of(
                                "id",
                                7,
                                "modelName",
                                "m",
                                "provider",
                                new LinkedHashMap<String, Object>(Map.of("id", 1, "code", "fixture_nested"))));
        var entryAnnotated = marker.annotate(ApiResponse.ok(entries));
        var entryRow = (Map<?, ?>) ((List<?>) entryAnnotated.data()).getFirst();
        assertThat(entryRow.get("source")).isEqualTo("FIXTURE");
        assertThat(((Map<?, ?>) entryRow.get("provider")).get("source")).isEqualTo("FIXTURE");

        // 无 code/baseUrl/provider 的回包（如创建结果、统计）不被改写。
        var untouched = marker.annotate(ApiResponse.ok(Map.of("id", 9, "status", "DISABLED")));
        assertThat(((Map<?, ?>) untouched.data()).containsKey("source")).isFalse();
        var plain =
                marker.annotate(
                        new ApiResponse<>("OK", "", Map.of("raw", true), null));
        assertThat(plain.data()).isEqualTo(Map.of("raw", true));
    }

    @Test
    void adviceOnlyTargetsModelController() throws Exception {
        var marker = new FixtureViewMarker(new FixturePolicy(false));
        var method = ModelController.class.getDeclaredMethod("providers");
        assertThat(marker.supports(new org.springframework.core.MethodParameter(method, -1), null))
                .isTrue();
        var foreign = Object.class.getDeclaredMethod("toString");
        assertThat(marker.supports(new org.springframework.core.MethodParameter(foreign, -1), null))
                .isFalse();
    }
}
