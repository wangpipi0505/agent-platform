package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Read-only operations projection over the run authority. Every number is computed from
 * engine.run/attempt/tool_call at statement snapshot time; this class never writes a metric,
 * cache or synthetic sample. A single REPEATABLE READ transaction keeps the cards mutually
 * consistent, while the timestamp identifies the source snapshot rather than claiming a
 * cluster-wide one.
 */
public final class RunOperations {
    private final JdbcTemplate db;

    public RunOperations(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> overview(int windowMinutes) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        if (windowMinutes < 5 || windowMinutes > 1440)
            throw new PlatformException(400, "OPS_WINDOW_INVALID", "时间窗口必须在5到1440分钟之间");

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        var tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        tx.setTimeout(15);
        return tx.execute(
                status -> {
                    Object[] window = {actor.tenantId(), Timestamp.from(from), Timestamp.from(to)};
                    Map<String, Object> runs =
                            db.queryForMap(
                                    """
                                    select count(*) as total,
                                           count(*) filter (where status='SUCCEEDED') as succeeded,
                                           count(*) filter (where status='FAILED') as failed,
                                           count(*) filter (where status='CANCELLED') as cancelled,
                                           count(*) filter (where status not in ('SUCCEEDED','FAILED','CANCELLED')) as unfinished,
                                           coalesce(avg(duration_ms),0) as "averageDurationMs",
                                           percentile_cont(0.5) within group (order by duration_ms) as "p50DurationMs",
                                           percentile_cont(0.95) within group (order by duration_ms) as "p95DurationMs"
                                      from (select status,
                                                   case when ended_at is not null
                                                        then greatest(0, extract(epoch from (ended_at-started_at))*1000)
                                                   end as duration_ms
                                              from engine.run
                                             where tenant_id=? and started_at>=? and started_at<?) s
                                    """,
                                    window);
                    long completed =
                            number(runs.get("succeeded"))
                                    + number(runs.get("failed"))
                                    + number(runs.get("cancelled"));
                    runs.put("completed", completed);
                    runs.put("failureRate", rate(number(runs.get("failed")), completed));
                    runs.put("unfinishedRate", rate(number(runs.get("unfinished")), number(runs.get("total"))));

                    Map<String, Object> active =
                            db.queryForMap(
                                    """
                                    select count(*) filter (where status='RUNNING') as running,
                                           count(*) filter (where status='PENDING') as pending,
                                           count(*) filter (where status not in ('SUCCEEDED','FAILED','CANCELLED')) as active
                                      from engine.run where tenant_id=?
                                    """,
                                    actor.tenantId());
                    Map<String, Object> model =
                            db.queryForMap(
                                    """
                                    select count(*) as calls,
                                           count(*) filter (where a.error is not null) as failed
                                      from engine.attempt a
                                      join engine.node_instance n on n.id=a.node_instance_id
                                      join engine.run r on r.id=n.run_id
                                     where r.tenant_id=? and a.started_at>=? and a.started_at<?
                                    """,
                                    window);
                    Map<String, Object> tools =
                            db.queryForMap(
                                    """
                                    select count(*) as calls,
                                           count(*) filter (where t.status='FAILED') as failed,
                                           count(*) filter (where t.status='UNKNOWN') as unknown
                                      from engine.tool_call t join engine.run r on r.id=t.run_id
                                     where r.tenant_id=? and t.created_at>=? and t.created_at<?
                                    """,
                                    window);
                    List<Map<String, Object>> topErrors =
                            db.queryForList(
                                    """
                                    select coalesce(nullif(error->>'semantic',''),'UNCLASSIFIED') as semantic,
                                           count(*) as count
                                      from engine.run
                                     where tenant_id=? and status='FAILED' and started_at>=? and started_at<?
                                     group by 1 order by count desc, semantic limit 5
                                    """,
                                    window);
                    List<Map<String, Object>> recentFailures =
                            db.queryForList(
                                    """
                                    select public_id as "runId", app_code as "appCode",
                                           coalesce(nullif(error->>'semantic',''),'UNCLASSIFIED') as semantic,
                                           left(coalesce(nullif(error->>'message',''),''),300) as message,
                                           ended_at as "endedAt"
                                      from engine.run
                                     where tenant_id=? and status='FAILED' and started_at>=? and started_at<?
                                     order by ended_at desc nulls last limit 10
                                    """,
                                    window);
                    String sourceSnapshot =
                            db.queryForObject("select transaction_timestamp()::text", String.class);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("windowMinutes", windowMinutes);
                    result.put("from", from.toString());
                    result.put("to", to.toString());
                    result.put("sourceSnapshot", sourceSnapshot);
                    result.put("runs", runs);
                    result.put("active", active);
                    result.put("model", model);
                    result.put("tools", tools);
                    result.put("topErrors", topErrors);
                    result.put("recentFailures", recentFailures);
                    return result;
                });
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Double rate(long part, long total) {
        return total == 0 ? null : Math.round(part * 10000.0 / total) / 10000.0;
    }
}
