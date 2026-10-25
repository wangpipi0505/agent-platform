package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

@Repository
public class TraceStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public TraceStore(JdbcTemplate db, PlatformTransactionManager tm) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
    }

    public JdbcTemplate db() {
        return db;
    }

    public <T> T atomic(Supplier<T> f) {
        return tx.execute(s -> f.get());
    }

    public Map<String, Object> run(String id, Actor actor) {
        var rows =
                db.queryForList(
                        "select * from engine.run where public_id=? and tenant_id=? and"
                            + " principal_id=?",
                        id,
                        actor.tenantId(),
                        actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "RUN_NOT_FOUND", "运行不存在");
        return rows.getFirst();
    }

    public List<Map<String, Object>> nodes(long id) {
        return db.queryForList(
                "select * from engine.node_instance where run_id=? order by step_seq", id);
    }

    public int event(long run, String type, Object payload) {
        int seq =
                db.queryForObject(
                        "update engine.run set event_seq=event_seq+1 where id=? returning"
                            + " event_seq",
                        Integer.class,
                        run);
        db.update(
                "insert into engine.run_event(run_id,seq,type,payload) values(?,?,?,?::jsonb)",
                run,
                seq,
                type,
                Json.write(payload));
        return seq;
    }

    public List<Map<String, Object>> events(long run, long after) {
        var rows =
                db.queryForList(
                        "select seq,type,payload,created_at from engine.run_event where run_id=?"
                            + " and seq>? order by seq",
                        run,
                        after);
        rows.forEach(r -> r.put("payload", json(r.get("payload"))));
        return rows;
    }

    public Map<String, Object> claim(long node, String owner) {
        return atomic(
                () -> {
                    long runId =
                            db.queryForObject(
                                    "select run_id from engine.node_instance where id=?",
                                    Long.class,
                                    node);
                    // Lock order is run then node, matching cancel/aggregate and seq allocation
                    // (§31.4.5).
                    var run = lockRun(runId);
                    if (!run.get("status").equals("RUNNING")) return null;
                    var row =
                            db.queryForMap(
                                    "select * from engine.node_instance where id=? for update",
                                    node);
                    if (!Set.of("READY", "RUNNING").contains(row.get("status"))) return null;
                    if (row.get("status").equals("RUNNING")
                            && Boolean.TRUE.equals(
                                    db.queryForObject(
                                            "select lease_until>now() from engine.node_instance"
                                                + " where id=?",
                                            Boolean.class,
                                            node))) return null;
                    var rules = json(run.get("snapshot")).path("definition").path("executionRules");
                    int maxParallel =
                            rules.path("parallel").asBoolean(true)
                                    ? rules.path("maxParallel").asInt(8)
                                    : 1;
                    int active =
                            db.queryForObject(
                                    "select count(*) from engine.node_instance where run_id=? and"
                                        + " status='RUNNING' and lease_until>now()",
                                    Integer.class,
                                    runId);
                    if (active >= maxParallel) return null;
                    String generation = owner + "_" + UUID.randomUUID().toString().substring(0, 8);
                    db.update(
                            "update engine.node_instance set"
                                + " status='RUNNING',lease_owner=?,lease_until=now()+interval '90"
                                + " seconds',started_at=coalesce(started_at,now()) where id=?",
                            generation,
                            node);
                    int no =
                            db.queryForObject(
                                    "select coalesce(max(attempt_no),0)+1 from engine.attempt where"
                                            + " node_instance_id=?",
                                    Integer.class,
                                    node);
                    if (no == 1) consume(runId, "steps", 1, 25);
                    long a =
                            db.queryForObject(
                                    "insert into"
                                        + " engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot)"
                                        + " values(?,?,'RUNNING',?,'pending', '{}'::jsonb)"
                                        + " returning id",
                                    Long.class,
                                    node,
                                    no,
                                    Digest.sha256("node:" + node));
                    db.update(
                            "update engine.attempt set request_snapshot_ref=? where id=?",
                            "pg:attempt/" + a,
                            a);
                    event(
                            ((Number) row.get("run_id")).longValue(),
                            "NODE_STARTED",
                            Map.of("nodeInstanceId", node, "attemptId", a, "attemptNo", no));
                    var result =
                            db.queryForMap("select * from engine.node_instance where id=?", node);
                    result.put("attemptId", a);
                    return result;
                });
    }

    public void freeze(long attempt, Object request) {
        db.update(
                "update engine.attempt set request_snapshot=?::jsonb where id=? and"
                    + " status='RUNNING'",
                Json.write(request),
                attempt);
    }

    public void response(long attempt, Object response) {
        db.update(
                "update engine.attempt set response=?::jsonb,response_ref=?,routed_model=?::jsonb"
                    + " where id=?",
                Json.write(response),
                "pg:attempt/" + attempt + "/response",
                Json.write(Json.tree(response).path("actualModel")),
                attempt);
    }

    public boolean commit(long run, long node, long attempt, String owner, Object output) {
        return atomic(
                () -> {
                    lockRun(run);
                    // §31.4.5: only a live RUNNING generation can become the unique logical result.
                    // A zero-row CAS records evidence and emits no logical-success event.
                    int n =
                            db.update(
                                    "update engine.node_instance set"
                                        + " status='SUCCEEDED',committed_attempt_id=?,committed_output_ref=?,output=?::jsonb,output_digest=?,ended_at=now()"
                                        + " where id=? and committed_attempt_id is null and"
                                        + " status='RUNNING' and lease_owner=? and"
                                        + " lease_until>now()",
                                    attempt,
                                    "pg:node/" + node,
                                    Json.write(output),
                                    Digest.sha256(Json.write(output)),
                                    node,
                                    owner);
                    if (n == 0) {
                        db.update(
                                "insert into engine.attempt_note(node_instance_id,attempt_id,kind)"
                                        + " values(?,?,'SUPERSEDED_SUBMIT')",
                                node,
                                attempt);
                        return false;
                    }
                    db.update(
                            "update engine.attempt set status='SUCCEEDED',ended_at=now() where"
                                + " id=?",
                            attempt);
                    event(
                            run,
                            "LOGIC_RESULT_COMMITTED",
                            Map.of("nodeInstanceId", node, "attemptId", attempt));
                    event(
                            run,
                            "NODE_FINISHED",
                            Map.of("nodeInstanceId", node, "status", "SUCCEEDED"));
                    return true;
                });
    }

    public void fail(long run, long node, long attempt, String owner, Object error) {
        atomic(
                () -> {
                    lockRun(run);
                    int n =
                            db.update(
                                    "update engine.node_instance set"
                                        + " status='FAILED',error=?::jsonb,ended_at=now() where"
                                        + " id=? and status='RUNNING' and lease_owner=? and"
                                        + " lease_until>now()",
                                    Json.write(error),
                                    node,
                                    owner);
                    db.update(
                            "update engine.attempt set"
                                + " status='FAILED',error=?::jsonb,ended_at=now() where id=?",
                            Json.write(error),
                            attempt);
                    if (n > 0)
                        event(run, "NODE_FAILED", Map.of("nodeInstanceId", node, "error", error));
                    return null;
                });
    }

    public static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public Map<String, Object> lockRun(long id) {
        return db.queryForMap("select * from engine.run where id=? for update", id);
    }

    public static com.fasterxml.jackson.databind.JsonNode json(Object value) {
        return value == null ? Json.read("{}") : Json.read(value.toString());
    }

    public void consume(long run, String metric, long amount, long defaultLimit) {
        atomic(
                () -> {
                    var row =
                            db.queryForMap(
                                    "select budget,started_at from engine.run where id=? for"
                                        + " update",
                                    run);
                    var budget =
                            (com.fasterxml.jackson.databind.node.ObjectNode)
                                    json(row.get("budget"));
                    String limitName =
                            switch (metric) {
                                case "steps" -> "maxSteps";
                                case "tokens" -> "totalTokens";
                                case "depth" -> "maxDepth";
                                default -> metric;
                            };
                    long limit = budget.path("limits").path(limitName).asLong(defaultLimit),
                            current = budget.path("consumed").path(metric).asLong();
                    if (amount < 0 || current + amount > limit)
                        throw new PlatformException(
                                409, "BUDGET_" + metric.toUpperCase(), "运行预算已耗尽：" + metric);
                    if (row.get("started_at") instanceof java.sql.Timestamp started
                            && System.currentTimeMillis() - started.getTime()
                                    > budget.path("limits").path("totalDurationMs").asLong(600000))
                        throw new PlatformException(409, "BUDGET_DURATION", "运行时间预算已耗尽");
                    if (!budget.path("consumed").isObject()) budget.set("consumed", Json.object());
                    ((com.fasterxml.jackson.databind.node.ObjectNode) budget.path("consumed"))
                            .put(metric, current + amount);
                    db.update(
                            "update engine.run set budget=?::jsonb where id=?",
                            Json.write(budget),
                            run);
                    return null;
                });
    }
}
