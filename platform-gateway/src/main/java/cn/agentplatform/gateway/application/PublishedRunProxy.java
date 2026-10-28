package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayRunAuthorization;
import cn.agentplatform.gateway.infra.PublishedRunHttp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.context.request.async.WebAsyncUtils;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One finite JSON create response. Servlet callbacks only perform nonblocking I/O and submit owned
 * work.
 */
public final class PublishedRunProxy implements AutoCloseable {
    private static final int MAX = 2 * 1024 * 1024;
    private static final ObjectMapper JSON =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PublishedMetadataClient metadata;
    private final CurrentAuthorityClient authority;
    private final PublishedRunHttp http;
    private final URI engine;
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("gateway-published-", 0).factory());
    private final ScheduledExecutorService timeouts =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("gateway-published-timeout").factory());
    private final Set<Exchange> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger workerStarts = new AtomicInteger(),
            workerExits = new AtomicInteger(),
            handleInstalls = new AtomicInteger(),
            handleCancels = new AtomicInteger();

    public PublishedRunProxy(
            URI engine,
            PublishedMetadataClient metadata,
            CurrentAuthorityClient authority,
            PublishedRunHttp http) {
        this(engine, metadata, authority, http, false);
    }

    /** Explicit finite loopback assembly only, never inferred from a request or token. */
    public PublishedRunProxy(
            URI engine,
            PublishedMetadataClient metadata,
            CurrentAuthorityClient authority,
            PublishedRunHttp http,
            boolean isolated) {
        this.engine = PublishedRunHttp.origin(engine, isolated);
        this.metadata = Objects.requireNonNull(metadata);
        this.authority = Objects.requireNonNull(authority);
        this.http = Objects.requireNonNull(http);
    }

    public void start(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayIngressBoundary.PortalRequest source)
            throws IOException {
        if (closed.get()) throw unavailable();
        if (!request.isAsyncSupported() || request.isAsyncStarted()) throw unavailable();
        var mvc = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
        if (mvc == null) throw unavailable();
        var exchange = new Exchange(request, response, source);
        active.add(exchange);
        // MVC must observe this same async request as started when the void controller returns.
        try {
            mvc.addTimeoutHandler(exchange::servletTimeout);
            mvc.addErrorHandler(e -> exchange.terminate());
            mvc.addCompletionHandler(exchange::terminate);
            mvc.setTimeout(
                    Math.max(
                            1L,
                            TimeUnit.NANOSECONDS.toMillis(exchange.deadline - System.nanoTime())));
            mvc.startAsync();
            if (exchange.installAsync(request.getAsyncContext())) exchange.read();
        } catch (IOException | RuntimeException failure) {
            exchange.terminate();
            throw failure;
        }
    }

    private enum State {
        READING,
        UPSTREAM,
        WRITING,
        TERMINAL
    }

    private final class Exchange {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private GatewayIngressBoundary.PortalRequest source;
        private final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5000);
        private final PublishedRunHttp.Cancellation cancellation =
                new PublishedRunHttp.Cancellation();
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private State state = State.READING;
        private AsyncContext async;
        private FutureTask<Void> future;
        private ScheduledFuture<?> timeout;
        private volatile Thread worker;
        private GatewayRunAuthorization proof;
        private byte[] output;
        private int position;
        private boolean success,
                listenerInstalled,
                writing,
                cancellationRequested,
                cancellationStarted;
        private int servletCalls, responseStatus = 200;
        private final AtomicInteger observedInstalls = new AtomicInteger(),
                observedCancels = new AtomicInteger();

        Exchange(
                HttpServletRequest request,
                HttpServletResponse response,
                GatewayIngressBoundary.PortalRequest source) {
            this.request = request;
            this.response = response;
            this.source = Objects.requireNonNull(source);
        }

        private void live() {
            if (closed.get()
                    || state == State.TERMINAL
                    || deadline - System.nanoTime() <= 0
                    || cancellationRequested) throw unavailable();
        }

        // startAsync stays outside this monitor: close must not wait for external framework code.
        // Whichever side sees a late handle owns completing it, even if terminal already won.
        private boolean installAsync(AsyncContext actual) {
            boolean stopped;
            AsyncContext completion;
            synchronized (this) {
                async = actual;
                stopped = state == State.TERMINAL || closed.get();
                if (stopped && state != State.TERMINAL) responseStatus = 503;
                completion = takeCompletion();
            }
            completeAsync(completion);
            if (stopped) {
                terminate();
                return false;
            }
            var task =
                    timeouts.schedule(
                            () -> fail(503),
                            Math.max(1, deadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
            synchronized (this) {
                stopped = state == State.TERMINAL;
                if (!stopped) timeout = task;
            }
            if (stopped) task.cancel(false);
            return !stopped;
        }

        void read() throws IOException {
            if (!beginServlet(State.READING)) return;
            try {
                if (request.getContentLengthLong() > MAX) {
                    fail(413);
                    return;
                }
                if (request.getHeader("Content-Encoding") != null
                        || request.getContentType() == null
                        || !request.getContentType()
                                .matches(
                                        "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*UTF-8)?")) {
                    fail(415);
                    return;
                }
                var in = request.getInputStream();
                in.setReadListener(
                        new ReadListener() {
                            public void onDataAvailable() {
                                try {
                                    if (!beginServlet(State.READING)) return;
                                    try {
                                        byte[] b = new byte[8192];
                                        while (in.isReady() && !in.isFinished()) {
                                            synchronized (Exchange.this) {
                                                if (state != State.READING) return;
                                                live();
                                            }
                                            int n = in.read(b);
                                            if (n < 0) break;
                                            synchronized (Exchange.this) {
                                                if (state != State.READING) return;
                                                if (n > MAX - input.size())
                                                    throw new PlatformException(
                                                            413, "INVALID_JSON", "请求过大");
                                                input.write(b, 0, n);
                                            }
                                        }
                                    } finally {
                                        endServlet();
                                    }
                                } catch (PlatformException e) {
                                    fail(e.status());
                                } catch (Exception e) {
                                    fail(503);
                                }
                            }

                            public void onAllDataRead() {
                                submit();
                            }

                            public void onError(Throwable error) {
                                terminate();
                            }
                        });
            } finally {
                endServlet();
            }
        }

        private void submit() {
            if (!beginServlet(State.READING)) return;
            try {
                if (!request.getTrailerFields().isEmpty())
                    throw new PlatformException(400, "INVALID_JSON", "请求格式不支持");
                byte[] body;
                synchronized (this) {
                    if (state != State.READING) return;
                    live();
                    body = input.toByteArray();
                }
                var node = JSON.readTree(body);
                if (node == null || !node.isObject()) throw new IOException("JSON object required");
                FutureTask<Void> task;
                synchronized (this) {
                    if (state != State.READING) return;
                    live();
                    state = State.UPSTREAM;
                    var portal = source;
                    source = null;
                    input.reset();
                    task =
                            future =
                                    new FutureTask<>(
                                            () -> {
                                                worker = Thread.currentThread();
                                                workerStarts.incrementAndGet();
                                                try {
                                                    upstream(portal, body);
                                                } finally {
                                                    observeHandles();
                                                    workerExits.incrementAndGet();
                                                    worker = null;
                                                }
                                                return null;
                                            });
                }
                workers.execute(task);
            } catch (PlatformException e) {
                fail(e.status());
            } catch (Exception e) {
                fail(400);
            } finally {
                endServlet();
            }
        }

        private void upstream(GatewayIngressBoundary.PortalRequest portal, byte[] body) {
            try {
                synchronized (this) {
                    live();
                }
                var version =
                        metadata.lookup(
                                portal.sourceToken(),
                                portal.subject(),
                                portal.appCode(),
                                deadline,
                                cancellation);
                synchronized (this) {
                    live();
                }
                // Independent original-token exchange, never metadata grant resume or a recreated
                // main.
                var main =
                        authority.exchangeGatewayPublishedRun(
                                portal.sourceToken(), version.resource());
                synchronized (this) {
                    live();
                    proof = main;
                }
                if (!portal.subject().equals(main.operation().subject())
                        || !version.resource().equals(main.operation().resource())) throw denied();
                var result =
                        http.post(
                                engine.resolve("/admin/apps/" + portal.appCode() + "/runs"),
                                main.ticket().value(),
                                body,
                                MAX,
                                deadline,
                                cancellation,
                                () -> {
                                    synchronized (this) {
                                        live();
                                        main.requireVerified();
                                    }
                                });
                var node = JSON.readTree(result.body());
                if (node == null
                        || !node.isObject()
                        || node.size() != 4
                        || !"OK".equals(node.path("code").asText())
                        || !node.path("message").isTextual()
                        || !node.path("data").isObject()
                        || !(node.path("traceId").isNull() || node.path("traceId").isTextual()))
                    throw unavailable();
                synchronized (this) {
                    live();
                    main.requireVerified();
                    if (state != State.UPSTREAM) return;
                    output = result.body();
                    success = true;
                    state = State.WRITING;
                }
                installWriter();
            } catch (PlatformException e) {
                fail(e.status());
            } catch (Exception e) {
                fail(503);
            }
        }

        private void installWriter() {
            int status;
            synchronized (this) {
                if (listenerInstalled || state != State.WRITING) return;
                listenerInstalled = true;
                servletCalls++;
                status = responseStatus;
            }
            try {
                response.setStatus(status);
                response.setContentType("application/json;charset=UTF-8");
                var out = response.getOutputStream();
                out.setWriteListener(
                        new WriteListener() {
                            public void onWritePossible() {
                                synchronized (Exchange.this) {
                                    if (state != State.WRITING || writing) return;
                                    writing = true;
                                    servletCalls++;
                                }
                                try {
                                    while (out.isReady()) {
                                        byte[] bytes;
                                        int offset, n;
                                        synchronized (Exchange.this) {
                                            if (state != State.WRITING) return;
                                            if (success) {
                                                live();
                                                proof.requireVerified();
                                            }
                                            bytes = output;
                                            offset = position;
                                            n = Math.min(8192, bytes.length - position);
                                            position += n;
                                        }
                                        if (n == 0) {
                                            terminate();
                                            return;
                                        }
                                        // This finite chunk belongs to the callback before a
                                        // concurrent terminal decision; completion waits for it.
                                        out.write(bytes, offset, n);
                                        if (offset + n == bytes.length) {
                                            terminate();
                                            return;
                                        }
                                    }
                                } catch (PlatformException expired) {
                                    fail(expired.status());
                                } catch (Exception error) {
                                    terminate();
                                } finally {
                                    synchronized (Exchange.this) {
                                        writing = false;
                                    }
                                    endServlet();
                                }
                            }

                            public void onError(Throwable error) {
                                terminate();
                            }
                        });
            } catch (Exception error) {
                terminate();
            } finally {
                endServlet();
            }
        }

        // Tomcat may run the first write callback inline in setWriteListener on this worker.
        // Self-interruption would poison AsyncContext.complete before buffered bytes reach the
        // wire.
        // Future is installed before execute, under this Exchange monitor: no late Future install.
        private void cancelWork() {
            FutureTask<Void> task;
            synchronized (this) {
                if (cancellationStarted) return;
                cancellationStarted = true;
                task = future;
            }
            cancellation.cancel();
            if (task != null) task.cancel(worker != Thread.currentThread());
            observeHandles();
        }

        void fail(int status) {
            if (!beginServlet(null)) return;
            try {
                boolean committed = response.isCommitted(), stop;
                synchronized (this) {
                    if (state == State.TERMINAL) return;
                    stop = committed || position > 0 || listenerInstalled || async == null;
                    cancellationRequested = true;
                    source = null;
                    input.reset();
                    // A claimed finite chunk keeps the status that accompanied its authorization.
                    // Once bytes are committed/claimed, termination may not relabel them as error.
                    if (!committed && position == 0)
                        responseStatus =
                                Set.of(400, 401, 403, 409, 413, 415, 503).contains(status)
                                        ? status
                                        : 503;
                    if (!stop) {
                        success = false;
                        proof = null;
                        output =
                                Json.write(
                                                new ApiResponse<>(
                                                        "GATEWAY_RUN_REJECTED",
                                                        "运行请求未完成，请检查权限或服务状态",
                                                        null,
                                                        null))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        position = 0;
                        state = State.WRITING;
                    }
                }
                cancelWork();
                // A reserved writer keeps its sole ownership even if registration has not returned.
                if (stop) terminate();
                else installWriter();
            } finally {
                endServlet();
            }
        }

        void terminate() {
            ScheduledFuture<?> timer;
            AsyncContext completion;
            synchronized (this) {
                if (state == State.TERMINAL) return;
                state = State.TERMINAL;
                cancellationRequested = true;
                timer = timeout;
                timeout = null;
                source = null;
                proof = null;
                output = null;
                input.reset();
                active.remove(this);
                completion = takeCompletion();
            }
            cancelWork();
            if (timer != null) timer.cancel(false);
            completeAsync(completion);
        }

        private void servletTimeout() {
            fail(503);
            // A real container timeout must be acknowledged before its listener returns, or
            // Tomcat handles it as an uncompleted timeout. A registration already in progress
            // may still return later; terminal state prevents its callback from writing bytes.
            terminate();
            AsyncContext completion;
            synchronized (this) {
                completion = async;
                async = null;
            }
            completeAsync(completion);
        }

        // No Servlet or cancellation call may hold the state monitor: container callbacks can
        // enter it while owning Tomcat's processor lock. An in-flight registration/write owns its
        // context until it returns, including callbacks invoked inline by setWriteListener.
        private synchronized boolean beginServlet(State expected) {
            if (state == State.TERMINAL || (expected != null && state != expected)) return false;
            servletCalls++;
            return true;
        }

        private void endServlet() {
            AsyncContext completion;
            synchronized (this) {
                servletCalls--;
                completion = takeCompletion();
            }
            completeAsync(completion);
        }

        private AsyncContext takeCompletion() {
            if (state != State.TERMINAL || servletCalls != 0) return null;
            var actual = async;
            async = null;
            return actual;
        }

        private void observeHandles() {
            // Cancellation and worker exit can now overlap. Monotonic deltas retain late handle
            // installs/cancels without counting the same owned handle twice.
            int installs = cancellation.installedHandles(),
                    cancels = cancellation.cancelledHandles();
            handleInstalls.addAndGet(
                    Math.max(0, installs - observedInstalls.getAndAccumulate(installs, Math::max)));
            handleCancels.addAndGet(
                    Math.max(0, cancels - observedCancels.getAndAccumulate(cancels, Math::max)));
        }

        private void completeAsync(AsyncContext actual) {
            if (actual != null)
                try {
                    int status;
                    synchronized (this) {
                        status = responseStatus;
                    }
                    if (status != 200 && !response.isCommitted()) response.setStatus(status);
                    actual.complete();
                } catch (IllegalStateException ignored) {
                }
        }
    }

    record Observation(
            int active, int workerStarts, int workerExits, int handleInstalls, int handleCancels) {}

    Observation observation() {
        return new Observation(
                active.size(),
                workerStarts.get(),
                workerExits.get(),
                handleInstalls.get(),
                handleCancels.get());
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "DEPENDENCY_UNAVAILABLE", "运行服务暂不可用");
    }

    private static PlatformException denied() {
        return new PlatformException(403, "SYS-AUTH-403", "当前授权不允许运行");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.forEach(
                e -> {
                    e.fail(503);
                    e.terminate();
                });
        timeouts.shutdownNow();
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(3, TimeUnit.SECONDS)
                    || !timeouts.awaitTermination(3, TimeUnit.SECONDS))
                throw new IllegalStateException("GATEWAY_WORKERS_STILL_RUNNING");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GATEWAY_CLOSE_INTERRUPTED", e);
        }
    }
}
