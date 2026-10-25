package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.api.SkillController;
import cn.agentplatform.skill.application.*;

import jakarta.servlet.Filter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Dedicated PostgreSQL, Nimbus verified JWT chain and actual MCP wire protocol. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class McpManagementTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    McpAdapterTest.Peer peer;
    SkillCatalog catalog;
    GenericWebApplicationContext context;
    MockMvc mvc;
    String tenant, code;
    String secretRef;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class WebConfig {}

    @BeforeAll
    static void database() {
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                System.getenv("AGENT_DB_PASSWORD")));
        if (admin.queryForObject(
                        "select count(*) from pg_database where"
                                + " datname='agent_platform_skill_mcp_test'",
                        Integer.class)
                == 0) admin.execute("create database agent_platform_skill_mcp_test");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_mcp_test",
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
        tx = new DataSourceTransactionManager(ds);
    }

    Actor actor(Set<String> roles) {
        return new Actor(tenant, "owner", roles, Map.of("region", Set.of("A")));
    }

    void authenticate(Actor actor) {
        var jwt =
                Jwt.withTokenValue("fixture")
                        .header("alg", "HS256")
                        .subject(actor.principalId())
                        .claim("tenant_id", actor.tenantId())
                        .claim("roles", actor.roles())
                        .claim("attributes", actor.attributes())
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @BeforeEach
    void start() throws Exception {
        tenant = "mcp_" + UUID.randomUUID();
        code = "mcp";
        peer = new McpAdapterTest.Peer();
        authenticate(actor(Set.of("ADMIN", "EDITOR", "SERVICE")));
        catalog = new SkillCatalog(db, tx, mock(SkillClients.class), "http://unused");
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "mcp-test",
                                Map.of(
                                        "agent.security.jwt-secret",
                                        new String(
                                                ServiceTokens.secretBytes(),
                                                StandardCharsets.UTF_8))));
        context.registerBean(
                SkillController.class,
                () ->
                        new SkillController(
                                catalog, mock(SkillExecution.class), mock(SkillGovernance.class)));
        new AnnotatedBeanDefinitionReader(context)
                .register(WebConfig.class, SecurityConfiguration.class, PlatformErrors.class);
        context.refresh();
        mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    @AfterEach
    void stop() throws Exception {
        if (context != null) context.close();
        peer.close();
        SecurityContextHolder.clearContext();
        if (secretRef != null)
            Files.deleteIfExists(
                    Path.of(System.getenv("AGENT_SECRET_DIR"), secretRef.substring(6) + ".secret"));
    }

    Map<String, Object> contract() {
        var contract = new LinkedHashMap<String, Object>();
        contract.putAll(
                Map.of(
                        "code",
                        code,
                        "name",
                        "MCP fixture",
                        "description",
                        "isolated MCP",
                        "type",
                        "MCP",
                        "endpoint",
                        peer.url(),
                        "toolName",
                        "lookup",
                        "timeoutMs",
                        3000));
        contract.put("inputSchema", peer.inputSchema);
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        contract.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        return contract;
    }

    MvcResult discover(Actor actor, String body) throws Exception {
        return mvc.perform(
                        post("/admin/skills/" + code + "/drafts/1/mcp-tools")
                                .header(
                                        "Authorization",
                                        "Bearer " + ServiceTokens.userToken(actor, 60))
                                .contentType("application/json")
                                .content(body))
                .andReturn();
    }

    @Test
    void editorDiscoversOnlyExistingTenantDraftAndCannotOverrideEndpoint() throws Exception {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put("minProperties", 1);
        catalog.create(contract());
        var reply =
                discover(
                        actor(Set.of("EDITOR")),
                        "{\"endpoint\":\"http://127.0.0.1:1\",\"tools\":[{\"name\":\"forged\"}]}");
        assertEquals(200, reply.getResponse().getStatus());
        var json = Json.read(reply.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("OK", json.path("code").asText());
        assertEquals("2024-11-05", json.path("data").path("protocolVersion").asText());
        assertEquals("lookup", json.path("data").path("tools").get(0).path("name").asText());
        assertEquals(
                1,
                json.path("data")
                        .path("tools")
                        .get(0)
                        .path("inputSchema")
                        .path("minProperties")
                        .asInt());
        assertFalse(json.toString().contains("authRef"));
        assertEquals(0, peer.calls.get());
    }

    @Test
    void readOnlyUserAndCrossTenantCannotOpenMcpConnection() throws Exception {
        catalog.create(contract());
        assertEquals(403, discover(actor(Set.of("USER")), "{}").getResponse().getStatus());
        assertEquals(
                404,
                discover(new Actor("other-tenant", "owner", Set.of("EDITOR"), Map.of()), "{}")
                        .getResponse()
                        .getStatus());
        assertTrue(peer.methods.isEmpty());
    }

    @Test
    void authenticatedDraftNeedsAdminAndDoesNotExposeSecretReference() throws Exception {
        secretRef = SecretVault.put("mcp-isolated-fixture-only-token");
        peer.expectedAuth = "Bearer mcp-isolated-fixture-only-token";
        var body = contract();
        body.put("authRef", secretRef);
        catalog.create(body);
        assertEquals(403, discover(actor(Set.of("EDITOR")), "{}").getResponse().getStatus());
        assertTrue(peer.methods.isEmpty());
        var reply = discover(actor(Set.of("ADMIN", "EDITOR")), "{}");
        assertEquals(200, reply.getResponse().getStatus());
        assertFalse(reply.getResponse().getContentAsString().contains(secretRef));
        assertFalse(
                reply.getResponse()
                        .getContentAsString()
                        .contains("mcp-isolated-fixture-only-token"));
        assertEquals(0, peer.authRejects.get());
    }

    @Test
    void wrongTenantCredentialReferenceRejectsBeforeOpeningSocket() throws Exception {
        secretRef =
                SecretVault.put(
                        "foreign-fixture-secret",
                        new Actor("foreign-tenant", "owner", Set.of("ADMIN"), Map.of()));
        var body = contract();
        body.put("authRef", secretRef);
        catalog.create(body);
        var reply = discover(actor(Set.of("ADMIN", "EDITOR")), "{}");
        assertEquals(403, reply.getResponse().getStatus());
        assertEquals(0, peer.opens.get());
    }

    @Test
    void invalidJwtCannotReachSavedDraftOrTransport() throws Exception {
        catalog.create(contract());
        var reply =
                mvc.perform(
                                post("/admin/skills/mcp/drafts/1/mcp-tools")
                                        .header("Authorization", "Bearer invalid-signed-token"))
                        .andReturn();
        assertEquals(401, reply.getResponse().getStatus());
        assertEquals(0, peer.opens.get());
    }

    @Test
    void nonMcpAndPublishedVersionCannotBeDiscovered() throws Exception {
        var body = contract();
        body.put("type", "HTTP");
        catalog.create(body);
        assertEquals(400, discover(actor(Set.of("EDITOR")), "{}").getResponse().getStatus());
        db.update("update skill.skill_version set status='PUBLISHED' where tenant_id=?", tenant);
        assertEquals(409, discover(actor(Set.of("EDITOR")), "{}").getResponse().getStatus());
        assertEquals(0, peer.opens.get());
    }

    @Test
    void missingMcpToolNameIsRejectedWhenSavingDraft() {
        var body = contract();
        body.remove("toolName");
        var failure = assertThrows(PlatformException.class, () -> catalog.create(body));
        assertEquals(400, failure.status());
    }
}
