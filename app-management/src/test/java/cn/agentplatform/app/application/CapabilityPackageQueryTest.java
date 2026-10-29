package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.agentplatform.app.domain.CapabilityPackage;
import cn.agentplatform.app.infra.CapabilityPackageRepository;
import cn.agentplatform.common.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 真实租户 PG 与管理面 HTTP；目录不把原声明或应用状态变成评测证据。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageQueryTest {
    static JdbcTemplate db;
    static AnnotationConfigWebApplicationContext context;
    static MockMvc mvc;
    String tenant;

    @Configuration @EnableWebMvc
    @ComponentScan(basePackages = "cn.agentplatform.app", useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "cn\\.agentplatform\\.app\\.(api\\.CapabilityPackageQueryController|application\\.CapabilityPackageQueryService|infra\\.CapabilityPackageQueryRepository)"))
    static class WebConfig {
        @Bean JdbcTemplate database() { return db; }
        @Bean PlatformErrors errors() { return new PlatformErrors(); }
    }

    @BeforeAll static void database() {
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void actor() {
        tenant = "capability-directory-" + UUID.randomUUID();
        CapabilityPackageImportTest.actor(tenant, "EDITOR");
    }
    @AfterEach void cleanup() {
        for (String id : List.of(tenant, tenant + "-other")) {
            db.update("delete from app.capability_app_binding where tenant_id=?", id);
            db.update("delete from app.app_draft where tenant_id=?", id);
            db.update("delete from app.lifecycle_log where tenant_id=?", id);
            db.update("delete from app.application where tenant_id=?", id);
            db.update("delete from app.capability_package_version where tenant_id=?", id);
        }
        SecurityContextHolder.clearContext();
    }
    long register(String version) {
        var declaration = CapabilityPackageImportTest.declaration("HTTP_SYNC");
        AppService.object(declaration.get("metadata")).put("version", version);
        var repository = new CapabilityPackageRepository(db, new DataSourceTransactionManager(db.getDataSource()));
        return ((Number) repository.register(CapabilityPackage.parse(declaration), Actor.current()).get("packageVersionId")).longValue();
    }
    Map<String, Object> request(String path, int status) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        return AppService.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }
    Map<String, Object> data(String path) throws Exception { return AppService.object(request(path, 200).get("data")); }

    @Test void unauthorizedAndRolelessReadsAreDeniedButViewerAndEditorMayRead() throws Exception {
        SecurityContextHolder.clearContext(); request("/admin/capability-packages", 401);
        CapabilityPackageImportTest.actor(tenant, "APPROVER"); request("/admin/capability-packages", 403);
        for (String role : List.of("VIEWER", "EDITOR", "ADMIN")) {
            CapabilityPackageImportTest.actor(tenant, role);
            assertEquals(List.of(), data("/admin/capability-packages").get("items"));
        }
    }
    @Test void listIsBoundedAndCursorDoesNotRepeatRowsOrLeakOtherTenants() throws Exception {
        long first = register("1.0.0"), second = register("1.0.1");
        CapabilityPackageImportTest.actor(tenant + "-other", "EDITOR"); long other = register("1.0.2");
        CapabilityPackageImportTest.actor(tenant, "VIEWER");
        var page = data("/admin/capability-packages?limit=1");
        var rows = AppService.objects(page.get("items"));
        assertEquals(second, ((Number) rows.getFirst().get("packageVersionId")).longValue());
        assertEquals(second, ((Number) page.get("nextBeforeId")).longValue());
        assertEquals(1, AppService.objects(data("/admin/capability-packages?limit=1&beforeId=" + second).get("items")).size());
        assertEquals(first, ((Number) AppService.objects(data("/admin/capability-packages?beforeId=" + second).get("items")).getFirst().get("packageVersionId")).longValue());
        request("/admin/capability-packages/" + other, 404);
        request("/admin/capability-packages?limit=101", 400);
        request("/admin/capability-packages?beforeId=0", 400);
        request("/admin/capability-packages/0", 400);
    }
    @Test void detailUsesActualSourceApplicationsAndNeverClaimsVerification() throws Exception {
        long id = register("1.0.0");
        var app = PublishTransactionTest.apps.create(Map.of("code", "directory_app", "name", "目录来源", "type", "FLOW"));
        db.update("insert into app.capability_app_binding(tenant_id,app_id,app_code,package_version_id,source) values(?,?,?,?,?::jsonb)",
            tenant, app.get("id"), app.get("code"), id, "{}");
        var detail = data("/admin/capability-packages/" + id);
        assertEquals("NOT_EVALUATED", detail.get("verificationStatus"));
        assertEquals("UNRESOLVED", detail.get("bindingStatus"));
        assertEquals("CapabilityPackage", AppService.object(detail.get("declaration")).get("kind"));
        var source = AppService.objects(detail.get("applications")).getFirst();
        assertEquals("directory_app", source.get("appCode")); assertEquals("DRAFT", source.get("status"));
        db.update("delete from app.capability_app_binding where tenant_id=?", tenant);
        assertEquals(List.of(), data("/admin/capability-packages/" + id).get("applications"));
        assertEquals(1, db.queryForObject("select count(*) from app.capability_package_version where tenant_id=?", Integer.class, tenant));
    }
    @Test void filtersTreatSearchAsLiteralAndOntologyAsDeclaredReferences() throws Exception {
        register("1.0.0");
        assertEquals(List.of(), data("/admin/capability-packages?q=%25").get("items"));
        assertEquals(List.of(), data("/admin/capability-packages?ontologySpace=other-space").get("items"));
        assertEquals(1, AppService.objects(data("/admin/capability-packages?q=fixture-software").get("items")).size());
    }
}
