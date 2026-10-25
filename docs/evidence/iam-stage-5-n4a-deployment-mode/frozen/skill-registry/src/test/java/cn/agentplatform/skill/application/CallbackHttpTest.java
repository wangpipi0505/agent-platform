package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

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

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Real PG and real JWT filter chain; the external provider and engine authority are HTTP fixtures.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CallbackHttpTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    GenericWebApplicationContext context;
    MockMvc mvc;
    SkillCatalog catalog;
    SkillExecution execution;
    SkillAdapters adapters;
    HttpServer provider, engine;
    ExecutorService workers;
    String tenant, key, secretRef;
    final String secret = "callback-test-secret-with-at-least-32-characters";
    long call, version;
    Map<String, Object> request, authority;
    AtomicInteger submits = new AtomicInteger(),
            facts = new AtomicInteger(),
            queries = new AtomicInteger();
    volatile String runState = "RUNNING", callState = "SUBMITTED";
    volatile boolean rejectFact, lateFact, dropAck;
    CountDownLatch submitEntered, releaseAck, unknownEntered, releaseUnknown;
    Map<String, Object> contractExtra = Map.of();

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
                                + " datname='agent_platform_skill_callback_test'",
                        Integer.class)
                == 0) sql.execute("create database agent_platform_skill_callback_test");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_callback_test",
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
        tenant = "callback_" + UUID.randomUUID();
        key = "key-" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        authenticate(actor(Set.of("A", "B")));
        secretRef = SecretVault.put(secret);
        workers = Executors.newCachedThreadPool();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(workers);
        provider.createContext(
                "/submit",
                ex -> {
                    submits.incrementAndGet();
                    ex.getRequestBody().readAllBytes();
                    if (submitEntered != null) submitEntered.countDown();
                    try {
                        if (releaseAck != null) releaseAck.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (dropAck) {
                        ex.close();
                        return;
                    }
                    byte[] bytes =
                            Json.write(Map.of("jobId", "job-1", "idempotencyKey", key))
                                    .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(202, bytes.length);
                    ex.getResponseBody().write(bytes);
                    ex.close();
                });
        provider.createContext(
                "/lookup",
                ex -> {
                    queries.incrementAndGet();
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "jobId",
                                                    "job-1",
                                                    "idempotencyKey",
                                                    key,
                                                    "status",
                                                    "RUNNING",
                                                    "result",
                                                    Map.of("value", 7)))
                                    .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, bytes.length);
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
                    if (path.endsWith("/fact")) {
                        var body =
                                Json.read(
                                        new String(
                                                ex.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        facts.incrementAndGet();
                        if (body.path("status").asText().equals("UNKNOWN")
                                && unknownEntered != null) {
                            unknownEntered.countDown();
                            try {
                                releaseUnknown.await(15, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (rejectFact) {
                            ex.sendResponseHeaders(503, -1);
                            ex.close();
                            return;
                        }
                        reply = Map.of("accepted", !lateFact, "late", lateFact);
                    } else if (path.contains("/admin/runs/")) {
                        reply =
                                Map.of(
                                        "runId",
                                        "r_callback",
                                        "status",
                                        runState,
                                        "nodes",
                                        List.of(Map.of("nodeInstanceId", 123, "status", runState)));
                    } else if (path.endsWith("/submitted")) {
                        callState = "SUBMITTED";
                        reply = Map.of("submitted", true);
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
        String engineUrl = "http://127.0.0.1:" + engine.getAddress().getPort();
        var clients = new SkillClients();
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
        adapters =
                new SkillAdapters(
                        new ArtifactStore(db), new AlgorithmAdapter(db), new HttpAsyncAdapter(db));
        execution = new SkillExecution(db, tx, catalog, adapters, clients, engineUrl);
        context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "callback-test",
                                Map.of(
                                        "agent.security.jwt-secret",
                                        new String(
                                                ServiceTokens.secretBytes(),
                                                StandardCharsets.UTF_8),
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
        mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    Actor actor(Set<String> regions) {
        return new Actor(
                tenant, "owner", Set.of("SERVICE", "EDITOR", "ADMIN"), Map.of("region", regions));
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
        c.put("code", "callback");
        c.put("name", "callback");
        c.put("type", "HTTP");
        c.put("description", "callback test");
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
                        Map.of("region", List.of("A", "B"))));
        c.put("timeoutMs", 10000);
        c.putAll(contractExtra);
        return c;
    }

    void prepare() {
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
                                Map.of("region", List.of("A", "B")),
                                "idempotencyKey",
                                key,
                                "leaseOwner",
                                "worker"));
        authority = new LinkedHashMap<>(request);
        authority.put("claimed", true);
        authority.put("skill_version_id", version);
        authority.put("skill_code", "callback");
        authority.put("args_digest", Digest.sha256(Json.canonical(request.get("arguments"))));
        authority.put("node_instance_id", 123);
        authority.put("attempt_id", 456);
        authority.put("idempotency_key", key);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        authority.put("trigger_type", "TASK");
        authority.put("mock_snapshot", Map.of());
    }

    void submitted() {
        prepare();
        assertEquals("UNKNOWN", execution.execute("callback", request).get("status"));
        facts.set(0);
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

    String signature(String timestamp, String raw) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of()
                .formatHex(mac.doFinal((timestamp + "\n" + raw).getBytes(StandardCharsets.UTF_8)));
    }

    MvcResult send(String raw, String timestamp, String signature, String token) throws Exception {
        var req =
                post("/internal/skill-jobs/" + call + "/callback")
                        .contentType("application/json")
                        .content(raw)
                        .header("X-Callback-Timestamp", timestamp)
                        .header("X-Callback-Signature", signature);
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req).andReturn();
    }

    MvcResult send(Map<String, Object> body) throws Exception {
        String raw = Json.write(body), ts = "" + Instant.now().getEpochSecond();
        return send(raw, ts, signature(ts, raw), ServiceTokens.issue(actor(Set.of("A", "B"))));
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
        if (releaseAck != null) releaseAck.countDown();
        if (releaseUnknown != null) releaseUnknown.countDown();
        if (context != null) context.close();
        provider.stop(0);
        engine.stop(0);
        workers.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void callbackContractRetainsFrozenSecretReference() {
        prepare();
        assertEquals(
                secretRef,
                Json.tree(catalog.versionId(version)).path("callback").path("secretRef").asText());
    }

    @Test
    void validCallbackIsDurableAndDuplicateCannotRepeatBusiness() throws Exception {
        submitted();
        var event = body("event-1");
        assertEquals(200, send(event).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
        assertEquals("STORED", receipt("event-1"));
        assertEquals(200, send(event).getResponse().getStatus());
        assertEquals(1, submits.get());
        authenticate(actor(Set.of("A", "B")));
        assertEquals("SUCCEEDED", execution.retry(call).get("status"));
        assertEquals(1, facts.get());
        assertEquals(
                7,
                Json.read(
                                db.queryForObject(
                                        "select result::text from skill.execution_fact where"
                                                + " tenant_id=? and call_id=?",
                                        String.class,
                                        tenant,
                                        call))
                        .path("structured")
                        .path("value")
                        .asInt());
    }

    @Test
    void serviceJwtAndOriginalPrincipalAreMandatoryEvenWithCorrectHmac() throws Exception {
        submitted();
        String raw = Json.write(body("identity")),
                ts = "" + Instant.now().getEpochSecond(),
                sig = signature(ts, raw);
        assertEquals(401, send(raw, ts, sig, null).getResponse().getStatus());
        assertEquals(401, send(raw, ts, sig, "invalid.jwt").getResponse().getStatus());
        assertEquals(
                403,
                send(raw, ts, sig, ServiceTokens.userToken(actor(Set.of("A", "B")), 60))
                        .getResponse()
                        .getStatus());
        assertEquals(
                404,
                send(
                                raw,
                                ts,
                                sig,
                                ServiceTokens.issue(
                                        new Actor(
                                                tenant,
                                                "stranger",
                                                Set.of("SERVICE"),
                                                Map.of("region", Set.of("A", "B")))))
                        .getResponse()
                        .getStatus());
        assertEquals(
                404,
                send(
                                raw,
                                ts,
                                sig,
                                ServiceTokens.issue(
                                        new Actor(
                                                "foreign",
                                                "owner",
                                                Set.of("SERVICE"),
                                                Map.of("region", Set.of("A", "B")))))
                        .getResponse()
                        .getStatus());
        assertEquals("UNKNOWN", status());
    }

    @Test
    void rejectsSignatureTimestampAndPayloadMutation() throws Exception {
        submitted();
        String raw = Json.write(body("signature")), ts = "" + Instant.now().getEpochSecond();
        assertEquals(
                401,
                send(raw, ts, "00".repeat(32), ServiceTokens.issue(actor(Set.of("A", "B"))))
                        .getResponse()
                        .getStatus());
        assertEquals(
                401,
                send(
                                raw + " ",
                                ts,
                                signature(ts, raw),
                                ServiceTokens.issue(actor(Set.of("A", "B"))))
                        .getResponse()
                        .getStatus());
        for (long offset : new long[] {-301, 301}) {
            String stale = "" + (Instant.now().getEpochSecond() + offset);
            assertEquals(
                    401,
                    send(
                                    raw,
                                    stale,
                                    signature(stale, raw),
                                    ServiceTokens.issue(actor(Set.of("A", "B"))))
                            .getResponse()
                            .getStatus());
        }
        assertEquals("UNKNOWN", status());
    }

    @Test
    void duplicateEventWithDifferentDigestIsRejected() throws Exception {
        submitted();
        var event = body("duplicate");
        assertEquals(200, send(event).getResponse().getStatus());
        event.put("result", Map.of("value", 8));
        assertEquals(409, send(event).getResponse().getStatus());
        assertEquals("STORED", receipt("duplicate"));
    }

    @Test
    void wrongBindingFieldsNeverCreateOrReplaceFacts() throws Exception {
        submitted();
        for (String field :
                List.of(
                        "jobId",
                        "runId",
                        "idempotencyKey",
                        "callId",
                        "attemptId",
                        "skillVersionId")) {
            var event = body("bad-" + field);
            event.put(
                    field,
                    Set.of("callId", "attemptId", "skillVersionId").contains(field)
                            ? 99
                            : "foreign");
            assertEquals(409, send(event).getResponse().getStatus(), field);
            assertEquals("UNKNOWN", status());
        }
    }

    @Test
    void currentScopeMustCoverOriginalTaskAndDenialIsAudited() throws Exception {
        submitted();
        String raw = Json.write(body("scope")), ts = "" + Instant.now().getEpochSecond();
        assertEquals(
                403,
                send(raw, ts, signature(ts, raw), ServiceTokens.issue(actor(Set.of("A"))))
                        .getResponse()
                        .getStatus());
        assertEquals("AUTHORIZATION_DENIED", receipt("scope"));
        assertEquals("UNKNOWN", status());
        assertEquals(200, send(body("scope")).getResponse().getStatus());
    }

    @Test
    void cancelledRunAndOldAttemptAreOnlyAudited() throws Exception {
        submitted();
        runState = "CANCELLED";
        assertEquals(200, send(body("cancelled")).getResponse().getStatus());
        assertEquals("LATE", receipt("cancelled"));
        assertEquals("UNKNOWN", status());
        runState = "RUNNING";
        authority.put("attempt_id", 457);
        assertEquals(200, send(body("old")).getResponse().getStatus());
        assertEquals("LATE", receipt("old"));
        assertEquals("UNKNOWN", status());
        assertEquals(0, facts.get());
    }

    @Test
    void malformedResultDoesNotBecomeAFinalFact() throws Exception {
        submitted();
        var event = body("invalid");
        event.put("result", Map.of("unexpected", true));
        assertEquals(422, send(event).getResponse().getStatus());
        assertEquals("OUTPUT_REJECTED", receipt("invalid"));
        assertEquals("UNKNOWN", status());
    }

    @Test
    void validFailurePersistsDeclaredSemantic() throws Exception {
        submitted();
        var event = body("failed");
        event.put("status", "FAILED");
        event.put("errorSemantic", "QUOTA");
        assertEquals(200, send(event).getResponse().getStatus());
        assertEquals("FAILED", status());
        assertEquals(
                "QUOTA",
                Json.read(
                                db.queryForObject(
                                        "select result::text from skill.execution_fact where"
                                                + " tenant_id=? and call_id=?",
                                        String.class,
                                        tenant,
                                        call))
                        .path("error")
                        .path("semantic")
                        .asText());
    }

    @Test
    void earlyCallbackWaitsForAckAndSameEventCanBeRetried() throws Exception {
        prepare();
        submitEntered = new CountDownLatch(1);
        releaseAck = new CountDownLatch(1);
        var future =
                workers.submit(
                        () -> {
                            authenticate(actor(Set.of("A", "B")));
                            return execution.execute("callback", request);
                        });
        assertTrue(submitEntered.await(5, TimeUnit.SECONDS));
        assertEquals(409, send(body("early")).getResponse().getStatus());
        assertEquals("WAITING_BINDING", receipt("early"));
        assertNull(
                db.queryForObject(
                        "select external_job_id from skill.external_job_binding where tenant_id=?"
                                + " and call_id=?",
                        String.class,
                        tenant,
                        call));
        releaseAck.countDown();
        future.get(10, TimeUnit.SECONDS);
        assertEquals(200, send(body("early")).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
        assertEquals(1, submits.get());
    }

    @Test
    void lostAckDoesNotAllowCallbackToInventBindingOrResubmit() throws Exception {
        dropAck = true;
        submitted();
        assertEquals(409, send(body("lost")).getResponse().getStatus());
        assertEquals("WAITING_BINDING", receipt("lost"));
        authenticate(actor(Set.of("A", "B")));
        execution.execute("callback", request);
        assertEquals(1, submits.get());
        assertEquals("UNKNOWN", status());
    }

    @Test
    void notSentFenceRejectsCallbackWithoutResultMaterialization() throws Exception {
        submitted();
        db.update(
                "update skill.external_job_binding set"
                    + " send_started=false,status='NOT_SENT',external_job_id=null where tenant_id=?"
                    + " and call_id=?",
                tenant,
                call);
        assertEquals(409, send(body("unsent")).getResponse().getStatus());
        assertEquals("UNKNOWN", status());
    }

    @Test
    void concurrentSameEventIsIdempotent() throws Exception {
        submitted();
        var futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 6; i++)
            futures.add(workers.submit(() -> send(body("race")).getResponse().getStatus()));
        for (var f : futures) assertTrue(Set.of(200, 409).contains(f.get(15, TimeUnit.SECONDS)));
        assertEquals(200, send(body("race")).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.callback_receipt where tenant_id=? and"
                                + " call_id=?",
                        Integer.class,
                        tenant,
                        call));
        assertEquals(1, submits.get());
    }

    @Test
    void responseLostAndOutboxFailureKeepDurableFactWithoutNewSubmission() throws Exception {
        submitted();
        send(body("response-lost"));
        rejectFact = true;
        authenticate(actor(Set.of("A", "B")));
        new SkillOutbox(db, execution).drainTenant(tenant, 1);
        assertEquals("SUCCEEDED", status());
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals(200, send(body("response-lost")).getResponse().getStatus());
        rejectFact = false;
        authenticate(actor(Set.of("A", "B")));
        assertEquals("SUCCEEDED", execution.retry(call).get("status"));
        assertEquals(1, submits.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"accepted", "late", "error"})
    void inFlightUnknownAcknowledgementCannotMarkNewSuccessDelivered(String mode) throws Exception {
        prepare();
        unknownEntered = new CountDownLatch(1);
        releaseUnknown = new CountDownLatch(1);
        var future =
                workers.submit(
                        () -> {
                            authenticate(actor(Set.of("A", "B")));
                            return execution.execute("callback", request);
                        });
        assertTrue(unknownEntered.await(5, TimeUnit.SECONDS));
        assertEquals(200, send(body("during-delivery")).getResponse().getStatus());
        lateFact = mode.equals("late");
        rejectFact = mode.equals("error");
        releaseUnknown.countDown();
        future.get(10, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", status());
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        assertNull(
                db.queryForObject(
                        "select callback_error from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
    }

    @Test
    void callbackOptionalLookupMustStillBeReadOnly() {
        contractExtra =
                Map.of(
                        "reconciliation",
                        Map.of(
                                "endpoint",
                                "http://127.0.0.1/lookup/{idempotencyKey}",
                                "method",
                                "DELETE",
                                "donePath",
                                "$.status",
                                "doneValue",
                                "DONE"));
        assertThrows(PlatformException.class, () -> catalog.create(contract()));
    }

    @Test
    void independentKeyLookupCanProveBindingAfterLostAck() throws Exception {
        contractExtra =
                Map.of(
                        "reconciliation",
                        Map.of(
                                "endpoint",
                                "http://127.0.0.1:"
                                        + provider.getAddress().getPort()
                                        + "/lookup/{idempotencyKey}",
                                "donePath",
                                "$.status",
                                "doneValue",
                                "DONE"));
        dropAck = true;
        submitted();
        assertEquals(409, send(body("lookup")).getResponse().getStatus());
        callState = "UNKNOWN";
        authenticate(actor(Set.of("A", "B")));
        assertEquals("UNKNOWN", execution.reconcile(call).get("status"));
        assertEquals(200, send(body("lookup")).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
        assertEquals(1, queries.get());
        assertEquals(1, submits.get());
    }

    @Test
    void returnMappingUsesOriginalRawSchemaThenProjectsResult() throws Exception {
        contractExtra =
                Map.of(
                        "returnSpec",
                        Map.of(
                                "structuredPath",
                                "$.value",
                                "modelSummary",
                                Map.of("mode", "TEMPLATE", "tpl", "value={{value}}"),
                                "cardTemplate",
                                Map.of("value", "{{value}}")));
        submitted();
        assertEquals(200, send(body("mapping")).getResponse().getStatus());
        var result =
                Json.read(
                        db.queryForObject(
                                "select result::text from skill.execution_fact where tenant_id=?"
                                        + " and call_id=?",
                                String.class,
                                tenant,
                                call));
        assertEquals(7, result.path("structured").asInt());
        assertEquals("value=7", result.path("modelSummary").asText());
        assertFalse(result.path("cardPayloadJson").asText().isBlank());
    }

    @Test
    void largeCallbackUsesControlledArtifactInsteadOfUnboundedResultTree() throws Exception {
        submitted();
        var event = body("large");
        event.put("result", Map.of("value", 7, "data", "x".repeat(2 * 1024 * 1024)));
        assertEquals(200, send(event).getResponse().getStatus());
        var result =
                Json.read(
                        db.queryForObject(
                                "select result::text from skill.execution_fact where tenant_id=?"
                                        + " and call_id=?",
                                String.class,
                                tenant,
                                call));
        assertTrue(result.path("structured").isNull());
        assertEquals(1, result.path("fileRefs").size());
    }

    @Test
    void trailingJsonAndDuplicateKeysAreRejectedWithoutChangingFact() throws Exception {
        submitted();
        String raw = Json.write(body("trailing")) + "{}", ts = "" + Instant.now().getEpochSecond();
        assertEquals(
                400,
                assertDoesNotThrow(
                                () ->
                                        send(
                                                raw,
                                                ts,
                                                signature(ts, raw),
                                                ServiceTokens.issue(actor(Set.of("A", "B")))))
                        .getResponse()
                        .getStatus());
        String duplicate =
                Json.write(body("duplicate-json"))
                        .replace("\"jobId\":\"job-1\"", "\"jobId\":\"job-1\",\"jobId\":\"other\"");
        assertEquals(
                400,
                send(
                                duplicate,
                                ts,
                                signature(ts, duplicate),
                                ServiceTokens.issue(actor(Set.of("A", "B"))))
                        .getResponse()
                        .getStatus());
        assertEquals("UNKNOWN", status());
    }

    @Test
    void abandonedProcessingLeaseCanBeRetriedButLiveLeaseCannot() throws Exception {
        submitted();
        String raw = Json.write(body("lease")), ts = "" + Instant.now().getEpochSecond();
        assertEquals(
                403,
                send(raw, ts, signature(ts, raw), ServiceTokens.issue(actor(Set.of("A"))))
                        .getResponse()
                        .getStatus());
        db.update(
                "update skill.callback_receipt set"
                    + " outcome='PROCESSING',processing_owner='crashed',processing_until=now()+interval"
                    + " '5 minutes' where tenant_id=? and call_id=?",
                tenant,
                call);
        assertEquals(409, send(body("lease")).getResponse().getStatus());
        db.update(
                "update skill.callback_receipt set processing_until=now()-interval '1 second' where"
                        + " tenant_id=? and call_id=?",
                tenant,
                call);
        assertEquals(200, send(body("lease")).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
    }

    @Test
    void atomicallyRollbackFactWhenReceiptUpdateFails() throws Exception {
        submitted();
        String trigger = "callback_test_" + Long.toUnsignedString(call);
        db.execute(
                "create function skill."
                        + trigger
                        + "() returns trigger language plpgsql as 'begin if NEW.outcome ="
                        + " ''STORED'' then raise exception ''injected receipt persistence"
                        + " failure''; end if; return NEW; end'");
        db.execute(
                "create trigger "
                        + trigger
                        + " before update on skill.callback_receipt for each row when (NEW.call_id="
                        + call
                        + ") execute function skill."
                        + trigger
                        + "()");
        try {
            assertThrows(Exception.class, () -> send(body("atomic")));
            assertEquals("UNKNOWN", status());
            assertNotEquals("STORED", receipt("atomic"));
        } finally {
            db.execute("drop trigger " + trigger + " on skill.callback_receipt");
            db.execute("drop function skill." + trigger + "()");
        }
        assertEquals(200, send(body("atomic")).getResponse().getStatus());
        assertEquals("SUCCEEDED", status());
    }

    @Test
    void receiptReportsDeliveryAcceptanceWithoutClaimingGlobalLogicalAcceptance() throws Exception {
        submitted();
        assertEquals(200, send(body("late-engine")).getResponse().getStatus());
        lateFact = true;
        authenticate(actor(Set.of("A", "B")));
        assertEquals("UNKNOWN", execution.retry(call).get("status"));
        var result =
                Json.read(send(body("late-engine")).getResponse().getContentAsString())
                        .path("data");
        assertEquals("STORED", result.path("outcome").asText());
        assertEquals("LATE", result.path("deliveryState").asText());
        assertTrue(result.path("deliveryAccepted").isBoolean());
        assertFalse(result.path("deliveryAccepted").asBoolean());
        assertFalse(result.has("logicalAccepted"));
    }

    @Test
    void deliveredReceiptReportsAcceptedDeliveryWithoutGlobalLogicalAcceptanceField()
            throws Exception {
        submitted();
        assertEquals(200, send(body("delivered-field")).getResponse().getStatus());
        authenticate(actor(Set.of("A", "B")));
        assertEquals("SUCCEEDED", execution.retry(call).get("status"));
        var result =
                Json.read(send(body("delivered-field")).getResponse().getContentAsString())
                        .path("data");
        assertEquals("STORED", result.path("outcome").asText());
        assertEquals("DELIVERED", result.path("deliveryState").asText());
        assertTrue(result.path("deliveryAccepted").isBoolean());
        assertTrue(result.path("deliveryAccepted").asBoolean());
        assertFalse(result.has("logicalAccepted"));
    }

    @Test
    void callbackSecretReferenceIsRedactedFromManagementView() {
        prepare();
        var controller = new cn.agentplatform.skill.api.SkillController(catalog, execution, null);
        var view = Json.tree(controller.get("callback"));
        assertFalse(view.toString().contains(secretRef));
        assertTrue(view.path("data").path("callback").path("secretConfigured").asBoolean());
    }

    @Test
    void expiredReceiptProcessorIsSupersededWhenIndependentRecoveryAlreadyStoredFact()
            throws Exception {
        submitted();
        String raw = Json.write(body("recovered")), ts = "" + Instant.now().getEpochSecond();
        assertEquals(
                403,
                send(raw, ts, signature(ts, raw), ServiceTokens.issue(actor(Set.of("A"))))
                        .getResponse()
                        .getStatus());
        db.update(
                "update skill.callback_receipt set"
                    + " outcome='PROCESSING',processing_owner='crashed',processing_until=now()-interval"
                    + " '1 second' where tenant_id=? and call_id=?",
                tenant,
                call);
        db.update(
                "update skill.execution_fact set status='SUCCEEDED' where tenant_id=? and"
                        + " call_id=?",
                tenant,
                call);
        assertEquals(200, send(body("recovered")).getResponse().getStatus());
        assertEquals("SUPERSEDED", receipt("recovered"));
    }

    @Test
    void validFailureWithoutSuccessPayloadPersistsAnEmptyFailureEnvelope() throws Exception {
        submitted();
        var event = body("failed-no-result");
        event.remove("result");
        event.put("status", "FAILED");
        event.put("errorSemantic", "QUOTA");
        assertEquals(200, send(event).getResponse().getStatus());
        assertEquals("FAILED", status());
        var envelope =
                Json.read(
                        db.queryForObject(
                                "select result::text from skill.execution_fact where tenant_id=?"
                                        + " and call_id=?",
                                String.class,
                                tenant,
                                call));
        assertEquals("QUOTA", envelope.path("error").path("semantic").asText());
        assertTrue(envelope.path("structured").isNull());
        assertTrue(envelope.path("fileRefs").isEmpty());
    }

    @Test
    void failureDoesNotMaterializeProviderErrorPayloadAsSuccessfulOutput() throws Exception {
        submitted();
        var event = body("failed-extra");
        event.put("status", "FAILED");
        event.put("errorSemantic", "PARAM_ERROR");
        event.put("result", Map.of("error", "rejected parameter"));
        assertEquals(200, send(event).getResponse().getStatus());
        assertEquals("FAILED", status());
        assertTrue(
                Json.read(
                                db.queryForObject(
                                        "select result::text from skill.execution_fact where"
                                                + " tenant_id=? and call_id=?",
                                        String.class,
                                        tenant,
                                        call))
                        .path("structured")
                        .isNull());
    }

    @Test
    void unknownOrMissingFailureSemanticCannotCreateConfirmedFailure() throws Exception {
        submitted();
        for (String semantic : List.of("RESULT_UNKNOWN", "", "unrecognized")) {
            var event = body("unconfirmed-" + semantic);
            event.remove("result");
            event.put("status", "FAILED");
            event.put("errorSemantic", semantic);
            assertEquals(422, send(event).getResponse().getStatus());
            assertEquals("UNKNOWN", status());
        }
    }
}
