package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实PG读取投影；直接准备本例观测行，不执行预检、发布、远端pin或联合验收。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AppPinStatusTest {
    static JdbcTemplate db;
    static AppService apps;
    MockMvc mvc;
    String tenant;
    final String code = "pin_observation";
    final String release = "11111111-1111-4111-8111-111111111111";
    final String binding = "22222222-2222-4222-8222-222222222222";

    @BeforeAll
    static void database() {
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        apps = new AppService(db, new DataSourceTransactionManager(db.getDataSource()),
                "http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1");
    }

    @BeforeEach
    void setup() {
        tenant = "pin-read-" + UUID.randomUUID();
        actor(tenant);
        apps.create(Map.of("code", code, "name", "只读观测夹具", "type", "CHAT"));
        mvc = MockMvcBuilders.standaloneSetup(new AppController(apps, new TemplateService(db),
                new AuthorityProperties("test", "app", null, null, null, null, null, false, null)))
                .setControllerAdvice(new PlatformErrors()).build();
    }

    @AfterEach
    void cleanup() {
        // 只清本例UUID租户及其派生租户，绝不按固定共享tenant清理。
        for (String owned : List.of(tenant, tenant + "-other")) {
            db.update("delete from app.publish_pin where tenant_id=?", owned);
            db.update("delete from app.app_draft where tenant_id=?", owned);
            db.update("delete from app.lifecycle_log where tenant_id=?", owned);
            db.update("delete from app.application where tenant_id=?", owned);
        }
        SecurityContextHolder.clearContext();
    }

    void actor(String tenantId) {
        // 与既有MVC用例一致：已认证上下文前提；不声称本测试覆盖JWT验签过滤器。
        var jwt = Jwt.withTokenValue("verified-pin-reader").header("alg", "HS256")
                .subject("pin-reader").claim("tenant_id", tenantId).claim("roles", List.of("VIEWER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> request(String appCode, int expectedStatus) throws Exception {
        var response = mvc.perform(get("/admin/apps/{code}/pin-status", appCode)
                .param("tenantId", tenant).header("X-Tenant-Id", tenant)).andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus());
        return AppService.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }

    Map<String, Object> pin() throws Exception {
        var envelope = request(code, 200);
        assertEquals("OK", envelope.get("code"));
        return AppService.object(envelope.get("data"));
    }

    void observation() {
        // 此行只表示读取夹具，不是伪造发布成功；未创建任何app_version/publish_report/commit。
        db.update("insert into app.publish_pin(tenant_id,app_code,app_version_no,state,release_id,binding_id,reason,environment,"
                        + "last_pinned_release_id,last_pinned_binding_id,last_pinned_at) values(?,?,7,'PINNED',?,?,null,'joint-test',?,?,?::timestamptz)",
                tenant, code, release, binding, release, binding, "2026-10-01T00:00:00Z");
    }

    @Test
    void readsPersistedObservationAndHistoryWithoutPublishingOrMutatingIt() throws Exception {
        observation();
        var before = db.queryForMap("select * from app.publish_pin where tenant_id=? and app_code=?", tenant, code);
        var view = pin();
        assertEquals(Set.of("appCode", "versionNo", "state", "releaseId", "bindingId", "reason", "environment",
                "lastPinnedReleaseId", "lastPinnedBindingId", "lastPinnedAt", "updatedAt"), view.keySet());
        assertEquals(code, view.get("appCode")); assertEquals(7, ((Number) view.get("versionNo")).intValue());
        assertEquals("PINNED", view.get("state")); assertEquals(release, view.get("releaseId"));
        assertEquals(binding, view.get("bindingId")); assertEquals("joint-test", view.get("environment"));
        assertEquals("2026-10-01T00:00:00Z", view.get("lastPinnedAt"));
        assertEquals(before, db.queryForMap("select * from app.publish_pin where tenant_id=? and app_code=?", tenant, code));
        for (String state : List.of("FAILED", "SKIPPED", "DISABLED")) {
            db.update("update app.publish_pin set state=?,binding_id=null,reason='fixture-observation' where tenant_id=? and app_code=?", state, tenant, code);
            view = pin();
            assertEquals(state, view.get("state")); assertNull(view.get("bindingId"));
            assertEquals("fixture-observation", view.get("reason"));
            assertEquals(release, view.get("lastPinnedReleaseId")); assertEquals(binding, view.get("lastPinnedBindingId"));
        }
        for (String table : List.of("app_version", "publish_report", "publish_commit")) {
            assertEquals(0, db.queryForObject("select count(*) from app." + table + " where tenant_id=? and app_code=?", Integer.class, tenant, code));
        }
        assertEquals("DRAFT", apps.get(code).get("status"));
        assertEquals(1, db.queryForObject("select count(*) from app.lifecycle_log where tenant_id=? and app_code=?", Integer.class, tenant, code));
    }

    @Test
    void missingHistoryIsEmptyAndForeignOrAnonymousIdentityCannotReadObservation() throws Exception {
        assertEquals(Map.of(), pin());
        observation();
        actor(tenant + "-other");
        assertEquals("APP_NOT_FOUND", request(code, 404).get("code"));
        apps.create(Map.of("code", code, "name", "另一自有租户夹具", "type", "CHAT"));
        assertEquals(Map.of(), pin()); // URL参数和自报tenant头不能越过Actor租户。
        assertEquals("APP_NOT_FOUND", request("not_created", 404).get("code"));
        SecurityContextHolder.clearContext();
        assertEquals("SYS-AUTH-401", request(code, 401).get("code"));
        actor(tenant);
        assertEquals(binding, pin().get("bindingId"));
    }
}
