package cn.agentplatform.gateway.domain;

import cn.agentplatform.common.PlatformException;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * T18.1 剩余段（PLAT-18）：/integration 业务入口按集成凭据（ApiKey credential）维度的滑动窗口限流。
 * 参考 engine RunQuota 的"窗口内已计量用量达到上限即拒绝"口径做网关侧独立轻量实现，
 * 不跨模块复用代码、不引依赖：进程内维护每凭据已放行时刻的滑动窗口日志。
 *
 * <p>设计不变量：
 *
 * <ul>
 *   <li>只对<b>通过凭据校验后的成功放行</b>计数（出票成功、票据代理放行各为一个单位）；
 *       401/403 被拒流量不消耗配额。直连 ApiKey 的一次业务请求在 authenticate 内出票时计数并打标，
 *       check 凭标记去重，保证一次 HTTP 请求恰好计一次。</li>
 *   <li>开关 {@code agent.integration.rate-limit-enabled} 默认 false、
 *       {@code max-requests} 默认 0（=不限），两者任一不满足即整体旁路；回滚=关开关。</li>
 *   <li>429 错误码 INTEGRATION_RATE_LIMITED 与 engine 的 QUOTA（token 配额，受理闸门）是不同
 *       触发条件（计划 §6.2-2 分码登记）：本码=入口请求速率超限；userMessage 必须含哪把 key
 *       （脱敏尾 4 位，凭据 id）、窗口内已放行数、上限与重试建议；完整密钥绝不出现。</li>
 *   <li>超限事件写 SLF4J 既有日志路径（网关无集成审计表写入口，新增表属 DDL 禁区），
 *       带 credential 标识、脱敏尾号与 MDC traceId，不带密钥。</li>
 *   <li>网关单实例部署：窗口为进程内存态，重启清零、多实例不共享，是有意接受的限制。</li>
 * </ul>
 */
public final class IntegrationRateLimit {
    public static final String CODE = "INTEGRATION_RATE_LIMITED";
    /** 直连 ApiKey 请求在 authenticate 计数后打的请求标记；check 据此去重。 */
    public static final String METERED_ATTRIBUTE = "gateway.integration.rateLimit.metered";

    private static final Logger log = LoggerFactory.getLogger(IntegrationRateLimit.class);

    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();
    /** 凭据 id → 密钥尾 4 位（首次出票成功时记录；票据代理请求拿不到密钥材料）。 */
    private final Map<Long, String> keyTails = new ConcurrentHashMap<>();

    private Clock clock = Clock.systemUTC();
    private boolean enabled = false;
    private long windowSeconds = 60;
    private long maxRequests = 0;

    public IntegrationRateLimit() {}

    public IntegrationRateLimit(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** 测试与独立装配缝：时钟可注入（滑动窗口的判定完全由时钟驱动）。 */
    public void clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void enabled(boolean value) {
        this.enabled = value;
    }

    public void windowSeconds(long value) {
        this.windowSeconds = value;
    }

    public void maxRequests(long value) {
        this.maxRequests = value;
    }

    /** 用例隔离缝：清空窗口与脱敏尾号（仅测试调用；生产进程内状态随窗口自然滑出）。 */
    public void reset() {
        windows.clear();
        keyTails.clear();
    }

    /**
     * 入口计数闸门：窗口内已放行数达到上限即抛 429（拒绝请求不计数），否则记录当前时刻并放行。
     * keyTail 仅在持有密钥材料的出票路径传入；票据代理路径传 null，沿用首次记录的尾号。
     */
    public void admit(long credentialId, String keyTail, String path) {
        if (!enabled || maxRequests <= 0) return;
        if (keyTail != null && !keyTail.isBlank()) keyTails.putIfAbsent(credentialId, keyTail);
        Deque<Long> admitted =
                windows.computeIfAbsent(credentialId, id -> new ArrayDeque<>());
        long now = clock.millis();
        long cutoff = now - Math.max(1, windowSeconds) * 1000L;
        synchronized (admitted) {
            while (!admitted.isEmpty() && admitted.peekFirst() < cutoff) admitted.pollFirst();
            long used = admitted.size();
            if (used >= maxRequests) {
                String tail = keyTails.getOrDefault(credentialId, "未知");
                // 超限审计：既有日志路径，凭据标识+脱敏尾号+用量/上限+traceId，绝无密钥本体。
                log.warn(
                        "{} credential={} keyTail={} path={} windowSeconds={} limit={} used={} traceId={}",
                        CODE,
                        credentialId,
                        tail,
                        path,
                        windowSeconds,
                        maxRequests,
                        used,
                        MDC.get("traceId"));
                throw new PlatformException(
                        429,
                        CODE,
                        "集成凭据 "
                                + credentialId
                                + "（尾号 "
                                + tail
                                + "）请求过于频繁："
                                + windowSeconds
                                + " 秒窗口内已放行 "
                                + used
                                + " 个请求，上限 "
                                + maxRequests
                                + " 个，请等待窗口滑出后重试，或联系平台管理员调整上限");
            }
            admitted.addLast(now);
        }
    }
}
