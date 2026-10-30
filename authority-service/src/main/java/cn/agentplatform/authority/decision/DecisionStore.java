package cn.agentplatform.authority.decision;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 委派决策登记簿。内存态、进程内、有界：决策只对"本服务刚刚判过权"的那一次调用链有意义，
 * 重启即失效是**预期语义**（调用方拿到 403 后重新判权），所以这里不引入外部存储。
 *
 * <p>三个刻意选择：
 * <ul>
 *   <li>{@link #find} 不清理过期项——否则转发拿不到"过期"这个事实，只能报"不存在"，
 *       而卡片要求过期与未登记可区分（409 {@code AUTHORITY_EXPIRED} vs 403）；</li>
 *   <li>写入时清理过期项；仍超容量时按插入顺序淘汰最旧的——转发出去的决策最多 5 秒，
 *       淘汰历史项不会误伤进行中的链路；</li>
 *   <li>不保存断言/令牌原文，只存判权结果，审计与回放都从这些字段重建。</li>
 * </ul>
 */
public final class DecisionStore {

    private final Map<String, Decision> entries = new LinkedHashMap<>();
    private final int capacity;
    private final Clock clock;

    public DecisionStore(int capacity, Clock clock) {
        this.capacity = Math.max(1, capacity);
        this.clock = clock;
    }

    /**
     * 一条决策的完整判权结果。{@code expiresAt} 是**这一跳**的回执有效期（不晚于 grant 到期），
     * 转发出来的下一跳会有自己的 id 与更短的有效期。
     */
    public record Decision(
            String id,
            String caller,
            String target,
            String action,
            Map<String, Object> resource,
            Map<String, Object> actor,
            Map<String, Object> ceiling,
            Map<String, Object> operation,
            String grantRef,
            Instant grantExpiresAt,
            String subjectCaller,
            String ticketJti,
            String authorizationVersion,
            Instant expiresAt) {
    }

    public synchronized void put(Decision decision) {
        sweep();
        entries.put(decision.id(), decision);
    }

    /** 不做过期过滤：调用方需要自己判断"过期"(409) 与"不存在"(403) 的区别。 */
    public synchronized Optional<Decision> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    public synchronized int size() {
        return entries.size();
    }

    private void sweep() {
        long now = now();
        entries.values().removeIf(decision -> !decision.expiresAt().isAfter(Instant.ofEpochSecond(now)));
        Iterator<String> oldest = entries.keySet().iterator();
        while (entries.size() >= capacity && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    private long now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }
}
