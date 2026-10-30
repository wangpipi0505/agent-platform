package cn.agentplatform.authority.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 委派判权与转发（{@code operation=authorize/forward}）的开关与时限。
 *
 * <p>为什么默认关：这两个 operation 会让 gateway 用"人的 portal 令牌"换出一张可以被转发到下游服务的
 * delegated 票据，等于把人的权限向下游扩散。M3 之前平台侧还没有消费方，默认开等于平白扩大攻击面；
 * 关闭时两条 operation 一律 403，其它三条链路（client / authorize_workload / session）完全不受影响。
 *
 * <p>三层时限严格嵌套，任何一层都不许"签更久"：
 * <ul>
 *   <li>{@code assertionSeconds}（{@code agent.authority.assertion-seconds}, ≤5s）决策断言本身；</li>
 *   <li>{@code grantSeconds}（本类, ≤60s）一次被委派的操作/grant 与 delegated 票据上限；</li>
 *   <li>{@code decisionSeconds}（本类）决策在存储里的存活窗口，只用于"还能不能再转发一次"，
 *       不会延长 {@code grant}。</li>
 * </ul>
 *
 * @param enabled         是否启用 authorize/forward；默认 false
 * @param grantSeconds    grant 与 delegated 票据的生存时长上限（与平台 {@code maxTicketSeconds} 一致）
 * @param decisionSeconds 决策在 {@code DecisionStore} 里的存活窗口上限
 * @param capacity        {@code DecisionStore} 的条目上限（超出按最早写入淘汰）
 */
@ConfigurationProperties(prefix = "agent.authority.delegation")
public record DelegationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("60") int grantSeconds,
        @DefaultValue("60") int decisionSeconds,
        @DefaultValue("256") int capacity) {

    public DelegationProperties {
        if (grantSeconds < 1 || grantSeconds > 60) {
            throw new IllegalStateException("AUTHORITY_DELEGATION_INVALID: grant-seconds 必须在 1..60");
        }
        if (decisionSeconds < 1 || decisionSeconds > 3600) {
            throw new IllegalStateException("AUTHORITY_DELEGATION_INVALID: decision-seconds 必须在 1..3600");
        }
        if (capacity < 1 || capacity > 10_000) {
            throw new IllegalStateException("AUTHORITY_DELEGATION_INVALID: capacity 必须在 1..10000");
        }
    }
}
