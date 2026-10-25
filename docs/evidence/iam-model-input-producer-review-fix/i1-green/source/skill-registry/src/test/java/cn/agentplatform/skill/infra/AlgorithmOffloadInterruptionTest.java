package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
            worker.start();
            try {
                assertTrue(
                        peer.entered.await(1, TimeUnit.SECONDS),
                        "actual SDK request reached " + stage);
                long start = System.nanoTime();
                worker.interrupt();
                worker.join(1000);
                assertFalse(worker.isAlive(), "bounded local cancellation return");
                long returnMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertNotNull(failure.get(), "interruption cannot become READY success");
                assertTrue(
                        scoped.get().algorithmIoQuiescent(),
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
            } finally {
                peer.release.countDown();
                worker.interrupt();
                worker.join(1000);
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
