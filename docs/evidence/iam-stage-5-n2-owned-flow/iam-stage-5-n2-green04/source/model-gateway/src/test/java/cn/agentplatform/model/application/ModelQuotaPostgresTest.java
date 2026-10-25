package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.model.infra.ModelStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.*;

/** 专用PG与真实Redis共同验证账务恢复；不启动模型服务或发送模型请求。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ModelQuotaPostgresTest {
    static JdbcTemplate db;
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    Actor actor;
    ModelStore store;
    ModelQuota quota;
    long provider, entry;

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
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @BeforeEach
    void fixture() {
        actor = new Actor("quota-pg-" + UUID.randomUUID(), "alice", Set.of("ADMIN"), Map.of());
        store = new ModelStore(db);
        quota = new ModelQuota(redis);
        provider =
                ((Number)
                                store.createProvider(
                                                Json.tree(
                                                        Map.of(
                                                                "code",
                                                                "quota",
                                                                "name",
                                                                "quota",
                                                                "baseUrl",
                                                                "http://127.0.0.1:1",
                                                                "trustLabels",
                                                                List.of("LOCAL"))),
                                                actor)
                                        .get("id"))
                        .longValue();
        entry =
                ((Number)
                                store.createEntry(
                                                Json.tree(
                                                        Map.of(
                                                                "providerId",
                                                                provider,
                                                                "modelName",
                                                                "quota",
                                                                "displayName",
                                                                "quota",
                                                                "contextWindow",
                                                                1000,
                                                                "maxOutputTokens",
                                                                10,
                                                                "capabilities",
                                                                List.of("chat"))),
                                                actor)
                                        .get("id"))
                        .longValue();
    }

    @Test
    void providerApplicationAndUserLimitsResolveIndependentlyAcrossApplications() {
        store.configureQuota(provider, Json.tree(Map.of("tpm", 9000, "rpm", 9)), actor);
        store.configureQuota(
                provider, Json.tree(Map.of("appCode", "one", "dailyTokens", 100)), actor);
        store.configureQuota(
                provider,
                Json.tree(Map.of("appCode", "one", "userPrincipal", "alice", "dailyTokens", 50)),
                actor);
        var one = store.quota(provider, "one", actor);
        var two = store.quota(provider, "two", actor);
        assertThat(one.get("daily_tokens")).isEqualTo(100);
        assertThat(one.get("user_daily_tokens")).isEqualTo(50);
        assertThat(two.get("user_daily_tokens")).isEqualTo(50);
        assertThat(one.get("tpm")).isEqualTo(9000);
        assertThat(one.get("rpm")).isEqualTo(9);
        var bob = new Actor(actor.tenantId(), "bob", Set.of("SERVICE"), Map.of());
        assertThat(store.quota(provider, "one", bob).get("user_daily_tokens")).isEqualTo(1000000);
    }

    @Test
    void redisAppliedButPostgresUnconfirmedExpiresWithoutDoubleRefundOrNetwork() throws Exception {
        try (var permit =
                quota.acquire(actor, provider, "one", 40, 10000, 100, 100, 3, 8, false, 0)) {
            var receipt = new LinkedHashMap<>(permit.receipt());
            // 缩短真实时钟窗口以复现48h标记到期，无需更改Redis服务器时钟。
            receipt.put("reconcileUntil", System.currentTimeMillis() + 500);
            long id = pending(receipt);
            assertThat(quota.reconcile(receipt, 5)).isEqualTo("RECONCILED"); // Redis成功，故意尚未执行PG确认。
            var keys = (List<String>) receipt.get("keys");
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("5");
            assertThat(
                            db.queryForObject(
                                    "select usage_reconciliation from model.call_log where id=?",
                                    String.class,
                                    id))
                    .isEqualTo("PENDING");
            String marker = keys.getFirst() + ":reconciled:" + receipt.get("id");
            redis.expire(marker, Duration.ofMillis(30));
            Thread.sleep(550);
            assertThat(redis.hasKey(marker)).isFalse();
            String daily = redis.opsForValue().get(keys.get(1));
            Object tokens = redis.opsForHash().get(keys.getFirst(), "tokens");
            new ModelCalls(store, quota).reconcilePendingUsage();
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo(daily);
            assertThat(redis.opsForHash().get(keys.getFirst(), "tokens")).isEqualTo(tokens);
            assertThat(
                            db.queryForObject(
                                    "select usage_reconciliation from model.call_log where id=?",
                                    String.class,
                                    id))
                    .isEqualTo("EXPIRED");
            assertThat(store.pendingUsage())
                    .noneMatch(row -> ((Number) row.get("id")).longValue() == id);
            assertThat(store.statistics(actor).get("usage_expired")).isEqualTo(1L);
            new ModelCalls(store, quota).reconcilePendingUsage();
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo(daily);
        }
    }

    @Test
    void durableTerminalLedgerCannotBeOverwrittenByAStaleWorker() {
        long id = pending(Map.of());
        store.reconciled(id, "PARTIAL");
        store.reconciled(id, "RECONCILED");
        assertThat(
                        db.queryForObject(
                                "select usage_reconciliation from model.call_log where id=?",
                                String.class,
                                id))
                .isEqualTo("PARTIAL");
        assertThat(store.statistics(actor).get("usage_partial")).isEqualTo(1L);
    }

    @Test
    void expiredUnsentLeaseFencesLateSenderAndRefundsOnlyOnce() {
        var req =
                Json.tree(Map.of("invocationRef", UUID.randomUUID().toString(), "appCode", "one"));
        var entryRow = Map.<String, Object>of("id", entry, "providerId", provider);
        var prepared = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        try (var permit =
                quota.acquire(
                        actor,
                        provider,
                        "one",
                        40,
                        10000,
                        100,
                        100,
                        100,
                        3,
                        8,
                        false,
                        0,
                        receipt -> {
                            store.reserveQuota(actor, req, entryRow, receipt);
                            prepared.set(receipt);
                        })) {
            db.update(
                    "update model.quota_reservation set lease_until=now()-interval '1 second' where"
                            + " tenant_id=? and reservation_id=?",
                    actor.tenantId(),
                    prepared.get().get("id"));
            new ModelCalls(store, quota).reconcilePendingUsage();
            assertThatThrownBy(() -> store.quotaSending(actor, prepared.get()))
                    .isInstanceOf(PlatformException.class);
            var keys = (List<String>) prepared.get().get("keys");
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("0");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("0");
            assertThat(redis.opsForHash().get(keys.getFirst(), "requests")).isEqualTo("100");
            new ModelCalls(store, quota).reconcilePendingUsage();
            assertThat(redis.opsForHash().get(keys.getFirst(), "requests")).isEqualTo("100");
        }
    }

    @Test
    void missingReservationEvidenceCannotBeDeclaredFinalBeforeAdmissionDeadline() {
        String prefix =
                "agent:model:{" + Digest.sha256(actor.tenantId()) + "}:provider:" + provider;
        String id = UUID.randomUUID().toString();
        var receipt =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "keys",
                                List.of(prefix, prefix + ":day", prefix + ":user-day"),
                                "id",
                                id,
                                "reserved",
                                40,
                                "tpm",
                                10000,
                                "rpm",
                                100,
                                "reconcileUntil",
                                System.currentTimeMillis() + 86400000L,
                                "providerUntil",
                                0,
                                "dailyUntil",
                                System.currentTimeMillis() + 86400000L,
                                "generation",
                                "",
                                "prepared",
                                true));
        receipt.put("admissionUntil", System.currentTimeMillis() + 900000L);
        store.reserveQuota(
                actor,
                Json.tree(Map.of("invocationRef", id)),
                Map.of("id", entry, "providerId", provider),
                receipt);
        db.update(
                "update model.quota_reservation set lease_until=now()-interval '1 second' where"
                        + " tenant_id=? and reservation_id=?",
                actor.tenantId(),
                id);
        new ModelCalls(store, quota).reconcilePendingUsage();
        assertThat(
                        db.queryForObject(
                                "select usage_reconciliation from model.quota_reservation where"
                                        + " tenant_id=? and reservation_id=?",
                                String.class,
                                actor.tenantId(),
                                id))
                .isEqualTo("PENDING");
    }

    private long pending(Map<String, Object> receipt) {
        return db.queryForObject(
                "insert into model.call_log(tenant_id,principal_id,invocation_ref,model_entry_id,"
                    + "provider_id,actual_endpoint,request_snapshot,estimated_tokens,status,"
                    + "total_tokens,quota_receipt,usage_reconciliation)"
                    + " values(?,?,?,?,?,'http://127.0.0.1:1','{}',40,'SUCCEEDED',5,?::jsonb,'PENDING')"
                    + " returning id",
                Long.class,
                actor.tenantId(),
                actor.principalId(),
                UUID.randomUUID().toString(),
                entry,
                provider,
                Json.write(receipt));
    }
}
