package cn.agentplatform.engine.infra;
import cn.agentplatform.engine.application.RunAuthority;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
class RunAuthorityLocalCurrentPostgresTest {
 static RunAuthority service(RunAuthorityFixture f){return new RunAuthority(f.store,f.engine,f.iam.clock(),Duration.ofSeconds(3));}
 @Test void newDelegatedOperationSurvivesExpiredPortalButUsesOriginalGrant()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.advanceClock(Duration.ofSeconds(41));int before=f.iam.authorityCalls();
   assertThatCode(()->{var current=service(f).current(id,RunAuthorityFixture.LOCAL);assertThat(current.operation().sourceKind()).isEqualTo("DELEGATED");}).doesNotThrowAnyException();
   assertThat(f.iam.authorityCalls()).isEqualTo(before+2);assertThat(f.store.load(id).checkVersion()).isEqualTo(1);
  }
 }
 @Test void narrowerCurrentPermanentlyBlocksBackgroundButPreservesHistoryAndOrdinaryRead()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);f.iam.decisionMutation(InitialMaterialScopePostgresTest::currentA);
   assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);
   var after=f.store.load(id);assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");assertThat(after.initial()).isEqualTo(before.initial());assertThat(after.scopes()).isEqualTo(before.scopes());
   int requests=f.iam.requestAttempts();f.iam.decisionMutation(c->{});assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);assertThat(f.iam.requestAttempts()).isEqualTo(requests);
   f.admitted(p->new MaterialScopeStore(f.ds,f.iam.clock()).read(after.scopes().getFirst().material(),p.current()));
  }
 }
 @Test void unavailableBackoffUsesTrustedClockZeroNetworkUntilDueAndClearsOnlyTemporaryBlock()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.unavailable(true);
   assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
   var blocked=f.store.load(id);assertThat(blocked.blockedReason()).isEqualTo("AUTHORITY_UNAVAILABLE");assertThat(blocked.retryNotBefore()).isEqualTo(f.iam.clock().instant().plusSeconds(3));
   int before=f.iam.requestAttempts();f.iam.unavailable(false);assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);assertThat(f.iam.requestAttempts()).isEqualTo(before);
   f.iam.advanceClock(Duration.ofSeconds(3));service(f).current(id,RunAuthorityFixture.LOCAL);assertThat(f.iam.requestAttempts()).isEqualTo(before+2);assertThat(f.store.load(id).blockedReason()).isNull();
  }
 }
 @Test void originalGrantExpiryAndRevocationNeverReturnCurrent()throws Exception{
  for(boolean expire:List.of(false,true))try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));if(expire)f.iam.advanceClock(Duration.ofSeconds(300));else f.iam.revoked(true);
   int before=f.iam.requestAttempts();assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);
   assertThat(f.store.load(id).blockedReason()).isEqualTo(expire?"AUTHORITY_EXPIRED":"AUTHORITY_DENIED");if(expire)assertThat(f.iam.requestAttempts()).isEqualTo(before);
  }
 }
 @Test void sameSignedTamperedOriginCannotReplaceStoredOriginalAuthentication()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.ticketMutation(c->c.put("origin_authentication_id","other-original"));f.iam.decisionMutation(c->{if(c.containsKey("origin_authentication_id"))c.put("origin_authentication_id","other-original");});
   assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);assertThat(f.store.load(id).blockedReason()).isEqualTo("AUTHORITY_DENIED");
  }
 }
 @Test void staleSuccessCannotOverwriteConcurrentPermanentBlockOrReturnAdvanceAuthority()throws Exception{
  try(var f=new RunAuthorityFixture();var executor=Executors.newSingleThreadExecutor()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);
   var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
   f.iam.decisionMutation(c->{if(c.containsKey("subject_caller")){arrived.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("barrier timeout");}catch(InterruptedException e){throw new AssertionError(e);}}});
   var future=executor.submit(()->catchThrowable(()->service(f).current(id,RunAuthorityFixture.LOCAL)));
   assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();assertThat(f.store.recordCheck(before,null,"AUTHORITY_DENIED",Duration.ofSeconds(3))).isTrue();release.countDown();
   assertThat(future.get(6,TimeUnit.SECONDS)).isInstanceOf(PlatformException.class);var after=f.store.load(id);assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");assertThat(after.checkVersion()).isEqualTo(1);
  }
 }
 @Test void successCannotBeRecordedWithoutFreshRetainedProof()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));var b=f.store.load(id);
   assertThatThrownBy(()->f.store.recordCheck(b,null,null,Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);assertThat(f.store.load(id)).isEqualTo(b);
  }
 }
 @Test void wrongTupleRootAndCallerTransactionMakeZeroNetworkCalls()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(t->f.create(p)));int calls=f.iam.requestAttempts();
   for(var call:List.of(RunAuthorityFixture.MAIN,new ServiceCall("app","run.advance",RunAuthorityFixture.ROOT),new ServiceCall("engine","run.advance",new ResourceBinding("app-version","other@1",RunAuthorityFixture.ROOT.versionDigest()))))assertThatThrownBy(()->service(f).current(id,call)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->f.tx.execute(t->service(f).current(id,RunAuthorityFixture.LOCAL))).isInstanceOf(IllegalStateException.class);assertThat(f.iam.requestAttempts()).isEqualTo(calls);
  }
 }
 @Test void unknownDatabaseErrorIsNotConvertedToAuthorityUnavailable()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);
   f.db.execute("create function engine.e1b_check_fault() returns trigger language plpgsql as $$ begin raise exception 'E1B_PROGRAM_DB_ERROR'; end $$");f.db.execute("create trigger e1b_check_fault before update on engine.run_authority for each row execute function engine.e1b_check_fault()");
   try{assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
   finally{f.db.execute("drop trigger e1b_check_fault on engine.run_authority");f.db.execute("drop function engine.e1b_check_fault()");}
   assertThat(f.store.load(id)).isEqualTo(before);
  }
 }
 @Test void localTicketIsCryptographicallyValidButRejectedByActualHttpGuard()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);var ticket=f.engine.resume(b.delegation(),RunAuthorityFixture.LOCAL);
   try(var decoder=new TrustedJwtDecoder(f.iam.config("engine"),f.iam.clock(),true)){
    var req=new MockHttpServletRequest("POST","/admin/apps/app-a/runs");var response=new MockHttpServletResponse();req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),f.engine);var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,com.fasterxml.jackson.databind.JsonNode.class));
    try{assertThatThrownBy(()->guard.preHandle(req,response,handler)).isInstanceOf(PlatformException.class);}
    finally{guard.afterCompletion(req,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
   }
  }
 }
}
