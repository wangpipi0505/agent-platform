package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.domain.OperationsRules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Product-facing operations center. Health is read live from every service; run aggregates are
 * read from engine's authority projection; alerts are persisted only when an ADMIN explicitly
 * evaluates the current live snapshot. This is deliberately not a hidden Grafana proxy: the
 * external OTel/Prometheus/Loki stack is deployment infrastructure, while this endpoint is the
 * tenant-scoped product view.
 */
@Service
public class OperationsService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RestClient http;
    private final String engineUrl;
    private final List<Check> checks;
    private final int snapshotRetentionDays;
    private final int alertRetentionDays;

    private record Check(String key, String label, String url) {}

    public OperationsService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl,
            @Value("${agent.services.skill:http://127.0.0.1:18083}") String skillUrl,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl,
            @Value("${agent.services.monitor:http://127.0.0.1:18085}") String monitorUrl,
            @Value("${agent.operations.snapshot-retention-days:30}") int snapshotRetentionDays,
            @Value("${agent.operations.alert-retention-days:90}") int alertRetentionDays) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.engineUrl = engineUrl;
        this.snapshotRetentionDays = Math.max(1, Math.min(365, snapshotRetentionDays));
        this.alertRetentionDays = Math.max(1, Math.min(365, alertRetentionDays));
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1200);
        factory.setReadTimeout(2500);
        this.http = RestClient.builder().requestFactory(factory).build();
        this.checks =
                List.of(
                        new Check("app", "应用管理", appUrl + "/actuator/health"),
                        new Check("engine", "运行时", engineUrl + "/actuator/health"),
                        new Check("skill", "技能注册", skillUrl + "/actuator/health"),
                        new Check("model", "模型网关", modelUrl + "/actuator/health"),
                        new Check("monitor", "监控/审批/统计", monitorUrl + "/actuator/health"));
    }

    public Map<String, Object> overview(int windowMinutes) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        return snapshot(OperationsRules.window(windowMinutes), actor);
    }

    public List<Map<String, Object>> alerts() {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        return rows(actor.tenantId());
    }

    public List<Map<String, Object>> evaluate(int windowMinutes) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        Map<String, Object> live = snapshot(OperationsRules.window(windowMinutes), actor);
        List<OperationsRules.Alert> raised = OperationsRules.evaluate(live);
        tx.execute(
                status -> {
                    for (OperationsRules.Alert alert : raised) {
                        db.update(
                                """
                                insert into monitor.ops_alert
                                    (tenant_id,id,rule_code,subject,severity,title,detail,status)
                                values (?,?,?,?,?,?,?,'OPEN')
                                on conflict (tenant_id,rule_code,subject) where resolved_at is null
                                do update set severity=excluded.severity,title=excluded.title,
                                              detail=excluded.detail,last_seen_at=now()
                                """,
                                actor.tenantId(),
                                UUID.randomUUID().toString(),
                                alert.ruleCode(),
                                alert.subject(),
                                alert.severity(),
                                alert.title(),
                                alert.detail());
                    }
                    Set<String> active = new HashSet<>();
                    for (OperationsRules.Alert alert : raised)
                        active.add(alert.ruleCode() + "\u0000" + alert.subject());
                    for (Map<String, Object> open :
                            db.queryForList(
                                    """
                                    select id,rule_code as "ruleCode",subject from monitor.ops_alert
                                     where tenant_id=? and resolved_at is null
                                    """,
                                    actor.tenantId())) {
                        String key = open.get("ruleCode") + "\u0000" + open.get("subject");
                        if (!active.contains(key))
                            db.update(
                                    "update monitor.ops_alert set status='RESOLVED',resolved_at=now()"
                                            + " where tenant_id=? and id=?",
                                    actor.tenantId(),
                                    open.get("id"));
                    }
                    return null;
                });
        return rows(actor.tenantId());
    }

    public Map<String, Object> acknowledge(String id) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new PlatformException(400, "OPS_ALERT_INVALID", "告警编号无效");
        int changed =
                db.update(
                        "update monitor.ops_alert set status='ACK',acknowledged_at=now(),"
                                + " acknowledged_by=? where tenant_id=? and id=? and status='OPEN'",
                        actor.principalId(),
                        actor.tenantId(),
                        id);
        if (changed == 0) {
            List<Map<String, Object>> existing =
                    db.queryForList(
                            "select status from monitor.ops_alert where tenant_id=? and id=?",
                            actor.tenantId(),
                            id);
            if (existing.isEmpty())
                throw new PlatformException(404, "OPS_ALERT_NOT_FOUND", "告警不存在");
            if (!"ACK".equals(existing.get(0).get("status")))
                throw new PlatformException(409, "OPS_ALERT_NOT_OPEN", "告警已关闭或已确认");
        }
        return rows(actor.tenantId()).stream()
                .filter(row -> id.equals(row.get("id")))
                .findFirst()
                .orElseThrow(() -> new PlatformException(404, "OPS_ALERT_NOT_FOUND", "告警不存在"));
    }

    public Map<String, Object> purge(String confirm) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        if (!"PURGE".equals(confirm))
            throw new PlatformException(400, "OPS_PURGE_CONFIRM_REQUIRED", "请输入 PURGE 确认清理");
        return purgeExpired();
    }

    /** Retention is a real deletion of inactive snapshots and resolved alerts, not a UI hint. */
    @Scheduled(
            fixedDelayString = "${agent.operations.retention-sweep-ms:3600000}",
            initialDelayString = "${agent.operations.retention-initial-delay-ms:300000}")
    public void scheduledPurge() {
        try {
            purgeExpired();
        } catch (RuntimeException ignored) {
            // The next sweep retries; a retention failure must not stop the service.
        }
    }

    private Map<String, Object> purgeExpired() {
        // Both deletes are global by age: an alert must expire even when its tenant has no
        // statistics snapshot row, otherwise retention silently depends on unrelated data.
        int[] deleted = new int[2];
        tx.execute(
                status -> {
                    deleted[0] =
                            db.update(
                                    """
                                    delete from monitor.statistics_snapshot s
                                     where s.created_at < now() - make_interval(days => ?)
                                       and not exists (select 1 from monitor.statistics_active a
                                                        where a.tenant_id=s.tenant_id
                                                          and a.snapshot_id=s.id)
                                    """,
                                    snapshotRetentionDays);
                    deleted[1] =
                            db.update(
                                    """
                                    delete from monitor.ops_alert
                                     where resolved_at is not null
                                       and resolved_at < now() - make_interval(days => ?)
                                    """,
                                    alertRetentionDays);
                    return null;
                });
        return Map.of(
                "snapshotsDeleted", deleted[0],
                "alertsDeleted", deleted[1],
                "snapshotRetentionDays", snapshotRetentionDays,
                "alertRetentionDays", alertRetentionDays);
    }

    private Map<String, Object> snapshot(int windowMinutes, Actor actor) {
        Instant to = Instant.now(), from = to.minus(Duration.ofMinutes(windowMinutes));
        List<Map<String, Object>> health = health();
        Map<String, Object> runs = engineOverview(windowMinutes, actor);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMinutes", windowMinutes);
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("checkedAt", Instant.now().toString());
        result.put("traceId", org.slf4j.MDC.get("traceId"));
        result.put("checks", health);
        result.put("runs", runs);
        result.put(
                "openAlerts",
                db.queryForObject(
                        "select count(*) from monitor.ops_alert where tenant_id=? and status='OPEN'",
                        Long.class,
                        actor.tenantId()));
        result.put(
                "retention",
                Map.of(
                        "snapshotDays", snapshotRetentionDays,
                        "alertDays", alertRetentionDays,
                        "sweepMinutes", 60));
        if (runs == null) result.put("sourceError", "运行时数据源暂不可用");
        return result;
    }

    private Map<String, Object> engineOverview(int windowMinutes, Actor actor) {
        try {
            Map<?, ?> body =
                    http.get()
                            .uri(engineUrl + "/internal/operations/overview?windowMinutes=" + windowMinutes)
                            .header("Authorization", "Bearer " + ServiceTokens.issue(actor))
                            .header(TraceContextFilter.HEADER, TraceContextFilter.current())
                            .retrieve()
                            .body(Map.class);
            if (body == null || !"OK".equals(body.get("code")) || !(body.get("data") instanceof Map<?, ?> data))
                return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) data;
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Map<String, Object>> health() {
        List<Callable<Map<String, Object>>> tasks =
                checks.stream().map(check -> (Callable<Map<String, Object>>) () -> probe(check)).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> futures = executor.invokeAll(tasks, 4, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Future<Map<String, Object>> future = futures.get(i);
                if (future.isCancelled()) {
                    Check check = checks.get(i);
                    result.add(
                            check(
                                    check,
                                    false,
                                    null,
                                    "超过4秒未响应",
                                    0));
                } else {
                    try {
                        result.add(future.get());
                    } catch (Exception e) {
                        Check check = checks.get(i);
                        result.add(check(check, false, null, "健康检查不可用", 0));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (Check check : checks)
                if (result.stream().noneMatch(row -> check.key().equals(row.get("key"))))
                    result.add(check(check, false, null, "健康检查被中断", 0));
        }
        return result;
    }

    private Map<String, Object> probe(Check check) {
        long started = System.nanoTime();
        try {
            var response = http.get().uri(check.url()).retrieve().toBodilessEntity();
            int status = response.getStatusCode().value();
            return check(check, status >= 200 && status < 300, status, "运行正常", elapsed(started));
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            return check(
                    check,
                    false,
                    status,
                    status >= 500 ? "依赖异常（HTTP " + status + "）" : "健康检查拒绝（HTTP " + status + "）",
                    elapsed(started));
        } catch (RuntimeException e) {
            return check(check, false, null, "连接失败", elapsed(started));
        }
    }

    private static Map<String, Object> check(
            Check check, boolean ok, Integer status, String detail, long latencyMs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", check.key());
        result.put("label", check.label());
        result.put("ok", ok);
        result.put("httpStatus", status);
        result.put("latencyMs", latencyMs);
        result.put("detail", detail);
        return result;
    }

    private static long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private List<Map<String, Object>> rows(String tenant) {
        return db.queryForList(
                """
                select id,rule_code as "ruleCode",subject,severity,title,detail,status,
                       first_seen_at as "firstSeenAt",last_seen_at as "lastSeenAt",
                       acknowledged_at as "acknowledgedAt",acknowledged_by as "acknowledgedBy",
                       resolved_at as "resolvedAt"
                  from monitor.ops_alert where tenant_id=?
                 order by last_seen_at desc limit 200
                """,
                tenant);
    }
}
