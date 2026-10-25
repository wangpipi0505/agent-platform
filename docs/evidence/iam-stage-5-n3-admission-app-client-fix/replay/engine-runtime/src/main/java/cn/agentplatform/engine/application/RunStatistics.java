package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Read-only authority projection. Run cohorts and confirmations are aggregated before skill joins.
 */
public final class RunStatistics {
    private final JdbcTemplate db;

    public RunStatistics(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> query(
            String from, String to, String app, String biz, String kind, String groupBy) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        Instant end = parse(to, Instant.now()), start = parse(from, end.minus(Duration.ofDays(30)));
        if (!start.isBefore(end)
                || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0
                || !"day".equals(groupBy)) throw invalid();
        for (String value : Arrays.asList(app, biz))
            if (value != null && (value.isBlank() || value.length() > 128)) throw invalid();
        if (kind != null && !Set.of("FORMAL", "DEBUG", "EVALUATION").contains(kind))
            throw invalid();
        List<Object> args =
                new ArrayList<>(
                        List.of(actor.tenantId(), Timestamp.from(start), Timestamp.from(end)));
        // Business identity comes only from the frozen verified actor, never run.inputs or query
        // identity.
        String business =
                "case when jsonb_typeof(guard_ctx->'attributes'->'bizSystemCode')='array' then case"
                    + " when jsonb_array_length(guard_ctx->'attributes'->'bizSystemCode')=1 then"
                    + " guard_ctx->'attributes'->'bizSystemCode'->>0 end when"
                    + " jsonb_typeof(guard_ctx->'attributes'->'bizSystemCode')='string' then"
                    + " guard_ctx->'attributes'->>'bizSystemCode' end";
        String base =
                "with cohort as (select id,public_id,app_code,"
                        + business
                        + " biz_system_code,case when trigger_type in ('CHAT','TASK') then 'FORMAL'"
                        + " else trigger_type end call_kind,to_char(started_at at time zone"
                        + " 'UTC','YYYY-MM-DD') bucket_day,status,case when ended_at is not null"
                        + " then greatest(0,extract(epoch from (ended_at-started_at))*1000) end"
                        + " duration_ms from engine.run where tenant_id=? and started_at>=? and"
                        + " started_at<?), selected as (select * from cohort where true";
        if (app != null) {
            base += " and app_code=?";
            args.add(app);
        }
        if (biz != null) {
            base += " and biz_system_code=?";
            args.add(biz);
        }
        if (kind != null) {
            base += " and call_kind=?";
            args.add(kind);
        }
        base += ") ";
        final String sql = base;
        var tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(db.getDataSource())));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setReadOnly(true);
        tx.setTimeout(15);
        return tx.execute(
                status -> {
                    var refs =
                            db.queryForList(
                                    sql
                                            + "select public_id as \"runId\",app_code as"
                                            + " \"appCode\",biz_system_code as"
                                            + " \"bizSystemCode\",call_kind as"
                                            + " \"callKind\",bucket_day date from selected order by"
                                            + " public_id limit 10001",
                                    args.toArray());
                    if (refs.size() > 10000) throw limit();
                    String dims =
                            "app_code as \"appCode\",biz_system_code as \"bizSystemCode\",call_kind"
                                + " as \"callKind\",bucket_day date";
                    var groups =
                            db.queryForList(
                                    sql
                                            + "select "
                                            + dims
                                            + ",count(*) runs,count(*) filter(where"
                                            + " status='SUCCEEDED') succeeded,count(*) filter(where"
                                            + " status='FAILED') failed,count(*) filter(where"
                                            + " status='CANCELLED') cancelled,count(*) filter(where"
                                            + " status not in ('SUCCEEDED','FAILED','CANCELLED'))"
                                            + " unfinished,count(*) filter(where"
                                            + " status='SUCCEEDED')::numeric/nullif(count(*),0) as"
                                            + " \"successRate\",avg(duration_ms) as"
                                            + " \"averageDurationMs\",percentile_cont(0.95) within"
                                            + " group(order by duration_ms) as"
                                            + " \"p95DurationMs\",sum((select count(*) from"
                                            + " engine.run_event e where e.run_id=selected.id and"
                                            + " e.type='APPROVAL_DONE')) as \"confirmationCount\""
                                            + " from selected group by"
                                            + " app_code,biz_system_code,call_kind,bucket_day order"
                                            + " by bucket_day,app_code,biz_system_code nulls"
                                            + " first,call_kind limit 2001",
                                    args.toArray());
                    var skillGroups =
                            db.queryForList(
                                    sql
                                            + "select r.app_code as \"appCode\",r.biz_system_code"
                                            + " as \"bizSystemCode\",r.call_kind as"
                                            + " \"callKind\",r.bucket_day date,t.skill_code as"
                                            + " \"skillCode\",t.skill_version_id as"
                                            + " \"skillVersionId\",count(*) calls,count(*)"
                                            + " filter(where t.is_mock) as \"mockCalls\",count(*)"
                                            + " filter(where not t.is_mock and"
                                            + " t.status='SUCCEEDED') succeeded,count(*)"
                                            + " filter(where not t.is_mock and t.status='FAILED')"
                                            + " failed,count(*) filter(where not t.is_mock and"
                                            + " t.status='UNKNOWN') unknown,count(*) filter(where"
                                            + " not t.is_mock and"
                                            + " t.status='SUCCEEDED')::numeric/nullif(count(*)"
                                            + " filter(where not t.is_mock),0) as"
                                            + " \"successRate\",avg(t.duration_ms) filter(where not"
                                            + " t.is_mock) as"
                                            + " \"averageDurationMs\",percentile_cont(0.95) within"
                                            + " group(order by t.duration_ms) filter(where not"
                                            + " t.is_mock) as \"p95DurationMs\" from"
                                            + " engine.tool_call t join selected r on r.id=t.run_id"
                                            + " group by"
                                            + " r.app_code,r.biz_system_code,r.call_kind,r.bucket_day,t.skill_code,t.skill_version_id"
                                            + " order by r.bucket_day,r.app_code,t.skill_code limit"
                                            + " 2001",
                                    args.toArray());
                    if (groups.size() > 2000 || skillGroups.size() > 2000) throw limit();
                    for (var row : refs) row.put("groupKey", key(row));
                    for (var row : groups) row.put("groupKey", key(row));
                    for (var row : skillGroups) row.put("groupKey", key(row));
                    var failures =
                            db.queryForList(
                                    sql
                                            + "select r.public_id as \"runId\",r.app_code as"
                                            + " \"appCode\",coalesce(nullif(e.error->>'semantic',''),'UNCLASSIFIED')"
                                            + " as \"errorSemantic\",'/admin/statistics/runs/'||r.public_id"
                                            + " as href from selected r join engine.run e on"
                                            + " e.id=r.id where r.status='FAILED' order by"
                                            + " r.bucket_day desc,r.public_id limit 50",
                                    args.toArray());
                    var result = new LinkedHashMap<String, Object>();
                    result.put("groups", groups);
                    result.put("skillGroups", skillGroups);
                    result.put("runRefs", refs);
                    result.put("failures", failures);
                    result.put(
                            "snapshotAt",
                            db.queryForObject(
                                    "select transaction_timestamp()::text", String.class));
                    result.put("consistency", "REPEATABLE_READ");
                    result.put("from", start.toString());
                    result.put("to", end.toString());
                    result.put("windowBasis", "RUN_STARTED_AT");
                    result.put("timezone", "UTC");
                    result.put("runGrain", "RUN");
                    result.put("skillGrain", "LOGICAL_SKILL_CALL");
                    result.put("confirmationGrain", "APPROVAL_DONE_EVENT");
                    return result;
                });
    }

    /** Admin may inspect tenant-scoped failure metadata without acquiring another owner's I/O. */
    public Map<String, Object> drilldown(String id) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
        var tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(db.getDataSource())));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(15);
        return tx.execute(
                status -> {
                    var matches =
                            db.queryForList(
                                    "select public_id as \"runId\",app_code as"
                                        + " \"appCode\",status,stop_reason as"
                                        + " \"stopReason\",error->>'semantic' as"
                                        + " \"errorSemantic\",left(error->>'message',1000) as"
                                        + " message,started_at as \"startedAt\",ended_at as"
                                        + " \"endedAt\" from engine.run where tenant_id=? and"
                                        + " public_id=?",
                                    actor.tenantId(),
                                    id);
                    if (matches.isEmpty())
                        throw new PlatformException(404, "STATISTICS_NOT_FOUND", "运行不存在或不在当前租户");
                    var result = matches.getFirst();
                    var calls =
                            db.queryForList(
                                    "select t.id as \"callId\",t.skill_code as"
                                        + " \"skillCode\",t.skill_version_id as"
                                        + " \"skillVersionId\",t.status,t.is_mock as"
                                        + " \"isMock\",t.error_semantic as"
                                        + " \"errorSemantic\",t.duration_ms as"
                                        + " \"actualExecutionDurationMs\" from engine.tool_call t"
                                        + " join engine.run r on r.id=t.run_id where r.tenant_id=?"
                                        + " and r.public_id=? and t.status in ('FAILED','UNKNOWN')"
                                        + " order by t.id limit 100",
                                    actor.tenantId(),
                                    id);
                    result.put("failedSkillCalls", calls);
                    result.put(
                            "failedSkillCallsTotal",
                            db.queryForObject(
                                    "select count(*) from engine.tool_call t join engine.run r on"
                                        + " r.id=t.run_id where r.tenant_id=? and r.public_id=? and"
                                        + " t.status in ('FAILED','UNKNOWN')",
                                    Long.class,
                                    actor.tenantId(),
                                    id));
                    result.put("sampleLimit", 100);
                    result.put(
                            "snapshotAt",
                            db.queryForObject(
                                    "select transaction_timestamp()::text", String.class));
                    return result;
                });
    }

    private static String key(Map<String, Object> row) {
        return Json.write(
                Arrays.asList(
                        row.get("date"),
                        row.get("appCode"),
                        row.get("bizSystemCode"),
                        row.get("callKind")));
    }

    private static Instant parse(String text, Instant fallback) {
        try {
            return text == null || text.isBlank() ? fallback : Instant.parse(text);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static PlatformException invalid() {
        return new PlatformException(
                400, "INVALID_STATISTICS_QUERY", "请选择有效时间（最长366天）、应用、业务系统、调用模式和groupBy=day");
    }

    private static PlatformException limit() {
        return new PlatformException(
                422, "STATISTICS_LIMIT", "统计范围超过10000次运行或2000个分组，请缩短时间或选择应用、业务系统、调用模式");
    }
}
