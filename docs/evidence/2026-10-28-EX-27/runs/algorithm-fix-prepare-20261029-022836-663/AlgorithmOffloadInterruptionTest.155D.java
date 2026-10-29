package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AlgorithmOffloadInterruptionTest {
    static AlgorithmTestDatabase database;

    @BeforeAll
    static void database() {
        database = new AlgorithmTestDatabase("agent_platform_skill_algorithm_test", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "GET", "GET_BODY"})
    void interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String stage)
            throws Exception {
        String tenant = "algorithm_interrupt_" + UUID.randomUUID(), run = "r_" + UUID.randomUUID();
        long call = System.nanoTime();
        AlgorithmTestDatabase.identity(tenant, "owner");
        try (var peer = new LatchedS3();
                var shared = new ArtifactStore(database.db, peer.configuration())) {
            // Warm the same finite SDK protocol/crypto path before arming a stage latch.
            shared.offload(
                    new byte[0],
                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                    Map.of("type", "object"),
                    call + 1,
                    run);
            peer.stage = stage;
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ArtifactStore> scoped = new AtomicReference<>();
            AtomicInteger closedInput = new AtomicInteger();
            InputStream source =
                    new ByteArrayInputStream(
                            ("{\"value\":\"" + "x".repeat(600) + "\"}")
                                    .getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() throws IOException {
                            closedInput.incrementAndGet();
                            super.close();
                        }
                    };
            Thread worker =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        AlgorithmTestDatabase.identity(tenant, "owner");
                                        try (var operation =
                                                shared.forAlgorithmDeadline(
                                                        System.nanoTime()
                                                                + TimeUnit.SECONDS.toNanos(2))) {
                                            scoped.set(operation);
                                            operation.offload(
                                                    new byte[0],
                                                    source,
                                                    Map.of("type", "object"),
                                                    call,
                                                    run);
                                        } catch (Throwable ex) {
                                            failure.set(ex);
                                        } finally {
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                            SecurityContextHolder.clearContext();
                                        }
                                    });
            var diagnostic =
                    new InterruptionDiagnostic(
                            stage, tenant, call, peer, scoped, failure, interrupted, closedInput, worker);
            worker.start();
            try {
                assertTrue(
                        peer.entered.await(1, TimeUnit.SECONDS),
                        "actual SDK request reached " + stage);
                if (!diagnostic.afterReturnOnly) diagnostic.capture("peer-entered", false);
                long start = System.nanoTime();
                diagnostic.interruptNanos = start;
                worker.interrupt();
                worker.join(1000);
                assertFalse(worker.isAlive(), "bounded local cancellation return");
                long returnMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                diagnostic.joinReturnMs = returnMs;
                assertNotNull(failure.get(), "interruption cannot become READY success");
                // Fix the original judgment before diagnostic reads, PG access or peer release.
                boolean quiescentAtFirstCheck = scoped.get().algorithmIoQuiescent();
                diagnostic.firstCheck(quiescentAtFirstCheck);
                assertTrue(
                        quiescentAtFirstCheck,
                        "original SDK stages, calls and owned executor ended BEFORE return");
                assertTrue(interrupted.get(), "SDK wrapped interruption must preserve caller flag");
                assertTrue(closedInput.get() > 0, "owned source closed before return");
                assertEquals(0, peer.deletes.get(), "no delete races remote accepted operation");
                assertEquals(
                        "CLEANUP_PENDING",
                        database.db.queryForObject(
                                "select state from skill.artifact where tenant_id=? and call_id=?",
                                String.class,
                                tenant,
                                call));
                int before = peer.routes.size();
                diagnostic.beforeRelease();
                peer.release.countDown();
                assertTrue(peer.finished.await(1, TimeUnit.SECONDS));
                Thread.sleep(40);
                assertEquals(
                        before, peer.routes.size(), "no late upload/readback/cleanup requests");
                assertEquals(
                        0,
                        database.db.queryForObject(
                                "select count(*) from skill.artifact where tenant_id=? and"
                                        + " call_id=? and state='READY'",
                                Integer.class,
                                tenant,
                                call));
                diagnostic.capture("after-original-peer-wait", true);
                // The original shared client remains usable, with real PUT then GET.
                peer.stage = "";
                assertNotNull(
                        shared.offload(
                                        new byte[0],
                                        new ByteArrayInputStream(
                                                "{}".getBytes(StandardCharsets.UTF_8)),
                                        Map.of("type", "object"),
                                        call + 2,
                                        run)
                                .get("artifactId"));
                database.db.update(
                        "update skill.artifact set expires_at=now()-interval '1"
                            + " second',cleanup_after=now()-interval '1 second' where tenant_id=?",
                        tenant);
                shared.cleanTenant(tenant, 8);
                assertTrue(peer.objects.isEmpty());
                System.out.println(
                        "ALGORITHM_INTERRUPT stage="
                                + stage
                                + " returnMs="
                                + returnMs
                                + " flag="
                                + interrupted.get()
                                + " inputClosed="
                                + closedInput.get());
                diagnostic.originalBodyCompleted = true;
            } finally {
                diagnostic.beforeRelease();
                try {
                    peer.release.countDown();
                    worker.interrupt();
                    worker.join(1000);
                } finally {
                    diagnostic.capture("after-finally-peer-release", true);
                    diagnostic.emit();
                }
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit() throws Exception {
        String tenant = "algorithm_retained_" + UUID.randomUUID(), run = "r_" + UUID.randomUUID();
        long call = System.nanoTime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicReference<ArtifactStore> scoped = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // Deliberately delays a finite 2-byte source even after close, to cover the explicit drain
        // limit branch. No network request is needed or allowed after it is released.
        InputStream source =
                new InputStream() {
                    volatile boolean closed;

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        entered.countDown();
                        try {
                            if (!release.await(1500, TimeUnit.MILLISECONDS))
                                throw new IOException("finite source latch");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IOException("source interrupt");
                        }
                        if (closed) throw new IOException("closed source");
                        return -1;
                    }

                    @Override
                    public int read() throws IOException {
                        return read(new byte[1], 0, 1);
                    }

                    @Override
                    public void close() {
                        closed = true;
                    }
                };
        try (var peer = new LatchedS3();
                var shared = new ArtifactStore(database.db, peer.configuration())) {
            Thread worker =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        AlgorithmTestDatabase.identity(tenant, "owner");
                                        try (var operation =
                                                shared.forAlgorithmDeadline(
                                                        System.nanoTime()
                                                                + TimeUnit.SECONDS.toNanos(2))) {
                                            scoped.set(operation);
                                            operation.offload(
                                                    new byte[0],
                                                    source,
                                                    Map.of("type", "object"),
                                                    call,
                                                    run);
                                        } catch (Throwable ex) {
                                            failure.set(ex);
                                        } finally {
                                            flag.set(Thread.currentThread().isInterrupted());
                                            SecurityContextHolder.clearContext();
                                        }
                                    });
            worker.start();
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(800);
                assertFalse(worker.isAlive());
                assertNotNull(failure.get());
                assertTrue(flag.get());
                assertTrue(
                        scoped.get().algorithmIoRetained(),
                        "original unresolved SDK future still owns lifecycle");
                assertFalse(scoped.get().algorithmIoQuiescent());
                assertEquals(
                        "ALGORITHM_IO_STOP_NOT_CONFIRMED",
                        database.db.queryForObject(
                                "select last_error_code from skill.artifact where tenant_id=? and"
                                    + " call_id=?",
                                String.class,
                                tenant,
                                call));
                assertEquals(0, peer.routes.size());
                release.countDown();
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (!scoped.get().algorithmIoQuiescent() && System.nanoTime() < until)
                    Thread.sleep(5);
                assertTrue(
                        scoped.get().algorithmIoQuiescent(),
                        "actual completion disposes retained resources");
                assertFalse(scoped.get().algorithmIoRetained());
                assertEquals(0, peer.routes.size());
                assertEquals(
                        "CLEANUP_PENDING",
                        database.db.queryForObject(
                                "select state from skill.artifact where tenant_id=? and call_id=?",
                                String.class,
                                tenant,
                                call));
            } finally {
                release.countDown();
                worker.interrupt();
                worker.join(800);
            }
        }
    }

    /** Optional observation only: never changes a judgment, cancels work or extends a wait. */
    static final class InterruptionDiagnostic {
        final boolean enabled = Boolean.getBoolean("ex27.algorithmInterruptDiagnostic");
        final boolean afterReturnOnly =
                enabled && Boolean.getBoolean("ex27.algorithmInterruptAfterReturnOnly");
        final String stage, tenant;
        final long call, origin = System.nanoTime();
        final LatchedS3 peer;
        final AtomicReference<ArtifactStore> scoped;
        final AtomicReference<Throwable> failure;
        final AtomicBoolean interrupted;
        final AtomicInteger closedInput;
        final Thread worker;
        final List<Map<String, Object>> samples = new ArrayList<>();
        long interruptNanos, firstCheckNanos;
        Long joinReturnMs;
        Boolean quiescentAtFirstCheck;
        boolean beforeReleaseCaptured, originalBodyCompleted;

        InterruptionDiagnostic(
                String stage,
                String tenant,
                long call,
                LatchedS3 peer,
                AtomicReference<ArtifactStore> scoped,
                AtomicReference<Throwable> failure,
                AtomicBoolean interrupted,
                AtomicInteger closedInput,
                Thread worker) {
            this.stage = stage;
            this.tenant = tenant;
            this.call = call;
            this.peer = peer;
            this.scoped = scoped;
            this.failure = failure;
            this.interrupted = interrupted;
            this.closedInput = closedInput;
            this.worker = worker;
        }

        void firstCheck(boolean result) {
            if (!enabled) return;
            firstCheckNanos = System.nanoTime();
            quiescentAtFirstCheck = result;
        }

        void beforeRelease() {
            if (!enabled || beforeReleaseCaptured) return;
            beforeReleaseCaptured = true;
            capture("before-peer-release", true);
        }

        void capture(String phase, boolean readPg) {
            if (!enabled || samples.size() >= 4) return;
            var sample = new LinkedHashMap<String, Object>();
            sample.put("phase", phase);
            sample.put("observedSinceOriginNanos", System.nanoTime() - origin);
            sample.put("workerAlive", worker.isAlive());
            sample.put("workerInterrupted", worker.isInterrupted());
            sample.put("workerFinallyInterrupted", interrupted.get());
            sample.put("inputClosed", closedInput.get());
            sample.put("routes", peer.routes.size());
            sample.put("deletes", peer.deletes.get());
            sample.put("peerEnteredRemaining", peer.entered.getCount());
            sample.put("peerReleaseRemaining", peer.release.getCount());
            sample.put("peerFinishedRemaining", peer.finished.getCount());
            try {
                sample.put("failureGraph", failureGraph(failure.get()));
                if (phase.equals("peer-entered")) sample.put("workerStack", workerStack());
                var operation = scoped.get();
                if (operation != null) sample.put("lifecycle", lifecycle(operation));
                if (readPg) sample.put("pg", pgState());
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // An observation error is reported safely; it cannot replace the original assert.
                sample.put("observationErrorClass", safeClass(ex));
            }
            samples.add(sample);
        }

        Map<String, Object> lifecycle(ArtifactStore operation)
                throws ReflectiveOperationException {
            Object io = field(operation, "algorithmIo");
            var result = new LinkedHashMap<String, Object>();
            // This original monitor protects only field reads. No SDK/PG/output while held.
            synchronized (io) {
                result.put("ioObservedSinceOriginNanos", System.nanoTime() - origin);
                result.put("stages", field(io, "stages"));
                result.put("calls", ((Set<?>) field(io, "calls")).size());
                result.put("stopping", field(io, "stopping"));
                result.put("interrupted", field(io, "interrupted"));
                result.put("stopNotConfirmed", field(io, "stopNotConfirmed"));
                long until = (long) field(io, "cancelUntil");
                result.put("cancelUntilSet", until != 0);
                if (until != 0 && interruptNanos != 0)
                    result.put("cancelUntilSinceInterruptNanos", until - interruptNanos);
            }
            if (peer.entered.getCount() != 0) {
                result.put("executorRead", "OMITTED_NO_PEER_ENQUEUE_WITNESS");
                return result;
            }
            // The actual peer latch proves enqueue already created this owned executor. These
            // getters do not cancel/close it; executor and I/O samples are deliberately separate.
            var transport = (okhttp3.OkHttpClient) field(operation, "transport");
            var executor = transport.dispatcher().executorService();
            result.put("executorObservedSinceOriginNanos", System.nanoTime() - origin);
            result.put("executorShutdown", executor.isShutdown());
            result.put("executorTerminated", executor.isTerminated());
            if (executor instanceof ThreadPoolExecutor pool) {
                result.put("executorActive", pool.getActiveCount());
                result.put("executorQueued", pool.getQueue().size());
            }
            result.put("quiescentAtSample", operation.algorithmIoQuiescent());
            return result;
        }

        static Object field(Object target, String name) throws ReflectiveOperationException {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        Map<String, Object> workerStack() {
            var result = new LinkedHashMap<String, Object>();
            var frames = new ArrayList<String>();
            boolean bodyRead = false, futureWait = false;
            var stack = worker.getStackTrace();
            for (int i = 0; i < Math.min(stack.length, 64); i++) {
                var frame = stack[i];
                String type = frame.getClassName(), method = frame.getMethodName();
                bodyRead |= type.equals(ArtifactStore.class.getName()) && method.equals("readJson");
                futureWait |=
                        type.equals(ArtifactStore.class.getName() + "$AlgorithmIo")
                                && method.equals("await");
                if (frames.size() < 16
                        && (type.startsWith("cn.agentplatform.skill.infra.")
                                || type.startsWith("io.minio.")
                                || type.startsWith("okhttp3.")
                                || type.startsWith("okio.")
                                || type.startsWith("com.fasterxml.jackson.")
                                || type.equals("java.util.concurrent.CompletableFuture")))
                    frames.add(type + "#" + method + ":" + frame.getLineNumber());
            }
            result.put("frames", frames);
            result.put("frameScanLimit", 64);
            result.put("frameOutputLimit", 16);
            result.put("frameScanTruncated", stack.length > 64);
            result.put("bodyReadFrameObserved", bodyRead);
            result.put("algorithmAwaitFrameObserved", futureWait);
            return result;
        }

        Map<String, Object> pgState() {
            // One own tenant/call aggregate row, read only, with a separate statement timeout.
            // Connection acquisition uses the original fixture DS; runtime admission/outer test
            // bound remain required. This neither provisions a DB nor changes the shared template.
            var jdbc = new JdbcTemplate(Objects.requireNonNull(database.db.getDataSource()));
            jdbc.setQueryTimeout(1);
            jdbc.setMaxRows(1);
            var row =
                    jdbc.queryForMap(
                            "select count(*) as rows, count(*) filter (where state='READY') as ready,"
                                    + " max(state) as state, max(last_error_code) as error from"
                                    + " skill.artifact where tenant_id=? and call_id=?",
                            tenant,
                            call);
            return Map.of(
                    "rows",
                    row.get("rows"),
                    "ready",
                    row.get("ready"),
                    "state",
                    fixedValue(row.get("state"), Set.of("UPLOADING", "CLEANUP_PENDING", "READY")),
                    "error",
                    fixedValue(
                            row.get("error"),
                            Set.of("ALGORITHM_IO_STOP_NOT_CONFIRMED", "OFFLOAD_FAILED")));
        }

        static String fixedValue(Object value, Set<String> allowed) {
            return value == null ? "NULL" : allowed.contains(value) ? (String) value : "OTHER";
        }

        record FailureNode(Throwable value, String edge, int parent) {}

        static List<Map<String, Object>> failureGraph(Throwable failure) {
            var result = new ArrayList<Map<String, Object>>();
            var pending = new ArrayDeque<FailureNode>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            if (failure != null) pending.add(new FailureNode(failure, "root", -1));
            while (!pending.isEmpty() && result.size() < 16) {
                var node = pending.remove();
                var value = node.value();
                if (!seen.add(value)) continue;
                int index = result.size();
                var codes = new ArrayList<String>();
                String message = value.getMessage();
                for (String code :
                        List.of(
                                "ALGORITHM_IO_STOP_NOT_CONFIRMED",
                                "OUTPUT_ARTIFACT_UNAVAILABLE",
                                "ARTIFACT_STATE_CHANGED"))
                    if (message != null && (message.equals(code) || message.startsWith(code + ":")))
                        codes.add(code);
                result.add(
                        Map.of(
                                "parent", node.parent(),
                                "edge", node.edge(),
                                "class", safeClass(value),
                                "codes", codes));
                if (value.getCause() != null && result.size() + pending.size() < 16)
                    pending.add(new FailureNode(value.getCause(), "cause", index));
                var suppressed = value.getSuppressed();
                for (int i = 0;
                        i < Math.min(suppressed.length, 16) && result.size() + pending.size() < 16;
                        i++)
                    pending.add(new FailureNode(suppressed[i], "suppressed", index));
            }
            return result;
        }

        static String safeClass(Throwable failure) {
            String name = failure.getClass().getName();
            for (String prefix :
                    List.of(
                            "java.lang.",
                            "java.io.",
                            "java.net.",
                            "java.nio.channels.",
                            "java.util.concurrent.",
                            "io.minio.errors.",
                            "com.fasterxml.jackson.",
                            "org.springframework.dao."))
                if (name.startsWith(prefix)) return name;
            return name.equals("cn.agentplatform.common.PlatformException")
                    ? name
                    : "OTHER_THROWABLE";
        }

        void emit() {
            if (!enabled) return;
            var result = new LinkedHashMap<String, Object>();
            result.put("stage", stage);
            result.put(
                    "diagnosticMode", afterReturnOnly ? "AFTER_RETURN_ONLY" : "WITH_PEER_ENTERED_SAMPLE");
            result.put("peerEnteredCaptureOmitted", afterReturnOnly);
            result.put("clientStageAtInterrupt", "UNKNOWN");
            result.put("joinReturnMs", joinReturnMs);
            result.put("quiescentAtFirstCheck", quiescentAtFirstCheck);
            if (firstCheckNanos != 0 && interruptNanos != 0)
                result.put("firstCheckSinceInterruptNanos", firstCheckNanos - interruptNanos);
            result.put("originalBodyCompleted", originalBodyCompleted);
            result.put("samples", samples);
            try {
                System.out.println("ALGORITHM_INTERRUPT_DIAGNOSTIC " + Json.write(result));
            } catch (RuntimeException ex) {
                System.out.println("ALGORITHM_INTERRUPT_DIAGNOSTIC_EMIT_ERROR " + safeClass(ex));
            }
        }
    }

    /**
     * A distinct finite latch peer: does not infer local I/O state from server handler activity.
     */
    static final class LatchedS3 implements AutoCloseable {
        static final String BUCKET = "algorithm-interrupt-test";
        final HttpServer server;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<String> routes = new CopyOnWriteArrayList<>();
        final AtomicInteger deletes = new AtomicInteger();
        volatile String stage = "";

        LatchedS3() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            server.start();
        }

        Map<String, String> configuration() {
            return Map.of(
                    "AGENT_MINIO_ENDPOINT",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "AGENT_MINIO_BUCKET",
                    BUCKET,
                    "AGENT_MINIO_ACCESS_KEY",
                    "fixture-access",
                    "AGENT_MINIO_SECRET_KEY",
                    "fixture-only-secret");
        }

        void handle(HttpExchange x) throws IOException {
            boolean held = false;
            try {
                String method = x.getRequestMethod(),
                        path = x.getRequestURI().getPath(),
                        query = Objects.toString(x.getRequestURI().getRawQuery(), "");
                routes.add(method + " " + path + "?" + query);
                if (routes.size() > 32) {
                    reply(x, 429, "{}");
                    return;
                }
                if (method.equals("GET") && path.equals("/" + BUCKET) && query.equals("location="))
                    reply(
                            x,
                            200,
                            "<LocationConstraint"
                                + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>");
                else if (method.equals("GET")
                        && path.equals("/" + BUCKET)
                        && query.contains("uploads=")
                        && query.contains("max-uploads=8"))
                    reply(
                            x,
                            200,
                            "<ListMultipartUploadsResult><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>");
                else if (path.matches(
                                "/" + BUCKET + "/[a-f0-9]{64}/artifacts/art_[a-f0-9]{32}\\.json")
                        && query.isEmpty()) {
                    if (method.equals("PUT")) {
                        byte[] bytes = x.getRequestBody().readNBytes(4097);
                        if (bytes.length > 4096) throw new IOException("finite upload");
                        if (stage.equals("PUT")) {
                            held = true;
                            entered.countDown();
                            if (!release.await(1500, TimeUnit.MILLISECONDS))
                                throw new IOException("latch deadline");
                        }
                        objects.put(path, bytes);
                        x.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                        reply(x, 200, "");
                    } else if (method.equals("GET")) {
                        if (stage.equals("GET")) {
                            held = true;
                            entered.countDown();
                            if (!release.await(1500, TimeUnit.MILLISECONDS))
                                throw new IOException("latch deadline");
                        }
                        byte[] bytes = objects.get(path);
                        if (bytes == null) {
                            reply(x, 404, "");
                            return;
                        }
                        x.getResponseHeaders().set("Content-Type", "application/json");
                        x.sendResponseHeaders(200, bytes.length);
                        if (stage.equals("GET_BODY")) {
                            x.getResponseBody().write(bytes, 0, 16);
                            x.getResponseBody().flush();
                            held = true;
                            entered.countDown();
                            if (!release.await(1500, TimeUnit.MILLISECONDS))
                                throw new IOException("latch deadline");
                            x.getResponseBody().write(bytes, 16, bytes.length - 16);
                        } else x.getResponseBody().write(bytes);
                    } else if (method.equals("DELETE")) {
                        deletes.incrementAndGet();
                        objects.remove(path);
                        x.sendResponseHeaders(204, -1);
                    } else reply(x, 400, "");
                } else reply(x, 400, "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                x.close();
                if (held) finished.countDown();
            }
        }

        void reply(HttpExchange x, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/xml");
            x.sendResponseHeaders(code, bytes.length);
            x.getResponseBody().write(bytes);
        }

        public void close() {
            release.countDown();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
