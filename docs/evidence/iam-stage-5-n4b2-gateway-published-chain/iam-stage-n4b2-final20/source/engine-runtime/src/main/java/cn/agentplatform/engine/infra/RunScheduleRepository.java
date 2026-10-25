package cn.agentplatform.engine.infra;

import cn.agentplatform.engine.domain.RunDispatch;
import cn.agentplatform.engine.domain.RunDispatch.Queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.*;

/** PG16 queue locking: short run-row transactions; never hold these locks over external work. */
@Repository
public class RunScheduleRepository {
    private final TraceStore store;
    private final boolean protectedOnly;

    @Autowired
    public RunScheduleRepository(TraceStore store) {
        this(store, false);
    }

    /** Explicit isolated IAM assembly; production IAM deployment remains closed. */
    public RunScheduleRepository(TraceStore store, boolean protectedOnly) {
        this.store = store;
        this.protectedOnly = protectedOnly;
    }

    public RunDispatch claimNext(Queue queue, String owner, Duration lease) {
        return claim(null, queue, owner, lease);
    }

    public RunDispatch claimSpecific(long id, Queue queue, String owner, Duration lease) {
        return claim(id, queue, owner, lease);
    }

    private RunDispatch claim(Long id, Queue queue, String owner, Duration lease) {
        return store.atomic(
                () -> {
                    String kind =
                            queue == Queue.BATCH
                                    ? "r.trigger_type='EVALUATION'"
                                    : "r.trigger_type in ('CHAT','TASK','DEBUG')";
                    String specific = id == null ? "true" : "r.id=?";
                    var args = new ArrayList<Object>();
                    if (id != null) args.add(id);
                    args.add(store.authorityNow());
                    args.add(store.authorityNow());
                    var rows =
                            store.db()
                                    .queryForList(
                                            "select"
                                                + " r.id,r.public_id,r.status,r.trigger_type,r.dispatch_generation,(a.run_id"
                                                + " is not null) as protected from engine.run r"
                                                + " left join engine.run_authority a on"
                                                + " a.run_id=r.id where r.status in"
                                                + " ('PENDING','RUNNING') and "
                                                    + kind
                                                    + " and "
                                                    + specific
                                                    + " and "
                                                    + (id == null
                                                            ? "r.dispatch_next_at<=clock_timestamp()"
                                                            : "(a.run_id is null or"
                                                                  + " r.dispatch_next_at<=clock_timestamp())")
                                                    + " and "
                                                    + (protectedOnly
                                                            ? "a.run_id is not null and "
                                                            : "")
                                                    + " (a.run_id is null or (a.grant_expires_at>?"
                                                    + " and (a.blocked_reason is null or"
                                                    + " (a.blocked_reason='AUTHORITY_UNAVAILABLE'"
                                                    + " and a.retry_not_before<=?)))) and"
                                                    + " (r.dispatch_until is null or"
                                                    + " r.dispatch_until<=clock_timestamp()) and"
                                                    + " (exists(select 1 from engine.node_instance"
                                                    + " n where n.run_id=r.id and (n.status='READY'"
                                                    + " or (n.status='RUNNING' and (n.lease_until"
                                                    + " is null or"
                                                    + " n.lease_until<=clock_timestamp())))) or"
                                                    + " (a.run_id is not null and"
                                                    + " r.status='RUNNING' and exists(select 1 from"
                                                    + " engine.node_instance n where n.run_id=r.id)"
                                                    + " and not exists(select 1 from"
                                                    + " engine.node_instance n where n.run_id=r.id"
                                                    + " and n.status not in"
                                                    + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED'))))"
                                                    + " order by r.dispatch_next_at,r.id limit 1"
                                                    + " for update of r skip locked",
                                            args.toArray());
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    long rid = TraceStore.number(row, "id"),
                            generation = TraceStore.number(row, "dispatch_generation") + 1;
                    store.db()
                            .update(
                                    "update engine.run set"
                                        + " dispatch_owner=?,dispatch_until=clock_timestamp()+(? *"
                                        + " interval '1"
                                        + " millisecond'),dispatch_generation=?,status=case when ?"
                                        + " then status else 'RUNNING' end where id=?",
                                    owner,
                                    lease.toMillis(),
                                    generation,
                                    Boolean.TRUE.equals(row.get("protected")),
                                    rid);
                    if (row.get("status").equals("PENDING")
                            && !Boolean.TRUE.equals(row.get("protected")))
                        store.event(
                                rid,
                                "RUN_STARTED",
                                Map.of(
                                        "runId",
                                        row.get("public_id"),
                                        "triggerType",
                                        row.get("trigger_type")));
                    return new RunDispatch(rid, owner, generation, queue);
                });
    }

    public boolean renew(RunDispatch lease, Duration duration) {
        // Busy rows receive no extension. The locking selection retains every authority/expiry
        // predicate, so skipping a heartbeat can never revive an expired or foreign generation.
        return store.db()
                        .update(
                                "with renewable as (select id from engine.run where id=? and"
                                    + " dispatch_owner=? and dispatch_generation=? and"
                                    + " dispatch_until>clock_timestamp() and status not in"
                                    + " ('SUCCEEDED','FAILED','CANCELLED') for update skip locked)"
                                    + " update engine.run r set dispatch_until=clock_timestamp()+(?"
                                    + " * interval '1 millisecond') from renewable n where"
                                    + " r.id=n.id",
                                lease.runId(),
                                lease.owner(),
                                lease.generation(),
                                duration.toMillis())
                == 1;
    }

    public boolean valid(RunDispatch lease) {
        return Boolean.TRUE.equals(
                store.db()
                        .queryForObject(
                                "select exists(select 1 from engine.run where id=? and"
                                        + " dispatch_owner=? and dispatch_generation=? and"
                                        + " dispatch_until>clock_timestamp() and status='RUNNING')",
                                Boolean.class,
                                lease.runId(),
                                lease.owner(),
                                lease.generation()));
    }

    public boolean release(RunDispatch lease, Duration delay) {
        return store.db()
                        .update(
                                "update engine.run set"
                                    + " dispatch_owner=null,dispatch_until=null,dispatch_next_at=now()+(?"
                                    + " * interval '1 millisecond') where id=? and dispatch_owner=?"
                                    + " and dispatch_generation=?",
                                delay.toMillis(),
                                lease.runId(),
                                lease.owner(),
                                lease.generation())
                == 1;
    }

    public void wake(long id) {
        store.db()
                .update(
                        "update engine.run set dispatch_next_at=now() where id=? and status not in"
                                + " ('SUCCEEDED','FAILED','CANCELLED')",
                        id);
    }

    public Queue queue(long id) {
        return "EVALUATION"
                        .equals(
                                store.db()
                                        .queryForObject(
                                                "select trigger_type from engine.run where id=?",
                                                String.class,
                                                id))
                ? Queue.BATCH
                : Queue.INTERACTIVE;
    }
}
