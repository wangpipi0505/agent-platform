package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.engine.infra.TraceStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** MNT-05 恢复协调：只验证分发与短事务边界，不重复执行/租约状态机本身。 */
class RunRecoveryCoordinatorTest {

    final TraceStore store = mock(TraceStore.class);
    final RunScheduler scheduler = mock(RunScheduler.class);
    final JdbcTemplate db = mock(JdbcTemplate.class);

    RunRecoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(store.db()).thenReturn(db);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(store)
                .atomic(any());
        coordinator = new RunRecoveryCoordinator(store, scheduler);
    }

    static final class RecordingHandler implements RunRecoveryCoordinator.Handler {
        final List<Long> owned = new ArrayList<>();
        final List<Long> rejected = new ArrayList<>();
        final Map<Long, Actor> legacy = new LinkedHashMap<>();

        @Override
        public void driveOwned(long id) {
            owned.add(id);
        }

        @Override
        public void driveLegacy(long id, Actor actor) {
            legacy.put(id, actor);
        }

        @Override
        public void guardRejected(long id) {
            rejected.add(id);
        }
    }

    @Test
    void protectedRunGoesStraightToTheOwnedPath() {
        when(store.protectedRun(7L)).thenReturn(true);
        var handler = new RecordingHandler();

        coordinator.recovered(7L, handler);

        assertEquals(List.of(7L), handler.owned);
        assertTrue(handler.legacy.isEmpty());
        assertTrue(handler.rejected.isEmpty());
    }

    @Test
    void legacyRunRebuildsActorFromTheDurableGuard() {
        when(store.protectedRun(7L)).thenReturn(false);
        when(db.queryForMap(anyString(), eq(7L)))
                .thenReturn(
                        Map.of(
                                "tenant_id",
                                "tenant-a",
                                "principal_id",
                                "alice",
                                "guard_ctx",
                                "{\"tenantId\":\"tenant-a\",\"principalId\":\"alice\","
                                        + "\"roles\":[\"operator\"],"
                                        + "\"attributes\":{\"region\":[\"A\"]}}"));
        var handler = new RecordingHandler();

        coordinator.recovered(7L, handler);

        var expected =
                new Actor("tenant-a", "alice", Set.of("operator"), Map.of("region", Set.of("A")));
        assertEquals(expected, handler.legacy.get(7L));
        assertTrue(handler.owned.isEmpty());
        assertTrue(handler.rejected.isEmpty());
    }

    @Test
    void guardMismatchStopsInsteadOfDrivingWithAFabricatedActor() {
        when(store.protectedRun(7L)).thenReturn(false);
        when(db.queryForMap(anyString(), eq(7L)))
                .thenReturn(
                        Map.of(
                                "tenant_id",
                                "tenant-a",
                                "principal_id",
                                "alice",
                                "guard_ctx",
                                "{\"tenantId\":\"tenant-a\",\"principalId\":\"bob\","
                                        + "\"roles\":[\"operator\"]}"));
        var handler = new RecordingHandler();

        coordinator.recovered(7L, handler);

        assertEquals(List.of(7L), handler.rejected);
        assertTrue(handler.legacy.isEmpty());
        assertTrue(handler.owned.isEmpty());
    }

    @Test
    void externalFactsWakeTheRunOnlyWhenTheNodeActuallyFlipped() {
        when(db.queryForList(anyString()))
                .thenReturn(List.of(Map.of("id", 11L, "run_id", 3L)))
                .thenReturn(List.of(Map.of("id", 12L, "run_id", 4L)));
        // 第一次翻转成功，第二次已经是别的 worker 处理过（changed=0）。
        when(db.update(anyString(), any(Object[].class))).thenReturn(1, 1, 0);

        coordinator.resumeExternalFacts();

        verify(scheduler, times(1)).wake(3L);
        verify(scheduler, never()).wake(4L);
        verify(store, times(1)).event(eq(3L), eq("EXTERNAL_READY"), any());
        verify(db, times(1))
                .update(contains("update engine.run set status='RUNNING'"), eq(3L));
    }

    @Test
    void leaseRenewalSkipsWhenNoNodeWasClaimed() {
        when(db.update(anyString(), any(Object[].class))).thenReturn(0);

        coordinator.renewLeases("worker-1", Set.of(21L, 22L));

        verify(db, times(2)).update(contains("engine.node_instance"), any(Object[].class));
        verify(db, never()).update(contains("engine.tool_call"), any(Object[].class));
    }

    @Test
    void leaseRenewalFailureIsIsolatedPerNode() {
        AtomicInteger attempts = new AtomicInteger();
        when(db.update(anyString(), any(Object[].class)))
                .thenAnswer(
                        invocation -> {
                            if (attempts.getAndIncrement() == 0)
                                throw new IllegalStateException("transient");
                            return 0;
                        });

        coordinator.renewLeases("worker-1", Set.of(21L, 22L));

        assertEquals(2, attempts.get(), "一个节点续租失败不能跳过后续节点");
    }
}
