package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class ToolCancellationRaceTest {
    private final TracePostgresTest fixture = new TracePostgresTest();

    @BeforeAll
    static void database() throws Exception {
        TracePostgresTest.connect();
    }

    @BeforeEach
    void clean() {
        fixture.clean();
    }

    @Test
    void validationAndSubmissionExcludeConcurrentCancellation() throws Exception {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var claim = TracePostgresTest.store.claim(n, "race");
        var input = fixture.call(r, n, claim);
        var calls = new ToolCalls(TracePostgresTest.store);
        long cid =
                TraceStore.number(
                        calls.register(input.register(), TracePostgresTest.actor), "callId");
        calls.claim(cid, input.claim(), TracePostgresTest.actor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ds = TracePostgresTest.db.getDataSource();
        var instrumented =
                new JdbcTemplate(ds) {
                    @Override
                    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                        T value = super.queryForObject(sql, type, args);
                        if (sql.contains("select exists(select 1 from engine.node_instance")
                                && entered.getCount() > 0) {
                            entered.countDown();
                            await(release);
                        }
                        return value;
                    }
                };
        var fenced =
                new ToolCalls(new TraceStore(instrumented, new DataSourceTransactionManager(ds)));
        String publicId =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var submit =
                    executor.submit(
                            () -> fenced.submitted(cid, input.claim(), TracePostgresTest.actor));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var cancel =
                    executor.submit(
                            () -> TracePostgresTest.runs.cancel(publicId, TracePostgresTest.actor));
            try {
                assertThrows(
                        TimeoutException.class,
                        () -> cancel.get(200, TimeUnit.MILLISECONDS),
                        "Cancellation must not commit inside the validated submission grant"
                                + " window");
            } finally {
                release.countDown();
            }
            assertEquals(true, submit.get(5, TimeUnit.SECONDS).get("submitted"));
            assertEquals("CANCELLED", cancel.get(5, TimeUnit.SECONDS).get("status"));
            assertFalse(
                    TracePostgresTest.store.commit(
                            r,
                            n,
                            TraceStore.number(claim, "attemptId"),
                            claim.get("lease_owner").toString(),
                            Map.of("late", true)));
        }
    }

    @Test
    void cancellationWinningFirstPreventsAnyExternalSubmission() throws Exception {
        long r = fixture.run(), n = fixture.node(r, "READY");
        var claim = TracePostgresTest.store.claim(n, "race");
        var input = fixture.call(r, n, claim);
        var calls = new ToolCalls(TracePostgresTest.store);
        long cid =
                TraceStore.number(
                        calls.register(input.register(), TracePostgresTest.actor), "callId");
        calls.claim(cid, input.claim(), TracePostgresTest.actor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ds = TracePostgresTest.db.getDataSource();
        var instrumented =
                new JdbcTemplate(ds) {
                    @Override
                    public int update(String sql, Object... args) {
                        int value = super.update(sql, args);
                        if (sql.contains("update engine.run set status='CANCELLED'")
                                && entered.getCount() > 0) {
                            entered.countDown();
                            await(release);
                        }
                        return value;
                    }
                };
        var cancelStore = new TraceStore(instrumented, new DataSourceTransactionManager(ds));
        var cancellation = SchedulerTestResources.runs(cancelStore, null, null, null);
        String publicId =
                TracePostgresTest.db.queryForObject(
                        "select public_id from engine.run where id=?", String.class, r);
        var external = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancel =
                    executor.submit(() -> cancellation.cancel(publicId, TracePostgresTest.actor));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var submit =
                    executor.submit(
                            () -> {
                                try {
                                    calls.submitted(cid, input.claim(), TracePostgresTest.actor);
                                    external.incrementAndGet();
                                    return true;
                                } catch (PlatformException expected) {
                                    return false;
                                }
                            });
            try {
                assertThrows(TimeoutException.class, () -> submit.get(200, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            cancel.get(5, TimeUnit.SECONDS);
            assertFalse(submit.get(5, TimeUnit.SECONDS));
            assertEquals(0, external.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Race gate timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
