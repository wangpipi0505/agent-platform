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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真实 PG/MVC/草稿/预检/发布事务；远端技能、图校验与评测结果是明确的协议夹具，不代表真实工具或模型运行。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageApplicationsTest {
    static JdbcTemplate db;
    static AnnotationConfigWebApplicationContext context;
    static MockMvc mvc;
    static AppService apps;
    static DataSourceTransactionManager manager;
    static HttpServer peer;
    static ExecutorService executor;
    static String origin, priorSecret;
    static volatile String expectedTenant;
    static Map<String, Object> metadata;
    static final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    String tenant;
    TransactionStatus transaction;

    @Configuration @EnableWebMvc
    @ComponentScan(basePackages = "cn.agentplatform.app", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "cn\\.agentplatform\\.app\\.(api\\.CapabilityPackage(Controller|BindingController|ApplicationsController)|application\\.CapabilityPackage(Service|BindingService|ApplicationsService)|infra\\.Capability(PackageRepository|SkillClient))"))
    @Import({OnboardingApplicationSources.class, cn.agentplatform.app.infra.AppAuthorityTransport.class})
    static class WebConfig {
        @Bean JdbcTemplate database() { return db; }
        @Bean DataSourceTransactionManager transactions() { return manager; }
        @Bean cn.agentplatform.app.infra.OnboardingImportSourceRepository onboardingSourcesRepository() {
            return new cn.agentplatform.app.infra.OnboardingImportSourceRepository(db, manager);
        }
        @Bean AppService applications() { return new AppService(db, manager, origin, origin, origin, origin); }
        @Bean PlatformErrors errors() { return new PlatformErrors(); }
    }

    @BeforeAll static void setup() throws Exception {
        PublishTransactionTest.database(); db = PublishTransactionTest.db;
        manager = new DataSourceTransactionManager(db.getDataSource());
        priorSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "capability-application-fixture-key-32-characters");
        var env = new MockEnvironment().withProperty("agent.security.mode", "test")
                .withProperty("agent.security.service-id", "app").withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("test"); SecurityConfiguration.bindDeployment(env);
        var definition = new LinkedHashMap<String, Object>();
        definition.put("type", "HTTP"); definition.put("invocationKind", "SYNC");
        definition.put("endpoint", "http://127.0.0.1:1/declared-only");
        definition.put("execution", Map.of("readonly", true, "sideEffect", false, "idempotent", true));
        definition.put("riskLevel", "READ_ONLY");
        definition.put("inputSchema", Map.of("type", "object")); definition.put("outputSchema", Map.of("type", "object"));
        definition.put("guard", Map.of("attributes", Map.of("region", List.of("LOCAL")), "roles", List.of()));
        metadata = new LinkedHashMap<>(definition);
        metadata.put("code", "bound_http"); metadata.put("id", 123); metadata.put("skillVersionId", 123);
        metadata.put("version", 1); metadata.put("versionNo", 1);
        metadata.put("status", "PUBLISHED"); metadata.put("skillStatus", "PUBLISHED");
        metadata.put("sha256", Digest.sha256(Json.canonical(definition))); metadata.put("digest", metadata.get("sha256"));
        metadata.put("definition", definition); metadata.put("registered", true);
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor(); peer.setExecutor(executor);
        peer.createContext("/internal/", exchange -> {
            try (exchange) {
                try {
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    assertEquals(expectedTenant, token.getJWTClaimsSet().getStringClaim("tenant_id"));
                    String path = exchange.getRequestURI().getPath();
                    Object data;
                    if (path.equals("/internal/skills/bound_http"))
                        data = exchange.getRequestURI().getRawQuery() == null
                                ? Map.of("code", "bound_http", "status", "PUBLISHED", "versions", List.of(metadata)) : metadata;
                    else if (path.equals("/internal/graphs/validate")) {
                        assertEquals("POST", exchange.getRequestMethod());
                        assertTrue(Json.read(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).path("definition").isObject());
                        data = Map.of("valid", true);
                    } else if (path.equals("/internal/evaluations/accepted"))
                        data = Map.of("accepted", true, "evalReportId", "explicit-protocol-fixture");
                    else throw new AssertionError("Unexpected protocol path: " + path);
                    byte[] bytes = Json.write(ApiResponse.ok(data)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) { peerFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(500, -1); }
            }
        });
        peer.start(); origin = "http://127.0.0.1:" + peer.getAddress().getPort();
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("agent.services.skill", origin)));
        context.register(WebConfig.class); context.refresh();
        apps = context.getBean(AppService.class); mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterAll static void close() throws Exception {
        try { if (context != null) context.close(); }
        finally {
            if (peer != null) peer.stop(0);
            if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            if (priorSecret == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", priorSecret);
        }
    }
    @BeforeEach void start() {
        tenant = "capability-app-" + UUID.randomUUID(); expectedTenant = tenant; peerFailure.set(null);
        CapabilityPackageImportTest.actor(tenant, "EDITOR");
        // 已发布行不可 DELETE；整个测试的业务行均加入真实外层事务，结束仅回滚本测试。
        transaction = manager.getTransaction(new DefaultTransactionDefinition());
    }
    @AfterEach void rollback() {
        try { if (transaction != null) manager.rollback(transaction); }
        finally { SecurityContextHolder.clearContext(); }
        assertNull(peerFailure.get(), "协议夹具失败不得作为业务红");
    }
    Map<String, Object> request(String path, Map<String, Object> body, int status) throws Exception {
        var response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(Json.write(body))).andReturn().getResponse();
        assertEquals(status, response.getStatus(), "CAPABILITY_APPLICATION_HTTP_STATUS");
        return AppService.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }
    long intake(boolean resolved) throws Exception {
        var result = AppService.object(request("/admin/capability-packages/import",
                Map.of("package", CapabilityPackageImportTest.declaration("HTTP_SYNC")), 200).get("data"));
        long id = ((Number) result.get("packageVersionId")).longValue();
        if (resolved) request("/admin/capability-packages/" + id + "/bindings/resolve", Map.of("environment", "dev", "routeProfileId", "primary",
                "bindings", List.of(Map.of("toolId", "fixture-tool", "skillRef", Map.of("code", "bound_http", "version", 1)))), 200);
        return id;
    }
    Map<String, Object> plan(String code) {
        return Map.of("applications", List.of(Map.of("appCode", code, "name", "用户完整流程", "type", "FLOW", "routeProfileId", "primary",
                "config", Map.of("inputSchema", Map.of("type", "object"), "outputSchema", Map.of("type", "object"),
                        "guard", Map.of("roles", List.of(), "attributes", Map.of("region", List.of("LOCAL")))),
                "definition", Map.of("nodes", List.of(Map.of("id", "start", "type", "START"), Map.of("id", "end", "type", "END")),
                        "edges", List.of(Map.of("from", "start", "to", "end"))))));
    }
    Map<String, Object> create(long id, Map<String, Object> plan, int status) throws Exception {
        return request("/admin/capability-packages/" + id + "/applications", plan, status);
    }
    void code(PlatformException ex, String expected) { assertEquals(expected, ex.code()); }

    @Test void actualDraftRemainsEditableAndSourceSurvivesCopyAndPublishedSnapshot() throws Exception {
        long id = intake(true); create(id, plan("flow_a"), 200);
        var app = apps.get("flow_a"); assertEquals("DRAFT", app.get("status"));
        var config = new LinkedHashMap<>(AppService.object(app.get("config")));
        var source = AppService.objects(config.get("capabilityPackages")).getFirst();
        assertEquals(id, ((Number) source.get("packageVersionId")).longValue());
        assertEquals(metadata.get("sha256"), AppService.objects(source.get("bindings")).getFirst().get("sha256"));
        config.put("description", "用户后续编辑");
        app = apps.save("flow_a", Map.of("draftRev", app.get("draftRev"), "config", config), false);
        var removed = new LinkedHashMap<>(config); removed.remove("capabilityPackages");
        Object rev = app.get("draftRev");
        code(assertThrows(PlatformException.class, () -> apps.save("flow_a", Map.of("draftRev", rev, "config", removed), false)), "CAPABILITY_SOURCE_CONFLICT");
        var changed = new LinkedHashMap<>(config); changed.put("skillRefs", List.of(Map.of("code", "bound_http", "version", 2)));
        code(assertThrows(PlatformException.class, () -> apps.save("flow_a", Map.of("draftRev", rev, "config", changed), false)), "CAPABILITY_SOURCE_CONFLICT");
        var copy = apps.copy("flow_a", Map.of("code", "flow_copy", "name", "复制草稿"));
        assertEquals(config, copy.get("config")); assertEquals("DRAFT", copy.get("status"));
        assertEquals(2, db.queryForObject("select count(*) from app.capability_app_binding where tenant_id=?", Integer.class, tenant));
        apps.delete("flow_copy");
        assertEquals(1, db.queryForObject("select count(*) from app.capability_app_binding where tenant_id=?", Integer.class, tenant));
        var checked = apps.precheck("flow_a"); assertEquals(true, checked.get("passed"));
        apps.commit("flow_a", Map.of("precheckId", checked.get("precheckId"), "draftRev", checked.get("draftRev"),
                "candidateSha256", checked.get("candidateSha256"), "idempotencyRequestNo", "publish-fixture"));
        var frozen = apps.version("flow_a", 1);
        assertEquals(config.get("capabilityPackages"), AppService.object(frozen.get("config")).get("capabilityPackages"));
        var edit = new LinkedHashMap<>(config); edit.put("description", "发布后改稿");
        apps.save("flow_a", Map.of("draftRev", apps.get("flow_a").get("draftRev"), "config", edit), false);
        assertEquals(frozen, apps.version("flow_a", 1));
    }

    @Test void samePlanKeepsUserEditsAndOtherPlanTenantOrDeletedTargetCannotReuseIt() throws Exception {
        long id = intake(true); var plan = plan("flow_b"); create(id, plan, 200);
        var app = apps.get("flow_b"); var config = new LinkedHashMap<>(AppService.object(app.get("config"))); config.put("description", "保留编辑");
        app = apps.save("flow_b", Map.of("draftRev", app.get("draftRev"), "config", config), false);
        var retry = AppService.object(create(id, plan, 200).get("data")); assertEquals(true, retry.get("reused"));
        assertEquals(config, apps.get("flow_b").get("config"));
        assertEquals(((Number) app.get("draftRev")).longValue(),
                ((Number) AppService.objects(retry.get("applications")).getFirst().get("draftRev")).longValue());
        assertEquals("CAPABILITY_IMPORT_PLAN_CONFLICT", create(id, plan("different"), 409).get("code"));
        CapabilityPackageImportTest.actor(tenant + "-other", "EDITOR");
        assertEquals("CAPABILITY_PACKAGE_NOT_FOUND", create(id, plan, 404).get("code"));
        CapabilityPackageImportTest.actor(tenant, "EDITOR"); apps.delete("flow_b");
        assertEquals("CAPABILITY_APPLICATION_MISSING", create(id, plan, 409).get("code"));
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id=?", Integer.class, tenant));
    }

    @Test void unresolvedMissingConfigurationAndForgedSourceFailBeforeCreatingAnyApp() throws Exception {
        long id = intake(false);
        assertEquals("CAPABILITY_BINDING_REQUIRED", create(id, plan("flow_c"), 409).get("code"));
        intake(true);
        var chat = Map.<String, Object>of("appCode", "missing_model", "name", "必须给模型", "type", "CHAT", "routeProfileId", "primary", "config", Map.of());
        assertEquals("INVALID_INPUT", create(id, Map.of("applications", List.of(chat)), 400).get("code"));
        var forged = new LinkedHashMap<>(AppService.objects(plan("flow_c").get("applications")).getFirst());
        forged.put("config", Map.of("capabilityPackages", List.of(Map.of("packageVersionId", id))));
        assertEquals("INVALID_INPUT", create(id, Map.of("applications", List.of(forged)), 400).get("code"));
        CapabilityPackageImportTest.actor(tenant, "READER");
        assertEquals("SYS-APP-4031", create(id, plan("flow_c"), 403).get("code"));
        assertEquals(0, db.queryForObject("select count(*) from app.application where tenant_id=?", Integer.class, tenant));
    }
}
