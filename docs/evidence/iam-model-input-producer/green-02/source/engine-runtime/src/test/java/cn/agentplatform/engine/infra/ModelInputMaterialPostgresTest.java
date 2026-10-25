package cn.agentplatform.engine.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ModelInputMaterialPostgresTest {
 @Test void preflight217AndSourceContinuity()throws Exception{try(var f=new ModelInputMaterialFixture(true)){assertThat(f.db.queryForList("select version from engine.flyway_schema_history where success and version is not null order by installed_rank",String.class)).containsExactly("200","201","202","203","204","205","206","207","208","209","210","211","212","213","214","215","216","217");}}
 @Test void persistsRequestAndScopeAtomicallyWithFullParentUnion()throws Exception{try(var f=new ModelInputMaterialFixture()){long run=f.create();var m=f.materials.prepareInitial(run);assertThat(m).isNotNull();assertThat(f.count("model_input_snapshot")).isEqualTo(1);assertThat(m.parents()).hasSize(3);assertThat(m.scope().requiredDataLabels()).contains("initial","config","parent");}}
 @Test void missingRootScopeMustDeny()throws Exception{try(var f=new ModelInputMaterialFixture()){long run=f.create();f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");f.db.update("delete from engine.material_scope where run_id=? and kind='run-inputs'",run);assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class);}}
 @Test void replayRecomputesFullRequestAndNeverCreatesDispatchFacts()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create();var first=f.materials.prepareInitial(run);var again=f.materials.prepareInitial(run);assertThat(again).isEqualTo(first);
  var request=Json.read(f.db.queryForObject("select request::text from engine.model_input_snapshot where run_id=?",String.class,run));assertThat(Digest.sha256(Json.canonical(request))).isEqualTo(first.requestSha256());assertThat(request.path("invocationRef").asText()).isEqualTo("material:"+first.material().id()+":MAIN");assertThat(request.path("callKind").asText()).isEqualTo("FORMAL");assertThat(request.path("toolDefinitions")).isEmpty();assertThat(request.path("messages")).hasSize(2);
  assertThat(first.scope().parentScopeSha256()).containsExactlyElementsOf(first.parents().stream().map(MaterialScopeV1::scopeSha256).sorted().toList());first.parents().forEach(s->assertThat(MaterialAuthorization.scopeDigest(s)).isEqualTo(s.scopeSha256()));
  assertThat(f.count("model_invocation")).isZero();assertThat(f.count("chat_turn")).isZero();assertThat(f.db.queryForObject("select budget->'consumed'->>'modelCalls' from engine.run where id=?",String.class,run)).isEqualTo("0");
  for(String table:List.of("engine.model_input_snapshot","engine.material_scope")){assertThatThrownBy(()->f.db.update("delete from "+table+" where run_id=?",run)).hasMessageContaining("IMMUTABLE");assertThatThrownBy(()->f.db.update("update "+table+" set tenant_id=tenant_id where run_id=?",run)).hasMessageContaining("IMMUTABLE");}
 }}
 @Test void narrowedCurrentDeniesBeforeAnySourceAndCannotRemoveOldObligations()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create();f.iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));});f.sql.clear();assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class);assertThat(f.count("model_input_snapshot")).isZero();assertThat(f.sql).noneMatch(s->s.contains("select case when octet_length(snapshot"));
 }}
 @Test void rejectsUnsupportedAndMismatchedFrozenConfiguration()throws Exception{try(var f=new ModelInputMaterialFixture()){
  var variants=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
  var pure=(ObjectNode)f.snapshot();pure.put("type","FLOW");variants.add(pure);
  for(String key:List.of("tools","history","frozenPrompts","modelOverride")){var s=(ObjectNode)f.snapshot();((ObjectNode)s.path("config")).set(key,Json.tree(List.of()));variants.add(s);}
  var mismatch=(ObjectNode)f.snapshot();((ObjectNode)mismatch.path("config")).set("modelBinding",Json.tree(Map.of("modelEntryId",8)));variants.add(mismatch);
  var dup=(ObjectNode)f.snapshot();((ArrayNode)dup.path("dependencyVersions").path("modelRefs")).add(dup.path("dependencyVersions").path("modelRefs").get(0).deepCopy());variants.add(dup);
  var drift=(ObjectNode)f.snapshot();((ObjectNode)drift.path("dependencyVersions").path("modelRefs").get(0).path("entry")).put("modelName","changed");variants.add(drift);
  for(var snapshot:variants){long run=f.create(snapshot,Json.read("{\"name\":\"Alice\"}"));assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class).satisfies(e->assertThat(((PlatformException)e).code()).isEqualTo("MATERIAL_SCOPE_MISSING_OR_CHANGED"));}
  assertThat(f.count("model_input_snapshot")).isZero();
 }}
 @Test void caughtScopeInsertFailureStillRollsBackRequest()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create();var proof=new RunAuthority(f.runs,f.engine,f.iam.clock(),Duration.ofSeconds(1)).currentRetained(run);var store=new ModelInputMaterialStore(f.ds,f.iam.clock());
  f.ds.beforeSql=s->{f.sql.add(s);if(s.startsWith("insert into engine.material_scope"))throw new PlatformException(403,"INJECTED_SCOPE_FAILURE","scope insert fault");};
  f.tx.execute(status->{var source=store.lockInitial(run,proof);try{store.insertInitial(source,ModelInputMaterials.assemble(source,proof),proof);}catch(PlatformException e){assertThat(e.code()).isEqualTo("INJECTED_SCOPE_FAILURE");}return null;});
  f.ds.beforeSql=f.sql::add;assertThat(f.count("model_input_snapshot")).isZero();assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=? and kind='model-input'",Long.class,run)).isZero();
 }}
 @Test void publicCurrentOnlyAndFabricatedSourceCannotWriteAndCatchCannotCommit()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create();var proof=new RunAuthority(f.runs,f.engine,f.iam.clock(),Duration.ofSeconds(1)).currentRetained(run);var store=new ModelInputMaterialStore(f.ds,f.iam.clock());
  f.tx.execute(status->{assertThatThrownBy(()->store.lockInitial(run,HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(proof.current()))).isInstanceOf(PlatformException.class);return null;});
  f.tx.execute(status->{var s=store.lockInitial(run,proof);var req=(ObjectNode)ModelInputMaterials.assemble(s,proof);req.put("modelEntryId",8);assertThatThrownBy(()->store.insertInitial(s,req,proof)).isInstanceOf(PlatformException.class);return null;});assertThat(f.count("model_input_snapshot")).isZero();
 }}
 @Test void realLockWaitExpiryRejectsAndPrecommitExpiryRollsBack()throws Exception{try(var f=new ModelInputMaterialFixture();var workers=Executors.newSingleThreadExecutor()){
  long run=f.create();var proof=new RunAuthority(f.runs,f.engine,f.iam.clock(),Duration.ofSeconds(1)).currentRetained(run);var store=new ModelInputMaterialStore(f.ds,f.iam.clock());var arrived=new CountDownLatch(1);
  try(var connection=f.ds.getConnection()){connection.setAutoCommit(false);try(var q=connection.prepareStatement("select id from engine.run where id=? for update")){q.setLong(1,run);q.executeQuery();}arrived=new CountDownLatch(1);var waitForLock=arrived;f.ds.beforeRunLock=pid->waitForLock.countDown();
   var future=workers.submit(()->assertThatThrownBy(()->f.tx.execute(status->store.lockInitial(run,proof))).isInstanceOf(PlatformException.class));assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();f.iam.advanceClock(Duration.ofSeconds(10));connection.commit();future.get(5,TimeUnit.SECONDS);
  }assertThat(f.count("model_input_snapshot")).isZero();
 }}
 @Test void finalLocalCheckRollsBackAndPostcommitCheckReportsNonAtomicBoundary()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create();f.ds.beforeSql=s->{f.sql.add(s);if(s.startsWith("insert into engine.material_scope"))f.iam.advanceClock(Duration.ofSeconds(10));};assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class);f.ds.beforeSql=f.sql::add;assertThat(f.count("model_input_snapshot")).isZero();
  long second=f.create();f.ds.afterMaterialCommit=()->f.iam.advanceClock(Duration.ofSeconds(10));assertThatThrownBy(()->f.materials.prepareInitial(second)).isInstanceOf(PlatformException.class);assertThat(f.count("model_input_snapshot")).isEqualTo(1);
 }}
 @Test void boundsRejectBeforeSourceMaterializationAndDepthRejects()throws Exception{try(var f=new ModelInputMaterialFixture()){
  long run=f.create(f.snapshot(),Json.tree(Map.of("name","A","message","x".repeat(1048577))));f.sql.clear();assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class);assertThat(f.sql).noneMatch(s->s.startsWith("select case when octet_length(snapshot"));
  var deep=Json.object();var cur=deep;for(int i=0;i<34;i++){var next=Json.object();cur.set("x",next);cur=next;}long another=f.create(f.snapshot(),deep);assertThatThrownBy(()->f.materials.prepareInitial(another)).isInstanceOf(PlatformException.class);assertThat(f.count("model_input_snapshot")).isZero();
 }}
 @Test void metadataSerializerStabilizesAllSetOrders()throws Exception{try(var f=new ModelInputMaterialFixture()){
  var m=f.materials.prepareInitial(f.create());var original=Json.canonical(m);var reread=Json.read(Json.write(m),cn.agentplatform.engine.domain.ModelInputManifest.class);assertThat(Json.canonical(reread)).isEqualTo(original);var unsigned=(ObjectNode)Json.tree(reread);unsigned.remove("manifestSha256");assertThat(Digest.sha256(Json.canonical(unsigned))).isEqualTo(m.manifestSha256());
 }}
}
