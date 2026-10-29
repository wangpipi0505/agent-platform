package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 真实 app PG/MVC 和签名 HTTP；技能回复为协议夹具，真实注册表另由 CapabilityBindingReadTest 验证。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageBindingTest {
    static JdbcTemplate db;
    static AnnotationConfigWebApplicationContext context;
    static MockMvc mvc;
    static HttpServer peer;
    static ExecutorService executor;
    static String priorSecret;
    static final AtomicReference<Map<String, Object>> metadata = new AtomicReference<>();
    static final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    static final AtomicInteger requests = new AtomicInteger();
    static volatile String expectedTenant;
    String tenant;

    @Configuration @EnableWebMvc
    @ComponentScan(basePackages = "cn.agentplatform.app", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "cn\\.agentplatform\\.app\\.(api\\.CapabilityPackage(Controller|BindingController)|application\\.CapabilityPackage(Service|BindingService)|infra\\.Capability(PackageRepository|SkillClient))"))
    static class WebConfig {
        @Bean JdbcTemplate database() { return db; }
        @Bean DataSourceTransactionManager transactions() { return new DataSourceTransactionManager(db.getDataSource()); }
        @Bean PlatformErrors errors() { return new PlatformErrors(); }
    }

    @BeforeAll static void database() throws Exception {
        PublishTransactionTest.database();
        db = PublishTransactionTest.db;
        priorSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "capability-protocol-fixture-key-32-characters");
        var env = new MockEnvironment().withProperty("agent.security.mode", "test")
                .withProperty("agent.security.service-id", "app").withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("test");
        SecurityConfiguration.bindDeployment(env);
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor();
        peer.setExecutor(executor);
        peer.createContext("/internal/skills/", exchange -> {
            try (exchange) {
                try {
                    requests.incrementAndGet();
                    assertEquals("GET", exchange.getRequestMethod());
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    var claims = token.getJWTClaimsSet();
                    assertEquals(expectedTenant, claims.getStringClaim("tenant_id"));
                    assertEquals("intake-editor", claims.getSubject());
                    assertEquals("service", claims.getStringClaim("token_use"));
                    assertTrue(claims.getAudience().contains("agent-platform-internal"));
                    assertTrue(claims.getStringListClaim("roles").contains("SERVICE"));
                    assertEquals("version=1&requireRegistered=true", exchange.getRequestURI().getRawQuery());
                    assertEquals("/internal/skills/" + metadata.get().get("code"), exchange.getRequestURI().getPath());
                    byte[] response = Json.write(ApiResponse.ok(metadata.get())).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                    exchange.sendResponseHeaders(500, -1);
                }
            }
        });
        peer.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("binding-fixture",
                Map.of("agent.services.skill", "http://127.0.0.1:" + peer.getAddress().getPort())));
        context.register(WebConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll static void close() throws Exception {
        try { if (context != null) context.close(); }
        finally {
            if (peer != null) peer.stop(0);
            if (executor != null) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
            if (priorSecret == null) System.clearProperty("agent.jwt.secret");
            else System.setProperty("agent.jwt.secret", priorSecret);
        }
    }

    @BeforeEach void actor() {
        tenant = "capability-binding-" + UUID.randomUUID();
        expectedTenant = tenant;
        CapabilityPackageImportTest.actor(tenant, "EDITOR");
        requests.set(0);
        peerFailure.set(null);
    }

    @AfterEach void cleanup() {
        try { db.update("delete from app.capability_package_version where tenant_id=?", tenant); }
        finally { SecurityContextHolder.clearContext(); }
        assertNull(peerFailure.get(), "协议夹具失败不得作为业务红");
    }

    Map<String, Object> request(String path, Map<String, Object> body, int expected) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(Json.write(body)))
                .andReturn().getResponse();
        assertEquals(expected, response.getStatus(), "CAPABILITY_BINDING_HTTP_STATUS");
        return AppService.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }

    Map<String, Object> pkg(String kind) {
        var pkg = CapabilityPackageImportTest.declaration(kind);
        var endpoint = AppService.objects(pkg.get("interfaces")).getFirst();
        var definition = new LinkedHashMap<String, Object>();
        definition.put("type", kind.equals("HTTP_SYNC") ? "HTTP" : kind);
        definition.put("invocationKind", "SYNC");
        definition.put("execution", Map.of("readonly", true, "sideEffect", false, "idempotent", true));
        definition.put("riskLevel", "READ_ONLY");
        definition.put("inputSchema", Map.of("type", "object"));
        definition.put("outputSchema", Map.of("type", "object"));
        definition.put("guard", Map.of("attributes", Map.of("region", List.of("LOCAL")), "roles", List.of()));
        if (kind.equals("PROCESS")) {
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toAbsolutePath().toString());
            endpoint.put("command", command);
            definition.put("process", Map.of("command", command, "timeoutMs", 60000));
        } else {
            if (kind.equals("MCP")) {
                endpoint.put("serverRef", "http://127.0.0.1:1/metadata-only");
                AppService.objects(pkg.get("tools")).getFirst().put("operation", "fixture_tool");
                definition.put("endpoint", endpoint.get("serverRef"));
                definition.put("toolName", "fixture_tool");
            } else definition.put("endpoint", endpoint.get("endpoint"));
        }
        String sha = Digest.sha256(Json.canonical(definition));
        var reply = new LinkedHashMap<String, Object>();
        reply.put("code", "bound_" + kind.toLowerCase(Locale.ROOT));
        reply.put("id", 123); reply.put("skillVersionId", 123);
        reply.put("version", 1); reply.put("versionNo", 1);
        reply.put("status", "PUBLISHED"); reply.put("skillStatus", "PUBLISHED");
        reply.put("sha256", sha); reply.put("digest", sha);
        reply.put("definition", definition); reply.put("registered", true);
        if (kind.equals("PROCESS")) reply.put("resolvedProcessProtocol", "algorithm-process/1");
        metadata.set(reply);
        return pkg;
    }

    long imported(Map<String, Object> pkg) throws Exception {
        var data = AppService.object(request("/admin/capability-packages/import", Map.of("package", pkg), 200).get("data"));
        return ((Number) data.get("packageVersionId")).longValue();
    }

    Map<String, Object> plan() {
        return Map.of("environment", "dev", "routeProfileId", "primary", "bindings",
                List.of(Map.of("toolId", "fixture-tool", "skillRef", Map.of("code", metadata.get().get("code"), "version", 1))));
    }

    Map<String, Object> resolve(long id, Map<String, Object> plan, int expected) throws Exception {
        return request("/admin/capability-packages/" + id + "/bindings/resolve", plan, expected);
    }

    void replyField(String field, Object value) {
        metadata.updateAndGet(old -> { var next = new LinkedHashMap<>(old); next.put(field, value); return next; });
    }

    @Test void httpMcpProcessResolveOverSignedHttpAndPersistOnlyVerifiedMapping() throws Exception {
        for (String kind : List.of("HTTP_SYNC", "MCP", "PROCESS")) {
            long id = imported(pkg(kind));
            var data = AppService.object(resolve(id, plan(), 200).get("data"));
            assertEquals("RESOLVED", data.get("bindingStatus"));
            assertEquals("NOT_EVALUATED", data.get("verificationStatus"));
            assertEquals(false, data.get("appCreated"));
            var binding = AppService.objects(data.get("bindings")).getFirst();
            assertEquals("fixture-tool", binding.get("toolId"));
            assertEquals(metadata.get().get("code"), binding.get("skillCode"));
            assertEquals(123, ((Number) binding.get("skillVersionId")).intValue());
            assertEquals(metadata.get().get("sha256"), binding.get("sha256"));
            var stored = Json.read(db.queryForObject("select resolved_mapping::text from app.capability_package_version where tenant_id=? and id=?", String.class, tenant, id));
            assertEquals(Json.tree(data.get("bindings")), stored.path("bindings"));
        }
        assertEquals(3, requests.get());
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id=?", Integer.class, tenant));
    }

    @Test void digestVersionPublicationAndRegistryMismatchNeverPersistResolution() throws Exception {
        long id = imported(pkg("HTTP_SYNC"));
        var wrongDigest = new LinkedHashMap<>(plan());
        wrongDigest.put("bindings", List.of(Map.of("toolId", "fixture-tool", "skillRef",
                Map.of("code", metadata.get().get("code"), "version", 1, "sha256", "0".repeat(64)))));
        assertEquals("CAPABILITY_BINDING_DIGEST_MISMATCH", resolve(id, wrongDigest, 409).get("code"));
        replyField("version", 2);
        assertEquals("DEPENDENCY_UNAVAILABLE", resolve(id, plan(), 502).get("code"));
        replyField("version", 1);
        replyField("status", "DRAFT");
        assertEquals("SKILL_NOT_PUBLISHED", resolve(id, plan(), 409).get("code"));
        replyField("status", "PUBLISHED");
        replyField("registered", false);
        assertEquals("SKILL_ADAPTER_NOT_REGISTERED", resolve(id, plan(), 409).get("code"));
        assertEquals("UNRESOLVED", db.queryForObject("select binding_status from app.capability_package_version where tenant_id=? and id=?", String.class, tenant, id));
    }

    @Test void resolvedPlanIsIdempotentButCannotBeChangedOrReadAcrossTenantAuthority() throws Exception {
        long id = imported(pkg("HTTP_SYNC"));
        var plan = plan();
        var first = AppService.object(resolve(id, plan, 200).get("data"));
        var second = AppService.object(resolve(id, plan, 200).get("data"));
        assertEquals(first.get("bindings"), second.get("bindings"));
        assertEquals(true, second.get("reused"));
        assertEquals(1, requests.get(), "重试读取原解析收据，不重新选择技能版本");
        var changed = new LinkedHashMap<>(plan); changed.put("routeProfileId", "other");
        assertEquals("CAPABILITY_IMPORT_PLAN_CONFLICT", resolve(id, changed, 409).get("code"));
        CapabilityPackageImportTest.actor(tenant + "-other", "EDITOR");
        assertEquals("CAPABILITY_PACKAGE_NOT_FOUND", resolve(id, plan, 404).get("code"));
        CapabilityPackageImportTest.actor(tenant, "READER");
        assertEquals("SYS-APP-4031", resolve(id, plan, 403).get("code"));
        assertEquals(1, requests.get());
    }
}
