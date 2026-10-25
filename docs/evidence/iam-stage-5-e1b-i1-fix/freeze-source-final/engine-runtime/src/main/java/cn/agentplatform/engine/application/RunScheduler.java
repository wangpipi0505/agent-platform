package cn.agentplatform.engine.application;

import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.domain.RunDispatch.Queue;
import cn.agentplatform.engine.infra.*;

import jakarta.annotation.PreDestroy;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/**
 * Two bounded local worker pools above PG dispatch generations. Waiting work stays in PG, never in
 * an unbounded executor queue. This does not replace distributed model/tool quotas.
 */
@Component
public class RunScheduler
        implements ApplicationListener<ContextClosedEvent>, ApplicationContextAware, AutoCloseable {
    private final RunScheduleRepository repository;
    private final EnumMap<Queue, Semaphore> pools = new EnumMap<>(Queue.class);
    private final ConcurrentMap<Long, RunDispatch> active = new ConcurrentHashMap<>();
    private final Object gate = new Object();
    private final ThreadLocal<RunDispatch> current = new ThreadLocal<>();
    private final String owner = UUID.randomUUID().toString();
    private final Duration lease, poll, drain;
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean draining;
    private volatile LongConsumer work;
    private Runnable renewNodes = () -> {}, readyFacts = () -> {};
    private ScheduledExecutorService timer;
    private ScheduledExecutorService heartbeatTimer;
    private ApplicationContext context;

    @Autowired
    public RunScheduler(
            RunScheduleRepository repository,
            @Value("${agent.scheduler.interactive-workers:16}") int interactive,
            @Value("${agent.scheduler.batch-workers:4}") int batch,
            @Value("${agent.scheduler.poll-ms:200}") long pollMs,
            @Value("${agent.scheduler.lease-ms:90000}") long leaseMs,
            @Value("${agent.scheduler.drain-ms:30000}") long drainMs) {
        if (interactive < 1
                || interactive > 256
                || batch < 1
                || batch > 256
                || pollMs < 10
                || pollMs > 10000
                || leaseMs < 100
                || leaseMs < pollMs * 3
                || drainMs < 0
                || drainMs > 300000)
            throw new IllegalArgumentException("Invalid bounded scheduler configuration");
        this.repository = repository;
        pools.put(Queue.INTERACTIVE, new Semaphore(interactive));
        pools.put(Queue.BATCH, new Semaphore(batch));
        poll = Duration.ofMillis(pollMs);
        lease = Duration.ofMillis(leaseMs);
        drain = Duration.ofMillis(drainMs);
    }

    /**
     * Explicit bounded scheduler for direct construction; Spring starts the same scheduler timer.
     */
    public RunScheduler(TraceStore store) {
        this(new RunScheduleRepository(store), 16, 4, 200, 90000, 30000);
    }

    public void bind(LongConsumer work, Runnable renewNodes, Runnable readyFacts) {
        this.work = Objects.requireNonNull(work);
        this.renewNodes = renewNodes;
        this.readyFacts = readyFacts;
    }

    public void start() {
        synchronized (gate) {
            if (timer != null || draining) return;
            // This timer is deliberately independent of @Scheduled. ContextClosedEvent cancels
            // Spring scheduled tasks before bean destruction, but in-flight leases need renewal
            // throughout our bounded drain, including the interval before @PreDestroy runs.
            timer =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                var t = new Thread(r, "engine-dispatch-" + owner);
                                t.setDaemon(true);
                                return t;
                            });
            heartbeatTimer =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                var t = new Thread(r, "engine-heartbeat-" + owner);
                                t.setDaemon(true);
                                return t;
                            });
            timer.scheduleWithFixedDelay(
                    () -> safe(this::poll), 0, poll.toMillis(), TimeUnit.MILLISECONDS);
            // Dispatch can have a longer lease, but nodes/tool claims retain their fixed 90s
            // lease. Both the first heartbeat and every later one must protect that boundary.
            long heartbeatMs = Math.max(10, Math.min(30000, lease.toMillis() / 3));
            heartbeatTimer.scheduleWithFixedDelay(
                    () -> safe(this::heartbeat), heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            LoggerFactory.getLogger(RunScheduler.class)
                    .info(
                            "Run scheduler started owner={} interactive={} batch={} leaseMs={}"
                                    + " drainMs={} pollMs={}",
                            owner,
                            pools.get(Queue.INTERACTIVE).availablePermits(),
                            pools.get(Queue.BATCH).availablePermits(),
                            lease.toMillis(),
                            drain.toMillis(),
                            poll.toMillis());
        }
    }

    private static void safe(Runnable task) {
        try {
            task.run();
        } catch (Exception failure) {
            LoggerFactory.getLogger(RunScheduler.class)
                    .warn("Scheduler operation failed: {}", failure.getClass().getSimpleName());
        }
    }

    public final class Admission implements AutoCloseable {
        private enum State {
            OPEN,
            PERSISTING,
            CLAIMING,
            TRANSFERRED,
            CLOSED
        }

        private final Queue queue;
        private State state = State.OPEN;
        private boolean persisted, accepted, reservationCompleted, closeRequested;

        private Admission(Queue queue) {
            this.queue = queue;
        }

        public <T> T persist(Supplier<T> write) {
            return persist(write, false);
        }

        /** Complete this already durable chat reservation, using its original finite slot. */
        <T> T completeReservation(Supplier<T> write) {
            return persist(write, true);
        }

        private <T> T persist(Supplier<T> write, boolean completion) {
            synchronized (gate) {
                if (state != State.OPEN
                        || (completion ? !accepted || reservationCompleted : persisted))
                    throw new IllegalStateException("Admission already consumed");
                if (!completion && draining) throw new RunAdmissionRejected(true);
                if (completion) reservationCompleted = true;
                else persisted = true;
                state = State.PERSISTING;
            }
            // Crossing the admission check starts an in-flight persistence operation. Stop never
            // rolls that transaction back; accepted PG work can remain queued. No DB work holds
            // the Java gate needed by shutdown or other pools.
            try {
                T result = write.get();
                synchronized (gate) {
                    if (!completion) accepted = true;
                }
                return result;
            } finally {
                synchronized (gate) {
                    state = State.OPEN;
                    if (closeRequested) close();
                }
            }
        }

        @Override
        public void close() {
            synchronized (gate) {
                if (state == State.CLAIMING || state == State.PERSISTING) {
                    closeRequested = true;
                    return;
                }
                if (state == State.OPEN) {
                    state = State.CLOSED;
                    pools.get(queue).release();
                    gate.notifyAll();
                }
            }
        }
    }

    public Admission reserveInteractive() {
        Admission ticket;
        synchronized (gate) {
            if (draining) throw new RunAdmissionRejected(true);
            if (!pools.get(Queue.INTERACTIVE).tryAcquire()) throw new RunAdmissionRejected(false);
            ticket = new Admission(Queue.INTERACTIVE);
        }
        try {
            // Previously accepted work goes first; continuous new requests cannot consume
            // every freshly released slot and starve old READY rows.
            var prior = repository.claimNext(Queue.INTERACTIVE, owner, lease);
            if (prior != null) {
                synchronized (gate) {
                    launch(prior, ticket);
                }
                throw new RunAdmissionRejected(false);
            }
            if (draining) throw new RunAdmissionRejected(true);
            return ticket;
        } catch (RuntimeException failure) {
            ticket.close();
            throw failure;
        }
    }

    public <T> T persistNew(Supplier<T> write) {
        synchronized (gate) {
            if (draining) throw new RunAdmissionRejected(true);
        }
        return write.get();
    }

    public void created(long id, Admission ticket) {
        if (ticket == null) {
            // Accepted batch/session recovery work only nudges its own queue once. A POST must
            // not synchronously scan unrelated external facts or drain an entire backlog.
            Queue queue = repository.queue(id);
            Admission queued;
            synchronized (gate) {
                if (draining || !pools.get(queue).tryAcquire()) return;
                queued = new Admission(queue);
            }
            try {
                var dispatch = repository.claimNext(queue, owner, lease);
                if (dispatch != null)
                    synchronized (gate) {
                        launch(dispatch, queued);
                    }
            } finally {
                queued.close();
            }
            return;
        }
        synchronized (gate) {
            if (ticket.state != Admission.State.OPEN)
                throw new IllegalStateException("Admission already consumed");
            ticket.state = Admission.State.CLAIMING;
        }
        try {
            if (draining) return; // Already accepted PG work survives shutdown.
            var dispatch = repository.claimSpecific(id, ticket.queue, owner, lease);
            if (dispatch != null)
                synchronized (gate) {
                    launch(dispatch, ticket);
                }
        } finally {
            synchronized (gate) {
                if (ticket.state == Admission.State.CLAIMING) ticket.state = Admission.State.OPEN;
            }
            ticket.close();
        }
    }

    public void poll() {
        if (work == null || draining) return;
        readyFacts.run();
        for (Queue queue : Queue.values()) {
            // A fast worker may release its slot while this loop is still running. A fixed
            // per-tick budget prevents an endless claim/release cycle under continuous load.
            int budget = pools.get(queue).availablePermits();
            for (int claimed = 0; claimed < budget && !draining; claimed++) {
                Admission ticket;
                synchronized (gate) {
                    if (draining || !pools.get(queue).tryAcquire()) break;
                    ticket = new Admission(queue);
                }
                try {
                    var dispatch = repository.claimNext(queue, owner, lease);
                    if (dispatch == null) break;
                    synchronized (gate) {
                        launch(dispatch, ticket);
                    }
                } finally {
                    ticket.close();
                }
            }
        }
    }

    private void launch(RunDispatch dispatch, Admission ticket) {
        if (draining || ticket.closeRequested || ticket.state == Admission.State.CLOSED) {
            repository.release(dispatch, poll);
            return;
        }
        if (active.putIfAbsent(dispatch.runId(), dispatch) != null) {
            repository.release(dispatch, poll);
            return;
        }
        ticket.state = Admission.State.TRANSFERRED;
        try {
            Thread.startVirtualThread(
                    () -> execute(dispatch, () -> safe(() -> work.accept(dispatch.runId()))));
        } catch (RuntimeException | Error failure) {
            finish(dispatch);
            throw failure;
        }
    }

    private void execute(RunDispatch dispatch, Runnable body) {
        current.set(dispatch);
        try {
            body.run();
        } finally {
            current.remove();
            finish(dispatch);
        }
    }

    private void finish(RunDispatch dispatch) {
        try {
            repository.release(dispatch, poll);
        } finally {
            synchronized (gate) {
                if (active.remove(dispatch.runId(), dispatch))
                    pools.get(dispatch.queue()).release();
                gate.notifyAll();
            }
        }
    }

    /**
     * Direct callers still acquire the same finite slot and PG generation; no synchronous bypass.
     */
    public void runNow(long id, Runnable body) {
        RunDispatch dispatch;
        Queue queue = repository.queue(id);
        synchronized (gate) {
            if (draining || active.containsKey(id)) return;
            if (!pools.get(queue).tryAcquire()) return;
        }
        try {
            dispatch = repository.claimSpecific(id, queue, owner, lease);
        } catch (RuntimeException failure) {
            pools.get(queue).release();
            throw failure;
        }
        if (dispatch == null) {
            pools.get(queue).release();
            return;
        }
        synchronized (gate) {
            if (draining || active.putIfAbsent(id, dispatch) != null) {
                repository.release(dispatch, poll);
                pools.get(queue).release();
                return;
            }
        }
        execute(dispatch, body);
    }

    public boolean owns(long id) {
        var dispatch = current.get();
        return dispatch != null && dispatch.runId() == id;
    }

    public boolean mayAdvance(long id) {
        var dispatch = current.get();
        return dispatch != null && dispatch.runId() == id && repository.valid(dispatch);
    }

    public void wake(long id) {
        repository.wake(id);
    }

    public int available(Queue queue) {
        return pools.get(queue).availablePermits();
    }

    public int activeWorkers() {
        synchronized (gate) {
            return active.size();
        }
    }

    public void heartbeat() {
        List<RunDispatch> snapshot;
        // Poll, admission and PG locks must not block the independent renewal lane.
        snapshot = List.copyOf(active.values());
        // A locked/failed item must not starve another run or the node/tool renewal lane.
        for (var dispatch : snapshot) safe(() -> repository.renew(dispatch, lease));
        safe(renewNodes);
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == context) draining = true;
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + drain.toNanos();
        synchronized (gate) {
            draining = true;
            while (!active.isEmpty() && System.nanoTime() < deadline) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(gate, Math.max(1, deadline - System.nanoTime()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Never release a blocked worker's slot or make its node artificially expire.
            // The dedicated heartbeat stops only after the drain; real PG leases then expire.
            if (timer != null) timer.shutdown();
            if (heartbeatTimer != null) heartbeatTimer.shutdown();
        }
    }
}
