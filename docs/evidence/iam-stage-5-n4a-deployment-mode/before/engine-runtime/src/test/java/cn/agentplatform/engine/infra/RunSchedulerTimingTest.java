package cn.agentplatform.engine.infra;

import static org.mockito.Mockito.*;

import cn.agentplatform.engine.application.RunScheduler;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.*;

/** Captures the actual scheduled task arguments without pretending to run a 90 second lease. */
class RunSchedulerTimingTest {
    @ParameterizedTest
    @CsvSource({"100,33", "90000,30000", "300000,30000"})
    void heartbeatFirstDelayAndPeriodProtectBothDispatchAndFixedNodeLease(
            long lease, long expected) {
        var poll = mock(ScheduledExecutorService.class);
        var heartbeat = mock(ScheduledExecutorService.class);
        try (var executors = mockStatic(Executors.class);
                var scheduler =
                        new RunScheduler(mock(RunScheduleRepository.class), 1, 1, 20, lease, 0)) {
            executors
                    .when(
                            () ->
                                    Executors.newSingleThreadScheduledExecutor(
                                            any(ThreadFactory.class)))
                    .thenReturn(poll, heartbeat);
            scheduler.start();
            verify(heartbeat)
                    .scheduleWithFixedDelay(
                            any(Runnable.class),
                            eq(expected),
                            eq(expected),
                            eq(TimeUnit.MILLISECONDS));
        }
    }
}
