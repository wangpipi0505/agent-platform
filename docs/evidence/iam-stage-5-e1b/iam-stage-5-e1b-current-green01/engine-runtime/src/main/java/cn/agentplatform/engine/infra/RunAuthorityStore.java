package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

/** Owns the actual INSERT, never attaches authority to a caller-selected historical run. */
public final class RunAuthorityStore {
    private final DataSource source;
    private final JdbcTemplate db;
    private final Clock clock;
    public RunAuthorityStore(DataSource source,Clock clock){this.source=Objects.requireNonNull(source);this.db=new JdbcTemplate(source);this.clock=Objects.requireNonNull(clock);}

    public long createPublished(RetainedAuthorization admission,JsonNode snapshot,JsonNode inputs,JsonNode refs,
            JsonNode budget,String mode,String requestId,Set<String> labels){
        requireWrite(source);AuthorityContext.requireBound(admission);
        // No REQUIRES_NEW: the run, admission, every scope and caller's subsequent node share rollback.
        var current=admission.current();requirePurpose(current,false);
        Actor initial=admission.requireInitialCeiling();String origin=admission.requireOriginAuthenticationId();
        object(snapshot);object(inputs);object(refs);object(budget);
        String code=text(snapshot,"appCode");if(code.length()>64)throw denied();
        long no=positive(snapshot.get("versionNo")),version=snapshot.has("appVersionId")?positive(snapshot.get("appVersionId")):positive(snapshot.get("versionId"));
        if(snapshot.has("versionId")&&positive(snapshot.get("versionId"))!=version)throw denied();
        if(positive(refs.get("appVer"))!=no)throw denied();
        String rootId=code+"@"+no,candidate=text(snapshot,"candidateSha256");hash(candidate);
        if(snapshot.has("appVersionKey")&&!rootId.equals(text(snapshot,"appVersionKey")))throw denied();
        same(new ResourceBinding("app-version",rootId,candidate),current.call().resource());
        if(!"FULL".equals(mode)||requestId!=null&&(requestId.isBlank()||requestId.length()>128)||labels==null)throw denied();
        labels.forEach(RunAuthorityStore::nonblank);validateBudget(snapshot,budget);
        // External sources and historical references need their own scopes before this seam can accept them.
        if(snapshot.hasNonNull("debugSnapshot")||snapshot.hasNonNull("evalCandidate")||snapshot.hasNonNull("controlledDraftId")
                || !refs.path("ontology").isMissingNode()&&(!refs.path("ontology").isArray()||!refs.path("ontology").isEmpty())
                || snapshot.path("config").hasNonNull("dataSources")||inputs.hasNonNull("materialRefs")||inputs.hasNonNull("historyRefs"))throw denied();
        String publicId="r_"+UUID.randomUUID().toString().replace("-","");
        long id=db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,request_id) values(?,?,?,'TASK','PENDING',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?) returning id",Long.class,
            publicId,code,version,initial.principalId(),initial.tenantId(),"pg:run/"+publicId+"/input",Json.write(refs),Json.write(Map.of("roles",current.actor().roles(),"attributes",current.actor().attributes(),"snapshotAt",clock.instant().toString())),Json.write(budget),Json.write(snapshot),Json.write(inputs),mode,requestId);
        String snapshotSha=MaterialScopeStore.payloadDigest(db,"run-snapshot",id);
        db.update("insert into engine.run_authority(run_id,tenant_id,principal_id,grant_ref,grant_expires_at,initial_ceiling,resource_digest,source_kind,origin_authentication_id,app_version_id,app_version_no,root_kind,root_id,initial_ceiling_sha256,accepted_snapshot_sha256,admission_decision_id,authorization_version,operation_id) values(?,?,?,?,?,?::jsonb,?,'PORTAL',?,?,?,'app-version',?,?,?,?,?,?)",
            id,initial.tenantId(),initial.principalId(),current.delegation().value(),Timestamp.from(current.delegation().expiresAt()),Json.write(initial),candidate,origin,version,no,rootId,MaterialAuthorization.ceilingSha256(initial),snapshotSha,current.decisionId(),current.authorizationVersion(),current.operation().operationId());
        var materialStore=new MaterialScopeStore(source,clock);
        var entitlements=new TreeMap<>(initial.attributes());entitlements.remove("dataLabels");
        var obligations=new TreeSet<>(initial.attributes().getOrDefault("dataLabels",Set.of()));obligations.addAll(current.actor().attributes().getOrDefault("dataLabels",Set.of()));obligations.addAll(labels);
        String parent=null;
        for(String kind:MaterialScopeStore.KINDS){
            var ref=new MaterialRef("engine",kind,publicId,snapshotSha,MaterialScopeStore.payloadDigest(db,kind,id));
            var scope=new MaterialScopeV1(1,ref,new SubjectKey(initial.tenantId(),initial.principalId()),current.decisionId(),current.authorizationVersion(),MaterialAuthorization.ceilingSha256(initial),initial.roles(),entitlements,obligations,kind.equals("run-version-refs")?List.of(parent):List.of(),null);
            scope=MaterialScopeStore.withDigest(scope);MaterialAuthorization.require(scope,current,clock);materialStore.write(scope);
            if(kind.equals("run-snapshot"))parent=scope.scopeSha256();
        }
        AuthorityContext.requireBound(admission);
        return id;
    }

    public record Binding(long runId,String publicId,SubjectKey owner,DelegationRef delegation,Actor initial,
        ResourceBinding root,long appVersionId,long appVersionNo,String initialSha,String snapshotSha,String originId,
        String admissionDecision,String authorizationVersion,String operationId,long checkVersion,String blockedReason,
        Instant retryNotBefore,List<MaterialScopeV1> scopes) {
        public Binding {scopes=List.copyOf(scopes);}
    }
    public Binding load(long runId){return MaterialScopeStore.snapshot(source,()->load(db,runId,true));}
    static Binding load(JdbcTemplate db,long runId,boolean complete){
        var rows=db.queryForList("select r.id,r.public_id,r.tenant_id as run_tenant,r.principal_id as run_principal,r.app_code,r.app_version_id as run_version,a.* from engine.run r join engine.run_authority a on a.run_id=r.id where r.id=?",runId);
        if(rows.size()!=1)throw denied();var r=rows.getFirst();
        Actor initial=parseInitial(Json.read(r.get("initial_ceiling").toString()));
        var owner=new SubjectKey(str(r,"tenant_id"),str(r,"principal_id"));same(owner,new SubjectKey(initial.tenantId(),initial.principalId()));
        same(owner,new SubjectKey(str(r,"run_tenant"),str(r,"run_principal")));
        long version=((Number)r.get("app_version_id")).longValue(),no=((Number)r.get("app_version_no")).longValue();
        if(version<1||no<1||!Objects.equals(r.get("run_version"),r.get("app_version_id")))throw denied();
        same("app-version",str(r,"root_kind"));same(str(r,"app_code")+"@"+no,str(r,"root_id"));same("PORTAL",str(r,"source_kind"));
        String initialSha=str(r,"initial_ceiling_sha256"),snapshotSha=str(r,"accepted_snapshot_sha256"),candidate=str(r,"resource_digest");
        hash(initialSha);hash(snapshotSha);hash(candidate);same(initialSha,MaterialAuthorization.ceilingSha256(initial));
        var b=new Binding(runId,str(r,"public_id"),owner,new DelegationRef(str(r,"grant_ref"),owner,instant(r,"grant_expires_at")),initial,
            new ResourceBinding("app-version",str(r,"root_id"),candidate),version,no,initialSha,snapshotSha,str(r,"origin_authentication_id"),str(r,"admission_decision_id"),str(r,"authorization_version"),str(r,"operation_id"),((Number)r.get("check_version")).longValue(),(String)r.get("blocked_reason"),instant(r,"retry_not_before"),List.of());
        if(!complete)return b;
        var scopes=MaterialScopeStore.scopes(db,b);
        return new Binding(b.runId,b.publicId,b.owner,b.delegation,b.initial,b.root,b.appVersionId,b.appVersionNo,b.initialSha,b.snapshotSha,b.originId,b.admissionDecision,b.authorizationVersion,b.operationId,b.checkVersion,b.blockedReason,b.retryNotBefore,scopes);
    }
    /** Result is usable only if this short CAS wins. Network calls must not hold a PG transaction. */
    public boolean recordCheck(Binding before,RetainedAuthorization proof,String blocked,Duration backoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("CURRENT_REQUIRES_NO_CALLER_TRANSACTION");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        return Boolean.TRUE.equals(tx.execute(status->{
            var now=load(db,before.runId,true);
            // Full immutable metadata is reread, so a future authorized generation switch cannot reuse this result.
            if(!now.equals(before))return false;
            if(proof!=null){proof.requireVerified();strict(before,proof);before.scopes.forEach(s->MaterialAuthorization.require(s,proof.current(),clock));}
            Instant checked=clock.instant();Instant retry="AUTHORITY_UNAVAILABLE".equals(blocked)?checked.plus(backoff):null;
            return db.update("update engine.run_authority set last_decision_id=?,last_checked_at=?,blocked_reason=?,retry_not_before=?,check_version=check_version+1 where run_id=? and check_version=?",
                proof==null?null:proof.current().decisionId(),Timestamp.from(checked),blocked,retry==null?null:Timestamp.from(retry),before.runId,before.checkVersion)==1;
        }));
    }
    public static void strict(Binding b,RetainedAuthorization proof){
        proof.requireVerified();var a=proof.current();requirePurpose(a,true);same(b.root,a.call().resource());same(b.delegation,a.delegation());
        same(b.initial,proof.requireInitialCeiling());same(b.initialSha,MaterialAuthorization.ceilingSha256(proof.requireInitialCeiling()));same(b.originId,proof.requireOriginAuthenticationId());
    }
    static void requirePurpose(VerifiedAuthority a,boolean localOnly){
        if(a==null||a.call()==null||a.operation()==null||a.call().resource()==null)throw denied();
        boolean local=ServiceRoutePolicy.localRunAdvance(a.caller(),a.call(),"delegated",a.operation().sourceKind(),a.operation().originKind());
        boolean main="gateway".equals(a.caller())&&"engine".equals(a.call().target())&&"proxy.engine.post.RunController.run".equals(a.call().action())&&"app-version".equals(a.call().resource().kind())&&"PORTAL".equals(a.operation().sourceKind())&&"PORTAL".equals(a.operation().originKind());
        if(localOnly?!local:!main)throw denied();same(a.call().resource(),a.operation().resource());
    }
    static void requireWrite(DataSource source){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("SAME_DATASOURCE_WRITE_TRANSACTION_REQUIRED");
        var connection=DataSourceUtils.getConnection(source);
        try{if(connection.getAutoCommit()||!DataSourceUtils.isConnectionTransactional(connection,source))throw new IllegalStateException("WRITE_TRANSACTION_REQUIRED");}
        catch(SQLException e){throw new IllegalStateException(e);}finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    private void validateBudget(JsonNode snapshot,JsonNode budget){
        object(budget.get("limits"));object(budget.get("consumed"));
        var defaults=Map.of("maxSteps",25L,"modelCalls",40L,"totalTokens",200000L,"totalDurationMs",600000L,"maxDepth",2L);
        if(budget.get("limits").size()!=5||budget.get("consumed").size()!=4)throw denied();
        defaults.forEach((key,value)->{long expected=snapshot.path("config").path("budgets").has(key)?positive(snapshot.path("config").path("budgets").get(key)):value;if(positive(budget.get("limits").get(key))!=expected)throw denied();});
        for(String key:List.of("steps","modelCalls","tokens","depth")){var v=budget.get("consumed").get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()!=0)throw denied();}
        try{Instant deadline=Instant.parse(text(budget,"deadline")),now=clock.instant();if(!deadline.isAfter(now)||deadline.isAfter(now.plusMillis(positive(budget.get("limits").get("totalDurationMs")))))throw denied();}catch(DateTimeException|ArithmeticException e){throw denied();}
    }
    private static Actor parseInitial(JsonNode n){
        object(n);if(n.size()!=4)throw denied();var attributes=n.get("attributes");object(attributes);var attrs=new TreeMap<String,Set<String>>();
        attributes.fields().forEachRemaining(e->{if(!Set.of("region","dataLabels","bizSystemCode","userId").contains(e.getKey()))throw denied();var values=strings(e.getValue());if(Set.of("bizSystemCode","userId").contains(e.getKey())&&values.size()!=1)throw denied();attrs.put(e.getKey(),values);});
        return new Actor(text(n,"tenantId"),text(n,"principalId"),strings(n.get("roles")),attrs);
    }
    private static Set<String> strings(JsonNode n){if(n==null||!n.isArray())throw denied();var result=new TreeSet<String>();for(var v:n){if(!v.isTextual()||v.textValue().isBlank()||!result.add(v.textValue()))throw denied();}return result;}
    static long positive(JsonNode v){if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw denied();return v.longValue();}
    static void object(JsonNode n){if(n==null||!n.isObject())throw denied();}
    static String text(JsonNode n,String key){var v=n.get(key);if(v==null||!v.isTextual())throw denied();return nonblank(v.textValue());}
    static String nonblank(String v){if(v==null||v.isBlank())throw denied();return v;}
    static String str(Map<String,Object> r,String key){return nonblank((String)r.get(key));}
    static Instant instant(Map<String,Object> r,String key){return r.get(key)==null?null:((Timestamp)r.get(key)).toInstant();}
    static void hash(String s){if(s==null||!s.matches("[a-f0-9]{64}"))throw denied();}
    static void same(Object a,Object b){if(!Objects.equals(a,b))throw denied();}
    public static PlatformException denied(){return new PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");}
}

