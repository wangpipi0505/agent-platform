package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

/** 真实Redis Lua；没有Redis时明确跳过外部验收，不以本地Map替代。 */
@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_PASSWORD", matches = ".+")
class ModelQuotaRedisTest {
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    ModelQuota quota;
    Actor actor;

    @BeforeAll
    static void redis() {
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
        quota = new ModelQuota(redis);
        actor = new Actor("quota-" + UUID.randomUUID(), "tester", Set.of("SERVICE"), Map.of());
    }

    @Test
    void atomicProviderRpmRejectsAcrossIndependentClients() {
        var permit = quota.acquire(actor, 17, "app", 1, 10000, 1, 1000000, 3, 8, false, 0);
        permit.close();
        assertThatThrownBy(
                        () ->
                                new ModelQuota(redis)
                                        .acquire(
                                                actor, 17, "app", 1, 10000, 1, 1000000, 3, 8, false,
                                                0))
                .isInstanceOfSatisfying(
                        PlatformException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(429);
                            assertThat(e.getMessage()).contains("retryAfter");
                        });
    }

    @Test
    void applicationDailyQuotaAndUserConcurrencyAreShared() {
        var first = quota.acquire(actor, 18, "app", 40, 10000, 100, 50, 1, 8, false, 0);
        assertThatThrownBy(
                        () -> quota.acquire(actor, 19, "other", 1, 10000, 100, 50, 1, 8, false, 0))
                .isInstanceOf(PlatformException.class);
        first.close();
        assertThatThrownBy(
                        () -> quota.acquire(actor, 18, "app", 20, 10000, 100, 50, 1, 8, false, 0))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void sameUserCannotSpendDailyAllowanceAgainInAnotherApplication() {
        try (var first = quota.acquire(actor, 18, "first", 40, 10000, 100, 50, 3, 8, false, 0)) {
            assertThatThrownBy(
                            () ->
                                    quota.acquire(
                                            actor, 19, "second", 20, 10000, 100, 50, 3, 8, false,
                                            0))
                    .isInstanceOf(PlatformException.class);
        }
    }

    @Test
    void expiredReceiptCannotRefundAgainAfterItsRedisMarkerExpires() throws Exception {
        try (var permit = quota.acquire(actor, 18, "app", 40, 10000, 100, 50, 3, 8, false, 0)) {
            var receipt = new LinkedHashMap<>(permit.receipt());
            receipt.put("reconcileUntil", System.currentTimeMillis() + 300);
            assertThat(quota.reconcile(receipt, 5)).isEqualTo("RECONCILED");
            var keys = (List<String>) receipt.get("keys");
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("5");
            String marker = keys.getFirst() + ":reconciled:" + receipt.get("id");
            redis.expire(marker, java.time.Duration.ofMillis(30));
            Thread.sleep(350);
            assertThat(redis.hasKey(marker)).isFalse();
            String dailyBefore = redis.opsForValue().get(keys.get(1));
            String providerBefore = redis.opsForHash().get(keys.getFirst(), "tokens").toString();
            quota.reconcile(receipt, 5);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo(dailyBefore);
            assertThat(redis.opsForHash().get(keys.getFirst(), "tokens").toString())
                    .isEqualTo(providerBefore);
        }
    }

    @Test
    void reconciliationNeverRecreatesAnExpiredDailyOrProviderKey() throws Exception {
        try (var permit = quota.acquire(actor, 18, "app", 40, 10000, 100, 50, 3, 8, false, 0)) {
            var receipt = permit.receipt();
            var keys = (List<String>) receipt.get("keys");
            for (String key : keys) redis.expire(key, java.time.Duration.ofMillis(20));
            Thread.sleep(60);
            for (String key : keys) assertThat(redis.hasKey(key)).isFalse();
            quota.reconcile(receipt, 5);
            for (String key : keys) assertThat(redis.hasKey(key)).isFalse();
        }
    }

    @Test
    void sameApplicationHasIndependentUserAllowancesAndOneSharedApplicationAllowance() {
        var bob = new Actor(actor.tenantId(), "bob", Set.of("SERVICE"), Map.of());
        try (var alicePermit =
                        quota.acquire(actor, 18, "app", 60, 10000, 100, 150, 100, 3, 8, false, 0);
                var bobPermit =
                        quota.acquire(bob, 18, "app", 60, 10000, 100, 150, 100, 3, 8, false, 0)) {
            var aliceKeys = (List<String>) alicePermit.receipt().get("keys");
            var bobKeys = (List<String>) bobPermit.receipt().get("keys");
            assertThat(aliceKeys.get(1)).isEqualTo(bobKeys.get(1));
            assertThat(aliceKeys.get(2)).isNotEqualTo(bobKeys.get(2));
            assertThatThrownBy(
                            () ->
                                    quota.acquire(
                                            actor, 18, "app", 50, 10000, 100, 150, 100, 3, 8, false,
                                            0))
                    .isInstanceOf(PlatformException.class);
            assertThat(redis.opsForValue().get(aliceKeys.get(1))).isEqualTo("120");
            assertThat(redis.opsForValue().get(aliceKeys.get(2))).isEqualTo("60");
            try (var more =
                    quota.acquire(bob, 18, "app", 20, 10000, 100, 150, 100, 3, 8, false, 0)) {
                assertThatThrownBy(
                                () ->
                                        quota.acquire(
                                                bob, 18, "app", 20, 10000, 100, 150, 100, 3, 8,
                                                false, 0))
                        .isInstanceOf(PlatformException.class);
                assertThat(redis.opsForValue().get(bobKeys.get(1))).isEqualTo("140");
                assertThat(redis.opsForValue().get(bobKeys.get(2))).isEqualTo("80");
            }
        }
    }

    @Test
    void reconciliationRefundsAndChargesBothDailyDimensionsExactlyOnce() {
        try (var permit =
                quota.acquire(actor, 18, "app", 40, 10000, 100, 100, 50, 3, 8, false, 0)) {
            var keys = (List<String>) permit.receipt().get("keys");
            assertThat(permit.reconcile(5)).isEqualTo("RECONCILED");
            quota.reconcile(permit.receipt(), 5);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("5");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("5");
        }
        try (var permit =
                quota.acquire(actor, 18, "app", 10, 10000, 100, 100, 50, 3, 8, false, 0)) {
            var keys = (List<String>) permit.receipt().get("keys");
            assertThat(permit.reconcile(30)).isEqualTo("RECONCILED");
            quota.reconcile(permit.receipt(), 30);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("35");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("35");
        }
    }

    @Test
    void lateProviderReconciliationOnlyAdjustsDailyCountersAndReportsPartial() {
        try (var permit =
                quota.acquire(actor, 18, "app", 40, 10000, 100, 100, 50, 3, 8, false, 0)) {
            var receipt = new LinkedHashMap<>(permit.receipt());
            receipt.put("providerUntil", System.currentTimeMillis() - 1);
            var keys = (List<String>) receipt.get("keys");
            Object before = redis.opsForHash().get(keys.getFirst(), "tokens");
            assertThat(quota.reconcile(receipt, 5)).isEqualTo("PARTIAL");
            assertThat(redis.opsForHash().get(keys.getFirst(), "tokens")).isEqualTo(before);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("5");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("5");
        }
    }

    @Test
    void receiptCannotAdjustAnotherProviderGenerationOrAPreviousDay() {
        try (var permit =
                quota.acquire(actor, 18, "app", 40, 10000, 100, 100, 50, 3, 8, false, 0)) {
            var receipt = new LinkedHashMap<>(permit.receipt());
            receipt.put("dailyUntil", System.currentTimeMillis() - 1);
            var keys = (List<String>) receipt.get("keys");
            redis.opsForHash().put(keys.getFirst(), "generation", "new-provider-window");
            Object before = redis.opsForHash().get(keys.getFirst(), "tokens");
            assertThat(quota.reconcile(receipt, 5)).isEqualTo("EXPIRED");
            assertThat(redis.opsForHash().get(keys.getFirst(), "tokens")).isEqualTo(before);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("40");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("40");
        }
    }

    @Test
    void legacyReceiptHasNoSafeRecoveryWindowAndNeverMutatesRedis() {
        try (var permit =
                quota.acquire(actor, 18, "app", 40, 10000, 100, 100, 50, 3, 8, false, 0)) {
            var receipt = new LinkedHashMap<>(permit.receipt());
            receipt.remove("reconcileUntil");
            var keys = (List<String>) receipt.get("keys");
            Object before = redis.opsForHash().get(keys.getFirst(), "tokens");
            assertThat(quota.reconcile(receipt, 5)).isEqualTo("EXPIRED");
            assertThat(redis.opsForHash().get(keys.getFirst(), "tokens")).isEqualTo(before);
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("40");
            assertThat(redis.opsForValue().get(keys.get(2))).isEqualTo("40");
        }
    }

    @Test
    void usageReconcilesReservationWithoutPretendingMissingUsageIsZero() {
        var first = quota.acquire(actor, 18, "app", 40, 10000, 100, 50, 3, 8, false, 0);
        first.reconcile(5);
        quota.reconcile(first.receipt(), 5);
        first.close();
        var second = quota.acquire(actor, 18, "app", 40, 10000, 100, 50, 3, 8, false, 0);
        second.close();
        assertThatThrownBy(
                        () -> quota.acquire(actor, 18, "app", 10, 10000, 100, 50, 3, 8, false, 0))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void batchWaitsInBoundedRedisQueueAndContinuesAfterPermitRelease() throws Exception {
        var first = quota.acquire(actor, 18, "app", 1, 10000, 100, 500, 1, 8, false, 0);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var second =
                    executor.submit(
                            () ->
                                    quota.acquire(
                                            actor, 18, "app", 1, 10000, 100, 500, 1, 8, true,
                                            2000));
            Thread.sleep(150);
            assertThat(second.isDone()).isFalse();
            first.close();
            try (var permit = second.get(3, java.util.concurrent.TimeUnit.SECONDS)) {
                assertThat(permit.queuedMs()).isGreaterThanOrEqualTo(100);
            }
        } finally {
            first.close();
        }
    }

    @Test
    void unavailableRedisFailsClosedWithoutLocalFallback() {
        var unavailable =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        unavailable.afterPropertiesSet();
        try {
            assertThatThrownBy(
                            () ->
                                    new ModelQuota(new StringRedisTemplate(unavailable))
                                            .acquire(
                                                    actor, 18, "app", 1, 10000, 100, 50, 3, 8,
                                                    false, 0))
                    .isInstanceOfSatisfying(
                            PlatformException.class, e -> assertThat(e.status()).isEqualTo(503));
        } finally {
            unavailable.destroy();
        }
    }
}
