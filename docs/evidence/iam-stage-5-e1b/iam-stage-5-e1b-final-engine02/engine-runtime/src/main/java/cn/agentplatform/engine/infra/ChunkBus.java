package cn.agentplatform.engine.infra;

import cn.agentplatform.common.PlatformException;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Transient text deltas are observation only; all recovery authority remains PostgreSQL (§31.6.3).
 */
@Component
public class ChunkBus implements AutoCloseable {
    public static final int MAX_CHUNK_BYTES = 8192;
    public static final int MAX_EVENT_BYTES = 262144;
    private final Map<Long, Set<Subscription>> subscribers = new HashMap<>();
    private final Semaphore slots;
    private final Semaphore bytes;
    private final Semaphore responseBytes = new Semaphore(16 * 1024 * 1024);
    private RedisChunks redis;
    private boolean closed;

    /**
     * Explicit local fixture for direct AgentDriver construction in tests; Spring never selects it.
     */
    public ChunkBus() {
        this(256, 16 * 1024 * 1024);
    }

    ChunkBus(int maximumSubscriptions, int bufferBytes) {
        if (maximumSubscriptions < 1
                || maximumSubscriptions > 5000
                || bufferBytes < 1
                || bufferBytes > 64 * 1024 * 1024)
            throw new IllegalArgumentException("stream limits");
        slots = new Semaphore(maximumSubscriptions);
        bytes = new Semaphore(bufferBytes);
    }

    @Autowired
    public ChunkBus(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:26379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${agent.stream.max-subscriptions:256}") int maximumSubscriptions,
            @Value("${agent.stream.buffer-bytes:16777216}") int bufferBytes) {
        this(maximumSubscriptions, bufferBytes);
        redis = new RedisChunks(host, port, password, this::receive, this::transportLost);
    }

    public synchronized Subscription subscribe(long run) {
        if (closed || !slots.tryAcquire())
            throw new PlatformException(429, "STREAM_CAPACITY", "实时观察已满，请稍后重连或查询运行状态");
        var subscription = new Subscription(run);
        subscribers.computeIfAbsent(run, key -> new HashSet<>()).add(subscription);
        return subscription;
    }

    public void publish(long run, String text) {
        if (text == null || text.isEmpty()) return;
        if (text.length() > MAX_CHUNK_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > MAX_CHUNK_BYTES) {
            reset(run, "CHUNK_LIMIT");
            return;
        }
        // Production delivery goes through Redis exactly once, including the publishing replica.
        if (redis == null) receive(run, text);
        else redis.publish(run, text);
    }

    private synchronized void receive(long run, String text) {
        if (closed) return;
        for (var subscription : subscribers.getOrDefault(run, Set.of())) subscription.offer(text);
    }

    private synchronized void reset(long run, String reason) {
        for (var subscription : subscribers.getOrDefault(run, Set.of())) subscription.stop(reason);
    }

    private synchronized void transportLost() {
        subscribers.values().forEach(set -> set.forEach(s -> s.stop("CHUNK_TRANSPORT_RESET")));
    }

    public boolean chunkAvailable() {
        return redis == null || redis.available();
    }

    /**
     * Reserve before reading PG and hold through send; 5000 configured viewers cannot multiply
     * payload memory.
     */
    public AutoCloseable reserveResponse() {
        return responseBytes.tryAcquire(MAX_EVENT_BYTES)
                ? new Reservation(responseBytes, MAX_EVENT_BYTES)
                : null;
    }

    public final class Subscription implements AutoCloseable {
        private final long run;
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(32);
        private boolean removed;
        private volatile String reason;

        private Subscription(long run) {
            this.run = run;
        }

        private void offer(String text) {
            if (stopped()) return;
            int size = text.getBytes(StandardCharsets.UTF_8).length;
            if (!bytes.tryAcquire(size)) {
                stop("BUFFER_LIMIT");
                return;
            }
            var frame = new Frame(text, size);
            if (!queue.offer(frame)) {
                frame.close();
                stop("SLOW_OBSERVER");
            }
        }

        private void stop(String why) {
            if (reason == null) reason = why;
            Frame frame;
            while ((frame = queue.poll()) != null) frame.close();
        }

        public Frame poll() {
            return queue.poll();
        }

        public boolean stopped() {
            return reason != null;
        }

        public String resetReason() {
            return reason;
        }

        @Override
        public void close() {
            synchronized (ChunkBus.this) {
                if (removed) return;
                removed = true;
                stop("CLOSED");
                var set = subscribers.get(run);
                if (set != null) {
                    set.remove(this);
                    if (set.isEmpty()) subscribers.remove(run);
                }
                // A stopped but blocked SSE still owns its slot until its sender finally exits.
                slots.release();
            }
        }
    }

    public final class Frame implements AutoCloseable {
        private final String text;
        private final Reservation reservation;

        private Frame(String text, int size) {
            this.text = text;
            reservation = new Reservation(bytes, size);
        }

        public String text() {
            return text;
        }

        @Override
        public void close() {
            reservation.close();
        }
    }

    private static final class Reservation implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final Semaphore budget;
        private final int size;

        private Reservation(Semaphore budget, int size) {
            this.budget = budget;
            this.size = size;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) budget.release(size);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            transportLost();
            for (var set : new ArrayList<>(subscribers.values()))
                for (var subscription : new ArrayList<>(set)) subscription.close();
        }
        if (redis != null) redis.close();
    }
}
