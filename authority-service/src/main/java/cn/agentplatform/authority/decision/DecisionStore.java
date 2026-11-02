package cn.agentplatform.authority.decision;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 委派决策登记簿。内存态、进程内、有界：决策只对"本服务刚刚判过权"的那一次调用链有意义，
 * 重启即失效是**预期语义**（调用方拿到 403 后重新判权），所以这里不引入外部存储。
 *
 * <p>EX-44 §6.1 已把"单实例内存"接受为**显式债务**：一次性消费的原子性只在单进程内成立，
 * 多实例/重启后 {@code query_ticket} 的未消费判定会失效，EX-46 迁移 PG 时由唯一约束接管。
 * 这是有意的取舍，不是遗漏。
 *
 * <p>四个刻意选择：
 * <ul>
 *   <li>{@link #find} 不清理过期项——否则转发拿不到"过期"这个事实，只能报"不存在"，
 *       而卡片要求过期与未登记可区分（409 {@code AUTHORITY_EXPIRED} vs 403）；</li>
 *   <li>写入时清理过期项；仍超容量时按插入顺序淘汰最旧的——转发出去的决策最多 5 秒，
 *       淘汰历史项不会误伤进行中的链路；</li>
 *   <li>不保存断言/令牌原文，只存判权结果与**摘要**，审计与回放都从这些字段重建；</li>
 *   <li>结构从 {@code LinkedHashMap + synchronized} 换成 {@link ConcurrentHashMap} + 插入序队列：
 *       EX-44 S1 要求"同一 opening 决策只能被消费一次"，用 {@code compute} 做单键 CAS，
 *       不允许"先查 consumedAt 再写"——两个并发请求都会通过先查，最终签出两张票。</li>
 * </ul>
 */
public final class DecisionStore {

    /** EX-44 观测链路的决策用途；普通委派（authorize/forward/workload）不标注。 */
    public enum Purpose {
        /** S0：portal 令牌 + gateway_events_opening 换出的 metadata opening 票。 */
        OPENING,
        /** S1：用 opening 票换出的 gateway 观测票（本切片）。 */
        RETAINED,
        /** S2/S3：由 retained 票继续派生的决策（1d 使用）。 */
        EVENTS,
        /** S4：child 交换后给 engine 的观测授权（EX-45）；父决策是 S3 的 {@link #EVENTS}。 */
        CHILD,
        /**
         * S5：engine 按 {@code child_open} 真正打开子观测后落下的窗口决策（EX-46）。
         *
         * <p>与 {@link #CHILD} 的分工：{@code CHILD} 是"S4 已授权 engine 可以来打开"的一次性凭据，
         * S5 消费它并产生一条可续期的窗口；S6 的 {@code portal_events_child_check} 只刷新这条窗口的
         * checked 上界，不再消费任何决策。
         */
        CHILD_OPEN
    }

    private final Map<String, Decision> entries = new ConcurrentHashMap<>();
    /** jti -> decision id：S1 只拿得到票面 jti，必须能反查到 S0 决策。 */
    private final Map<String, String> tickets = new ConcurrentHashMap<>();
    /** continuation_jti -> decision id：S4 只拿得到 continuation，必须能反查到 S3 决策（EX-45）。 */
    private final Map<String, String> continuations = new ConcurrentHashMap<>();
    /** 插入序；与 entries 分开存，才能在不加全局锁的前提下做有界淘汰。 */
    private final Queue<String> order = new ConcurrentLinkedQueue<>();
    private final int capacity;
    private final Clock clock;

    public DecisionStore(int capacity, Clock clock) {
        this.capacity = Math.max(1, capacity);
        this.clock = clock;
    }

    /**
     * 一条决策的完整判权结果。{@code expiresAt} 是**这一跳**的回执有效期（不晚于 grant 到期），
     * 转发出来的下一跳会有自己的 id 与更短的有效期。
     *
     * <p>后面七个字段是 EX-44 §4.2 的扩展：{@code purpose} 区分三跳；{@code sourceTokenSha256}
     * 把决策钉死到**某一张** portal 令牌原文（换一张同用户令牌不能复用 opening）；
     * {@code admissionNonce}/{@code admissionRequestSha256} 记录**入站请求**的 nonce 与摘要，
     * 供 1d 的 {@code gateway_query} 比对；{@code consumedAt}/{@code consumedBy} 是一次性消费标记；
     * {@code parentAdmissionId} 让 S1 决策能指回它消费的 S0 决策。
     *
     * <p>EX-46 增补 {@code immutable}：S5/S6 续期时断言必须**逐字节**回显当初开窗的那份 immutable
     * （客户端 {@code install} 要求续期前后 {@code immutable} 引用相同，{@code eventsCurrent} 再做整节点
     * 比较），而 child 窗口的 {@code opening_decision_id}/{@code admission_nonce} 等字段来自 engine 的
     * 入站请求、无法从本服务已有决策重算，所以只能把已经过一次整节点校验的节点存下来。
     * 这里存的不是令牌原文，而是公开的、已经进了签名的判权素材；过期淘汰规则与其它字段一致。
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
            String originAuthenticationId,
            String authorizationVersion,
            Instant expiresAt,
            Purpose purpose,
            String sourceTokenSha256,
            String admissionNonce,
            String admissionRequestSha256,
            Instant consumedAt,
            String consumedBy,
            String parentAdmissionId,
            String bindingResponseId,
            String continuationJti,
            Instant hardDeadline,
            com.fasterxml.jackson.databind.JsonNode immutable) {

        /** 同一决策签出票后回写票 jti：决策 id 不变，只补"这张决策对应哪张票"。 */
        public Decision withTicketJti(String jti) {
            return new Decision(id, caller, target, action, resource, actor, ceiling, operation, grantRef,
                    grantExpiresAt, subjectCaller, jti, originAuthenticationId, authorizationVersion, expiresAt,
                    purpose, sourceTokenSha256, admissionNonce, admissionRequestSha256, consumedAt, consumedBy,
                    parentAdmissionId, bindingResponseId, continuationJti, hardDeadline, immutable);
        }

        /** 续期时只推后登记的有效期：窗口登记要活到硬截止，判权结果与一次性消费标记都不动。 */
        public Decision withExpiresAt(Instant until) {
            return new Decision(id, caller, target, action, resource, actor, ceiling, operation, grantRef,
                    grantExpiresAt, subjectCaller, ticketJti, originAuthenticationId, authorizationVersion, until,
                    purpose, sourceTokenSha256, admissionNonce, admissionRequestSha256, consumedAt, consumedBy,
                    parentAdmissionId, bindingResponseId, continuationJti, hardDeadline, immutable);
        }

        /** 一次性消费标记：只改这两个字段，其余判权结果不动（消费不改变已经做出的授权）。 */
        public Decision consumed(Instant at, String consumer) {
            return new Decision(id, caller, target, action, resource, actor, ceiling, operation, grantRef,
                    grantExpiresAt, subjectCaller, ticketJti, originAuthenticationId, authorizationVersion,
                    expiresAt, purpose, sourceTokenSha256, admissionNonce, admissionRequestSha256, at, consumer,
                    parentAdmissionId, bindingResponseId, continuationJti, hardDeadline, immutable);
        }
    }

    public void put(Decision decision) {
        sweep();
        if (entries.put(decision.id(), decision) == null) {
            // 覆盖写（先登记决策、签票后再回写 jti）不产生第二个淘汰位，否则同一条决策会被计两次容量。
            order.add(decision.id());
        }
        if (decision.ticketJti() != null) {
            tickets.put(decision.ticketJti(), decision.id());
        }
        if (decision.continuationJti() != null) {
            continuations.put(decision.continuationJti(), decision.id());
        }
    }

    /** 不做过期过滤：调用方需要自己判断"过期"(409) 与"不存在"(403) 的区别。 */
    public Optional<Decision> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    /**
     * 按票面 jti 反查它对应的决策。同样不做过期过滤：{@code query_ticket} 要区分
     * {@code EVENTS_TICKET_UNKNOWN}（没有这张票）与 {@code EVENTS_TICKET_EXPIRED}（有，但过期）。
     */
    public Optional<Decision> findByTicketJti(String jti) {
        if (jti == null) {
            return Optional.empty();
        }
        String id = tickets.get(jti);
        if (id == null) {
            return Optional.empty();
        }
        Decision decision = entries.get(id);
        if (decision == null || !jti.equals(decision.ticketJti())) {
            // 索引指向的条目已被淘汰/覆盖：顺手清掉悬空索引，避免它随容量反复增长。
            tickets.remove(jti, id);
            return Optional.empty();
        }
        return Optional.of(decision);
    }

    /**
     * 按 continuation 的 jti 反查它所属的 S3 决策（EX-45 S4 的入口）。
     *
     * <p>同样不做过期过滤：S4 必须能区分"没有这段会话"（403 EVENTS_CHILD_CONTINUATION）与
     * "会话还在但已过窗"（409），并且<b>重放判定要排在过期判定之前</b>——否则已消费的 S3
     * 决策过期后会从"重放"漂移成"过期"，同一个攻击得到两种 reason。
     */
    public Optional<Decision> findByContinuationJti(String jti) {
        if (jti == null) {
            return Optional.empty();
        }
        String id = continuations.get(jti);
        if (id == null) {
            return Optional.empty();
        }
        Decision decision = entries.get(id);
        if (decision == null || !jti.equals(decision.continuationJti())) {
            // 条目已被淘汰/覆盖：清掉悬空索引，避免它随容量反复增长。
            continuations.remove(jti, id);
            return Optional.empty();
        }
        return Optional.of(decision);
    }

    /**
     * 一次性消费：{@code compute} 对同一个 key 是原子的（ConcurrentHashMap 单 bin 锁），
     * 两个并发调用只有一个能把 {@code consumedAt} 从 null 改成时间戳，另一个拿到 {@link Optional#empty()}。
     * 不需要"先查再写"，也没有任何窗口能让两个请求都成功。
     *
     * @return 消费成功时返回**已标记消费**的决策；已消费或不存在时 empty（调用方据此报 403）。
     */
    public Optional<Decision> consume(String id, String consumer) {
        if (id == null) {
            return Optional.empty();
        }
        Instant at = Instant.ofEpochSecond(now());
        Decision[] consumed = new Decision[1];
        entries.compute(id, (key, current) -> {
            if (current == null || current.consumedAt() != null) {
                return current;
            }
            consumed[0] = current.consumed(at, consumer);
            return consumed[0];
        });
        return Optional.ofNullable(consumed[0]);
    }

    public int size() {
        return entries.size();
    }

    private void sweep() {
        Instant now = Instant.ofEpochSecond(now());
        entries.forEach((id, decision) -> {
            if (!decision.expiresAt().isAfter(now)) {
                remove(id);
            }
        });
        while (entries.size() >= capacity) {
            String oldest = order.poll();
            if (oldest == null) {
                break;
            }
            remove(oldest);
        }
    }

    private void remove(String id) {
        Decision removed = entries.remove(id);
        if (removed != null && removed.ticketJti() != null) {
            tickets.remove(removed.ticketJti(), id);
        }
        if (removed != null && removed.continuationJti() != null) {
            continuations.remove(removed.continuationJti(), id);
        }
    }

    private long now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS).getEpochSecond();
    }
}
