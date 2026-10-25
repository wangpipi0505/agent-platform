package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cn.agentplatform.engine.infra.FlowMaterialScopePostgresTest.*;
import static org.assertj.core.api.Assertions.*;

class FlowMaterialStatusPostgresTest {
 static RunService service(RunAuthorityFixture f){return new RunService(trace(f),null,null,null);}
 @Test void actualRunServiceStatusReadsOriginalMapInOneReadOnlyRrAndPreservesAliasesAndTimes()throws Exception{
  try(var f=fixture()){
   var i=insert(f);runningFixture(f,i.run());var trace=trace(f);var proof=current(f,i.run());var claim=trace.claim(i.node(),"worker",proof);trace.commit(i.run(),i.node(),TraceStore.number(claim,"attemptId"),(String)claim.get("lease_owner"),Map.of("ok",1),proof);
   var b=f.store.load(i.run());f.ds.payloadObservations.clear();f.ds.payloadReads.set(0);int requests=f.iam.requestAttempts();
   f.ds.beforePayload=()->{assertThat(f.iam.requestAttempts()).isGreaterThan(requests);assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();};
   var status=f.admitted(p->service(f).status(b.publicId(),p.current().actor()));
   assertThat(status.keySet()).containsExactly("runId","appCode","status","eventsWatermark","inputs","output","fileRefs","error","versionRefs","nodes","events","paused");assertThat(status.get("output")).isEqualTo(Json.read("{}"));assertThat(status.get("error")).isEqualTo(Json.read("{}"));assertThat(status.get("fileRefs")).isEqualTo(List.of());assertThat(status.get("paused")).isNull();
   var nodes=(List<Map<String,Object>>)status.get("nodes");assertThat(nodes).hasSize(1);assertThat(nodes.getFirst().keySet()).containsExactly("id","node_def_id","node_type","status","pause_seq","error","output","started_at","ended_at","nodeInstanceId","nodeDefId");assertThat(((Number)nodes.getFirst().get("nodeInstanceId")).longValue()).isEqualTo(i.node());assertThat(nodes.getFirst().get("ended_at")).isInstanceOf(java.sql.Timestamp.class);
   var events=(List<Map<String,Object>>)status.get("events");assertThat(events).hasSize(3);assertThat(events.getFirst().keySet()).containsExactly("seq","type","payload","created_at");assertThat(events.getFirst().get("created_at")).isInstanceOf(java.sql.Timestamp.class);
   assertThat(f.ds.payloadReads.get()).isEqualTo(14);assertThat(new HashSet<>(f.ds.payloadObservations)).hasSize(1);assertThat(f.ds.payloadObservations.getFirst()).endsWith(":4:true:false");
  }
 }
 @Test void narrowerCurrentWrongOwnerWrongRootAndRawStatusReadNoPayload()throws Exception{
  try(var f=fixture()){
   var i=insert(f);var b=f.store.load(i.run());f.ds.payloadReads.set(0);
   assertThatThrownBy(()->service(f).status(b.publicId(),RunAuthorityFixture.actor(Set.of("A","B")))).isInstanceOf(PlatformException.class);assertThat(f.ds.payloadReads.get()).isZero();
   f.iam.decisionMutation(InitialMaterialScopePostgresTest::currentA);f.admitted(p->{assertThatThrownBy(()->service(f).status(b.publicId(),p.current().actor())).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).isZero();
   f.iam.decisionMutation(c->{});var other=new ServiceCall("engine",RunAuthorityFixture.MAIN.action(),new ResourceBinding("app-version","other@1",Digest.sha256("other")));
   f.admitted(other,p->{assertThatThrownBy(()->service(f).status(b.publicId(),p.current().actor())).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).isZero();
   f.admitted(p->{var bob=new Actor("tenant-a","bob",p.current().actor().roles(),p.current().actor().attributes());assertThatThrownBy(()->service(f).status(b.publicId(),bob)).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void freshMainReadsExpiredAndBackgroundBlockedHistoryWithoutRebinding()throws Exception{
  try(var f=fixture()){
   var i=insert(f);var old=f.store.load(i.run());assertThat(f.store.recordCheck(old,null,"AUTHORITY_DENIED",Duration.ofSeconds(3))).isTrue();f.iam.advanceClock(Duration.ofSeconds(301));f.iam.portalActor(RunAuthorityFixture.actor(Set.of("A","B","C")));
   f.admitted(p->{assertThat(p.current().delegation()).isNotEqualTo(old.delegation());assertThat(service(f).status(old.publicId(),p.current().actor()).get("runId")).isEqualTo(old.publicId());var fresh=AuthorityContext.requireInitialFor(p.current().actor(),old.root());assertThatThrownBy(()->f.tx.execute(s->trace(f).createNode(i.run(),"bad",2,"END",Json.read("{}"),fresh))).isInstanceOf(PlatformException.class);return null;});
   var after=f.store.load(i.run());assertThat(after.initial()).isEqualTo(old.initial());assertThat(after.delegation()).isEqualTo(old.delegation());assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");assertThatThrownBy(()->current(f,i.run())).isInstanceOf(PlatformException.class);
  }
 }
 @Test void missingNullableScopeWrongDigestForgedParentAndDroppedParentLabelsDenyBeforePayload()throws Exception{
  for(String defect:List.of("missing","digest","parent","labels","initial"))try(var f=fixture()){
   var i=insert(f);var b=f.store.load(i.run());var row=f.db.queryForMap("select * from engine.material_scope where run_id=? and kind='run-output'",i.run());
   var original=Json.read(row.get("scope").toString(),MaterialScopeV1.class);var changed=original;
   if(defect.equals("parent"))changed=new MaterialScopeV1(1,original.material(),original.owner(),original.sourceDecisionId(),original.sourceAuthorizationVersion(),original.initialCeilingSha256(),original.requiredRoles(),original.requiredEntitlements(),original.requiredDataLabels(),List.of(Digest.sha256("forged-parent")),null);
   if(defect.equals("labels"))changed=new MaterialScopeV1(1,original.material(),original.owner(),original.sourceDecisionId(),original.sourceAuthorizationVersion(),original.initialCeilingSha256(),original.requiredRoles(),original.requiredEntitlements(),Set.of("initial"),original.parentScopeSha256(),null);
   if(defect.equals("initial"))changed=new MaterialScopeV1(1,original.material(),original.owner(),original.sourceDecisionId(),original.sourceAuthorizationVersion(),Digest.sha256("forged-initial"),original.requiredRoles(),original.requiredEntitlements(),original.requiredDataLabels(),original.parentScopeSha256(),null);
   if(changed!=original)changed=MaterialScopeStore.withDigest(changed);
   f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");
   try{
    if(defect.equals("missing"))f.db.update("delete from engine.material_scope where run_id=? and kind='run-output'",i.run());
    else if(defect.equals("digest"))f.db.update("update engine.material_scope set payload_sha256=? where run_id=? and kind='run-output'",Digest.sha256("wrong"),i.run());
    else f.db.update("update engine.material_scope set scope=?::jsonb,scope_sha256=? where run_id=? and kind='run-output'",Json.write(changed),changed.scopeSha256(),i.run());
    f.db.execute("alter table engine.material_scope enable trigger material_scope_immutable");f.ds.payloadReads.set(0);
    f.admitted(p->{assertThatThrownBy(()->service(f).status(b.publicId(),p.current().actor())).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).as(defect+" metadata must deny before payload").isZero();
   }finally{
    f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");
    if(defect.equals("missing"))f.db.update("insert into engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope,material_revision) values(?,?,?,?,?,?,?,?,?::jsonb,?)",row.get("run_id"),row.get("tenant_id"),row.get("producer"),row.get("kind"),row.get("material_id"),row.get("material_version"),row.get("payload_sha256"),row.get("scope_sha256"),row.get("scope").toString(),row.get("material_revision"));
    else f.db.update("update engine.material_scope set payload_sha256=?,scope_sha256=?,scope=?::jsonb where run_id=? and kind='run-output'",row.get("payload_sha256"),row.get("scope_sha256"),row.get("scope").toString(),i.run());
    f.db.execute("alter table engine.material_scope enable trigger material_scope_immutable");
   }
  }
 }
 @Test void actualContentCorruptionIsClassifiedAfterPayloadRatherThanMetadataDenial()throws Exception{
  try(var f=fixture()){
   var i=insert(f);var b=f.store.load(i.run());f.db.update("update engine.run set output='{\"corrupt\":true}'::jsonb where id=?",i.run());f.ds.payloadReads.set(0);
   f.admitted(p->{assertThatThrownBy(()->service(f).status(b.publicId(),p.current().actor())).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).isGreaterThan(0);
   f.db.update("update engine.run set output=null where id=?",i.run());
  }
 }
 @Test void expiryAfterPayloadAndRealReadCommitNeverReturnsStatus()throws Exception{
  for(boolean afterCommit:List.of(false,true))try(var f=fixture()){
   var i=insert(f);String id=f.store.load(i.run()).publicId();var once=new java.util.concurrent.atomic.AtomicBoolean();Runnable expire=()->{if(once.compareAndSet(false,true))f.iam.advanceClock(Duration.ofSeconds(6));};
   f.admitted(p->{if(afterCommit)f.ds.afterReadCommit=expire;else f.ds.afterPayload=expire;assertThatThrownBy(()->service(f).status(id,p.current().actor())).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));return null;});assertThat(once).isTrue();assertThat(f.ds.payloadReads.get()).isGreaterThan(0);
  }
 }
 @Test void statusRejectsAmbientReadCommittedAndRepeatableReadBeforeNetworkingOrPayload()throws Exception{
  try(var f=fixture()){
   var i=insert(f);String id=f.store.load(i.run()).publicId();for(int isolation:new int[]{2,4})f.admitted(p->{f.tx.setIsolationLevel(isolation);f.ds.payloadReads.set(0);int requests=f.iam.requestAttempts();assertThatThrownBy(()->f.tx.execute(s->service(f).status(id,p.current().actor()))).isInstanceOf(IllegalStateException.class).hasMessage("STATUS_REFRESH_REQUIRES_NO_TRANSACTION");assertThat(f.iam.requestAttempts()).isEqualTo(requests);assertThat(f.ds.payloadReads.get()).isZero();return null;});
  }
 }
 @Test void legacyWithoutAuthorityRetainsActualTraceStoreAndStatusBehavior()throws Exception{
  try(var f=fixture()){
   String publicId="r_legacy_"+UUID.randomUUID().toString().replace("-","");long id=f.db.queryForObject("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs) values(?,'app-a',12,'TASK','RUNNING','alice','tenant-a','legacy','{}','{}',?::jsonb,'{}','{}') returning id",Long.class,publicId,Json.write(f.budget()));
   var trace=trace(f);assertThat(trace.run(publicId,RunAuthorityFixture.actor(Set.of("A","B")))).containsEntry("status","RUNNING");trace.event(id,"LEGACY",Map.of("ok",true));assertThat(trace.events(id,0)).hasSize(1);
   long node=f.db.queryForObject("insert into engine.node_instance(run_id,node_def_id,step_seq,node_type,status,definition) values(?,'legacy-n',1,'START','READY','{}') returning id",Long.class,id);
   var claim=trace.claim(node,"legacy");long attempt=TraceStore.number(claim,"attemptId");trace.freeze(attempt,Map.of("request",true));trace.response(attempt,Map.of("value",true));assertThat(trace.commit(id,node,attempt,(String)claim.get("lease_owner"),Map.of("ok",1))).isTrue();assertThat(trace.commit(id,node,attempt,(String)claim.get("lease_owner"),Map.of())).isFalse();trace.consume(id,"tokens",2,200000);
   var status=service(f).status(publicId,RunAuthorityFixture.actor(Set.of("A","B")));assertThat(status.get("output")).isEqualTo(Json.read("{}"));assertThat((List<?>)status.get("events")).hasSize(4);assertThat((List<?>)status.get("nodes")).hasSize(1);
   assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Integer.class,id)).isZero();
  }
 }
 @Test void actualToolMetadataBlocksPureFlowStatusBeforeAnyResultOrPayloadRead()throws Exception{
  try(var f=fixture()){
   var i=insert(f);runningFixture(f,i.run());var proof=current(f,i.run());var claim=trace(f).claim(i.node(),"worker",proof);long attempt=TraceStore.number(claim,"attemptId");var b=f.store.load(i.run());
   f.db.update("insert into engine.tool_call(run_id,node_instance_id,call_seq,skill_version_id,args_digest,idempotency_key,status,skill_code,args,attempt_id,result) values(?,?,1,1,?,?,'SUCCEEDED','n1-fixture','{}',?,'{\"private\":true}')",i.run(),i.node(),Digest.sha256("args"),Digest.sha256(UUID.randomUUID().toString()),attempt);
   f.ds.payloadReads.set(0);f.ds.beforeSql=sql->{if(sql.matches("(?is)select .*result.*from engine.tool_call.*"))throw new AssertionError("RESULT_MUST_NOT_BE_READ");};
   f.admitted(p->{assertThatThrownBy(()->service(f).status(b.publicId(),p.current().actor())).isInstanceOf(PlatformException.class);return null;});assertThat(f.ds.payloadReads.get()).isZero();
  }
 }
 @Test void pausedProjectionKeepsOriginalAllowedActionsAndAliases()throws Exception{
  try(var f=fixture()){
   var i=insert(f);var proof=current(f,i.run());f.tx.setIsolationLevel(4);f.tx.execute(s->{var b=RunAuthorityStore.load(f.db,i.run(),true);var ms=new MaterialScopeStore(f.ds,f.iam.clock());ms.requireWriter(b,proof,false);var scopes=ms.currentScopes(b);f.db.update("update engine.node_instance set status='WAITING_STEP',pause_seq=3 where id=?",i.node());ms.append(b,"node-state",Long.toString(i.node()),proof,scopes);return null;});
   var b=f.store.load(i.run());var status=f.admitted(p->service(f).status(b.publicId(),p.current().actor()));var paused=(Map<String,Object>)status.get("paused");assertThat(((Number)paused.get("nodeInstanceId")).longValue()).isEqualTo(i.node());assertThat(paused).containsEntry("nodeDefId","n1").containsEntry("allowedActions",List.of("NEXT_NODE","RUN_TO_END","CANCEL"));assertThat(((Number)paused.get("pauseSeq")).longValue()).isEqualTo(3);
  }
 }
}
