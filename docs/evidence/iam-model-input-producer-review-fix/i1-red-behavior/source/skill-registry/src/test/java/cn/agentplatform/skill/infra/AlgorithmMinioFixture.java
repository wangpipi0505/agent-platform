package cn.agentplatform.skill.infra;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Finite S3 protocol peer for deadline tests. No real bucket or external MinIO is changed. */
final class AlgorithmMinioFixture implements AutoCloseable {
    static final String BUCKET = "algorithm-deadline-test";
    final HttpServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final ConcurrentMap<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger puts = new AtomicInteger(),
            reads = new AtomicInteger(),
            deletes = new AtomicInteger(),
            active = new AtomicInteger(),
            closed = new AtomicInteger(),
            disconnected = new AtomicInteger();
    final List<String> routes = new CopyOnWriteArrayList<>();
    volatile String slowStage = "";

    AlgorithmMinioFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    Map<String, String> configuration() {
        return Map.of(
                "AGENT_MINIO_ENDPOINT",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "AGENT_MINIO_ACCESS_KEY",
                "fixture-access",
                "AGENT_MINIO_SECRET_KEY",
                "fixture-secret-only",
                "AGENT_MINIO_BUCKET",
                BUCKET);
    }

    void handle(HttpExchange x) throws IOException {
        active.incrementAndGet();
        try {
            String method = x.getRequestMethod(),
                    path = x.getRequestURI().getPath(),
                    query = Objects.toString(x.getRequestURI().getRawQuery(), "");
            routes.add(method + " " + path + "?" + query);
            if (routes.size() > 40) {
                reply(x, 429, "{}");
                return;
            }
            if (method.equals("GET") && path.equals("/" + BUCKET) && query.equals("location=")) {
                reply(
                        x,
                        200,
                        "<LocationConstraint"
                            + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>");
            } else if (method.equals("GET")
                    && path.equals("/" + BUCKET)
                    && query.contains("uploads=")
                    && query.contains("max-uploads=8")) {
                reply(
                        x,
                        200,
                        "<ListMultipartUploadsResult><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>");
            } else if (path.matches(
                            "/" + BUCKET + "/[a-f0-9]{64}/artifacts/art_[a-f0-9]{32}\\.json")
                    && query.isEmpty()) {
                if (method.equals("PUT")) {
                    byte[] bytes = x.getRequestBody().readNBytes(4097);
                    if (bytes.length > 4096) throw new IOException("finite fixture upload");
                    puts.incrementAndGet();
                    objects.put(path, bytes);
                    if (slowStage.equals("UPLOAD_ACK")) Thread.sleep(500);
                    x.getResponseHeaders().set("ETag", "\"fixture-etag\"");
                    reply(x, 200, "");
                } else if (method.equals("GET")) {
                    reads.incrementAndGet();
                    byte[] body = objects.get(path);
                    if (body == null) {
                        reply(x, 404, "");
                        return;
                    }
                    x.getResponseHeaders().set("Content-Type", "application/json");
                    x.sendResponseHeaders(200, body.length);
                    if (slowStage.equals("READBACK")) {
                        for (int offset = 0; offset < body.length; offset += 16) {
                            x.getResponseBody()
                                    .write(body, offset, Math.min(16, body.length - offset));
                            x.getResponseBody().flush();
                            Thread.sleep(20);
                        }
                    } else x.getResponseBody().write(body);
                } else if (method.equals("DELETE")) {
                    deletes.incrementAndGet();
                    objects.remove(path);
                    x.sendResponseHeaders(204, -1);
                } else reply(x, 400, "");
            } else reply(x, 400, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            disconnected.incrementAndGet();
            throw e;
        } finally {
            x.close();
            active.decrementAndGet();
            closed.incrementAndGet();
        }
    }

    void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/xml");
        x.sendResponseHeaders(code, bytes.length);
        x.getResponseBody().write(bytes);
    }

    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
