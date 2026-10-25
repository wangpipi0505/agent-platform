package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Private authorized CHAT fixture. Only public signed admission APIs create retained authority. */
final class InitialSenderIntegrationFixture implements AutoCloseable {
    final FrozenModelBindingProducerFixture producer;
    final TraceStore trace;
    final RunAuthority authority;
    RuntimeClients clients;
    RunScheduler scheduler;
    RunService service;
    ChunkBus chunks;
    final Path dir = Path.of(System.getProperty("initial.fixture"));
    long run;
    String mode = "normal";
    boolean faultUsed;
    boolean outcomeFaultUsed;
    final List<String> observed = new ArrayList<>();
    final java.util.concurrent.atomic.AtomicReference<ChunkBus.Subscription> subscriber = new java.util.concurrent.atomic.AtomicReference<>();

    InitialSenderIntegrationFixture() throws Exception {
        producer = new FrozenModelBindingProducerFixture(
                Path.of(System.getProperty("initial.fixture"), "engine"));
        RuntimeClients opened = null;
        RunScheduler worker = null;
        try {
            var transactions = new DataSourceTransactionManager(producer.ds) {
                @Override protected void prepareForCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                    if (!outcomeFaultUsed && mode.equals("lease-outcome-commit")
                            && producer.db.queryForObject("select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=? and jsonb_exists(a.checkpoint,'initialFailure')",Integer.class,run)==1) {
                        outcomeFaultUsed=true;
                        producer.db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=?",run);
                    }
                    super.prepareForCommit(status);
                }
                @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                    if (!faultUsed && (mode.equals("rollback") || mode.equals("ack-loss"))
                            && producer.db.queryForObject("select count(*) from engine.model_invocation where run_id=?",Integer.class,run)==1) {
                        faultUsed=true;
                        if(mode.equals("rollback")) { super.doRollback(status); throw new org.springframework.transaction.TransactionSystemException("REAL_PG_ROLLBACK"); }
                        super.doCommit(status);
                        throw new org.springframework.transaction.TransactionSystemException("REAL_PG_COMMIT_ACK_LOST");
                    }
                    super.doCommit(status);
                }
            };
            trace = new TraceStore(producer.db, transactions, producer.iam.clock()) {
                @Override public Map<String,Object> dispatchInitial(cn.agentplatform.engine.domain.RunDispatch dispatch,
                        String worker, cn.agentplatform.engine.domain.ModelInputManifest manifest, byte[] raw,
                        HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                    var result = super.dispatchInitial(dispatch, worker, manifest, raw, proof);
                    if (!faultUsed) {
                        faultUsed = true;
                        if (mode.equals("revoke-before-send")) producer.iam.revoked(true);
                        if (mode.equals("outage-before-send")) producer.iam.unavailable(true);
                        if (mode.equals("lease-before-send")) producer.db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=?", run);
                        if (mode.equals("dispatch-before-send")) producer.db.update("update engine.run set dispatch_generation=dispatch_generation+1 where id=?", run);
                    }
                    return result;
                }
                @Override public void commitInitial(cn.agentplatform.engine.domain.RunDispatch dispatch, Map<String,Object> claim,
                        HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                    if (mode.equals("response-recovery") || mode.equals("duration-response")) { mode="normal"; throw new IllegalStateException("RESTART_BEFORE_LOGICAL_COMMIT"); }
                    super.commitInitial(dispatch,claim,proof);
                }
                @Override public void responseInitial(cn.agentplatform.engine.domain.RunDispatch dispatch, Map<String,Object> claim,
                        JsonNode response, HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                    if (mode.equals("duration-response")) {
                        producer.iam.advanceClock(Duration.ofSeconds(31));
                        proof = authority.currentRetained(run);
                    }
                    super.responseInitial(dispatch,claim,response,proof);
                    if (mode.equals("revoke-response")) producer.iam.revoked(true);
                    if (mode.equals("lease-response")) producer.db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=?",run);
                }
            };
            authority = new RunAuthority(producer.runs, producer.engine, producer.iam.clock(), Duration.ofSeconds(1));
        } catch (Exception | Error failure) {
            if (worker != null) try { worker.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            if (opened != null) try { opened.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            try { producer.close(); } catch (Throwable close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    void assemble() throws Exception {
        if (service != null) return;
        String modelOrigin = Json.read(Files.readString(dir.resolve("model-ready.json"))).path("origin").asText();
        clients = new RuntimeClients(producer.iam.config("engine"), producer.engine, "http://127.0.0.1:1", true, modelOrigin);
        scheduler = new RunScheduler(new RunScheduleRepository(trace) {
            @Override public cn.agentplatform.engine.domain.RunDispatch claimNext(cn.agentplatform.engine.domain.RunDispatch.Queue queue, String owner, Duration lease) {
                return run == 0 ? null : super.claimSpecific(run,queue,owner,lease);
            }
        }, 1, 1, 100, 30000, 1000) {
            @Override public void bind(java.util.function.LongConsumer work,Runnable renew,Runnable ready) {
                super.bind(id -> {
                    try {work.accept(id);}
                    catch(RuntimeException failure) {
                        try { Files.writeString(dir.resolve("recovery-private-error.txt"),failure.getClass().getName()+Arrays.toString(failure.getStackTrace())+(failure.getCause()==null?"":failure.getCause().getClass().getName()+Arrays.toString(failure.getCause().getStackTrace()))); }
                        catch(java.io.IOException write) {failure.addSuppressed(write);}
                        throw failure;
                    }
                },renew,ready);
            }
        };
        chunks = new ChunkBus() {
            @Override public void publish(long id, String text) {
                super.publish(id, text);
                var s = subscriber.get();
                if (s == null) throw new AssertionError("ACTUAL_OBSERVER_REQUIRED");
                try (var frame = s.poll()) {
                    if (frame == null) throw new AssertionError("CHUNK_NOT_DELIVERED");
                    observed.add(frame.text());
                    if (mode.equals("revoke-chunk")) producer.iam.revoked(true);
                    if (mode.equals("outage-chunk")) producer.iam.unavailable(true);
                    if (mode.equals("lease-chunk")) producer.db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=?", run);
                    if (mode.equals("cancel-chunk")) producer.db.update("update engine.run set status='CANCELLED',stop_reason='USER_CANCEL',ended_at=clock_timestamp() where id=?",run);
                    if (mode.equals("callback")) throw new IllegalStateException("OBSERVER_DISCONNECTED");
                    if (!Files.exists(dir.resolve("engine-first-observed"))) Files.writeString(dir.resolve("engine-first-observed"), java.time.Instant.now().toString());
                    if (Files.exists(dir.resolve("hold-final"))) Files.writeString(dir.resolve("allow-final"), "observed");
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        };
        service = new RunService(trace, clients, null, null, scheduler, authority, producer.runs,
                producer.iam.clock(), producer.materials, chunks);
    }

    Map<String, Object> drive() throws Exception {
        assemble();
        if (Files.exists(dir.resolve("mode"))) mode=Files.readString(dir.resolve("mode")).trim();
        if (run == 0) run = create();
        if (mode.equals("oversized-scope")) {
            producer.db.execute("create function engine.eisf_scope_fault() returns trigger language plpgsql as $$ begin if NEW.kind='model-input' then NEW.scope=NEW.scope||jsonb_build_object('oversized',repeat('x',262145)); end if; return NEW; end $$");
            producer.db.execute("create trigger eisf_scope_fault before insert on engine.material_scope for each row execute function engine.eisf_scope_fault()");
            try { producer.materials.prepareInitial(run); }
            finally { producer.db.execute("drop trigger eisf_scope_fault on engine.material_scope");producer.db.execute("drop function engine.eisf_scope_fault()"); }
        }
        if (mode.equals("historic-null") || mode.equals("raw-change")) {
            producer.db.execute("create function engine.eis_insert_raw_fault() returns trigger language plpgsql as $$ begin NEW.canonical_request_utf8="
                    +(mode.equals("historic-null")?"NULL":"NEW.canonical_request_utf8||' '")+"; return NEW; end $$");
            producer.db.execute("create trigger eis_insert_raw_fault before insert on engine.model_input_snapshot for each row execute function engine.eis_insert_raw_fault()");
            try { producer.materials.prepareInitial(run); }
            finally { producer.db.execute("drop trigger eis_insert_raw_fault on engine.model_input_snapshot");producer.db.execute("drop function engine.eis_insert_raw_fault()"); }
        }
        String error = "";
        try (var subscription = chunks.subscribe(run)) {
            subscriber.set(subscription);
            try { service.drive(run, producer.actor); }
            catch (RuntimeException failure) {
                error = failure instanceof PlatformException p ? p.code() : failure.getClass().getSimpleName();
                Files.writeString(dir.resolve("drive-private-error.txt"), failure.getClass().getName()+java.util.Arrays.toString(failure.getStackTrace())+(failure.getCause()==null?"":failure.getCause().getClass().getName()+java.util.Arrays.toString(failure.getCause().getStackTrace())));
            } finally { subscriber.set(null); }
        }
        var facts = facts();
        facts.put("error", error); facts.put("observedChunks", List.copyOf(observed));
        Files.writeString(dir.resolve("drive-observation.json"), Json.write(facts));
        return facts;
    }

    Map<String,Object> recover() throws Exception {
        scheduler.close(); clients.close(); chunks.close(); service=null;
        producer.db.update("update engine.node_instance set lease_until=clock_timestamp()-interval '1 second' where run_id=? and committed_attempt_id is null",run);
        producer.db.update("update engine.run set dispatch_next_at=clock_timestamp() where id=?",run);
        mode="normal"; assemble();
        try(var subscription=chunks.subscribe(run)) {
            subscriber.set(subscription); scheduler.poll();
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
            long before=producer.db.queryForObject("select dispatch_generation from engine.run where id=?",Long.class,run);
            while(System.nanoTime()<until) {
                var row=producer.db.queryForMap("select status,dispatch_owner,dispatch_generation from engine.run where id=?",run);
                if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(row.get("status")) || (row.get("dispatch_owner")==null && ((Number)row.get("dispatch_generation")).longValue()>=before)) break;
                Thread.sleep(20);
            }
            var result=facts();Files.writeString(dir.resolve("recovery-observation.json"),Json.write(result));return result;
        } finally {subscriber.set(null);}
    }

    Map<String, Object> facts() {
        var facts = new LinkedHashMap<String, Object>();
        facts.put("run", run);
        facts.putAll(producer.db.queryForMap("select status,(select count(*) from engine.model_invocation where run_id=r.id) as invocations,"
                + "(select coalesce(sum(sdk_attempts),0) from engine.model_invocation where run_id=r.id) as sends,"
                + "(select count(*) from engine.run_event where run_id=r.id and type='LOGIC_RESULT_COMMITTED') as commits"
                + " from engine.run r where id=?", run));
        facts.put("attempts", producer.db.queryForList("select a.id,a.request_snapshot_ref,a.response_ref,a.status,"
                + "a.request_snapshot->>'requestSha256' as request_sha256 from engine.attempt a join engine.node_instance n"
                + " on n.id=a.node_instance_id where n.run_id=?", run));
        facts.put("usage",producer.db.queryForList("select usage_accounted,response->'usage' as usage from engine.model_invocation where run_id=?",run));
        var state=producer.db.queryForMap("select output::text,error::text,stop_reason,budget::text from engine.run where id=?",run);
        for(String key:List.of("output","error","budget")) facts.put(key,state.get(key)==null?null:Json.read(state.get(key).toString()));
        facts.put("stopReason",state.get("stop_reason"));
        facts.put("durable",producer.db.queryForList("select a.response::text,a.checkpoint::text,n.status as node_status from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=?",run));
        facts.put("initialScopeBytes",producer.db.queryForList("select octet_length(scope::text) as bytes from engine.material_scope where run_id=? and kind='model-input'",run));
        facts.put("terminalEvents",producer.db.queryForObject("select count(*) from engine.run_event where run_id=? and type in ('NODE_FAILED','NODE_SUCCEEDED','RUN_FINISHED','LOGIC_RESULT_COMMITTED')",Integer.class,run));
        return facts;
    }

    public static void main(String[] args) throws Exception {
        com.sun.net.httpserver.HttpServer control = null;
        try (var owned = new InitialSenderIntegrationFixture()) {
            FrozenModelBindingProducerFixture.f = owned.producer;
            owned.producer.start();
            control = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            control.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            control.createContext("/control", exchange -> {
                try {
                    String action = exchange.getRequestURI().getRawQuery();
                    Object result;
                    if ("drive".equals(action)) result = owned.drive();
                    else if ("recover".equals(action)) result = owned.recover();
                    else if ("prepare".equals(action)) { owned.assemble();owned.run=owned.create();owned.producer.materials.prepareInitial(owned.run);result=owned.facts(); }
                    else if ("facts".equals(action)) result = owned.facts();
                    else {
                        if ("revoke".equals(action)) owned.producer.iam.revoked(true);
                        else if ("outage".equals(action)) owned.producer.iam.unavailable(true);
                        else if ("restore".equals(action)) { owned.producer.iam.revoked(false); owned.producer.iam.unavailable(false); }
                        result = Map.of("authorityCalls", owned.producer.iam.authorityCalls());
                    }
                    byte[] bytes = Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception failure) { Files.writeString(owned.dir.resolve("control-private-error.txt"), failure.getClass().getName()+failure.getClass().getName()+java.util.Arrays.toString(failure.getStackTrace())+(failure.getCause()==null?"":failure.getCause().getClass().getName()+java.util.Arrays.toString(failure.getCause().getStackTrace()))); exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            control.start();
            Files.writeString(owned.dir.resolve("engine/control-ready.json"), Json.write(Map.of(
                    "origin", "http://127.0.0.1:" + control.getAddress().getPort(), "pid", ProcessHandle.current().pid())));
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(70);
            while (!Files.exists(owned.dir.resolve("engine/stop")) && System.nanoTime() < until) Thread.sleep(20);
        } finally { if (control != null) { control.stop(0); ((java.util.concurrent.ExecutorService) control.getExecutor()).close(); } }
    }

    long create() throws Exception {
        var ticket = producer.gateway.exchange(producer.originalPortal, FrozenModelBindingProducerFixture.MAIN);
        try (var decoder = new TrustedJwtDecoder(producer.iam.config("engine"), producer.iam.clock(), true)) {
            var request = new MockHttpServletRequest("POST", "/admin/apps/app-a/runs");
            var response = new MockHttpServletResponse();
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/apps/{code}/runs");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            SecurityContextHolder.getContext().setAuthentication(
                    new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
            var handler = new HandlerMethod(new RunController(null, null, null, null),
                    RunController.class.getMethod("run", String.class, JsonNode.class));
            var guard = new ServiceAuthorization("engine", new ServiceRoutePolicy(), producer.engine);
            try {
                guard.preHandle(request, response, handler);
                var proof = AuthorityContext.requireInitialFor(Actor.current(), FrozenModelBindingProducerFixture.ROOT);
                var snapshot = (ObjectNode) producer.snapshot();
                ((ObjectNode) snapshot.path("config")).set("output", Json.tree(Map.of("maxTokens", 256)));
                if(Files.exists(dir.resolve("output-declaration.json"))) ((ObjectNode)snapshot.path("config")).set("output",Json.read(Files.readString(dir.resolve("output-declaration.json"))));
                if(mode.equals("output-retry")) ((ObjectNode)snapshot.path("config").path("output")).put("onInvalid","RETRY");
                if(mode.equals("tools")) ((ObjectNode)snapshot.path("config")).set("tools",Json.tree(List.of()));
                var inputs=Json.tree(Map.of("name", "界🙂", "message", "请保留引号\"与换行\n"));
                if(mode.equals("history")) ((ObjectNode)inputs).set("history",Json.tree(List.of()));
                var budget=(ObjectNode)producer.budget();
                if(Files.exists(dir.resolve("budget-limits.json"))) {
                    var limits=(ObjectNode)Json.read(Files.readString(dir.resolve("budget-limits.json")));
                    ((ObjectNode)budget.path("limits")).setAll(limits);
                    ((ObjectNode)snapshot.path("config")).set("budgets",limits);
                    budget.put("deadline",producer.iam.clock().instant().plusMillis(budget.path("limits").path("totalDurationMs").asLong()).toString());
                }
                // Align the real INSERT timestamp with the fixture's injected authority clock before
                // materialization; no persisted run/material is rewritten after admission.
                if(mode.equals("duration-response")) {
                    producer.db.execute("create function engine.eisf_start_clock() returns trigger language plpgsql as $$ begin NEW.started_at='"+producer.iam.clock().instant()+"'::timestamptz; return NEW; end $$");
                    producer.db.execute("create trigger eisf_start_clock before insert on engine.run for each row execute function engine.eisf_start_clock()");
                }
                try { return producer.tx.execute(status -> {
                    long run = producer.runs.createPublished(proof, snapshot,
                            inputs,
                            Json.tree(Map.of("appVer", 1)), budget, "FULL",
                            UUID.randomUUID().toString(), Set.of("parent"));
                    trace.createNode(run, "initial", 1, "LLM",
                            Json.tree(Map.of("id", "initial", "type", "LLM")), proof);
                    return run;
                }); } finally {
                    if(mode.equals("duration-response")) {
                        producer.db.execute("drop trigger eisf_start_clock on engine.run");
                        producer.db.execute("drop function engine.eisf_start_clock()");
                    }
                }
            } finally {
                guard.afterCompletion(request, response, handler, null);
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    @Override public void close() {
        Throwable failure = null;
        for (AutoCloseable resource : Arrays.<AutoCloseable>asList(scheduler, chunks, clients, producer)) {
            if (resource == null) continue;
            try { resource.close(); }
            catch (Throwable close) { if (failure == null) failure = close; else failure.addSuppressed(close); }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(failure);
    }
}
