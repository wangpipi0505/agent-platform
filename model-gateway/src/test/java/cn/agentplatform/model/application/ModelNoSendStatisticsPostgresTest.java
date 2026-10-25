package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.model.infra.ModelStore;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real call_log insertion, PG send CAS and Redis refunds at the transport boundary. */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ModelNoSendStatisticsPostgresTest {
    static JdbcTemplate db;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static ModelQuota quota;
    Actor actor;
    ModelStore store;
    HttpServer server;
    long entry, provider;
    AtomicInteger requests = new AtomicInteger();
    boolean missingUsage;
    String run = "r_nosend";

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_model_test",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("model").defaultSchema("model").load().migrate();
        db = new JdbcTemplate(ds);
        var config = new RedisStandaloneConfiguration("127.0.0.1", 26379);
        config.setPassword(System.getenv("AGENT_REDIS_PASSWORD"));
        connection = new LettuceConnectionFactory(config);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        quota = new ModelQuota(redis);
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @BeforeEach
    void fixture() throws Exception {
        actor =
                new Actor(
                        "nosend-stats-" + UUID.randomUUID(),
                        "owner",
                        Set.of("ADMIN", "SERVICE"),
                        Map.of());
        store = new ModelStore(db);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/models",
                exchange -> {
                    byte[] body =
                            "{\"data\":[{\"id\":\"gpt-4\"}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    var response =
                            new LinkedHashMap<String, Object>(
                                    Map.of(
                                            "id",
                                            "fixture",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "gpt-4",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    "ok"),
                                                            "finish_reason",
                                                            "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    8,
                                                    "completion_tokens",
                                                    4,
                                                    "total_tokens",
                                                    12)));
                    if (missingUsage) response.remove("usage");
                    byte[] body = Json.write(response).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        provider =
                ((Number)
                                store.createProvider(
                                                Json.tree(
                                                        Map.of(
                                                                "code",
                                                                "fixture",
                                                                "name",
                                                                "fixture",
                                                                "baseUrl",
                                                                "http://127.0.0.1:"
                                                                        + server.getAddress()
                                                                                .getPort(),
                                                                "trustLabels",
                                                                List.of("LOCAL"))),
                                                actor)
                                        .get("id"))
                        .longValue();
        assertThat(new ModelCalls(store, quota).testProvider(provider, actor).get("ok"))
                .isEqualTo(true);
        requests.set(0);
        entry =
                ((Number)
                                store.createEntry(
                                                Json.tree(
                                                        Map.of(
                                                                "providerId",
                                                                provider,
                                                                "modelName",
                                                                "gpt-4",
                                                                "contextWindow",
                                                                8192,
                                                                "maxOutputTokens",
                                                                128,
                                                                "capabilities",
                                                                List.of("chat"))),
                                                actor)
                                        .get("id"))
                        .longValue();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    com.fasterxml.jackson.databind.JsonNode request() {
        return Json.tree(
                Map.of(
                        "modelEntryId",
                        entry,
                        "invocationRef",
                        UUID.randomUUID().toString(),
                        "runId",
                        run,
                        "appCode",
                        "one",
                        "messages",
                        List.of(Map.of("role", "user", "content", "Hello")),
                        "params",
                        Map.of("maxOutputTokens", 32),
                        "requiredLabels",
                        List.of("LOCAL")));
    }

    ModelStore fencedStore(boolean failNoSend, boolean recoveryWins) {
        return new ModelStore(db) {
            @Override
            public void quotaSending(Actor owner, Map<String, Object> receipt) {
                // The real call_log has already committed. Expire its real PG lease, then execute
                // the production CAS; no injected transport or fake summary is involved.
                assertThat(
                                db.queryForObject(
                                        "select count(*) from model.call_log where tenant_id=?",
                                        Long.class,
                                        actor.tenantId()))
                        .isEqualTo(1L);
                db.update(
                        "update model.quota_reservation set lease_until=now()-interval '1 second'"
                            + " where tenant_id=? and reservation_id=?",
                        owner.tenantId(),
                        receipt.get("id"));
                if (recoveryWins) new ModelCalls(store, quota).reconcilePendingUsage();
                super.quotaSending(owner, receipt);
            }

            @Override
            public boolean quotaNoSend(String tenant, String reservation, String error) {
                if (failNoSend)
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "injected NO_SEND confirmation failure");
                return super.quotaNoSend(tenant, reservation, error);
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistedLogThenSendCasFailureIsExcludedForBothTransports(boolean stream) {
        assertFencedCall(fencedStore(false, false), stream);
        assertNoSendAndRefund();
        assertNoSends();
        new ModelCalls(store, quota).call(request(), actor, null);
        assertThat(requests.get()).isEqualTo(1);
        var report = rows().getFirst();
        assertThat(report)
                .containsEntry("calls", 1L)
                .containsEntry("failed", 0L)
                .containsEntry("usageMissingCalls", 0L)
                .containsEntry("knownTokens", 12L)
                .containsEntry("totalTokens", 12L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recoveryReclassifiesPersistedLogAfterNoSendConfirmationFailure(boolean stream) {
        assertFencedCall(fencedStore(true, false), stream);
        assertThat(reservation().get("status")).isEqualTo("RESERVING");
        assertThat(rows().getFirst())
                .containsEntry("calls", 1L)
                .containsEntry("usageMissingCalls", 1L)
                .containsEntry("totalTokens", null);
        new ModelCalls(store, quota).reconcilePendingUsage();
        assertNoSendAndRefund();
        assertNoSends();
    }

    @Test
    void recoveryWinningBeforeSendCasStillExcludesPersistedLog() {
        assertFencedCall(fencedStore(false, true), false);
        assertNoSendAndRefund();
        assertNoSends();
    }

    @Test
    void realSentWithoutUsageRemainsUnknownAndKeepsItsQuota() {
        missingUsage = true;
        new ModelCalls(store, quota).call(request(), actor, null);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(reservation().get("status")).isEqualTo("SENT");
        assertThat(rows().getFirst())
                .containsEntry("calls", 1L)
                .containsEntry("usageMissingCalls", 1L)
                .containsEntry("totalTokens", null);
        var receipt = Json.read(reservation().get("quota_receipt").toString(), Map.class);
        var keys = (List<String>) receipt.get("keys");
        String daily = redis.opsForValue().get(keys.get(1));
        assertThat(Integer.parseInt(daily)).isPositive();
        new ModelCalls(store, quota).reconcilePendingUsage();
        assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo(daily);
    }

    void assertFencedCall(ModelStore guarded, boolean stream) {
        assertThatThrownBy(
                        () ->
                                new ModelCalls(guarded, quota)
                                        .call(request(), actor, stream ? text -> {} : null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertThat(
                        db.queryForMap(
                                "select status,total_tokens from model.call_log where tenant_id=?",
                                actor.tenantId()))
                .containsEntry("status", "FAILED")
                .containsEntry("total_tokens", null);
    }

    Map<String, Object> reservation() {
        return db.queryForMap(
                "select status,usage_reconciliation,quota_receipt::text from"
                    + " model.quota_reservation where tenant_id=?",
                actor.tenantId());
    }

    void assertNoSendAndRefund() {
        assertThat(reservation())
                .containsEntry("status", "NO_SEND")
                .containsEntry("usage_reconciliation", "RECONCILED");
        var receipt = Json.read(reservation().get("quota_receipt").toString(), Map.class);
        var keys = (List<String>) receipt.get("keys");
        assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("0");
        assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("0");
        String tokens = Objects.toString(redis.opsForHash().get(keys.getFirst(), "tokens"));
        String requestsLeft = Objects.toString(redis.opsForHash().get(keys.getFirst(), "requests"));
        assertThat(Double.parseDouble(tokens)).isEqualTo(60000);
        assertThat(Double.parseDouble(requestsLeft)).isEqualTo(60);
        new ModelCalls(store, quota).reconcilePendingUsage();
        assertThat(redis.opsForHash().get(keys.getFirst(), "tokens").toString()).isEqualTo(tokens);
        assertThat(redis.opsForHash().get(keys.getFirst(), "requests").toString())
                .isEqualTo(requestsLeft);
        assertThat(requests.get()).isZero();
    }

    List<Map<String, Object>> rows() {
        return (List<Map<String, Object>>) store.statistics(List.of(run), actor).get("byRun");
    }

    void assertNoSends() {
        assertThat(rows()).isEmpty();
        assertThat(store.statistics(actor))
                .containsEntry("calls", 0L)
                .containsEntry("usage_missing", 0L)
                .containsEntry("tokens", 0L)
                .containsEntry("known_tokens", 0L);
    }
}
