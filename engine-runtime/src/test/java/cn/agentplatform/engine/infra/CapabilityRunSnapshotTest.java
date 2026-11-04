package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 真实 PG admission 和签名 HTTP；远端发布元数据为协议夹具，调度通知被测试拥有者抑制，不执行技能。 */
@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class CapabilityRunSnapshotTest {
    static JdbcTemplate db;
    static TraceStore store;
    static HttpServer peer;
    static ExecutorService executor;
    static String origin, priorSecret;
    static volatile Actor actor;
    static final AtomicReference<JsonNode> application = new AtomicReference<>(), skill = new AtomicReference<>();
    static final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    static final AtomicInteger reads = new AtomicInteger(), skillReads = new AtomicInteger();
    static final List<String> queries = new CopyOnWriteArrayList<>();
    RuntimeClients clients;
    RunScheduler scheduler;
    RunService runs;

    /** 默认仍是专用 25432 引擎库；只有显式 ENGINE_PG_URL 才切换，保持隐式跨库为零。 */
    static String engineUrl() {
        String override = System.getenv("ENGINE_PG_URL");
        return override == null || override.isBlank()
                ? "jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test"
                : override;
    }

    @BeforeAll static void database() throws Exception {
        var ds = new DriverManagerDataSource(engineUrl(),
                EngineTestDatabase.user(), EngineTestDatabase.password());
        org.flywaydb.core.Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds); store = new TraceStore(db, new DataSourceTransactionManager(ds));
        priorSecret = System.getProperty("agent.jwt.secret");
        System.setProperty("agent.jwt.secret", "capability-run-protocol-fixture-key-32-characters");
        var env = new MockEnvironment().withProperty("agent.security.mode", "test")
                .withProperty("agent.security.service-id", "engine").withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("test"); SecurityConfiguration.bindDeployment(env);
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor(); peer.setExecutor(executor);
        peer.createContext("/internal/", exchange -> {
            try (exchange) {
                try {
                    reads.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    assertEquals(actor.tenantId(), token.getJWTClaimsSet().getStringClaim("tenant_id"));
                    JsonNode data;
                    if (exchange.getRequestURI().getPath().equals("/internal/apps/capability_fixture/published")) data = application.get();
                    else if (exchange.getRequestURI().getPath().equals("/internal/skills/bound_http")) {
                        skillReads.incrementAndGet(); queries.add(exchange.getRequestURI().getRawQuery()); data = skill.get();
                    } else throw new AssertionError("Unexpected metadata request " + exchange.getRequestURI());
                    byte[] body = Json.write(ApiResponse.ok(data)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
                } catch (Throwable failure) { peerFailure.compareAndSet(null, failure); exchange.sendResponseHeaders(500, -1); }
            }
        });
        peer.start(); origin = "http://127.0.0.1:" + peer.getAddress().getPort();
    }
    @AfterAll static void closePeer() throws Exception {
        try { if (peer != null) peer.stop(0); }
        finally {
            if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            if (priorSecret == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", priorSecret);
        }
    }
    @BeforeEach void setup() {
        actor = new Actor("capability-run-" + UUID.randomUUID(), "fixture-editor", Set.of("EDITOR"), Map.of());
        peerFailure.set(null); reads.set(0); skillReads.set(0); queries.clear();
        skill.set(metadata()); application.set(source());
        clients = new RuntimeClients(); clients.app = origin; clients.skill = origin; clients.model = origin;
        var queue = new RunScheduleRepository(store) {
            @Override public cn.agentplatform.engine.domain.RunDispatch claimNext(
                    cn.agentplatform.engine.domain.RunDispatch.Queue kind, String owner, java.time.Duration lease) {
                // 本测试不验证调度器；禁止扫描/领取其它测试留下的队列工作，真实 ticket/持久化仍保留。
                return null;
            }
        };
        scheduler = new RunScheduler(queue, 4, 4, 20, 1000, 1000) {
            @Override public void created(long id, Admission ticket) { /* 仅隔离执行通知；原 admission 和 PG 提交保持真实。 */ }
        };
        runs = new RunService(store, clients, null, null, scheduler);
    }
    @AfterEach void cleanup() {
        try { if (scheduler != null) scheduler.close(); if (clients != null) clients.close(); }
        finally {
            // 无调度/工具/模型运行；只清本次随机租户的登记行，不调用共享 fixture.clean。
            db.update("delete from engine.run_event where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.node_instance where run_id in(select id from engine.run where tenant_id=?)", actor.tenantId());
            db.update("delete from engine.run where tenant_id=?", actor.tenantId());
        }
        assertNull(peerFailure.get(), "协议夹具失败不能作为业务红");
    }
    static ObjectNode metadata() {
        var definition = Json.object(); definition.put("type", "HTTP"); definition.put("invocationKind", "SYNC");
        definition.put("endpoint", "http://127.0.0.1:1/not-invoked");
        definition.set("execution", Json.tree(Map.of("readonly", true, "sideEffect", false, "idempotent", true)));
        definition.set("guard", Json.object()); definition.set("inputSchema", Json.tree(Map.of("type", "object")));
        definition.set("outputSchema", Json.tree(Map.of("type", "object")));
        var value = definition.deepCopy(); value.set("definition", definition);
        value.put("code", "bound_http"); value.put("id", 31); value.put("skillVersionId", 31);
        value.put("version", 1); value.put("versionNo", 1); value.put("status", "PUBLISHED"); value.put("skillStatus", "PUBLISHED");
        value.put("registered", true); value.put("sha256", Digest.sha256(Json.canonical(definition))); value.put("digest", value.path("sha256").asText());
        return value;
    }
    static ObjectNode source() {
        var source = Json.object(); source.put("appCode", "capability_fixture"); source.put("type", "FLOW"); source.put("versionNo", 1);
        source.put("appVersionId", 91);
        var config = source.putObject("config"); config.set("skillRefs", Json.tree(List.of(Map.of("code", "bound_http", "version", 1))));
        var mapping = Json.object(); mapping.put("environment", "qa"); mapping.put("environmentDeclaration", "declared-environment"); mapping.put("routeProfileId", "primary");
        var bindings = mapping.putArray("bindings");
        for (String tool : List.of("search", "search_alias")) bindings.add(Json.tree(Map.of("routeProfileId", "primary", "toolId", tool,
                "interfaceId", "http", "skillCode", "bound_http", "skillVersionId", 31, "version", 1, "sha256", skill.get().path("sha256").asText())));
        var pkg = mapping.deepCopy(); pkg.put("packageVersionId", 17); pkg.put("packageId", "fixture-package"); pkg.put("version", "1.0.0");
        pkg.put("sha256", Digest.sha256("declaration-fixture")); pkg.put("softwareId", "fixture-software"); pkg.put("softwareVersion", "2.0");
        pkg.put("mappingSha256", Digest.sha256(Json.canonical(mapping))); config.putArray("capabilityPackages").add(pkg);
        source.set("definition", Json.tree(Map.of("nodes", List.of(Map.of("id", "s", "type", "START"), Map.of("id", "e", "type", "END")),
                "edges", List.of(Map.of("from", "s", "to", "e")))));
        return source;
    }
    String create(String request) {
        return runs.create("capability_fixture", Json.tree(Map.of("inputs", Map.of(), "requestId", request)), "TASK", actor).get("runId").toString();
    }
    JsonNode snapshot(String id) { return TraceStore.json(store.run(id, actor).get("snapshot")); }
    JsonNode refs(String id) { return TraceStore.json(store.run(id, actor).get("version_refs")); }
    int count() { return db.queryForObject("select count(*) from engine.run where tenant_id=?", Integer.class, actor.tenantId()); }
    void mismatch(Runnable create) {
        var error = assertThrows(PlatformException.class, create::run, "CAPABILITY_SNAPSHOT_MUST_REJECT");
        assertEquals(409, error.status()); assertEquals("CAPABILITY_SNAPSHOT_CONFLICT", error.code());
        assertEquals(0, count());
    }

    @Test void exactPackageEnvironmentRouteAndEveryLogicalToolOriginArePersisted() {
        String id = create("valid"); var frozen = snapshot(id);
        assertTrue(frozen.path("capabilityBindings").isArray(), "CAPABILITY_SNAPSHOT_REQUIRED");
        var pkg = frozen.path("capabilityBindings").get(0);
        assertEquals("fixture-package", pkg.path("packageId").asText()); assertEquals("qa", pkg.path("environment").asText());
        assertEquals("2.0", pkg.path("softwareVersion").asText()); assertEquals("primary", pkg.path("routeProfileId").asText());
        assertEquals(2, pkg.path("bindings").size());
        for (var binding : pkg.path("bindings")) { assertEquals(31, binding.path("skillVersionId").asInt()); assertEquals("SELECTED", binding.path("selection").asText()); }
        var tools = frozen.path("config").path("tools"); assertEquals(1, tools.size());
        assertEquals(2, tools.get(0).path("capabilityOrigins").size());
        assertEquals(frozen.path("capabilityBindings"), refs(id).path("capabilityPackages"));
        assertEquals(1, skillReads.get()); assertEquals(List.of("version=1&requireRegistered=true"), queries);
        var original = frozen.deepCopy(); application.set(source().put("versionNo", 2)); skill.set(metadata().put("skillVersionId", 99));
        assertEquals(original, snapshot(id)); assertEquals(id, create("valid"), "requestId 重试不能改原快照");
        assertEquals(1, skillReads.get());
    }

    @Test void mismatchedSkillIdentityDigestRouteAndGraphVersionFailBeforeRunWrite() {
        skill.set(metadata().put("skillVersionId", 32).put("id", 32)); mismatch(() -> create("wrong-id"));
        skill.set(metadata().put("digest", Digest.sha256("wrong"))); mismatch(() -> create("wrong-digest"));
        skill.set(metadata()); var wrongRoute = source();
        ((ObjectNode) wrongRoute.path("config").path("capabilityPackages").get(0).path("bindings").get(0)).put("routeProfileId", "other");
        application.set(wrongRoute); mismatch(() -> create("wrong-route"));
        var wrongRef = source(); ((ObjectNode) wrongRef.path("config").path("skillRefs").get(0)).put("version", 2);
        application.set(wrongRef); mismatch(() -> create("wrong-reference"));
    }

    @Test void frozenReplayUsesOriginalSourcesRejectsCorruptionAndLeavesLegacyUntouched() {
        String original = create("source"); JsonNode frozen = snapshot(original); int beforeReads = reads.get();
        skill.set(metadata().put("id", 99).put("skillVersionId", 99)); application.set(source().put("versionNo", 7));
        String replay = runs.reproduceFrozen(original, Json.tree(Map.of("requestId", "replay", "sourceDigest", Digest.sha256(Json.canonical(frozen)))), actor).get("runId").toString();
        assertEquals(beforeReads, reads.get(), "受控回放不得读取当前技能或应用目录");
        assertEquals(frozen.path("config").path("capabilityPackages"), snapshot(replay).path("config").path("capabilityPackages"));
        var corrupted = (ObjectNode) frozen.deepCopy();
        ((ObjectNode) corrupted.path("config").path("tools").get(0)).put("contractDigest", Digest.sha256("tampered"));
        db.update("update engine.run set snapshot=?::jsonb where tenant_id=? and public_id=?", Json.write(corrupted), actor.tenantId(), original);
        int accepted = count();
        var rejected = assertThrows(PlatformException.class, () -> runs.reproduceFrozen(original,
                Json.tree(Map.of("requestId", "corrupt", "sourceDigest", Digest.sha256(Json.canonical(corrupted)))), actor), "CAPABILITY_REPLAY_MUST_REJECT");
        assertEquals("CAPABILITY_SNAPSHOT_CONFLICT", rejected.code()); assertEquals(accepted, count()); assertEquals(beforeReads, reads.get());
        skill.set(metadata()); var legacy = source(); ((ObjectNode) legacy.path("config")).remove("capabilityPackages"); application.set(legacy);
        String old = create("legacy"); assertFalse(snapshot(old).has("capabilityBindings")); assertFalse(refs(old).has("capabilityPackages"));
        assertFalse(snapshot(old).path("config").path("tools").get(0).has("capabilityOrigins"));
    }
}
