package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

@RestController
public class RunController {
    private final RunService service;
    private final TraceStore store;
    private final ChunkBus chunks;
    private final ApprovalService approvals;

    public RunController(RunService s, TraceStore t, ChunkBus c, ApprovalService a) {
        service = s;
        store = t;
        chunks = c;
        approvals = a;
    }

    @PostMapping("/admin/debug-runs")
    public Object debug(@RequestBody JsonNode req) {
        return ApiResponse.ok(
                service.create(req.path("appCode").asText(), req, "DEBUG", Actor.current()));
    }

    @PostMapping({"/admin/apps/{code}/runs", "/v1/apps/{code}/runs"})
    public Object run(@PathVariable String code, @RequestBody JsonNode req) {
        if (service.iamEnabled()) {
            var attributes =
                    org.springframework.web.context.request.RequestContextHolder
                            .getRequestAttributes();
            if (!(attributes
                            instanceof
                            org.springframework.web.context.request.ServletRequestAttributes
                                    servlet)
                    || !"POST".equals(servlet.getRequest().getMethod())
                    || !"/admin/apps/{code}/runs"
                            .equals(
                                    servlet.getRequest()
                                            .getAttribute(
                                                    org.springframework.web.servlet.HandlerMapping
                                                            .BEST_MATCHING_PATTERN_ATTRIBUTE)))
                throw new PlatformException(403, "IAM_ADMISSION_ROUTE_DENIED", "当前受理入口未迁移");
        }
        return ApiResponse.ok(service.create(code, req, "TASK", Actor.current()));
    }

    @PostMapping("/internal/evaluations/run")
    public Object eval(@RequestBody JsonNode req) {
        Actor actor = Actor.current();
        actor.requireRole("SERVICE");
        return ApiResponse.ok(
                service.create(req.path("appCode").asText(), req, "EVALUATION", actor));
    }

    @GetMapping("/admin/runs")
    public Object list() {
        return ApiResponse.ok(service.list(Actor.current()));
    }

    @GetMapping({"/admin/runs/{id}", "/admin/debug-runs/{id}/status", "/v1/runs/{id}"})
    public Object status(@PathVariable String id) {
        return ApiResponse.ok(service.status(id, Actor.current()));
    }

    @PostMapping("/admin/debug-runs/{id}/resume")
    public Object resume(@PathVariable String id, @RequestBody JsonNode req) {
        return ApiResponse.ok(service.resume(id, req, Actor.current()));
    }

    @PostMapping({
        "/admin/debug-runs/{id}/cancel",
        "/admin/runs/{id}/cancel",
        "/v1/runs/{id}/cancel"
    })
    public Object cancel(@PathVariable String id) {
        return ApiResponse.ok(service.cancel(id, Actor.current()));
    }

    @GetMapping("/admin/debug-runs/{id}/variables")
    public Object variables(@PathVariable String id) {
        var run = store.run(id, Actor.current());
        return ApiResponse.ok(
                Map.of(
                        "inputs",
                        TraceStore.json(run.get("inputs")),
                        "nodes",
                        service.status(id, Actor.current()).get("nodes")));
    }

    @GetMapping("/admin/debug-runs/{id}/node/{nid}/prompt")
    public Object prompt(@PathVariable String id, @PathVariable long nid) {
        var run = store.run(id, Actor.current());
        var rows =
                store.db()
                        .queryForList(
                                "select a.request_snapshot,a.response,a.checkpoint from"
                                    + " engine.attempt a join engine.node_instance n on"
                                    + " n.id=a.node_instance_id where n.run_id=? and n.id=? order"
                                    + " by a.attempt_no desc limit 1",
                                run.get("id"),
                                nid);
        if (rows.isEmpty())
            return ApiResponse.ok(
                    Map.of("messages", List.of(), "manifest", Map.of("state", "NOT_ASSEMBLED")));
        return ApiResponse.ok(
                rows.getFirst().entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey, e -> TraceStore.json(e.getValue()))));
    }

    @GetMapping(
            value = {
                "/admin/runs/{id}/events",
                "/admin/debug-runs/{id}/events",
                "/v1/runs/{id}/events"
            },
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.http.ResponseEntity<StreamingResponseBody> events(
            @PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String cursor,
            @RequestParam(value = "Last-Event-ID", required = false) Long queryCursor) {
        Actor actor = Actor.current();
        var run = store.run(id, actor);
        long rid = TraceStore.number(run, "id"),
                after =
                        queryCursor != null
                                ? queryCursor
                                : cursor == null ? 0 : Long.parseLong(cursor);
        if (after < 0) throw new PlatformException(400, "EVENT_CURSOR", "事件游标不能为负数");
        var subscription = chunks.subscribe(rid);
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof
                org.springframework.web.context.request.ServletRequestAttributes attributes) {
            org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(
                            attributes.getRequest())
                    .registerCallableInterceptor(
                            subscription,
                            new org.springframework.web.context.request.async
                                    .CallableProcessingInterceptor() {
                                @Override
                                public <T> void afterCompletion(
                                        org.springframework.web.context.request.NativeWebRequest
                                                request,
                                        java.util.concurrent.Callable<T> task) {
                                    // Submission rejection/timeout before writer start must release
                                    // admission too.
                                    if (started.compareAndSet(false, true)) subscription.close();
                                }
                            });
        }
        // MVC invokes this writer only after response initialization: no SseEmitter early-send
        // cache.
        // Admission precedes task submission; the one writer keeps its slot until actual exit.
        StreamingResponseBody body =
                output -> {
                    if (!started.compareAndSet(false, true)) return;
                    long last = after;
                    long until =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(55);
                    long nextPoll = 0;
                    try (subscription) {
                        var initial =
                                store.db()
                                        .queryForMap(
                                                "select status,event_seq,(select min(seq) from"
                                                    + " engine.run_event where run_id=r.id) as"
                                                    + " oldest_seq from engine.run r where id=?",
                                                rid);
                        Object oldest = initial.get("oldest_seq");
                        long watermark = ((Number) initial.get("event_seq")).longValue();
                        if ((oldest instanceof Number n
                                        ? last < n.longValue() - 1
                                        : last < watermark)
                                || last > watermark) {
                            send(
                                    output,
                                    null,
                                    "stream_reset",
                                    Json.write(
                                            Map.of(
                                                    "reason",
                                                    last > watermark
                                                            ? "CURSOR_AHEAD"
                                                            : "CURSOR_EXPIRED",
                                                    "eventsWatermark",
                                                    watermark,
                                                    "status",
                                                    initial.get("status"),
                                                    "recovery",
                                                    "FINAL_SNAPSHOT",
                                                    "chunkReplay",
                                                    false)));
                            return;
                        }
                        send(
                                output,
                                null,
                                "stream_status",
                                Json.write(
                                        Map.of(
                                                "chunkAvailable",
                                                chunks.chunkAvailable(),
                                                "chunkReplay",
                                                false,
                                                "recovery",
                                                "LAST_EVENT_ID_AND_FINAL_SNAPSHOT")));
                        while (!Thread.currentThread().isInterrupted()
                                && System.nanoTime() < until) {
                            if (subscription.stopped()) {
                                send(
                                        output,
                                        null,
                                        "stream_reset",
                                        Json.write(
                                                Map.of(
                                                        "reason",
                                                        subscription.resetReason(),
                                                        "chunkReplay",
                                                        false,
                                                        "lastEventId",
                                                        last,
                                                        "recovery",
                                                        "LAST_EVENT_ID_AND_FINAL_SNAPSHOT")));
                                break;
                            }
                            if (System.nanoTime() >= nextPoll) {
                                // Reserve globally before PG read, including bytes retained by a
                                // blocked
                                // send.
                                try (var reservation = chunks.reserveResponse()) {
                                    if (reservation != null) {
                                        var events =
                                                store.db()
                                                        .queryForList(
                                                                "select seq,type,case when"
                                                                    + " octet_length(payload::text)<=?"
                                                                    + " then payload::text else"
                                                                    + " null end as payload from"
                                                                    + " engine.run_event where"
                                                                    + " run_id=? and seq>? order by"
                                                                    + " seq limit 1",
                                                                ChunkBus.MAX_EVENT_BYTES,
                                                                rid,
                                                                last);
                                        if (!events.isEmpty()) {
                                            var event = events.getFirst();
                                            if (event.get("payload") == null) {
                                                send(
                                                        output,
                                                        null,
                                                        "stream_reset",
                                                        Json.write(
                                                                Map.of(
                                                                        "reason",
                                                                        "EVENT_LIMIT",
                                                                        "lastEventId",
                                                                        last,
                                                                        "recovery",
                                                                        "FINAL_SNAPSHOT",
                                                                        "chunkReplay",
                                                                        false)));
                                                break;
                                            }
                                            long seq = ((Number) event.get("seq")).longValue();
                                            send(
                                                    output,
                                                    seq,
                                                    event.get("type").toString(),
                                                    event.get("payload").toString());
                                            last = seq;
                                            continue;
                                        }
                                        var state =
                                                store.db()
                                                        .queryForMap(
                                                                "select status,event_seq,(select"
                                                                    + " min(seq) from"
                                                                    + " engine.run_event where"
                                                                    + " run_id=r.id) as oldest_seq"
                                                                    + " from engine.run r where"
                                                                    + " id=?",
                                                                rid);
                                        long stateWatermark =
                                                ((Number) state.get("event_seq")).longValue();
                                        Object retained = state.get("oldest_seq");
                                        // Retention may clear history after the initial snapshot.
                                        // A status watermark is not a consumed fact cursor.
                                        if (retained instanceof Number n
                                                ? last < n.longValue() - 1
                                                : last < stateWatermark) {
                                            send(
                                                    output,
                                                    null,
                                                    "stream_reset",
                                                    Json.write(
                                                            Map.of(
                                                                    "reason",
                                                                    "CURSOR_EXPIRED",
                                                                    "eventsWatermark",
                                                                    stateWatermark,
                                                                    "status",
                                                                    state.get("status"),
                                                                    "recovery",
                                                                    "FINAL_SNAPSHOT",
                                                                    "chunkReplay",
                                                                    false)));
                                            break;
                                        }
                                        // A terminal commit may follow the event query: drain its
                                        // PG
                                        // watermark first.
                                        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED")
                                                        .contains(state.get("status"))
                                                && last
                                                        >= ((Number) state.get("event_seq"))
                                                                .longValue()) break;
                                        if (last < ((Number) state.get("event_seq")).longValue())
                                            continue;
                                        nextPoll =
                                                System.nanoTime()
                                                        + java.util.concurrent.TimeUnit.MILLISECONDS
                                                                .toNanos(450);
                                    }
                                }
                            }
                            try (var frame = subscription.poll()) {
                                if (frame != null) {
                                    send(
                                            output,
                                            null,
                                            "chunk",
                                            Json.write(Map.of("text", frame.text())));
                                    continue;
                                }
                            }
                            Thread.sleep(20);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (java.io.IOException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new java.io.IOException("事件流中断，请按事实游标和最终快照恢复", failure);
                    }
                };
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private static void send(java.io.OutputStream output, Long seq, String type, String payload)
            throws java.io.IOException {
        if (!type.matches("[A-Za-z_]{1,64}")) throw new java.io.IOException("invalid event type");
        if (seq != null)
            output.write(("id:" + seq + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(("event:" + type + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // PG JSONB text contains escaped string newlines, never raw framing separators.
        output.write("data:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write("\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.flush();
    }

    @PostMapping("/internal/runs/{id}/approvals/{nid}")
    public Object approval(
            @PathVariable String id, @PathVariable long nid, @RequestBody JsonNode req) {
        return ApiResponse.ok(approvals.approve(id, nid, req, Actor.current()));
    }

    @GetMapping("/internal/approvals")
    public Object approvals() {
        return ApiResponse.ok(approvals.list(Actor.current()));
    }

    @GetMapping("/internal/statistics")
    public Object statistics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String appCode,
            @RequestParam(required = false) String bizSystemCode,
            @RequestParam(required = false) String callKind,
            @RequestParam(defaultValue = "day") String groupBy) {
        return ApiResponse.ok(
                new RunStatistics(store.db())
                        .query(from, to, appCode, bizSystemCode, callKind, groupBy));
    }

    @GetMapping("/internal/statistics/runs/{id}")
    public Object statisticsRun(@PathVariable String id) {
        return ApiResponse.ok(new RunStatistics(store.db()).drilldown(id));
    }

    @GetMapping("/internal/audit")
    public Object audit() {
        var rows =
                store.db()
                        .queryForList(
                                "select r.public_id as \"runId\",r.principal_id as"
                                    + " \"principalId\",e.seq,e.type,e.payload,e.created_at as"
                                    + " \"createdAt\" from engine.run_event e join engine.run r on"
                                    + " r.id=e.run_id where r.tenant_id=? order by e.id desc limit"
                                    + " 500",
                                Actor.current().tenantId());
        rows.forEach(r -> r.put("payload", TraceStore.json(r.get("payload"))));
        return ApiResponse.ok(rows);
    }

    @PostMapping("/internal/graphs/validate")
    public Object validateGraph(@RequestBody JsonNode req) {
        var errors =
                new cn.agentplatform.engine.domain.GraphValidator()
                        .validate(req.path("definition"));
        return ApiResponse.ok(Map.of("valid", errors.isEmpty(), "errors", errors));
    }
}
