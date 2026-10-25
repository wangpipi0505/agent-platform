package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static cn.agentplatform.engine.infra.RunAuthorityStore.*;

/** Metadata-first, one PostgreSQL snapshot. This component deliberately has no Spring stereotype. */
public final class MaterialScopeStore {
    static final List<String> KINDS=List.of("run-snapshot","run-inputs","run-version-refs");
    // Jackson 2.19 JsonNodeDeserializer._fromFloat chooses getDecimalValue only with this flag.
    // Parse the persisted JSONB text, never round a decimal through binary double or global Json.
    private static final ObjectMapper EXACT=new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DataSource source;
    private final JdbcTemplate db;
    private final Clock clock;
    public MaterialScopeStore(DataSource source,Clock clock){this.source=Objects.requireNonNull(source);db=new JdbcTemplate(source);this.clock=Objects.requireNonNull(clock);}

    public AuthorizedMaterial read(MaterialRef ref,VerifiedAuthority current){
        if(ref==null||current==null||!"engine".equals(ref.producer())||!KINDS.contains(ref.kind()))throw denied();
        return snapshot(source,()->{
            var ids=db.queryForList("select run_id from engine.material_scope where tenant_id=? and producer=? and kind=? and material_id=? and material_version=?",Long.class,current.actor().tenantId(),ref.producer(),ref.kind(),ref.id(),ref.version());
            if(ids.size()!=1)throw denied();var binding=RunAuthorityStore.load(db,ids.getFirst(),true);
            boolean local=ServiceRoutePolicy.localRunAdvance(current.caller(),current.call(),"delegated",current.operation().sourceKind(),current.operation().originKind());
            requirePurpose(current,local);same(binding.root(),current.call().resource());same(binding.owner(),new SubjectKey(current.actor().tenantId(),current.actor().principalId()));
            var scope=binding.scopes().stream().filter(s->s.material().kind().equals(ref.kind())).findFirst().orElseThrow(RunAuthorityStore::denied);
            same(ref,scope.material());MaterialAuthorization.require(scope,current,clock);
            // No grant/initial equality here: a new same-owner grant may cover historical scope.
            // The trusted metadata checks above happen before the single fixed payload column SELECT.
            JsonNode payload=payload(db,ref.kind(),binding.runId());same(ref.payloadSha256(),digest(payload));
            MaterialAuthorization.require(scope,current,clock);return new AuthorizedMaterial(payload,scope);
        });
    }
    /** Package-only trusted primitive. Outer create owns both requireBound checks and transaction. */
    void write(MaterialScopeV1 scope){
        requireWrite(source);if(scope==null||scope.material()==null||scope.owner()==null)throw denied();
        var ids=db.queryForList("select id from engine.run where public_id=? and tenant_id=?",Long.class,scope.material().id(),scope.owner().tenantId());if(ids.size()!=1)throw denied();
        var b=RunAuthorityStore.load(db,ids.getFirst(),false);validate(scope,b);
        same(scope.material().payloadSha256(),payloadDigest(db,scope.material().kind(),b.runId()));
        if(scope.material().kind().equals("run-version-refs")){
            var parents=db.queryForList("select scope_sha256 from engine.material_scope where run_id=? and kind='run-snapshot'",String.class,b.runId());
            if(parents.size()!=1||!scope.parentScopeSha256().equals(parents))throw denied();
            var parent=Json.read(db.queryForObject("select scope::text from engine.material_scope where run_id=? and kind='run-snapshot'",String.class,b.runId()),MaterialScopeV1.class);
            if(!scope.requiredDataLabels().containsAll(parent.requiredDataLabels()))throw denied();
        }else if(!scope.parentScopeSha256().isEmpty())throw denied();
        var r=scope.material();db.update("insert into engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope) values(?,?,?,?,?,?,?,?,?::jsonb)",b.runId(),scope.owner().tenantId(),r.producer(),r.kind(),r.id(),r.version(),r.payloadSha256(),scope.scopeSha256(),Json.write(scope));
    }
    static List<MaterialScopeV1> scopes(JdbcTemplate db,RunAuthorityStore.Binding b){
        var rows=db.queryForList("select tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope::text as scope from engine.material_scope where run_id=?",b.runId());
        if(rows.size()!=3)throw denied();var scopes=new ArrayList<MaterialScopeV1>();var kinds=new HashSet<String>();
        for(var row:rows){
            MaterialScopeV1 s;try{s=Json.read(str(row,"scope"),MaterialScopeV1.class);}catch(RuntimeException e){throw denied();}
            validate(s,b);if(!kinds.add(s.material().kind()))throw denied();
            same(s.owner().tenantId(),str(row,"tenant_id"));same(s.material(),new MaterialRef(str(row,"producer"),str(row,"kind"),str(row,"material_id"),str(row,"material_version"),str(row,"payload_sha256")));same(s.scopeSha256(),str(row,"scope_sha256"));scopes.add(s);
        }
        var parent=scopes.stream().filter(s->s.material().kind().equals("run-snapshot")).findFirst().orElseThrow(RunAuthorityStore::denied);
        for(var s:scopes){same(s.parentScopeSha256(),s.material().kind().equals("run-version-refs")?List.of(parent.scopeSha256()):List.of());if(s.material().kind().equals("run-version-refs")&&!s.requiredDataLabels().containsAll(parent.requiredDataLabels()))throw denied();}
        scopes.sort(Comparator.comparing(s->s.material().kind()));return List.copyOf(scopes);
    }
    private static void validate(MaterialScopeV1 s,RunAuthorityStore.Binding b){
        if(s==null||s.schemaVersion()!=1||s.material()==null||s.owner()==null||s.requiredRoles()==null||s.requiredEntitlements()==null||s.requiredDataLabels()==null||s.parentScopeSha256()==null)throw denied();
        var r=s.material();if(!"engine".equals(r.producer())||!KINDS.contains(r.kind()))throw denied();
        same(b.owner(),s.owner());same(b.publicId(),r.id());same(b.snapshotSha(),r.version());hash(r.payloadSha256());hash(s.scopeSha256());
        same(b.admissionDecision(),s.sourceDecisionId());same(b.authorizationVersion(),s.sourceAuthorizationVersion());same(b.initialSha(),s.initialCeilingSha256());
        same(b.initial().roles(),s.requiredRoles());var entitlements=new TreeMap<>(b.initial().attributes());entitlements.remove("dataLabels");same(entitlements,s.requiredEntitlements());
        if(!s.requiredDataLabels().containsAll(b.initial().attributes().getOrDefault("dataLabels",Set.of())))throw denied();s.requiredDataLabels().forEach(RunAuthorityStore::nonblank);
        same(MaterialAuthorization.scopeDigest(s),s.scopeSha256());
        if("run-snapshot".equals(r.kind()))same(b.snapshotSha(),r.payloadSha256());
    }
    static MaterialScopeV1 withDigest(MaterialScopeV1 s){return new MaterialScopeV1(s.schemaVersion(),s.material(),s.owner(),s.sourceDecisionId(),s.sourceAuthorizationVersion(),s.initialCeilingSha256(),s.requiredRoles(),s.requiredEntitlements(),s.requiredDataLabels(),s.parentScopeSha256(),MaterialAuthorization.scopeDigest(s));}
    static String payloadDigest(JdbcTemplate db,String kind,long id){return digest(payload(db,kind,id));}
    private static JsonNode payload(JdbcTemplate db,String kind,long id){
        String column=switch(kind){case "run-snapshot"->"snapshot";case "run-inputs"->"inputs";case "run-version-refs"->"version_refs";default->throw denied();};
        String raw=db.queryForObject("select "+column+"::text from engine.run where id=?",String.class,id);
        try{if(raw==null)throw denied();var node=EXACT.readTree(raw);object(node);return node;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw denied();}
    }
    private static String digest(JsonNode node){return Digest.sha256(normalize(node).toString());}
    private static JsonNode normalize(JsonNode node){
        if(node.isObject()){var result=JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);keys.forEach(k->result.set(k,normalize(node.get(k))));return result;}
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();node.forEach(n->result.add(normalize(n)));return result;}
        if(node.isNumber()){var value=node.decimalValue().stripTrailingZeros();if(value.signum()==0)return BigIntegerNode.valueOf(java.math.BigInteger.ZERO);return value.scale()<=0?BigIntegerNode.valueOf(value.toBigIntegerExact()):DecimalNode.valueOf(value);}
        return node;
    }
    static <T>T snapshot(DataSource source,Supplier<T> action){
        // Spring 6.2.7 normally silently joins REQUIRED. Inspect the bound connection explicitly:
        // accept RR or SERIALIZABLE, reject READ_COMMITTED, and never suspend an outer transaction.
        if(TransactionSynchronizationManager.isActualTransactionActive()){
            if(!TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("SAME_DATASOURCE_TRANSACTION_REQUIRED");
            var c=DataSourceUtils.getConnection(source);try{if(c.getAutoCommit()||c.getTransactionIsolation()<Connection.TRANSACTION_REPEATABLE_READ)throw new IllegalStateException("REPEATABLE_READ_REQUIRED");}
            catch(SQLException e){throw new IllegalStateException(e);}finally{DataSourceUtils.releaseConnection(c,source);}return action.get();
        }
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(status->action.get());
    }
}
