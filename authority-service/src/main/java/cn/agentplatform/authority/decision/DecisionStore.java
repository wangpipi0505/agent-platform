package cn.agentplatform.authority.decision;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
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
    /**
     * grant 保留区（EX-26）：按 decision id 保存仍可能被 {@code operation=resume} 换票的原委托。
     *
     * <p>为什么需要第二份登记：{@code entries} 的 {@code expiresAt} 是**这一跳**的展示/消费有效期
     * （authorize 取 {@code min(issued+decisionSeconds, grantExpiresAt)}）。{@code decisionSeconds}
     * 一旦配得比 {@code grantSeconds} 短，登记会在 grant 仍有效时被 {@code sweep()} 按 expiresAt 清掉，
     * 于是同一份委托 resume 只能报 {@code RESUME_GRANT_UNKNOWN}——恢复来源被展示 TTL 误杀。
     * 保留区把恢复来源活到 {@code grantExpiresAt}，与决策的展示/消费 TTL 解耦；只有
     * {@code grantRef != null}（可 resume）的决策才登记，容量与 {@code capacity} 同量级，
     * 按 {@code grantExpiresAt} 淘汰最旧。
     *
     * <p>它不是第二份权威：{@link #consume}/{@link #touchExpiry} 在同一次调用里同步同一决策，
     * 保留区里的 {@code consumedAt} 与 {@code entries} 一样是撤销信号；过期时间永远只看
     * {@code grantExpiresAt}，绝不用 {@code expiresAt}。
     */
    private final Map<String, Decision> grantEntries = new ConcurrentHashMap<>();
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
        if (decision.grantRef() == null) {
            // 没有 grant 的决策本来就 resume 不了；若曾登记过，覆盖写要顺手撤掉，避免保留区留下
            // 一条 grantRef 为空、resume 永远匹配不上的僵尸项。
            grantEntries.remove(decision.id());
        } else {
            // 保留区只吸收"还能不能 resume"的状态：消费标记一旦落下，后续覆盖写（例如回写票 jti）
            // 不能用创建时的旧快照把它擦掉——那会让 resume 从 REVOKED 漂移回成功。
            grantEntries.compute(decision.id(),
                    (key, current) -> current == null ? decision : keepConsumption(current, decision));
        }
    }

    /** 合并两份登记时以 {@code consumedAt} 为准：消费是单向状态，任何旧快照都不能把它抹掉。 */
    private static Decision keepConsumption(Decision retained, Decision incoming) {
        if (retained.consumedAt() == null || incoming.consumedAt() != null) {
            return incoming;
        }
        return incoming.consumed(retained.consumedAt(), retained.consumedBy());
    }

    /** 不做过期过滤：调用方需要自己判断"过期"(409) 与"不存在"(403) 的区别。 */
    public Optional<Decision> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    /**
     * 按 grant_ref 反查它签出过的全部决策（同一个 grant 可能因 forward 产生多跳）。
     *
     * <p>同样不做过期过滤：{@code operation=resume} 必须把"这份委托从未签发过"（403）与
     * "签发过但 grant 已到期"（403 {@code AUTHORITY_EXPIRED}）分开——前者只能重判权，
     * 后者至少让调用方知道原能力存在过，不能把两者混成一个不可区分的拒绝。
     *
     * <p>EX-26 起合并两个来源：{@code entries}（短 TTL，供展示/消费）与 {@link #grantEntries}
     * （活到 {@code grantExpiresAt} 的恢复来源）。按 decision id 去重，entries 优先——它们的字段
     * 除 expiresAt 外同源。这里仍<b>不清理也不过滤</b>过期项：保留区的清理发生在写入路径的
     * {@code sweep()} 里，resume 才能先看到"已过期"（{@code AUTHORITY_EXPIRED}）而不是"不存在"。
     *
     * <p>刻意不加 grant_ref 索引：两个登记区都按容量有界（默认 {@code 256}），线性扫描的代价
     * 远小于维护第三个可能悬空的索引；淘汰/覆盖由 {@code sweep} 与 {@code put} 负责，这里只读。
     */
    public List<Decision> findByGrantRef(String grantRef) {
        if (grantRef == null || grantRef.isBlank()) {
            return List.of();
        }
        Map<String, Decision> found = new LinkedHashMap<>();
        for (Decision decision : entries.values()) {
            if (grantRef.equals(decision.grantRef())) {
                found.put(decision.id(), decision);
            }
        }
        for (Decision decision : grantEntries.values()) {
            if (grantRef.equals(decision.grantRef())) {
                found.putIfAbsent(decision.id(), decision);
            }
        }
        return List.copyOf(found.values());
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
        if (consumed[0] != null) {
            // 保留区必须看到同一个撤销标记：entries 被 sweep（短 TTL）清掉后，resume 只剩保留区
            // 这一条来源；这里漏同步会让已消费的委托在 expiresAt 之后又能成功换票（EX-26）。
            // 用 compute 基于保留区的当前值改，保留它自己的 expiresAt，且不覆盖更早的消费时间。
            grantEntries.compute(id, (key, current) -> {
                if (current == null || current.consumedAt() != null) {
                    return current;
                }
                return current.consumed(at, consumer);
            });
        }
        return Optional.ofNullable(consumed[0]);
    }

    /**
     * 只推后登记有效期，且**基于当前值**重写（EX-46）。<b>续期 ≠ 复活</b>：
     *
     * <p>已消费的登记也允许续期——S4 child 换票会消费父 EVENTS 窗口，S6 的第一次续期必须仍能
     * 把窗口推到硬截止，否则合法链路在第一个断言窗后就 403。但续期只改 {@code expiresAt}，
     * {@code consumedAt}/{@code consumedBy} 原样保留：已消费的登记仍然不可再 {@link #consume}，
     * 也仍然会让 resume 报 {@code RESUME_REVOKED}。消费的"单向性"没有变，变的只是有效期。
     *
     * <p>不能用"读出旧快照 → {@code put(withExpiresAt)}"：读与写之间若有 child 换票把
     * {@code consumedAt} 标记上去，旧快照回写会把消费标记擦掉，同一张观察窗就能二次换票——
     * 那才是复活。这里用 {@code compute} 在同一把 bin 锁内读-改-写，并同步保留区。
     *
     * @return 成功推后时返回更新后的决策；登记不存在时 empty（调用方据此报 403）。
     */
    public Optional<Decision> touchExpiry(String id, Instant until) {
        if (id == null || until == null) {
            return Optional.empty();
        }
        Decision[] touched = new Decision[1];
        entries.compute(id, (key, current) -> {
            if (current == null) {
                return null;
            }
            touched[0] = current.withExpiresAt(until);
            return touched[0];
        });
        // 保留区镜像同一份有效期；同样按字段改而不是整体替换，绝不把消费标记写丢。
        if (touched[0] != null) {
            grantEntries.compute(id, (key, current) ->
                    current == null ? null : current.withExpiresAt(until));
        }
        return Optional.ofNullable(touched[0]);
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
        sweepGrantReservations(now);
    }

    /**
     * 保留区的过期与容量：按 {@code grantExpiresAt}（不是 {@code expiresAt}）清理与淘汰。
     *
     * <p>清理只发生在写入路径：读路径保留过期项，resume 才能区分"过期"（AUTHORITY_EXPIRED）
     * 与"从未签发"（RESUME_GRANT_UNKNOWN）。容量按 grantExpiresAt 最旧淘汰——按插入序会把
     * "签发早但 grant 更晚"的恢复来源误删，而 grantExpiresAt 才是它真正的可用边界。
     */
    private void sweepGrantReservations(Instant now) {
        grantEntries.forEach((id, decision) -> {
            Instant until = decision.grantExpiresAt();
            if (until == null || !until.isAfter(now)) {
                grantEntries.remove(id, decision);
            }
        });
        while (grantEntries.size() >= capacity) {
            String oldest = oldestGrant();
            if (oldest == null) {
                break;
            }
            grantEntries.remove(oldest);
        }
    }

    /** 按 grantExpiresAt 找最旧的保留项；null（形状异常的决策）视为最旧，优先淘汰。 */
    private String oldestGrant() {
        String oldestId = null;
        Instant oldestUntil = null;
        for (Map.Entry<String, Decision> entry : grantEntries.entrySet()) {
            Instant until = entry.getValue().grantExpiresAt();
            if (oldestId == null || until == null
                    || (oldestUntil != null && until.isBefore(oldestUntil))) {
                oldestId = entry.getKey();
                oldestUntil = until;
            }
        }
        return oldestId;
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
