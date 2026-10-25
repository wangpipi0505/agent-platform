package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaDeadline;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Fixed SDK 0.10.0 transport extension point, not a replacement MCP session. Its stock
 * FlowSseClient assembles unbounded lines/events and does not close the HTTP client. Count raw
 * UTF-8 bytes before decoding; dispatch one bounded event synchronously, without a queue. JSON-RPC
 * IDs, negotiation, request matching and tool conversion stay in the SDK.
 */
final class BoundedMcpSseTransport implements McpClientTransport, AutoCloseable {
    static final int EVENT_BYTES = 4 * 1024 * 1024,
            TOTAL_BYTES = 16 * 1024 * 1024,
            MAX_EVENTS = 256;
    static final int REQUEST_BYTES = 128 * 1024,
            SCHEMA_BYTES = 64 * 1024,
            CATALOG_BYTES = 1024 * 1024;
    private final URI endpoint;
    private final String authorization;
    private final long deadline;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<URI> discovered = new CompletableFuture<>();
    private final LinkedHashMap<String, Map<String, Object>> tools = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private int activeSends;
    private volatile InputStream stream;
    private volatile Thread reader;
    private volatile URI messages;
    private volatile MonoSink<Void> connection;
    private volatile SkillAdapters.Failure failure;
    private volatile boolean callSent;
    private int catalogBytes;

    BoundedMcpSseTransport(String endpoint, String authorization, long deadline) {
        this.endpoint = SkillRules.endpoint(endpoint);
        this.authorization = authorization;
        this.deadline = deadline;
        http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        mapper = Json.mapper().copy();
        mapper.getFactory()
                .setStreamReadConstraints(
                        com.fasterxml.jackson.core.StreamReadConstraints.builder()
                                .maxDocumentLength(EVENT_BYTES)
                                .maxStringLength(EVENT_BYTES)
                                .maxNameLength(1024)
                                .maxNestingDepth(64)
                                .maxTokenCount(100000)
                                .maxNumberLength(1000)
                                .build());
        mapper.enable(
                com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION
                        .mappedFeature());
        mapper.enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Duration remaining() {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SkillAdapters.Failure("RETRYABLE_FAILURE", false, "MCP会话总时限已到");
        return Duration.ofNanos(nanos);
    }

    SkillAdapters.Failure failure() {
        return failure;
    }

    boolean callSent() {
        return callSent;
    }

    synchronized List<Map<String, Object>> tools() {
        return List.copyOf(tools.values());
    }

    private HttpRequest.Builder request(URI uri) {
        var builder = HttpRequest.newBuilder(uri).timeout(remaining());
        if (authorization != null) builder.header("Authorization", authorization);
        return builder;
    }

    @Override
    public Mono<Void> connect(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.create(
                sink -> {
                    synchronized (lifecycle) {
                        if (closed.get()) {
                            sink.success();
                            return;
                        }
                        if (reader != null) {
                            sink.error(
                                    new IllegalStateException("MCP transport already connected"));
                            return;
                        }
                        connection = sink;
                        reader =
                                Thread.ofVirtual()
                                        .name("mcp-sse-reader")
                                        .start(
                                                () -> {
                                                    try {
                                                        var future =
                                                                http.sendAsync(
                                                                        request(endpoint)
                                                                                .header(
                                                                                        "Accept",
                                                                                        "text/event-stream")
                                                                                .GET()
                                                                                .build(),
                                                                        HttpResponse.BodyHandlers
                                                                                .ofInputStream());
                                                        requests.add(future);
                                                        HttpResponse<InputStream> response;
                                                        try {
                                                            response =
                                                                    future.get(
                                                                            remaining().toMillis()
                                                                                    + 1,
                                                                            TimeUnit.MILLISECONDS);
                                                        } finally {
                                                            requests.remove(future);
                                                        }
                                                        stream = response.body();
                                                        checkStatus(response.statusCode());
                                                        if (!response.headers()
                                                                .firstValue("Content-Type")
                                                                .orElse("")
                                                                .toLowerCase(Locale.ROOT)
                                                                .startsWith("text/event-stream"))
                                                            throw invalid("MCP端点没有返回SSE");
                                                        receive(handler);
                                                        if (!closed.get())
                                                            throw new EOFException(
                                                                    "MCP SSE closed");
                                                    } catch (Exception ex) {
                                                        if (!closed.get()) fail(ex);
                                                    } finally {
                                                        try {
                                                            if (stream != null) stream.close();
                                                        } catch (IOException ignored) {
                                                        }
                                                    }
                                                });
                    }
                });
    }

    private void receive(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler)
            throws Exception {
        var line = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream();
        String type = "message";
        byte[] buffer = new byte[8192];
        long total = 0;
        int events = 0;
        boolean previousCr = false, firstLine = true;
        for (int n; (n = stream.read(buffer)) != -1; ) {
            remaining();
            if ((total += n) > TOTAL_BYTES) throw invalid("MCP会话超过总接收字节上限");
            for (int i = 0; i < n; i++) {
                int b = buffer[i] & 255;
                // SSE permits CR, LF and CRLF. A CR at a network-buffer boundary
                // still consumes exactly one following LF, never another event boundary.
                if (previousCr && b == '\n') {
                    previousCr = false;
                    continue;
                }
                previousCr = b == '\r';
                if (b != '\n' && b != '\r') {
                    if (line.size() >= EVENT_BYTES) throw invalid("MCP单行超过接收上限");
                    line.write(b);
                    continue;
                }
                byte[] bytes = line.toByteArray();
                line.reset();
                if (firstLine) {
                    firstLine = false;
                    if (bytes.length >= 3
                            && bytes[0] == (byte) 0xef
                            && bytes[1] == (byte) 0xbb
                            && bytes[2] == (byte) 0xbf)
                        bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
                }
                int length = bytes.length;
                if (length == 0) {
                    if (data.size() > 0) {
                        if (++events > MAX_EVENTS) throw invalid("MCP会话事件数超过上限");
                        String value = decode(data.toByteArray(), data.size());
                        data.reset();
                        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
                        if (type.equals("endpoint")) {
                            if (messages != null) throw invalid("MCP重复声明发送端点");
                            URI resolved = endpoint.resolve(value);
                            SkillRules.endpoint(resolved.toString());
                            if (!Objects.equals(resolved.getScheme(), endpoint.getScheme())
                                    || !Objects.equals(resolved.getHost(), endpoint.getHost())
                                    || port(resolved) != port(endpoint))
                                throw invalid("MCP发送端点必须与SSE同源");
                            messages = resolved;
                            discovered.complete(resolved);
                            connection.success();
                        } else if (type.equals("message")) {
                            var message = McpSchema.deserializeJsonRpcMessage(mapper, value);
                            // Client advertises no sampling/roots; do not enqueue unsolicited
                            // requests or list-change callbacks that create unbounded SDK work.
                            if (message instanceof McpSchema.JSONRPCResponse)
                                handler.apply(Mono.just(message)).block(remaining());
                            else if (!(message instanceof McpSchema.JSONRPCNotification))
                                throw invalid("此MCP工具客户端不接收服务器主动请求");
                        } else throw invalid("MCP SSE事件类型不受支持");
                    }
                    type = "message";
                } else {
                    String field = decode(bytes, length);
                    if (field.startsWith("event:")) {
                        type = field.substring(6).stripLeading();
                        if (type.length() > 64) throw invalid("MCP事件类型过长");
                    } else if (field.startsWith("data:")) {
                        int start = 5;
                        if (length > 5 && bytes[5] == ' ') start++;
                        if ((long) data.size() + length - start + 1 > EVENT_BYTES)
                            throw invalid("MCP单事件超过接收上限");
                        data.write(bytes, start, length - start);
                        data.write('\n');
                    }
                    // SSE extension fields, id/retry and comments are ignored. Their
                    // raw bytes still count toward line/session limits; no event queue.
                }
            }
        }
        // A partial event is never accepted as a complete authoritative result.
    }

    private static int port(URI uri) {
        return uri.getPort() < 0 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
    }

    private static String decode(byte[] bytes, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString();
    }

    private static SkillAdapters.Failure invalid(String message) {
        return new SkillAdapters.Failure("OUTPUT_INVALID", false, message);
    }

    private static void checkStatus(int status) {
        if (status >= 200 && status < 300) return;
        throw new SkillAdapters.Failure(
                status == 403 || status == 401
                        ? "NO_PERMISSION"
                        : status == 429
                                ? "QUOTA"
                                : status >= 400 && status < 500
                                        ? "PARAM_ERROR"
                                        : "RETRYABLE_FAILURE",
                false,
                "MCP HTTP拒绝请求: " + status);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(
                () -> {
                    synchronized (lifecycle) {
                        if (closed.get()) throw new IllegalStateException("MCP transport closed");
                        if (failure != null) throw failure;
                        activeSends++;
                    }
                    try {
                        URI target =
                                discovered.get(remaining().toMillis() + 1, TimeUnit.MILLISECONDS);
                        var encoded = new ByteArrayOutputStream();
                        mapper.writeValue(
                                new FilterOutputStream(encoded) {
                                    public void write(int value) throws IOException {
                                        if (encoded.size() >= REQUEST_BYTES)
                                            throw new SkillAdapters.Failure(
                                                    "PARAM_ERROR", false, "MCP请求超过128KiB");
                                        out.write(value);
                                    }

                                    public void write(byte[] values, int offset, int length)
                                            throws IOException {
                                        if ((long) encoded.size() + length > REQUEST_BYTES)
                                            throw new SkillAdapters.Failure(
                                                    "PARAM_ERROR", false, "MCP请求超过128KiB");
                                        out.write(values, offset, length);
                                    }
                                },
                                message);
                        byte[] body = encoded.toByteArray();
                        boolean business =
                                message instanceof McpSchema.JSONRPCRequest r
                                        && r.method().equals("tools/call");
                        // Prevent HTTP transport recovery from replaying any POST body. One SDK
                        // send has one subscription; a repeated body subscription fails closed.
                        var subscribed = new AtomicBoolean();
                        HttpRequest.BodyPublisher publisher =
                                new HttpRequest.BodyPublisher() {
                                    public long contentLength() {
                                        return body.length;
                                    }

                                    public void subscribe(
                                            Flow.Subscriber<? super ByteBuffer> subscriber) {
                                        if (!subscribed.compareAndSet(false, true)) {
                                            subscriber.onSubscribe(
                                                    new Flow.Subscription() {
                                                        public void request(long n) {}

                                                        public void cancel() {}
                                                    });
                                            subscriber.onError(
                                                    new IOException("MCP POST replay denied"));
                                            return;
                                        }
                                        HttpRequest.BodyPublishers.ofByteArray(body)
                                                .subscribe(subscriber);
                                    }
                                };
                        HttpRequest outbound =
                                request(target)
                                        .header("Content-Type", "application/json")
                                        .POST(publisher)
                                        .build();
                        if (business) callSent = true;
                        var future =
                                http.sendAsync(
                                        outbound,
                                        info -> {
                                            checkStatus(info.statusCode());
                                            return ackSubscriber();
                                        });
                        requests.add(future);
                        try {
                            var response =
                                    future.get(remaining().toMillis() + 1, TimeUnit.MILLISECONDS);
                            checkStatus(response.statusCode());
                        } finally {
                            requests.remove(future);
                        }
                    } catch (Exception ex) {
                        fail(ex);
                        throw failure;
                    } finally {
                        synchronized (lifecycle) {
                            activeSends--;
                            lifecycle.notifyAll();
                        }
                    }
                });
    }

    private synchronized void fail(Exception ex) {
        while (ex instanceof ExecutionException && ex.getCause() instanceof Exception cause)
            ex = cause;
        if (failure == null)
            failure =
                    ex instanceof SkillAdapters.Failure f
                            ? f
                            : new SkillAdapters.Failure(
                                    "RETRYABLE_FAILURE", false, "MCP连接、解码或总时限失败");
        discovered.completeExceptionally(failure);
        // SDK 0.10 subscribes connect() without an error consumer. The discovery
        // future/current RPC carries this stored failure; complete that lifecycle
        // subscription without an unconsumed Reactor global error or a global hook.
        var sink = connection;
        if (sink != null) sink.success();
    }

    /** Bounded ACK discard: no byte aggregation, one reactive buffer batch at a time. */
    private static HttpResponse.BodySubscriber<Void> ackSubscriber() {
        return new HttpResponse.BodySubscriber<>() {
            final CompletableFuture<Void> body = new CompletableFuture<>();
            Flow.Subscription subscription;
            long bytes;

            public CompletionStage<Void> getBody() {
                return body;
            }

            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            public void onNext(List<ByteBuffer> buffers) {
                for (var buffer : buffers) bytes += buffer.remaining();
                if (bytes > 8192) {
                    subscription.cancel();
                    body.completeExceptionally(invalid("MCP提交确认超过8KiB"));
                } else subscription.request(1);
            }

            public void onError(Throwable error) {
                body.completeExceptionally(error);
            }

            public void onComplete() {
                body.complete(null);
            }
        };
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        try (var ignored = SchemaDeadline.until(deadline)) {
            SchemaDeadline.check();
            if (typeRef.getType().equals(McpSchema.InitializeResult.class)
                    && !McpSchema.LATEST_PROTOCOL_VERSION.equals(
                            Json.tree(data).path("protocolVersion").asText()))
                throw new SkillAdapters.Failure("PARAM_ERROR", false, "固定MCP SDK仅支持2024-11-05协议");
            if (typeRef.getType().equals(McpSchema.ListToolsResult.class)) captureTools(data);
            T converted = mapper.convertValue(data, typeRef);
            SchemaDeadline.check();
            return converted;
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void captureTools(Object raw) {
        var json = Json.tree(raw);
        if (!json.path("tools").isArray() || json.path("tools").size() > 100)
            throw invalid("MCP工具列表无效或超过100项");
        for (var tool : json.path("tools")) {
            String name = tool.path("name").asText();
            if (!name.matches("[A-Za-z0-9_.-]{1,128}") || tools.containsKey(name))
                throw invalid("MCP工具名称无效或重复");
            var schema = tool.path("inputSchema");
            if (!schema.isObject() || !schema.path("type").asText().equals("object"))
                throw invalid("MCP工具输入Schema必须为object");
            byte[] schemaBytes = Json.write(schema).getBytes(StandardCharsets.UTF_8);
            if (schemaBytes.length > SCHEMA_BYTES) throw invalid("MCP工具Schema超过64KiB");
            Map<String, Object> schemaMap = mapper.convertValue(schema, Map.class);
            try {
                SkillRules.mcpSchema(schemaMap);
            } catch (Exception ex) {
                throw invalid("MCP工具Schema不可安全解析");
            }
            String description = tool.path("description").asText("");
            if (description.length() > 2048) throw invalid("MCP工具描述超过2048字符");
            catalogBytes += schemaBytes.length + description.length() * 3 + name.length();
            if (catalogBytes > CATALOG_BYTES || tools.size() >= 100) throw invalid("MCP工具目录超过接收上限");
            tools.put(
                    name,
                    Map.of("name", name, "description", description, "inputSchema", schemaMap));
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.defer(
                () -> {
                    close();
                    return Mono.fromFuture(terminated, true);
                });
    }

    void whenTerminated(Runnable release) {
        terminated.thenRun(release);
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed.compareAndSet(false, true)) {
                // A maximum of two session-owned cleanup watchers can exist: their
                // permits remain occupied until real reader/HTTP/send termination.
                Thread.ofVirtual().name("mcp-session-cleanup").start(this::finishClose);
            }
        }
        // Bound API cleanup latency. Timeout does not imply termination or release.
        try {
            terminated.get(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException ignored) {
        }
    }

    private void finishClose() {
        discovered.cancel(true);
        requests.forEach(future -> future.cancel(true));
        http.shutdownNow();
        try {
            if (stream != null) stream.close();
        } catch (IOException ignored) {
        }
        Thread active = reader;
        if (active != null) active.interrupt();
        // This watcher owns the occupied permit. Interrupted/slow cleanup never
        // releases it early and does not block the caller or create replacement work.
        boolean interrupted = false;
        for (; ; )
            try {
                if (active != null) active.join();
                while (!http.awaitTermination(Duration.ofMillis(250))) {}
                synchronized (lifecycle) {
                    while (activeSends != 0) lifecycle.wait();
                }
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        requests.clear();
        terminated.complete(null);
        if (interrupted) Thread.currentThread().interrupt();
    }
}
