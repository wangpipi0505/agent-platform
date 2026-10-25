package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP server is a test fixture; all binding arbitration uses a dedicated real PostgreSQL database.
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class AsyncHttpExecutionTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    String tenant, code, key;
    long call;
    HttpServer server;
    SkillCatalog catalog;
    SkillClients clients;
    SkillExecution executor;
    Map<String, Object> request;
    final AtomicInteger submits = new AtomicInteger(), queries = new AtomicInteger();
    volatile String state = "RUNNING";
    volatile boolean wrongJob;
    volatile boolean bindingBeforeSend;
    volatile String receivedKey;
    volatile boolean dropAck;
    volatile Object actualResult = Map.of("value", 7);
    boolean lookupByKey;
    int submitStatus = 202;
    volatile boolean bindDifferentJobDuringQuery;
    Map<String, Object> arguments = Map.of("region", "A");
    Map<String, Object> guard =
            Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A")));
    Map<String, Object> authority;
    HttpServer engine;
    ExecutorService httpWorkers;
    final AtomicReference<String> engineState = new AtomicReference<>("EXECUTING");
    final AtomicInteger facts = new AtomicInteger();
    CountDownLatch beforeSubmitted, releaseSubmitted;
    volatile boolean rejectFacts;
    volatile boolean lateFacts;
    volatile boolean dropSubmittedAck;
    CountDownLatch afterSubmitted, releaseSubmittedAck;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_async_test",
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
        tenant = "async_" + UUID.randomUUID();
        code = "async";
        key = "key/+ ?" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        authenticate(List.of("ADMIN", "EDITOR", "SERVICE"), List.of("A"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/submit",
                exchange -> {
                    submits.incrementAndGet();
                    receivedKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    bindingBeforeSend =
                            db.queryForObject(
                                            "select count(*) from skill.external_job_binding where"
                                                    + " tenant_id=? and call_id=?",
                                            Integer.class,
                                            tenant,
                                            call)
                                    == 1;
                    exchange.getRequestBody().readAllBytes();
                    if (dropAck) {
                        exchange.close();
                        return;
                    }
                    byte[] body =
                            Json.write(Map.of("jobId", "job-1", "idempotencyKey", key))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(submitStatus, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/jobs",
                exchange -> {
                    queries.incrementAndGet();
                    if (bindDifferentJobDuringQuery)
                        db.update(
                                "update skill.external_job_binding set"
                                        + " external_job_id='competing-job' where tenant_id=? and"
                                        + " call_id=?",
                                tenant,
                                call);
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "jobId",
                                                    wrongJob ? "other-job" : "job-1",
                                                    "idempotencyKey",
                                                    key,
                                                    "status",
                                                    state,
                                                    "result",
                                                    actualResult))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, tx, clients, "http://unused");
    }

    void authenticate(List<String> roles, List<String> regions) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("owner")
                        .claim("tenant_id", tenant)
                        .claim("roles", roles)
                        .claim("attributes", Map.of("region", regions))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> contract() {
        var c = new LinkedHashMap<String, Object>();
        c.put("code", code);
        c.put("name", "async");
        c.put("type", "HTTP");
        c.put("description", "查询任务结果");
        c.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/submit");
        c.put("invocationKind", "SUBMIT_QUERY");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", 100);
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
        c.put("guard", guard);
        c.put(
                "reconciliation",
                Map.of(
                        "endpoint",
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/jobs/"
                                + (lookupByKey ? "{idempotencyKey}" : "{jobId}"),
                        "method",
                        "GET",
                        "donePath",
                        "$.status",
                        "doneValue",
                        "DONE"));
        return c;
    }

    void prepareExecution() {
        long version = ((Number) catalog.create(contract()).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_async",
                                "nodeInstanceId",
                                123,
                                "attemptId",
                                456,
                                "skillVersionId",
                                version,
                                "arguments",
                                arguments,
                                "idempotencyKey",
                                key,
                                "leaseOwner",
                                "worker"));
        authority =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "claimed",
                                true,
                                "status",
                                "UNKNOWN",
                                "skill_version_id",
                                version,
                                "skill_code",
                                code,
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                "r_async",
                                "trigger_type",
                                "TASK",
                                "idempotency_key",
                                key,
                                "mock_snapshot",
                                Map.of()));
        authority.put("callId", call);
        authority.put("attempt_id", 456);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        when(clients.get(anyString()))
                .thenAnswer(
                        i ->
                                Json.tree(
                                        i.<String>getArgument(0).contains("/admin/runs/")
                                                ? Map.of(
                                                        "runId",
                                                        "r_async",
                                                        "status",
                                                        "RUNNING",
                                                        "nodes",
                                                        List.of(
                                                                Map.of(
                                                                        "nodeInstanceId",
                                                                        123,
                                                                        "status",
                                                                        "RUNNING")))
                                                : authority));
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        i -> {
                            String url = i.getArgument(0);
                            return Json.tree(
                                    url.endsWith("/claim")
                                            ? authority
                                            : url.endsWith("/submitted")
                                                    ? Map.of("submitted", true)
                                                    : Map.of("accepted", true));
                        });
        executor =
                new SkillExecution(
                        db,
                        tx,
                        catalog,
                        new SkillAdapters(
                                new ArtifactStore(db),
                                new AlgorithmAdapter(db),
                                new HttpAsyncAdapter(db)),
                        clients,
                        "http://engine.test");
    }

    @AfterEach
    void cleanup() {
        if (releaseSubmitted != null) releaseSubmitted.countDown();
        if (releaseSubmittedAck != null) releaseSubmittedAck.countDown();
        if (engine != null) engine.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow();
        if (server != null) server.stop(0);
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitOnceAndReconcileOriginalJobWithRealResult() {
        prepareExecution();
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        assertTrue(bindingBeforeSend);
        assertEquals(key, receivedKey);
        assertEquals(1, submits.get());
        state = "DONE";
        var recovered = executor.reconcile(call);
        assertEquals("SUCCEEDED", recovered.get("status"));
        assertEquals(
                7, Json.tree(recovered.get("result")).path("structured").path("value").asInt());
        executor.execute(code, request);
        assertEquals(1, submits.get());
        assertTrue(queries.get() > 0);
    }

    @Test
    void foreignJobNeverBecomesSuccessAndDoesNotResubmit() {
        prepareExecution();
        executor.execute(code, request);
        state = "DONE";
        wrongJob = true;
        var result = executor.reconcile(call);
        assertEquals("UNKNOWN", result.get("status"));
        assertEquals(1, submits.get());
    }

    @Test
    void lostAcknowledgementRecoversByExactKeyWithoutSecondSubmission() {
        lookupByKey = true;
        dropAck = true;
        prepareExecution();
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        state = "DONE";
        assertEquals("SUCCEEDED", executor.reconcile(call).get("status"));
        assertEquals(1, submits.get());
        assertEquals(
                "job-1",
                db.queryForObject(
                        "select external_job_id from skill.external_job_binding where tenant_id=?"
                                + " and call_id=?",
                        String.class,
                        tenant,
                        call));
    }

    @Test
    void malformedFinalResultStaysUnknownWithoutResubmit() {
        prepareExecution();
        executor.execute(code, request);
        state = "DONE";
        actualResult = Map.of("wrong", 7);
        assertEquals("UNKNOWN", executor.reconcile(call).get("status"));
        assertEquals(1, submits.get());
    }

    @Test
    void largeAsyncResultUsesControlledArtifactInsteadOfProtocolBuffer() {
        prepareExecution();
        executor.execute(code, request);
        state = "DONE";
        actualResult = Map.of("value", 7, "data", Collections.nCopies(6000, "x".repeat(3000)));
        var result = executor.reconcile(call);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals(1, Json.tree(result.get("result")).path("fileRefs").size());
        assertEquals(1, submits.get());
    }

    @Test
    void explicitSubmitDenialIsPermissionFailureRatherThanUnknown() {
        submitStatus = 403;
        prepareExecution();
        var result = executor.execute(code, request);
        assertEquals("FAILED", result.get("status"));
        assertEquals("NO_PERMISSION", result.get("errorSemantic"));
        assertEquals(0, queries.get());
    }

    @Test
    void unsupportedHttpMappingCannotSilentlyLoseItsRequestSemantics() {
        var c = contract();
        c.put("httpMethod", "DELETE");
        var ex = assertThrows(PlatformException.class, () -> catalog.create(c));
        assertEquals("INVALID_ASYNC_PROTOCOL", ex.code());
    }

    @Test
    void concurrentKeyLookupCannotAdoptResultForLosingJobBinding() {
        lookupByKey = true;
        dropAck = true;
        prepareExecution();
        executor.execute(code, request);
        bindDifferentJobDuringQuery = true;
        state = "DONE";
        assertEquals("UNKNOWN", executor.reconcile(call).get("status"));
        assertEquals(1, submits.get());
    }

    void prepareWideScope() {
        arguments = Map.of("region", List.of("A", "B"));
        guard = Map.of("roles", List.of("READER"), "attributes", Map.of("region", List.of()));
        prepareExecution();
        authenticate(List.of("SERVICE", "READER"), List.of("A", "B"));
        assertEquals("UNKNOWN", executor.execute(code, request).get("status"));
        state = "DONE";
        queries.set(0);
        clearInvocations(clients);
    }

    void assertScopeDenied() {
        var denied = assertThrows(PlatformException.class, () -> executor.reconcile(call));
        assertEquals(403, denied.status());
        assertEquals(0, queries.get());
        assertEquals(1, submits.get());
        var fact =
                db.queryForMap(
                        "select * from skill.execution_fact where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals("UNKNOWN", fact.get("status"));
        assertTrue(Json.read(fact.get("result").toString()).path("structured").isNull());
        assertEquals(0, Json.read(fact.get("result").toString()).path("fileRefs").size());
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.reconciliation_note where tenant_id=? and"
                                + " call_id=? and outcome='AUTHORIZATION_DENIED'",
                        Integer.class,
                        tenant,
                        call));
        verify(clients, never()).post(endsWith("/fact"), any());
    }

    @Test
    void nonemptyScopeNarrowingCannotQueryOrAdoptWideHistoricalResult() {
        prepareWideScope();
        authenticate(List.of("SERVICE", "READER"), List.of("A"));
        assertScopeDenied();
    }

    @Test
    void roleRevocationIsAuditedWithoutChangingExternalUnknown() {
        prepareWideScope();
        authenticate(List.of("SERVICE"), List.of("A", "B"));
        assertScopeDenied();
    }

    @Test
    void missingCurrentAuthorizationEvidenceIsAuditedAndDenied() {
        prepareWideScope();
        authenticate(List.of("SERVICE", "READER"), List.of());
        assertScopeDenied();
    }

    @Test
    void missingFrozenGuardEvidenceIsDenied() {
        prepareWideScope();
        db.update(
                "update skill.execution_fact set"
                    + " request_snapshot=request_snapshot-'guardedArguments' where tenant_id=? and"
                    + " call_id=?",
                tenant,
                call);
        assertScopeDenied();
    }

    @Test
    void reorderedFrozenScopeAndExpandedAuthorizationStillRecover() {
        prepareWideScope();
        db.update(
                "update skill.execution_fact set"
                    + " request_snapshot=jsonb_set(request_snapshot,'{guardedArguments,region}','[\"B\",\"A\"]'::jsonb)"
                    + " where tenant_id=? and call_id=?",
                tenant,
                call);
        authenticate(List.of("SERVICE", "READER"), List.of("C", "B", "A"));
        assertEquals("SUCCEEDED", executor.reconcile(call).get("status"));
        assertEquals(1, queries.get());
        assertEquals(1, submits.get());
    }

    @Test
    void expandedEffectiveScopeStillCoversOriginalTask() {
        arguments = Map.of("region", List.of("A", "B"));
        guard = Map.of("roles", List.of("READER"), "attributes", Map.of("region", List.of()));
        prepareExecution();
        authenticate(List.of("SERVICE", "READER"), List.of("A"));
        executor.execute(code, request);
        authenticate(List.of("SERVICE", "READER"), List.of("A", "B"));
        state = "DONE";
        assertEquals("SUCCEEDED", executor.reconcile(call).get("status"));
        assertEquals(1, submits.get());
    }

    // Controlled HTTP authority fixture copies only the reviewed EXECUTING/SUBMITTED/fact CAS.
    // It is deliberately not claimed as a real engine process or a full cross-service acceptance.
    void realAuthority() throws Exception {
        engineState.set("REGISTERED");
        beforeSubmitted = new CountDownLatch(1);
        releaseSubmitted = new CountDownLatch(1);
        authority.put("claimed_by", "worker");
        authority.put("attempt_id", 456);
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpWorkers = Executors.newCachedThreadPool();
        engine.setExecutor(httpWorkers);
        engine.createContext(
                "/admin/runs/",
                exchange -> {
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "runId",
                                                    "r_async",
                                                    "status",
                                                    "RUNNING",
                                                    "nodes",
                                                    List.of(
                                                            Map.of(
                                                                    "nodeInstanceId",
                                                                    123,
                                                                    "status",
                                                                    "RUNNING"))))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        engine.createContext(
                "/internal/tool-calls/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    Object response;
                    if (path.endsWith("/submitted")) {
                        beforeSubmitted.countDown();
                        try {
                            if (!releaseSubmitted.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("barrier timed out");
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
                        }
                        response =
                                Map.of(
                                        "submitted",
                                        engineState.compareAndSet("EXECUTING", "SUBMITTED"));
                        if (afterSubmitted != null) {
                            afterSubmitted.countDown();
                            try {
                                if (!releaseSubmittedAck.await(15, TimeUnit.SECONDS))
                                    throw new IllegalStateException("ack barrier timed out");
                            } catch (InterruptedException ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                        if (dropSubmittedAck) {
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                            return;
                        }
                    } else if (path.endsWith("/fact")) {
                        facts.incrementAndGet();
                        var body =
                                Json.read(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        if (rejectFacts) {
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                            return;
                        }
                        if (!lateFacts) engineState.set(body.path("status").asText());
                        response = Map.of("accepted", !lateFacts, "late", lateFacts);
                    } else {
                        var current = new LinkedHashMap<>(authority);
                        if (path.endsWith("/claim"))
                            current.put(
                                    "claimed",
                                    engineState.compareAndSet("REGISTERED", "EXECUTING"));
                        current.put("status", engineState.get());
                        response = current;
                    }
                    byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        engine.start();
        clients = new SkillClients();
        executor =
                new SkillExecution(
                        db,
                        tx,
                        catalog,
                        new SkillAdapters(
                                new ArtifactStore(db),
                                new AlgorithmAdapter(db),
                                new HttpAsyncAdapter(db)),
                        clients,
                        "http://127.0.0.1:" + engine.getAddress().getPort());
    }

    @Test
    void outboxCannotDeliverRegistrationBeforeSubmittedRpcAndOnlyOneBusinessSend()
            throws Exception {
        prepareExecution();
        realAuthority();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var running =
                    worker.submit(
                            () -> {
                                authenticate(List.of("SERVICE"), List.of("A"));
                                try {
                                    return executor.execute(code, request);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            try {
                assertTrue(beforeSubmitted.await(10, TimeUnit.SECONDS));
                assertEquals(
                        1,
                        db.queryForObject(
                                "select count(*) from skill.external_job_binding where tenant_id=?"
                                        + " and call_id=?",
                                Integer.class,
                                tenant,
                                call));
                new SkillOutbox(db, executor).drainTenant(tenant, 1);
                assertEquals(0, facts.get(), "pre-registration is not an execution fact");
                assertEquals("EXECUTING", engineState.get());
                executor.retry(call);
                executor.execute(code, request);
                assertEquals(0, facts.get());
                assertEquals(
                        "RECONCILE_STATE",
                        assertThrows(PlatformException.class, () -> executor.reconcile(call))
                                .code());
            } finally {
                releaseSubmitted.countDown();
            }
            assertEquals("UNKNOWN", running.get(10, TimeUnit.SECONDS).get("status"));
        }
        assertEquals(1, submits.get());
        assertEquals(1, facts.get());
        executor.retry(call);
        new SkillOutbox(db, executor).drainTenant(tenant, 1);
        assertEquals(1, submits.get());
        assertEquals(1, facts.get());
        state = "DONE";
        assertEquals("SUCCEEDED", executor.reconcile(call).get("status"));
        assertEquals(2, facts.get());
        assertEquals(1, submits.get());
    }

    @Test
    void interruptedBeforeSubmittedRecordsNotSentOnlyAfterAuthorityBecomesUnknown()
            throws Exception {
        lookupByKey = true;
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        dropSubmittedAck = true; // Lost submitted ACK: no permission to dispatch again.
        assertThrows(PlatformException.class, () -> executor.execute(code, request));
        assertEquals(0, submits.get());
        engineState.set("UNKNOWN");
        var result = executor.reconcile(call);
        assertEquals("NOT_SENT", result.get("outcome"));
        assertEquals("UNKNOWN", result.get("status"));
        assertEquals(0, queries.get());
        assertEquals(0, submits.get());
        new SkillOutbox(db, executor).drainTenant(tenant, 1);
        assertEquals(1, facts.get());
        executor.execute(code, request);
        assertEquals(0, submits.get());
        state = "DONE";
        assertEquals("NOT_SENT", executor.reconcile(call).get("outcome"));
        assertEquals(0, queries.get());
    }

    @Test
    void actualUnknownOutboxUsesPostgresSkipLockedAndNeverReexecutesBusiness() throws Exception {
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        rejectFacts = true;
        executor.execute(code, request);
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        try (var connection = Objects.requireNonNull(db.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "select call_id from skill.execution_fact where tenant_id=? and"
                                    + " call_id=? for update")) {
                lock.setString(1, tenant);
                lock.setLong(2, call);
                lock.executeQuery().close();
                assertEquals(0, new SkillOutbox(db, executor).drainTenant(tenant, 1));
            } finally {
                connection.rollback();
            }
        }
        rejectFacts = false;
        assertEquals(1, new SkillOutbox(db, executor).drainTenant(tenant, 1));
        assertEquals("UNKNOWN", engineState.get());
        assertEquals(0, new SkillOutbox(db, executor).drainTenant(tenant, 1));
        assertEquals(1, submits.get());
    }

    @Test
    void completedFactIsQueuedAndLateDeliveryNeverRevivesCancelledAuthority() throws Exception {
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        state = "DONE";
        rejectFacts = true;
        executor.execute(code, request);
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "select status from skill.execution_fact where tenant_id=? and call_id=?",
                        String.class,
                        tenant,
                        call));
        rejectFacts = false;
        lateFacts = true;
        engineState.set("CANCELLED");
        assertEquals(1, new SkillOutbox(db, executor).drainTenant(tenant, 1));
        assertEquals("CANCELLED", engineState.get());
        assertEquals(
                "LATE",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals(1, submits.get());
    }

    @Test
    void sentPreparationCrashImageRecoversByOriginalKeyUnderExpiredLease() throws Exception {
        lookupByKey = true;
        dropAck = true;
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        rejectFacts = true;
        executor.execute(code, request);
        // Reconstruct the durable image at process loss after send_started, before fact UPDATE.
        // Actual dispatch/ACK loss above used HTTP; this is not a process-kill acceptance test.
        db.update(
                "update skill.execution_fact set callback_state='PREPARING' where tenant_id=? and"
                        + " call_id=?",
                tenant,
                call);
        assertEquals(
                true,
                db.queryForObject(
                        "select send_started from skill.external_job_binding where tenant_id=? and"
                                + " call_id=?",
                        Boolean.class,
                        tenant,
                        call));
        engineState.set("UNKNOWN");
        state = "DONE";
        rejectFacts = false;
        lateFacts = true;
        assertEquals("UNKNOWN", executor.reconcile(call).get("status"));
        assertEquals(
                "SUCCEEDED",
                db.queryForObject(
                        "select status from skill.execution_fact where tenant_id=? and call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals(
                "LATE",
                db.queryForObject(
                        "select callback_state from skill.execution_fact where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals("UNKNOWN", engineState.get());
        assertEquals(1, submits.get());
    }

    @Test
    void cancelledPreparationCannotDeliverQueryOrResubmit() throws Exception {
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        dropSubmittedAck = true;
        assertThrows(PlatformException.class, () -> executor.execute(code, request));
        engineState.set("CANCELLED");
        executor.execute(code, request);
        executor.retry(call);
        new SkillOutbox(db, executor).drainTenant(tenant, 1);
        assertEquals(
                "RECONCILE_STATE",
                assertThrows(PlatformException.class, () -> executor.reconcile(call)).code());
        assertEquals("CANCELLED", engineState.get());
        assertEquals(0, facts.get());
        assertEquals(0, submits.get());
        assertEquals(0, queries.get());
    }

    @Test
    void unknownAuthorityFencesLateSubmittedAcknowledgementBeforeAnyBusinessSend()
            throws Exception {
        prepareExecution();
        realAuthority();
        releaseSubmitted.countDown();
        afterSubmitted = new CountDownLatch(1);
        releaseSubmittedAck = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var running =
                    worker.submit(
                            () -> {
                                authenticate(List.of("SERVICE"), List.of("A"));
                                try {
                                    return executor.execute(code, request);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            try {
                assertTrue(afterSubmitted.await(10, TimeUnit.SECONDS));
                engineState.set(
                        "UNKNOWN"); // Engine lease expires while submitted response is delayed.
                lateFacts = true;
                assertEquals("NOT_SENT", executor.reconcile(call).get("outcome"));
            } finally {
                releaseSubmittedAck.countDown();
            }
            assertEquals("UNKNOWN", running.get(10, TimeUnit.SECONDS).get("status"));
        }
        assertEquals(0, submits.get(), "NOT_SENT recovery must fence the delayed original sender");
        assertEquals(0, queries.get());
        assertEquals("UNKNOWN", engineState.get());
    }
}
