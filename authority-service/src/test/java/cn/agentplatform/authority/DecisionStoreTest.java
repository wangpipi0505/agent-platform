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

    private static DecisionStore.Decision decision(String id, Instant expiresAt) {
        return new DecisionStore.Decision(id, "engine", "skill", "skill.process.cancel",
                Map.of("kind", "run", "id", "run-1", "version_digest", AuthorityTestSupport.DIGEST),
                Map.of("tenant_id", "tenant-a", "sub", "alice"), Map.of("tenant_id", "tenant-a", "sub", "alice"),
                Map.of("operation_id", "op-bootstrap"), "grant-1", expiresAt, "gateway", null,
                "delegated-v1", expiresAt);
    }
}
