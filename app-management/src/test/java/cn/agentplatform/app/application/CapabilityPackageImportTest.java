package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformErrors;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Real app PostgreSQL and MVC; the authenticated context is a fixture, not a JWT signature test. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageImportTest {
    static JdbcTemplate db;
    static AnnotationConfigWebApplicationContext context;
    static MockMvc mvc;
    String tenant;

    @Configuration
    @EnableWebMvc
    @ComponentScan(
            basePackages = "cn.agentplatform.app",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "cn\\.agentplatform\\.app\\.(api\\.CapabilityPackageController|application\\.CapabilityPackageService|infra\\.CapabilityPackageRepository)"))
    static class WebConfig {
        @Bean JdbcTemplate database() { return db; }
        @Bean DataSourceTransactionManager transactions() {
            return new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()));
        }
        @Bean PlatformErrors errors() { return new PlatformErrors(); }
    }

    @BeforeAll
    static void database() {
        // Reuse the repository's provisioned 25432 / agent_platform_v12 / app fixture.
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(WebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @BeforeEach
    void actor() {
        tenant = "capability-intake-" + UUID.randomUUID();
        actor(tenant, "EDITOR");
    }

    @AfterEach
    void cleanup() {
        try {
            if (db.queryForObject("select to_regclass('app.capability_package_version') is not null", Boolean.class))
                db.update("delete from app.capability_package_version where tenant_id in (?,?)", tenant, tenant + "-other");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    static void actor(String tenantId, String role) {
        var jwt = Jwt.withTokenValue("verified-test").header("alg", "HS256")
                .subject("intake-editor").claim("tenant_id", tenantId)
                .claim("roles", List.of(role)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> request(Map<String, Object> body, int expectedStatus) throws Exception {
        var response = mvc.perform(post("/admin/capability-packages/import")
                        .contentType(MediaType.APPLICATION_JSON).content(Json.write(body)))
                .andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus(), "CAPABILITY_IMPORT_HTTP_STATUS");
        return AppService.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }

    Map<String, Object> imported(Map<String, Object> pkg) throws Exception {
        var response = request(Map.of("package", pkg), 200);
        assertEquals("OK", response.get("code"));
        return AppService.object(response.get("data"));
    }

    static Map<String, Object> declaration(String kind) {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("id", "primary", "kind", kind));
        switch (kind) {
            case "HTTP_SYNC" -> endpoint.put("endpoint", "http://127.0.0.1:1/declared-only");
            case "MCP" -> endpoint.put("serverRef", "declared-only-mcp");
            case "PROCESS" -> {
                endpoint.put("command", List.of("declared-only-worker"));
                endpoint.put("protocol", "algorithm-process/1");
            }
            default -> throw new IllegalArgumentException(kind);
        }
        var pkg = new LinkedHashMap<String, Object>();
        pkg.put("apiVersion", "platform/v1");
        pkg.put("kind", "CapabilityPackage");
        pkg.put("metadata", new LinkedHashMap<>(Map.of("name", "intake-" + kind.toLowerCase(Locale.ROOT).replace('_', '-'),
                "version", "1.0.0", "domain", "test", "owner", "test", "description", "Declaration only")));
        pkg.put("software", Map.of("system", "fixture-software", "environments", Map.of("dev", "declared-only")));
        pkg.put("interfaces", List.of(endpoint));
        pkg.put("tools", List.of(new LinkedHashMap<>(Map.of("id", "fixture-tool", "binding", "primary", "exposure", "READ_ONLY"))));
        pkg.put("identity", Map.of("subjectMapping", "declared-only"));
        pkg.put("verification", Map.of("connection", true, "contract", true, "model", true,
                "evaluation", true, "safety", true, "concurrency", true));
        pkg.put("operations", Map.of());
        return pkg;
    }

    @Test
    void declarationsPersistWithoutClaimingBindingsOrCreatingAppsAndRetryReusesIdentity() throws Exception {
        for (String kind : List.of("HTTP_SYNC", "MCP", "PROCESS")) {
            var pkg = declaration(kind);
            var first = imported(pkg);
            assertEquals(false, first.get("reused"));
            assertEquals("UNRESOLVED", first.get("bindingStatus"));
            assertEquals("NOT_EVALUATED", first.get("verificationStatus"));
            assertEquals(false, first.get("appCreated"));
            assertEquals(List.of(), first.get("applications"));
            assertEquals(Digest.sha256(Json.canonical(pkg)), first.get("sha256"));
            var reversed = new LinkedHashMap<String, Object>();
            var keys = new ArrayList<>(pkg.keySet());
            Collections.reverse(keys);
            keys.forEach(key -> reversed.put(key, pkg.get(key)));
            var retry = imported(reversed);
            assertEquals(true, retry.get("reused"));
            assertEquals(first.get("packageVersionId"), retry.get("packageVersionId"));
            String stored = db.queryForObject("select declaration::text from app.capability_package_version where tenant_id=? and id=?",
                    String.class, tenant, first.get("packageVersionId"));
            assertEquals(Json.tree(pkg), Json.read(stored));
        }
        assertEquals(3, db.queryForObject("select count(*) from app.capability_package_version where tenant_id=?", Integer.class, tenant));
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id=?", Integer.class, tenant));
    }

    @Test
    void sameVersionDifferentContentConflictsAndPreservesOriginal() throws Exception {
        var pkg = declaration("HTTP_SYNC");
        var first = imported(pkg);
        AppService.object(pkg.get("metadata")).put("description", "Changed declaration");
        assertEquals("CAPABILITY_VERSION_CONFLICT", request(Map.of("package", pkg), 409).get("code"));
        assertEquals(first.get("sha256"), db.queryForObject("select package_sha256 from app.capability_package_version where tenant_id=? and id=?",
                String.class, tenant, first.get("packageVersionId")));
        AppService.object(pkg.get("metadata")).put("version", "1.0.1");
        assertNotEquals(first.get("packageVersionId"), imported(pkg).get("packageVersionId"));
        assertEquals(2, db.queryForObject("select count(*) from app.capability_package_version where tenant_id=?", Integer.class, tenant));
    }

    @Test
    void equalPackageIdentityIsIndependentForEachAuthenticatedTenant() throws Exception {
        var first = imported(declaration("MCP"));
        actor(tenant + "-other", "EDITOR");
        var other = imported(declaration("MCP"));
        assertEquals(false, other.get("reused"));
        assertNotEquals(first.get("packageVersionId"), other.get("packageVersionId"));
        assertEquals(first.get("sha256"), other.get("sha256"));
        actor(tenant, "EDITOR");
        assertEquals(first.get("packageVersionId"), imported(declaration("MCP")).get("packageVersionId"));
    }

    @Test
    void missingIdentityReaderAndBodyTenantCannotAuthorizeImport() throws Exception {
        SecurityContextHolder.clearContext();
        assertEquals("SYS-AUTH-401", request(Map.of("package", declaration("HTTP_SYNC")), 401).get("code"));
        actor(tenant, "READER");
        assertEquals("SYS-APP-4031", request(Map.of("package", declaration("HTTP_SYNC")), 403).get("code"));
        actor(tenant, "EDITOR");
        assertEquals("INVALID_INPUT", request(Map.of("package", declaration("HTTP_SYNC"), "tenantId", tenant + "-other"), 400).get("code"));
        assertEquals(0, db.queryForObject("select count(*) from app.capability_package_version where tenant_id in (?,?)", Integer.class, tenant, tenant + "-other"));
    }

    @Test
    void invalidSchemaReferencesCredentialsAndApplicationPlanAreRejectedBeforePersistence() throws Exception {
        var pkg = declaration("HTTP_SYNC");
        AppService.object(pkg.get("metadata")).remove("version");
        assertEquals("CAPABILITY_DECLARATION_INVALID", request(Map.of("package", pkg), 400).get("code"));
        pkg = declaration("HTTP_SYNC");
        AppService.objects(pkg.get("tools")).getFirst().put("binding", "missing");
        assertEquals("CAPABILITY_DECLARATION_INVALID", request(Map.of("package", pkg), 400).get("code"));
        pkg = declaration("HTTP_SYNC");
        pkg.put("identity", Map.of("subjectMapping", "test", "guard", Map.of("attributes", Map.of("password", List.of("plaintext-fixture")))));
        assertEquals("CAPABILITY_DECLARATION_INVALID", request(Map.of("package", pkg), 400).get("code"));
        pkg = declaration("HTTP_SYNC");
        pkg.put("identity", Map.of("subjectMapping", "test", "password", Map.of("value", "plaintext-fixture")));
        var wrappedCredential = request(Map.of("package", pkg), 400);
        assertEquals("CAPABILITY_DECLARATION_INVALID", wrappedCredential.get("code"));
        assertEquals("identity 中的凭据只能使用 secret:// 或 cred:// 引用", wrappedCredential.get("message"));
        for (String endpoint : List.of("http://user:plaintext@127.0.0.1:1/test",
                "http://user%3Aplaintext@127.0.0.1:1/test",
                "http://user%3Aplaintext%40127.0.0.1:1/test",
                "http://127.0.0.1:1/test?api%5Fkey=plaintext-fixture")) {
            pkg = declaration("HTTP_SYNC");
            AppService.objects(pkg.get("interfaces")).getFirst().put("endpoint", endpoint);
            assertEquals("CAPABILITY_DECLARATION_INVALID", request(Map.of("package", pkg), 400).get("code"));
        }
        assertEquals("CAPABILITY_BINDING_REQUIRED", request(Map.of("package", declaration("PROCESS"), "applications", List.of(Map.of("appCode", "notCreated"))), 409).get("code"));
        assertEquals(0, db.queryForObject("select count(*) from app.capability_package_version where tenant_id=?", Integer.class, tenant));
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id=?", Integer.class, tenant));
    }
}
