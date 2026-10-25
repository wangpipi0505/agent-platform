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
   f.admitted(p->{assertThat(p.current().delegation()).isNotEqualTo(old.delegation());assertThat(service(f).status(old.publicId(),p.current().actor()).get("runId")).isEqualTo(old.publicId());assertThatThrownBy(()->trace(f).createNode(i.run(),"bad",2,"END",Json.read("{}"),p)).isInstanceOf(IllegalStateException.class);return null;});
   var after=f.store.load(i.run());assertThat(after.initial()).isEqualTo(old.initial());assertThat(after.delegation()).isEqualTo(old.delegation());assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");assertThatThrownBy(()->current(f,i.run())).isInstanceOf(PlatformException.class);
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
   var status=service(f).status(publicId,RunAuthorityFixture.actor(Set.of("A","B")));assertThat(status.get("output")).isEqualTo(Json.read("{}"));assertThat((List<?>)status.get("events")).hasSize(1);assertThat((List<?>)status.get("nodes")).isEmpty();
  }
 }
}
