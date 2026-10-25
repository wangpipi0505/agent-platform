package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static cn.agentplatform.engine.infra.RunAuthorityStore.*;

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
        if(!(tm instanceof DataSourceTransactionManager manager)||manager.getDataSource()!=source)throw new IllegalArgumentException("SAME_DATASOURCE_MANAGER_REQUIRED");
        protectedTx=new TransactionTemplate(tm);protectedTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        materials=new MaterialScopeStore(source,clock);
    }

    public JdbcTemplate db() {
        return db;
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

    public boolean protectedRun(String id,Actor actor){
        var rows=db.queryForList("select r.id,exists(select 1 from engine.run_authority a where a.run_id=r.id) as protected from engine.run r where r.public_id=? and r.tenant_id=? and r.principal_id=?",id,actor.tenantId(),actor.principalId());
        if(rows.isEmpty())throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");return Boolean.TRUE.equals(rows.getFirst().get("protected"));
    }
    private void legacy(long run){if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from engine.run_authority where run_id=?)",Boolean.class,run)))throw denied();}
    private void legacyNode(long node){legacy(db.queryForObject("select run_id from engine.node_instance where id=?",Long.class,node));}
    private void legacyAttempt(long attempt){legacy(db.queryForObject("select n.run_id from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where a.id=?",Long.class,attempt));}

    public record PublishedView(Map<String,Object> run,List<Map<String,Object>> nodes,List<Map<String,Object>> events,RetainedAuthorization proof){
        public void requireCurrent(){AuthorityContext.requireBound(proof);proof.requireVerified();}
    }
    /** Only the original main POST response purpose is accepted. Refresh occurs before the single
     * RR snapshot; callers in a transaction must finish it before composing this response. */
    public PublishedView statusPublished(String id,Actor actor){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("STATUS_REFRESH_REQUIRES_NO_TRANSACTION");
        var ids=db.queryForList("select r.id from engine.run r join engine.run_authority a on a.run_id=r.id where r.public_id=? and r.tenant_id=? and r.principal_id=?",Long.class,id,actor.tenantId(),actor.principalId());
        if(ids.size()!=1)throw denied();var before=new RunAuthorityStore(source,clock).load(ids.getFirst());
        var main=AuthorityContext.current();requirePurpose(main,false);materials.requireReader(before,main);
        var proof=AuthorityContext.requireInitialFor(actor,before.root());AuthorityContext.requireBound(proof);
        var view=MaterialScopeStore.snapshot(source,()->{
            var b=RunAuthorityStore.load(db,before.runId(),true);same(b.root(),before.root());same(b.owner(),before.owner());materials.requireReader(b,proof.current());
            var unsupported=db.queryForMap("select exists(select 1 from engine.tool_call where run_id=r.id) as tools,controlled_draft_skill_version_id,controlled_skill_test_version_id from engine.run r where id=?",b.runId());
            if(Boolean.TRUE.equals(unsupported.get("tools"))||unsupported.get("controlled_draft_skill_version_id")!=null||unsupported.get("controlled_skill_test_version_id")!=null)throw denied();
            var scopes=materials.currentScopes(b);var nodeIds=db.queryForList("select id from engine.node_instance where run_id=? order by step_seq",Long.class,b.runId());var eventIds=db.queryForList("select id from engine.run_event where run_id=? order by seq",Long.class,b.runId());
            var needed=new ArrayList<MaterialScopeV1>();for(var s:scopes)if(!s.material().kind().startsWith("attempt-"))needed.add(s);
            requireComplete(b,needed,nodeIds,List.of(),eventIds);
            // Every metadata ref is checked before any payload. A missing nullable scope is denial.
            needed.forEach(s->MaterialAuthorization.require(s,proof.current(),clock));AuthorityContext.requireBound(proof);
            var values=readValues(b,needed,proof);var run=state(values,"run-state",b.publicId());
            run.put("id",b.runId());for(String kind:List.of("run-inputs","run-output","run-error","run-version-refs")){var value=values.get(key(kind,b.publicId()));run.put(kind.substring(4).replace('-','_'),value.isNull()?null:value);}
            var nodes=new ArrayList<Map<String,Object>>();for(long node:nodeIds){var row=state(values,"node-state",Long.toString(node));for(String kind:List.of("node-output","node-error")){var value=values.get(key(kind,Long.toString(node)));row.put(kind.substring(5),value.isNull()?null:value);}nodes.add(row);}
            var events=new ArrayList<Map<String,Object>>();for(long event:eventIds){var row=state(values,"run-event",Long.toString(event));var visible=new LinkedHashMap<String,Object>();for(String field:List.of("seq","type","payload","created_at"))visible.put(field,row.get(field));events.add(visible);}
            var result=new PublishedView(run,List.copyOf(nodes),List.copyOf(events),proof);result.requireCurrent();return result;
        });
        // A proof expiring during the actual read transaction's completion is never delivered.
        view.requireCurrent();return view;
    }
    private static String key(String kind,String locator){return kind+":"+locator;}
    private Map<String,JsonNode> readValues(Binding b,List<MaterialScopeV1> scopes,RetainedAuthorization proof){
        var values=new LinkedHashMap<String,JsonNode>();for(var s:scopes)values.put(key(s.material().kind(),s.material().id()),materials.readCurrent(b,s,proof));return values;
    }
    private static Map<String,Object> state(Map<String,JsonNode> values,String kind,String locator){
        var n=values.get(key(kind,locator));if(n==null||!n.isObject())throw denied();var row=new LinkedHashMap<String,Object>();
        n.fields().forEachRemaining(e->{var v=e.getValue();Object value=v.isNull()?null:v.isTextual()?v.textValue():v.isBoolean()?v.booleanValue():v.isIntegralNumber()?v.longValue():v;
            if(value instanceof String text&&Set.of("started_at","ended_at","created_at","lease_until").contains(e.getKey()))value=java.sql.Timestamp.from(Instant.parse(text));row.put(e.getKey(),value);});return row;
    }
    private static void requireComplete(Binding b,List<MaterialScopeV1> scopes,List<Long> nodes,List<Long> attempts,List<Long> events){
        var expected=new HashSet<String>();for(String kind:MaterialScopeStore.KINDS)expected.add(key(kind,b.publicId()));for(String kind:MaterialScopeStore.RUN_KINDS)expected.add(key(kind,b.publicId()));
        for(long node:nodes)for(String kind:MaterialScopeStore.NODE_KINDS)expected.add(key(kind,Long.toString(node)));
        for(long attempt:attempts)for(String kind:MaterialScopeStore.ATTEMPT_KINDS)expected.add(key(kind,Long.toString(attempt)));
        for(long event:events)expected.add(key("run-event",Long.toString(event)));
        var actual=new HashSet<String>();for(var s:scopes)if(!actual.add(key(s.material().kind(),s.material().id())))throw denied();same(expected,actual);
    }
    private record WriteView(Binding binding,List<MaterialScopeV1> scopes,Map<String,JsonNode> values){}
    private WriteView writable(long run,RetainedAuthorization proof){
        // Run then node lock order. Only control metadata is read before proof and scope checks.
        if(db.queryForList("select id from engine.run where id=? for update",Long.class,run).size()!=1)throw denied();
        var b=RunAuthorityStore.load(db,run,true);materials.requireWriter(b,proof,false);
        var nodes=db.queryForList("select id from engine.node_instance where run_id=? order by id for update",Long.class,run);
        var attempts=db.queryForList("select a.id from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=? order by a.id",Long.class,run);
        var events=db.queryForList("select id from engine.run_event where run_id=? order by id",Long.class,run);
        var scopes=materials.currentScopes(b);requireComplete(b,scopes,nodes,attempts,events);scopes.forEach(s->MaterialAuthorization.require(s,proof.current(),clock));proof.requireVerified();
        return new WriteView(b,new ArrayList<>(scopes),readValues(b,scopes,proof));
    }
    private <T>T protectedAtomic(RetainedAuthorization proof,Supplier<T> work){
        if(proof==null)throw denied();proof.requireVerified();
        if(TransactionSynchronizationManager.isActualTransactionActive()){
            requireWrite(source);var c=DataSourceUtils.getConnection(source);try{if(c.getTransactionIsolation()<java.sql.Connection.TRANSACTION_REPEATABLE_READ)throw new IllegalStateException("REPEATABLE_READ_REQUIRED");}catch(java.sql.SQLException e){throw new IllegalStateException(e);}finally{DataSourceUtils.releaseConnection(c,source);}
            // REQUIRED joining cannot observe its caller's later commit. N2's concrete outer
            // owners need their own retained-proof completion seam before composing writes.
            throw new IllegalStateException("PROTECTED_WRITE_REQUIRES_TOP_LEVEL_TRANSACTION");
        }
        try{var result=protectedTx.execute(s->{try{var value=work.get();proof.requireVerified();return value;}catch(RuntimeException|Error e){((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();throw e;}});proof.requireVerified();return result;}
        catch(RuntimeException|Error e){if(TransactionSynchronizationManager.hasResource(source))((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();throw e;}
    }
    private void append(WriteView view,String kind,String locator,RetainedAuthorization proof){
        // Later events in this same action consume facts just materialized above. Keep those real
        // persisted scopes as direct parents instead of silently reverting to the entry snapshot.
        view.scopes().add(materials.append(view.binding(),kind,locator,proof,view.scopes()));
    }
    private long appendEvent(WriteView view,String type,Object payload,RetainedAuthorization proof){
        int seq=db.queryForObject("update engine.run set event_seq=event_seq+1 where id=? returning event_seq",Integer.class,view.binding().runId());
        long id=db.queryForObject("insert into engine.run_event(run_id,seq,type,payload) values(?,?,?,?::jsonb) returning id",Long.class,view.binding().runId(),seq,type,Json.write(payload));
        append(view,"run-event",Long.toString(id),proof);append(view,"run-state",view.binding().publicId(),proof);return seq;
    }
    public int event(long run,String type,Object payload,RetainedAuthorization proof){return protectedAtomic(proof,()->(int)appendEvent(writable(run,proof),type,payload,proof));}

    /** Concrete insertion seam for N3. It never attaches initial authority to an existing node. */
    public long createNode(long run,String nodeDefId,int stepSeq,String nodeType,JsonNode definition,RetainedAuthorization admission){
        requireWrite(source);try{
            AuthorityContext.requireBound(admission);db.queryForObject("select id from engine.run where id=? for update",Long.class,run);
            var b=RunAuthorityStore.load(db,run,true);materials.requireWriter(b,admission,true);var parents=materials.currentScopes(b);parents.forEach(s->MaterialAuthorization.require(s,admission.current(),clock));
            // A run missing earlier material metadata cannot acquire new nodes as a repair path.
            requireComplete(b,parents,db.queryForList("select id from engine.node_instance where run_id=?",Long.class,run),db.queryForList("select a.id from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=?",Long.class,run),db.queryForList("select id from engine.run_event where run_id=?",Long.class,run));
            object(definition);long node=db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition) values(?,?,?,?,'READY',?::jsonb) returning id",Long.class,run,nodeDefId,stepSeq,nodeType,Json.write(definition));
            for(String kind:MaterialScopeStore.NODE_KINDS)materials.append(b,kind,Long.toString(node),admission,parents);AuthorityContext.requireBound(admission);return node;
        }catch(RuntimeException|Error e){((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();throw e;}
    }
    public Map<String,Object> claim(long node,String owner,RetainedAuthorization proof){
        long run=db.queryForObject("select run_id from engine.node_instance where id=?",Long.class,node);
        return protectedAtomic(proof,()->{
            var view=writable(run,proof);var runState=state(view.values(),"run-state",view.binding().publicId());if(!"RUNNING".equals(runState.get("status")))return null;
            var row=state(view.values(),"node-state",Long.toString(node));if(!Set.of("READY","RUNNING").contains(row.get("status")))return null;
            if("RUNNING".equals(row.get("status"))&&Boolean.TRUE.equals(db.queryForObject("select lease_until>now() from engine.node_instance where id=?",Boolean.class,node)))return null;
            var rules=view.values().get(key("run-snapshot",view.binding().publicId())).path("definition").path("executionRules");int max=rules.path("parallel").asBoolean(true)?rules.path("maxParallel").asInt(8):1;
            int active=db.queryForObject("select count(*) from engine.node_instance where run_id=? and status='RUNNING' and lease_until>now()",Integer.class,run);if(active>=max)return null;
            String generation=owner+"_"+UUID.randomUUID().toString().substring(0,8);db.update("update engine.node_instance set status='RUNNING',lease_owner=?,lease_until=now()+interval '90 seconds',started_at=coalesce(started_at,now()) where id=?",generation,node);
            int no=db.queryForObject("select coalesce(max(attempt_no),0)+1 from engine.attempt where node_instance_id=?",Integer.class,node);
            if(no==1)consume(view,"steps",1,25,proof);
            long attempt=db.queryForObject("insert into engine.attempt(node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,request_snapshot) values(?,?,'RUNNING',?,'pending','{}'::jsonb) returning id",Long.class,node,no,Digest.sha256("node:"+node));
            db.update("update engine.attempt set request_snapshot_ref=? where id=?","pg:attempt/"+attempt,attempt);
            append(view,"node-state",Long.toString(node),proof);for(String kind:MaterialScopeStore.ATTEMPT_KINDS)append(view,kind,Long.toString(attempt),proof);
            appendEvent(view,"NODE_STARTED",Map.of("nodeInstanceId",node,"attemptId",attempt,"attemptNo",no),proof);
            var result=state(Map.of(key("node-state",Long.toString(node)),MaterialScopeStore.payload(db,"node-state",Long.toString(node),run)),"node-state",Long.toString(node));
            result.putAll(db.queryForMap("select lease_owner,lease_until from engine.node_instance where id=?",node));
            result.put("definition",view.values().get(key("node-definition",Long.toString(node))));result.put("output",view.values().get(key("node-output",Long.toString(node))));result.put("error",view.values().get(key("node-error",Long.toString(node))));result.put("attemptId",attempt);proof.requireVerified();return result;
        });
    }
    private void relation(long run,long node,long attempt){
        var ids=db.queryForList("select a.id from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where a.id=? and n.id=? and n.run_id=?",Long.class,attempt,node,run);if(ids.size()!=1)throw denied();
    }
    public boolean commit(long run,long node,long attempt,String owner,Object output,RetainedAuthorization proof){
        return protectedAtomic(proof,()->{
            var view=writable(run,proof);relation(run,node,attempt);
            int n=db.update("update engine.node_instance set status='SUCCEEDED',committed_attempt_id=?,committed_output_ref=?,output=?::jsonb,output_digest=?,ended_at=now() where id=? and run_id=? and committed_attempt_id is null and status='RUNNING' and lease_owner=? and lease_until>now()",attempt,"pg:node/"+node,Json.write(output),Digest.sha256(Json.write(output)),node,run,owner);
            if(n==0){db.update("insert into engine.attempt_note(node_instance_id,attempt_id,kind) values(?,?,'SUPERSEDED_SUBMIT')",node,attempt);return false;}
            db.update("update engine.attempt set status='SUCCEEDED',ended_at=now() where id=?",attempt);
            append(view,"node-state",Long.toString(node),proof);append(view,"node-output",Long.toString(node),proof);append(view,"attempt-state",Long.toString(attempt),proof);
            appendEvent(view,"LOGIC_RESULT_COMMITTED",Map.of("nodeInstanceId",node,"attemptId",attempt),proof);appendEvent(view,"NODE_FINISHED",Map.of("nodeInstanceId",node,"status","SUCCEEDED"),proof);return true;
        });
    }
    public void fail(long run,long node,long attempt,String owner,Object error,RetainedAuthorization proof){
        protectedAtomic(proof,()->{
            var view=writable(run,proof);relation(run,node,attempt);
            int n=db.update("update engine.node_instance set status='FAILED',error=?::jsonb,ended_at=now() where id=? and run_id=? and status='RUNNING' and lease_owner=? and lease_until>now()",Json.write(error),node,run,owner);
            // Preserve the original failure fact even when this generation lost the node CAS.
            db.update("update engine.attempt set status='FAILED',error=?::jsonb,ended_at=now() where id=?",Json.write(error),attempt);
            append(view,"attempt-state",Long.toString(attempt),proof);append(view,"attempt-error",Long.toString(attempt),proof);
            if(n>0){append(view,"node-state",Long.toString(node),proof);append(view,"node-error",Long.toString(node),proof);appendEvent(view,"NODE_FAILED",Map.of("nodeInstanceId",node,"error",error),proof);}return null;
        });
    }
    public void consume(long run,String metric,long amount,long defaultLimit,RetainedAuthorization proof){protectedAtomic(proof,()->{consume(writable(run,proof),metric,amount,defaultLimit,proof);return null;});}
    private void consume(WriteView view,String metric,long amount,long defaultLimit,RetainedAuthorization proof){
        if(!Set.of("steps","tokens","depth","modelCalls").contains(metric))throw denied();
        var budget=(com.fasterxml.jackson.databind.node.ObjectNode)view.values().get(key("run-budget",view.binding().publicId())).deepCopy();
        String limitName=switch(metric){case "steps"->"maxSteps";case "tokens"->"totalTokens";case "depth"->"maxDepth";default->metric;};
        long limit=budget.path("limits").path(limitName).asLong(defaultLimit),current=budget.path("consumed").path(metric).asLong();
        if(amount<0||amount>limit||current>limit-amount)throw new PlatformException(409,"BUDGET_"+metric.toUpperCase(),"运行预算已耗尽："+metric);
        var state=state(view.values(),"run-state",view.binding().publicId());if(state.get("started_at") instanceof java.sql.Timestamp started&&clock.millis()-started.getTime()>budget.path("limits").path("totalDurationMs").asLong(600000))throw new PlatformException(409,"BUDGET_DURATION","运行时间预算已耗尽");
        ((com.fasterxml.jackson.databind.node.ObjectNode)budget.path("consumed")).put(metric,current+amount);db.update("update engine.run set budget=?::jsonb where id=?",Json.write(budget),view.binding().runId());append(view,"run-budget",view.binding().publicId(),proof);
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
