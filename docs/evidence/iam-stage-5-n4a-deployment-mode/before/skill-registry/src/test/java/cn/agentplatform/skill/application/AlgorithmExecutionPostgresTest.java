package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real PG + fixed SDK HTTP. The engine peer supplies authority; no process/cluster claim. */
class AlgorithmExecutionPostgresTest {
    static AlgorithmTestDatabase database;
    @TempDir Path secrets;
    AlgorithmApiFixture api;
    HttpServer engine;
    ExecutorService workers;
    SkillCatalog catalog;
    ArtifactStore artifacts;
    AlgorithmAdapter algorithm;
    SkillAdapters adapters;
    SkillExecution execution;
    Map<String, Object> request, authority;
    String tenant, run;
    long call, version;
    AtomicInteger submitted = new AtomicInteger(),
            deliveries = new AtomicInteger(),
            logicalCommits = new AtomicInteger();
    volatile boolean acceptSubmit = true, acceptFact = true, approved = true, lateFact;
    volatile Runnable atSubmitted = () -> {};

    @BeforeAll
    static void database() {
        database = new AlgorithmTestDatabase("agent_platform_skill_algorithm_test", null);
    }

    @BeforeEach
    void start() throws Exception {
        tenant = "algorithm_execution_" + UUID.randomUUID();
        run = "r_" + UUID.randomUUID();
        call = System.nanoTime();
        AlgorithmTestDatabase.identity(tenant, "owner");
        System.setProperty("agent.secret.dir", secrets.toString());
        System.setProperty("agent.master.key", "algorithm-only-test-master-key-32-characters");
        api = new AlgorithmApiFixture();
        api.expectedBearer = "finite-fixture-bearer";
        workers = Executors.newVirtualThreadPerTaskExecutor();
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.setExecutor(workers);
        engine.createContext(
                "/internal/tool-calls",
                x -> {
                    try {
                        String path = x.getRequestURI().getPath();
                        x.getRequestBody().readNBytes(65537);
                        Map<String, Object> reply;
                        if (path.endsWith("/approval"))
                            reply =
                                    Map.of(
                                            "approved",
                                            approved,
                                            "skillVersionId",
                                            version,
                                            "parametersDigest",
                                            Digest.sha256(
                                                    Json.canonical(request.get("arguments"))));
                        else if (path.endsWith("/claim")) {
                            var claim = new LinkedHashMap<>(authority);
                            claim.put("claimed", true);
                            reply = claim;
                        } else if (path.endsWith("/submitted")) {
                            submitted.incrementAndGet();
                            atSubmitted.run();
                            reply = Map.of("submitted", acceptSubmit);
                        } else if (path.endsWith("/fact")) {
                            deliveries.incrementAndGet();
                            if (acceptFact && !lateFact) logicalCommits.compareAndSet(0, 1);
                            reply = Map.of("accepted", acceptFact && !lateFact, "late", lateFact);
                        } else if (path.startsWith("/internal/runs/"))
                            reply =
                                    Map.of(
                                            "status",
                                            "RUNNING",
                                            "trigger_type",
                                            "TASK",
                                            "nodes",
                                            List.of(
                                                    Map.of(
                                                            "nodeInstanceId",
                                                            123,
                                                            "status",
                                                            "WAITING_EXTERNAL")));
                        else reply = authority;
                        byte[] body =
                                Json.write(ApiResponse.ok(reply)).getBytes(StandardCharsets.UTF_8);
                        x.getResponseHeaders().set("Content-Type", "application/json");
                        x.sendResponseHeaders(200, body.length);
                        x.getResponseBody().write(body);
                    } finally {
                        x.close();
                    }
                });
        engine.createContext(
                "/internal/runs",
                x -> {
                    byte[] body =
                            Json.write(
                                            ApiResponse.ok(
                                                    Map.of(
                                                            "status",
                                                            "RUNNING",
                                                            "trigger_type",
                                                            "TASK",
                                                            "nodes",
                                                            List.of(
                                                                    Map.of(
                                                                            "nodeInstanceId",
                                                                            123,
                                                                            "status",
                                                                            "WAITING_EXTERNAL")))))
                                    .getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, body.length);
                    x.getResponseBody().write(body);
                    x.close();
                });
        engine.start();
        engine.createContext(
                "/admin/runs",
                x -> {
                    byte[] body =
                            Json.write(
                                            ApiResponse.ok(
                                                    Map.of(
                                                            "runId",
                                                            run,
                                                            "status",
                                                            "RUNNING",
                                                            "nodes",
                                                            List.of(
                                                                    Map.of(
                                                                            "nodeInstanceId",
                                                                            123,
                                                                            "status",
                                                                            "WAITING_EXTERNAL")))))
                                    .getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, body.length);
                    x.getResponseBody().write(body);
                    x.close();
                });
        var clients = new SkillClients();
        catalog = new SkillCatalog(database.db, database.manager, clients, "http://unused");
        artifacts = new ArtifactStore(database.db);
        algorithm =
                AlgorithmTestDatabase.adapter(
                        database.db, api, 1024 * 1024, ArtifactStore.MAX_ARTIFACT_BYTES);
        adapters = new SkillAdapters(artifacts, algorithm);
        execution =
                new SkillExecution(
                        database.db,
                        database.manager,
                        catalog,
                        adapters,
                        clients,
                        "http://127.0.0.1:" + engine.getAddress().getPort());
        prepare(false);
    }

    void prepare(boolean write) {
        var c = new LinkedHashMap<String, Object>();
        c.putAll(
                Map.of(
                        "code",
                        "algorithm",
                        "name",
                        "Algorithm fixture",
                        "description",
                        "finite protocol",
                        "type",
                        "ALGORITHM",
                        "timeoutMs",
                        500,
                        "authRef",
                        SecretVault.put("finite-fixture-bearer")));
        c.put(
                "jobTemplate",
                Map.of("image", "fixture/algorithm:fixed", "command", List.of("/algorithm")));
        c.put(
                "inputSchema",
                Map.of("type", "object", "properties", Map.of("region", Map.of("type", "string"))));
        c.put("outputSchema", Map.of("type", "object"));
        c.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        c.put(
                "execution",
                Map.of(
                        "readonly",
                        !write,
                        "sideEffect",
                        write,
                        "idempotent",
                        false,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("ADMIN")));
        c.put("constraints", Map.of("outputMaxBytes", 8192));
        version = ((Number) catalog.create(c).get("skillVersionId")).longValue();
        database.db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                tenant,
                version);
        database.db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where tenant_id=?"
                        + " and code='algorithm'",
                version,
                tenant);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "skillVersionId",
                                version,
                                "attemptId",
                                456L,
                                "leaseOwner",
                                "worker",
                                "nodeInstanceId",
                                123L,
                                "runId",
                                run,
                                "arguments",
                                Map.of("region", "A"),
                                "idempotencyKey",
                                "logical-" + call));
        authority =
                new LinkedHashMap<>(
                        Map.of(
                                "skill_version_id",
                                version,
                                "skill_code",
                                "algorithm",
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123L,
                                "runId",
                                run,
                                "idempotency_key",
                                "logical-" + call,
                                "trigger_type",
                                "TASK",
                                "attempt_id",
                                456L,
                                "status",
                                "REGISTERED",
                                "mock_snapshot",
                                Map.of()));
        authority.put("callId", call);
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        authority.put("claimed_by", "worker");
    }

    String safeFactDiagnostic() {
        var current = fact();
        var error = Json.read(current.get("result").toString()).path("error");
        return "call="
                + call
                + " attempt="
                + current.get("attempt_id")
                + " error="
                + error.path("reason").asText();
    }

    Map<String, Object> fact() {
        return database.db.queryForMap(
                "select * from skill.execution_fact where tenant_id=? and call_id=?", tenant, call);
    }

    Map<String, Object> binding() {
        return database.db.queryForMap(
                "select * from skill.external_job_binding where tenant_id=? and call_id=?",
                tenant,
                call);
    }

    @AfterEach
    void stop() {
        if (artifacts != null) artifacts.close();
        if (engine != null) engine.stop(0);
        if (api != null) api.close();
        if (workers != null) workers.shutdownNow();
        SecurityContextHolder.clearContext();
        System.clearProperty("agent.secret.dir");
        System.clearProperty("agent.master.key");
        if (api != null) api.assertHealthy();
    }

    @Test
    void preparingAndRegisteredExistBeforeSubmittedAndFirstPost() {
        var checked = new AtomicBoolean();
        atSubmitted =
                () -> {
                    assertEquals("PREPARING", fact().get("callback_state"));
                    assertEquals("REGISTERED", binding().get("status"));
                    checked.set(true);
                };
        api.beforePost =
                body -> {
                    assertEquals(1, submitted.get());
                    assertEquals("PREPARING", fact().get("callback_state"));
                    assertEquals("SENDING", binding().get("status"));
                    assertEquals(true, binding().get("send_started"));
                };
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        assertTrue(checked.get());
        assertEquals(1, api.posts.get());
        assertEquals("DONE", binding().get("status"));
    }

    @Test
    void registrationAndFactRollbackTogetherBeforeAnyNetwork() {
        var contract = catalog.versionId(version);
        var frozen = new LinkedHashMap<>(request);
        frozen.put("guardedArguments", request.get("arguments"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new TransactionTemplate(database.manager)
                                .executeWithoutResult(
                                        t -> {
                                            adapters.bind(contract, frozen);
                                            assertEquals("REGISTERED", binding().get("status"));
                                            throw new IllegalStateException("rollback");
                                        }));
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.external_job_binding where tenant_id=?",
                        Integer.class,
                        tenant));
        assertEquals(0, api.posts.get());
    }

    @Test
    void submittedRefusalLeavesPreparingAndNeverPosts() {
        acceptSubmit = false;
        assertThrows(PlatformException.class, () -> execution.execute("algorithm", request));
        assertEquals("PREPARING", fact().get("callback_state"));
        assertEquals(false, binding().get("send_started"));
        assertEquals(0, api.posts.get());
    }

    @Test
    void ackLostThenNewAdapterQueryConvergesWithoutReplay() {
        api.dropPost = true;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        assertEquals(1, api.posts.get());
        authority.put("status", "UNKNOWN");
        api.dropPost = false;
        var fresh =
                new SkillExecution(
                        database.db,
                        database.manager,
                        catalog,
                        new SkillAdapters(
                                artifacts,
                                AlgorithmTestDatabase.adapter(
                                        database.db,
                                        api,
                                        1048576,
                                        ArtifactStore.MAX_ARTIFACT_BYTES)),
                        new SkillClients(),
                        "http://127.0.0.1:" + engine.getAddress().getPort());
        assertEquals("SUCCEEDED", fresh.reconcile(call).get("status"));
        assertEquals(1, api.posts.get());
        assertEquals("SUCCEEDED", fact().get("status"));
    }

    @Test
    void trustedFailedConditionCanConvergeFromUnknown() {
        api.condition = "";
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        authority.put("status", "UNKNOWN");
        api.condition = "Failed";
        assertEquals("FAILED", execution.reconcile(call).get("status"));
        assertEquals("FAILED", fact().get("status"));
        assertEquals(0, api.logs.get());
        assertEquals(1, api.posts.get());
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.reconciliation_proof where tenant_id=?",
                        Integer.class,
                        tenant));
    }

    @Test
    void w3OnlyRedeliversPersistedFact() {
        acceptFact = false;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        var original = fact();
        assertEquals("SUCCEEDED", original.get("status"));
        acceptFact = true;
        assertEquals("SUCCEEDED", execution.retry(call).get("status"));
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        assertEquals(1, api.posts.get());
        assertEquals(1, logicalCommits.get());
        assertEquals(original.get("result"), fact().get("result"));
    }

    @Test
    void noApprovalAndDebugMockHaveZeroAlgorithmRequests() {
        // Reuse a fresh tenant to create an explicitly write-capable contract.
        tenant = "algorithm_write_" + UUID.randomUUID();
        AlgorithmTestDatabase.identity(tenant, "owner");
        prepare(true);
        approved = false;
        assertEquals("WAITING_APPROVAL", execution.execute("algorithm", request).get("status"));
        assertEquals(0, api.posts.get());
        assertEquals(0, api.gets.get());
        approved = true;
        authority.put("trigger_type", "DEBUG");
        authority.put(
                "mock_snapshot",
                Map.of(
                        "skillCode",
                        "algorithm",
                        "mode",
                        "FIXED",
                        "fixedResponse",
                        Map.of("value", "mock")));
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        assertEquals(0, api.posts.get());
        assertEquals(0, api.gets.get());
        assertEquals(true, fact().get("is_mock"));
    }

    @Test
    void trustedFailedJobIsNonRetryableAndDuplicateDoesNotCreateNewAttempt() {
        api.condition = "Failed";
        assertEquals("FAILED", execution.execute("algorithm", request).get("status"));
        assertEquals(
                "NON_RETRYABLE_FAILURE",
                Json.read(fact().get("result").toString()).path("error").path("semantic").asText());
        var original = binding();
        request.put("attemptId", 457L);
        authority.put("attempt_id", 457L);
        assertEquals("FAILED", execution.execute("algorithm", request).get("status"));
        assertEquals("FAILED", execution.reconcile(call).get("status"));
        assertEquals(456L, fact().get("attempt_id"));
        assertEquals(original, binding());
        assertEquals(1, api.posts.get());
    }

    @Test
    void unsentReadonlyTransientFailureIsFencedBeforeRetryableFact() {
        api.errorCode = 503;
        assertEquals("FAILED", execution.execute("algorithm", request).get("status"));
        assertEquals("NOT_SENT", binding().get("status"));
        assertEquals(false, binding().get("send_started"));
        assertEquals(
                "RETRYABLE_FAILURE",
                Json.read(fact().get("result").toString()).path("error").path("semantic").asText());
        assertEquals(0, api.posts.get());
    }

    void seedKnownUnsentFailure() {
        api.errorCode = 503;
        execution.execute("algorithm", request);
        database.db.update(
                "update skill.execution_fact set"
                    + " status='FAILED',result=jsonb_set(result,'{error,semantic}','\"RETRYABLE_FAILURE\"'::jsonb)"
                    + " where tenant_id=? and call_id=?",
                tenant,
                call);
        database.db.update(
                "update skill.external_job_binding set status='NOT_SENT',send_started=false where"
                        + " tenant_id=? and call_id=?",
                tenant,
                call);
        api.errorCode = 0;
        request.put("attemptId", 457L);
        authority.put("attempt_id", 457L);
        authority.put("status", "REGISTERED");
    }

    @Test
    void engineAuthorizedReadonlyGenerationIsPreparedBeforeOnlyBusinessPost() {
        seedKnownUnsentFailure();
        String oldBinding =
                database.db.queryForObject(
                        "select to_jsonb(b)::text from skill.external_job_binding b where"
                                + " tenant_id=? and call_id=?",
                        String.class,
                        tenant,
                        call);
        atSubmitted =
                () -> {
                    assertEquals(457L, fact().get("attempt_id"));
                    assertEquals("PREPARING", fact().get("callback_state"));
                    assertEquals(457L, binding().get("attempt_id"));
                    assertEquals("REGISTERED", binding().get("status"));
                };
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        assertEquals(1, api.posts.get());
        assertEquals(457L, fact().get("attempt_id"));
        assertEquals(
                1,
                database.db.queryForObject(
                        "select count(*) from skill.execution_fact_history where tenant_id=? and"
                                + " call_id=? and attempt_id=456",
                        Integer.class,
                        tenant,
                        call));
        var history =
                database.db.queryForMap(
                        "select binding from skill.external_job_binding_history where tenant_id=?"
                                + " and call_id=? and attempt_id=456",
                        tenant,
                        call);
        assertEquals(
                Json.canonical(Json.read(oldBinding)),
                Json.canonical(Json.read(history.get("binding").toString())));
    }

    @Test
    void readonlyGenerationPreparationRollbackPreservesOriginalFactAndBinding() {
        seedKnownUnsentFailure();
        var beforeFact = fact();
        var beforeBinding = binding();
        // The test owns this named database and creates a tenant-specific finite trigger only.
        database.db.execute(
                "create function skill.reject_algorithm_generation_"
                        + call
                        + "() returns trigger language plpgsql as $$ begin if NEW.tenant_id='"
                        + tenant
                        + "' and NEW.attempt_id=457 then raise exception 'fixture preparation"
                        + " rollback'; end if; return NEW; end $$");
        database.db.execute(
                "create trigger reject_algorithm_generation_"
                        + call
                        + " before update on skill.execution_fact for each row execute function"
                        + " skill.reject_algorithm_generation_"
                        + call
                        + "()");
        try {
            assertThrows(RuntimeException.class, () -> execution.execute("algorithm", request));
            assertEquals(beforeFact, fact());
            assertEquals(beforeBinding, binding());
            assertEquals(0, api.posts.get());
        } finally {
            database.db.execute(
                    "drop trigger reject_algorithm_generation_"
                            + call
                            + " on skill.execution_fact");
            database.db.execute("drop function skill.reject_algorithm_generation_" + call + "()");
        }
    }

    @Test
    void absentNewAuthorityCannotResetKnownUnsentFact() {
        seedKnownUnsentFailure();
        authority.put("status", "UNKNOWN");
        assertEquals("FAILED", execution.execute("algorithm", request).get("status"));
        assertEquals(456L, fact().get("attempt_id"));
        assertEquals(0, api.posts.get());
    }

    @Test
    void unknownFactNeverEntersReadonlyGenerationHandoff() {
        api.dropPost = true;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        request.put("attemptId", 457L);
        authority.put("attempt_id", 457L);
        api.dropPost = false;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        assertEquals(456L, fact().get("attempt_id"));
        assertEquals(1, api.posts.get());
    }

    @Test
    void w1ReconciliationFencesDelayedSubmittedAck() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        atSubmitted =
                () -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending =
                    pool.submit(
                            () -> {
                                AlgorithmTestDatabase.identity(tenant, "owner");
                                try {
                                    return execution.execute("algorithm", request);
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                authority.put("status", "UNKNOWN");
                var response = execution.reconcile(call);
                assertEquals("NOT_SENT", response.get("outcome"));
                assertEquals(false, response.get("retryBusinessAllowed"));
                assertEquals("NOT_SENT", binding().get("status"));
            } finally {
                release.countDown();
            }
            assertEquals("UNKNOWN", pending.get(3, TimeUnit.SECONDS).get("status"));
            assertEquals(0, api.posts.get());
            assertEquals(0, api.gets.get());
        }
    }

    @Test
    void twoAuthorizedRetryWorkersPrepareOnlyOneGeneration() throws Exception {
        seedKnownUnsentFailure();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    AlgorithmTestDatabase.identity(tenant, "owner");
                                    ready.countDown();
                                    try {
                                        assertTrue(go.await(1, TimeUnit.SECONDS));
                                        execution.execute(
                                                "algorithm", new LinkedHashMap<>(request));
                                    } catch (PlatformException | SkillAdapters.Failure expected) {
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            go.countDown();
            for (var future : futures) future.get(4, TimeUnit.SECONDS);
        }
        assertEquals(1, api.posts.get());
        assertEquals(457L, fact().get("attempt_id"));
        assertEquals("SUCCEEDED", fact().get("status"), () -> safeFactDiagnostic());
        assertEquals(
                1,
                database.db.queryForObject(
                        "select count(*) from skill.external_job_binding_history where tenant_id=?"
                                + " and call_id=?",
                        Integer.class,
                        tenant,
                        call));
    }

    @Test
    void oldExplicitAttemptCannotSendIntoPreparedNewReadonlyGeneration() {
        seedKnownUnsentFailure();
        var originalContract = catalog.versionId(version);
        var originalArgs = new LinkedHashMap<>((Map<String, Object>) request.get("arguments"));
        String key = request.get("idempotencyKey").toString();
        acceptSubmit = false;
        assertThrows(PlatformException.class, () -> execution.execute("algorithm", request));
        assertEquals("PREPARING", fact().get("callback_state"));
        assertEquals(457L, binding().get("attempt_id"));
        assertThrows(
                SkillAdapters.Failure.class,
                () ->
                        algorithm.invoke(
                                originalContract,
                                originalArgs,
                                key,
                                call,
                                false,
                                adapters,
                                run,
                                456));
        assertEquals(0, api.posts.get());
        assertEquals(false, binding().get("send_started"));
    }

    @Test
    void staleSuccessObservationCannotMarkChangedFactDone() {
        api.beforeLog =
                () ->
                        database.db.update(
                                "update skill.execution_fact set attempt_id=999 where tenant_id=?"
                                        + " and call_id=?",
                                tenant,
                                call);
        assertThrows(PlatformException.class, () -> execution.execute("algorithm", request));
        assertEquals(999L, fact().get("attempt_id"));
        assertEquals("UNKNOWN", fact().get("status"));
        assertEquals("SUBMITTED", binding().get("status"));
        assertEquals(0, deliveries.get());
    }

    @Test
    void staleObservationCannotAdoptReplacementBindingUid() {
        api.beforeLog =
                () ->
                        database.db.update(
                                "update skill.external_job_binding set"
                                        + " external_job_uid='replacement' where tenant_id=? and"
                                        + " call_id=?",
                                tenant,
                                call);
        assertThrows(SkillAdapters.Failure.class, () -> execution.execute("algorithm", request));
        assertEquals("UNKNOWN", fact().get("status"));
        assertEquals("SUBMITTED", binding().get("status"));
        assertEquals("replacement", binding().get("external_job_uid"));
        assertEquals(0, deliveries.get());
    }

    @Test
    void concurrentlyTerminalFactCannotBeOverwrittenByOldObservation() {
        api.beforeLog =
                () ->
                        database.db.update(
                                "update skill.execution_fact set status='FAILED' where tenant_id=?"
                                        + " and call_id=?",
                                tenant,
                                call);
        assertThrows(PlatformException.class, () -> execution.execute("algorithm", request));
        assertEquals("FAILED", fact().get("status"));
        assertEquals("SUBMITTED", binding().get("status"));
        assertEquals(0, deliveries.get());
    }

    @Test
    void narrowedCurrentScopeDoesNotQueryHistoricalJob() {
        api.dropPost = true;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        authority.put("status", "UNKNOWN");
        int before = api.routes.size();
        var authentication =
                (org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationToken)
                        SecurityContextHolder.getContext().getAuthentication();
        var jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("narrowed-fixture")
                        .header("alg", "HS256")
                        .claims(c -> c.putAll(authentication.getToken().getClaims()))
                        .claim("attributes", Map.of("region", List.of("B")))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.oauth2.server.resource.authentication
                                .JwtAuthenticationToken(jwt));
        assertThrows(PlatformException.class, () -> execution.reconcile(call));
        assertEquals(before, api.routes.size());
    }

    @Test
    void lateTerminalObservationIsRetainedWithoutRevivingCancelledAuthority() {
        lateFact = true;
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        assertEquals("SUCCEEDED", fact().get("status"));
        assertEquals("LATE", fact().get("callback_state"));
        assertEquals("DONE", binding().get("status"));
        assertEquals(0, logicalCommits.get());
        assertEquals(1, api.posts.get());
        assertEquals("UNKNOWN", execution.retry(call).get("status"));
        assertEquals(1, api.posts.get());
        assertEquals(0, logicalCommits.get());
    }

    @Test
    void debugLastRealMakesNoAdditionalAlgorithmRequest() {
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        var previous = fact();
        var result = Json.read(previous.get("result").toString());
        int before = api.routes.size();
        long oldCall = call;
        call = System.nanoTime();
        request.put("callId", call);
        request.put("idempotencyKey", "logical-" + call);
        authority.put("callId", call);
        authority.put("idempotency_key", "logical-" + call);
        authority.put("trigger_type", "DEBUG");
        authority.put(
                "mock_snapshot",
                Map.of(
                        "skillCode",
                        "algorithm",
                        "mode",
                        "FIXED",
                        "sourceKind",
                        "LAST_REAL",
                        "sourceCallId",
                        oldCall,
                        "sourceRunId",
                        run,
                        "sourcePrincipal",
                        "owner",
                        "sourceDigest",
                        Digest.sha256(Json.canonical(result)),
                        "fixedResponse",
                        result));
        assertEquals(
                "SUCCEEDED",
                execution.execute("algorithm", request).get("status"),
                () -> safeFactDiagnostic());
        assertEquals(before, api.routes.size());
        assertEquals(true, fact().get("is_mock"));
    }

    @Test
    void formalInvocationIgnoresMockAndUsesRealAlgorithm() {
        authority.put(
                "mock_snapshot",
                Map.of(
                        "skillCode",
                        "algorithm",
                        "mode",
                        "FIXED",
                        "fixedResponse",
                        Map.of("value", "mock")));
        var response = execution.execute("algorithm", request);
        assertEquals("SUCCEEDED", response.get("status"));
        assertEquals(false, fact().get("is_mock"));
        assertEquals(1, api.posts.get());
        assertEquals(
                "ok", Json.tree(response.get("result")).path("structured").path("value").asText());
    }

    @Test
    void sentBindingCannotEnterReadonlyGenerationHandoff() {
        seedKnownUnsentFailure();
        database.db.update(
                "update skill.external_job_binding set"
                        + " send_started=true,status='SUBMITTED',external_job_uid='already-present'"
                        + " where tenant_id=? and call_id=?",
                tenant,
                call);
        var before = fact();
        assertThrows(SkillAdapters.Failure.class, () -> execution.execute("algorithm", request));
        assertEquals(before, fact());
        assertEquals(456L, binding().get("attempt_id"));
        assertEquals(0, api.posts.get());
    }

    @Test
    void sideEffectContractCannotEnterReadonlyGenerationHandoff() {
        tenant = "algorithm_side_effect_" + UUID.randomUUID();
        AlgorithmTestDatabase.identity(tenant, "owner");
        prepare(true);
        seedKnownUnsentFailure();
        assertEquals("FAILED", execution.execute("algorithm", request).get("status"));
        assertEquals(456L, fact().get("attempt_id"));
        assertEquals(0, api.posts.get());
    }

    @Test
    void initialFactInsertFailureRollsBackBindingAndNeverSubmits() {
        database.db.execute(
                "create function skill.reject_algorithm_insert_"
                        + call
                        + "() returns trigger language plpgsql as $$ begin if NEW.tenant_id='"
                        + tenant
                        + "' then raise exception 'fixture insert rollback'; end if; return NEW;"
                        + " end $$");
        database.db.execute(
                "create trigger reject_algorithm_insert_"
                        + call
                        + " before insert on skill.execution_fact for each row execute function"
                        + " skill.reject_algorithm_insert_"
                        + call
                        + "()");
        try {
            assertThrows(RuntimeException.class, () -> execution.execute("algorithm", request));
            assertEquals(
                    0,
                    database.db.queryForObject(
                            "select count(*) from skill.external_job_binding where tenant_id=?",
                            Integer.class,
                            tenant));
            assertEquals(
                    0,
                    database.db.queryForObject(
                            "select count(*) from skill.execution_fact where tenant_id=?",
                            Integer.class,
                            tenant));
            assertEquals(0, submitted.get());
            assertEquals(0, api.posts.get());
        } finally {
            database.db.execute(
                    "drop trigger reject_algorithm_insert_" + call + " on skill.execution_fact");
            database.db.execute("drop function skill.reject_algorithm_insert_" + call + "()");
        }
    }

    @Test
    void terminalFactFailureRollsBackBindingDoneAtomically() {
        database.db.execute(
                "create function skill.reject_algorithm_terminal_"
                        + call
                        + "() returns trigger language plpgsql as $$ begin if NEW.tenant_id='"
                        + tenant
                        + "' and NEW.status='SUCCEEDED' then raise exception 'fixture terminal"
                        + " rollback'; end if; return NEW; end $$");
        database.db.execute(
                "create trigger reject_algorithm_terminal_"
                        + call
                        + " before update on skill.execution_fact for each row execute function"
                        + " skill.reject_algorithm_terminal_"
                        + call
                        + "()");
        try {
            assertThrows(RuntimeException.class, () -> execution.execute("algorithm", request));
            assertEquals("UNKNOWN", fact().get("status"));
            assertEquals("SUBMITTED", binding().get("status"));
            assertFalse(Json.read(binding().get("metadata").toString()).has("terminalEvidence"));
            assertEquals(0, deliveries.get());
            assertEquals(1, api.posts.get());
        } finally {
            database.db.execute(
                    "drop trigger reject_algorithm_terminal_" + call + " on skill.execution_fact");
            database.db.execute("drop function skill.reject_algorithm_terminal_" + call + "()");
        }
    }

    @Test
    void legacyBindingWithoutPrincipalStaysAuditedUnknownWithoutNetwork() {
        assertLegacyBindingUnknown(false);
    }

    @Test
    void missingOriginalBindingStaysAuditedUnknownWithoutNetwork() {
        assertLegacyBindingUnknown(true);
    }

    private void assertLegacyBindingUnknown(boolean missing) {
        api.condition = "";
        assertEquals("UNKNOWN", execution.execute("algorithm", request).get("status"));
        authority.put("status", "UNKNOWN");
        if (missing)
            database.db.update(
                    "delete from skill.external_job_binding where tenant_id=? and call_id=?",
                    tenant,
                    call);
        else
            database.db.update(
                    "update skill.external_job_binding set"
                        + " principal_id=null,external_job_uid=null,external_namespace=null,external_api_sha256=null,external_identity_sha256=null"
                        + " where tenant_id=? and call_id=?",
                    tenant,
                    call);
        int before = api.routes.size();
        var result = assertDoesNotThrow(() -> execution.reconcile(call));
        assertEquals("UNKNOWN", result.get("status"));
        assertEquals(false, result.get("retryBusinessAllowed"));
        assertEquals(before, api.routes.size());
        assertEquals("UNKNOWN", fact().get("status"));
        assertTrue(
                database.db
                        .queryForObject(
                                "select detail->>'algorithmReason' from skill.reconciliation_note"
                                    + " where tenant_id=? and call_id=? order by id desc limit 1",
                                String.class,
                                tenant,
                                call)
                        .contains("LEGACY_BINDING"));
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.reconciliation_proof where tenant_id=?",
                        Integer.class,
                        tenant));
    }
}
