package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

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
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * X-10：外部事实被引擎采纳后，skill 以 callback eventId 唤醒引擎，让派发不必等满退避。
 *
 * <p>真实 PG、真实服务票据和真实 HTTP 引擎桩：桩收包后断言路径/方法/正文两键/服务票据，
 * 并验证唤醒失败（500）不改变回调结论、重复投递不重复唤醒。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillCallbackWakeTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    GenericWebApplicationContext context;
    SkillCatalog catalog;
    SkillExecution execution;
    SkillAdapters adapters;
    SkillCallbacks callbacks;
    HttpServer provider, engine;
    ExecutorService workers;
    String tenant, key, secretRef, engineUrl;
    final String secret = "callback-test-secret-with-at-least-32-characters";
    long call, version;
    Map<String, Object> request, authority;
    AtomicInteger submits = new AtomicInteger();
    volatile String callState = "SUBMITTED";
    volatile int wakeStatus = 200;
    final List<Map<String, Object>> wakes = Collections.synchronizedList(new ArrayList<>());

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @ComponentScan(
            basePackages = "cn.agentplatform.skill",
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = ".*SkillCallback(s|Controller)"))
    static class WebConfig {}

    @BeforeAll
    static void database() throws Exception {
        var admin =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/postgres",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        var sql = new JdbcTemplate(admin);
        if (sql.queryForObject(
                        "select count(*) from pg_database where"
                                + " datname='agent_platform_skill_wake_test'",
                        Integer.class)
                == 0) sql.execute("create database agent_platform_skill_wake_test");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_wake_test",
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

    @BeforeEach
    void setup() throws Exception {
        tenant = "wake_" + UUID.randomUUID();
        key = "key-" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        authenticate(actor());
        secretRef = SecretVault.put(secret);
        workers = Executors.newCachedThreadPool();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(workers);
        provider.createContext(
                "/submit",
                ex -> {
                    submits.incrementAndGet();
                    ex.getRequestBody().readAllBytes();
                    byte[] bytes =
                            Json.write(Map.of("jobId", "job-1", "idempotencyKey", key))
                                    .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(202, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        provider.start();
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.setExecutor(workers);
        engine.createContext(
                "/",
                ex -> {
                    String path = ex.getRequestURI().getPath();
                    Object reply;
                    if (path.endsWith("/wake")) {
                        byte[] raw = ex.getRequestBody().readAllBytes();
                        wakes.add(
                                Map.of(
                                        "method",
                                        ex.getRequestMethod(),
                                        "path",
                                        path,
                                        "authorization",
                                        String.valueOf(
                                                ex.getRequestHeaders()
                                                        .getFirst("Authorization")),
                                        "body",
                                        new String(raw, StandardCharsets.UTF_8)));
                        if (wakeStatus != 200) {
                            ex.sendResponseHeaders(wakeStatus, -1);
                            ex.close();
                            return;
                        }
                        reply = Map.of("woken", true, "source", "SKILL_CALLBACK");
                    } else if (path.endsWith("/submitted")) {
                        callState = "SUBMITTED";
                        reply = Map.of("submitted", true);
                    } else if (path.contains("/admin/runs/")) {
                        reply =
                                Map.of(
                                        "runId",
                                        "r_callback",
                                        "status",
                                        "RUNNING",
                                        "nodes",
                                        List.of(Map.of("nodeInstanceId", 123, "status", "RUNNING")));
                    } else {
                        var current = new LinkedHashMap<>(authority);
                        current.put("status", callState);
                        reply = current;
                    }
                    byte[] bytes =
                            Json.write(ApiResponse.ok(reply)).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        engine.start();
        engineUrl = "http://127.0.0.1:" + engine.getAddress().getPort();
        var clients = new SkillClients();
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
        adapters =
                new SkillAdapters(
                        new ArtifactStore(db), new AlgorithmAdapter(db), new HttpAsyncAdapter(db));
        execution = new SkillExecution(db, tx, catalog, adapters, clients, engineUrl);
        callbacks = new SkillCallbacks(db, tx, catalog, adapters, clients, execution, engineUrl);
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "wake-test",
                                Map.of(
                                        "agent.security.jwt-secret",
                                        new String(
                                                ServiceTokens.secretBytes(),
                                                StandardCharsets.UTF_8),
                                        "agent.security.mode",
                                        "dev-hmac",
                                        "agent.security.service-id",
                                        "skill",
                                        "agent.dev-auth",
                                        "true",
                                        "server.address",
                                        "127.0.0.1",
                                        "agent.services.engine",
                                        engineUrl)));
        context.registerBean(JdbcTemplate.class, () -> db);
        context.registerBean(
                org.springframework.transaction.PlatformTransactionManager.class, () -> tx);
        context.registerBean(SkillCatalog.class, () -> catalog);
        context.registerBean(SkillAdapters.class, () -> adapters);
        context.registerBean(SkillExecution.class, () -> execution);
        context.registerBean(SkillClients.class, () -> clients);
        new AnnotatedBeanDefinitionReader(context)
                .register(WebConfig.class, SecurityConfiguration.class, PlatformErrors.class);
        context.refresh();
    }

    Actor actor() {
        return new Actor(
                tenant, "owner", Set.of("SERVICE", "EDITOR", "ADMIN"), Map.of("region", Set.of("A")));
    }

    void authenticate(Actor actor) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject(actor.principalId())
                        .claim("tenant_id", actor.tenantId())
                        .claim("roles", actor.roles())
                        .claim("attributes", actor.attributes())
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> contract() {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", "wake");
        c.put("name", "wake");
        c.put("type", "HTTP");
        c.put("description", "wake test");
        c.put("endpoint", "http://127.0.0.1:" + provider.getAddress().getPort() + "/submit");
        c.put("invocationKind", "CALLBACK");
        c.put("callback", Map.of("secretRef", secretRef));
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put(
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
        c.put(
                "guard",
                Map.of(
                        "roles",
                        List.of("SERVICE"),
                        "attributes",
                        Map.of("region", List.of("A"))));
        c.put("timeoutMs", 10000);
        return c;
    }

    void submitted() {
        version =
                ((Number)
                                assertDoesNotThrow(() -> catalog.create(contract()))
                                        .get("skillVersionId"))
                        .longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_callback",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                Map.of("region", List.of("A")),
                                "idempotencyKey",
                                key,
                                "leaseOwner",
                                "worker"));
        authority = new LinkedHashMap<>(request);
        authority.put("claimed", true);
        authority.put("skill_version_id", version);
        authority.put("skill_code", "wake");
        authority.put("args_digest", Digest.sha256(Json.canonical(request.get("arguments"))));
        authority.put("node_instance_id", 123);
        authority.put("attempt_id", 456);
        authority.put("idempotency_key", key);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        authority.put("trigger_type", "TASK");
        authority.put("mock_snapshot", Map.of());
        assertEquals("UNKNOWN", execution.execute("wake", request).get("status"));
    }

    Map<String, Object> body(String event) {
        return new LinkedHashMap<>(
                Map.of(
                        "eventId",
                        event,
                        "jobId",
                        "job-1",
                        "runId",
                        "r_callback",
                        "callId",
                        call,
                        "attemptId",
                        456,
                        "skillVersionId",
                        version,
                        "idempotencyKey",
                        key,
                        "status",
                        "SUCCEEDED",
                        "result",
                        Map.of("value", 7)));
    }

    Map<String, Object> deliver(String event) throws Exception {
        String raw = Json.write(body(event)), ts = "" + Instant.now().getEpochSecond();
        return callbacks.receive(
                call,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),
                ts,
                signature(ts, raw));
    }

    String signature(String timestamp, String raw) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of()
                .formatHex(mac.doFinal((timestamp + "\n" + raw).getBytes(StandardCharsets.UTF_8)));
    }

    String status() {
        return db.queryForObject(
                "select status from skill.execution_fact where tenant_id=? and call_id=?",
                String.class,
                tenant,
                call);
    }

    String receipt(String event) {
        return db.queryForObject(
                "select outcome from skill.callback_receipt where tenant_id=? and call_id=? and"
                        + " event_id=?",
                String.class,
                tenant,
                call,
                event);
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (provider != null) provider.stop(0);
        if (engine != null) engine.stop(0);
        if (workers != null) workers.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void adoptedFactSendsOneSignedWake() throws Exception {
        String event = "evt-" + UUID.randomUUID();
        submitted();

        var received = deliver(event);

        assertEquals("STORED", received.get("outcome"));
        assertEquals("SUCCEEDED", status());
        assertEquals(1, wakes.size(), "采纳成功后应恰好唤醒一次");
        var wake = wakes.get(0);
        assertEquals("POST", wake.get("method"));
        assertEquals("/internal/runs/r_callback/wake", wake.get("path"));
        var body = Json.read((String) wake.get("body"));
        var fields = new TreeSet<String>();
        body.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("requestId", "source"), fields, "请求体恰为 requestId 与 source");
        assertEquals(event, body.path("requestId").asText(), "requestId 取回调 eventId");
        assertEquals("SKILL_CALLBACK", body.path("source").asText());
        String authorization = (String) wake.get("authorization");
        assertTrue(authorization.startsWith("Bearer "), "唤醒必须带服务票据");
        var claims = Json.read(tokenPayload(authorization.substring("Bearer ".length())));
        assertEquals("service", claims.path("token_use").asText());
        assertEquals("owner", claims.path("sub").asText());
        assertEquals(tenant, claims.path("tenant_id").asText());
        var roles = new TreeSet<String>();
        claims.path("roles").forEach(role -> roles.add(role.asText()));
        assertTrue(roles.contains("SERVICE"), "服务票据必须带 SERVICE 角色");
    }

    @Test
    void wakeFailureKeepsCallbackSuccessful() throws Exception {
        String event = "evt-" + UUID.randomUUID();
        submitted();
        wakeStatus = 503;

        var received = assertDoesNotThrow(() -> deliver(event));

        assertEquals("STORED", received.get("outcome"), "唤醒失败不得改变回调结论");
        assertFalse((Boolean) received.get("deliveryAccepted"), "事实尚未被 outbox 投递，接口如实回报");
        assertEquals("SUCCEEDED", status(), "事实必须已持久化");
        assertEquals("STORED", receipt(event));
        assertEquals(1, wakes.size(), "失败也真的发过一次请求");
    }

    @Test
    void replayAndSupersededDeliveryDoNotWakeAgain() throws Exception {
        String event = "evt-" + UUID.randomUUID();
        submitted();
        assertEquals("STORED", deliver(event).get("outcome"));
        assertEquals(1, wakes.size());

        // 同一 eventId 的重投：命中 receipt 早返回，不再发唤醒。
        assertEquals("STORED", deliver(event).get("outcome"));
        assertEquals(1, wakes.size(), "重复投递不得重复唤醒");

        // 事实已终态后再来一个新事件：本次只是 SUPERSEDED，也没有新事实可采纳。
        String late = "evt-" + UUID.randomUUID();
        assertEquals("SUPERSEDED", deliver(late).get("outcome"));
        assertEquals("SUPERSEDED", receipt(late));
        assertEquals(1, wakes.size(), "未被采纳的事实不得唤醒");
    }

    private String tokenPayload(String token) {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "服务票据应为三段式 JWT");
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
