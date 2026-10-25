package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class AlgorithmProtocolTest {
    static AlgorithmTestDatabase database;
    @TempDir Path secrets;
    AlgorithmApiFixture api;
    AlgorithmAdapter adapter;
    SkillAdapters outputs;
    ArtifactStore artifacts;
    Map<String, Object> contract, request;
    String tenant, run, key;
    long call;

    @BeforeAll
    static void database() {
        database = new AlgorithmTestDatabase("agent_platform_skill_algorithm_test", null);
    }

    @BeforeEach
    void start() throws Exception {
        tenant = "algorithm_protocol_" + UUID.randomUUID();
        run = "r_" + UUID.randomUUID();
        call = System.nanoTime();
        key = "logical-" + call;
        AlgorithmTestDatabase.identity(tenant, "owner");
        System.setProperty("agent.secret.dir", secrets.toString());
        System.setProperty("agent.master.key", "algorithm-fixture-only-master-key-32-characters");
        api = new AlgorithmApiFixture();
        adapter =
                AlgorithmTestDatabase.adapter(
                        database.db, api, 1024 * 1024, ArtifactStore.MAX_ARTIFACT_BYTES);
        artifacts = new ArtifactStore(database.db);
        outputs = new SkillAdapters(artifacts, adapter);
        contract =
                new LinkedHashMap<>(
                        Map.of(
                                "skillVersionId",
                                7L,
                                "sha256",
                                Digest.sha256("contract"),
                                "type",
                                "ALGORITHM",
                                "jobTemplate",
                                Map.of("image", "fixture/algorithm:fixed"),
                                "authRef",
                                SecretVault.put("fixture-bearer"),
                                "timeoutMs",
                                800,
                                "outputSchema",
                                Map.of("type", "object"),
                                "outputMaxBytes",
                                8192));
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "skillVersionId",
                                7L,
                                "attemptId",
                                456L,
                                "nodeInstanceId",
                                123L,
                                "runId",
                                run,
                                "idempotencyKey",
                                key,
                                "arguments",
                                Map.of("region", "A"),
                                "guardedArguments",
                                Map.of("region", "A")));
    }

    @AfterEach
    void stop() {
        if (artifacts != null) {
            database.db.update(
                    "update skill.artifact set expires_at=now()-interval '1"
                        + " second',cleanup_after=now()-interval '1 second' where tenant_id=? and"
                        + " state<>'DELETED'",
                    tenant);
            artifacts.cleanTenant(tenant, 8);
            artifacts.close();
        }
        if (api != null) api.close();
        SecurityContextHolder.clearContext();
        System.clearProperty("agent.secret.dir");
        System.clearProperty("agent.master.key");
        if (api != null) api.assertHealthy();
    }

    SkillAdapters.Raw invoke(boolean query) {
        return adapter.invoke(contract, Map.of("region", "A"), key, call, query, outputs, run, 456);
    }

    @Test
    void queryOnlyWithoutBindingDoesNotInsertOrSend() {
        assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.external_job_binding where tenant_id=? and"
                                + " call_id=?",
                        Integer.class,
                        tenant,
                        call));
        assertEquals(0, api.posts.get());
    }

    @Test
    void algorithmUsesPersistentBindingBeforeInvoke() {
        outputs.bind(contract, request);
        var b =
                database.db.queryForList(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals(1, b.size(), "ALGORITHM must bind inside PREPARING transaction");
        assertEquals("REGISTERED", b.getFirst().get("status"));
        assertEquals(false, b.getFirst().get("send_started"));
    }

    @Test
    void wrongNamespaceNeverReadsLogs() {
        outputs.bind(contract, request);
        api.wrongNamespace = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void wrongPodOwnerNeverReadsLogs() {
        outputs.bind(contract, request);
        api.ownerUid = "foreign-uid";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void podControllerMustBeTrue() {
        outputs.bind(contract, request);
        api.controller = false;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void podOwnerVersionMustMatch() {
        outputs.bind(contract, request);
        api.ownerVersion = "apps/v1";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void multiplePodsNeverSelectFirst() {
        outputs.bind(contract, request);
        api.podCount = 2;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void continuedPodListIsUnknown() {
        outputs.bind(contract, request);
        api.continuation = "more";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void nonAlgorithmContainerCannotSupplyLogs() {
        outputs.bind(contract, request);
        api.containerName = "sidecar";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void nonzeroContainerExitCannotSupplySuccessfulLogs() {
        outputs.bind(contract, request);
        api.exitCode = 1;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void failedCountAloneIsUncertain() {
        contract.put("timeoutMs", 200);
        outputs.bind(contract, request);
        api.condition = "";
        api.failedCount = 1;
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
    }

    @Test
    void contradictoryConditionsAreUncertain() {
        outputs.bind(contract, request);
        api.contradictory = true;
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
        assertEquals(0, api.logs.get());
    }

    @Test
    void replacingUidCannotReplaceOriginalBinding() {
        outputs.bind(contract, request);
        invoke(false);
        api.uid = "replacement";
        api.ownerUid = "replacement";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals(1, api.logs.get());
    }

    @Test
    void lostAckNeverCreatesSecondJobAfter404() {
        outputs.bind(contract, request);
        api.dropPost = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        api.dropPost = false;
        api.missing = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(1, api.posts.get());
    }

    @Test
    void staleAttemptCannotObserveOrSend() {
        outputs.bind(contract, request);
        assertThrows(
                SkillAdapters.Failure.class,
                () ->
                        adapter.invoke(
                                contract,
                                Map.of("region", "A"),
                                key,
                                call,
                                false,
                                outputs,
                                run,
                                457));
        assertEquals(0, api.posts.get());
        assertEquals(0, api.gets.get());
    }

    @Test
    void metadataBudgetAppliesBeforeSdkMaterialization() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 1024, 4096);
        api.metadataPadding = 1500;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void chunkedMetadataBudgetAppliesWithoutLength() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 1024, 4096);
        api.metadataPadding = 1500;
        api.chunked = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void logBudgetRejectsCompleteJsonBeyondBytes() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 4096, 4096);
        api.stdout = "{\"value\":\"" + "中".repeat(1500) + "\"}";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(1, api.logs.get());
    }

    @Test
    void oneDeadlineCoversSlowInitialGetAndDoesNotSendLate() throws Exception {
        contract.put("timeoutMs", 200);
        outputs.bind(contract, request);
        api.delayMs = 300;
        long started = System.nanoTime();
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000);
        Thread.sleep(350);
        assertEquals(0, api.posts.get());
    }

    @Test
    void totalDeadlineBoundsSlowContinuousMetadata() {
        contract.put("timeoutMs", 200);
        outputs.bind(contract, request);
        api.chunked = true;
        api.fragmentDelayMs = 20;
        long started = System.nanoTime();
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertTrue(
                (System.nanoTime() - started) / 1_000_000 < 1000,
                "read timeout alone permits continuous bytes beyond total deadline");
    }

    @Test
    void trailingJsonDocumentCannotBeSuccessful() {
        outputs.bind(contract, request);
        api.stdout = "{\"value\":\"ok\"} {\"foreign\":true}";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
    }

    @Test
    void truncatedJsonCannotBeSuccessful() {
        outputs.bind(contract, request);
        api.stdout = "{\"value\":\"incomplete";
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
    }

    @Test
    void offloadPreservesCompleteUtf8JsonAndRunOwnership() {
        contract.put("timeoutMs", 3000);
        contract.put("outputMaxBytes", 32);
        outputs.bind(contract, request);
        api.stdout = "{\"value\":\"" + "中".repeat(600) + "\"}";
        var result = invoke(false);
        assertEquals(1, result.fileRefs().size());
        var stored =
                database.db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals("READY", stored.get("state"));
        assertEquals(run, stored.get("run_id"));
        assertEquals("owner", stored.get("principal_id"));
        assertEquals(
                (long) api.stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                stored.get("size_bytes"));
    }

    @Test
    void trailingJsonCannotProduceReadyArtifact() {
        contract.put("timeoutMs", 3000);
        contract.put("outputMaxBytes", 32);
        outputs.bind(contract, request);
        api.stdout = "{\"value\":\"" + "x".repeat(600) + "\"} {}";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='READY'",
                        Integer.class,
                        tenant));
    }

    @Test
    void twoInstancesShareOneDurableSendCas() throws Exception {
        contract.put("timeoutMs", 1500);
        outputs.bind(contract, request);
        var barrier = new CountDownLatch(2);
        api.beforeGet =
                () -> {
                    if (api.gets.get() <= 2) {
                        barrier.countDown();
                        try {
                            assertTrue(barrier.await(1, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                };
        var second =
                AlgorithmTestDatabase.adapter(
                        database.db, api, 1048576, ArtifactStore.MAX_ARTIFACT_BYTES);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (var instance : List.of(adapter, second))
                tasks.add(
                        pool.submit(
                                () -> {
                                    AlgorithmTestDatabase.identity(tenant, "owner");
                                    try {
                                        instance.invoke(
                                                contract,
                                                Map.of("region", "A"),
                                                key,
                                                call,
                                                false,
                                                outputs,
                                                run,
                                                456);
                                        return true;
                                    } catch (SkillAdapters.Failure e) {
                                        return false;
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            int successes = 0;
            for (var task : tasks) if (task.get(3, TimeUnit.SECONDS)) successes++;
            assertTrue(successes >= 1);
            assertEquals(1, api.posts.get());
        }
    }

    @Test
    void forgedAnnotationCannotSupplyResult() {
        outputs.bind(contract, request);
        api.beforePost =
                body -> {
                    ((Map<String, Object>)
                                    ((Map<String, Object>) body.get("metadata")).get("annotations"))
                            .put(AlgorithmAdapter.IDENTITY, Digest.sha256("foreign"));
                };
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void identicalJobRecoversThrough409WithoutSecondPost() {
        outputs.bind(contract, request);
        api.conflictPost = true;
        assertEquals("ok", invoke(false).structured().path("value").asText());
        assertEquals(1, api.posts.get());
        assertEquals("ok", invoke(true).structured().path("value").asText());
        assertEquals(1, api.posts.get());
    }

    @Test
    void crossPrincipalAndTenantCannotQueryOrMutate() {
        outputs.bind(contract, request);
        AlgorithmTestDatabase.identity(tenant, "other");
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        AlgorithmTestDatabase.identity(tenant + "_other", "owner");
        assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals(0, api.routes.size());
        AlgorithmTestDatabase.identity(tenant, "owner");
        assertEquals(
                false,
                database.db.queryForObject(
                        "select send_started from skill.external_job_binding where tenant_id=? and"
                                + " call_id=?",
                        Boolean.class,
                        tenant,
                        call));
    }

    @Test
    void changedArgumentsOrContractCannotIssueRequests() {
        outputs.bind(contract, request);
        assertThrows(
                SkillAdapters.Failure.class,
                () ->
                        adapter.invoke(
                                contract,
                                Map.of("region", "B"),
                                key,
                                call,
                                false,
                                outputs,
                                run,
                                456));
        contract.put("sha256", Digest.sha256("changed"));
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.routes.size());
    }

    @Test
    void running404PreservesBindingAndNeverCreatesInQueryOnly() {
        outputs.bind(contract, request);
        api.dropPost = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        api.dropPost = false;
        api.missing = true;
        var before =
                database.db.queryForMap(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        var after =
                database.db.queryForMap(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals(before, after);
        assertEquals(1, api.posts.get());
    }

    @Test
    void algorithmBodyRetainsResourcesSecurityAndExplicitContainer() {
        outputs.bind(contract, request);
        invoke(false);
        var spec = Json.tree(api.job).path("spec");
        assertEquals(1, spec.path("parallelism").asInt());
        assertEquals(1, spec.path("completions").asInt());
        assertEquals(0, spec.path("backoffLimit").asInt());
        var pod = spec.path("template").path("spec");
        assertFalse(pod.path("automountServiceAccountToken").asBoolean());
        assertEquals("algorithm-runner", pod.path("serviceAccountName").asText());
        var container = pod.path("containers").get(0);
        assertTrue(container.path("securityContext").path("runAsNonRoot").asBoolean());
        assertFalse(container.path("securityContext").path("allowPrivilegeEscalation").asBoolean());
        assertTrue(container.path("resources").has("limits"));
        assertTrue(
                api.routes.stream()
                        .filter(p -> p.contains("/log?"))
                        .allMatch(
                                p ->
                                        p.contains("container=algorithm")
                                                && p.contains("follow=false")
                                                && !p.contains("limitBytes")
                                                && !p.contains("tailLines")));
    }

    @Test
    void sdkVisibleSourceConsumesAtMostLimitPlusOneProbeByteAndCloses() throws Exception {
        for (int limit : List.of(1024, 4096)) {
            var count = new java.util.concurrent.atomic.AtomicLong();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            var source =
                    okio.Okio.buffer(
                            new okio.ForwardingSource(new okio.Buffer().write(new byte[6000])) {
                                @Override
                                public long read(okio.Buffer sink, long bytes)
                                        throws java.io.IOException {
                                    long n = super.read(sink, bytes);
                                    if (n > 0) count.addAndGet(n);
                                    return n;
                                }

                                @Override
                                public void close() throws java.io.IOException {
                                    closed.set(true);
                                    super.close();
                                }
                            });
            var original =
                    new okhttp3.ResponseBody() {
                        public okhttp3.MediaType contentType() {
                            return null;
                        }

                        public long contentLength() {
                            return -1;
                        }

                        public okio.BufferedSource source() {
                            return source;
                        }
                    };
            try (var bounded = AlgorithmAdapter.bounded(original, limit)) {
                assertThrows(java.io.IOException.class, () -> bounded.byteStream().readAllBytes());
                assertEquals(
                        limit + 1,
                        bounded.consumed,
                        "SDK-visible source consumes at most one probe byte");
            }
            assertTrue(
                    count.get() <= 8192,
                    "underlying fixed transport buffer is separate from SDK materialization"
                            + " budget");
            assertTrue(closed.get());
        }
    }

    @Test
    void chunkedErrorBodyIsBoundedWithoutSend() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 1024, 4096);
        api.errorCode = 503;
        api.metadataPadding = 1500;
        api.chunked = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.posts.get());
        assertEquals(1, api.gets.get());
    }

    @Test
    void slowAcceptedPostIsUnknownAndHasOneReception() throws Exception {
        contract.put("timeoutMs", 200);
        outputs.bind(contract, request);
        api.beforePost =
                body -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
        long started = System.nanoTime();
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000);
        Thread.sleep(350);
        assertEquals(1, api.posts.get());
        assertEquals(0, api.active.get());
    }

    @Test
    void continuousLogBytesCannotExtendTotalDeadline() throws Exception {
        contract.put("timeoutMs", 200);
        outputs.bind(contract, request);
        api.chunked = true;
        api.stdout = "{\"value\":\"" + "x".repeat(600) + "\"}";
        api.beforeLog = () -> api.fragmentDelayMs = 20;
        long started = System.nanoTime();
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1000);
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
        while (api.active.get() > 0 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(0, api.active.get());
        assertEquals(1, api.logs.get());
    }

    @Test
    void apiChangeAndPolicyShrinkCannotQueryHistoricalBinding() throws Exception {
        outputs.bind(contract, request);
        try (var other = new AlgorithmApiFixture()) {
            var moved = AlgorithmTestDatabase.adapter(database.db, other, 1024, 4096);
            assertThrows(
                    SkillAdapters.Failure.class,
                    () ->
                            moved.invoke(
                                    contract,
                                    Map.of("region", "A"),
                                    key,
                                    call,
                                    true,
                                    outputs,
                                    run,
                                    456));
            assertEquals(0, other.routes.size());
        }
        var denied =
                new AlgorithmAdapter(
                        database.db,
                        Map.of(
                                "AGENT_K8S_API",
                                api.url(),
                                "AGENT_ALGORITHM_NAMESPACE",
                                "alg-test",
                                "AGENT_ALGORITHM_SERVICE_ACCOUNT",
                                "algorithm-runner",
                                "AGENT_ALGORITHM_ALLOWED_IMAGES",
                                "other:image",
                                "AGENT_ALGORITHM_ALLOW_LOOPBACK_HTTP",
                                "true"),
                        1024,
                        4096);
        assertEquals(
                "NO_PERMISSION",
                assertThrows(
                                SkillAdapters.Failure.class,
                                () ->
                                        denied.invoke(
                                                contract,
                                                Map.of("region", "A"),
                                                key,
                                                call,
                                                true,
                                                outputs,
                                                run,
                                                456))
                        .semantic);
        assertEquals(0, api.routes.size());
    }

    @Test
    void zeroPodsCannotSupplyResult() {
        outputs.bind(contract, request);
        api.podCount = 0;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void missingStatusCannotBeCountedAsComplete() {
        outputs.bind(contract, request);
        api.condition = "";
        contract.put("timeoutMs", 200);
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
        assertEquals(0, api.logs.get());
    }

    @Test
    void decodedGzipMetadataIsBoundedBeforeSdkModels() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 1024, 4096);
        api.metadataPadding = 1500;
        api.gzip = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.logs.get());
    }

    @Test
    void decodedGzipErrorBodyUsesSameBudget() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 1024, 4096);
        api.metadataPadding = 1500;
        api.errorCode = 503;
        api.gzip = true;
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(0, api.posts.get());
        assertEquals(1, api.gets.get());
    }

    @Test
    void decodedGzipLogCannotBypassHardBytes() {
        outputs.bind(contract, request);
        adapter = AlgorithmTestDatabase.adapter(database.db, api, 4096, 4096);
        api.gzip = true;
        api.stdout = "{\"value\":\"" + "中".repeat(1500) + "\"}";
        assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals(1, api.logs.get());
    }

    @Test
    void completeSmallSchemaViolationIsOutputInvalid() {
        contract.put(
                "outputSchema",
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer"))));
        outputs.bind(contract, request);
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertFalse(failure.unknown);
        assertEquals("OUTPUT_INVALID", failure.semantic);
    }

    @Test
    void foreignTenantSecretCannotReachAlgorithmApi() {
        AlgorithmTestDatabase.identity(tenant + "_foreign", "owner");
        String foreign = SecretVault.put("foreign-fixture-only");
        AlgorithmTestDatabase.identity(tenant, "owner");
        contract.put("authRef", foreign);
        outputs.bind(contract, request);
        assertEquals(
                "NO_PERMISSION",
                assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).semantic);
        assertEquals(0, api.routes.size());
    }

    @Test
    void exactBearerRejectionKeepsRemoteObservationUnknown() {
        outputs.bind(contract, request);
        api.expectedBearer = "different-fixture-only";
        assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
        assertEquals(1, api.authRejected.get());
        assertEquals(0, api.posts.get());
        assertEquals(0, api.logs.get());
    }

    @Test
    void completeOffloadedSchemaViolationIsOutputInvalidWithoutReadyArtifact() {
        contract.put("timeoutMs", 3000);
        contract.put("outputMaxBytes", 32);
        contract.put(
                "outputSchema",
                Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer"))));
        outputs.bind(contract, request);
        api.stdout = "{\"value\":\"" + "x".repeat(600) + "\"}";
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertFalse(failure.unknown);
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(
                0,
                database.db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='READY'",
                        Integer.class,
                        tenant));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOAD_ACK", "READBACK"})
    void algorithmOutputDeadlineCapsMinioUploadAndReadbackAndLeavesSharedClientUsable(String stage)
            throws Exception {
        try (var minio = new AlgorithmMinioFixture()) {
            artifacts.close();
            artifacts = new ArtifactStore(database.db, minio.configuration());
            outputs = new SkillAdapters(artifacts, adapter);
            // Warm fixed SDK/crypto/region lookup outside the measured operation; all bodies <1KiB.
            artifacts.offload(
                    new byte[0],
                    new java.io.ByteArrayInputStream(
                            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    Map.of("type", "object"),
                    call + 1,
                    run);
            int beforePuts = minio.puts.get(), beforeReads = minio.reads.get();
            minio.slowStage = stage;
            contract.put("timeoutMs", 200);
            contract.put("outputMaxBytes", 32);
            outputs.bind(contract, request);
            String stdout = "{\"value\":\"" + "x".repeat(600) + "\"}";
            long started = System.nanoTime();
            long deadline = started + TimeUnit.MILLISECONDS.toNanos(200);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            outputs.readAlgorithm(
                                    new java.io.ByteArrayInputStream(
                                            stdout.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)),
                                    contract,
                                    call,
                                    run,
                                    deadline));
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertFalse(
                    Thread.currentThread().isInterrupted(),
                    "native deadline is not caller interruption");
            assertTrue(
                    elapsed < 450,
                    "single 200ms deadline must include MinIO " + stage + ", measured=" + elapsed);
            assertEquals(beforePuts + 1, minio.puts.get());
            if (stage.equals("READBACK")) assertEquals(beforeReads + 1, minio.reads.get());
            assertEquals(
                    0,
                    database.db.queryForObject(
                            "select count(*) from skill.artifact where tenant_id=? and call_id=?"
                                    + " and state='READY'",
                            Integer.class,
                            tenant,
                            call));
            assertEquals(
                    "CLEANUP_PENDING",
                    database.db.queryForObject(
                            "select state from skill.artifact where tenant_id=? and call_id=?",
                            String.class,
                            tenant,
                            call));
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            while (minio.active.get() > 0 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(0, minio.active.get());
            int afterStop = minio.routes.size();
            Thread.sleep(50);
            assertEquals(afterStop, minio.routes.size(), "no late upload/readback/cleanup I/O");
            assertTrue(
                    Thread.getAllStackTraces().keySet().stream()
                            .noneMatch(
                                    thread ->
                                            thread.getName()
                                                    .startsWith(
                                                            "OkHttp http://127.0.0.1:"
                                                                    + minio.server
                                                                            .getAddress()
                                                                            .getPort())),
                    "no active MinIO I/O task after timeout return");
            if (stage.equals("READBACK"))
                assertTrue(
                        minio.disconnected.get() > 0,
                        "expired readback closes its response stream");
            assertEquals(0, api.posts.get());
            minio.slowStage = "UPLOAD_ACK";
            long normalStarted = System.nanoTime();
            var normal =
                    artifacts.offload(
                            new byte[0],
                            new java.io.ByteArrayInputStream(
                                    "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            Map.of("type", "object"),
                            call + 2,
                            run);
            assertNotNull(normal.get("artifactId")); // Original shared entry is still usable.
            long normalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - normalStarted);
            assertTrue(
                    normalMs >= 450 && normalMs < 1500,
                    "original entry keeps its own 45s timeout, no leaked algorithm deadline");
            minio.slowStage = "";
            database.db.update(
                    "update skill.artifact set expires_at=now()-interval '1"
                        + " second',cleanup_after=now()-interval '1 second' where tenant_id=? and"
                        + " state<>'DELETED'",
                    tenant);
            artifacts.cleanTenant(tenant, 8);
            assertTrue(minio.objects.isEmpty());
            System.out.println(
                    "ALGORITHM_MINIO stage="
                            + stage
                            + " budgetMs=200 elapsedMs="
                            + elapsed
                            + " originalEntryMs="
                            + normalMs
                            + " POST="
                            + api.posts.get()
                            + " active="
                            + minio.active.get()
                            + " objectsAfterOwnedCleanup="
                            + minio.objects.size());
        }
    }

    @Test
    void fullKubernetesChainPassesOriginalAbsoluteDeadlineToOutput() {
        contract.put("timeoutMs", 1000);
        api.delayMs = 70;
        var firstGet = new java.util.concurrent.atomic.AtomicLong();
        var outputDeadline = new java.util.concurrent.atomic.AtomicLong();
        var outputStarted = new java.util.concurrent.atomic.AtomicLong();
        api.beforeGet = () -> firstGet.compareAndSet(0, System.nanoTime());
        outputs =
                new SkillAdapters(artifacts, adapter) {
                    @Override
                    public Raw readAlgorithm(
                            java.io.InputStream input,
                            Map<String, Object> c,
                            long id,
                            String r,
                            long deadline)
                            throws java.io.IOException {
                        outputDeadline.set(deadline);
                        outputStarted.set(System.nanoTime());
                        return super.readAlgorithm(input, c, id, r, deadline);
                    }
                };
        outputs.bind(contract, request);
        assertNotNull(invoke(false));
        long fromFirst = TimeUnit.NANOSECONDS.toMillis(outputDeadline.get() - firstGet.get());
        long remaining = TimeUnit.NANOSECONDS.toMillis(outputDeadline.get() - outputStarted.get());
        assertTrue(fromFirst > 700 && fromFirst <= 1000, "same initial deadline, ms=" + fromFirst);
        assertTrue(
                remaining > 0 && remaining < 800,
                "prelude consumes deadline, remaining=" + remaining);
        assertEquals(1, api.posts.get());
    }

    @Test
    void frontStageDeadlineExpiresBeforeAnyMinioPutOrDelayedPost() throws Exception {
        try (var minio = new AlgorithmMinioFixture()) {
            artifacts.close();
            artifacts = new ArtifactStore(database.db, minio.configuration());
            outputs = new SkillAdapters(artifacts, adapter);
            contract.put("timeoutMs", 200);
            contract.put("outputMaxBytes", 32);
            outputs.bind(contract, request);
            api.delayMs = 300;
            assertTrue(assertThrows(SkillAdapters.Failure.class, () -> invoke(false)).unknown);
            Thread.sleep(350);
            assertEquals(0, api.posts.get());
            assertEquals(0, minio.puts.get());
            assertEquals(0, minio.routes.size());
            assertEquals(
                    0,
                    database.db.queryForObject(
                            "select count(*) from skill.artifact where tenant_id=?",
                            Integer.class,
                            tenant));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNTIME", "ASSERTION"})
    void postFixtureFailureHasSafeStageAndExplicitBackgroundAssertion(String kind) {
        outputs.bind(contract, request);
        String sentinel = "SECRET_BODY_MUST_NOT_ESCAPE";
        api.beforePost =
                body -> {
                    if (kind.equals("ASSERTION")) throw new AssertionError(sentinel);
                    throw new IllegalStateException(sentinel);
                };
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertTrue(failure.unknown);
        assertTrue(
                failure.getMessage().contains("stage=POST_JOB category=HTTP_500"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel));
        System.out.println("ALGORITHM_DIAGNOSTIC " + failure.getMessage());
        assertThrows(AssertionError.class, api::assertHealthy);
        assertEquals(
                kind.equals("ASSERTION") ? AssertionError.class : IllegalStateException.class,
                api.backgroundFailure.getAndSet(null).getClass());
    }

    @Test
    void malformedSdkAckRetainsCategoryWithoutMaterializingDiagnosticBody() {
        outputs.bind(contract, request);
        String sentinel = "SECRET_ACK_MUST_NOT_ESCAPE";
        api.beforePost = body -> body.put("spec", sentinel);
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertTrue(failure.unknown);
        assertTrue(failure.getMessage().contains("stage=POST_JOB category="), failure.getMessage());
        assertFalse(failure.getMessage().contains(sentinel));
        System.out.println("ALGORITHM_DIAGNOSTIC " + failure.getMessage());
        api.assertHealthy();
        assertEquals(1, api.posts.get());
    }

    @Test
    void independentFixturesHaveDistinctStableJobUids() throws Exception {
        try (var other = new AlgorithmApiFixture()) {
            assertNotEquals(
                    api.uid, other.uid, "ephemeral API ports may be reused while PG rows remain");
            assertEquals(api.uid, api.ownerUid);
            assertEquals(other.uid, other.ownerUid);
        }
    }

    @Test
    void reusedApiNamespaceUidIsRejectedWithSafeBindStageDiagnostic() {
        var prior = new LinkedHashMap<>(request);
        prior.put("callId", call + 1);
        prior.put("idempotencyKey", key + "-prior");
        outputs.bind(contract, prior);
        database.db.update(
                "update skill.external_job_binding set"
                    + " external_job_uid=?,status='SUBMITTED',send_started=true where tenant_id=?"
                    + " and call_id=?",
                api.uid,
                tenant,
                call + 1);
        outputs.bind(contract, request);
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertTrue(
                failure.getMessage().contains("stage=BIND_UID category=DuplicateKeyException"),
                failure.getMessage());
        System.out.println("ALGORITHM_DIAGNOSTIC " + failure.getMessage());
        assertEquals(1, api.posts.get());
        var bound =
                database.db.queryForMap(
                        "select * from skill.external_job_binding where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertNull(bound.get("external_job_uid"));
        assertEquals("SENDING", bound.get("status"));
    }
}
