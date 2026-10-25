package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real PG/MinIO/SSE. Engine claim/approval/fact authority is an isolated HTTP fixture. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class McpExecutionPostgresTest {
    static JdbcTemplate db;
    McpAdapterTest.Peer peer;
    HttpServer engine;
    ExecutorService workers;
    SkillCatalog catalog;
    ArtifactStore artifacts;
    SkillExecution execution;
    String tenant, code, run;
    long version, call;
    Map<String, Object> request, authority;
    Map<String, Object> extraContract = Map.of();
    AtomicInteger submitted = new AtomicInteger(), facts = new AtomicInteger();

    @BeforeAll
    static void database() {
        McpManagementTest.database();
        db = McpManagementTest.db;
    }

    @BeforeEach
    void start() throws Exception {
        tenant = "mcp_execution_" + UUID.randomUUID();
        code = "mcp";
        run = "r_mcp_" + UUID.randomUUID().toString().replace("-", "");
        call = System.nanoTime();
        identity(tenant, "owner");
        peer = new McpAdapterTest.Peer();
        workers = Executors.newVirtualThreadPerTaskExecutor();
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.setExecutor(workers);
        engine.createContext(
                "/internal/tool-calls",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    exchange.getRequestBody().readNBytes(128 * 1024);
                    Map<String, Object> reply;
                    if (path.endsWith("/approval"))
                        reply =
                                Map.of(
                                        "approved",
                                        true,
                                        "skillVersionId",
                                        version,
                                        "parametersDigest",
                                        Digest.sha256(Json.canonical(request.get("arguments"))));
                    else if (path.endsWith("/claim")) {
                        var claimed = new LinkedHashMap<>(authority);
                        claimed.put("claimed", true);
                        reply = claimed;
                    } else if (path.endsWith("/submitted")) {
                        submitted.incrementAndGet();
                        assertEquals(0, peer.calls.get());
                        reply = Map.of("submitted", true);
                    } else if (path.endsWith("/fact")) {
                        facts.incrementAndGet();
                        reply = Map.of("accepted", true);
                    } else reply = authority;
                    byte[] bytes =
                            Json.write(ApiResponse.ok(reply)).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        engine.start();
        var clients = new SkillClients();
        catalog = new SkillCatalog(db, McpManagementTest.tx, clients, "http://unused");
        artifacts = new ArtifactStore(db);
        execution =
                new SkillExecution(
                        db,
                        McpManagementTest.tx,
                        catalog,
                        new SkillAdapters(artifacts, mock(AlgorithmAdapter.class)),
                        clients,
                        "http://127.0.0.1:" + engine.getAddress().getPort());
    }

    void identity(String tenant, String principal) {
        var jwt =
                Jwt.withTokenValue("fixture-direct-service-context")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("EDITOR", "ADMIN", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    void prepare(boolean write, int outputLimit) {
        var c = new LinkedHashMap<String, Object>();
        c.putAll(
                Map.of(
                        "code",
                        code,
                        "name",
                        "MCP PG fixture",
                        "type",
                        "MCP",
                        "description",
                        "isolated execution",
                        "endpoint",
                        peer.url(),
                        "toolName",
                        "lookup",
                        "timeoutMs",
                        2000));
        c.put("inputSchema", peer.inputSchema);
        c.put("outputSchema", Map.of("type", "object"));
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
        c.put(
                "guard",
                Map.of("roles", List.of("SERVICE"), "attributes", Map.of("region", List.of("A"))));
        c.put("constraints", Map.of("outputMaxBytes", outputLimit));
        c.putAll(extraContract);
        version = ((Number) catalog.create(c).get("skillVersionId")).longValue();
        // Published fixture only: product publication still requires connection/selection reports.
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                tenant,
                version);
        db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where tenant_id=?"
                        + " and code=?",
                version,
                tenant,
                code);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "skillVersionId",
                                version,
                                "attemptId",
                                456,
                                "leaseOwner",
                                "generation",
                                "nodeInstanceId",
                                123,
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
                                code,
                                "args_digest",
                                Digest.sha256(Json.canonical(request.get("arguments"))),
                                "node_instance_id",
                                123,
                                "runId",
                                run,
                                "idempotency_key",
                                "logical-" + call,
                                "trigger_type",
                                "TASK",
                                "attempt_id",
                                456,
                                "status",
                                "REGISTERED",
                                "mock_snapshot",
                                Map.of()));
    }

    @AfterEach
    void stop() throws Exception {
        try {
            identity(tenant, "owner");
            db.update(
                    "update skill.artifact set expires_at=now()-interval '1"
                        + " second',cleanup_after=now()-interval '1 second' where tenant_id=? and"
                        + " state<>'DELETED'",
                    tenant);
            if (artifacts != null) artifacts.cleanTenant(tenant, 8);
        } finally {
            if (artifacts != null) artifacts.close();
            engine.stop(0);
            workers.shutdownNow();
            peer.close();
            SecurityContextHolder.clearContext();
        }
    }

    Map<String, Object> fact() {
        return db.queryForMap(
                "select * from skill.execution_fact where tenant_id=? and call_id=?", tenant, call);
    }

    @Test
    void repeatedReadUsesOnePersistedFactAndOneRealToolsCall() {
        prepare(false, 1024 * 1024);
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
        assertEquals(1, peer.calls.get());
        assertEquals(1, peer.opens.get());
        assertEquals(1, submitted.get());
        assertEquals(1, facts.get());
        assertEquals("SUCCEEDED", fact().get("status"));
        assertEquals(false, fact().get("is_mock"));
    }

    @Test
    void sentWriteDisconnectPersistsUnknownAndDuplicateEngineRequestNeverResends() {
        prepare(true, 1024 * 1024);
        peer.dropCall = true;
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        assertEquals(1, peer.calls.get());
        assertEquals(1, peer.opens.get());
        assertEquals(1, submitted.get());
        var row = fact();
        assertEquals("UNKNOWN", row.get("status"));
        assertEquals(456L, row.get("attempt_id"));
        assertEquals(
                "RESULT_UNKNOWN",
                Json.read(row.get("result").toString()).path("error").path("semantic").asText());
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=?",
                        Integer.class,
                        tenant));
    }

    @Test
    void unknownMcpWithoutQueryContractCannotResendDuringRepeatedReconcile() {
        prepare(true, 1024 * 1024);
        peer.dropCall = true;
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        authority.put("status", "UNKNOWN");
        var before = fact();
        var replies = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2; i++) {
            try {
                replies.add(execution.reconcile(call));
            } catch (PlatformException failure) {
                replies.add(Map.of("status", "HTTP_" + failure.status()));
            }
        }
        assertEquals(1, peer.calls.get());
        assertEquals(1, peer.opens.get());
        assertEquals("UNKNOWN", fact().get("status"));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from skill.reconciliation_note where tenant_id=? and"
                                + " call_id=? and outcome='UNKNOWN'",
                        Integer.class,
                        tenant,
                        call));
        var after = fact();
        for (String field :
                List.of(
                        "attempt_id",
                        "execution_owner",
                        "skill_version_id",
                        "args_digest",
                        "skill_contract_sha256",
                        "request_snapshot",
                        "result"))
            assertEquals(
                    Objects.toString(before.get(field)),
                    Objects.toString(after.get(field)),
                    field + " must remain unchanged");
        assertEquals(
                List.of("UNKNOWN", "UNKNOWN"),
                replies.stream().map(reply -> reply.get("status")).toList());
        for (var reply : replies) {
            assertEquals("UNKNOWN", reply.get("outcome"));
            assertEquals(false, reply.get("retryBusinessAllowed"));
        }
    }

    @Test
    void missingCurrentRemoteToolProducesFailedFactWithoutBusinessWrite() {
        prepare(true, 1024 * 1024);
        peer.toolName = "removed";
        assertEquals("FAILED", execution.execute(code, request).get("status"));
        assertEquals(0, peer.calls.get());
        assertEquals("FAILED", fact().get("status"));
        assertEquals(
                "PARAM_ERROR",
                Json.read(fact().get("result").toString()).path("error").path("semantic").asText());
    }

    @Test
    void explicitHttpEvidenceContractStillReconcilesOriginalMcpCall() {
        var queries = new AtomicInteger();
        peer.server.createContext(
                "/original-call-query",
                exchange -> {
                    queries.incrementAndGet();
                    assertEquals("GET", exchange.getRequestMethod());
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "idempotencyKey",
                                                    request.get("idempotencyKey"),
                                                    "state",
                                                    "DONE",
                                                    "result",
                                                    Map.of("value", 9)))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        extraContract =
                Map.of(
                        "reconciliation",
                        Map.of(
                                "endpoint",
                                peer.url()
                                        .replace(
                                                "/sse",
                                                "/original-call-query?key={idempotencyKey}"),
                                "donePath",
                                "$.state",
                                "doneValue",
                                "DONE"));
        prepare(true, 1024 * 1024);
        peer.dropCall = true;
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        authority.put("status", "UNKNOWN");
        assertEquals("SUCCEEDED", execution.reconcile(call).get("status"));
        assertEquals("SUCCEEDED", execution.reconcile(call).get("status"));
        assertEquals(1, queries.get());
        assertEquals(1, peer.calls.get());
        assertEquals(1, peer.opens.get());
        assertEquals("SUCCEEDED", fact().get("status"));
        assertEquals(
                9,
                Json.read(fact().get("result").toString())
                        .path("structured")
                        .path("value")
                        .asInt());
    }

    @Test
    void savedLiteralRefSchemaSurvivesCatalogAndExecutorSharedGate() {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put(
                "properties",
                Map.of(
                        "region",
                        Map.of("type", "string"),
                        "optional",
                        Map.of("default", Map.of("$ref", peer.schemaUrl()))));
        prepare(false, 1024 * 1024);
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
        assertEquals(1, peer.calls.get());
        assertEquals(0, peer.schemaReads.get());
        assertEquals(
                Json.tree(peer.inputSchema),
                Json.tree(catalog.versionId(version).get("inputSchema")));
    }

    @Test
    void writeIsErrorPersistsUnknownRatherThanRetryableFailure() {
        prepare(true, 1024 * 1024);
        peer.isError = true;
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        assertEquals("UNKNOWN", execution.execute(code, request).get("status"));
        assertEquals(1, peer.calls.get());
        assertEquals("UNKNOWN", fact().get("status"));
    }

    @Test
    void externalMcpHttpDoesNotHoldCatalogRowsOrFactTransactionOpen() {
        prepare(false, 1024 * 1024);
        peer.beforeCall =
                () ->
                        assertDoesNotThrow(
                                () -> {
                                    try (var connection =
                                            Objects.requireNonNull(db.getDataSource())
                                                    .getConnection()) {
                                        connection.setAutoCommit(false);
                                        try (var lock =
                                                connection.prepareStatement(
                                                        "select id from skill.skill_version where"
                                                                + " tenant_id=? and id=? for update"
                                                                + " nowait")) {
                                            lock.setString(1, tenant);
                                            lock.setLong(2, version);
                                            try (var row = lock.executeQuery()) {
                                                assertTrue(row.next());
                                            }
                                        } finally {
                                            connection.rollback();
                                        }
                                    }
                                });
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
        assertEquals(1, peer.calls.get());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")
    void boundedMcpResultOverOutputGateUsesOwnedPersistentArtifact() throws Exception {
        prepare(false, 1024);
        peer.output = Json.write(Map.of("value", "x".repeat(8192)));
        assertEquals("SUCCEEDED", execution.execute(code, request).get("status"));
        var envelope = Json.read(fact().get("result").toString());
        var ref = envelope.path("fileRefs").get(0);
        assertNotNull(ref);
        String id = ref.path("artifactId").asText();
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("READY", row.get("state"));
        assertEquals("owner", row.get("principal_id"));
        assertEquals(run, row.get("run_id"));
        assertEquals(call, row.get("call_id"));
        try (var download = artifacts.download(id, run)) {
            byte[] bytes = download.source().readAllBytes();
            assertEquals(peer.output, new String(bytes, StandardCharsets.UTF_8));
            assertEquals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    ref.path("sha256").asText());
        }
        identity(tenant, "other");
        assertThrows(PlatformException.class, () -> artifacts.download(id, run));
        identity(tenant, "owner");
        assertThrows(PlatformException.class, () -> artifacts.download(id, "r_other"));
        assertEquals(1, peer.calls.get());
    }
}
