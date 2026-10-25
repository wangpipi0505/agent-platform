package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.SkillClients;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class OpenApiImportPersistenceTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    SkillCatalog catalog;
    OpenApiImports imports;
    String tenant = "openapi_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    static void migrate() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
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
    void prepare() {
        actor(tenant);
        catalog = new SkillCatalog(db, manager, mock(SkillClients.class), "http://unused");
        imports = new OpenApiImports(catalog);
    }

    void actor(String tenantId) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("import-tester")
                        .claim("tenant_id", tenantId)
                        .claim("roles", List.of("EDITOR"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clean() {
        actor(tenant);
        db.update("delete from skill.skill_version where tenant_id=?", tenant);
        db.update("delete from skill.skill where tenant_id=?", tenant);
        SecurityContextHolder.clearContext();
    }

    @Test
    void persistsGeneratedSixDimensionsSourceDigestAndEditableDraftWithoutPublication() {
        var request = OpenApiImportsTest.request(OpenApiImportsTest.document());
        var manual =
                SkillCatalog.object(SkillCatalog.object(request.get("overrides")).get("readItem"));
        manual.put(
                "guard", Map.of("roles", List.of("READER"), "attributes", Map.of("id", List.of())));
        var response = imports.create(request);
        var draft = ((List<Map<String, Object>>) response.get("drafts")).getFirst();
        assertEquals("DRAFT", draft.get("status"));
        assertNull(draft.get("currentVersion"));
        var stored = catalog.version("read_item", 1);
        assertEquals(
                Digest.sha256(OpenApiImportsTest.document()),
                SkillCatalog.object(stored.get("importSource")).get("documentSha256"));
        assertEquals(List.of("id"), SkillCatalog.object(stored.get("httpMapping")).get("path"));
        assertEquals(Digest.sha256(Json.canonical(stored.get("definition"))), stored.get("sha256"));
        assertEquals(
                "CONNECTION_TEST_REQUIRED",
                assertThrows(PlatformException.class, () -> catalog.publish("read_item", 1))
                        .code());
        var edited = new LinkedHashMap<>(SkillCatalog.object(stored.get("definition")));
        edited.put("description", "人工完善了能力说明、适用与不适用范围，例：id=42");
        edited.remove("importSource");
        edited.remove("httpMapping");
        var saved = catalog.save("read_item", edited);
        assertEquals(1, saved.get("versionNo"));
        assertEquals("DRAFT", saved.get("status"));
        assertEquals(stored.get("importSource"), saved.get("importSource"));
        assertEquals(stored.get("httpMapping"), saved.get("httpMapping"));
        assertNotEquals(stored.get("sha256"), saved.get("sha256"));
        var incompatible = new LinkedHashMap<>(edited);
        incompatible.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
        assertEquals(
                "INVALID_HTTP_MAPPING",
                assertThrows(PlatformException.class, () -> catalog.save("read_item", incompatible))
                        .code());
        var forged = new LinkedHashMap<>(edited);
        forged.put(
                "importSource",
                Map.of(
                        "kind",
                        "OPENAPI",
                        "operationId",
                        "different",
                        "method",
                        "GET",
                        "path",
                        "/other",
                        "documentSha256",
                        "b".repeat(64)));
        assertEquals(
                "IMPORT_SOURCE_IMMUTABLE",
                assertThrows(PlatformException.class, () -> catalog.save("read_item", forged))
                        .code());
        actor(tenant + "_other");
        assertEquals(
                "SKILL_NOT_FOUND",
                assertThrows(PlatformException.class, () -> catalog.get("read_item", null)).code());
        actor(tenant);
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.connection_test where tenant_id=?",
                        Integer.class,
                        tenant));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.acceptance where tenant_id=?",
                        Integer.class,
                        tenant));
    }
}
