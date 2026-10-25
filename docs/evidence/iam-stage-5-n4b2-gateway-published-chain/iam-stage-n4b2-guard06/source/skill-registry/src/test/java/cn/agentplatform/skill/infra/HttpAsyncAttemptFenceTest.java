package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real PG arbitration; loopback HTTP has bounded fixed responses. No runtime database is used. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class HttpAsyncAttemptFenceTest {
    static JdbcTemplate db;
    String tenant;
    final long call = 91, attempt = 17;
    final AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger();
    Map<String, Object> contract, request;
    HttpServer server;
    HttpAsyncAdapter adapter;
    SkillAdapters outputs;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
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
    }

    void actor(String subject) {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject(subject)
                        .claim("tenant_id", tenant)
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @BeforeEach
    void setup() throws Exception {
        tenant = "attempt_" + UUID.randomUUID();
        actor("owner");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                x -> {
                    if (x.getRequestMethod().equals("POST")) posts.incrementAndGet();
                    else gets.incrementAndGet();
                    x.getRequestBody().readNBytes(4096);
                    byte[] body =
                            Json.write(
                                            Map.of(
                                                    "jobId",
                                                    "job-1",
                                                    "idempotencyKey",
                                                    "key",
                                                    "status",
                                                    "DONE",
                                                    "result",
                                                    Map.of("value", 7)))
                                    .getBytes(StandardCharsets.UTF_8);
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(200, body.length);
                    x.getResponseBody().write(body);
                    x.close();
                });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        contract =
                new LinkedHashMap<>(
                        Map.of(
                                "type",
                                "HTTP",
                                "sha256",
                                "frozen-contract",
                                "endpoint",
                                endpoint + "/submit",
                                "invocationKind",
                                "SUBMIT_QUERY",
                                "timeoutMs",
                                2000,
                                "outputMaxBytes",
                                4096,
                                "outputSchema",
                                Map.of("type", "object"),
                                "reconciliation",
                                Map.of(
                                        "endpoint",
                                        endpoint + "/jobs/{idempotencyKey}",
                                        "donePath",
                                        "$.status",
                                        "doneValue",
                                        "DONE")));
        contract.put("skillVersionId", 101);
        request =
                new LinkedHashMap<>(
                        Map.of(
                                "callId",
                                call,
                                "attemptId",
                                attempt,
                                "skillVersionId",
                                101,
                                "runId",
                                "run-1",
                                "idempotencyKey",
                                "key",
                                "arguments",
                                Map.of("region", "A"),
                                "guardedArguments",
                                Map.of("region", "A")));
        adapter = new HttpAsyncAdapter(db);
        outputs = new SkillAdapters(new ArtifactStore(db), new AlgorithmAdapter(db), adapter);
        adapter.bind(contract, request);
    }

    @AfterEach
    void close(TestInfo test) {
        System.out.printf(
                "ATTEMPT_FENCE_HTTP %s POST=%d GET=%d%n",
                test.getDisplayName(), posts.get(), gets.get());
        server.stop(0);
        SecurityContextHolder.clearContext();
    }

    SkillAdapters.Raw invoke(HttpAsyncAdapter target, boolean queryOnly) {
        return target.invoke(
                contract, Map.of("region", "A"), "key", call, queryOnly, "run-1", attempt, outputs);
    }

    // Only a test-controlled transaction simulates a future generation; no production reset exists.
    void replaceAttempt() {
        new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(db.getDataSource())))
                .executeWithoutResult(
                        tx ->
                                db.update(
                                        "update skill.external_job_binding set attempt_id=?,"
                                            + " request_snapshot=jsonb_set(request_snapshot,'{attemptId}',?::jsonb),"
                                            + " status='REGISTERED',send_started=false,external_job_id=null"
                                            + " where tenant_id=? and call_id=?",
                                        attempt + 1,
                                        Long.toString(attempt + 1),
                                        tenant,
                                        call));
    }

    @Test
    void lateOriginalSenderDoesNotPostToNewRegisteredAttempt() {
        replaceAttempt();
        assertThrows(RuntimeException.class, () -> invoke(adapter, false));
        assertEquals(0, posts.get());
        assertEquals(0, gets.get());
    }

    @Test
    void generationChangeAfterReadBeforeCasNeverPosts() {
        var racing =
                new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
                    @Override
                    public int update(String sql, Object... args) {
                        if (sql.contains("status='SENDING'")) replaceAttempt();
                        return super.update(sql, args);
                    }
                };
        assertThrows(RuntimeException.class, () -> invoke(new HttpAsyncAdapter(racing), false));
        assertEquals(0, posts.get());
        assertEquals(0, gets.get());
        assertEquals(
                false,
                db.queryForObject(
                        "select send_started from skill.external_job_binding where tenant_id=? and"
                            + " call_id=?",
                        Boolean.class,
                        tenant,
                        call));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "principal_id",
                "run_id",
                "skill_version_id",
                "idempotency_key",
                "request_snapshot",
                "metadata"
            })
    void identityChangeAfterReadBeforeCasNeverPosts(String field) {
        var racing =
                new JdbcTemplate(Objects.requireNonNull(db.getDataSource())) {
                    @Override
                    public int update(String sql, Object... args) {
                        if (sql.contains("status='SENDING'")) {
                            String mutation =
                                    switch (field) {
                                        case "skill_version_id" -> "skill_version_id=102";
                                        case "request_snapshot" ->
                                                "request_snapshot=request_snapshot ||"
                                                    + " '{\"leaseOwner\":\"changed\"}'::jsonb";
                                        case "metadata" ->
                                                "metadata='{\"protocol\":\"HTTP\",\"contractSha256\":\"changed\"}'::jsonb";
                                        default -> field + "='changed'";
                                    };
                            db.update(
                                    "update skill.external_job_binding set "
                                            + mutation
                                            + " where tenant_id=? and call_id=?",
                                    tenant,
                                    call);
                        }
                        return super.update(sql, args);
                    }
                };
        assertThrows(RuntimeException.class, () -> invoke(new HttpAsyncAdapter(racing), false));
        assertEquals(0, posts.get());
        assertEquals(0, gets.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "attemptId",
                "skillVersionId",
                "runId",
                "idempotencyKey",
                "arguments",
                "guardedArguments",
                "snapshot",
                "contract",
                "principal"
            })
    void bindConflictRequiresEveryFrozenIdentity(String field) {
        var changed = new LinkedHashMap<>(request);
        var changedContract = new LinkedHashMap<>(contract);
        switch (field) {
            case "attemptId", "skillVersionId" -> changed.put(field, 99);
            case "arguments", "guardedArguments" -> changed.put(field, Map.of("region", "B"));
            case "snapshot" -> changed.put("leaseOwner", "foreign-owner");
            case "contract" -> changedContract.put("sha256", "different-contract");
            case "principal" -> actor("foreign");
            default -> changed.put(field, "foreign");
        }
        assertThrows(RuntimeException.class, () -> adapter.bind(changedContract, changed));
        assertEquals(0, posts.get());
        assertEquals(0, gets.get());
    }

    @Test
    void queryOnlyNeverPostsAndWrongAttemptNeverQueriesOrWrites() {
        db.update(
                "update skill.external_job_binding set"
                    + " send_started=true,status='SUBMITTED',external_job_id='job-1' where"
                    + " tenant_id=? and call_id=?",
                tenant,
                call);
        invoke(adapter, true);
        assertEquals(0, posts.get());
        assertEquals(1, gets.get());
        replaceAttempt();
        assertThrows(RuntimeException.class, () -> invoke(adapter, true));
        assertEquals(0, posts.get());
        assertEquals(1, gets.get());
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
    void concurrentSameAttemptSendsOnlyOnce() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        workers.submit(
                                () -> {
                                    actor("owner");
                                    ready.countDown();
                                    try {
                                        assertTrue(go.await(5, TimeUnit.SECONDS));
                                        invoke(adapter, false);
                                    } catch (SkillAdapters.Failure expected) {
                                        assertEquals("RESULT_UNKNOWN", expected.semantic);
                                    } catch (InterruptedException ex) {
                                        throw new RuntimeException(ex);
                                    } finally {
                                        SecurityContextHolder.clearContext();
                                    }
                                }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, posts.get());
    }
}
