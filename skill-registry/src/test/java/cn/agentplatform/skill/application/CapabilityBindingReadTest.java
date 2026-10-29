package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.api.SkillController;
import cn.agentplatform.skill.infra.AdapterRegistry;
import cn.agentplatform.skill.infra.SkillClients;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** 真实 PG/Controller/Catalog/注册表；身份上下文和发布门证据是夹具，不宣称执行器已验收。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityBindingReadTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    TransactionStatus transaction;
    SkillCatalog catalog;
    MockMvc mvc;
    String tenant;

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                "agent_v12", System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("skill").defaultSchema("skill")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach void setup() {
        tenant = "capability-read-" + UUID.randomUUID();
        actor(tenant, "ADMIN");
        transaction = manager.getTransaction(new DefaultTransactionDefinition());
        catalog = catalog(AdapterRegistry.withPlatformDefaults());
        mvc = mvc(catalog);
    }

    @AfterEach void rollback() {
        try { if (transaction != null) manager.rollback(transaction); }
        finally { SecurityContextHolder.clearContext(); }
    }

    SkillCatalog catalog(AdapterRegistry registry) {
        return new SkillCatalog(db, manager, new SkillClients(), "http://127.0.0.1:1",
                true, true, "", registry);
    }

    MockMvc mvc(SkillCatalog source) {
        return MockMvcBuilders.standaloneSetup(new SkillController(source, null, null))
                .setControllerAdvice(new PlatformErrors()).build();
    }

    static void actor(String tenant, String role) {
        var jwt = Jwt.withTokenValue("verified-test").header("alg", "HS256").subject("binding-editor")
                .claim("tenant_id", tenant).claim("roles", List.of(role))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> read(MockMvc http, String code, Integer version, boolean registered, int expected) throws Exception {
        var request = get("/internal/skills/{code}", code);
        if (version != null) request.param("version", version.toString());
        if (registered) request.param("requireRegistered", "true");
        var response = http.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), "CAPABILITY_REGISTERED_READ_STATUS");
        return SkillCatalog.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }

    Map<String, Object> draft(String type) {
        var value = new LinkedHashMap<String, Object>();
        value.put("code", "binding_" + type.toLowerCase(Locale.ROOT));
        value.put("name", type);
        value.put("type", type);
        value.put("description", "Metadata binding fixture; no invocation");
        value.put("authScheme", "NONE");
        value.put("inputSchema", Map.of("type", "object"));
        value.put("outputSchema", Map.of("type", "object"));
        value.put("execution", Map.of("readonly", true, "sideEffect", false, "idempotent", true, "cancellable", false));
        value.put("guard", Map.of("attributes", Map.of("region", List.of("LOCAL"))));
        if (type.equals("PROCESS")) {
            String exe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath().toString();
            value.put("process", Map.of("command", List.of(exe), "protocol", "algorithm-process/1"));
        } else {
            value.put("endpoint", "http://127.0.0.1:1/never-invoked");
            if (type.equals("MCP")) value.put("toolName", "fixture_tool");
        }
        return value;
    }

    Map<String, Object> publish(String code) {
        var candidate = catalog.version(code, 1);
        // 仅为元数据测试提供同摘要的发布门夹具；实际状态转换仍调用原 publish，未启动 worker。
        db.update("insert into skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest) values(?,?,?,true,'{\"fixture\":true}',?)",
                tenant, candidate.get("id"), candidate.get("sha256"), candidate.get("semanticDigest"));
        db.update("insert into skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest) values(?,?,?,?,true,1,'{\"fixture\":true}',?)",
                "sa_" + UUID.randomUUID(), tenant, candidate.get("id"), candidate.get("sha256"), candidate.get("semanticDigest"));
        return catalog.publish(code, 1);
    }

    @Test void exactPublishedVersionIsRequiredWithoutChangingLegacyReadsOrTenantAuthority() throws Exception {
        catalog.create(draft("HTTP"));
        assertEquals("DRAFT", SkillCatalog.object(read(mvc, "binding_http", 1, false, 200).get("data")).get("status"));
        assertEquals("SKILL_NOT_PUBLISHED", read(mvc, "binding_http", 1, true, 409).get("code"));
        assertEquals("SKILL_VERSION_REQUIRED", read(mvc, "binding_http", null, true, 400).get("code"));
        var published = publish("binding_http");
        var loaded = SkillCatalog.object(read(mvc, "binding_http", 1, true, 200).get("data"));
        assertEquals(true, loaded.get("registered"));
        assertEquals(published.get("definition"), loaded.get("definition"));
        assertEquals(published.get("sha256"), loaded.get("sha256"));
        assertEquals("SKILL_VERSION_NOT_FOUND", read(mvc, "binding_http", 99, true, 404).get("code"));
        actor(tenant + "-other", "SERVICE");
        assertEquals("SKILL_VERSION_NOT_FOUND", read(mvc, "binding_http", 1, true, 404).get("code"));
        actor(tenant, "READER");
        assertEquals("SYS-APP-4031", read(mvc, "binding_http", 1, true, 403).get("code"));
    }

    @Test void actualHttpMcpProcessRegistryIsCheckedAndReadNeverChangesPublishedFacts() throws Exception {
        for (String type : List.of("HTTP", "MCP", "PROCESS")) {
            catalog.create(draft(type));
            String code = "binding_" + type.toLowerCase(Locale.ROOT);
            var original = publish(code);
            var loaded = SkillCatalog.object(read(mvc, code, 1, true, 200).get("data"));
            assertEquals(true, loaded.get("registered"));
            assertEquals(original.get("definition"), loaded.get("definition"));
            assertEquals(Digest.sha256(Json.canonical(original.get("definition"))), loaded.get("digest"));
            if (type.equals("PROCESS")) assertEquals("algorithm-process/1", loaded.get("resolvedProcessProtocol"));
        }
        var before = db.queryForList("select * from skill.skill_version where tenant_id=? order by id", tenant);
        var missing = mvc(catalog(new AdapterRegistry(List.of())));
        assertEquals("SKILL_ADAPTER_NOT_REGISTERED", read(missing, "binding_http", 1, true, 409).get("code"));
        assertEquals("PUBLISHED", SkillCatalog.object(read(missing, "binding_http", 1, false, 200).get("data")).get("status"));
        assertEquals(before, db.queryForList("select * from skill.skill_version where tenant_id=? order by id", tenant));
        assertEquals(0, db.queryForObject("select count(*) from skill.execution_fact where tenant_id=?", Integer.class, tenant));
    }
}
