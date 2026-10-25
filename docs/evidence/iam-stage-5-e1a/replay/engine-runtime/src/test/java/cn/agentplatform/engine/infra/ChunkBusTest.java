package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

class ChunkBusTest {
    @Test
    void slowObserverCannotBlockPublisherAndOverflowKeepsItsThreadSlotUntilCleanup() {
        try (var bus = new ChunkBus(1, 1024)) {
            var observer = bus.subscribe(1);
            assertTimeoutPreemptively(
                    Duration.ofMillis(200),
                    () -> {
                        for (int i = 0; i < 33; i++) bus.publish(1, "delta");
                    });
            assertEquals("SLOW_OBSERVER", observer.resetReason());
            assertNull(observer.poll());
            assertThrows(PlatformException.class, () -> bus.subscribe(1));
            observer.close();
            observer.close();
            try (var replacement = bus.subscribe(1)) {
                assertFalse(replacement.stopped());
            }
        }
    }

    @Test
    void globalByteBudgetAppliesAcrossRunsAndIsReleasedAfterDeliveryOrClose() {
        try (var bus = new ChunkBus(4, 10);
                var a = bus.subscribe(1);
                var b = bus.subscribe(2)) {
            bus.publish(1, "123456");
            bus.publish(2, "123456");
            assertEquals("BUFFER_LIMIT", b.resetReason());
            try (var frame = a.poll()) {
                assertEquals("123456", frame.text());
            }
            try (var c = bus.subscribe(3)) {
                bus.publish(3, "1234567890");
                try (var frame = c.poll()) {
                    assertEquals("1234567890", frame.text());
                }
            }
        }
    }

    @Test
    void queuedAndInFlightFramesShareBudgetAndCancelledObserversAreRemoved() {
        try (var bus = new ChunkBus(4, 10);
                var a = bus.subscribe(1);
                var b = bus.subscribe(2)) {
            bus.publish(1, "123456");
            var frame = a.poll();
            bus.publish(2, "12345");
            assertEquals("BUFFER_LIMIT", b.resetReason());
            frame.close();
            frame.close();
            a.close();
            try (var c = bus.subscribe(1)) {
                bus.publish(1, "1234567890");
                try (var delivered = c.poll()) {
                    assertEquals("1234567890", delivered.text());
                }
                assertNull(c.poll());
            }
        }
    }

    @Test
    void textLimitCountsUtf8AndIsolationDoesNotLeakRuns() {
        try (var bus = new ChunkBus();
                var a = bus.subscribe(1);
                var other = bus.subscribe(2)) {
            bus.publish(1, "中".repeat(3000));
            assertEquals("CHUNK_LIMIT", a.resetReason());
            assertFalse(other.stopped());
            assertNull(other.poll());
        }
    }

    @Test
    void pgAndSendReservationsHaveGlobalByteBound() throws Exception {
        try (var bus = new ChunkBus()) {
            var permits = new ArrayList<AutoCloseable>();
            for (int i = 0; i < 64; i++) permits.add(Objects.requireNonNull(bus.reserveResponse()));
            assertNull(bus.reserveResponse());
            for (var permit : permits) permit.close();
            try (var permit = bus.reserveResponse()) {
                assertNotNull(permit);
            }
        }
    }
}
