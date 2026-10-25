package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/** Finite localhost wire peer; it never runs a container or accepts a delete/watch/proxy route. */
public final class AlgorithmApiFixture implements AutoCloseable {
    final HttpServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public final AtomicInteger posts = new AtomicInteger(),
            gets = new AtomicInteger(),
            logs = new AtomicInteger(),
            podLists = new AtomicInteger();
    public volatile Map<String, Object> job;
    public volatile String uid = "fixture-job-" + UUID.randomUUID(),
            condition = "Complete",
            ownerUid = uid;
    public volatile String expectedBearer = "fixture-bearer";
    public final AtomicInteger authRejected = new AtomicInteger();
    public volatile String stdout = "{\"value\":\"ok\"}";
    public volatile boolean dropPost,
            conflictPost,
            missing,
            chunked,
            wrongNamespace,
            contradictory,
            gzip;
    public volatile int failedCount,
            podCount = 1,
            metadataPadding,
            errorCode,
            delayMs,
            fragmentDelayMs;
    public volatile String continuation = "",
            containerName = "algorithm",
            ownerKind = "Job",
            ownerVersion = "batch/v1";
    public volatile boolean controller = true;
    public volatile int exitCode;
    public volatile Consumer<Map<String, Object>> beforePost = body -> {};
    public volatile Runnable beforeGet = () -> {}, beforeLog = () -> {};
    public final AtomicInteger active = new AtomicInteger(), closed = new AtomicInteger();
    final java.util.concurrent.atomic.AtomicReference<Throwable> backgroundFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

    public void assertHealthy() {
        var failure = backgroundFailure.get();
        if (failure != null)
            throw new AssertionError(
                    "Algorithm fixture background failure: " + failure.getClass().getSimpleName());
    }

    public final List<String> routes = new CopyOnWriteArrayList<>();

    public AlgorithmApiFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void handle(HttpExchange x) throws IOException {
        active.incrementAndGet();
        try {
            if (!Objects.equals(
                    "Bearer " + expectedBearer, x.getRequestHeaders().getFirst("Authorization"))) {
                authRejected.incrementAndGet();
                send(x, 401, "{}", false);
                return;
            }
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            routes.add(
                    method
                            + " "
                            + path
                            + "?"
                            + Objects.toString(x.getRequestURI().getRawQuery(), ""));
            if (routes.size() > 200) {
                send(x, 429, "{}", false);
                return;
            }
            if (delayMs > 0) Thread.sleep(Math.min(delayMs, 500));
            if (method.equals("POST") && path.equals("/apis/batch/v1/namespaces/alg-test/jobs")) {
                posts.incrementAndGet();
                var body =
                        Json.read(
                                new String(
                                        x.getRequestBody().readNBytes(65537),
                                        StandardCharsets.UTF_8),
                                Map.class);
                beforePost.accept(body);
                job = body;
                if (dropPost) {
                    x.close();
                    return;
                }
                send(
                        x,
                        conflictPost ? 409 : 201,
                        conflictPost ? "{}" : Json.write(observed()),
                        chunked);
            } else if (method.equals("GET")
                    && path.matches("/apis/batch/v1/namespaces/alg-test/jobs/alg-[a-f0-9]{40}")) {
                gets.incrementAndGet();
                beforeGet.run();
                if (errorCode > 0)
                    send(
                            x,
                            errorCode,
                            Json.write(
                                    Map.of("message", "x".repeat(Math.min(metadataPadding, 4096)))),
                            chunked);
                else if (job == null || missing) send(x, 404, "{}", chunked);
                else send(x, 200, Json.write(observed()), chunked);
            } else if (method.equals("GET") && path.equals("/api/v1/namespaces/alg-test/pods")) {
                podLists.incrementAndGet();
                var items = new ArrayList<>();
                for (int i = 0; i < Math.min(podCount, 3); i++)
                    items.add(
                            Map.of(
                                    "metadata",
                                    Map.of(
                                            "name",
                                            "pod-" + i,
                                            "namespace",
                                            "alg-test",
                                            "uid",
                                            "pod-uid-" + i,
                                            "ownerReferences",
                                            List.of(
                                                    Map.of(
                                                            "apiVersion",
                                                            ownerVersion,
                                                            "kind",
                                                            ownerKind,
                                                            "name",
                                                            object(job.get("metadata")).get("name"),
                                                            "uid",
                                                            ownerUid,
                                                            "controller",
                                                            controller))),
                                    "status",
                                    Map.of(
                                            "containerStatuses",
                                            List.of(
                                                    Map.of(
                                                            "name",
                                                            containerName,
                                                            "image",
                                                            "fixture/algorithm:fixed",
                                                            "imageID",
                                                            "sha256:fixture",
                                                            "ready",
                                                            false,
                                                            "restartCount",
                                                            0,
                                                            "state",
                                                            Map.of(
                                                                    "terminated",
                                                                    Map.of(
                                                                            "exitCode",
                                                                            exitCode)))))));
                send(
                        x,
                        200,
                        Json.write(
                                Map.of(
                                        "apiVersion",
                                        "v1",
                                        "kind",
                                        "PodList",
                                        "metadata",
                                        Map.of("continue", continuation),
                                        "items",
                                        items)),
                        chunked);
            } else if (method.equals("GET")
                    && path.matches("/api/v1/namespaces/alg-test/pods/pod-[0-2]/log")) {
                logs.incrementAndGet();
                beforeLog.run();
                send(x, 200, stdout, chunked);
            } else send(x, 400, "{\"message\":\"route denied\"}", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | AssertionError e) {
            backgroundFailure.compareAndSet(null, e);
            send(x, 500, "{\"message\":\"fixture assertion failed\"}", false);
        } finally {
            x.close();
            active.decrementAndGet();
            closed.incrementAndGet();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    public Map<String, Object> observed() {
        var result = new LinkedHashMap<>(job);
        var metadata = new LinkedHashMap<>(object(job.get("metadata")));
        metadata.put("namespace", wrongNamespace ? "foreign" : "alg-test");
        metadata.put("uid", uid);
        if (metadataPadding > 0) {
            var annotations = new LinkedHashMap<String, Object>();
            if (metadata.get("annotations") instanceof Map<?, ?> existing)
                existing.forEach((k, v) -> annotations.put(k.toString(), v));
            annotations.put("fixture/padding", "x".repeat(Math.min(metadataPadding, 4096)));
            metadata.put("annotations", annotations);
        }
        result.put("metadata", metadata);
        var conditions = new ArrayList<>();
        if (!condition.isEmpty()) conditions.add(Map.of("type", condition, "status", "True"));
        if (contradictory) conditions.add(Map.of("type", "Failed", "status", "True"));
        result.put(
                "status",
                Map.of(
                        "succeeded",
                        condition.equals("Complete") ? 1 : 0,
                        "failed",
                        failedCount,
                        "conditions",
                        conditions));
        return result;
    }

    void send(HttpExchange x, int code, String body, boolean chunks) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new IllegalArgumentException("finite fixture body");
        x.getResponseHeaders().set("Content-Type", "application/json");
        if (gzip) {
            var compressed = new ByteArrayOutputStream();
            try (var zipped = new java.util.zip.GZIPOutputStream(compressed)) {
                zipped.write(bytes);
            }
            bytes = compressed.toByteArray();
            x.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        x.sendResponseHeaders(code, chunks ? 0 : bytes.length);
        if (fragmentDelayMs > 0) {
            for (int i = 0; i < bytes.length; i += 16) {
                x.getResponseBody().write(bytes, i, Math.min(16, bytes.length - i));
                x.getResponseBody().flush();
                try {
                    Thread.sleep(Math.min(fragmentDelayMs, 50));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture interrupted", e);
                }
            }
        } else x.getResponseBody().write(bytes);
    }

    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
