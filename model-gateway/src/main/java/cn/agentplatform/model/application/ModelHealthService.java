package cn.agentplatform.model.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.model.infra.ModelStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AP-S02 model depth health. Probes are explicit and cached; a page refresh must never turn
 * into an unbounded fan-out against every upstream provider.
 */
@Service
public class ModelHealthService {
    private record Snapshot(Map<String, Object> value, Instant at) {}

    private final ModelStore store;
    private final ModelCalls calls;
    private final Duration ttl;
    private final int maxRefresh;
    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final AtomicLong lastRefresh = new AtomicLong();
    private final AtomicLong rotation = new AtomicLong();

    public ModelHealthService(
            ModelStore store,
            ModelCalls calls,
            @Value("${agent.model.health-ttl-seconds:60}") long ttlSeconds,
            @Value("${agent.model.health-max-refresh:32}") int maxRefresh) {
        this.store = store;
        this.calls = calls;
        this.ttl = Duration.ofSeconds(Math.max(10, ttlSeconds));
        this.maxRefresh = Math.max(1, Math.min(64, maxRefresh));
    }

    /** 条目视图只带供应商编码；fixture 判定必须与模型管理页同源，避免两处口径漂移。 */
    private static boolean fixture(Map<String, Object> entry) {
        Object provider = entry.get("provider");
        if (!(provider instanceof Map<?, ?> row)) return false;
        return FixturePolicy.isFixtureProviderCode(String.valueOf(row.get("code")));
    }

    public Map<String, Object> summary(Actor actor, boolean refresh) {
        return summary(actor, refresh, false);
    }

    /**
     * refresh=true 只补未探测或已过期条目；force=true 才重新访问全部已启用条目。
     * 页面挂载不能等同于“重新探测”，否则每次刷新都会放大成对上游的扇出。
     */
    public Map<String, Object> summary(Actor actor, boolean refresh, boolean force) {
        actor.requireRole("ADMIN");
        // 深度健康只描述启用的真实条目；停用条目由模型管理页负责，
        // FIXTURE 是联调/协议测试数据，不应把生产系统状态拖成红色。
        List<Map<String, Object>> entries = store.entries(actor).stream()
                .filter(e -> "ENABLED".equals(e.get("status")))
                .filter(e -> !fixture(e))
                .toList();
        if (refresh) refresh(entries, actor, force);
        List<Map<String, Object>> rows = new ArrayList<>();
        int healthy = 0, degraded = 0, unhealthy = 0, unknown = 0;
        for (var entry : entries) {
            long id = ((Number) entry.get("id")).longValue();
            var snapshot = snapshot(actor, id);
            Map<String, Object> row = snapshot == null ? unknown(entry) : view(snapshot);
            rows.add(row);
            switch (String.valueOf(row.get("status"))) {
                case "HEALTHY" -> healthy++;
                case "DEGRADED" -> degraded++;
                case "UNHEALTHY" -> unhealthy++;
                default -> unknown++;
            }
        }
        var result = new LinkedHashMap<String, Object>();
        boolean empty = entries.isEmpty();
        result.put("ok", !empty && unhealthy == 0 && unknown == 0);
        result.put("status", empty ? "UNHEALTHY" : unhealthy > 0 ? "UNHEALTHY"
                : degraded > 0 ? "DEGRADED" : unknown > 0 ? "UNKNOWN" : "HEALTHY");
        if (empty) result.put("reason", "没有启用的模型条目");
        result.put("checkedAt", Instant.now().toString());
        result.put("ttlSeconds", ttl.toSeconds());
        result.put("counts", Map.of(
                "total", entries.size(),
                "healthy", healthy,
                "degraded", degraded,
                "unhealthy", unhealthy,
                "unknown", unknown));
        result.put("entries", rows);
        return result;
    }

    public Map<String, Object> detail(Actor actor, long entryId) {
        actor.requireRole("ADMIN");
        var entry = store.entry(entryId, actor, false);
        var snapshot = snapshot(actor, entryId);
        return snapshot == null ? unknown(entry) : view(snapshot);
    }

    public Map<String, Object> probe(Actor actor, long entryId, boolean force) {
        actor.requireRole("ADMIN");
        var entry = store.entry(entryId, actor, false);
        var snapshot = force ? null : snapshot(actor, entryId);
        if (snapshot != null) return view(snapshot);
        String key = key(actor, entryId);
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            // force 必须在锁内继续生效：否则并发的“重新探测”仍会读到刚写回的缓存，
            // 按钮看起来执行了但实际没有访问上游。
            if (!force) {
                snapshot = snapshot(actor, entryId);
                if (snapshot != null) return view(snapshot);
            }
            Map<String, Object> value = calls.entryHealth(entryId, actor);
            cache.put(key, new Snapshot(value, Instant.now()));
            return view(cache.get(key));
        }
    }

    private void refresh(List<Map<String, Object>> entries, Actor actor, boolean force) {
        long now = System.currentTimeMillis();
        long previous = lastRefresh.get();
        List<Long> enabled = entries.stream()
                .filter(e -> "ENABLED".equals(e.get("status")))
                .map(e -> ((Number) e.get("id")).longValue())
                .toList();
        // 页面挂载允许在 TTL 后补探测；但“有未探测条目”时不能被节流挡住，
        // 否则系统状态与健康卡并发加载时，先到的一次会返回 UNKNOWN。
        boolean missing = enabled.stream().anyMatch(id -> snapshot(actor, id) == null);
        if (!force && !missing && now - previous < 5000) return;
        if (!lastRefresh.compareAndSet(previous, now) && !missing && !force) return;
        List<Long> ids;
        if (force) {
            ids = window(enabled);
        } else {
            List<Long> stale = enabled.stream().filter(id -> snapshot(actor, id) == null).toList();
            ids = window(stale);
        }
        if (ids.isEmpty()) return;
        var pool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, ids.size())));
        try {
            for (long id : ids) pool.submit(() -> {
                try {
                    probe(actor, id, force);
                } catch (RuntimeException failure) {
                    // 单条探测失败不能回滚已成功的条目；该条目保持 UNKNOWN，等下一轮刷新重试。
                }
            });
            pool.shutdown();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 超过单次上限时轮转窗口，避免编号靠后的条目永远拿不到探测机会。
     */
    private List<Long> window(List<Long> candidates) {
        if (candidates.isEmpty()) return List.of();
        int limit = Math.min(maxRefresh, candidates.size());
        int start = (int) Math.floorMod(rotation.getAndAdd(limit), (long) candidates.size());
        List<Long> ids = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) ids.add(candidates.get((start + i) % candidates.size()));
        return ids;
    }

    private Snapshot snapshot(Actor actor, long entryId) {
        Snapshot value = cache.get(key(actor, entryId));
        if (value == null || value.at().plus(ttl).isBefore(Instant.now())) return null;
        return value;
    }

    private Map<String, Object> view(Snapshot snapshot) {
        var value = new LinkedHashMap<>(snapshot.value());
        value.put("cached", true);
        value.put("ageSeconds", Math.max(0, Duration.between(snapshot.at(), Instant.now()).toSeconds()));
        return value;
    }

    private Map<String, Object> unknown(Map<String, Object> entry) {
        var value = new LinkedHashMap<String, Object>();
        value.put("entryId", entry.get("id"));
        value.put("providerId", entry.get("providerId"));
        value.put("model", entry.get("modelName"));
        value.put("status", "UNKNOWN");
        value.put("ok", false);
        value.put("stage", "NOT_PROBED");
        value.put("reason", "尚未深度探测；点击“重新探测”执行一次有界检查");
        value.put("retryable", true);
        value.put("checks", List.of());
        value.put("cached", false);
        return value;
    }

    private static String key(Actor actor, long entryId) {
        return actor.tenantId() + ":" + entryId;
    }
}
