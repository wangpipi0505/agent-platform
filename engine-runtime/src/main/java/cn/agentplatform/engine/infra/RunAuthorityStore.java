package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.domain.InitialChatRules;
import cn.agentplatform.engine.domain.RunObservationMetadata;
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
        return create(admission,snapshot,inputs,refs,budget,mode,requestId,labels,false,null);
    }
    public long createPublishedFirstChat(RetainedAuthorization admission,JsonNode snapshot,JsonNode inputs,JsonNode refs,
            JsonNode budget,String mode,String requestId,Set<String> labels){
        return create(admission,snapshot,inputs,refs,budget,mode,requestId,labels,true,null);
    }
    public long createEvaluation(RetainedAuthorization admission, JsonNode snapshot, JsonNode request,
                                 JsonNode refs, JsonNode budget) {
        object(request);
        return create(admission, snapshot, request.path("inputs"), refs, budget, "FULL",
                text(request, "requestId"), InitialChatRules.evaluationLabels(snapshot), false, request);
    }
    private long create(RetainedAuthorization admission,JsonNode snapshot,JsonNode inputs,JsonNode refs,
            JsonNode budget,String mode,String requestId,Set<String> labels,boolean firstChat,JsonNode evaluation){
        requireWrite(source);
        try{return createBound(admission,snapshot,inputs,refs,budget,mode,requestId,labels,firstChat,evaluation);}
        catch(RuntimeException|Error failure){
            // Spring 6.2.7 DataSourceTransactionObject.isRollbackOnly reads this exact holder.
            // A caller catching an authorization failure must not commit a partially admitted run.
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
            throw failure;
        }
    }
    private long createBound(RetainedAuthorization admission,JsonNode snapshot,JsonNode inputs,JsonNode refs,
            JsonNode budget,String mode,String requestId,Set<String> labels,boolean firstChat,JsonNode evaluation){
        AuthorityContext.requireBound(admission);
        // No REQUIRES_NEW: the run, admission, every scope and caller's subsequent node share rollback.
        var current=admission.current();requirePurpose(current,false);
        Actor initial=admission.requireInitialCeiling();String origin=admission.requireOriginAuthenticationId();
        object(snapshot);object(inputs);object(refs);object(budget);
        boolean portalSession = PortalRuntimeRoutes.isSessionTurn(current);
        if (portalSession) InitialChatRules.requirePortalSources(snapshot, inputs);
        else if (firstChat) InitialChatRules.requireSources(snapshot, inputs);
        String code=MetadataSelectors.code(text(snapshot,"appCode"));
        Long no = null, version = null, revision = null;
        ResourceBinding root;
        String candidate = text(snapshot, "candidateSha256");
        hash(candidate);
        if (evaluation == null) {
            no = positive(snapshot.get("versionNo"));
            version = snapshot.has("appVersionId") ? positive(snapshot.get("appVersionId")) : positive(snapshot.get("versionId"));
            if (snapshot.has("versionId") && positive(snapshot.get("versionId")) != version) throw denied();
            same(no, positive(refs.get("appVer")));
            if (snapshot.has("appVersionKey")) same(code + "@" + no, text(snapshot, "appVersionKey"));
            root = portalSession ? PortalRuntimeRoutes.sessionResource(code, snapshot.path("chatSession").path("sessionId").asText())
                    : new ResourceBinding("app-version", code + "@" + no, candidate);
        } else {
            if ("CHAT".equals(text(snapshot,"type"))) InitialChatRules.requireEvaluationSources(snapshot, inputs);
            else cn.agentplatform.engine.domain.GraphRules.requirePublishedFlow(snapshot);
            same(evaluation.has("turnNo") ? "evaluation.turn" : "evaluation.run", current.call().action());
            same(evaluation.has("turnNo"), snapshot.has("chatSession"));
            revision = positive(snapshot.get("draftRev"));
            root = EvaluationCandidateBinding.of(code, revision, candidate);
            same(root, EvaluationCandidateBinding.from(evaluation));
        }
        same(root,current.call().resource());
        if(!"FULL".equals(mode)||requestId!=null&&(requestId.isBlank()||requestId.length()>128)||labels==null)throw denied();
        labels.forEach(RunAuthorityStore::nonblank);validateBudget(snapshot,budget);
        // External sources and historical references need their own scopes before this seam can accept them.
        if(snapshot.hasNonNull("debugSnapshot")||snapshot.hasNonNull("evalCandidate")||snapshot.hasNonNull("controlledDraftId")
                || !refs.path("ontology").isMissingNode()&&(!refs.path("ontology").isArray()||!refs.path("ontology").isEmpty())
                || snapshot.path("config").hasNonNull("dataSources")||inputs.hasNonNull("materialRefs")||inputs.hasNonNull("historyRefs"))throw denied();
        String publicId="r_"+UUID.randomUUID().toString().replace("-","");
        String trigger = evaluation != null ? "EVALUATION" : firstChat ? "CHAT" : "TASK";
        String candidateRef = evaluation == null ? null : Json.write(Map.of("appCode", code, "draftRev", revision,
                "candidateSha256", candidate));
        long id=db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,request_id,eval_candidate) values(?,?,?,?,'PENDING',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb) returning id",Long.class,
            publicId,code,version,trigger,initial.principalId(),initial.tenantId(),"pg:run/"+publicId+"/input",Json.write(refs),Json.write(Map.of("tenantId",initial.tenantId(),"principalId",initial.principalId(),"roles",current.actor().roles(),"attributes",current.actor().attributes(),"snapshotAt",clock.instant().toString())),Json.write(budget),Json.write(snapshot),Json.write(inputs),mode,requestId,candidateRef);
        String snapshotSha=MaterialScopeStore.payloadDigest(db,"run-snapshot",id);
        db.update("insert into engine.run_authority(run_id,tenant_id,principal_id,grant_ref,grant_expires_at,initial_ceiling,resource_digest,source_kind,origin_authentication_id,app_version_id,app_version_no,root_kind,root_id,initial_ceiling_sha256,accepted_snapshot_sha256,admission_decision_id,authorization_version,operation_id,candidate_rev) values(?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,initial.tenantId(),initial.principalId(),current.delegation().value(),Timestamp.from(current.delegation().expiresAt()),Json.write(initial),root.versionDigest(),current.operation().originKind(),origin,version,no,root.kind(),root.id(),MaterialAuthorization.ceilingSha256(initial),snapshotSha,current.decisionId(),current.authorizationVersion(),current.operation().operationId(),revision);
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
        // This private path owns the INSERT above; no historical run can be given missing scopes.
        var binding=load(db,id,true);materialStore.requireWriter(binding,admission,true);
        for(String kind:MaterialScopeStore.RUN_KINDS)materialStore.append(binding,kind,publicId,admission,binding.scopes());
        AuthorityContext.requireBound(admission);
        return id;
    }

    public record Binding(long runId,String publicId,SubjectKey owner,DelegationRef delegation,Actor initial,
        ResourceBinding root,long appVersionId,long appVersionNo,String initialSha,String snapshotSha,String originId,
        String admissionDecision,String authorizationVersion,String operationId,long checkVersion,String blockedReason,
        Instant retryNotBefore,String sourceKind,List<MaterialScopeV1> scopes) {
        public Binding {scopes=List.copyOf(scopes);}
    }
    public Binding load(long runId){return MaterialScopeStore.snapshot(source,()->load(db,runId,true));}
    /** Only control metadata and the three root manifests, in a new top-level read-only RR snapshot. */
    public RunObservationMetadata observationMetadata(SubjectKey owner,String publicId,RetainedAuthorization proof){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("OBSERVATION_REQUIRES_NO_CALLER_TRANSACTION");
        // Spring 6.2.7 DataSourceTransactionManager.doBegin/DataSourceUtils prepare this exact
        // connection; rejecting ambient transactions avoids REQUIRED silently joining a writer.
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status->{
            AuthorityContext.requireBound(proof);
            var query=proof.current();
            RunObservationSelectors.requirePurpose(query.call(),query.caller(),"delegated",query.operation().sourceKind(),query.operation().originKind());
            same(RunObservationSelectors.ACTION,query.call().action());
            same(owner,new SubjectKey(query.actor().tenantId(),query.actor().principalId()));
            same(RunObservationSelectors.metadata(owner,publicId),query.call().resource());same(query.call().resource(),query.operation().resource());
            var binding=loadObservationBinding(db,owner,publicId);
            var result=new RunObservationMetadata(binding.publicId(),binding.owner(),binding.root(),binding.appVersionId(),binding.appVersionNo(),binding.snapshotSha(),binding.initialSha());
            AuthorityContext.requireBound(proof);
            return result;
        });
    }
    /** Pure control/manifest load: caller owns purpose, transaction and any capability mint. */
    private static final String CHAT_RELATIONS = "(r.trigger_type='CHAT' and (r.snapshot->>'type' is distinct from 'CHAT'"
        +" or exists(select 1 from engine.chat_turn c where c.run_public_id=r.public_id)"
        +" or (select count(*) from engine.node_instance n where n.run_id=r.id)<>1"
        +" or exists(select 1 from engine.node_instance n where n.run_id=r.id and (n.node_type<>'AGENT' or n.node_def_id is not null or n.step_seq<>1 or n.definition<>'{\"type\":\"AGENT\"}'::jsonb))"
        +" or (select count(*) from engine.model_invocation i where i.run_id=r.id)>1"
        +" or exists(select 1 from engine.model_invocation i where i.run_id=r.id and (i.kind<>'MAIN' or i.round_no<>0 or i.output_attempt_no<>0 or not exists(select 1 from engine.node_instance n where n.id=i.node_instance_id and n.run_id=r.id)))"
        +" or (exists(select 1 from engine.model_invocation i where i.run_id=r.id) and not exists(select 1 from engine.model_input_snapshot m where m.run_id=r.id))))";
    private static void observationLifecycle(Map<String,Object> row){
        if(!Set.of("TASK","CHAT").contains(row.get("trigger_type")))throw denied();
        same("FULL",row.get("mode"));same(false,row.get("unsupported"));same(false,row.get("has_tools"));same(false,row.get("invalid_chat"));
    }
    static Binding loadObservationBinding(JdbcTemplate db,SubjectKey owner,String publicId){
        var rows=db.queryForList("select r.id,r.public_id,r.tenant_id,r.principal_id,r.trigger_type,r.mode,"
            +"(r.debug_snapshot is not null or r.eval_candidate is not null or r.controlled_draft_skill_version_id is not null or r.controlled_draft_skill_digest is not null or r.controlled_skill_test_version_id is not null or r.controlled_skill_test_digest is not null or r.controlled_skill_test_status is not null) as unsupported,"
            +"exists(select 1 from engine.tool_call t where t.run_id=r.id) as has_tools,"+CHAT_RELATIONS+" as invalid_chat "
            +"from engine.run r where r.public_id=? and r.tenant_id=? and r.principal_id=?",publicId,owner.tenantId(),owner.principalId());
        if(rows.isEmpty())throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");
        if(rows.size()!=1)throw denied();var row=rows.getFirst();
        same(publicId,str(row,"public_id"));same(owner,new SubjectKey(str(row,"tenant_id"),str(row,"principal_id")));
        observationLifecycle(row);
        // load(true) reads authority control (including the old grant and initial ceiling) and
        // validates all three manifests. The query never resumes that grant or checks its block.
        Binding binding;
        try{binding=load(db,((Number)row.get("id")).longValue(),true);}
        catch(IllegalArgumentException|ClassCastException|NullPointerException corruptMetadata){throw denied();}
        same(owner,binding.owner());same(publicId,binding.publicId());
        return binding;
    }

    /** Event-only bounded control path. Never calls load/scopes or resumes the historical grant. */
    static Binding loadObservationBindingBounded(RunEventReader.Work w,SubjectKey owner,String publicId){
        var ownerRows=w.query("select id,trigger_type,mode,(debug_snapshot is not null or eval_candidate is not null or controlled_draft_skill_version_id is not null or controlled_draft_skill_digest is not null or controlled_skill_test_version_id is not null or controlled_skill_test_digest is not null or controlled_skill_test_status is not null) as unsupported,exists(select 1 from engine.tool_call t where t.run_id=r.id) as has_tools,"+CHAT_RELATIONS+" as invalid_chat from engine.run r where public_id=? and tenant_id=? and principal_id=?",2,publicId,owner.tenantId(),owner.principalId());
        if(ownerRows.isEmpty())throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");
        if(ownerRows.size()!=1)throw denied();var row=ownerRows.getFirst();
        observationLifecycle(row);
        long run=RunEventReader.num(row,"id");
        // Fixed projection, not a.*. Both initial and every variable control field are measured
        // in PG before JDBC can obtain text; CASE is also retained on the subsequent text read.
        String control="select r.public_id,r.tenant_id as run_tenant,r.principal_id as run_principal,r.app_code,r.app_version_id as run_version,a.tenant_id,a.principal_id,a.grant_ref,to_char(a.grant_expires_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as grant_expires_at,a.resource_digest,a.source_kind,a.origin_authentication_id,a.app_version_id,a.app_version_no,a.root_kind,a.root_id,a.initial_ceiling_sha256,a.accepted_snapshot_sha256,a.admission_decision_id,a.authorization_version,a.operation_id,a.check_version,a.blocked_reason,to_char(a.retry_not_before at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as retry_not_before from engine.run r join engine.run_authority a on a.run_id=r.id where r.id=?";
        String from=" from (select row_to_json(c)::text as control_text from ("+control+") c) q";
        var sizes=w.query("select octet_length(control_text) as control_bytes"+from,2,run);
        if(sizes.size()!=1)throw denied();long bytes=RunEventReader.num(sizes.getFirst(),"control_bytes");
        RunEventReader.Limits.single(bytes,RunEventReader.CONTROL_BYTES,"CONTROL_BYTES");w.limits.metadata(bytes);
        var initialProbe=w.query("select octet_length(initial_ceiling::text) as initial_bytes,jsonb_typeof(initial_ceiling) as initial_type from engine.run_authority where run_id=?",2,run);
        if(initialProbe.size()!=1)throw denied();same("object",initialProbe.getFirst().get("initial_type"));
        long initialBytes=RunEventReader.num(initialProbe.getFirst(),"initial_bytes");RunEventReader.Limits.single(initialBytes,RunEventReader.JSON_BYTES,"INITIAL_BYTES");w.limits.metadata(initialBytes);
        var c=w.jsonText("select case when octet_length(control_text)<=262144 then control_text end as control_text"+from,bytes,false,run);
        Actor initial=parseInitial(w.jsonText("select case when octet_length(initial_ceiling::text)<=1048576 and jsonb_typeof(initial_ceiling)='object' then initial_ceiling::text end as initial_text from engine.run_authority where run_id=?",initialBytes,false,run));
        same(publicId,text(c,"public_id"));same(owner,new SubjectKey(text(c,"tenant_id"),text(c,"principal_id")));same(owner,new SubjectKey(text(c,"run_tenant"),text(c,"run_principal")));same(owner,new SubjectKey(initial.tenantId(),initial.principalId()));
        long version=positive(c.get("app_version_id")),no=positive(c.get("app_version_no"));same(version,positive(c.get("run_version")));same("app-version",text(c,"root_kind"));same(text(c,"app_code")+"@"+no,text(c,"root_id"));same("PORTAL",text(c,"source_kind"));
        String initialSha=text(c,"initial_ceiling_sha256"),snapshotSha=text(c,"accepted_snapshot_sha256"),candidate=text(c,"resource_digest");hash(initialSha);hash(snapshotSha);hash(candidate);same(initialSha,w.ceiling(initial,initialBytes));
        if(!c.path("check_version").isIntegralNumber()||!c.path("check_version").canConvertToLong()||c.path("check_version").longValue()<0)throw denied();
        try{
            var binding=new Binding(run,publicId,owner,new DelegationRef(text(c,"grant_ref"),owner,Instant.parse(text(c,"grant_expires_at"))),initial,new ResourceBinding("app-version",text(c,"root_id"),candidate),version,no,initialSha,snapshotSha,text(c,"origin_authentication_id"),text(c,"admission_decision_id"),text(c,"authorization_version"),text(c,"operation_id"),c.path("check_version").longValue(),c.path("blocked_reason").isNull()?null:text(c,"blocked_reason"),c.path("retry_not_before").isNull()?null:Instant.parse(text(c,"retry_not_before")),"PORTAL",List.of());
            w.live();return binding;
        }catch(DateTimeException invalid){throw denied();}
    }
    static Binding load(JdbcTemplate db,long runId,boolean complete){
        var rows=db.queryForList("""
                select r.id,r.public_id,r.tenant_id as run_tenant,r.principal_id as run_principal,
                    r.app_code,r.app_version_id as run_version,r.trigger_type,r.eval_candidate::text,
                    r.snapshot #>> '{chatSession,sessionId}' as session_id,a.*,
                    coalesce(g.grant_ref,a.grant_ref) as active_grant_ref,
                    coalesce(g.grant_expires_at,a.grant_expires_at) as active_grant_expires_at,
                    coalesce(g.origin_authentication_id,a.origin_authentication_id) as active_origin_id
                from engine.run r join engine.run_authority a on a.run_id=r.id
                left join engine.evaluation_grant_generation g on g.run_id=a.run_id and g.generation=a.active_generation
                where r.id=?
                """,runId);
        if(rows.size()!=1)throw denied();var r=rows.getFirst();
        Actor initial=parseInitial(Json.read(r.get("initial_ceiling").toString()));
        var owner=new SubjectKey(str(r,"tenant_id"),str(r,"principal_id"));same(owner,new SubjectKey(initial.tenantId(),initial.principalId()));
        same(owner,new SubjectKey(str(r,"run_tenant"),str(r,"run_principal")));
        long version=0,no=0;
        String kind = str(r, "root_kind");
        String sourceKind = str(r, "source_kind");
        if ("INTEGRATION".equals(sourceKind)) {
            same("app-version", kind);
            same("CHAT", str(r, "trigger_type"));
        } else same("PORTAL", sourceKind);
        String initialSha=str(r,"initial_ceiling_sha256"),snapshotSha=str(r,"accepted_snapshot_sha256"),candidate=str(r,"resource_digest");
        hash(initialSha);hash(snapshotSha);hash(candidate);same(initialSha,MaterialAuthorization.ceilingSha256(initial));
        var root = new ResourceBinding(kind, str(r, "root_id"), candidate);
        if (EvaluationCandidateBinding.KIND.equals(kind)) {
            same("EVALUATION", str(r, "trigger_type"));
            same(null, r.get("run_version"));
            same(root, EvaluationCandidateBinding.of(str(r, "app_code"), ((Number) r.get("candidate_rev")).longValue(), candidate));
            same(root, EvaluationCandidateBinding.from(Json.read(str(r, "eval_candidate"))));
        } else {
            version=((Number)r.get("app_version_id")).longValue();
            no=((Number)r.get("app_version_no")).longValue();
            if(version<1||no<1||!Objects.equals(r.get("run_version"),r.get("app_version_id")))throw denied();
            if (PortalRuntimeRoutes.SESSION_KIND.equals(kind)) {
                same("CHAT", str(r, "trigger_type"));
                same(root, PortalRuntimeRoutes.sessionResource(str(r, "app_code"), str(r, "session_id")));
            } else {
                same("app-version", kind);
                same(str(r, "app_code") + "@" + no, root.id());
            }
        }
        var b=new Binding(runId,str(r,"public_id"),owner,new DelegationRef(str(r,"active_grant_ref"),owner,instant(r,"active_grant_expires_at")),initial,
            root,version,no,initialSha,snapshotSha,str(r,"active_origin_id"),str(r,"admission_decision_id"),str(r,"authorization_version"),str(r,"operation_id"),((Number)r.get("check_version")).longValue(),(String)r.get("blocked_reason"),instant(r,"retry_not_before"),sourceKind,List.of());
        if(!complete)return b;
        var scopes=MaterialScopeStore.scopes(db,b);
        return new Binding(b.runId,b.publicId,b.owner,b.delegation,b.initial,b.root,b.appVersionId,b.appVersionNo,b.initialSha,b.snapshotSha,b.originId,b.admissionDecision,b.authorizationVersion,b.operationId,b.checkVersion,b.blockedReason,b.retryNotBefore,b.sourceKind,scopes);
    }

    /** Called only after replay matches the frozen request. Preserve the original material ceiling. */
    public boolean renewEvaluation(long runId, RetainedAuthorization proof) {
        AuthorityContext.requireBound(proof);
        var current = proof.current();
        requirePurpose(current, false);
        same(EvaluationCandidateBinding.KIND, current.call().resource().kind());
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        return Boolean.TRUE.equals(tx.execute(status -> {
            String state = db.queryForObject("select status from engine.run where id=? for update", String.class, runId);
            if (!Set.of("PENDING", "RUNNING").contains(state)) return false;
            long generation = db.queryForObject(
                    "select coalesce(active_generation,0) from engine.run_authority where run_id=? for update",
                    Long.class, runId);
            var binding = load(db, runId, true);
            var now = clock.instant();
            if (binding.delegation().expiresAt().isAfter(now)) return false;
            same(binding.root(), current.call().resource());
            same(binding.owner(), current.delegation().subject());
            same(binding.initial(), proof.requireInitialCeiling());
            if ("AUTHORITY_DENIED".equals(binding.blockedReason())
                    || binding.delegation().value().equals(current.delegation().value())
                    || !current.delegation().expiresAt().isAfter(now)) throw denied();
            new MaterialScopeStore(source, clock).currentScopes(binding)
                    .forEach(scope -> MaterialAuthorization.require(scope, current, clock));
            AuthorityContext.requireBound(proof);
            db.update("""
                    insert into engine.evaluation_grant_generation
                      (run_id,generation,grant_ref,grant_expires_at,origin_authentication_id,
                       decision_id,authorization_version,operation_id,recorded_at)
                    values (?,?,?,?,?,?,?,?,?)
                    """, runId, generation + 1, current.delegation().value(),
                    Timestamp.from(current.delegation().expiresAt()), proof.requireOriginAuthenticationId(),
                    current.decisionId(), current.authorizationVersion(), current.operation().operationId(), Timestamp.from(now));
            int updated = db.update("""
                    update engine.run_authority set active_generation=?,last_decision_id=null,last_checked_at=null,
                        blocked_reason=null,retry_not_before=null,check_version=check_version+1
                    where run_id=? and check_version=?
                    """, generation + 1, runId, binding.checkVersion());
            if (updated != 1) throw denied();
            db.update("update engine.run set dispatch_next_at=clock_timestamp() where id=?", runId);
            AuthorityContext.requireBound(proof);
            return true;
        }));
    }
    /** Result is usable only if this short CAS wins. Network calls must not hold a PG transaction. */
    public boolean recordCheck(Binding before,RetainedAuthorization proof,String blocked,Duration backoff){
        if(blocked==null&&proof==null || blocked!=null&&(!Set.of("AUTHORITY_DENIED","AUTHORITY_EXPIRED","AUTHORITY_UNAVAILABLE").contains(blocked)||proof!=null))throw new IllegalArgumentException("CHECK_RESULT_PROOF_REQUIRED");
        if(backoff==null||backoff.isNegative()||backoff.isZero()||backoff.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("BOUNDED_RETRY_REQUIRED");
        if(before.blockedReason()!=null&&(!"AUTHORITY_UNAVAILABLE".equals(before.blockedReason())||before.retryNotBefore().isAfter(clock.instant())))return false;
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("CURRENT_REQUIRES_NO_CALLER_TRANSACTION");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        return Boolean.TRUE.equals(tx.execute(status->{
            var now=load(db,before.runId,true);
            // Full immutable metadata is reread, so a future authorized generation switch cannot reuse this result.
            if(!now.equals(before))return false;
            if(proof!=null){proof.requireVerified();strict(before,proof);before.scopes.forEach(s->MaterialAuthorization.require(s,proof.current(),clock));}
            Instant checked=clock.instant();Instant retry="AUTHORITY_UNAVAILABLE".equals(blocked)?checked.plus(backoff):null;
            boolean updated=db.update("update engine.run_authority set last_decision_id=?,last_checked_at=?,blocked_reason=?,retry_not_before=?,check_version=check_version+1 where run_id=? and check_version=?",
                proof==null?null:proof.current().decisionId(),Timestamp.from(checked),blocked,retry==null?null:Timestamp.from(retry),before.runId,before.checkVersion)==1;
            // Row-lock waits can outlive proof validity without changing CAS version. Throw inside
            // this transaction so the expired success cannot clear a block or commit a new version.
            if(updated&&proof!=null)proof.requireVerified();
            return updated;
        }));
    }
    public static void strict(Binding b,RetainedAuthorization proof){
        proof.requireVerified();var a=proof.current();requirePurpose(a,true);same(b.root,a.call().resource());same(b.delegation,a.delegation());
        same(b.sourceKind, a.operation().originKind());
        same(b.initial,proof.requireInitialCeiling());same(b.initialSha,MaterialAuthorization.ceilingSha256(proof.requireInitialCeiling()));same(b.originId,proof.requireOriginAuthenticationId());
    }
    static void requirePurpose(VerifiedAuthority a,boolean localOnly){
        if(a==null||a.call()==null||a.operation()==null||a.call().resource()==null)throw denied();
        boolean local=ServiceRoutePolicy.localRunAdvance(a.caller(),a.call(),"delegated",a.operation().sourceKind(),a.operation().originKind());
        boolean main = "gateway".equals(a.caller()) && "engine".equals(a.call().target())
                && Set.of("proxy.engine.post.RunController.run", "proxy.engine.post.TaskController.create")
                        .contains(a.call().action())
                && "app-version".equals(a.call().resource().kind())
                && "PORTAL".equals(a.operation().sourceKind()) && "PORTAL".equals(a.operation().originKind());
        boolean evaluation = "monitor".equals(a.caller()) && "engine".equals(a.call().target())
                && Set.of("evaluation.run", "evaluation.turn").contains(a.call().action())
                && EvaluationCandidateBinding.KIND.equals(a.call().resource().kind())
                && "PORTAL".equals(a.operation().sourceKind()) && "PORTAL".equals(a.operation().originKind());
        boolean integrated = "gateway".equals(a.caller()) && "engine".equals(a.call().target())
                && IntegrationTaskRoutes.CREATE.equals(a.call().action()) && IntegrationTaskRoutes.versionRoot(a.call().resource())
                && "INTEGRATION".equals(a.operation().sourceKind()) && "INTEGRATION".equals(a.operation().originKind())
                && IntegrationTaskRoutes.submitRoles(a.actor().roles());
        boolean session = PortalRuntimeRoutes.isSessionTurn(a)
                && PortalRuntimeRoutes.SESSION_KIND.equals(a.call().resource().kind());
        if(localOnly ? !local : !(main || evaluation || integrated || session))throw denied();
        same(a.call().resource(),a.operation().resource());
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


