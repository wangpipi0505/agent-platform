package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.RunAuthority;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class RunAuthorityLocalCurrentPostgresTest {
    @Test
    void shortProofExpiryAtSuccessCasDoesNotPermanentlyBlockValidOriginalGrant() throws Exception {
        try (var f = new RunAuthorityFixture()) {
            long id = f.admitted(p -> f.tx.execute(s -> f.create(p)));
            var before = f.store.load(id);
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            f.ds.beforeAuthorityUpdate =
                    pid -> {
                        if (once.compareAndSet(false, true))
                            f.iam.advanceClock(Duration.ofSeconds(6));
                    };
            assertThatThrownBy(() -> service(f).currentRetained(id, RunAuthorityFixture.LOCAL))
                    .isInstanceOfSatisfying(
                            PlatformException.class,
                            e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
            assertThat(f.store.load(id)).isEqualTo(before);
            f.ds.beforeAuthorityUpdate = pid -> {};
            assertThatCode(() -> service(f).currentRetained(id, RunAuthorityFixture.LOCAL))
                    .doesNotThrowAnyException();
            assertThat(f.store.load(id).blockedReason()).isNull();
            assertThat(f.store.load(id).checkVersion()).isEqualTo(before.checkVersion() + 1);
        }
    }

 static RunAuthority service(RunAuthorityFixture f){return new RunAuthority(f.store,f.engine,f.iam.clock(),Duration.ofSeconds(3));}
 @Test void successExpiresWhileCasWaitsOnUnchangedRow()throws Exception{
  try(var f=new RunAuthorityFixture();var executor=Executors.newSingleThreadExecutor()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var initial=f.store.load(id);
            assertThat(
                            f.store.recordCheck(
                                    initial, null, "AUTHORITY_UNAVAILABLE", Duration.ofSeconds(3)))
                    .isTrue();
            f.iam.advanceClock(Duration.ofSeconds(3));
            var before = f.store.load(id);
   var ticket=f.engine.resume(before.delegation(),RunAuthorityFixture.LOCAL);var proof=f.engine.authorizeRetained(ticket.value(),RunAuthorityFixture.LOCAL);
   var arrived=new CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicInteger();
   f.ds.beforeAuthorityUpdate=p->{pid.set(p);arrived.countDown();};
   try(var blocker=f.ds.getConnection()){
    blocker.setAutoCommit(false);
                try (var lock =
                        blocker.prepareStatement(
                                "select run_id from engine.run_authority where run_id=? for"
                                        + " update")) {
     lock.setLong(1,id);try(var row=lock.executeQuery()){assertThat(row.next()).isTrue();}
     var future=executor.submit(()->catchThrowable(()->f.store.recordCheck(before,proof,null,Duration.ofSeconds(3))));
     try{
      assertThat(arrived.await(5,TimeUnit.SECONDS)).isTrue();
      boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        do {
                            waiting =
                                    Boolean.TRUE.equals(
                                            f.db.queryForObject(
                                                    "select wait_event_type='Lock' and query like"
                                                            + " 'update engine.run_authority set"
                                                            + " last_decision_id=%' from"
                                                            + " pg_stat_activity where pid=?",
                                                    Boolean.class, pid.get()));
                            if (!waiting) Thread.sleep(20);
                        } while (!waiting && System.nanoTime() < deadline);
      assertThat(waiting).as("actual CAS UPDATE waits on the unchanged authority row").isTrue();
      assertThat(f.store.load(id)).isEqualTo(before);f.iam.advanceClock(Duration.ofSeconds(6));
     }finally{blocker.rollback();}
                    assertThat(future.get(6, TimeUnit.SECONDS))
                            .isInstanceOfSatisfying(
                                    PlatformException.class,
                                    e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
    }finally{blocker.rollback();}
   }
   // Public repository success must roll back even without the application catch/failure recorder.
   assertThat(f.store.load(id)).isEqualTo(before);assertThat(f.count()).isGreaterThan(0);
            assertThat(
                            f.db.queryForObject(
                                    "select status from engine.run where id=?", String.class, id))
                    .isEqualTo("PENDING");
  }
 }
 @Test void currentDoesNotReturnProofExpiredDuringCommit()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);var commits=new java.util.concurrent.atomic.AtomicInteger();
            f.ds.afterSuccessCommit =
                    () -> {
                        commits.incrementAndGet();
                        assertThat(
                                        new org.springframework.jdbc.core.JdbcTemplate(
                                                        f.ds.getTargetDataSource())
                                                .queryForObject(
                                                        "select check_version from"
                                                                + " engine.run_authority where"
                                                                + " run_id=?",
                                                        Long.class,
                                                        id))
                                .isEqualTo(before.checkVersion() + 1);
                        f.iam.advanceClock(Duration.ofSeconds(6));
                    };
            assertThatThrownBy(() -> service(f).current(id, RunAuthorityFixture.LOCAL))
                    .isInstanceOfSatisfying(
                            PlatformException.class,
                            e -> assertThat(e.code()).isEqualTo("AUTHORITY_EXPIRED"));
   assertThat(commits.get()).isEqualTo(1);var after=f.store.load(id);
   // The committed success remains historical; the stale failure CAS cannot overwrite its new version.
   assertThat(after.checkVersion()).isEqualTo(before.checkVersion()+1);assertThat(after.blockedReason()).isNull();
   assertThat(after.initial()).isEqualTo(before.initial());assertThat(after.scopes()).isEqualTo(before.scopes());
            assertThat(
                            f.db.queryForObject(
                                    "select last_decision_id is not null from engine.run_authority"
                                            + " where run_id=?",
                                    Boolean.class,
                                    id))
                    .isTrue();
  }
 }
 @Test void newDelegatedOperationSurvivesExpiredPortalButUsesOriginalGrant()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.advanceClock(Duration.ofSeconds(41));int before=f.iam.authorityCalls();
            assertThatCode(
                            () -> {
                                var current = service(f).current(id, RunAuthorityFixture.LOCAL);
                                assertThat(current.operation().sourceKind()).isEqualTo("DELEGATED");
                            })
                    .doesNotThrowAnyException();
   assertThat(f.iam.authorityCalls()).isEqualTo(before+2);assertThat(f.store.load(id).checkVersion()).isEqualTo(1);
  }
 }
 @Test void narrowerCurrentPermanentlyBlocksBackgroundButPreservesHistoryAndOrdinaryRead()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);f.iam.decisionMutation(InitialMaterialScopePostgresTest::currentA);
   assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);
            var after = f.store.load(id);
            assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");
            assertThat(after.initial()).isEqualTo(before.initial());
            assertThat(after.scopes()).isEqualTo(before.scopes());
   int requests=f.iam.requestAttempts();f.iam.decisionMutation(c->{});assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);assertThat(f.iam.requestAttempts()).isEqualTo(requests);
   f.admitted(p->new MaterialScopeStore(f.ds,f.iam.clock()).read(after.scopes().getFirst().material(),p));
  }
 }
 @Test void unavailableBackoffUsesTrustedClockZeroNetworkUntilDueAndClearsOnlyTemporaryBlock()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.unavailable(true);
   assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(503));
            var blocked = f.store.load(id);
            assertThat(blocked.blockedReason()).isEqualTo("AUTHORITY_UNAVAILABLE");
            assertThat(blocked.retryNotBefore()).isEqualTo(f.iam.clock().instant().plusSeconds(3));
   int before=f.iam.requestAttempts();f.iam.unavailable(false);assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);assertThat(f.iam.requestAttempts()).isEqualTo(before);
   f.iam.advanceClock(Duration.ofSeconds(3));service(f).current(id,RunAuthorityFixture.LOCAL);assertThat(f.iam.requestAttempts()).isEqualTo(before+2);assertThat(f.store.load(id).blockedReason()).isNull();
  }
 }
 @Test void originalGrantExpiryAndRevocationNeverReturnCurrent()throws Exception{
  for(boolean expire:List.of(false,true))try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));if(expire)f.iam.advanceClock(Duration.ofSeconds(300));else f.iam.revoked(true);
   int before=f.iam.requestAttempts();assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(PlatformException.class);
                assertThat(f.store.load(id).blockedReason())
                        .isEqualTo(expire ? "AUTHORITY_EXPIRED" : "AUTHORITY_DENIED");
                if (expire) assertThat(f.iam.requestAttempts()).isEqualTo(before);
  }
 }
 @Test void sameSignedTamperedOriginCannotReplaceStoredOriginalAuthentication()throws Exception{
  try(var f=new RunAuthorityFixture()){
            long id = f.admitted(p -> f.tx.execute(s -> f.create(p)));
            f.iam.ticketMutation(c -> c.put("origin_authentication_id", "other-original"));
            f.iam.decisionMutation(
                    c -> {
                        if (c.containsKey("origin_authentication_id"))
                            c.put("origin_authentication_id", "other-original");
                    });
            assertThatThrownBy(() -> service(f).current(id, RunAuthorityFixture.LOCAL))
                    .isInstanceOf(PlatformException.class);
            assertThat(f.store.load(id).blockedReason()).isEqualTo("AUTHORITY_DENIED");
  }
 }
 @Test void staleSuccessCannotOverwriteConcurrentPermanentBlockOrReturnAdvanceAuthority()throws Exception{
  try(var f=new RunAuthorityFixture();var executor=Executors.newSingleThreadExecutor()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);
   var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);
            f.iam.decisionMutation(
                    c -> {
                        if (c.containsKey("subject_caller")) {
                            arrived.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("barrier timeout");
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                    });
   var future=executor.submit(()->catchThrowable(()->service(f).current(id,RunAuthorityFixture.LOCAL)));
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f.store.recordCheck(before, null, "AUTHORITY_DENIED", Duration.ofSeconds(3)))
                    .isTrue();
            release.countDown();
            assertThat(future.get(6, TimeUnit.SECONDS)).isInstanceOf(PlatformException.class);
            var after = f.store.load(id);
            assertThat(after.blockedReason()).isEqualTo("AUTHORITY_DENIED");
            assertThat(after.checkVersion()).isEqualTo(1);
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
            for (var call :
                    List.of(
                            RunAuthorityFixture.MAIN,
                            new ServiceCall("app", "run.advance", RunAuthorityFixture.ROOT),
                            new ServiceCall(
                                    "engine",
                                    "run.advance",
                                    new ResourceBinding(
                                            "app-version",
                                            "other@1",
                                            RunAuthorityFixture.ROOT.versionDigest()))))
                assertThatThrownBy(() -> service(f).current(id, call))
                        .isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->f.tx.execute(t->service(f).current(id,RunAuthorityFixture.LOCAL))).isInstanceOf(IllegalStateException.class);assertThat(f.iam.requestAttempts()).isEqualTo(calls);
  }
 }
 @Test void unknownDatabaseErrorIsNotConvertedToAuthorityUnavailable()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var before=f.store.load(id);
            f.db.execute(
                    "create function engine.e1b_check_fault() returns trigger language plpgsql as"
                            + " $$ begin raise exception 'E1B_PROGRAM_DB_ERROR'; end $$");
            f.db.execute(
                    "create trigger e1b_check_fault before update on engine.run_authority for each"
                            + " row execute function engine.e1b_check_fault()");
   try{assertThatThrownBy(()->service(f).current(id,RunAuthorityFixture.LOCAL)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            } finally {
                f.db.execute("drop trigger e1b_check_fault on engine.run_authority");
                f.db.execute("drop function engine.e1b_check_fault()");
            }
   assertThat(f.store.load(id)).isEqualTo(before);
  }
 }
 @Test void localTicketIsCryptographicallyValidButRejectedByActualHttpGuard()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);var ticket=f.engine.resume(b.delegation(),RunAuthorityFixture.LOCAL);
   try(var decoder=new TrustedJwtDecoder(f.iam.config("engine"),f.iam.clock(),true)){
                var req = new MockHttpServletRequest("POST", "/admin/apps/app-a/runs");
                var response = new MockHttpServletResponse();
                req.setAttribute(
                        HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/apps/{code}/runs");
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
                var guard = new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
                var handler =
                        new HandlerMethod(
                                new RunController(null, null, null, null),
                                RunController.class.getMethod(
                                        "run",
                                        String.class,
                                        com.fasterxml.jackson.databind.JsonNode.class));
    try{assertThatThrownBy(()->guard.preHandle(req,response,handler)).isInstanceOf(PlatformException.class);}
    finally{guard.afterCompletion(req,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
   }
  }
 }
}
