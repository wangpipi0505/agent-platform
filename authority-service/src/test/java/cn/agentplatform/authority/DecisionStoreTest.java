package cn.agentplatform.authority;

import cn.agentplatform.authority.decision.DecisionStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DecisionStore} 的有界与过期语义（注入固定 {@link Clock}，不依赖真实时间）：
 * 写入时清理过期项、超容量按最早写入淘汰，而 {@code find} 不做过期过滤——
 * 否则转发拿不到"过期"这个事实，只能报"未登记"，卡片要求的 409 就退化成 403。
 */
class DecisionStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void keepsOnlyTheNewestEntriesWhenCapacityIsReached() {
        DecisionStore store = new DecisionStore(2, CLOCK);

        store.put(decision("dec-1", NOW.plusSeconds(60)));
        store.put(decision("dec-2", NOW.plusSeconds(60)));
        store.put(decision("dec-3", NOW.plusSeconds(60)));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find("dec-1")).isEmpty();
        assertThat(store.find("dec-2")).isPresent();
        assertThat(store.find("dec-3")).isPresent();
    }

    @Test
    void sweepsExpiredEntriesOnWrite() {
        DecisionStore store = new DecisionStore(8, CLOCK);

        store.put(decision("dec-old", NOW.minusSeconds(1)));
        store.put(decision("dec-new", NOW.plusSeconds(60)));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.find("dec-old")).isEmpty();
        assertThat(store.find("dec-new")).isPresent();
    }

    @Test
    void findKeepsExpiredDecisionVisibleSoTheCallerSeesAUTHORITYEXPIRED() {
        DecisionStore store = new DecisionStore(8, CLOCK);

        store.put(decision("dec-expired", NOW.minusSeconds(1)));

        assertThat(store.find("dec-expired")).isPresent();
    }

    @Test
    void touchExpiryExtendsTheRegistrationToTheHardDeadline() {
        DecisionStore store = new DecisionStore(8, CLOCK);
        store.put(decision("dec-window", NOW.plusSeconds(5)));

        Instant hardDeadline = NOW.plusSeconds(55);
        assertThat(store.touchExpiry("dec-window", hardDeadline)).isPresent();
        // 过期项在 put 时被清掉；这里显式触发一次写入，确认已延后的登记不会被 sweep 回收。
        store.put(decision("dec-other", NOW.plusSeconds(60)));
        assertThat(store.find("dec-window")).isPresent();
        assertThat(store.find("dec-window").orElseThrow().expiresAt()).isEqualTo(hardDeadline);
    }

    @Test
    void touchExpiryExtendsAConsumedRegistrationWithoutResurrectingIt() {
        DecisionStore store = new DecisionStore(8, CLOCK);
        store.put(decision("dec-consumed", NOW.plusSeconds(5)));
        DecisionStore.Decision consumed = store.consume("dec-consumed", "gateway").orElseThrow();
        Instant consumedAt = consumed.consumedAt();

        // S4 child 换票已消费父窗口；S6 的首次续期必须仍允许（EX-46 的 403 回归），
        // 但续期只能推后 expiresAt：消费标记原样保留，窗口不会“复活”成可再消费/可再换票。
        Instant hardDeadline = NOW.plusSeconds(55);
        assertThat(store.touchExpiry("dec-consumed", hardDeadline))
                .as("续期 ≠ 复活：已消费的登记允许推后 expiresAt")
                .isPresent();

        DecisionStore.Decision touched = store.find("dec-consumed").orElseThrow();
        assertThat(touched.expiresAt()).isEqualTo(hardDeadline);
        assertThat(touched.consumedAt())
                .as("续期不能擦掉消费时间：旧快照回写才是复活")
                .isEqualTo(consumedAt);
        assertThat(touched.consumedBy()).isEqualTo("gateway");
        // 续期不改变一次性消费的语义：同一张窗口不能再被消费一次。
        assertThat(store.consume("dec-consumed", "gateway")).isEmpty();
    }

    private static DecisionStore.Decision decision(String id, Instant expiresAt) {
        return new DecisionStore.Decision(id, "engine", "skill", "skill.process.cancel",
                Map.of("kind", "run", "id", "run-1", "version_digest", AuthorityTestSupport.DIGEST),
                Map.of("tenant_id", "tenant-a", "sub", "alice"), Map.of("tenant_id", "tenant-a", "sub", "alice"),
                Map.of("operation_id", "op-bootstrap"), "grant-1", expiresAt, "gateway", null,
                null, "delegated-v1", expiresAt,
                // 1c/1d 新增的 admission/消费/continuation 字段：本测试只关心容量与过期，全部留空。
                // 第 26 个字段是 EX-46 的 immutable 快照（续期要逐字节回显），本测试不涉及续期，留空。
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
