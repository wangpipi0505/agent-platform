package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.api.ReconciliationProofController;
import cn.agentplatform.skill.api.SkillController;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** PG is authoritative; provider and engine authority are actual local HTTP protocol fixtures. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ReconciliationProofTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class WebConfig {}

    static JdbcTemplate db;
    static DataSourceTransactionManager tx;
    String tenant, key, principal = "owner", externalState = "RUNNING", runState = "RUNNING";
    long call, version;
    HttpServer provider, engine;
    SkillExecution execution;
    Map<String, Object> request, authority;
    AtomicInteger submits = new AtomicInteger(),
            queries = new AtomicInteger(),
            facts = new AtomicInteger();
    boolean async = true, configured = true, wrongKey, wrongJob, dropSubmittedAck, dropProviderAck;
    int queryStatus = 200;
    int responseRevision = 1;
    boolean revisionPerQuery;
    boolean businessReturnSpec;
    ExecutorService providerWorkers;
    volatile Runnable queryHook = () -> {};
    Object queryResult;
    CountDownLatch queryEntered, releaseQuery;

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
                                + " datname='agent_platform_skill_proof_test'",
                        Integer.class)
                == 0) admin.execute("create database agent_platform_skill_proof_test");
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_skill_proof_test",
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
        tenant = "proof_" + UUID.randomUUID();
        key = "key/+ ?" + UUID.randomUUID();
        call = Math.abs(UUID.randomUUID().getMostSignificantBits());
        authenticate(
                tenant,
                "owner",
                List.of("ADMIN", "EDITOR", "SERVICE", "READER"),
                List.of("A", "B"));
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerWorkers = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(providerWorkers);
        provider.createContext(
                "/submit",
                ex -> {
                    submits.incrementAndGet();
                    ex.getRequestBody().readAllBytes();
                    if (dropProviderAck) {
                        ex.close();
                        return;
                    }
                    Object body =
                            async
                                    ? Map.of("jobId", "job-1", "idempotencyKey", key)
                                    : Map.of("value", 1);
                    reply(ex, async ? 202 : 503, body);
                });
        provider.createContext(
                "/query",
                ex -> {
                    int queryNo = queries.incrementAndGet();
                    queryHook.run();
                    if (queryEntered != null) {
                        queryEntered.countDown();
                        try {
                            releaseQuery.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    var body =
                            new LinkedHashMap<String, Object>(
                                    Map.of(
                                            "jobId",
                                            wrongJob ? "foreign-job" : "job-1",
                                            "idempotencyKey",
                                            wrongKey ? "foreign-key" : key,
                                            "status",
                                            externalState,
                                            "evidenceRevision",
                                            revisionPerQuery ? queryNo : responseRevision));
                    if (queryResult != null) body.put("result", queryResult);
                    if (businessReturnSpec)
                        body.put("payload", Map.of("idempotencyKey", key, "status", "NOT_APPLIED"));
                    reply(ex, queryStatus, body);
                });
        provider.start();
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext(
                "/",
                ex -> {
                    String path = ex.getRequestURI().getPath();
                    Object body;
                    if (path.startsWith("/admin/runs/"))
                        body =
                                Map.of(
                                        "runId",
                                        "r_proof",
                                        "status",
                                        runState,
                                        "nodes",
                                        List.of(Map.of("nodeInstanceId", 123, "status", runState)));
                    else if (path.endsWith("/claim")) body = authority;
                    else if (path.endsWith("/submitted")) {
                        if (dropSubmittedAck) {
                            reply(ex, 503, Map.of());
                            return;
                        }
                        body = Map.of("submitted", true);
                    } else if (path.endsWith("/approval"))
                        body =
                                Map.of(
                                        "approved",
                                        true,
                                        "skillVersionId",
                                        version,
                                        "parametersDigest",
                                        Digest.sha256(Json.canonical(request.get("arguments"))));
                    else if (path.endsWith("/fact")) {
                        facts.incrementAndGet();
                        body = Map.of("accepted", true);
                    } else body = authority;
                    reply(ex, 200, ApiResponse.ok(body));
                });
        engine.start();
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, Object body)
            throws java.io.IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    void authenticate(String t, String p, List<String> roles, List<String> regions) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject(p)
                        .claim("tenant_id", t)
                        .claim("roles", roles)
                        .claim("attributes", Map.of("region", regions))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    void prepare() {
        String url = "http://127.0.0.1:" + provider.getAddress().getPort();
        var c = new LinkedHashMap<String, Object>();
        c.put("code", "proof");
        c.put("name", "proof");
        c.put("description", "proof fixture");
        c.put("type", "HTTP");
        c.put("endpoint", url + "/submit");
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputSchema", Map.of("type", "object", "required", List.of("value")));
        c.put("timeoutMs", 1000);
        c.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        true,
                        "idempotent",
                        true,
                        "cancellable",
                        false,
                        "confirmRoles",
                        List.of("APPROVER")));
        c.put(
                "guard",
                Map.of("roles", List.of("READER"), "attributes", Map.of("region", List.of())));
        if (async) c.put("invocationKind", "SUBMIT_QUERY");
        var policy =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "endpoint",
                                url + "/query/" + (async ? "{jobId}" : "{idempotencyKey}"),
                                "method",
                                "GET",
                                "donePath",
                                "$.status",
                                "doneValue",
                                "DONE"));
        if (configured) policy.put("notAppliedValue", "NOT_APPLIED");
        if (dropProviderAck) policy.put("lookupEndpoint", url + "/query/{idempotencyKey}");
        c.put("reconciliation", policy);
        if (businessReturnSpec) c.put("returnSpec", Map.of("structuredPath", "$.payload"));
        var clients = new SkillClients();
        var catalog = new SkillCatalog(db, tx, clients, "http://unused");
        version = ((Number) catalog.create(c).get("skillVersionId")).longValue();
        db.update("update skill.skill_version set status='PUBLISHED' where id=?", version);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "runId",
                                "r_proof",
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
        authority.put("status", "UNKNOWN");
        authority.put("skill_code", "proof");
        authority.put("skill_version_id", version);
        authority.put("node_instance_id", 123);
        authority.put("attempt_id", 456);
        authority.put("idempotency_key", key);
        authority.put("args_digest", Digest.sha256(Json.canonical(request.get("arguments"))));
        authority.put("skill_contract_sha256", catalog.versionId(version).get("sha256"));
        authority.put("trigger_type", "TASK");
        execution =
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
        if (dropSubmittedAck)
            assertThrows(PlatformException.class, () -> execution.execute("proof", request));
        else assertEquals("UNKNOWN", execution.execute("proof", request).get("status"));
        queries.set(0);
        facts.set(0);
        externalState = "NOT_APPLIED";
    }

    @AfterEach
    void cleanup() {
        if (releaseQuery != null) releaseQuery.countDown();
        if (provider != null) provider.stop(0);
        if (providerWorkers != null) providerWorkers.shutdownNow();
        if (engine != null) engine.stop(0);
        SecurityContextHolder.clearContext();
    }

    @Test
    void explicitAsyncNotAppliedCreatesImmutableProofWithoutBusinessRetry() {
        prepare();
        var answer = execution.reconcile(call);
        assertEquals("NOT_APPLIED", answer.get("outcome"));
        assertNotNull(answer.get("proofRef"));
        assertNotNull(answer.get("proofSha256"));
        assertEquals(false, answer.get("retryBusinessAllowed"));
        assertEquals(1, submits.get());
    }

    @Test
    void explicitSynchronousNotAppliedAlsoHasProof() {
        async = false;
        prepare();
        var answer = execution.reconcile(call);
        assertEquals("NOT_APPLIED", answer.get("outcome"));
        assertNotNull(answer.get("proofRef"));
        assertEquals(1, submits.get());
    }

    @Test
    void reliableNotSentFenceCreatesProofWithoutAnyExternalRequest() {
        dropSubmittedAck = true;
        prepare();
        var answer = execution.reconcile(call);
        assertEquals("NOT_SENT", answer.get("outcome"));
        assertNotNull(answer.get("proofRef"));
        assertEquals(0, submits.get());
        assertEquals(0, queries.get());
    }

    ReconciliationProofs proofs() {
        return new ReconciliationProofs(db, tx);
    }

    int countProofs() {
        return db.queryForObject(
                "select count(*) from skill.reconciliation_proof where tenant_id=? and call_id=?",
                Integer.class,
                tenant,
                call);
    }

    @Test
    void completeProofDigestIsRecomputableAndPgRejectsMutation() {
        prepare();
        var ref = execution.reconcile(call);
        var proof = proofs().get(call, 456);
        assertEquals(ref.get("proofRef"), proof.get("proofRef"));
        assertEquals(tenant, proof.get("tenantId"));
        assertEquals("owner", proof.get("principalId"));
        assertEquals("r_proof", proof.get("runId"));
        assertEquals(123, ((Number) proof.get("nodeInstanceId")).intValue());
        assertEquals(call, ((Number) proof.get("callId")).longValue());
        assertEquals(456, ((Number) proof.get("attemptId")).intValue());
        assertEquals(version, ((Number) proof.get("skillVersionId")).longValue());
        assertEquals(key, proof.get("idempotencyKey"));
        assertEquals("job-1", proof.get("externalJobId"));
        assertEquals("NOT_APPLIED", proof.get("externalState"));
        assertEquals(true, proof.get("sendStarted"));
        assertEquals(64, proof.get("sourceSha256").toString().length());
        assertEquals(64, proof.get("skillContractSha256").toString().length());
        assertEquals(64, proof.get("guardedArgsDigest").toString().length());
        Instant.parse(proof.get("verifiedAt").toString());
        var canonical = new LinkedHashMap<>(proof);
        canonical.remove("proofSha256");
        canonical.remove("retryBusinessAllowed");
        assertEquals(Digest.sha256(Json.canonical(canonical)), proof.get("proofSha256"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "update skill.reconciliation_proof set outcome='NOT_SENT' where"
                                        + " tenant_id=? and call_id=?",
                                tenant,
                                call));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "delete from skill.reconciliation_proof where tenant_id=? and"
                                        + " call_id=?",
                                tenant,
                                call));
        assertEquals(proof, proofs().get(call, 456));
    }

    @Test
    void repeatedQueriesReturnSameProofWithoutReexecution() {
        prepare();
        var first = execution.reconcile(call);
        var second = execution.reconcile(call);
        assertEquals(first, second);
        assertEquals(1, countProofs());
        execution.retry(call);
        execution.execute("proof", request);
        assertEquals(1, submits.get());
        assertEquals("UNKNOWN", execution.proof(call).get("status"));
    }

    @Test
    void notSentIsIdempotentAndDoesNotTrustArbitrarySendFalseState() {
        dropSubmittedAck = true;
        prepare();
        db.update(
                "update skill.external_job_binding set status='SUBMITTED' where tenant_id=? and"
                        + " call_id=?",
                tenant,
                call);
        assertThrows(PlatformException.class, () -> execution.reconcile(call));
        assertEquals(0, countProofs());
        assertEquals(0, submits.get());
        assertEquals(0, queries.get());
        db.update(
                "update skill.external_job_binding set status='REGISTERED' where tenant_id=? and"
                        + " call_id=?",
                tenant,
                call);
        var first = execution.reconcile(call);
        assertEquals(first, execution.reconcile(call));
        assertEquals(1, countProofs());
    }

    @Test
    void unconfiguredMarkerCannotGrantProof() {
        configured = false;
        prepare();
        assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        assertEquals(0, countProofs());
    }

    @Test
    void wrongJobOrKeyCannotGrantProof() {
        prepare();
        wrongJob = true;
        assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        wrongJob = false;
        wrongKey = true;
        assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void ordinaryHttp404And5xxRemainUnknown() {
        async = false;
        prepare();
        for (int status : List.of(404, 500, 503)) {
            queryStatus = status;
            assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        }
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void asyncHttp404And5xxRemainUnknown() {
        prepare();
        for (int status : List.of(404, 500)) {
            queryStatus = status;
            assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        }
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void narrowedAuthorizationCannotQueryOrCreateProofIncludingSyncHttp() {
        async = false;
        prepare();
        authenticate(tenant, "owner", List.of("SERVICE", "READER"), List.of("A"));
        assertEquals(
                403,
                assertThrows(PlatformException.class, () -> execution.reconcile(call)).status());
        assertEquals(0, queries.get());
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void narrowedAuthorizationCannotCreateAsyncNotAppliedProof() {
        prepare();
        authenticate(tenant, "owner", List.of("SERVICE", "READER"), List.of("A"));
        assertEquals(
                403,
                assertThrows(PlatformException.class, () -> execution.reconcile(call)).status());
        assertEquals(0, queries.get());
        assertEquals(0, countProofs());
    }

    @Test
    void callbackSuccessDuringQueryPreventsPublishingNewProof() {
        prepare();
        queryHook =
                () ->
                        db.update(
                                "update skill.execution_fact set status='SUCCEEDED' where"
                                        + " tenant_id=? and call_id=?",
                                tenant,
                                call);
        assertEquals(
                "RECONCILIATION_PROOF_STALE",
                assertThrows(PlatformException.class, () -> execution.reconcile(call)).code());
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void attemptChangesDuringQueryPreventPublishingProofForNewGeneration() {
        prepare();
        queryHook =
                () ->
                        db.update(
                                "update skill.execution_fact set attempt_id=457 where tenant_id=?"
                                        + " and call_id=?",
                                tenant,
                                call);
        assertEquals(
                "RECONCILIATION_PROOF_STALE",
                assertThrows(PlatformException.class, () -> execution.reconcile(call)).code());
        assertEquals(0, countProofs());
    }

    @Test
    void cancellingRunDuringQueryPreventsProofAndEngineMismatchFailsClosed() {
        prepare();
        queryHook = () -> runState = "CANCELLED";
        assertThrows(PlatformException.class, () -> execution.reconcile(call));
        assertEquals(0, countProofs());
        runState = "RUNNING";
        queryHook = () -> authority.put("args_digest", "0".repeat(64));
        assertThrows(PlatformException.class, () -> execution.reconcile(call));
        assertEquals(0, countProofs());
    }

    @Test
    void proofReadIsExactServiceTenantAndPrincipalRestrictedAndHistorical() {
        prepare();
        execution.reconcile(call);
        var original = proofs().get(call, 456);
        authenticate(tenant, "owner", List.of("ADMIN"), List.of("A", "B"));
        assertEquals(
                403, assertThrows(PlatformException.class, () -> proofs().get(call, 456)).status());
        authenticate(tenant, "other", List.of("SERVICE"), List.of("A", "B"));
        assertEquals(
                404, assertThrows(PlatformException.class, () -> proofs().get(call, 456)).status());
        authenticate("other-tenant", "owner", List.of("SERVICE"), List.of("A", "B"));
        assertEquals(
                404, assertThrows(PlatformException.class, () -> proofs().get(call, 456)).status());
        authenticate(tenant, "owner", List.of("SERVICE"), List.of());
        db.update(
                "update skill.execution_fact set attempt_id=457 where tenant_id=? and call_id=?",
                tenant,
                call);
        assertEquals(original, proofs().get(call, 456));
        assertEquals(false, proofs().get(call, 456).get("retryBusinessAllowed"));
        assertEquals(
                404, assertThrows(PlatformException.class, () -> proofs().get(call, 457)).status());
    }

    @Test
    void staleReconcilerCannotFenceANewerGenerationBinding() {
        dropSubmittedAck = true;
        prepare();
        db.update(
                "update skill.external_job_binding set attempt_id=457 where tenant_id=? and"
                        + " call_id=?",
                tenant,
                call);
        assertThrows(PlatformException.class, () -> execution.reconcile(call));
        assertEquals(
                "REGISTERED",
                db.queryForObject(
                        "select status from skill.external_job_binding where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals(0, countProofs());
        assertEquals(0, submits.get());
        assertEquals(0, queries.get());
    }

    @Test
    void sourceConflictCannotOverwriteImmutableProof() {
        prepare();
        execution.reconcile(call);
        var original = proofs().get(call, 456);
        responseRevision = 2;
        assertEquals(
                "RECONCILIATION_PROOF_CONFLICT",
                assertThrows(PlatformException.class, () -> execution.reconcile(call)).code());
        assertEquals(original, proofs().get(call, 456));
        assertEquals(1, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void queryTimeoutDoesNotCreateProofOrResend() {
        prepare();
        queryEntered = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
        assertEquals("UNKNOWN", execution.reconcile(call).get("outcome"));
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void originalKeyLookupAfterLostAckProducesStableProofWithoutSecondSubmit() {
        dropProviderAck = true;
        prepare();
        var first = execution.reconcile(call);
        assertEquals("NOT_APPLIED", first.get("outcome"));
        assertEquals(first, assertDoesNotThrow(() -> execution.reconcile(call)));
        assertEquals(1, submits.get());
        assertEquals(1, countProofs());
    }

    @Test
    void noPostgresRowLockSpansProviderHttp() {
        prepare();
        queryHook =
                () -> {
                    try (var connection =
                            Objects.requireNonNull(db.getDataSource()).getConnection()) {
                        connection.setAutoCommit(false);
                        try (var statement = connection.createStatement()) {
                            statement
                                    .executeQuery(
                                            "select call_id from skill.execution_fact where"
                                                    + " tenant_id='"
                                                    + tenant
                                                    + "' and call_id="
                                                    + call
                                                    + " for update nowait")
                                    .close();
                            statement
                                    .executeQuery(
                                            "select call_id from skill.external_job_binding where"
                                                    + " tenant_id='"
                                                    + tenant
                                                    + "' and call_id="
                                                    + call
                                                    + " for update nowait")
                                    .close();
                        } finally {
                            connection.rollback();
                        }
                    } catch (java.sql.SQLException ex) {
                        throw new AssertionError(ex);
                    }
                };
        assertEquals("NOT_APPLIED", execution.reconcile(call).get("outcome"));
    }

    Callable<Object> reconcileWorker() {
        return () -> {
            authenticate(tenant, "owner", List.of("SERVICE", "READER"), List.of("A", "B"));
            try {
                return execution.reconcile(call);
            } catch (PlatformException ex) {
                return ex.code();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    @Test
    void concurrentIdenticalResponsesCreateOneProof() throws Exception {
        prepare();
        queryEntered = new CountDownLatch(2);
        releaseQuery = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(reconcileWorker());
            var second = workers.submit(reconcileWorker());
            assertTrue(queryEntered.await(10, TimeUnit.SECONDS));
            releaseQuery.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void concurrentConflictingResponsesFailClosedWithoutReplacement() throws Exception {
        prepare();
        revisionPerQuery = true;
        queryEntered = new CountDownLatch(2);
        releaseQuery = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(reconcileWorker());
            var second = workers.submit(reconcileWorker());
            assertTrue(queryEntered.await(10, TimeUnit.SECONDS));
            releaseQuery.countDown();
            var answers =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, answers.stream().filter(Map.class::isInstance).count());
            assertEquals(
                    1, answers.stream().filter("RECONCILIATION_PROOF_CONFLICT"::equals).count());
        }
        assertEquals(1, countProofs());
        assertEquals(1, submits.get());
    }

    @Test
    void proofHttpRequiresVerifiedInternalTicketAndExactOriginalOwner() throws Exception {
        prepare();
        execution.reconcile(call);
        try (var context = new GenericWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                    "proof-test",
                                    Map.of(
                                            "agent.security.jwt-secret",
                                            new String(
                                                    ServiceTokens.secretBytes(),
                                                    StandardCharsets.UTF_8))));
            context.registerBean(ReconciliationProofs.class, this::proofs);
            context.registerBean(ReconciliationProofController.class);
            new AnnotatedBeanDefinitionReader(context)
                    .register(WebConfig.class, SecurityConfiguration.class, PlatformErrors.class);
            context.refresh();
            var mvc =
                    MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(
                                    context.getBean(
                                            "springSecurityFilterChain",
                                            jakarta.servlet.Filter.class))
                            .build();
            String endpoint = "/internal/skill-facts/" + call + "/reconciliation-proofs/456";
            var actor = new Actor(tenant, "owner", Set.of("EDITOR"), Map.of());
            var valid =
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + ServiceTokens.issue(actor)))
                            .andReturn()
                            .getResponse();
            assertEquals(200, valid.getStatus());
            assertEquals(
                    false,
                    Json.read(valid.getContentAsString())
                            .path("data")
                            .path("retryBusinessAllowed")
                            .asBoolean(true));
            assertEquals(
                    403,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.userToken(actor, 120)))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    404,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.issue(
                                                                    new Actor(
                                                                            tenant, "other",
                                                                            Set.of(), Map.of()))))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    404,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.issue(
                                                                    new Actor(
                                                                            "other", "owner",
                                                                            Set.of(), Map.of()))))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    401,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header("Authorization", "Bearer broken"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
    }

    @Test
    void appliedResponseFromOldAttemptCannotOverwriteOrDeliverAsNewAttempt() throws Exception {
        prepare();
        externalState = "DONE";
        queryResult = Map.of("value", 7);
        queryEntered = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(reconcileWorker());
            assertTrue(queryEntered.await(10, TimeUnit.SECONDS));
            db.update(
                    "update skill.execution_fact set attempt_id=457,"
                        + " request_snapshot=jsonb_set(request_snapshot,'{attemptId}','457'::jsonb)"
                        + " where tenant_id=? and call_id=?",
                    tenant,
                    call);
            var before =
                    db.queryForMap(
                            "select status,result,attempt_id,request_snapshot from"
                                    + " skill.execution_fact where tenant_id=? and call_id=?",
                            tenant,
                            call);
            releaseQuery.countDown();
            assertEquals("RECONCILE_FACT_STALE", pending.get(10, TimeUnit.SECONDS));
            assertEquals(
                    before,
                    db.queryForMap(
                            "select status,result,attempt_id,request_snapshot from"
                                    + " skill.execution_fact where tenant_id=? and call_id=?",
                            tenant,
                            call));
        }
        assertEquals(0, facts.get());
        assertEquals(1, submits.get());
        assertEquals(0, countProofs());
    }

    @Test
    void callbackFinalDuringAppliedQueryCannotBeOverwrittenOrRedeliveredByOldResponse()
            throws Exception {
        prepare();
        externalState = "DONE";
        queryResult = Map.of("value", 7);
        queryEntered = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(reconcileWorker());
            assertTrue(queryEntered.await(10, TimeUnit.SECONDS));
            db.update(
                    "update skill.execution_fact set status='SUCCEEDED',"
                        + " result=jsonb_set(jsonb_set(jsonb_set(result,'{structured}','{\"value\":99}'::jsonb),'{status}','\"SUCCEEDED\"'::jsonb),'{error}','null'::jsonb)"
                        + " where tenant_id=? and call_id=?",
                    tenant,
                    call);
            var before =
                    db.queryForMap(
                            "select status,result,attempt_id from skill.execution_fact where"
                                    + " tenant_id=? and call_id=?",
                            tenant,
                            call);
            releaseQuery.countDown();
            assertEquals("RECONCILE_FACT_STALE", pending.get(10, TimeUnit.SECONDS));
            assertEquals(
                    before,
                    db.queryForMap(
                            "select status,result,attempt_id from skill.execution_fact where"
                                    + " tenant_id=? and call_id=?",
                            tenant,
                            call));
        }
        assertEquals(0, facts.get());
        assertEquals(1, submits.get());
    }

    @Test
    void normalAppliedResponseForSameAttemptStillCommitsAndDelivers() {
        prepare();
        externalState = "DONE";
        queryResult = Map.of("value", 7);
        var answer = execution.reconcile(call);
        assertEquals("SUCCEEDED", answer.get("status"));
        assertEquals(7, Json.tree(answer.get("result")).path("structured").path("value").asInt());
        assertEquals(1, facts.get());
        assertEquals(1, submits.get());
        assertEquals(0, countProofs());
    }

    @Test
    void oldAppliedQueryCannotSetDoneOnReplacementBinding() throws Exception {
        prepare();
        externalState = "DONE";
        queryResult = Map.of("value", 7);
        queryEntered = new CountDownLatch(1);
        releaseQuery = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(reconcileWorker());
            assertTrue(queryEntered.await(10, TimeUnit.SECONDS));
            db.update(
                    "update skill.external_job_binding set"
                        + " attempt_id=457,request_snapshot=jsonb_set(request_snapshot,'{attemptId}','457'::jsonb)"
                        + " where tenant_id=? and call_id=?",
                    tenant,
                    call);
            var binding =
                    db.queryForMap(
                            "select status,attempt_id,request_snapshot from"
                                    + " skill.external_job_binding where tenant_id=? and call_id=?",
                            tenant,
                            call);
            releaseQuery.countDown();
            pending.get(10, TimeUnit.SECONDS);
            assertEquals(
                    binding,
                    db.queryForMap(
                            "select status,attempt_id,request_snapshot from"
                                    + " skill.external_job_binding where tenant_id=? and call_id=?",
                            tenant,
                            call));
        }
        assertEquals("UNKNOWN", execution.proof(call).get("status"));
        assertEquals(0, facts.get());
        assertEquals(1, submits.get());
    }

    @Test
    void businessResultProjectionCannotChangeReconciliationProtocolMeaning() {
        async = false;
        businessReturnSpec = true;
        prepare();
        externalState = "RUNNING";
        var response = execution.reconcile(call);
        assertEquals("UNKNOWN", response.get("outcome"));
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }

    GenericWebApplicationContext proofHttpContext() {
        var context = new GenericWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "proof-ticket-test",
                                Map.of(
                                        "agent.security.jwt-secret",
                                        new String(
                                                ServiceTokens.secretBytes(),
                                                StandardCharsets.UTF_8))));
        context.registerBean(ReconciliationProofs.class, this::proofs);
        context.registerBean(ReconciliationProofController.class);
        // Only reconcile is used; its other catalog/governance routes are outside this fixture.
        context.registerBean(
                SkillController.class, () -> new SkillController(null, execution, null));
        new AnnotatedBeanDefinitionReader(context)
                .register(WebConfig.class, SecurityConfiguration.class, PlatformErrors.class);
        context.refresh();
        return context;
    }

    org.springframework.test.web.servlet.MockMvc proofMvc(GenericWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(
                        context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class))
                .build();
    }

    Actor originalActor() {
        return new Actor(
                tenant,
                "owner",
                Set.of("EDITOR", "READER", "SERVICE"),
                Map.of("region", Set.of("A", "B")));
    }

    String signedPurpose(Actor actor, List<String> audiences, String use) throws Exception {
        String[] token = ServiceTokens.userToken(actor, 120).split("\\.");
        var claims =
                Json.read(
                        new String(Base64.getUrlDecoder().decode(token[1]), StandardCharsets.UTF_8),
                        Map.class);
        claims.put("aud", audiences);
        claims.put("token_use", use);
        String unsigned =
                token[0]
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        Json.write(claims).getBytes(StandardCharsets.UTF_8));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(ServiceTokens.secretBytes(), "HmacSHA256"));
        return unsigned
                + "."
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void portalTokenWithServiceRoleCannotReadOriginalOwnerProof() throws Exception {
        prepare();
        execution.reconcile(call);
        try (var context = proofHttpContext()) {
            var response =
                    proofMvc(context)
                            .perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(
                                                    "/internal/skill-facts/"
                                                            + call
                                                            + "/reconciliation-proofs/456")
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.userToken(
                                                                    originalActor(), 120)))
                            .andReturn()
                            .getResponse();
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void mixedAudiencePortalPurposeCannotReadProof() throws Exception {
        prepare();
        execution.reconcile(call);
        try (var context = proofHttpContext()) {
            var mvc = proofMvc(context);
            String endpoint = "/internal/skill-facts/" + call + "/reconciliation-proofs/456";
            assertEquals(
                    403,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + signedPurpose(
                                                                    originalActor(),
                                                                    List.of(
                                                                            "agent-platform",
                                                                            "agent-platform-internal"),
                                                                    "user")))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    401,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + signedPurpose(
                                                                    originalActor(),
                                                                    List.of(
                                                                            "agent-platform-internal"),
                                                                    "user")))
                            .andReturn()
                            .getResponse()
                            .getStatus());
            assertEquals(
                    401,
                    mvc.perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.get(endpoint)
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + signedPurpose(
                                                                    originalActor(),
                                                                    List.of("agent-platform"),
                                                                    "service")))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
    }

    @Test
    void portalServiceRoleCannotCreateExternalNotAppliedProof() throws Exception {
        prepare();
        try (var context = proofHttpContext()) {
            assertEquals(
                    403,
                    proofMvc(context)
                            .perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(
                                                    "/internal/skill-facts/" + call + "/reconcile")
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.userToken(
                                                                    originalActor(), 120)))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        assertEquals(0, countProofs());
        assertEquals(
                "UNKNOWN",
                db.queryForObject(
                        "select status from skill.execution_fact where tenant_id=? and call_id=?",
                        String.class,
                        tenant,
                        call));
        assertEquals(1, submits.get());
    }

    @Test
    void portalServiceRoleCannotCreateNotSentProofOrFenceSender() throws Exception {
        dropSubmittedAck = true;
        prepare();
        try (var context = proofHttpContext()) {
            assertEquals(
                    403,
                    proofMvc(context)
                            .perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(
                                                    "/internal/skill-facts/" + call + "/reconcile")
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.userToken(
                                                                    originalActor(), 120)))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        assertEquals(0, countProofs());
        assertEquals(0, submits.get());
        assertEquals(0, queries.get());
        assertEquals(
                "REGISTERED",
                db.queryForObject(
                        "select status from skill.external_job_binding where tenant_id=? and"
                                + " call_id=?",
                        String.class,
                        tenant,
                        call));
    }

    @Test
    void nonProofAppliedReconcileRetainsExistingTicketPolicyInThisLimitedFix() throws Exception {
        prepare();
        externalState = "DONE";
        queryResult = Map.of("value", 7);
        // This limited proof fix deliberately does not redefine the legacy non-proof API policy.
        try (var context = proofHttpContext()) {
            var response =
                    proofMvc(context)
                            .perform(
                                    org.springframework.test.web.servlet.request
                                            .MockMvcRequestBuilders.post(
                                                    "/internal/skill-facts/" + call + "/reconcile")
                                            .header(
                                                    "Authorization",
                                                    "Bearer "
                                                            + ServiceTokens.userToken(
                                                                    originalActor(), 120)))
                            .andReturn()
                            .getResponse();
            assertEquals(200, response.getStatus());
            assertEquals(
                    "SUCCEEDED",
                    Json.read(response.getContentAsString()).path("data").path("status").asText());
        }
        assertEquals(0, countProofs());
        assertEquals(1, submits.get());
    }
}
