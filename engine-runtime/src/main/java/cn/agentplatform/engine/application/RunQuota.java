package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * T19.1（PLAT-19 缩水版，X-6 定案：人工维护价目、不引入模型价目与金额成本）：
 * token 计量聚合查询 + tenant/app/credential 三级滑动窗口配额闸门。
 *
 * <p>设计不变量：
 *
 * <ul>
 *   <li>计量事实唯一来源是既有 trace 写入（engine.model_invocation.usage_accounted 与
 *       response-&gt;'usage'-&gt;'totalTokens'）。本类只读，不改 trace 写入语义；
 *       usage_accounted 保证初始调用与主轮之间不重复计数。</li>
 *   <li>窗口按 mi.updated_at（响应落库≈计量时刻）滑动。准入判定口径："窗口内已计量用量 ≥ 上限即拒绝"，
 *       不预估本次运行的未来 token——模型调用的实时节流仍由 model-gateway 供应商级 TPM/RPM 负责，
 *       本级是平台侧可读、跨供应商的配额边界。</li>
 *   <li>credential 级以冻结模型引用的 providerId（供应商凭据主体）为 scope；快照无模型引用时跳过该级，
 *       避免纯无模型运行被误伤。</li>
 *   <li>上限 0 或负数表示该级不限；agent.engine.quota-enabled=false 时准入整体旁路（回滚开关）。
 *       聚合读路径是纯只读查询，不受开关控制。</li>
 * </ul>
 */
@Service
public class RunQuota {
    private static final String WINDOW_FROM =
            " from engine.model_invocation mi join engine.run r on r.id=mi.run_id"
                + " where mi.usage_accounted"
                + " and mi.response->'usage'->>'totalTokens' is not null"
                + " and mi.updated_at >= ?";

    private static final String WINDOW_SUM =
            "select coalesce(sum((mi.response->'usage'->>'totalTokens')::bigint),0)" + WINDOW_FROM;

    private final JdbcTemplate db;
    private final Clock clock;

    @Value("${agent.engine.quota-enabled:false}")
    private boolean enabled = false;

    @Value("${agent.engine.quota.window-seconds:3600}")
    private long windowSeconds = 3600;

    @Value("${agent.engine.quota.tenant-tokens:0}")
    private long tenantTokens = 0;

    @Value("${agent.engine.quota.app-tokens:0}")
    private long appTokens = 0;

    @Value("${agent.engine.quota.credential-tokens:0}")
    private long credentialTokens = 0;

    @Autowired
    public RunQuota(TraceStore store) {
        this(store.db(), Clock.systemUTC());
    }

    /** 测试与独立装配缝：直接持 JdbcTemplate，时钟可注入。 */
    public RunQuota(JdbcTemplate db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    public void quotaEnabled(boolean value) {
        this.enabled = value;
    }

    public void windowSeconds(long value) {
        this.windowSeconds = value;
    }

    public void tenantTokens(long value) {
        this.tenantTokens = value;
    }

    public void appTokens(long value) {
        this.appTokens = value;
    }

    public void credentialTokens(long value) {
        this.credentialTokens = value;
    }

    /**
     * 运行受理闸门：在 engine.run 插入前、受理事务外以只读窗口求和判定；超限抛 QUOTA。
     * userMessage 必须可读地说明哪一级配额、当前用量与上限（计划 §6.2 错误信封规范）。
     */
    public void requireRunAdmission(Actor actor, String appCode, JsonNode frozen) {
        if (!enabled) return;
        long window = Math.max(1, windowSeconds);
        String tenantId = actor.tenantId();
        requireWithin(
                "租户",
                tenantId,
                tenantTokens,
                tenantWindowTokens(tenantId, window),
                window);
        requireWithin(
                "应用",
                appCode,
                appTokens,
                appWindowTokens(tenantId, appCode, window),
                window);
        long provider = primaryProviderId(frozen);
        if (provider > 0)
            requireWithin(
                    "模型凭据",
                    "provider:" + provider,
                    credentialTokens,
                    credentialWindowTokens(tenantId, provider, window),
                    window);
    }

    /** tenant 维度窗口内已计量 token 求和。 */
    public long tenantWindowTokens(String tenantId, long windowSeconds) {
        return db.queryForObject(
                WINDOW_SUM + " and r.tenant_id=?", Long.class, cutoff(windowSeconds), tenantId);
    }

    /** app 维度窗口内已计量 token 求和。 */
    public long appWindowTokens(String tenantId, String appCode, long windowSeconds) {
        return db.queryForObject(
                WINDOW_SUM + " and r.tenant_id=? and r.app_code=?",
                Long.class,
                cutoff(windowSeconds),
                tenantId,
                appCode);
    }

    /** credential（供应商凭据）维度窗口内已计量 token 求和。 */
    public long credentialWindowTokens(String tenantId, long providerId, long windowSeconds) {
        return db.queryForObject(
                WINDOW_SUM + " and r.tenant_id=? and mi.response->'actualModel'->>'providerId'=?",
                Long.class,
                cutoff(windowSeconds),
                tenantId,
                Long.toString(providerId));
    }

    /**
     * 运行中心聚合读路径（PLAT-19 实现项 1 的查询侧）：tenant 内按 app/user/model 维度汇总
     * 窗口内已计量 token 与调用次数。tenant 来自已验 JWT，不接受请求参数指定。
     */
    public List<Map<String, Object>> aggregate(Actor actor, String by, long windowSeconds) {
        if (windowSeconds < 60 || windowSeconds > 2_592_000L)
            throw new IllegalArgumentException("聚合窗口必须在 60~2592000 秒之间");
        String dimension =
                switch (by == null ? "" : by) {
                    case "app" -> "r.app_code";
                    case "user" -> "r.principal_id";
                    case "model" ->
                            "coalesce(mi.response->'actualModel'->>'modelName','(unknown)')";
                    default -> throw new IllegalArgumentException("聚合维度必须是 app|user|model");
                };
        return db.queryForList(
                "select "
                        + dimension
                        + " as scope,"
                        + " coalesce(sum((mi.response->'usage'->>'totalTokens')::bigint),0) as tokens,"
                        + " count(*) as invocations"
                        + WINDOW_FROM
                        + " and r.tenant_id=? group by "
                        + dimension
                        + " order by tokens desc, scope",
                cutoff(windowSeconds),
                actor.tenantId());
    }

    private void requireWithin(String level, String scope, long limit, long used, long window) {
        if (limit <= 0 || used < limit) return;
        // QUOTA 是本次唯一新增错误码（见计划 §6.2-2）：触发条件=对应级别滑动窗口已计量用量达到上限。
        throw new PlatformException(
                429,
                "QUOTA",
                level
                        + "级 token 配额已超限：近 "
                        + window
                        + " 秒已用 "
                        + used
                        + " token，上限 "
                        + limit
                        + " token，请稍后重试或联系管理员调整配额");
    }

    /** 主模型的冻结 providerId；无模型引用或引用不含 providerId 时返回 0（credential 级跳过）。 */
    private long primaryProviderId(JsonNode frozen) {
        JsonNode config = frozen.path("config");
        long primary =
                config.path("modelEntryId")
                        .asLong(config.path("modelBinding").path("modelEntryId").asLong());
        if (primary <= 0) return 0;
        for (JsonNode ref : frozen.path("dependencyVersions").path("modelRefs"))
            if (ref.path("modelEntryId").asLong() == primary)
                return ref.path("entry").path("providerId").asLong(0);
        return 0;
    }

    private java.sql.Timestamp cutoff(long windowSeconds) {
        return java.sql.Timestamp.from(
                clock.instant().minus(Duration.ofSeconds(Math.max(1, windowSeconds))));
    }
}
