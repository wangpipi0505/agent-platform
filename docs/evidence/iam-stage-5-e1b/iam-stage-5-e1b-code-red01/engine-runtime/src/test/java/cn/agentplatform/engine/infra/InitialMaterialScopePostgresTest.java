package cn.agentplatform.engine.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class InitialMaterialScopePostgresTest {
 @Test void readsThreeActualColumnsAndPreservesParentAndLabelUnion()throws Exception{
  try(var f=new RunAuthorityFixture()){
   f.iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial","current")));});
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);
   assertThat(b.scopes()).hasSize(3);assertThat(b.scopes()).allSatisfy(s->assertThat(s.requiredDataLabels()).containsExactlyInAnyOrder("initial","current","config"));
   f.ds.payloadReads.set(0);f.admitted(p->{for(var s:b.scopes()){var value=new MaterialScopeStore(f.ds,f.iam.clock()).read(s.material(),p.current());assertThat(value.payload()).isInstanceOf(JsonNode.class);assertThat(value.scope()).isEqualTo(s);}return null;});
   assertThat(f.ds.payloadReads.get()).isEqualTo(3);
  }
 }
 @Test void freshSameOwnerGrantWithNewInitialReadsExpiredHistoricalGrant()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var old=f.store.load(id);f.iam.advanceClock(Duration.ofSeconds(301));
   f.iam.portalActor(RunAuthorityFixture.actor(Set.of("A","B","C")));
   assertThatCode(()->f.admitted(p->{assertThat(p.current().delegation()).isNotEqualTo(old.delegation());assertThat(p.requireInitialCeiling()).isNotEqualTo(old.initial());return new MaterialScopeStore(f.ds,f.iam.clock()).read(old.scopes().getFirst().material(),p.current());})).doesNotThrowAnyException();
  }
 }
 @Test void narrowerCurrentCannotReadAnyPayloadAndDoesNotRewriteHistory()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);
   f.iam.decisionMutation(InitialMaterialScopePostgresTest::currentA);f.ds.payloadReads.set(0);
   f.admitted(p->{assertThatThrownBy(()->new MaterialScopeStore(f.ds,f.iam.clock()).read(b.scopes().getFirst().material(),p.current())).isInstanceOf(PlatformException.class);return null;});
   assertThat(f.ds.payloadReads.get()).isZero();assertThat(f.store.load(id).initial()).isEqualTo(b.initial());
  }
 }
 @Test void wrongOwnerRootPurposeVersionOrDigestRejectBeforePayload()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);var ref=b.scopes().getFirst().material();var store=new MaterialScopeStore(f.ds,f.iam.clock());
   f.ds.payloadReads.set(0);
   f.admitted(p->{for(var bad:List.of(new MaterialRef("app",ref.kind(),ref.id(),ref.version(),ref.payloadSha256()),new MaterialRef("engine","unknown",ref.id(),ref.version(),ref.payloadSha256()),new MaterialRef("engine",ref.kind(),ref.id(),Digest.sha256("wrong"),ref.payloadSha256()),new MaterialRef("engine",ref.kind(),ref.id(),ref.version(),Digest.sha256("wrong"))))assertThatThrownBy(()->store.read(bad,p.current())).isInstanceOf(PlatformException.class);return null;});
   var otherRoot=new ServiceCall("engine",RunAuthorityFixture.MAIN.action(),new ResourceBinding("app-version","other@1",Digest.sha256("other")));
   f.admitted(otherRoot,p->{assertThatThrownBy(()->store.read(ref,p.current())).isInstanceOf(PlatformException.class);return null;});
   f.iam.portalActor(new Actor("tenant-a","bob",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));
   f.admitted(p->{assertThatThrownBy(()->store.read(ref,p.current())).isInstanceOf(PlatformException.class);return null;});
   assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void repeatableReadMustNotSilentlyJoinReadCommitted()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var ref=f.store.load(id).scopes().getFirst().material();f.ds.payloadReads.set(0);
   f.admitted(p->{assertThatThrownBy(()->f.tx.execute(s->new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,p.current()))).isInstanceOf(IllegalStateException.class).hasMessage("REPEATABLE_READ_REQUIRED");return null;});
   assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void preciseJsonbNumbersNullsAndArrayOrderDetermineActualHashes()throws Exception{
  try(var f=new RunAuthorityFixture()){
   var exact=new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   var digests=new ArrayList<String>();
   for(String json:List.of("{\"n\":1}","{\"n\":1.0}","{\"n\":1e0}","{\"n\":1234567890123456789012345678901234567890}","{\"n\":1234567890123456789012345678901234567891}","{\"n\":0.123456789012345678901}","{\"n\":0.123456789012345678902}","{\"n\":null}","{\"n\":{}}","{\"n\":[1,2]}","{\"n\":[2,1]}")){
    JsonNode inputs=exact.readTree(json);long id=f.admitted(p->f.tx.execute(s->f.store.createPublished(p,f.snapshot(),inputs,Json.read("{\"appVer\":1}"),f.budget(),"FULL",UUID.randomUUID().toString(),Set.of())));
    var scope=f.store.load(id).scopes().stream().filter(s->s.material().kind().equals("run-inputs")).findFirst().orElseThrow();digests.add(scope.material().payloadSha256());
    f.admitted(p->{var material=new MaterialScopeStore(f.ds,f.iam.clock()).read(scope.material(),p.current());assertThat(((JsonNode)material.payload()).equals((a,b)->a.isNumber()&&b.isNumber()?a.decimalValue().compareTo(b.decimalValue()):a.equals(b)?0:1,inputs)).isTrue();return null;});
   }
   assertThat(digests.subList(0,3)).containsOnly(digests.getFirst());assertThat(new HashSet<>(digests.subList(2,digests.size()))).hasSize(9);
  }
 }
 @Test void immutableDatabaseRejectsConcurrentPayloadChangeBetweenMetadataAndSelect()throws Exception{
  try(var f=new RunAuthorityFixture();var executor=Executors.newSingleThreadExecutor()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var ref=f.store.load(id).scopes().stream().filter(s->s.material().kind().equals("run-inputs")).findFirst().orElseThrow().material();
   var arrived=new CountDownLatch(1);var attempted=new CountDownLatch(1);
   f.ds.beforePayload=()->{arrived.countDown();try{if(!attempted.await(5,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");}catch(InterruptedException e){throw new AssertionError(e);}};
   var worker=executor.submit(()->{try{assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->f.db.update("update engine.run set inputs='{}'::jsonb where id=?",id)).hasMessageContaining("AUTHORIZED_RUN_IMMUTABLE");}finally{attempted.countDown();}return null;});
   f.admitted(p->{assertThat(((JsonNode)new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,p.current()).payload()).path("a").bigIntegerValue()).isEqualTo(java.math.BigInteger.ONE);return null;});worker.get(6,TimeUnit.SECONDS);
  }
 }
 @Test void corruptedMetadataAndMissingScopesDenyWithoutSelectingPayload()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var b=f.store.load(id);var ref=b.scopes().getFirst().material();
   for(String mutation:List.of(
    "delete from engine.material_scope where run_id=? and kind='run-version-refs'",
    "update engine.material_scope set tenant_id='other' where run_id=?",
    "update engine.material_scope set scope=jsonb_set(scope,'{sourceDecisionId}','\"wrong\"') where run_id=?",
    "update engine.material_scope set scope=jsonb_set(scope,'{initialCeilingSha256}','\""+Digest.sha256("wrong")+"\"') where run_id=?",
    "update engine.material_scope set scope=jsonb_set(scope,'{parentScopeSha256}','[]') where run_id=? and kind='run-version-refs'",
    "update engine.material_scope set material_version='"+Digest.sha256("wrong")+"' where run_id=? and kind='run-inputs'")){
    f.ds.payloadReads.set(0);f.admitted(p->{f.tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);try{return f.tx.execute(t->{
     f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");f.db.update(mutation,id);
     assertThatThrownBy(()->new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,p.current())).isInstanceOf(PlatformException.class);t.setRollbackOnly();return null;
    });}finally{f.tx.setIsolationLevel(-1);}});assertThat(f.ds.payloadReads.get()).as(mutation).isZero();
   }
   assertThat(f.store.load(id)).isEqualTo(b);
  }
 }
 @Test void payloadDigestMismatchAndRootNullAreRejectedAfterExactlyOneRead()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var ref=f.store.load(id).scopes().stream().filter(s->s.material().kind().equals("run-inputs")).findFirst().orElseThrow().material();
   for(String payload:List.of("{}","null")){
    f.ds.payloadReads.set(0);f.admitted(p->{f.tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);try{return f.tx.execute(t->{
     f.db.execute("alter table engine.run disable trigger authorized_run_immutable");f.db.update("update engine.run set inputs=?::jsonb where id=?",payload,id);
     assertThatThrownBy(()->new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,p.current())).isInstanceOf(PlatformException.class);t.setRollbackOnly();return null;
    });}finally{f.tx.setIsolationLevel(-1);}});assertThat(f.ds.payloadReads.get()).isEqualTo(1);
   }
  }
 }
 @Test void integersBeyondDoubleRangeAndSignedZeroRemainExact()throws Exception{
  try(var f=new RunAuthorityFixture()){
   var exact=new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
   var hashes=new ArrayList<String>();String huge="1"+"0".repeat(400);
   for(String json:List.of("{\"n\":"+huge+"}","{\"n\":"+huge.substring(0,400)+"1}","{\"n\":-0.0}","{\"n\":0}")){
    JsonNode inputs=exact.readTree(json);long id=f.admitted(p->f.tx.execute(t->f.store.createPublished(p,f.snapshot(),inputs,Json.read("{\"appVer\":1}"),f.budget(),"FULL",UUID.randomUUID().toString(),Set.of())));
    var ref=f.store.load(id).scopes().stream().filter(v->v.material().kind().equals("run-inputs")).findFirst().orElseThrow().material();hashes.add(ref.payloadSha256());
    f.admitted(p->{var actual=(JsonNode)new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,p.current()).payload();assertThat(actual.path("n").decimalValue()).isEqualByComparingTo(inputs.path("n").decimalValue());return null;});
   }
   assertThat(hashes.get(0)).isNotEqualTo(hashes.get(1));assertThat(hashes.get(2)).isEqualTo(hashes.get(3));
  }
 }
 @Test void recomputedScopeHashCannotHideWrongParentOwnerOrAdmissionAudit()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var b=f.store.load(id);var s=b.scopes().stream().filter(v->v.material().kind().equals("run-version-refs")).findFirst().orElseThrow();
   var store=new MaterialScopeStore(f.ds,f.iam.clock());
   for(var bad:List.of(
    new MaterialScopeV1(1,s.material(),s.owner(),s.sourceDecisionId(),s.sourceAuthorizationVersion(),s.initialCeilingSha256(),s.requiredRoles(),s.requiredEntitlements(),s.requiredDataLabels(),List.of(Digest.sha256("nonexistent-parent")),null),
    new MaterialScopeV1(1,s.material(),new SubjectKey("tenant-a","bob"),s.sourceDecisionId(),s.sourceAuthorizationVersion(),s.initialCeilingSha256(),s.requiredRoles(),s.requiredEntitlements(),s.requiredDataLabels(),s.parentScopeSha256(),null),
    new MaterialScopeV1(1,s.material(),s.owner(),"other-admission",s.sourceAuthorizationVersion(),s.initialCeilingSha256(),s.requiredRoles(),s.requiredEntitlements(),s.requiredDataLabels(),s.parentScopeSha256(),null),
    new MaterialScopeV1(1,s.material(),s.owner(),s.sourceDecisionId(),s.sourceAuthorizationVersion(),s.initialCeilingSha256(),Set.of(),s.requiredEntitlements(),s.requiredDataLabels(),s.parentScopeSha256(),null)))
    assertThatThrownBy(()->f.tx.execute(t->{store.write(MaterialScopeStore.withDigest(bad));return null;})).isInstanceOf(PlatformException.class);
  }
 }
 @Test void sameRootStatusPurposeCannotReadInitialMaterial()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var ref=f.store.load(id).scopes().getFirst().material();
   var call=new ServiceCall("engine","proxy.engine.get.RunController.status",RunAuthorityFixture.ROOT);
   var current=f.engine.authorizeRetained(f.gateway.exchange(f.iam.portal(),call).value(),call).current();f.ds.payloadReads.set(0);
   assertThatThrownBy(()->new MaterialScopeStore(f.ds,f.iam.clock()).read(ref,current)).isInstanceOf(PlatformException.class);assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 static void currentA(Map<String,Object> c){if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));}
}
