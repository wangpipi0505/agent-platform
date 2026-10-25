package cn.agentplatform.model.infra;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Model-send grain, separately aggregated before joining trusted engine run dimensions. */
public final class ModelStatistics {
    // A log commits before the send CAS. Only its tenant-scoped durable reservation can
    // prove NO_SEND; missing historical receipts and unresolved/SENT usage stay conservative.
    static final String SEND_FACT_FILTER =
            "not exists(select 1 from model.quota_reservation q where"
                    + " q.tenant_id=l.tenant_id and q.reservation_id=l.quota_receipt->>'id'"
                    + " and q.status='NO_SEND')";
    private final JdbcTemplate db;

    public ModelStatistics(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> query(Object runIds, Actor actor) {
        actor.requireRole("ADMIN");
        actor.requireRole("SERVICE");
        if (!(runIds instanceof List<?> list)
                || list.size() > 10000
                || list.stream()
                        .anyMatch(
                                v -> !(v instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,80}")))
            throw new PlatformException(400, "INVALID_STATISTICS_QUERY", "内部统计需要最多10000个有效运行标识");
        var ids = new LinkedHashSet<>((List<?>) runIds);
        var tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(db.getDataSource())));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(15);
        return tx.execute(
                status -> {
                    List<Map<String, Object>> rows = List.of();
                    if (!ids.isEmpty()) {
                        var args = new ArrayList<Object>();
                        args.add(actor.tenantId());
                        args.addAll(ids);
                        String placeholders =
                                String.join(",", Collections.nCopies(ids.size(), "?"));
                        // Missing usage remains null even when successful. knownTokens is a partial
                        // sum,
                        // never a claimed total; a run with no model sends is represented by no
                        // row.
                        rows =
                                db.queryForList(
                                        "select run_id as \"runId\",count(*) calls,count(*)"
                                            + " filter(where status='SUCCEEDED') succeeded,count(*)"
                                            + " filter(where status='FAILED')"
                                            + " failed,sum(total_tokens) as \"knownTokens\",case"
                                            + " when count(*) filter(where total_tokens is null)=0"
                                            + " then sum(total_tokens) end as"
                                            + " \"totalTokens\",count(*) filter(where total_tokens"
                                            + " is null) as \"usageMissingCalls\",count(*)"
                                            + " filter(where usage_reconciliation='PENDING') as"
                                            + " \"usagePendingCalls\",avg(duration_ms) as"
                                            + " \"averageDurationMs\",percentile_cont(0.95) within"
                                            + " group(order by duration_ms) as \"p95DurationMs\""
                                            + " from model.call_log l where tenant_id=? and "
                                                + SEND_FACT_FILTER
                                                + " and run_id in"
                                                + " ("
                                                + placeholders
                                                + ") group by run_id order by run_id",
                                        args.toArray());
                    }
                    return Map.of(
                            "byRun",
                            rows,
                            "snapshotAt",
                            db.queryForObject("select transaction_timestamp()::text", String.class),
                            "consistency",
                            "REPEATABLE_READ",
                            "grain",
                            "MODEL_SEND");
                });
    }
}
