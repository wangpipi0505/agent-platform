package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.model.infra.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 独立PG+本地受控模型端点；验证真实SDK网络字节及发送前持久化，不宣称外部模型已验收。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ModelCallsPostgresTest {
    static JdbcTemplate db;
    static org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
            redisConnection;
    static ModelQuota quota;
    ModelStore store;
    ModelCalls service;
    HttpServer server;
    long modelId;
    Actor actor = new Actor("model-tests", "model-tester", Set.of("EDITOR"), Map.of());
    Actor admin = new Actor("model-tests", "model-admin", Set.of("ADMIN"), Map.of());
    AtomicInteger requests = new AtomicInteger();
    AtomicBoolean sawFrozen = new AtomicBoolean();
    AtomicReference<String> authorization = new AtomicReference<>();
    String behavior = "TEXT";
    AtomicReference<java.util.concurrent.CountDownLatch> probeEntered = new AtomicReference<>(),
            probeRelease = new AtomicReference<>();
    AtomicReference<com.fasterxml.jackson.databind.JsonNode> tokenizerMaterial =
            new AtomicReference<>();
    AtomicBoolean rejectWire = new AtomicBoolean();
    long userDailyBefore;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_model_test",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("model").defaultSchema("model").load().migrate();
        db = new JdbcTemplate(ds);
        var config =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                        "127.0.0.1", 26379);
        config.setPassword(System.getenv("AGENT_REDIS_PASSWORD"));
        redisConnection =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        config);
        redisConnection.afterPropertiesSet();
        quota =
                new ModelQuota(
                        new org.springframework.data.redis.core.StringRedisTemplate(
                                redisConnection));
    }

    @AfterAll
    static void closeRedis() {
        redisConnection.destroy();
    }

    @BeforeEach
    void fixture() throws Exception {
        store = new ModelStore(db);
        service = new ModelCalls(store, quota);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/tokenize",
                exchange -> {
                    var material =
                            Json.read(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    tokenizerMaterial.set(material.path("request"));
                    int count =
                            rejectWire.get() && material.path("request").has("model")
                                    ? 500
                                    : material.path("request")
                                                            .path("params")
                                                            .path("output")
                                                            .has("format")
                                                    || material.path("request")
                                                            .has("response_format")
                                            ? 40000
                                            : 123;
                    byte[] bytes =
                            Json.write(
                                            Map.of(
                                                    "count",
                                                    count,
                                                    "request_sha256",
                                                    material.path("request_sha256").asText()))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/v1/models",
                exchange -> {
                    if (probeEntered.get() != null) {
                        probeEntered.get().countDown();
                        try {
                            probeRelease.get().await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] bytes =
                            Json.write(Map.of("data", List.of(Map.of("id", "gpt-4"))))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.createContext(
                "/v1/chat/completions",
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    requests.incrementAndGet();
                    byte[] wire = exchange.getRequestBody().readAllBytes();
                    String raw = new String(wire, StandardCharsets.UTF_8);
                    var rows =
                            db.queryForList(
                                    "select request_snapshot::text from model.call_log where"
                                            + " tenant_id='model-tests' and status='SENDING'");
                    sawFrozen.set(
                            rows.stream()
                                    .anyMatch(
                                            r ->
                                                    Json.read(r.get("request_snapshot").toString())
                                                            .equals(Json.read(raw))));
                    if (behavior.startsWith("HTTP")) {
                        int status = Integer.parseInt(behavior.substring(4));
                        byte[] bytes =
                                "{\"error\":{\"message\":\"DO-NOT-LEAK-UPSTREAM\"}}".getBytes();
                        exchange.sendResponseHeaders(status, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                        return;
                    }
                    if ("FAIL".equals(behavior)) {
                        byte[] bytes =
                                "{\"error\":{\"message\":\"test unavailable\",\"type\":\"server_error\"}}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(503, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                        return;
                    }
                    if (Json.read(raw).path("stream").asBoolean()) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        String first =
                                "data:"
                                    + " {\"id\":\"stream-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hello"
                                    + " \"}}]}\n\n";
                        String last =
                                "data:"
                                    + " {\"id\":\"stream-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data:"
                                    + " {\"id\":\"stream-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-4\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n"
                                    + "data: [DONE]\n\n";
                        exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        exchange.getResponseBody().write(last.getBytes(StandardCharsets.UTF_8));
                        exchange.close();
                        return;
                    }
                    Object message =
                            "TOOLS".equals(behavior)
                                    ? Map.of(
                                            "role",
                                            "assistant",
                                            "content",
                                            "",
                                            "tool_calls",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "call-1",
                                                            "type",
                                                            "function",
                                                            "function",
                                                            Map.of(
                                                                    "name",
                                                                    "weather",
                                                                    "arguments",
                                                                    "{\"city\":\"杭州\"}"))))
                                    : Map.of(
                                            "role",
                                            "assistant",
                                            "content",
                                            "LARGE".equals(behavior)
                                                    ? "中".repeat(750000)
                                                    : "Hello world");
                    var payload =
                            new LinkedHashMap<String, Object>(
                                    Map.of(
                                            "id",
                                            "response-1",
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
                                                            message,
                                                            "finish_reason",
                                                            "TOOLS".equals(behavior)
                                                                    ? "tool_calls"
                                                                    : "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    3,
                                                    "completion_tokens",
                                                    2,
                                                    "total_tokens",
                                                    5)));
                    if ("NO_USAGE".equals(behavior)) payload.remove("usage");
                    byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        var provider =
                store.createProvider(
                        Json.tree(
                                Map.of(
                                        "code",
                                        "test_" + UUID.randomUUID().toString().replace("-", ""),
                                        "name",
                                        "受控模型端点",
                                        "baseUrl",
                                        "http://127.0.0.1:" + server.getAddress().getPort(),
                                        "trustLabels",
                                        List.of("LOCAL"))),
                        admin);
        long id = ((Number) provider.get("id")).longValue();
        assertThat(service.testProvider(id, admin).get("ok")).isEqualTo(true);
        requests.set(0);
        var entry =
                store.createEntry(
                        Json.tree(
                                Map.of(
                                        "providerId",
                                        id,
                                        "modelName",
                                        "gpt-4",
                                        "contextWindow",
                                        8192,
                                        "maxOutputTokens",
                                        128,
                                        "capabilities",
                                        List.of("chat", "tools"))),
                        admin);
        modelId = ((Number) entry.get("id")).longValue();
        var fixtureRedis =
                new org.springframework.data.redis.core.StringRedisTemplate(redisConnection);
        String userKey =
                "agent:model:{"
                        + Digest.sha256(actor.tenantId())
                        + "}:user-daily:"
                        + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                        + ":"
                        + Digest.sha256(actor.principalId());
        userDailyBefore =
                Long.parseLong(Objects.toString(fixtureRedis.opsForValue().get(userKey), "0"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    com.fasterxml.jackson.databind.JsonNode request() {
        return Json.tree(
                Map.of(
                        "modelEntryId",
                        modelId,
                        "invocationRef",
                        "test-" + UUID.randomUUID(),
                        "messages",
                        List.of(Map.of("role", "user", "content", "Hello")),
                        "toolDefinitions",
                        List.of(),
                        "params",
                        Map.of("maxOutputTokens", 32),
                        "requiredLabels",
                        List.of("LOCAL")));
    }

    @Test
    void invalidTimeoutNeverSpendsModelQuotaOrSendsHttp() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("timeout-" + UUID.randomUUID()));
        req.set(
                "params",
                Json.tree(Map.of("maxOutputTokens", 32, "timeout", Map.of("modelMs", 1))));
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
    }

    @Test
    void missingCredentialNeverSpendsModelQuotaOrSendsHttp() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("credential-" + UUID.randomUUID()));
        long provider = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        db.update(
                "update model.provider set secret_ref='vault:00000000-0000-0000-0000-000000000000'"
                        + " where tenant_id=? and id=?",
                actor.tenantId(),
                provider);
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
    }

    @Test
    void rejectedActualWireRefundsBothDailyCountersAndProviderTokensAndRpm() {
        var req = wireRejectedRequest();
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
        var row = noSend(req);
        assertThat(row.get("status")).isEqualTo("NO_SEND");
        assertThat(row.get("usage_reconciliation")).isEqualTo("RECONCILED");
        service.reconcilePendingUsage();
        assertNoQuotaSpent(req);
    }

    @Test
    void streamingWireRejectionAlsoPersistsZeroSendRefund() {
        var req = wireRejectedRequest();
        assertThatThrownBy(() -> service.call(req, actor, text -> {}))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
        assertThat(noSend(req).get("usage_reconciliation")).isEqualTo("RECONCILED");
    }

    @Test
    void zeroSendRefundSurvivesRedisFailureWithDurablePostgresReceipt() {
        var req = wireRejectedRequest();
        var failRefund = new AtomicBoolean(true);
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(redisConnection);
        var failingQuota =
                new ModelQuota(redis) {
                    @Override
                    public String reconcile(Map<String, Object> receipt, int actual) {
                        if (actual == 0 && failRefund.get())
                            throw new IllegalStateException("injected Redis settlement failure");
                        return super.reconcile(receipt, actual);
                    }
                };
        var failed = new ModelCalls(store, failingQuota);
        assertThatThrownBy(() -> failed.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertThat(
                        db.queryForObject(
                                "select to_regclass('model.quota_reservation') is not null",
                                Boolean.class))
                .isTrue();
        var row = noSend(req);
        assertThat(row.get("status")).isEqualTo("NO_SEND");
        assertThat(row.get("usage_reconciliation")).isEqualTo("PENDING");
        assertThat(Json.read(row.get("quota_receipt").toString()).path("id").asText()).isNotBlank();
        failRefund.set(false);
        new ModelCalls(new ModelStore(db), new ModelQuota(redis)).reconcilePendingUsage();
        assertNoQuotaSpent(req);
        assertThat(noSend(req).get("usage_reconciliation")).isEqualTo("RECONCILED");
        service.reconcilePendingUsage();
        assertNoQuotaSpent(req);
    }

    @Test
    void failedSendingLogStillHasDurableZeroSendRefund() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("log-failure-" + UUID.randomUUID()));
        var failingStore =
                new ModelStore(db) {
                    @Override
                    public long sending(
                            Actor owner,
                            com.fasterxml.jackson.databind.JsonNode request,
                            Map<String, Object> entry,
                            String url,
                            String body,
                            int tokens) {
                        throw new org.springframework.dao.DataAccessResourceFailureException(
                                "injected sending log failure");
                    }
                };
        assertThatThrownBy(() -> new ModelCalls(failingStore, quota).call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
        assertThat(noSend(req).get("usage_reconciliation")).isEqualTo("RECONCILED");
    }

    @Test
    void failedReservationJournalPreventsRedisReservationAndHttp() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("intent-failure-" + UUID.randomUUID()));
        var failingStore =
                new ModelStore(db) {
                    @Override
                    public void reserveQuota(
                            Actor owner,
                            com.fasterxml.jackson.databind.JsonNode request,
                            Map<String, Object> entry,
                            Map<String, Object> receipt) {
                        throw new org.springframework.dao.DataAccessResourceFailureException(
                                "injected journal failure");
                    }
                };
        assertThatThrownBy(() -> new ModelCalls(failingStore, quota).call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertNoQuotaSpent(req);
    }

    @Test
    void failedNoSendPostgresUpdateRecoversFromPreparedIntentAfterLease() {
        var req = wireRejectedRequest();
        var failingStore =
                new ModelStore(db) {
                    @Override
                    public boolean quotaNoSend(String tenant, String reservation, String error) {
                        throw new org.springframework.dao.DataAccessResourceFailureException(
                                "injected zero-send PG failure");
                    }
                };
        assertThatThrownBy(() -> new ModelCalls(failingStore, quota).call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
        assertThat(noSend(req).get("status")).isEqualTo("RESERVING");
        db.update(
                "update model.quota_reservation set lease_until=now()-interval '1 second' where"
                        + " tenant_id=? and invocation_ref=?",
                actor.tenantId(),
                req.path("invocationRef").asText());
        service.reconcilePendingUsage();
        assertNoQuotaSpent(req);
        assertThat(noSend(req).get("status")).isEqualTo("NO_SEND");
        assertThat(noSend(req).get("usage_reconciliation")).isEqualTo("RECONCILED");
    }

    @Test
    void alreadySentWithMissingUsageKeepsReservation() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("sent-unknown-" + UUID.randomUUID()));
        behavior = "NO_USAGE";
        service.call(req, actor, null);
        assertThat(requests.get()).isEqualTo(1);
        String prefix = "agent:model:{" + Digest.sha256(actor.tenantId()) + "}:";
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(redisConnection);
        String daily =
                prefix
                        + "daily:"
                        + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                        + ":"
                        + Digest.sha256(req.path("appCode").asText());
        String before = redis.opsForValue().get(daily);
        assertThat(Integer.parseInt(before)).isPositive();
        service.reconcilePendingUsage();
        assertThat(redis.opsForValue().get(daily)).isEqualTo(before);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode wireRejectedRequest() {
        rejectWire.set(true);
        store.updateEntry(
                modelId,
                Json.tree(
                        Map.of(
                                "tokenizerUrl",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/tokenize")),
                admin);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("appCode", Json.tree("wire-" + UUID.randomUUID()));
        return req;
    }

    private Map<String, Object> noSend(com.fasterxml.jackson.databind.JsonNode req) {
        return db.queryForMap(
                "select status,usage_reconciliation,quota_receipt::text from"
                        + " model.quota_reservation where tenant_id=? and invocation_ref=?",
                actor.tenantId(),
                req.path("invocationRef").asText());
    }

    private void assertNoQuotaSpent(com.fasterxml.jackson.databind.JsonNode req) {
        String prefix = "agent:model:{" + Digest.sha256(actor.tenantId()) + "}:";
        var redis = new org.springframework.data.redis.core.StringRedisTemplate(redisConnection);
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String daily = prefix + "daily:" + day + ":" + Digest.sha256(req.path("appCode").asText());
        assertThat(redis.opsForValue().get(daily)).isIn((String) null, "0");
        String user = prefix + "user-daily:" + day + ":" + Digest.sha256(actor.principalId());
        assertThat(Long.parseLong(Objects.toString(redis.opsForValue().get(user), "0")))
                .isEqualTo(userDailyBefore);
        long provider = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        var state = redis.opsForHash().entries(prefix + "provider:" + provider);
        if (!state.isEmpty()) {
            assertThat(Double.parseDouble(state.get("tokens").toString())).isEqualTo(60000);
            assertThat(Double.parseDouble(state.get("requests").toString())).isEqualTo(60);
        }
    }

    @Test
    void vendorErrorsAreNormalizedWithoutUpstreamSecrets() {
        for (int status : List.of(401, 429, 503, 504)) {
            behavior = "HTTP" + status;
            assertThatThrownBy(() -> service.call(request(), actor, null))
                    .isInstanceOfSatisfying(
                            PlatformException.class,
                            e -> {
                                assertThat(e.status()).isEqualTo(status);
                                assertThat(e.code()).isEqualTo("SYS-MODEL-" + status);
                                assertThat(e.getMessage()).doesNotContain("DO-NOT-LEAK-UPSTREAM");
                            });
        }
    }

    @Test
    void listingAloneCannotEnableProviderWhenShortChatFails() {
        var entry = store.entry(modelId, actor, true);
        long id = ((Number) entry.get("providerId")).longValue();
        behavior = "FAIL";
        assertThat(service.testProvider(id, admin).get("ok")).isEqualTo(false);
        assertThat(store.provider(id, actor).get("status")).isEqualTo("DISABLED");
    }

    @Test
    void credentialsRequireAdminAndRotationChangesFrozenRuntimeHeader() {
        var p = store.entry(modelId, actor, true);
        long id = ((Number) p.get("providerId")).longValue();
        assertThatThrownBy(
                        () ->
                                store.rotate(
                                        id, Json.tree(Map.of("secret", "secret-one-1234")), actor))
                .isInstanceOf(PlatformException.class);
        var admin = new Actor(actor.tenantId(), actor.principalId(), Set.of("ADMIN"), Map.of());
        var frozen = store.freeze(modelId, actor);
        store.rotate(id, Json.tree(Map.of("secret", "secret-one-1234")), admin);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("modelBindingRef", Json.tree(frozen));
        assertThat(store.resolve(req, actor).get("secretRef"))
                .isEqualTo(store.provider(id, actor).get("secret_ref"));
        service.call(req, actor, null);
        assertThat(authorization.get()).isEqualTo("Bearer secret-one-1234");
        var before = store.resolve(req, actor).get("secretRef");
        store.rotate(id, Json.tree(Map.of("secret", "secret-two-5678")), admin);
        assertThat(store.resolve(req, actor).get("secretRef")).isNotEqualTo(before);
        req.put("invocationRef", "test-" + UUID.randomUUID());
        service.call(req, actor, null);
        assertThat(authorization.get()).isEqualTo("Bearer secret-two-5678");
        assertThat(Json.write(frozen)).doesNotContain("secretRef", "1234", "5678");
    }

    @Test
    void frozenBindingSurvivesMetadataEditAndDisabledProvider() {
        var frozen = store.freeze(modelId, actor);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("modelBindingRef", Json.tree(frozen));
        var entry = store.entry(modelId, actor, true);
        long provider = ((Number) entry.get("providerId")).longValue();
        db.update(
                "update model.entry set model_name='changed-model',context_window=500 where id=?",
                modelId);
        db.update(
                "update model.provider set status='DISABLED',base_url='http://127.0.0.1:1' where"
                        + " id=?",
                provider);
        var result = service.call(req, actor, null);
        assertThat(result.get("text")).isEqualTo("Hello world");
        assertThat(Json.tree(result).path("actualModel").path("modelName").asText())
                .isEqualTo("gpt-4");
        assertThatThrownBy(() -> store.freeze(modelId, actor))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void arbitraryFrozenBodyCannotBypassDisabledProvider() {
        var entry = store.entry(modelId, actor, true);
        db.update(
                "update model.provider set status='DISABLED' where id=?", entry.get("providerId"));
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.put("allowDisabled", true);
        req.set("resolvedModel", Json.tree(entry));
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    void frozenBindingCannotCrossTenantOrDigest() {
        var frozen = store.freeze(modelId, actor);
        var ref = Json.tree(frozen);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("modelBindingRef", ref);
        assertThatThrownBy(
                        () ->
                                service.call(
                                        req,
                                        new Actor(
                                                "other-tenant",
                                                "other",
                                                Set.of("EDITOR"),
                                                Map.of()),
                                        null))
                .isInstanceOf(PlatformException.class);
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) ref;
        changed.put("sha256", "wrong");
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isZero();
    }

    @Test
    void freezesActualWireBeforeSingleCall() {
        var response = service.call(request(), actor, null);
        assertThat(response.get("text")).isEqualTo("Hello world");
        assertThat(requests.get()).isEqualTo(1);
        assertThat(sawFrozen).isTrue();
    }

    @Test
    void missingVendorUsageRemainsUnavailableRatherThanFabricatedZero() {
        behavior = "NO_USAGE";
        var req = request();
        var result = service.call(req, actor, null);
        assertThat((Map<?, ?>) result.get("usage")).isEmpty();
        var row =
                db.queryForMap(
                        "select total_tokens,usage_reconciliation from model.call_log where"
                                + " tenant_id=? and invocation_ref=?",
                        actor.tenantId(),
                        req.path("invocationRef").asText());
        assertThat(row.get("total_tokens")).isNull();
        assertThat(row.get("usage_reconciliation")).isEqualTo("UNAVAILABLE");
    }

    @Test
    void dedicatedTokenizerUsesRealCountAndIsClearlyDistinguishedFromJtokkit() {
        store.updateEntry(
                modelId,
                Json.tree(
                        Map.of(
                                "modelName",
                                "custom-model",
                                "tokenizerUrl",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/tokenize")),
                admin);
        var measured = service.measure(request(), actor);
        assertThat(measured.get("inputTokens")).isEqualTo(123);
        assertThat(measured.get("exact")).isEqualTo(true);
        assertThat(requests.get()).isZero();
    }

    @Test
    void threeMeteringTiersAreRecordedWithoutClaimingJtokkitIsExact() {
        var req = request();
        service.call(req, actor, null);
        var log =
                db.queryForMap(
                        "select"
                            + " estimated_tokens,admission_tokens,metering_snapshot::text,model_snapshot::text,usage_reconciliation"
                            + " from model.call_log where tenant_id=? and invocation_ref=?",
                        actor.tenantId(),
                        req.path("invocationRef").asText());
        assertThat(((Number) log.get("estimated_tokens")).intValue()).isPositive();
        assertThat(((Number) log.get("admission_tokens")).intValue()).isPositive();
        assertThat(Json.read(log.get("metering_snapshot").toString()).path("exact").asBoolean(true))
                .isFalse();
        assertThat(log.get("model_snapshot").toString()).doesNotContain("secretRef");
        assertThat(
                        db.queryForObject(
                                "select count(*) from model.metering_calibration where tenant_id=?"
                                        + " and model_entry_id=?",
                                Integer.class,
                                actor.tenantId(),
                                modelId))
                .isEqualTo(1);
        var second = request();
        service.call(second, actor, null);
        assertThat(
                        db.queryForObject(
                                "select (metering_snapshot->>'displayFactor')::double precision"
                                    + " from model.call_log where tenant_id=? and invocation_ref=?",
                                Double.class,
                                actor.tenantId(),
                                second.path("invocationRef").asText()))
                .isLessThan(1.0);
        var withTools = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        withTools.set(
                "toolDefinitions",
                Json.tree(
                        List.of(
                                Map.of(
                                        "type",
                                        "function",
                                        "function",
                                        Map.of(
                                                "name",
                                                "weather",
                                                "description",
                                                "天气",
                                                "parameters",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of()))))));
        service.call(withTools, actor, null);
        assertThat(
                        db.queryForObject(
                                "select count(*) from model.metering_calibration where tenant_id=?"
                                        + " and model_entry_id=?",
                                Integer.class,
                                actor.tenantId(),
                                modelId))
                .isEqualTo(2);
    }

    @Test
    void responseSchemaCannotBypassAdmissionOrProviderQuota() {
        store.updateEntry(
                modelId,
                Json.tree(Map.of("capabilities", List.of("chat", "tools", "json_mode"))),
                admin);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set(
                "params",
                Json.tree(
                        Map.of(
                                "maxOutputTokens",
                                32,
                                "output",
                                Map.of(
                                        "format",
                                        Map.of(
                                                "type",
                                                "json",
                                                "schema",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "description",
                                                        "schema ".repeat(20000)))))));
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> assertThat(e.code()).isEqualTo("SYS-MODEL-413"));
        assertThat(requests.get()).isZero();
    }

    @Test
    void coldInstanceCannotIgnorePersistedDegradedProvider() {
        var frozen = store.freeze(modelId, actor);
        long id = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        store.circuitState(id, actor, "OPEN");
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("modelBindingRef", Json.tree(frozen));
        assertThatThrownBy(() -> new ModelCalls(store, quota).call(req, actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> assertThat(e.code()).isEqualTo("SYS-MODEL-503"));
        assertThat(requests.get()).isZero();
    }

    @Test
    void dedicatedTokenizerReceivesAndCountsOutputSchema() {
        store.updateEntry(
                modelId,
                Json.tree(
                        Map.of(
                                "modelName",
                                "custom-json",
                                "tokenizerUrl",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/tokenize",
                                "capabilities",
                                List.of("chat", "json_mode"))),
                admin);
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set(
                "params",
                Json.tree(
                        Map.of(
                                "output",
                                Map.of(
                                        "format",
                                        Map.of(
                                                "type",
                                                "json",
                                                "schema",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "description",
                                                        "large schema"))))));
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> assertThat(e.code()).isEqualTo("SYS-MODEL-413"));
        assertThat(
                        tokenizerMaterial
                                .get()
                                .path("params")
                                .path("output")
                                .path("format")
                                .path("schema")
                                .has("description"))
                .isTrue();
        assertThat(requests.get()).isZero();
    }

    @Test
    void providerMutationAndCredentialRotationHoldSamePostgresRowLock() throws Exception {
        long id = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var instrumented =
                new JdbcTemplate(db.getDataSource()) {
                    @Override
                    public List<Map<String, Object>> queryForList(String sql, Object... args) {
                        var rows = super.queryForList(sql, args);
                        if (Thread.currentThread().getName().equals("model-rotate-lock")
                                && sql.contains("for update")) {
                            locked.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return rows;
                    }
                };
        var guarded = new ModelStore(instrumented);
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var rotation =
                    pool.submit(
                            () -> {
                                Thread.currentThread().setName("model-rotate-lock");
                                return guarded.rotate(
                                        id,
                                        Json.tree(Map.of("secret", "controlled-test-secret-3141")),
                                        admin);
                            });
            assertThat(locked.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var edit =
                    pool.submit(
                            () ->
                                    guarded.updateProvider(
                                            id,
                                            Json.tree(Map.of("name", "after rotation")),
                                            admin));
            Thread.sleep(150);
            assertThat(edit.isDone()).isFalse();
            release.countDown();
            rotation.get(3, java.util.concurrent.TimeUnit.SECONDS);
            edit.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(store.provider(id, actor).get("secret_ref")).isNotNull();
            assertThat(store.provider(id, actor).get("name")).isEqualTo("after rotation");
            assertThatThrownBy(
                            () ->
                                    guarded.updateProvider(
                                            id,
                                            Json.tree(Map.of("baseUrl", "http://127.0.0.1:1")),
                                            actor))
                    .isInstanceOf(PlatformException.class);
            assertThat(requests.get()).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void editorCannotAdministerProviderTrustOrModelMetadata() {
        long id = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        assertThatThrownBy(
                        () ->
                                store.createProvider(
                                        Json.tree(
                                                Map.of(
                                                        "code",
                                                        "forbidden",
                                                        "name",
                                                        "forbidden",
                                                        "baseUrl",
                                                        "http://127.0.0.1:1",
                                                        "trustLabels",
                                                        List.of("LOCAL", "SECRET"))),
                                        actor))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(
                        () ->
                                store.updateProvider(
                                        id,
                                        Json.tree(
                                                Map.of("trustLabels", List.of("LOCAL", "SECRET"))),
                                        actor))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(
                        () ->
                                store.updateProvider(
                                        id,
                                        Json.tree(
                                                Map.of(
                                                        "secretRef",
                                                        "vault:00000000-0000-0000-0000-000000000000")),
                                        actor))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(
                        () ->
                                store.updateEntry(
                                        modelId, Json.tree(Map.of("contextWindow", 99999)), actor))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void probeForOldConfigurationCannotEnableEditedOrDisabledProvider() throws Exception {
        long id = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        probeEntered.set(new java.util.concurrent.CountDownLatch(1));
        probeRelease.set(new java.util.concurrent.CountDownLatch(1));
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> service.testProvider(id, admin));
            assertThat(probeEntered.get().await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            store.updateProvider(id, Json.tree(Map.of("name", "changed while probing")), admin);
            store.disableProvider(id, admin);
            probeRelease.get().countDown();
            assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS).get("ok"))
                    .isEqualTo(false);
            assertThat(store.provider(id, actor).get("status")).isEqualTo("DISABLED");
        } finally {
            probeRelease.get().countDown();
        }
    }

    @Test
    void repeatedProviderFailureOpensCircuitBeforeNextNetworkRequest() throws Exception {
        var frozen = store.freeze(modelId, actor);
        behavior = "FAIL";
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(() -> service.call(request(), actor, null))
                    .isInstanceOf(PlatformException.class);
        int sent = requests.get();
        assertThatThrownBy(() -> service.call(request(), actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> assertThat(e.code()).isEqualTo("SYS-MODEL-503"));
        assertThat(requests.get()).isEqualTo(sent);
        long id = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        assertThat(store.provider(id, actor).get("status")).isEqualTo("DEGRADED");
        service = new ModelCalls(store, quota);
        var coldRequest = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        coldRequest.set("modelBindingRef", Json.tree(frozen));
        assertThatThrownBy(() -> service.call(coldRequest, actor, null))
                .isInstanceOf(PlatformException.class);
        Thread.sleep(30500);
        behavior = "TEXT";
        service.probeDegraded();
        var resumed = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        resumed.set("modelBindingRef", Json.tree(frozen));
        assertThat(service.call(resumed, actor, null).get("text")).isEqualTo("Hello world");
        assertThat(store.provider(id, actor).get("status")).isEqualTo("ENABLED");
    }

    @Test
    void configuredProviderQuotaRejectsSecondCallBeforeModelNetwork() {
        long provider = ((Number) store.entry(modelId, actor, true).get("providerId")).longValue();
        store.configureQuota(
                provider,
                Json.tree(Map.of("rpm", 1, "tpm", 10000)),
                new Actor(actor.tenantId(), actor.principalId(), Set.of("ADMIN"), Map.of()));
        service.call(request(), actor, null);
        assertThatThrownBy(() -> service.call(request(), actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class, e -> assertThat(e.status()).isEqualTo(429));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void streamingAlsoFreezesActualWireAndReturnsRealChunks() {
        List<String> chunks = new ArrayList<>();
        var response = service.call(request(), actor, chunks::add);
        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).isEqualTo("Hello world");
        assertThat(response.get("text")).isEqualTo("Hello world");
        assertThat(sawFrozen).isTrue();
    }

    @Test
    void functionCallsAreReturnedRatherThanExecuted() {
        behavior = "TOOLS";
        var response = service.call(request(), actor, null);
        assertThat(Json.tree(response).path("toolCalls").get(0).path("name").asText())
                .isEqualTo("weather");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void frameworkDoesNotImplicitlyRetryServiceFailure() {
        behavior = "FAIL";
        assertThatThrownBy(() -> service.call(request(), actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void untrustedRouteRejectsBeforeModelNetwork() {
        var req = (com.fasterxml.jackson.databind.node.ObjectNode) request();
        req.set("requiredLabels", Json.tree(List.of("SECRET")));
        assertThatThrownBy(() -> service.call(req, actor, null))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(403);
                            assertThat(e.code()).isEqualTo("SYS-EGRESS-4031");
                        });
        assertThat(requests.get()).isZero();
    }

    @Test
    void oversizedModelResponseIsBoundedBeforeJacksonMaterializesIt() {
        behavior = "LARGE";
        assertThatThrownBy(() -> service.call(request(), actor, null))
                .isInstanceOf(PlatformException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void measurementUsesModelTokenizerWithoutSendingModelRequest() {
        var measured = service.measure(request(), actor);
        assertThat(((Number) measured.get("inputTokens")).intValue()).isPositive();
        assertThat(measured.get("contextWindow")).isEqualTo(8192);
        assertThat(measured.get("exact")).isEqualTo(false);
        assertThat(requests.get()).isZero();
    }
}
