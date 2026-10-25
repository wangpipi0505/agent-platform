package cn.agentplatform.engine.infra;

import static org.mockito.Mockito.*;

import cn.agentplatform.engine.api.RunController;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

class RunEventResponseTest {
    @Test
    void readyChunksAreDrainedWithoutAnArtificialPerChunkDelay() throws Exception {
        var store = mock(TraceStore.class);
        var db = mock(JdbcTemplate.class);
        when(store.run(anyString(), any())).thenReturn(Map.of("id", 1L));
        when(store.db()).thenReturn(db);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(db.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("status", "RUNNING", "event_seq", 0));
        RunEventsPostgresTest.actor("tenant", "alice");
        try (var bus = new ChunkBus()) {
            var response = new RunController(null, store, bus, null).events("r_test", "0", null);
            for (int i = 0; i < 32; i++) bus.publish(1, "ready");
            var frames = new java.util.concurrent.atomic.AtomicInteger();
            var output =
                    new java.io.OutputStream() {
                        int previous;

                        @Override
                        public void write(int value) {
                            if (value == '\n' && previous == '\n' && frames.incrementAndGet() == 33)
                                Thread.currentThread().interrupt();
                            previous = value;
                        }
                    };
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofMillis(300), () -> response.getBody().writeTo(output));
            org.junit.jupiter.api.Assertions.assertEquals(33, frames.get());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void doesNotReadHistoryUntilMvcStartsTheResponseWriter() throws Exception {
        var store = mock(TraceStore.class);
        var db = mock(JdbcTemplate.class);
        when(store.run(anyString(), any())).thenReturn(Map.of("id", 1L));
        when(store.db()).thenReturn(db);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(db.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("status", "SUCCEEDED", "event_seq", 0));
        RunEventsPostgresTest.actor("tenant", "alice");
        try (var bus = new ChunkBus()) {
            new RunController(null, store, bus, null).events("r_test", "0", null);
            Thread.sleep(100);
            verifyNoInteractions(db);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
