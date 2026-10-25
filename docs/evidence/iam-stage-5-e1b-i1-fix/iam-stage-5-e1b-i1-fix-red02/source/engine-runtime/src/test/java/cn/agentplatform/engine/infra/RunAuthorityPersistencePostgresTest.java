package cn.agentplatform.engine.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RunAuthorityPersistencePostgresTest {
 @Test void actualPublishedInsertAndThreeScopesShareTheCallerTransaction()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();
   assertThatCode(()->f.admitted(p->f.tx.execute(s->{long id=f.create(p);assertThat(f.db.queryForObject("select count(*) from engine.run_authority where run_id=?",Integer.class,id)).isEqualTo(1);assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Integer.class,id)).isEqualTo(3);return id;}))).doesNotThrowAnyException();
   assertThat(f.count()).isEqualTo(before+1);
  }
 }
 @Test void publishedAuditRetainsOriginalOwnerFieldsWithoutBeingAuthorizationSource()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var guard=Json.read(f.db.queryForObject("select guard_ctx::text from engine.run where id=?",String.class,id));
   assertThat(guard.path("tenantId").textValue()).isEqualTo("tenant-a");assertThat(guard.path("principalId").textValue()).isEqualTo("alice");
   f.db.update("update engine.run set guard_ctx='{}' where id=?",id);assertThat(f.store.load(id).owner()).isEqualTo(new SubjectKey("tenant-a","alice"));
  }
 }
 @Test void nullReplacedProofAndDifferentDatasourceAreRejectedBeforePayload()throws Exception{
  try(var f=new RunAuthorityFixture()){
   f.ds.payloadReads.set(0);
   f.admitted(p->{assertThatThrownBy(()->f.tx.execute(t->f.create(null))).isInstanceOf(PlatformException.class);return null;});
   f.admitted(p->{AuthorityContext.requireInitialFor(p.current().actor(),RunAuthorityFixture.ROOT);assertThatThrownBy(()->f.tx.execute(t->f.create(p))).isInstanceOf(PlatformException.class);return null;});
   var other=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_engine_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));
   var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(other));
   f.admitted(p->{assertThatThrownBy(()->tx.execute(t->f.create(p))).isInstanceOf(IllegalStateException.class);return null;});
   assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void narrowedAdmissionRollsBackRunAuthorityAndEveryScopeEvenIfCallerCatches()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();f.iam.decisionMutation(InitialMaterialScopePostgresTest::currentA);
   f.admitted(p->{try{f.tx.execute(s->{assertThatThrownBy(()->f.create(p)).isInstanceOf(PlatformException.class);return null;});}catch(org.springframework.transaction.UnexpectedRollbackException expected){}return null;});
   assertThat(f.count()).isEqualTo(before);
  }
 }
 @Test void actualSqlFailuresAtEachWriteAndFollowingNodeRollBackEverything()throws Exception{
  try(var f=new RunAuthorityFixture()){
   for(String kind:List.of("authority","run-snapshot","run-inputs","run-version-refs","node")){
    long before=f.count();String table=kind.equals("authority")?"run_authority":"material_scope";
    if(!kind.equals("node")){f.db.execute("create or replace function engine.e1b_fault() returns trigger language plpgsql as $$ begin "+(kind.equals("authority")?"":"if NEW.kind='"+kind+"' then ")+"raise exception 'E1B_SQL_FAULT'; "+(kind.equals("authority")?"":"end if; ")+"return NEW; end $$");f.db.execute("create trigger e1b_fault before insert on engine."+table+" for each row execute function engine.e1b_fault()");}
    try{assertThatThrownBy(()->f.admitted(p->f.tx.execute(s->{long id=f.create(p);if(kind.equals("node"))f.db.update("insert into engine.node_instance(run_id,step_seq,node_type,status,definition) values(?,1,'TASK','INVALID','{}')",id);return id;}))).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    finally{if(!kind.equals("node")){f.db.execute("drop trigger e1b_fault on engine."+table);f.db.execute("drop function engine.e1b_fault()");}}
    assertThat(f.count()).as(kind).isEqualTo(before);
   }
  }
 }
 @Test void proofLifecycleAndSameDatasourceTransactionRejectBeforeAnyPayload()throws Exception{
  try(var f=new RunAuthorityFixture();var executor=Executors.newSingleThreadExecutor()){
   var captured=new java.util.concurrent.atomic.AtomicReference<RetainedAuthorization>();
   f.admitted(p->{captured.set(p);assertThatThrownBy(()->f.create(p)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->f.tx.execute(s->f.create(RetainedAuthorization.currentOnly(p.current())))).isInstanceOf(PlatformException.class);return null;});
   f.ds.payloadReads.set(0);
   assertThatThrownBy(()->f.tx.execute(s->f.create(captured.get()))).isInstanceOf(PlatformException.class);
   f.admitted(p->{assertThatThrownBy(()->f.tx.execute(s->f.create(captured.get()))).isInstanceOf(PlatformException.class);return null;});
   f.admitted(p->{try{executor.submit(()->assertThatThrownBy(()->f.tx.execute(s->f.create(p))).isInstanceOf(PlatformException.class)).get(3,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}return null;});
   f.admitted(p->{f.iam.advanceClock(Duration.ofSeconds(5));assertThatThrownBy(()->f.tx.execute(s->f.create(p))).isInstanceOf(PlatformException.class);return null;});
   assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void deadlineConsumedAndNullInputsFailBeforeInsert()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();
   for(String invalid:List.of("expired","long","consumed","limits","null-input"))f.admitted(p->{
    var budget=(ObjectNode)f.budget();if(invalid.equals("expired"))budget.put("deadline",f.iam.clock().instant().toString());
    if(invalid.equals("long"))budget.put("deadline",f.iam.clock().instant().plusSeconds(601).toString());
    if(invalid.equals("consumed"))((ObjectNode)budget.path("consumed")).put("steps",1);
    if(invalid.equals("limits"))((ObjectNode)budget.path("limits")).put("maxSteps",26);
    assertThatThrownBy(()->f.tx.execute(t->f.store.createPublished(p,f.snapshot(),invalid.equals("null-input")?Json.read("null"):Json.object(),Json.read("{\"appVer\":1}"),budget,"FULL",null,Set.of()))).isInstanceOf(PlatformException.class);return null;
   });assertThat(f.count()).isEqualTo(before);
  }
 }
 @Test void finalBoundCheckExpiresAfterActualWritesAndRollsBack()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();var reads=new java.util.concurrent.atomic.AtomicInteger();f.ds.beforePayload=()->{if(reads.incrementAndGet()==7)f.iam.advanceClock(Duration.ofSeconds(5));};
   assertThatThrownBy(()->f.admitted(p->f.tx.execute(t->f.create(p)))).isInstanceOf(PlatformException.class);assertThat(f.count()).isEqualTo(before);
  }
 }
 @Test void historicalRunWithoutAuthorityIsNeverBackfilledOrRead()throws Exception{
  try(var f=new RunAuthorityFixture()){
   String publicId="r_legacy_"+UUID.randomUUID();long id=f.db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs) values(?,'app-a',12,'TASK','PENDING','alice','tenant-a','legacy','{}','{}','{}','{}','{}') returning id",Long.class,publicId);
   f.ds.payloadReads.set(0);assertThatThrownBy(()->f.store.load(id)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->new cn.agentplatform.engine.application.RunAuthority(f.store,f.engine,f.iam.clock(),Duration.ofSeconds(3)).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);
   f.admitted(p->{assertThatThrownBy(()->new MaterialScopeStore(f.ds,f.iam.clock()).read(new MaterialRef("engine","run-snapshot",publicId,Digest.sha256("{}"),Digest.sha256("{}")),p.current())).isInstanceOf(PlatformException.class);return null;});
   assertThat(f.ds.payloadReads.get()).isZero();assertThat(f.db.queryForObject("select count(*) from engine.run_authority where run_id=?",Integer.class,id)).isZero();
  }
 }
 @Test void invalidPublishedAppCodeCannotBeAdmittedEvenWithMatchingSignedRoot()throws Exception{
  try(var f=new RunAuthorityFixture()){
   var snapshot=(ObjectNode)f.snapshot();snapshot.put("appCode","bad.code");
   var call=new ServiceCall("engine",RunAuthorityFixture.MAIN.action(),new ResourceBinding("app-version","bad.code@1",RunAuthorityFixture.ROOT.versionDigest()));
   assertThatThrownBy(()->f.admitted(call,p->f.tx.execute(t->f.store.createPublished(p,snapshot,Json.object(),Json.read("{\"appVer\":1}"),f.budget(),"FULL",null,Set.of())))).isInstanceOf(PlatformException.class);
  }
 }
 @Test void actualVersionTypesAndBudgetModesCannotBeCoerced()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();
   for(String value:List.of("\"1\"","1.0","0","-1","9223372036854775808","null")){
    var snapshot=(ObjectNode)f.snapshot();snapshot.set("versionNo",Json.read(value));
    f.admitted(p->{assertThatThrownBy(()->f.tx.execute(s->f.store.createPublished(p,snapshot,Json.object(),Json.read("{\"appVer\":1}"),f.budget(),"FULL",null,Set.of()))).isInstanceOf(PlatformException.class);return null;});
   }
   for(String key:List.of("appVersionId","versionId","candidateSha256","appCode","appVersionKey")){
    var snapshot=(ObjectNode)f.snapshot();snapshot.put(key,"wrong");
    f.admitted(p->{assertThatThrownBy(()->f.tx.execute(s->f.store.createPublished(p,snapshot,Json.object(),Json.read("{\"appVer\":1}"),f.budget(),"FULL",null,Set.of()))).isInstanceOf(PlatformException.class);return null;});
   }
   f.admitted(p->{for(String mode:List.of("DEBUG","STEP","RUN_TO_END","ASYNC"))assertThatThrownBy(()->f.tx.execute(s->f.store.createPublished(p,f.snapshot(),Json.object(),Json.read("{\"appVer\":1}"),f.budget(),mode,null,Set.of()))).isInstanceOf(PlatformException.class);return null;});
   assertThat(f.count()).isEqualTo(before);
  }
 }
 @Test void duplicateRequestDoesNotAttachOrUpdateAuthority()throws Exception{
  try(var f=new RunAuthorityFixture()){
   String request=UUID.randomUUID().toString();long id=f.admitted(p->f.tx.execute(s->f.store.createPublished(p,f.snapshot(),Json.object(),Json.read("{\"appVer\":1}"),f.budget(),"FULL",request,Set.of())));var before=f.store.load(id);
   assertThatThrownBy(()->f.admitted(p->f.tx.execute(s->f.store.createPublished(p,f.snapshot(),Json.object(),Json.read("{\"appVer\":1}"),f.budget(),"FULL",request,Set.of())))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
   assertThat(f.store.load(id)).isEqualTo(before);
  }
 }
 @Test void pgImmutabilityAndTrustedWriterEnforceBindingScopeAndAudit()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);var material=new MaterialScopeStore(f.ds,f.iam.clock());
   for(String sql:List.of("update engine.run set tenant_id='other' where id=?","update engine.run set inputs='{}' where id=?","update engine.run_authority set initial_ceiling='{}' where run_id=?","delete from engine.run_authority where run_id=?","update engine.material_scope set scope='{}' where run_id=?","delete from engine.material_scope where run_id=?"))assertThatThrownBy(()->f.db.update(sql,id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
   var scope=b.scopes().getFirst();assertThatThrownBy(()->material.write(scope)).isInstanceOf(IllegalStateException.class);
   assertThatThrownBy(()->f.tx.execute(s->{material.write(scope);return null;})).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
   var wrong=new MaterialScopeV1(1,scope.material(),scope.owner(),"wrong",scope.sourceAuthorizationVersion(),scope.initialCeilingSha256(),scope.requiredRoles(),scope.requiredEntitlements(),scope.requiredDataLabels(),scope.parentScopeSha256(),scope.scopeSha256());
   assertThatThrownBy(()->f.tx.execute(s->{material.write(wrong);return null;})).isInstanceOf(PlatformException.class);
   f.db.update("update engine.run set status='RUNNING' where id=?",id);assertThat(f.store.load(id)).isEqualTo(b);
  }
 }
}
