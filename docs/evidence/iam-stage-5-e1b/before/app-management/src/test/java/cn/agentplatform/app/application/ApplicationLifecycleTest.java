package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.agentplatform.app.api.AppController;
import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

/**
 * HTTP contracts backed by the independent PostgreSQL test database, including tenant isolation.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ApplicationLifecycleTest {
    static AppService apps;
    static JdbcTemplate db;
    MockMvc mvc;
    String tenant;
    String code;

    @BeforeAll
    static void database() {
        PublishTransactionTest.database();
        apps = PublishTransactionTest.apps;
        db = PublishTransactionTest.db;
    }

    @BeforeEach
    void setup() {
        tenant = "lifecycle-" + UUID.randomUUID();
        code = "source";
        actor(tenant, "EDITOR");
        apps.create(
                Map.of(
                        "code",
                        code,
                        "name",
                        "Source",
                        "type",
                        "FLOW",
                        "domain",
                        "finance",
                        "bizCategory",
                        "report",
                        "tags",
                        List.of("daily")));
        mvc =
                MockMvcBuilders.standaloneSetup(new AppController(apps, new TemplateService(db)))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    void actor(String tenantId, String role) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("lifecycle-editor")
                        .claim("tenant_id", tenantId)
                        .claim("roles", List.of(role))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> publish() {
        var app = apps.get(code);
        String id = "pc_" + UUID.randomUUID();
        db.update(
                "insert into"
                    + " app.publish_report(tenant_id,id,app_draft_id,app_code,draft_rev,candidate_sha256,eval_report_id,checks,passed,expires_at)"
                    + " values(?,?,?,?,?,?,'report-evidence','[]',true,now()+interval '10"
                    + " minutes')",
                tenant,
                id,
                app.get("draftId"),
                code,
                app.get("draftRev"),
                app.get("candidateSha256"));
        return apps.commit(
                code,
                Map.of(
                        "precheckId",
                        id,
                        "draftRev",
                        app.get("draftRev"),
                        "candidateSha256",
                        app.get("candidateSha256"),
                        "idempotencyRequestNo",
                        id));
    }

    Object data(MockHttpServletRequestBuilder request) throws Exception {
        String body =
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return AppService.object(Json.read(body, Map.class)).get("data");
    }

    MockHttpServletRequestBuilder copy(String target) {
        return post("/admin/apps/{code}/copy", code)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Json.write(Map.of("code", target, "name", "Copied")));
    }

    int count(String table, String target) {
        return db.queryForObject(
                "select count(*) from app." + table + " where tenant_id=? and app_code=?",
                Integer.class,
                tenant,
                target);
    }

    @Test
    void publishedApplicationCopyResetsPublicationAndKeepsCurrentDraftInputs() throws Exception {
        publish();
        var config =
                Map.of(
                        "modelBinding",
                        Map.of("modelEntryId", 41),
                        "skillRefs",
                        List.of(Map.of("code", "search", "version", 2)));
        apps.save(code, Map.of("draftRev", 1, "config", config), false);
        var definition = Map.of("inputs", List.of(Map.of("name", "question", "type", "string")));
        apps.save(
                code,
                Map.of("draftRev", 2, "definition", definition, "layout", Map.of("zoom", 2)),
                true);
        var source = apps.get(code);
        db.update(
                "insert into app.candidate_model_snapshot(tenant_id,app_code,draft_rev,"
                        + "candidate_sha256,model_refs) values(?,?,?,?, '[]')",
                tenant,
                code,
                source.get("draftRev"),
                source.get("candidateSha256"));
        db.update(
                "insert into app.integration_credential(tenant_id,app_code,biz_system_code,"
                        + "secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by)"
                        + " values(?,?,'partner','secret_ref:test',?,'ACTIVE','{}','[]','tester')",
                tenant,
                code,
                "a".repeat(64));

        var result = AppService.object(data(copy("copied")));
        assertEquals("DRAFT", result.get("status"));
        assertNull(result.get("currentVersionNo"));
        assertEquals(1, ((Number) result.get("draftRev")).intValue());
        assertEquals(1, ((Number) result.get("draftNo")).intValue());
        for (String field :
                List.of("config", "definition", "layout", "domain", "bizCategory", "tags"))
            assertEquals(source.get(field), result.get(field), field);
        assertEquals(config, result.get("config"));
        for (String table :
                List.of(
                        "app_version",
                        "publish_report",
                        "publish_commit",
                        "candidate_model_snapshot",
                        "integration_credential",
                        "skill_reference")) assertEquals(0, count(table, "copied"), table);
        assertEquals(source, apps.get(code));
        var logs = AppService.objects(data(get("/admin/apps/copied/lifecycle-logs")));
        var detail = AppService.object(logs.getFirst().get("detail"));
        assertEquals("COPY", logs.getFirst().get("action"));
        assertEquals(code, detail.get("sourceAppCode"));
        assertEquals(source.get("draftRev"), ((Number) detail.get("sourceDraftRev")).longValue());
        assertEquals(1, detail.get("sourceCurrentVersionNo"));
    }

    @Test
    void copySupportsPublishedDisabledAndArchivedSources() throws Exception {
        publish();
        data(copy("publishedCopy"));
        apps.lifecycle(code, "disable", Map.of());
        data(copy("disabledCopy"));
        apps.lifecycle(code, "archive", Map.of());
        assertEquals("DRAFT", AppService.object(data(copy("archivedCopy"))).get("status"));
    }

    @Test
    void duplicateOrInvalidCopyIdentityReturnsBusinessErrorWithoutPartialDraft() throws Exception {
        mvc.perform(copy(code)).andExpect(status().isConflict());
        mvc.perform(copy("invalid.code")).andExpect(status().isBadRequest());
        mvc.perform(
                        post("/admin/apps/source/copy")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":12,\"name\":\"Copy\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(1, apps.list().size());
    }

    @Test
    void readerCannotCopyAndOtherTenantCannotFindSource() throws Exception {
        actor(tenant, "READER");
        mvc.perform(copy("blocked")).andExpect(status().isForbidden());
        actor("other-" + tenant, "EDITOR");
        mvc.perform(copy("blocked")).andExpect(status().isNotFound());
        assertTrue(apps.list().isEmpty());
    }

    @Test
    void listFiltersAreCombinedAndArchivedNeedsExplicitStatus() throws Exception {
        publish();
        apps.lifecycle(code, "disable", Map.of());
        apps.lifecycle(code, "archive", Map.of());
        apps.create(Map.of("code", "another", "name", "Another", "type", "CHAT"));
        assertTrue(AppService.objects(data(get("/admin/apps").param("q", "Source"))).isEmpty());
        var request =
                get("/admin/apps")
                        .param("q", "sOuRcE")
                        .param("code", code)
                        .param("domain", "finance")
                        .param("bizCategory", "report")
                        .param("tag", "daily")
                        .param("status", "ARCHIVED");
        assertEquals(1, AppService.objects(data(request)).size());
        assertTrue(
                AppService.objects(
                                data(
                                        get("/admin/apps")
                                                .param("status", "ARCHIVED")
                                                .param("tag", "missing")))
                        .isEmpty());
        assertTrue(AppService.objects(data(get("/admin/apps").param("q", "%"))).isEmpty());
        assertTrue(AppService.objects(data(get("/admin/apps").param("code", "sour"))).isEmpty());
        mvc.perform(get("/admin/apps").param("status", "UNRECOGNIZED"))
                .andExpect(status().isBadRequest());
        actor("other-" + tenant, "READER");
        assertTrue(
                AppService.objects(data(get("/admin/apps").param("status", "ARCHIVED"))).isEmpty());
    }

    @Test
    void lifecycleLogsAreReadOnlyAndTenantScoped() throws Exception {
        publish();
        apps.lifecycle(code, "disable", Map.of());
        actor("other-" + tenant, "EDITOR");
        apps.create(Map.of("code", code, "name", "Other", "type", "CHAT"));
        actor(tenant, "READER");
        var logs = AppService.objects(data(get("/admin/apps/{code}/lifecycle-logs", code)));
        assertTrue(logs.stream().anyMatch(log -> "DISABLE".equals(log.get("action"))));
        assertTrue(logs.stream().allMatch(log -> "lifecycle-editor".equals(log.get("actor"))));
        actor("other-" + tenant, "READER");
        var other = AppService.objects(data(get("/admin/apps/{code}/lifecycle-logs", code)));
        assertFalse(other.stream().anyMatch(log -> "DISABLE".equals(log.get("action"))));
        mvc.perform(get("/admin/apps/missing/lifecycle-logs")).andExpect(status().isNotFound());
    }

    @Test
    void creationAndMetadataDraftTransitionAreRecordedInStableOrder() throws Exception {
        publish();
        apps.metadata(code, Map.of("draftRev", 1, "name", "Edited"));
        var logs = AppService.objects(data(get("/admin/apps/source/lifecycle-logs")));
        assertEquals(
                List.of("MODIFY", "PUBLISH", "CREATE"),
                logs.stream().map(log -> log.get("action")).toList());
        assertEquals("PUBLISHED", logs.getFirst().get("fromState"));
        assertEquals("DRAFT", logs.getFirst().get("toState"));
        assertTrue(
                ((Number) logs.get(0).get("id")).longValue()
                        > ((Number) logs.get(1).get("id")).longValue());
    }

    @Test
    void draftCopyIgnoresIdentityAndStateOverrides() throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("code", "safeCopy");
        request.put("name", "Safe Copy");
        request.put("tenantId", "injected-tenant");
        request.put("status", "PUBLISHED");
        request.put("currentVersionNo", 100);
        request.put("draftRev", 100);
        request.put("config", Map.of("injected", true));
        var result =
                AppService.object(
                        data(
                                post("/admin/apps/source/copy")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(Json.write(request))));
        assertEquals("safeCopy", result.get("code"));
        assertEquals("Safe Copy", result.get("name"));
        assertEquals("DRAFT", result.get("status"));
        assertNull(result.get("currentVersionNo"));
        assertEquals(Map.of(), result.get("config"));
        assertEquals(1, ((Number) result.get("draftRev")).intValue());
        assertTrue(apps.versions("safeCopy").isEmpty());
        actor("injected-tenant", "EDITOR");
        assertThrows(PlatformException.class, () -> apps.get("safeCopy"));
    }

    @Test
    void versionDiffUsesImmutableVersionsAndStructuredPaths() throws Exception {
        apps.save(
                code,
                Map.of(
                        "draftRev",
                        1,
                        "config",
                        Map.of(
                                "temperature",
                                0.1,
                                "a/b",
                                Map.of("~value", "old"),
                                "removed",
                                true)),
                false);
        publish();
        var secondConfig = new LinkedHashMap<String, Object>();
        secondConfig.put("temperature", 0.7);
        secondConfig.put("a/b", Map.of("~value", "new"));
        secondConfig.put("added", null);
        apps.save(code, Map.of("draftRev", 2, "config", secondConfig), false);
        publish();
        var before = apps.version(code, 1);
        var after = apps.version(code, 2);
        apps.save(code, Map.of("draftRev", 3, "config", Map.of("unpublished", true)), false);
        actor(tenant, "READER");
        var result =
                AppService.object(
                        data(
                                get("/admin/apps/{code}/versions/diff", code)
                                        .param("fromVersion", "1")
                                        .param("toVersion", "2")));
        var changes = AppService.objects(result.get("changes"));
        assertEquals(4, changes.size());
        assertTrue(
                changes.stream()
                        .anyMatch(
                                c ->
                                        "/config/a~1b/~0value".equals(c.get("path"))
                                                && "REPLACE".equals(c.get("operation"))
                                                && "old".equals(c.get("before"))
                                                && "new".equals(c.get("after"))));
        assertTrue(
                changes.stream()
                        .anyMatch(
                                c ->
                                        "/config/added".equals(c.get("path"))
                                                && "ADD".equals(c.get("operation"))
                                                && c.containsKey("after")
                                                && c.get("after") == null));
        assertEquals(before, apps.version(code, 1));
        assertEquals(after, apps.version(code, 2));
        assertTrue(
                AppService.objects(
                                AppService.object(
                                                data(
                                                        get(
                                                                        "/admin/apps/{code}/versions/diff",
                                                                        code)
                                                                .param("fromVersion", "1")
                                                                .param("toVersion", "1")))
                                        .get("changes"))
                        .isEmpty());
    }

    @Test
    void versionDiffCannotReadAnotherTenantOrApplicationVersion() throws Exception {
        publish();
        apps.create(Map.of("code", "otherApp", "name", "Other", "type", "CHAT"));
        code = "otherApp";
        publish();
        apps.save(code, Map.of("draftRev", 1, "config", Map.of("other", true)), false);
        publish();
        mvc.perform(
                        get("/admin/apps/source/versions/diff")
                                .param("fromVersion", "1")
                                .param("toVersion", "2"))
                .andExpect(status().isNotFound());
        actor("other-" + tenant, "EDITOR");
        apps.create(Map.of("code", "source", "name", "Hidden", "type", "CHAT"));
        mvc.perform(
                        get("/admin/apps/source/versions/diff")
                                .param("fromVersion", "1")
                                .param("toVersion", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionDiffRejectsNonPositiveVersionNumbers() throws Exception {
        mvc.perform(
                        get("/admin/apps/source/versions/diff")
                                .param("fromVersion", "0")
                                .param("toVersion", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/admin/apps/source/versions/diff")
                                .param("fromVersion", "1")
                                .param("toVersion", "-1"))
                .andExpect(status().isBadRequest());
    }
}
