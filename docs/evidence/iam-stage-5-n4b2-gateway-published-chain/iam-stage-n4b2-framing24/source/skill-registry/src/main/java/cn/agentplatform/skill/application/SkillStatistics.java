package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Statistics read committed execution facts; no queue or client supplied counters can replace them.
 */
public final class SkillStatistics {
    private final JdbcTemplate db;
    private final TransactionTemplate snapshot;

    public SkillStatistics(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(15);
    }

    public Map<String, Object> query(
            String code, String from, String to, String groupBy, Integer version) {
        Actor actor = Actor.current();
        if (code == null
                || !code.matches("[A-Za-z0-9_-]{1,64}")
                || !"day".equals(groupBy)
                || version != null && version < 1) throw invalid();
        Instant end = parse(to, Instant.now()), start = parse(from, end.minus(Duration.ofDays(30)));
        if (!start.isBefore(end)
                || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0)
            throw invalid();
        List<Object> args =
                new ArrayList<>(
                        List.of(
                                actor.tenantId(),
                                code,
                                Timestamp.from(start),
                                Timestamp.from(end)));
        String filter =
                " where f.tenant_id=? and v.skill_code=? and f.created_at>=? and f.created_at<?";
        // Non-admin drilldown and statistics use the same owner boundary as execution records.
        if (!actor.roles().contains("ADMIN")) {
            filter += " and f.principal_id=?";
            args.add(actor.principalId());
        }
        if (version != null) {
            filter += " and v.version_no=?";
            args.add(version);
        }
        String facts =
                "with facts as (select"
                    + " f.*,coalesce(nullif(f.result->'error'->>'semantic',''),'UNCLASSIFIED')"
                    + " semantic from skill.execution_fact f join skill.skill_version v on"
                    + " v.tenant_id=f.tenant_id and v.id=f.skill_version_id"
                        + filter
                        + ") ";
        return snapshot.execute(
                tx -> {
                    // PK(tenant_id,call_id) identifies one logical invocation. Retries accumulate
                    // actual
                    // execution segments in duration_ms; reconciliation waiting never changes that
                    // value.
                    var total =
                            db.queryForMap(
                                    facts
                                            + "select count(*) calls,count(*) filter(where"
                                            + " call_kind='FORMAL') formal,count(*) filter(where"
                                            + " call_kind='DEBUG') debug,count(*) filter(where"
                                            + " call_kind='EVALUATION') evaluation from facts",
                                    args.toArray());
                    var byStatus =
                            db.queryForMap(
                                    facts
                                            + "select count(*) filter(where not is_mock and"
                                            + " status='SUCCEEDED') succeeded,count(*) filter(where"
                                            + " not is_mock and status='FAILED') failed,count(*)"
                                            + " filter(where not is_mock and status='UNKNOWN')"
                                            + " unknown from facts",
                                    args.toArray());
                    var mock =
                            db.queryForMap(
                                    facts + "select count(*) calls from facts where is_mock",
                                    args.toArray());
                    var byDay =
                            db.queryForList(
                                    facts
                                            + "select to_char(created_at at time zone"
                                            + " 'UTC','YYYY-MM-DD') date,count(*) calls,count(*)"
                                            + " filter(where is_mock) as \"mockCalls\",count(*)"
                                            + " filter(where not is_mock and call_kind='FORMAL') as"
                                            + " \"realFormalCalls\",count(*) filter(where not"
                                            + " is_mock and call_kind='FORMAL' and"
                                            + " status='SUCCEEDED')::numeric/nullif(count(*)"
                                            + " filter(where not is_mock and call_kind='FORMAL'),0)"
                                            + " as \"successRate\",percentile_cont(0.95) within"
                                            + " group(order by duration_ms) filter(where not"
                                            + " is_mock and call_kind='FORMAL') p95 from facts"
                                            + " group by 1 order by 1",
                                    args.toArray());
                    byDay.forEach(row -> row.put("succRate", row.get("successRate")));
                    var byMode =
                            db.queryForList(
                                    facts
                                            + "select call_kind as \"callKind\",count(*)"
                                            + " calls,count(*) filter(where is_mock) as"
                                            + " \"mockCalls\",count(*) filter(where not is_mock and"
                                            + " status='SUCCEEDED')::numeric/nullif(count(*)"
                                            + " filter(where not is_mock),0) as"
                                            + " \"successRate\",avg(duration_ms) filter(where not"
                                            + " is_mock) as"
                                            + " \"averageDurationMs\",percentile_cont(0.95) within"
                                            + " group(order by duration_ms) filter(where not"
                                            + " is_mock) as \"p95DurationMs\" from facts group by"
                                            + " call_kind order by call_kind",
                                    args.toArray());
                    var failures =
                            db.queryForList(
                                    facts
                                            + "select semantic as \"errorSemantic\",count(*) count"
                                            + " from facts where not is_mock and status='FAILED'"
                                            + " group by semantic order by count(*) desc,semantic"
                                            + " limit 101",
                                    args.toArray());
                    if (failures.size() > 100)
                        throw new PlatformException(422, "STATISTICS_LIMIT", "失败类型过多，请缩小时间范围");
                    for (var failure : failures) {
                        var sampleArgs = new ArrayList<>(args);
                        sampleArgs.add(failure.get("errorSemantic"));
                        var ids =
                                db.queryForList(
                                        facts
                                                + "select distinct run_id from facts where not"
                                                + " is_mock and status='FAILED' and semantic=?"
                                                + " order by run_id limit 5",
                                        String.class,
                                        sampleArgs.toArray());
                        failure.put("sampleRunIds", ids);
                        String detailPath =
                                actor.roles().contains("ADMIN")
                                        ? "/admin/statistics/runs/"
                                        : "/admin/runs/";
                        failure.put(
                                "sampleLinks",
                                ids.stream()
                                        .map(id -> Map.of("runId", id, "href", detailPath + id))
                                        .toList());
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("total", total);
                    result.put("byStatus", byStatus);
                    result.put("mock", mock);
                    result.put("failures", failures);
                    result.put("byDay", byDay);
                    result.put("byMode", byMode);
                    result.put("from", start.toString());
                    result.put("to", end.toString());
                    result.put("groupBy", "day");
                    result.put("timezone", "UTC");
                    result.put(
                            "snapshotAt",
                            db.queryForObject(
                                    "select transaction_timestamp()::text", String.class));
                    result.put("consistency", "REPEATABLE_READ");
                    result.put("grain", "LOGICAL_SKILL_CALL");
                    result.put("successRateBasis", "REAL_FORMAL_CALLS");
                    return result;
                });
    }

    private static Instant parse(String raw, Instant fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Instant.parse(raw);
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private static PlatformException invalid() {
        return new PlatformException(
                400, "INVALID_STATISTICS_QUERY", "统计需要有效from/to时间、最长366天、groupBy=day与正整数version");
    }
}
