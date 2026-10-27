package cn.agentplatform.app.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAT-20 / T20.1：数据面健康快照——数据源类型、库名、Flyway 已应用版本、关键表行数（取整脱敏）
 * 与演示/真实环境标记。数据访问复用服务既有 JdbcTemplate（与 SystemStatusController 同一探测源），
 * 不另建数据源连接。明细只经 {@code /health/data}（ADMIN）与 data 健康贡献者读出；
 * {@code management.endpoint.health.show-details=never} 保证公开的 /actuator/health 不携带明细。
 *
 * <p>行数只报首位数字量级（{@link #roundCount}）：健康面面向可用性观测，精确行数属于业务数据，
 * 不得经运维接口泄露。快照带 TTL 缓存：/actuator/health 被网关/监控高频轮询，
 * count(*) 全表不应逐次执行；需要精确观测的调用方把 TTL 设为 0。
 */
@Service
public class DataHealthService {
    /** 生产默认缓存窗口：覆盖监控轮询间隔量级，又不至于让演示环境看到分钟级陈旧状态。 */
    static final long DEFAULT_TTL_MILLIS = 15_000;

    private static final Pattern PRODUCT =
            Pattern.compile("^(\\p{Alpha}+)[ ,]*(\\d+(?:\\.\\d+)*)");

    private final JdbcTemplate db;
    private final boolean demoMode;
    private final boolean devAuth;
    private final long ttlMillis;
    private volatile Map<String, Object> cache;
    private volatile long cachedAt;

    // 双构造（生产/测试）：显式 @Autowired 指定装配入口，避免 Spring 在多构造时要求默认构造。
    @org.springframework.beans.factory.annotation.Autowired
    public DataHealthService(
            JdbcTemplate db,
            // 既有环境标记（.env / 部署环境变量）原样进入健康详情，演示数据不得冒充真实结果。
            @Value("${AGENT_DEMO_MODE:false}") String demoMode,
            @Value("${AGENT_DEV_AUTH:false}") String devAuth) {
        this(
                db,
                Boolean.parseBoolean(demoMode == null ? "" : demoMode.trim()),
                Boolean.parseBoolean(devAuth == null ? "" : devAuth.trim()),
                DEFAULT_TTL_MILLIS);
    }

    /** 测试/诊断入口：显式给定标记与缓存窗口。 */
    DataHealthService(JdbcTemplate db, boolean demoMode, boolean devAuth, long ttlMillis) {
        this.db = db;
        this.demoMode = demoMode;
        this.devAuth = devAuth;
        this.ttlMillis = Math.max(0, ttlMillis);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = cache;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - cachedAt < ttlMillis) return snapshot;
        synchronized (this) {
            snapshot = cache;
            now = System.currentTimeMillis();
            if (snapshot != null && now - cachedAt < ttlMillis) return snapshot;
            snapshot = build();
            cache = snapshot;
            cachedAt = now;
            return snapshot;
        }
    }

    private Map<String, Object> build() {
        var view = new LinkedHashMap<String, Object>();
        String product = "unknown";
        String version = db.queryForObject("select version()", String.class);
        if (version != null) {
            Matcher match = PRODUCT.matcher(version.trim());
            if (match.find()) product = match.group(1) + " " + match.group(2);
        }
        view.put("dataSourceType", product);
        view.put("database", db.queryForObject("select current_database()", String.class));
        view.put("flywayVersion", maxAppliedFlywayVersion());
        // 演示/真实可从健康面区分（方案 §4.20 验收项）；devAuth 用于区分本地开发票据面。
        view.put("environment", demoMode ? "DEMO" : "REAL");
        view.put("demoMode", demoMode);
        view.put("devAuth", devAuth);
        var counts = new LinkedHashMap<String, Object>();
        for (String table :
                List.of("app.application", "app.app_version", "app.publish_commit", "app.lifecycle_log"))
            counts.put(table, roundCount(db.queryForObject("select count(*) from " + table, Long.class)));
        view.put("rowCounts", counts);
        return view;
    }

    /** 最高已成功应用的 Flyway 版本：数值比较（版本号在本仓始终为整数段）。 */
    private Integer maxAppliedFlywayVersion() {
        int max = 0;
        // installed_rank=0 的 "<< Flyway Schema Creation >>" 行 version 为 NULL，属正常行，跳过。
        for (String version :
                db.queryForList(
                        "select version from app.flyway_schema_history where success"
                                + " and version is not null",
                        String.class)) {
            try {
                max = Math.max(max, Integer.parseUnsignedInt(version.trim()));
            } catch (NumberFormatException ignored) {
                // 未来若出现非整数版本段，跳过即可：健康面只关心已应用到的主量级。
            }
        }
        return max == 0 ? null : max;
    }

    /** 只保留首位数字量级：12→10、1234→1000；0 与负值一律 0。从不泄露精确行数。 */
    static long roundCount(long count) {
        if (count <= 0) return 0;
        long magnitude = 1;
        long value = count;
        while (value >= 10) {
            value /= 10;
            magnitude *= 10;
        }
        return value * magnitude;
    }
}
