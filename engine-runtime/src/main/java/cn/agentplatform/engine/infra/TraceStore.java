package cn.agentplatform.engine.infra;

import static cn.agentplatform.engine.infra.RunAuthorityStore.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.domain.GraphRules;
import cn.agentplatform.engine.domain.InitialChatRules;
import cn.agentplatform.engine.domain.RunDispatch;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

@Repository
public class TraceStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TransactionTemplate protectedTx;
    private final DataSource source;
    private final Clock clock;
    private final MaterialScopeStore materials;

    @Autowired
    public TraceStore(JdbcTemplate db, PlatformTransactionManager tm) {
        this(db,tm,Clock.systemUTC());
    }
    public TraceStore(JdbcTemplate db, PlatformTransactionManager tm,Clock clock) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.source=Objects.requireNonNull(db.getDataSource());this.clock=Objects.requireNonNull(clock);
        if (!(tm instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != source)
            throw new IllegalArgumentException("SAME_DATASOURCE_MANAGER_REQUIRED");
        protectedTx=new TransactionTemplate(tm);protectedTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        materials=new MaterialScopeStore(source,clock);
    }

    public JdbcTemplate db() {
        return db;
    }

    java.sql.Timestamp authorityNow() {
        return java.sql.Timestamp.from(clock.instant());
    }

    public <T> T atomic(Supplier<T> f) {
        return tx.execute(s -> f.get());
    }

    public Map<String, Object> run(String id, Actor actor) {
        if(protectedRun(id,actor))throw denied();
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
        legacy(id);
        return db.queryForList(
                "select * from engine.node_instance where run_id=? order by step_seq", id);
    }

    public int event(long run, String type, Object payload) {
        legacy(run);
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
        legacy(run);
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
        legacyNode(node);
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
        legacyAttempt(attempt);
        db.update(
                "update engine.attempt set request_snapshot=?::jsonb where id=? and"
                    + " status='RUNNING'",
                Json.write(request),
                attempt);
    }

    public void response(long attempt, Object response) {
        legacyAttempt(attempt);
        db.update(
                "update engine.attempt set response=?::jsonb,response_ref=?,routed_model=?::jsonb"
                    + " where id=?",
                Json.write(response),
                "pg:attempt/" + attempt + "/response",
                Json.write(Json.tree(response).path("actualModel")),
                attempt);
    }

    public boolean commit(long run, long node, long attempt, String owner, Object output) {
        legacy(run);legacyNode(node);legacyAttempt(attempt);
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
        legacy(run);legacyNode(node);legacyAttempt(attempt);
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

    public boolean protectedRun(String id, Actor actor) {
        var rows =
                db.queryForList(
                        "select r.id,exists(select 1 from engine.run_authority a where"
                            + " a.run_id=r.id) as protected from engine.run r where r.public_id=?"
                            + " and r.tenant_id=? and r.principal_id=?",
                        id,
                        actor.tenantId(),
                        actor.principalId());
        if (rows.isEmpty()) throw new PlatformException(404, "RUN_NOT_FOUND", "运行不存在");
        return Boolean.TRUE.equals(rows.getFirst().get("protected"));
    }

    private void legacy(long run) {
        if (Boolean.TRUE.equals(
                db.queryForObject(
                        "select exists(select 1 from engine.run_authority where run_id=?)",
                        Boolean.class,
                        run))) throw denied();
    }

    private void legacyNode(long node) {
        legacy(
                db.queryForObject(
                        "select run_id from engine.node_instance where id=?", Long.class, node));
    }

    private void legacyAttempt(long attempt) {
        legacy(
                db.queryForObject(
                        "select n.run_id from engine.attempt a join engine.node_instance n on"
                            + " n.id=a.node_instance_id where a.id=?",
                        Long.class,
                        attempt));
    }

    public record PublishedView(
            Map<String, Object> run,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> events,
            RetainedAuthorization proof) {
        public void requireCurrent() {
            AuthorityContext.requireBound(proof);
            proof.requireVerified();
        }
    }

    /**
     * Only the original main POST response purpose is accepted. Refresh occurs before the single RR
     * snapshot; callers in a transaction must finish it before composing this response.
     */
    public PublishedView statusPublished(String id, Actor actor) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("STATUS_REFRESH_REQUIRES_NO_TRANSACTION");
        var ids=db.queryForList("select r.id from engine.run r join engine.run_authority a on a.run_id=r.id where r.public_id=? and r.tenant_id=? and r.principal_id=?",Long.class,id,actor.tenantId(),actor.principalId());
        if(ids.size()!=1)throw denied();
        var before=new RunAuthorityStore(source,clock).load(ids.getFirst());
        requirePurpose(AuthorityContext.current(),false);materials.requireReader(before,AuthorityContext.current());
        var proof=AuthorityContext.requireInitialFor(actor,before.root());AuthorityContext.requireBound(proof);
        var view=MaterialScopeStore.snapshot(source,()->{
            var binding=RunAuthorityStore.load(db,before.runId(),true);
            same(binding.root(),before.root());same(binding.owner(),before.owner());materials.requireReader(binding,proof.current());
            var unsupported=db.queryForMap("select exists(select 1 from engine.tool_call where run_id=r.id) as tools,controlled_draft_skill_version_id,controlled_skill_test_version_id from engine.run r where id=?",binding.runId());
            if(Boolean.TRUE.equals(unsupported.get("tools"))||unsupported.get("controlled_draft_skill_version_id")!=null||unsupported.get("controlled_skill_test_version_id")!=null)throw denied();
            var plan=statusPlan(binding);plan.scopes().forEach(scope->MaterialAuthorization.require(scope,proof.current(),clock));
            AuthorityContext.requireBound(proof);
            var values=new LinkedHashMap<String,JsonNode>();var nulls=new HashSet<String>();
            for(var scope:plan.scopes())putStatusValue(values,nulls,scope,materials.readCurrentValue(binding,scope,proof));
            var data=statusData(binding,plan,values,nulls);
            var result=new PublishedView(data.run(),data.nodes(),data.events(),proof);result.requireCurrent();return result;
        });
        view.requireCurrent();return view;
    }

    /** Only this class can mint a status read capability, after live M and real PG preflight. */
    static final class StatusReadProof {
        private final RetainedAuthorization retained;
        private final Binding binding;
        private final Set<MaterialScopeV1> scopes;
        private final Thread owner=Thread.currentThread();
        private StatusReadProof(RetainedAuthorization retained,Binding binding,List<MaterialScopeV1> scopes){
            this.retained=retained;this.binding=binding;this.scopes=Set.copyOf(scopes);
        }
        void requireCurrent(){
            if(Thread.currentThread()!=owner)throw denied();
            AuthorityContext.requireBound(retained);
            requireStatus(retained.current(),binding.publicId());
            same(binding.owner(),new SubjectKey(retained.current().actor().tenantId(),retained.current().actor().principalId()));
            same(RunObservationSelectors.status(binding.publicId(),binding.snapshotSha()),retained.current().call().resource());
            same(binding.initialSha(),MaterialAuthorization.ceilingSha256(binding.initial()));
        }
        void requireMaterial(Binding actual,MaterialScopeV1 scope,Clock clock){
            requireCurrent();same(binding,actual);if(!scopes.contains(scope))throw denied();
            MaterialAuthorization.require(scope,retained.current(),clock);
        }
    }
    public static final class ObservedView {
        private final StatusData data;
        private final StatusReadProof proof;
        private ObservedView(StatusData data,StatusReadProof proof){this.data=data;this.proof=proof;}
        public Map<String,Object> run(){return data.run();}
        public List<Map<String,Object>> nodes(){return data.nodes();}
        public List<Map<String,Object>> events(){return data.events();}
        public void requireCurrent(){proof.requireCurrent();}
    }
    private static void requireStatus(VerifiedAuthority current,String id){
        RunObservationSelectors.requirePurpose(current.call(),current.caller(),"delegated",current.operation().sourceKind(),current.operation().originKind());
        same(RunObservationSelectors.STATUS_ACTION,current.call().action());same(id,current.call().resource().id());
        same(current.call().resource(),current.operation().resource());
    }
    public ObservedView statusObserved(String id,Actor actor){
        // Reject even an ambient read-only RR before refresh; no online HTTP can inherit a DB tx.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("STATUS_REFRESH_REQUIRES_NO_TRANSACTION");
        var current=AuthorityContext.current();requireStatus(current,id);
        same(new SubjectKey(actor.tenantId(),actor.principalId()),new SubjectKey(current.actor().tenantId(),current.actor().principalId()));
        var retained=AuthorityContext.requireInitialFor(actor,current.call().resource());
        AuthorityContext.requireBound(retained);requireStatus(retained.current(),id);
        var view=MaterialScopeStore.snapshot(source,()->{
            AuthorityContext.requireBound(retained);
            var binding=loadObservationBinding(db,new SubjectKey(actor.tenantId(),actor.principalId()),id);
            same(RunObservationSelectors.status(binding.publicId(),binding.snapshotSha()),retained.current().call().resource());
            var plan=statusPlan(binding);
            // All root, nullable, node and event scopes precede the first business payload SELECT.
            for(var scope:plan.scopes())MaterialAuthorization.require(scope,retained.current(),clock);
            AuthorityContext.requireBound(retained);
            var proof=new StatusReadProof(retained,binding,plan.scopes());proof.requireCurrent();
            var values=new LinkedHashMap<String,JsonNode>();var nulls=new HashSet<String>();
            for(String kind:List.of("run-snapshot","run-version-refs")){
                var scope=plan.scopes().stream().filter(v->kind.equals(v.material().kind())).findFirst().orElseThrow(RunAuthorityStore::denied);
                putStatusValue(values,nulls,scope,materials.readObservedValue(binding,scope,proof));
            }
            var snapshot=values.get(key("run-snapshot",id));var refs=values.get(key("run-version-refs",id));
            requireSnapshotRelations(binding,snapshot,refs);
            if ("CHAT".equals(snapshot.path("type").asText())) {
                same("CHAT",db.queryForObject("select trigger_type from engine.run where id=?",String.class,binding.runId()));
                var inputScope=plan.scopes().stream().filter(v->"run-inputs".equals(v.material().kind())).findFirst().orElseThrow(RunAuthorityStore::denied);
                putStatusValue(values,nulls,inputScope,materials.readObservedValue(binding,inputScope,proof));
                InitialChatRules.requireSources(snapshot,values.get(key("run-inputs",id)));
                cn.agentplatform.engine.application.FrozenModels.reference(snapshot,InitialChatRules.entryId(snapshot));
            } else GraphRules.requirePublishedFlow(snapshot);
            for(var scope:plan.scopes()){
                if(values.containsKey(key(scope.material().kind(),scope.material().id())))continue;
                putStatusValue(values,nulls,scope,materials.readObservedValue(binding,scope,proof));
            }
            var result=new ObservedView(statusData(binding,plan,values,nulls),proof);result.requireCurrent();return result;
        });
        // Spring 6.2.7 processCommit calls the actual JDBC commit before execute returns.
        // A callback-only check misses expiry/retirement during that completion window.
        view.requireCurrent();return view;
    }
    static void requireSnapshotRelations(Binding b,JsonNode snapshot,JsonNode refs){
        object(snapshot);object(refs);
        String code=MetadataSelectors.code(text(snapshot,"appCode"));long no=positive(snapshot.get("versionNo"));
        long version=snapshot.has("appVersionId")?positive(snapshot.get("appVersionId")):positive(snapshot.get("versionId"));
        if(snapshot.has("versionId"))same(version,positive(snapshot.get("versionId")));
        same(b.appVersionId(),version);same(b.appVersionNo(),no);same(code+"@"+no,b.root().id());
        String candidate=text(snapshot,"candidateSha256");hash(candidate);same(candidate,b.root().versionDigest());
        if(snapshot.has("appVersionKey"))same(code+"@"+no,text(snapshot,"appVersionKey"));
        same(no,positive(refs.get("appVer")));
    }
    private record StatusPlan(List<MaterialScopeV1> scopes,List<Long> nodes,List<Long> events){}
    private record StatusData(Map<String,Object> run,List<Map<String,Object>> nodes,List<Map<String,Object>> events){}
    private StatusPlan statusPlan(Binding binding){
        var needed=new ArrayList<MaterialScopeV1>();
        for(var scope:materials.currentScopes(binding))if(!scope.material().kind().startsWith("attempt-"))needed.add(scope);
        var nodes=db.queryForList("select id from engine.node_instance where run_id=? order by step_seq",Long.class,binding.runId());
        var events=db.queryForList("select id from engine.run_event where run_id=? order by seq",Long.class,binding.runId());
        requireComplete(binding,needed,nodes,List.of(),events);
        return new StatusPlan(List.copyOf(needed),List.copyOf(nodes),List.copyOf(events));
    }
    private static void putStatusValue(Map<String,JsonNode> values,Set<String> nulls,MaterialScopeV1 scope,MaterialScopeStore.Payload payload){
        String locator=key(scope.material().kind(),scope.material().id());values.put(locator,payload.value());if(payload.sqlNull())nulls.add(locator);
    }
    private static StatusData statusData(Binding b,StatusPlan plan,Map<String,JsonNode> values,Set<String> nulls){
        var run=state(values,"run-state",b.publicId());run.put("id",b.runId());
        for(String kind:List.of("run-inputs","run-output","run-error","run-version-refs")){
            String locator=key(kind,b.publicId());run.put(kind.substring(4).replace('-','_'),nulls.contains(locator)?null:values.get(locator));
        }
        var nodes=new ArrayList<Map<String,Object>>();
        for(long node:plan.nodes()){
            var row=state(values,"node-state",Long.toString(node));
            for(String kind:List.of("node-output","node-error")){String locator=key(kind,Long.toString(node));row.put(kind.substring(5),nulls.contains(locator)?null:values.get(locator));}
            nodes.add(row);
        }
        var events=new ArrayList<Map<String,Object>>();
        if ("CHAT".equals(run.get("trigger_type"))) {
            var snapshot=values.get(key("run-snapshot",b.publicId()));
            InitialChatRules.requireSources(snapshot,values.get(key("run-inputs",b.publicId())));
            cn.agentplatform.engine.application.FrozenModels.reference(snapshot,InitialChatRules.entryId(snapshot));
            if(nodes.size()!=1)throw denied();
            var node=nodes.getFirst();
            InitialChatRules.requireAgent(values.get(key("node-definition",Long.toString(plan.nodes().getFirst()))),node.get("node_def_id"),node.get("node_type"),number(node,"step_seq"));
        }
        for(long event:plan.events()){
            var row=state(values,"run-event",Long.toString(event));var visible=new LinkedHashMap<String,Object>();
            for(String field:List.of("seq","type","payload","created_at"))visible.put(field,row.get(field));
            visible.put("payload",values.get(key("run-event",Long.toString(event))).get("payload"));events.add(visible);
        }
        return new StatusData(run,List.copyOf(nodes),List.copyOf(events));
    }

    private static String key(String kind, String locator) {
        return kind + ":" + locator;
    }

    private static Map<String, Object> state(
            Map<String, JsonNode> values, String kind, String locator) {
        var n = values.get(key(kind, locator));
        if (n == null || !n.isObject()) throw denied();
        var row = new LinkedHashMap<String, Object>();
        n.fields()
                .forEachRemaining(
                        e -> {
                            var v = e.getValue();
                            Object value =
                                    v.isNull()
                                            ? null
                                            : v.isTextual()
                                                    ? v.textValue()
                                                    : v.isBoolean()
                                                            ? v.booleanValue()
                                                            : v.isIntegralNumber()
                                                                    ? v.longValue()
                                                                    : v;
                            if (value instanceof String text
                                    && Set.of("started_at", "ended_at", "created_at", "lease_until")
                                            .contains(e.getKey()))
                                value = java.sql.Timestamp.from(Instant.parse(text));
                            row.put(e.getKey(), value);
                        });
        return row;
    }

    private void requireComplete(
            Binding b,
            List<MaterialScopeV1> scopes,
            List<Long> nodes,
            List<Long> attempts,
            List<Long> events) {
        var expected = new HashSet<String>();
        for (String kind : MaterialScopeStore.KINDS) expected.add(key(kind, b.publicId()));
        for (String kind : MaterialScopeStore.RUN_KINDS) expected.add(key(kind, b.publicId()));
        for (long node : nodes)
            for (String kind : MaterialScopeStore.NODE_KINDS)
                expected.add(key(kind, Long.toString(node)));
        for (long attempt : attempts)
            for (String kind : MaterialScopeStore.ATTEMPT_KINDS)
                expected.add(key(kind, Long.toString(attempt)));
        for (long event : events) expected.add(key("run-event", Long.toString(event)));
        var initial = db.queryForList("select material_id from engine.model_input_snapshot where run_id=?",
                String.class, b.runId());
        if (initial.size() > 1) throw denied();
        if (!initial.isEmpty()) expected.add(key("model-input", initial.getFirst()));
        var actual = new HashSet<String>();
        for (var s : scopes)
            if (!actual.add(key(s.material().kind(), s.material().id()))) throw denied();
        same(expected, actual);
    }

    private record WriteView(
            Binding binding,
            List<MaterialScopeV1> scopes,
            Map<String, JsonNode> values,
            Set<String> sqlNulls) {}

    private WriteView writable(long run, RetainedAuthorization proof) {
        return writable(run, proof, 0, 0);
    }

    private WriteView writable(long run, RetainedAuthorization proof, long node, long attempt) {
        return writable(run, proof, node, attempt, null);
    }

    private WriteView writable(
            long run, RetainedAuthorization proof, long node, long attempt, RunDispatch dispatch) {
        // Run, authority observation, then node locks. Only control metadata precedes scope checks.
        if (db.queryForList("select id from engine.run where id=? for update", Long.class, run)
                        .size()
                != 1) throw denied();
        // Serialize the durable current/block observation with this mutation. A run lock alone
        // lets recordCheck commit denial while this RR snapshot still sees its earlier success.
        // PostgreSQL 16 RR closes concurrent-update conflicts; this component does not retry them.
        if (db.queryForList(
                                "select run_id from engine.run_authority where run_id=? for update",
                                Long.class,
                                run)
                        .size()
                != 1) throw denied();
        var b = RunAuthorityStore.load(db, run, true);
        materials.requireWriter(b, proof, false);
        if (dispatch != null) requireDispatch(dispatch);
        var nodes =
                db.queryForList(
                        "select id from engine.node_instance where run_id=? order by id for update",
                        Long.class,
                        run);
        // Node-row contention can outlive the dispatch after the earlier run/authority check.
        // No business material is read until this actual post-lock clock_timestamp fence passes.
        if (dispatch != null) requireDispatch(dispatch);
        if (node != 0) relation(run, node, attempt);
        var attempts =
                db.queryForList(
                        "select a.id from engine.attempt a join engine.node_instance n on"
                                + " n.id=a.node_instance_id where n.run_id=? order by a.id",
                        Long.class,
                        run);
        var events =
                db.queryForList(
                        "select id from engine.run_event where run_id=? order by id",
                        Long.class,
                        run);
        var scopes = materials.currentScopes(b);
        requireComplete(b, scopes, nodes, attempts, events);
        scopes.forEach(s -> MaterialAuthorization.require(s, proof.current(), clock));
        proof.requireVerified();
        var values = new LinkedHashMap<String, JsonNode>();
        var nulls = new HashSet<String>();
        for (var scope : scopes) {
            var payload = materials.readCurrentValue(b, scope, proof);
            String k = key(scope.material().kind(), scope.material().id());
            values.put(k, payload.value());
            if (payload.sqlNull()) nulls.add(k);
        }
        return new WriteView(b, new ArrayList<>(scopes), values, nulls);
    }

    private <T> T protectedAtomic(RetainedAuthorization proof, Supplier<T> work) {
        if (proof == null) throw denied();
        proof.requireVerified();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            requireWrite(source);
            var c = DataSourceUtils.getConnection(source);
            try {
                if (c.getTransactionIsolation() < java.sql.Connection.TRANSACTION_REPEATABLE_READ)
                    throw new IllegalStateException("REPEATABLE_READ_REQUIRED");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            } finally {
                DataSourceUtils.releaseConnection(c, source);
            }
            // REQUIRED joining cannot observe its caller's later commit. N2's concrete outer
            // owners need their own retained-proof completion seam before composing writes.
            throw new IllegalStateException("PROTECTED_WRITE_REQUIRES_TOP_LEVEL_TRANSACTION");
        }
        try {
            var result =
                    protectedTx.execute(
                            s -> {
                                try {
                                    var value = work.get();
                                    proof.requireVerified();
                                    return value;
                                } catch (RuntimeException | Error e) {
                                    ((ConnectionHolder)
                                                    TransactionSynchronizationManager.getResource(
                                                            source))
                                            .setRollbackOnly();
                                    throw e;
                                }
                            });
            proof.requireVerified();
            return result;
        } catch (RuntimeException | Error e) {
            if (TransactionSynchronizationManager.hasResource(source))
                ((ConnectionHolder) TransactionSynchronizationManager.getResource(source))
                        .setRollbackOnly();
            throw e;
        }
    }

    private void append(WriteView view, String kind, String locator, RetainedAuthorization proof) {
        // Later events in this same action consume facts just materialized above. Keep those real
        // persisted scopes as direct parents instead of silently reverting to the entry snapshot.
        var scope = materials.append(view.binding(), kind, locator, proof, view.scopes());
        view.scopes().add(scope);
        var payload = materials.readCurrentValue(view.binding(), scope, proof);
        String k = key(kind, locator);
        view.values().put(k, payload.value());
        if (payload.sqlNull()) view.sqlNulls().add(k);
        else view.sqlNulls().remove(k);
    }

    private long appendEvent(
            WriteView view, String type, Object payload, RetainedAuthorization proof) {
        int seq =
                db.queryForObject(
                        "update engine.run set event_seq=event_seq+1 where id=? returning"
                            + " event_seq",
                        Integer.class,
                        view.binding().runId());
        long id =
                db.queryForObject(
                        "insert into engine.run_event(run_id,seq,type,payload)"
                            + " values(?,?,?,?::jsonb) returning id",
                        Long.class,
                        view.binding().runId(),
                        seq,
                        type,
                        Json.write(payload));
        append(view, "run-event", Long.toString(id), proof);
        append(view, "run-state", view.binding().publicId(), proof);
        return seq;
    }

    public int event(long run, String type, Object payload, RetainedAuthorization proof) {
        return protectedAtomic(
                proof, () -> (int) appendEvent(writable(run, proof), type, payload, proof));
    }

    /** Concrete insertion seam for N3. It never attaches initial authority to an existing node. */
    public long createNode(
            long run,
            String nodeDefId,
            int stepSeq,
            String nodeType,
            JsonNode definition,
            RetainedAuthorization admission) {
        requireWrite(source);
        try {
            AuthorityContext.requireBound(admission);
            db.queryForObject("select id from engine.run where id=? for update", Long.class, run);
            var b = RunAuthorityStore.load(db, run, true);
            materials.requireWriter(b, admission, true);
            var parents = materials.currentScopes(b);
            parents.forEach(s -> MaterialAuthorization.require(s, admission.current(), clock));
            // A run missing earlier material metadata cannot acquire new nodes as a repair path.
            requireComplete(
                    b,
                    parents,
                    db.queryForList(
                            "select id from engine.node_instance where run_id=?", Long.class, run),
                    db.queryForList(
                            "select a.id from engine.attempt a join engine.node_instance n on"
                                + " n.id=a.node_instance_id where n.run_id=?",
                            Long.class,
                            run),
                    db.queryForList(
                            "select id from engine.run_event where run_id=?", Long.class, run));
            object(definition);
            long node =
                    db.queryForObject(
                            "insert into"
                                + " engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition)"
                                + " values(?,?,?,?,'READY',?::jsonb) returning id",
                            Long.class,
                            run,
                            nodeDefId,
                            stepSeq,
                            nodeType,
                            Json.write(definition));
            for (String kind : MaterialScopeStore.NODE_KINDS)
                materials.append(b, kind, Long.toString(node), admission, parents);
            AuthorityContext.requireBound(admission);
            return node;
        } catch (RuntimeException | Error e) {
            ((ConnectionHolder) TransactionSynchronizationManager.getResource(source))
                    .setRollbackOnly();
            throw e;
        }
    }

    public Map<String, Object> claim(long node, String owner, RetainedAuthorization proof) {
        long run =
                db.queryForObject(
                        "select run_id from engine.node_instance where id=?", Long.class, node);
        return protectedAtomic(proof, () -> claim(writable(run, proof), node, owner, proof));
    }

    private Map<String, Object> claim(
            WriteView view, long node, String owner, RetainedAuthorization proof) {
        return claim(view, node, owner, proof, null);
    }

    private boolean claimable(WriteView view, long node) {
        long run = view.binding().runId();
        if (!"RUNNING"
                .equals(state(view.values(), "run-state", view.binding().publicId()).get("status")))
            return false;
        var row = state(view.values(), "node-state", Long.toString(node));
        if (!Set.of("READY", "RUNNING").contains(row.get("status"))) return false;
        if ("RUNNING".equals(row.get("status"))
                && Boolean.TRUE.equals(
                        db.queryForObject(
                                "select lease_until>clock_timestamp() from engine.node_instance"
                                        + " where id=?",
                                Boolean.class,
                                node))) return false;
        var rules =
                view.values()
                        .get(key("run-snapshot", view.binding().publicId()))
                        .path("definition")
                        .path("executionRules");
        int max = rules.path("parallel").asBoolean(true) ? rules.path("maxParallel").asInt(8) : 1;
        if (db.queryForObject(
                        "select count(*) from engine.node_instance where run_id=? and"
                                + " status='RUNNING' and lease_until>clock_timestamp()",
                        Integer.class,
                        run)
                >= max) return false;
        return true;
    }

    private Map<String, Object> claim(
            WriteView view,
            long node,
            String owner,
            RetainedAuthorization proof,
            RunDispatch dispatch) {
        long run = view.binding().runId();
        if (!claimable(view, node)) return null;
        int no =
                db.queryForObject(
                        "select coalesce(max(attempt_no),0)+1 from engine.attempt where"
                                + " node_instance_id=?",
                        Integer.class,
                        node);
        if (no == 1) consume(view, "steps", 1, 25, proof);
        String generation = owner + "_" + UUID.randomUUID().toString().substring(0, 8);
        if (dispatch != null) requireDispatch(dispatch);
        var claimArgs = new ArrayList<Object>(Arrays.asList(generation, node));
        if (dispatch != null) {
            claimArgs.add(dispatch.owner());
            claimArgs.add(dispatch.generation());
        }
        if (db.update(
                        "update engine.node_instance set"
                            + " status='RUNNING',lease_owner=?,lease_until=clock_timestamp()+interval"
                            + " '90 seconds',started_at=coalesce(started_at,now()) where id=?"
                                + (dispatch == null ? "" : dispatchFence()),
                        claimArgs.toArray())
                != 1) throw new DispatchLost();
        long attempt =
                db.queryForObject(
                        "insert into"
                            + " engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot)"
                            + " values(?,?,'RUNNING',?,'pending','{}'::jsonb) returning id",
                        Long.class,
                        node,
                        no,
                        Digest.sha256("node:" + node));
        db.update(
                "update engine.attempt set request_snapshot_ref=? where id=?",
                "pg:attempt/" + attempt,
                attempt);
        append(view, "node-state", Long.toString(node), proof);
        // These real attempt parents retain every input consumed by execute across later changes.
        for (String kind : MaterialScopeStore.ATTEMPT_KINDS)
            append(view, kind, Long.toString(attempt), proof);
        appendEvent(
                view,
                "NODE_STARTED",
                Map.of("nodeInstanceId", node, "attemptId", attempt, "attemptNo", no),
                proof);
        var result = node(view, node);
        result.putAll(
                db.queryForMap(
                        "select lease_owner,lease_until from engine.node_instance where id=?",
                        node));
        result.put("attemptId", attempt);
        return result;
    }

    private void relation(long run, long node, long attempt) {
        var ids =
                db.queryForList(
                        "select a.id from engine.attempt a join engine.node_instance n on"
                            + " n.id=a.node_instance_id where a.id=? and n.id=? and n.run_id=?",
                        Long.class,
                        attempt,
                        node,
                        run);
        if (ids.size() != 1) throw denied();
    }

    public boolean commit(
            long run,
            long node,
            long attempt,
            String owner,
            Object output,
            RetainedAuthorization proof) {
        return protectedAtomic(
                proof,
                () ->
                        commit(
                                writable(run, proof, node, attempt),
                                node,
                                attempt,
                                owner,
                                output,
                                null,
                                false,
                                null,
                                proof));
    }

    private boolean commit(
            WriteView view,
            long node,
            long attempt,
            String owner,
            Object output,
            Object error,
            boolean defaultValue,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        String fence =
                dispatch == null
                        ? ""
                        : " and exists(select 1 from engine.run r where r.id=node_instance.run_id"
                                + " and r.dispatch_owner=? and r.dispatch_generation=? and"
                                + " r.dispatch_until>clock_timestamp() and r.status='RUNNING')";
        var args =
                new ArrayList<Object>(
                        Arrays.asList(
                                attempt,
                                "pg:node/" + node,
                                Json.write(output),
                                Digest.sha256(Json.write(output)),
                                defaultValue,
                                Json.write(error),
                                node,
                                view.binding().runId(),
                                owner));
        if (dispatch != null) {
            args.add(dispatch.owner());
            args.add(dispatch.generation());
        }
        int changed =
                db.update(
                        "update engine.node_instance set"
                            + " status='SUCCEEDED',committed_attempt_id=?,committed_output_ref=?,output=?::jsonb,output_digest=?,error=case"
                            + " when ? then ?::jsonb else error end,ended_at=now() where id=? and"
                            + " run_id=? and committed_attempt_id is null and status='RUNNING' and"
                            + " lease_owner=? and lease_until>clock_timestamp()"
                                + fence,
                        args.toArray());
        if (changed == 0) {
            db.update(
                    "insert into engine.attempt_note(node_instance_id,attempt_id,kind)"
                            + " values(?,?,'SUPERSEDED_SUBMIT')",
                    node,
                    attempt);
            return false;
        }
        db.update(
                "update engine.attempt set status='SUCCEEDED',ended_at=now() where id=?", attempt);
        if (defaultValue) append(view, "node-error", Long.toString(node), proof);
        append(view, "node-state", Long.toString(node), proof);
        append(view, "node-output", Long.toString(node), proof);
        append(view, "attempt-state", Long.toString(attempt), proof);
        appendEvent(
                view,
                "LOGIC_RESULT_COMMITTED",
                Map.of("nodeInstanceId", node, "attemptId", attempt),
                proof);
        appendEvent(
                view,
                "NODE_FINISHED",
                Map.of("nodeInstanceId", node, "status", "SUCCEEDED"),
                proof);
        return true;
    }

    public void fail(
            long run,
            long node,
            long attempt,
            String owner,
            Object error,
            RetainedAuthorization proof) {
        protectedAtomic(
                proof,
                () -> {
                    fail(
                            writable(run, proof, node, attempt),
                            node,
                            attempt,
                            owner,
                            error,
                            null,
                            proof);
                    return null;
                });
    }

    private void fail(
            WriteView view,
            long node,
            long attempt,
            String owner,
            Object error,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        String fence =
                dispatch == null
                        ? ""
                        : " and exists(select 1 from engine.run r where r.id=node_instance.run_id"
                                + " and r.dispatch_owner=? and r.dispatch_generation=? and"
                                + " r.dispatch_until>clock_timestamp() and r.status='RUNNING')";
        var args =
                new ArrayList<Object>(
                        Arrays.asList(Json.write(error), node, view.binding().runId(), owner));
        if (dispatch != null) {
            args.add(dispatch.owner());
            args.add(dispatch.generation());
        }
        int changed =
                db.update(
                        "update engine.node_instance set"
                                + " status='FAILED',error=?::jsonb,ended_at=now() where id=? and"
                                + " run_id=? and status='RUNNING' and lease_owner=? and"
                                + " lease_until>clock_timestamp()"
                                + fence,
                        args.toArray());
        // A lost node CAS still records the true attempt failure, but never a lost dispatch.
        if (dispatch != null) requireDispatch(dispatch);
        db.update(
                "update engine.attempt set status='FAILED',error=?::jsonb,ended_at=now() where"
                        + " id=?",
                Json.write(error),
                attempt);
        append(view, "attempt-state", Long.toString(attempt), proof);
        append(view, "attempt-error", Long.toString(attempt), proof);
        if (changed > 0) {
            append(view, "node-state", Long.toString(node), proof);
            append(view, "node-error", Long.toString(node), proof);
            appendEvent(view, "NODE_FAILED", Map.of("nodeInstanceId", node, "error", error), proof);
        }
    }

    public void consume(
            long run, String metric, long amount, long defaultLimit, RetainedAuthorization proof) {
        protectedAtomic(
                proof,
                () -> {
                    consume(writable(run, proof), metric, amount, defaultLimit, proof);
                    return null;
                });
    }

    private void consume(
            WriteView view,
            String metric,
            long amount,
            long defaultLimit,
            RetainedAuthorization proof) {
        var budget = budgetAfter(view, metric, amount, defaultLimit);
        db.update(
                "update engine.run set budget=?::jsonb where id=?",
                Json.write(budget),
                view.binding().runId());
        append(view, "run-budget", view.binding().publicId(), proof);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode budgetAfter(
            WriteView view, String metric, long amount, long defaultLimit) {
        if (!Set.of("steps", "tokens", "depth", "modelCalls").contains(metric)) throw denied();
        var budget =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        view.values().get(key("run-budget", view.binding().publicId())).deepCopy();
        String limitName =
                switch (metric) {
                    case "steps" -> "maxSteps";
                    case "tokens" -> "totalTokens";
                    case "depth" -> "maxDepth";
                    default -> metric;
                };
        long limit = budget.path("limits").path(limitName).asLong(defaultLimit),
                current = budget.path("consumed").path(metric).asLong();
        if (amount < 0 || amount > limit || current > limit - amount)
            throw new PlatformException(409, "BUDGET_" + metric.toUpperCase(), "运行预算已耗尽：" + metric);
        var state = state(view.values(), "run-state", view.binding().publicId());
        if (state.get("started_at") instanceof java.sql.Timestamp started
                && clock.millis() - started.getTime()
                        > budget.path("limits").path("totalDurationMs").asLong(600000))
            throw new PlatformException(409, "BUDGET_DURATION", "运行时间预算已耗尽");
        ((com.fasterxml.jackson.databind.node.ObjectNode) budget.path("consumed"))
                .put(metric, current + amount);
        return budget;
    }

    public String ownedApplicationType(RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(proof, () -> {
            var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
            var type = view.values().get(key("run-snapshot", view.binding().publicId())).path("type");
            if (!type.isTextual() || !Set.of("FLOW", "CHAT").contains(type.textValue())) throw denied();
            return type.textValue();
        });
    }

    private long initialNode(WriteView view) {
        var run = flowRun(view);
        boolean firstChat = "CHAT".equals(run.get("trigger_type"));
        if (!(firstChat || "TASK".equals(run.get("trigger_type"))) || !"FULL".equals(run.get("mode"))
                || !"CHAT".equals(json(run.get("snapshot")).path("type").asText())) throw denied();
        var nodes = flowNodes(view);
        if (nodes.size() != 1
                || number(nodes.getFirst(), "step_seq") != 1) throw denied();
        long id = number(nodes.getFirst(), "id");
        var definition = json(nodes.getFirst().get("definition"));
        if (firstChat) {
            InitialChatRules.requireSources(json(run.get("snapshot")),json(run.get("inputs")));
            InitialChatRules.requireAgent(definition,nodes.getFirst().get("node_def_id"),nodes.getFirst().get("node_type"),number(nodes.getFirst(),"step_seq"));
        } else if (!"LLM".equals(nodes.getFirst().get("node_type")) || !definition.equals(Json.tree(Map.of("id", "initial", "type", "LLM")))
                || !"initial".equals(nodes.getFirst().get("node_def_id"))) throw denied();
        if (db.queryForObject("select count(*) from engine.run r where id=? and"
                + " (debug_snapshot is not null or eval_candidate is not null or"
                + " controlled_draft_skill_version_id is not null or controlled_draft_skill_digest is not null"
                + " or controlled_skill_test_version_id is not null or controlled_skill_test_digest is not null"
                + " or exists(select 1 from engine.chat_turn where run_public_id=r.public_id)"
                + " or exists(select 1 from engine.tool_call where run_id=r.id))", Integer.class,
                view.binding().runId()) != 0) throw denied();
        var invocations = db.queryForList("select node_instance_id,round_no,kind,output_attempt_no"
                + " from engine.model_invocation where run_id=?", view.binding().runId());
        if (invocations.size() > 1) throw denied();
        for (var invocation : invocations)
            if (number(invocation, "node_instance_id") != id || number(invocation, "round_no") != 0
                    || number(invocation, "output_attempt_no") != 0 || !"MAIN".equals(invocation.get("kind"))) throw denied();
        if (!Set.of("PENDING", "RUNNING", "SUCCEEDED").contains(run.get("status"))) throw denied();
        return id;
    }

    public boolean hasInitialPreparation(RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(proof, () -> {
            initialNode(writable(dispatch.runId(), proof, 0, 0, dispatch));
            return db.queryForObject("select count(*) from engine.model_input_snapshot where run_id=?",
                    Integer.class, dispatch.runId()) == 1;
        });
    }

    /** Selected from the protected durable run and exact node, never a caller trigger flag. */
    public String ownedInitialTrigger(RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(proof, () -> {
            var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
            initialNode(view);
            return flowRun(view).get("trigger_type").toString();
        });
    }

    /** Atomically claims, freezes, budgets and marks one dispatch. No HTTP occurs in this tx. */
    public Map<String, Object> dispatchInitial(RunDispatch dispatch, String worker,
            cn.agentplatform.engine.domain.ModelInputManifest manifest, byte[] canonical, RetainedAuthorization proof) {
        return protectedAtomic(proof, () -> {
            var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
            long node = initialNode(view);
            var bytes = new ModelInputMaterialStore(source, clock).initialRequestUtf8(dispatch.runId(), manifest, proof);
            if (!Arrays.equals(bytes, canonical)) throw denied();
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            cn.agentplatform.engine.application.ModelInputMaterials.initialOutputPolicy(Json.read(raw).path("params").path("output"));
            String ref = "/internal/material-scopes/model-input/" + manifest.material().id() + "?version=" + manifest.requestSha256();
            var snapshot = view.values().get(key("run-snapshot", view.binding().publicId()));
            var binding = cn.agentplatform.engine.application.FrozenModels.reference(snapshot, manifest.modelEntryId());
            same(binding, manifest.modelBindingRef());
            JsonNode entry = null;
            for (var item : snapshot.path("dependencyVersions").path("modelRefs"))
                if (item.path("modelEntryId").asLong() == manifest.modelEntryId()) entry = item.path("entry");
            if (entry == null || !entry.path("modelName").isTextual() || entry.path("modelName").asText().isBlank()
                    || !entry.path("baseUrl").isTextual() || entry.path("baseUrl").asText().isBlank()
                    || !entry.path("providerId").isIntegralNumber() || entry.path("providerId").asLong() < 1) throw denied();
            var route = Json.tree(Map.of("modelEntryId", manifest.modelEntryId(), "modelName", entry.path("modelName").asText(),
                    "providerId", entry.path("providerId").longValue(), "endpoint", entry.path("baseUrl").asText()));
            var previous = db.queryForList("select id,sdk_attempts,status,source_request::text,response::text"
                    + " from engine.model_invocation where run_id=?", dispatch.runId());
            Map<String, Object> claim;
            JsonNode frozen;
            JsonNode response = null;
            if (!previous.isEmpty()) {
                var invocation = previous.getFirst();
                if (number(invocation, "sdk_attempts") != 1) throw denied();
                frozen = json(invocation.get("source_request"));
                if (!manifest.manifestSha256().equals(frozen.path("manifestSha256").asText())
                        || !raw.equals(frozen.path("canonicalRequestUtf8").asText())
                        || !Json.canonical(route).equals(Json.canonical(frozen.path("route")))) throw denied();
                long attempt = frozen.path("attemptId").longValue();
                relation(dispatch.runId(), node, attempt);
                same(Json.canonical(frozen), Json.canonical(view.values().get(key("attempt-request", Long.toString(attempt)))));
                if (invocation.get("response") == null) throw initialUnknown();
                response = json(invocation.get("response"));
                same(Json.canonical(response), Json.canonical(view.values().get(key("attempt-checkpoint", Long.toString(attempt))).path("initialResponse")));
                if (!"SUCCEEDED".equals(invocation.get("status"))) throw denied();
                // Recovery renews ownership of this exact response-bearing attempt, never creates a new invocation.
                String owner = UUID.randomUUID().toString();
                if (db.update("update engine.node_instance set lease_owner=?,lease_until=clock_timestamp()+interval '90 seconds'"
                        + " where id=? and status='RUNNING' and committed_attempt_id is null"
                        + " and (lease_until is null or lease_until<=clock_timestamp())" + dispatchFence(),
                        owner, node, dispatch.owner(), dispatch.generation()) != 1) throw new DispatchLost();
                claim = node(view, node); claim.put("attemptId", attempt); claim.put("lease_owner", owner);
            } else {
                // A paid response may need terminal recovery after its engine time budget. Only a
                // fresh dispatch needs time left; transport separately enforces the same deadline.
                if (!Instant.parse(json(flowRun(view).get("budget")).path("deadline").asText()).isAfter(clock.instant())) throw denied();
                if (db.queryForObject("select count(*) from engine.attempt where node_instance_id=?", Integer.class, node) != 0) throw denied();
                if ("PENDING".equals(flowRun(view).get("status"))) {
                    if (db.update("update engine.run set status='RUNNING' where id=? and status='PENDING'"
                            + " and dispatch_owner=? and dispatch_generation=? and dispatch_until>clock_timestamp()",
                            dispatch.runId(), dispatch.owner(), dispatch.generation()) != 1) throw new DispatchLost();
                    append(view, "run-state", view.binding().publicId(), proof);
                    appendEvent(view, "RUN_STARTED", Map.of("runId", view.binding().publicId(), "triggerType", flowRun(view).get("trigger_type")), proof);
                }
                claim = claim(view, node, worker, proof, dispatch);
                if (claim == null) throw new DispatchLost();
                long attempt = number(claim, "attemptId");
                frozen = Json.tree(Map.ofEntries(Map.entry("attemptId", attempt), Map.entry("canonicalRequestUtf8", raw),
                        Map.entry("manifestSha256", manifest.manifestSha256()), Map.entry("materialRef", ref),
                        Map.entry("requestSha256", manifest.requestSha256()), Map.entry("binding", binding),
                        Map.entry("invocationRef", "material:" + manifest.material().id() + ":MAIN"),
                        Map.entry("sendSeq", 1), Map.entry("route", route)));
                consume(view, "modelCalls", 1, 40, proof);
                db.update("update engine.attempt set request_snapshot=?::jsonb where id=?", Json.write(frozen), attempt);
                db.update("insert into engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request,final_request,status,sdk_attempts,output_attempt_no)"
                        + " values(?,?,0,'MAIN',?::jsonb,?::jsonb,'DISPATCHED',1,0)", dispatch.runId(), node, Json.write(frozen), raw);
                append(view, "attempt-request", Long.toString(attempt), proof);
                append(view, "attempt-state", Long.toString(attempt), proof);
                appendEvent(view, "MODEL_DISPATCHED", Map.of("nodeInstanceId", node, "attemptId", attempt, "sendSeq", 1), proof);
            }
            var result = new LinkedHashMap<>(claim);
            result.put("frozen", frozen); result.put("response", response);
            initialCommitFence(dispatch, node, number(claim, "attemptId"), claim.get("lease_owner").toString(), proof);
            return result;
        });
    }

    private void initialLease(RunDispatch dispatch, long node, long attempt, String owner, RetainedAuthorization proof) {
        proof.requireVerified(); requireDispatch(dispatch); relation(dispatch.runId(), node, attempt);
        if (!Boolean.TRUE.equals(db.queryForObject("select status='RUNNING' and committed_attempt_id is null"
                + " and lease_owner=? and lease_until>clock_timestamp() and exists(select 1 from engine.run r"
                + " where r.id=node_instance.run_id and r.status='RUNNING') from engine.node_instance where id=?",
                Boolean.class, owner, node))) throw new DispatchLost();
    }

    private void initialCommitFence(RunDispatch dispatch, long node, long attempt, String owner, RetainedAuthorization proof) {
        initialLease(dispatch, node, attempt, owner, proof);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { initialLease(dispatch, node, attempt, owner, proof); }
        });
    }

    public void requireInitialLive(RunDispatch dispatch, Map<String, Object> claim, RetainedAuthorization proof) {
        protectedAtomic(proof, () -> {
            long node = number(claim, "id"), attempt = number(claim, "attemptId");
            var view = writable(dispatch.runId(), proof, node, attempt, dispatch);
            if (initialNode(view) != node) throw denied();
            same(Json.canonical(claim.get("frozen")), Json.canonical(view.values().get(key("attempt-request", Long.toString(attempt)))));
            initialCommitFence(dispatch, node, attempt, claim.get("lease_owner").toString(), proof);
            return null;
        });
    }

    public long initialRemainingMillis(RunDispatch dispatch, Map<String, Object> claim) {
        var rows = db.queryForList("select greatest(0,floor(extract(epoch from (least(n.lease_until,r.dispatch_until)"
                + "-clock_timestamp()))*1000))::bigint as remaining,r.budget->>'deadline' as deadline"
                + " from engine.node_instance n join engine.run r on r.id=n.run_id where n.id=? and r.id=?"
                + " and n.lease_owner=? and n.status='RUNNING' and n.committed_attempt_id is null and r.status='RUNNING'"
                + " and r.dispatch_owner=? and r.dispatch_generation=?", number(claim, "id"), dispatch.runId(),
                claim.get("lease_owner"), dispatch.owner(), dispatch.generation());
        if (rows.size() != 1) return 0;
        return Math.min(number(rows.getFirst(), "remaining"), Math.max(0, java.time.Duration.between(clock.instant(), Instant.parse(rows.getFirst().get("deadline").toString())).toMillis()));
    }

    public void responseInitial(RunDispatch dispatch, Map<String, Object> claim, JsonNode response, RetainedAuthorization proof) {
        protectedAtomic(proof, () -> {
            long node = number(claim, "id"), attempt = number(claim, "attemptId");
            var view = writable(dispatch.runId(), proof, node, attempt, dispatch);
            initialNode(view); initialLease(dispatch, node, attempt, claim.get("lease_owner").toString(), proof);
            same(Json.canonical(claim.get("frozen")), Json.canonical(view.values().get(key("attempt-request", Long.toString(attempt)))));
            var usage = response.path("usage");
            boolean known = usage.isObject() && usage.path("totalTokens").isIntegralNumber()
                    && usage.path("totalTokens").canConvertToLong() && usage.path("totalTokens").longValue() >= 0;
            if (db.update("update engine.model_invocation set response=?::jsonb,status='SUCCEEDED',usage_accounted=?,updated_at=now()"
                    + " where run_id=? and node_instance_id=? and status='DISPATCHED' and sdk_attempts=1 and response is null",
                    Json.write(response), known, dispatch.runId(), node) != 1) throw initialUnknown();
            long tokens = known ? usage.path("totalTokens").longValue() : 0;
            var budgetError = initialBudgetError(view, tokens);
            if (known) {
                // Paid usage is a fact, including an overrun. The normal admission gate may reject
                // further work but must not roll back this response or turn unknown usage into zero.
                var budget = (com.fasterxml.jackson.databind.node.ObjectNode) view.values()
                        .get(key("run-budget", view.binding().publicId())).deepCopy();
                var consumed = (com.fasterxml.jackson.databind.node.ObjectNode) budget.path("consumed");
                consumed.put("tokens", consumed.path("tokens").bigIntegerValue().add(java.math.BigInteger.valueOf(tokens)));
                db.update("update engine.run set budget=?::jsonb where id=?", Json.write(budget), dispatch.runId());
                append(view, "run-budget", view.binding().publicId(), proof);
            }
            var checkpoint = Json.mapper().createObjectNode().set("initialResponse", response);
            if (budgetError != null) ((com.fasterxml.jackson.databind.node.ObjectNode) checkpoint).set("initialBudgetError", budgetError);
            db.update("update engine.attempt set response=?::jsonb,response_ref=?,checkpoint=?::jsonb,routed_model=?::jsonb where id=?",
                    Json.write(response), "pg:attempt/" + attempt + "/response", Json.write(checkpoint), Json.write(response.path("actualModel")), attempt);
            append(view, "attempt-checkpoint", Long.toString(attempt), proof);
            append(view, "attempt-state", Long.toString(attempt), proof);
            initialCommitFence(dispatch, node, attempt, claim.get("lease_owner").toString(), proof);
            return null;
        });
        requireInitialLive(dispatch, claim, proof);
    }

    private JsonNode initialBudgetError(WriteView view, long tokens) {
        String reason = null;
        try { budgetAfter(view, "tokens", tokens, 200000); }
        catch (PlatformException exhausted) {
            if (!Set.of("BUDGET_TOKENS", "BUDGET_DURATION").contains(exhausted.code())) throw exhausted;
            reason = exhausted.code();
        }
        if (reason == null && !Instant.parse(view.values().get(key("run-budget", view.binding().publicId()))
                .path("deadline").asText()).isAfter(clock.instant())) reason = "BUDGET_DURATION";
        return reason == null ? null : Json.tree(Map.of("semantic", reason, "message", "运行预算已耗尽"));
    }

    public void commitInitial(RunDispatch dispatch, Map<String, Object> claim, RetainedAuthorization proof) {
        String terminalStatus = protectedAtomic(proof, () -> {
            long node = number(claim, "id"), attempt = number(claim, "attemptId");
            var view = writable(dispatch.runId(), proof, node, attempt, dispatch);
            initialNode(view); initialLease(dispatch, node, attempt, claim.get("lease_owner").toString(), proof);
            var checkpoint = (com.fasterxml.jackson.databind.node.ObjectNode) view.values().get(key("attempt-checkpoint", Long.toString(attempt))).deepCopy();
            var response = checkpoint.path("initialResponse");
            if (!response.isObject()) throw initialUnknown();
            same(Json.canonical(claim.get("frozen")), Json.canonical(view.values().get(key("attempt-request", Long.toString(attempt)))));
            var durable = db.queryForList("select response::text from engine.model_invocation where run_id=? and node_instance_id=?"
                    + " and kind='MAIN' and round_no=0 and output_attempt_no=0 and sdk_attempts=1 and status='SUCCEEDED'",dispatch.runId(),node);
            if(durable.size()!=1 || durable.getFirst().get("response")==null) throw initialUnknown();
            same(Json.canonical(response),Json.canonical(json(durable.getFirst().get("response"))));
            var output = (com.fasterxml.jackson.databind.node.ObjectNode) response.deepCopy();
            JsonNode error = checkpoint.get("initialBudgetError");
            if (error == null) error = initialBudgetError(view, 0);
            var frozen = (JsonNode) claim.get("frozen");
            var policy = cn.agentplatform.engine.application.ModelInputMaterials.initialOutputPolicy(
                    Json.read(frozen.path("canonicalRequestUtf8").asText()).path("params").path("output"));
            if (error == null && policy.json()) {
                var verdict = policy.validate(response);
                var diagnostic = Json.tree(policy.diagnostic(verdict, 0));
                output.set("outputValidation", diagnostic);
                if (verdict.valid()) output.set("structured", verdict.structured());
                else {
                    output.remove("structured");
                    if (policy.onInvalid().equals("TERMINATE")) error = diagnostic;
                }
                db.update("update engine.model_invocation set output_validation=?::jsonb where run_id=? and node_instance_id=?",
                        Json.write(diagnostic), dispatch.runId(), node);
                checkpoint.set("outputValidation", diagnostic);
                appendEvent(view, "MODEL_OUTPUT_VALIDATED", Map.of("nodeInstanceId", node, "validation", diagnostic), proof);
            }
            // Raw response is never rewritten. Derived output/diagnostic is a protected checkpoint
            // revision with the same floor and parents, committed atomically with the terminal CAS.
            checkpoint.set(error == null ? "initialOutput" : "initialFailure", error == null ? output : error);
            db.update("update engine.attempt set checkpoint=?::jsonb where id=?", Json.write(checkpoint), attempt);
            append(view, "attempt-checkpoint", Long.toString(attempt), proof);
            String status;
            if (error == null) {
                status = "SUCCEEDED";
                if (!commit(view, node, attempt, claim.get("lease_owner").toString(), output, null, false, dispatch, proof)) throw new DispatchLost();
                if (db.update("update engine.run set status='SUCCEEDED',output=?::jsonb,output_ref=?,ended_at=now(),stop_reason='FINAL_ANSWER'"
                        + " where id=? and status='RUNNING' and dispatch_owner=? and dispatch_generation=? and dispatch_until>clock_timestamp()",
                        Json.write(output), "pg:run/" + dispatch.runId() + "/output", dispatch.runId(), dispatch.owner(), dispatch.generation()) != 1) throw new DispatchLost();
                append(view, "run-output", view.binding().publicId(), proof);
            } else {
                status = "FAILED";
                fail(view, node, attempt, claim.get("lease_owner").toString(), error, dispatch, proof);
                if (db.update("update engine.run set status='FAILED',error=?::jsonb,ended_at=now(),stop_reason=?"
                        + " where id=? and status='RUNNING' and dispatch_owner=? and dispatch_generation=? and dispatch_until>clock_timestamp()",
                        Json.write(error), error.path("semantic").asText(), dispatch.runId(), dispatch.owner(), dispatch.generation()) != 1) throw new DispatchLost();
                append(view, "run-error", view.binding().publicId(), proof);
            }
            append(view, "run-state", view.binding().publicId(), proof);
            appendEvent(view, "RUN_FINISHED", Map.of("status", status), proof);
            initialTerminalFence(dispatch, node, attempt, claim.get("lease_owner").toString(), status, proof);
            final String committedStatus = status;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    initialTerminalFence(dispatch, node, attempt, claim.get("lease_owner").toString(), committedStatus, proof);
                }
            });
            return status;
        });
        initialTerminalFence(dispatch, number(claim, "id"), number(claim, "attemptId"),
                claim.get("lease_owner").toString(), terminalStatus, proof);
    }

    private void initialTerminalFence(RunDispatch dispatch, long node, long attempt, String owner,
            String status, RetainedAuthorization proof) {
        proof.requireVerified(); requireDispatch(dispatch); relation(dispatch.runId(), node, attempt);
        if (!Boolean.TRUE.equals(db.queryForObject("select n.status=? and a.status=n.status and r.status=n.status"
                + " and n.lease_owner=? and n.lease_until>clock_timestamp()"
                + " and ((n.status='SUCCEEDED' and n.committed_attempt_id=a.id)"
                + " or (n.status='FAILED' and n.committed_attempt_id is null))"
                + " from engine.node_instance n join engine.attempt a on a.node_instance_id=n.id"
                + " join engine.run r on r.id=n.run_id where n.id=? and a.id=?",
                Boolean.class, status, owner, node, attempt))) throw new DispatchLost();
    }

    public static PlatformException initialUnknown() {
        return new PlatformException(409, "MODEL_RESULT_UNKNOWN", "模型发送结果待核实，不自动重发");
    }

    /**
     * A fixed work value, never an authority receipt. Only this store owns the actual RR commit.
     */
    public record FlowClaim(
            Map<String, Object> run,
            Map<String, Object> node,
            JsonNode scope,
            String budgetExhausted) {}

    public static final class DispatchLost extends RuntimeException {}

    public boolean protectedRun(long run) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "select exists(select 1 from engine.run_authority where run_id=?)",
                        Boolean.class,
                        run));
    }

    private static String dispatchFence() {
        return " and exists(select 1 from engine.run r where r.id=node_instance.run_id and"
                + " r.dispatch_owner=? and r.dispatch_generation=? and"
                + " r.dispatch_until>clock_timestamp())";
    }

    private void requireDispatch(RunDispatch dispatch) {
        if (!Boolean.TRUE.equals(
                db.queryForObject(
                        "select exists(select 1 from engine.run where id=? and dispatch_owner=? and"
                                + " dispatch_generation=? and dispatch_until>clock_timestamp())",
                        Boolean.class,
                        dispatch.runId(),
                        dispatch.owner(),
                        dispatch.generation()))) throw new DispatchLost();
    }

    private Map<String, Object> node(WriteView view, long id) {
        var row = state(view.values(), "node-state", Long.toString(id));
        row.put("definition", view.values().get(key("node-definition", Long.toString(id))));
        for (String field : List.of("output", "error")) {
            String k = key("node-" + field, Long.toString(id));
            row.put(field, view.sqlNulls().contains(k) ? null : view.values().get(k));
        }
        return row;
    }

    private List<Map<String, Object>> flowNodes(WriteView view) {
        return view.values().keySet().stream()
                .filter(k -> k.startsWith("node-state:"))
                .map(k -> node(view, Long.parseLong(k.substring("node-state:".length()))))
                .sorted(Comparator.comparingLong(n -> number(n, "step_seq")))
                .toList();
    }

    private Map<String, Object> flowRun(WriteView view) {
        String id = view.binding().publicId();
        var run = state(view.values(), "run-state", id);
        for (String field : List.of("snapshot", "inputs", "budget", "version-refs"))
            run.put(field.replace('-', '_'), view.values().get(key("run-" + field, id)));
        return run;
    }

    private JsonNode flowScope(WriteView view) {
        var scope = new LinkedHashMap<String, Object>();
        scope.put("input", view.values().get(key("run-inputs", view.binding().publicId())));
        scope.put("context", Map.of());
        for (var node : flowNodes(view)) {
            scope.put(
                    node.get("node_def_id").toString(),
                    Map.of("output", json(node.get("output")), "error", json(node.get("error"))));
            if (node.get("error") != null) scope.put("error", json(node.get("error")));
        }
        return Json.tree(scope);
    }

    private void pureFlow(WriteView view) {
        var run = flowRun(view);
        var snapshot = json(run.get("snapshot"));
        if (!"FULL".equals(run.get("mode"))
                || !"TASK".equals(run.get("trigger_type"))
                || !snapshot.path("type").asText().equals("FLOW")
                || !snapshot.path("definition").isObject()
                || flowNodes(view).isEmpty()) throw denied();
        for (String field :
                List.of(
                        "controlledReplay",
                        "debugSnapshot",
                        "evalCandidate",
                        "controlledDraftId",
                        "debugControls")) if (snapshot.hasNonNull(field)) throw denied();
        var config = snapshot.path("config");
        for (String field :
                List.of(
                        "skillRefs",
                        "promptRefs",
                        "modelEntryId",
                        "modelBinding",
                        "tools",
                        "frozenPrompts",
                        "dataSources")) if (presentDependency(config.get(field))) throw denied();
        for (String field : List.of("modelRefs", "skillRefs", "promptRefs", "ontology"))
            if (presentDependency(snapshot.path("dependencyVersions").get(field))) throw denied();
        for (var node : flowNodes(view)) {
            var def = json(node.get("definition"));
            if (!Set.of("START", "END", "CODE", "CONDITION").contains(def.path("type").asText())
                    || !def.path("type").asText().equals(node.get("node_type"))) throw denied();
            for (String field :
                    List.of("skillRef", "promptRef", "modelOverride", "tools", "dataSources"))
                if (presentDependency(def.get(field))) throw denied();
        }
        var definitions = new LinkedHashMap<String, JsonNode>();
        for (var def : snapshot.path("definition").path("nodes")) {
            if (!Set.of("START", "END", "CODE", "CONDITION").contains(def.path("type").asText())
                    || definitions.put(def.path("id").asText(), def) != null) throw denied();
        }
        if (definitions.size() != flowNodes(view).size()) throw denied();
        for (var node : flowNodes(view))
            if (!Objects.equals(definitions.get(node.get("node_def_id")), node.get("definition")))
                throw denied();
        if (!db.queryForList(
                        "select id from engine.run where id=? and"
                                + " (controlled_draft_skill_version_id is not null or"
                                + " controlled_skill_test_version_id is not null)",
                        Long.class,
                        view.binding().runId())
                .isEmpty()) throw denied();
        if (!db.queryForList(
                        "select id from engine.tool_call where run_id=? limit 1",
                        Long.class,
                        view.binding().runId())
                .isEmpty()) throw denied();
    }

    private static boolean presentDependency(JsonNode n) {
        return n != null && !n.isNull() && !(n.isContainerNode() && n.isEmpty());
    }

    public List<Long> startOwnedFlow(RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(
                proof,
                () -> {
                    var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
                    pureFlow(view);
                    var run = flowRun(view);
                    if (!Set.of("PENDING", "RUNNING").contains(run.get("status"))) return List.of();
                    if ("PENDING".equals(run.get("status"))) {
                        int changed =
                                db.update(
                                        "update engine.run set status='RUNNING' where id=? and"
                                                + " status='PENDING' and dispatch_owner=? and"
                                                + " dispatch_generation=? and"
                                                + " dispatch_until>clock_timestamp()",
                                        dispatch.runId(),
                                        dispatch.owner(),
                                        dispatch.generation());
                        if (changed != 1) throw new DispatchLost();
                        append(view, "run-state", view.binding().publicId(), proof);
                        appendEvent(
                                view,
                                "RUN_STARTED",
                                Map.of(
                                        "runId",
                                        view.binding().publicId(),
                                        "triggerType",
                                        run.get("trigger_type")),
                                proof);
                    }
                    requireDispatch(dispatch);
                    return flowNodes(view).stream()
                            .filter(n -> Set.of("READY", "RUNNING").contains(n.get("status")))
                            .map(n -> number(n, "id"))
                            .toList();
                });
    }

    private boolean prepareFlow(
            WriteView view, long node, RetainedAuthorization proof, RunDispatch dispatch) {
        var decision =
                GraphRules.prepare(
                        view.values()
                                .get(key("run-snapshot", view.binding().publicId()))
                                .path("definition"),
                        node(view, node),
                        flowNodes(view));
        if (!Set.of("WAIT", "READY").contains(decision.state())) {
            if (db.update(
                            "update engine.node_instance set status=?,ended_at=now() where id=? and"
                                    + " status='READY'"
                                    + dispatchFence(),
                            decision.state(),
                            node,
                            dispatch.owner(),
                            dispatch.generation())
                    > 0) {
                append(view, "node-state", Long.toString(node), proof);
                appendEvent(
                        view,
                        decision.state().equals("FAILED") ? "NODE_FAILED" : "NODE_SKIPPED",
                        Map.of("nodeInstanceId", node, "status", decision.state()),
                        proof);
            }
            return false;
        }
        if (decision.winner() != null) {
            if (db.update(
                            "update engine.node_instance set winner_node_id=? where id=? and"
                                    + " status='READY' and winner_node_id is null"
                                    + dispatchFence(),
                            decision.winner(),
                            node,
                            dispatch.owner(),
                            dispatch.generation())
                    > 0) append(view, "node-state", Long.toString(node), proof);
            for (long parent : decision.abandon())
                if (db.update(
                                "update engine.node_instance set status='ABANDONED',ended_at=now()"
                                        + " where id=? and status in ('READY','RUNNING')"
                                        + dispatchFence(),
                                parent,
                                dispatch.owner(),
                                dispatch.generation())
                        > 0) append(view, "node-state", Long.toString(parent), proof);
        }
        return decision.state().equals("READY");
    }

    public FlowClaim prepareAndClaimFlow(
            long node, String owner, RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(
                proof,
                () -> {
                    var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
                    pureFlow(view);
                    if (!"RUNNING".equals(flowRun(view).get("status"))) return null;
                    var row = node(view, node);
                    if (!Set.of("READY", "RUNNING").contains(row.get("status"))) return null;
                    if ("READY".equals(row.get("status"))
                            && !prepareFlow(view, node, proof, dispatch)) {
                        requireDispatch(dispatch);
                        return null;
                    }
                    if (!claimable(view, node)) {
                        requireDispatch(dispatch);
                        return null;
                    }
                    // Preserve the old prepare-commit window: budget failure commits prepare only.
                    int no =
                            db.queryForObject(
                                    "select coalesce(max(attempt_no),0)+1 from engine.attempt where"
                                            + " node_instance_id=?",
                                    Integer.class,
                                    node);
                    if (no == 1) {
                        try {
                            budgetAfter(view, "steps", 1, 25);
                        } catch (PlatformException e) {
                            requireDispatch(dispatch);
                            return new FlowClaim(Map.of(), Map.of(), Json.object(), e.code());
                        }
                    }
                    requireDispatch(dispatch);
                    var claimed = claim(view, node, owner, proof, dispatch);
                    requireDispatch(dispatch);
                    return claimed == null
                            ? null
                            : new FlowClaim(flowRun(view), claimed, flowScope(view), null);
                });
    }

    public boolean commitFlow(
            long node,
            long attempt,
            String owner,
            Object output,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        return commitFlow(node, attempt, owner, output, null, false, dispatch, proof);
    }

    public boolean commitDefaultFlow(
            long node,
            long attempt,
            String owner,
            Object output,
            Object error,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        return commitFlow(node, attempt, owner, output, error, true, dispatch, proof);
    }

    private boolean commitFlow(
            long node,
            long attempt,
            String owner,
            Object output,
            Object error,
            boolean defaultValue,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        return protectedAtomic(
                proof,
                () -> {
                    WriteView view;
                    try {
                        view = writable(dispatch.runId(), proof, node, attempt, dispatch);
                    } catch (DispatchLost lost) {
                        return false;
                    }
                    boolean result =
                            commit(
                                    view,
                                    node,
                                    attempt,
                                    owner,
                                    output,
                                    error,
                                    defaultValue,
                                    dispatch,
                                    proof);
                    requireDispatch(dispatch);
                    return result;
                });
    }

    public void failFlow(
            long node,
            long attempt,
            String owner,
            Object error,
            RunDispatch dispatch,
            RetainedAuthorization proof) {
        protectedAtomic(
                proof,
                () -> {
                    WriteView view;
                    try {
                        view = writable(dispatch.runId(), proof, node, attempt, dispatch);
                    } catch (DispatchLost lost) {
                        return null;
                    }
                    fail(view, node, attempt, owner, error, dispatch, proof);
                    requireDispatch(dispatch);
                    return null;
                });
    }

    public void stopFlowForBudget(
            RunDispatch dispatch, RetainedAuthorization proof, String reason) {
        if (!Set.of("BUDGET_STEPS", "BUDGET_DURATION").contains(reason))
            throw new IllegalArgumentException("FLOW_BUDGET_REASON_REQUIRED");
        protectedAtomic(
                proof,
                () -> {
                    var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
                    pureFlow(view);
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED")
                            .contains(flowRun(view).get("status"))) return null;
                    String actual = null;
                    try {
                        budgetAfter(view, "steps", 1, 25);
                    } catch (PlatformException exhausted) {
                        actual = exhausted.code();
                    }
                    if (!reason.equals(actual))
                        throw new IllegalArgumentException("FLOW_BUDGET_NOT_EXHAUSTED");
                    if (db.update(
                                    "update engine.run set"
                                        + " status='FAILED',stop_reason=?,error=?::jsonb,ended_at=now()"
                                        + " where id=? and dispatch_owner=? and"
                                        + " dispatch_generation=? and"
                                        + " dispatch_until>clock_timestamp()",
                                    reason,
                                    Json.write(Map.of("semantic", reason, "message", "运行预算已耗尽")),
                                    dispatch.runId(),
                                    dispatch.owner(),
                                    dispatch.generation())
                            != 1) throw new DispatchLost();
                    append(view, "run-state", view.binding().publicId(), proof);
                    append(view, "run-error", view.binding().publicId(), proof);
                    for (var node : flowNodes(view))
                        if (db.update(
                                        "update engine.node_instance set"
                                            + " status='CANCELLED',ended_at=now() where id=? and"
                                            + " status not in"
                                            + " ('SUCCEEDED','FAILED','SKIPPED','ABANDONED','CANCELLED')"
                                                + dispatchFence(),
                                        node.get("id"),
                                        dispatch.owner(),
                                        dispatch.generation())
                                > 0) append(view, "node-state", node.get("id").toString(), proof);
                    appendEvent(
                            view,
                            "RUN_FINISHED",
                            Map.of("status", "FAILED", "stopReason", reason),
                            proof);
                    requireDispatch(dispatch);
                    return null;
                });
    }

    public String aggregateFlow(RunDispatch dispatch, RetainedAuthorization proof) {
        return protectedAtomic(
                proof,
                () -> {
                    var view = writable(dispatch.runId(), proof, 0, 0, dispatch);
                    pureFlow(view);
                    String previous = flowRun(view).get("status").toString();
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(previous))
                        return previous;
                    boolean runnable = false;
                    for (var pending : flowNodes(view)) {
                        var fresh = node(view, number(pending, "id"));
                        if ("RUNNING".equals(fresh.get("status"))) runnable = true;
                        if ("READY".equals(fresh.get("status"))
                                && prepareFlow(view, number(fresh, "id"), proof, dispatch))
                            runnable = true;
                    }
                    var nodes = flowNodes(view);
                    String state = GraphRules.aggregate(nodes, runnable);
                    boolean terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state);
                    Object output = GraphRules.aggregateOutput(nodes);
                    if (db.update(
                                    "update engine.run set"
                                        + " status=?,output=?::jsonb,output_ref=?,ended_at=case"
                                        + " when ? then now() else null end,stop_reason=case when ?"
                                        + " then ? else stop_reason end where id=? and"
                                        + " dispatch_owner=? and dispatch_generation=? and"
                                        + " dispatch_until>clock_timestamp()",
                                    state,
                                    output == null ? null : output.toString(),
                                    output == null
                                            ? null
                                            : "pg:run/" + dispatch.runId() + "/output",
                                    terminal,
                                    terminal,
                                    state.equals("FAILED") ? "NODE_FAILED" : "FINAL_ANSWER",
                                    dispatch.runId(),
                                    dispatch.owner(),
                                    dispatch.generation())
                            != 1) throw new DispatchLost();
                    append(view, "run-state", view.binding().publicId(), proof);
                    append(view, "run-output", view.binding().publicId(), proof);
                    if (terminal) appendEvent(view, "RUN_FINISHED", Map.of("status", state), proof);
                    requireDispatch(dispatch);
                    return state;
                });
    }

    public static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public Map<String, Object> lockRun(long id) {
        legacy(id);
        return db.queryForMap("select * from engine.run where id=? for update", id);
    }

    public static com.fasterxml.jackson.databind.JsonNode json(Object value) {
        return value == null ? Json.read("{}") : Json.read(value.toString());
    }

    public void consume(long run, String metric, long amount, long defaultLimit) {
        legacy(run);
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
