package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.PlatformException;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two fixed-origin calls only. Each actual Apache request has an installed cancellation handle and
 * a full-body deadline.
 */
public final class PublishedRunHttp implements AutoCloseable {
    private static final String CHECK = PublishedRunHttp.class.getName() + ".check";
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("gateway-http-deadline").factory());
    private final Set<HttpUriRequestBase> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public record Response(int status, byte[] body) {}

    public PublishedRunHttp() {
        // Core 5.3.4's trailer parser rejects when headerLines.size() >= maxHeaderCount.
        // A connection threshold of one therefore rejects the first actual trailer, including
        // undeclared trailers. Bound chunk/trailer lines before that count check as well.
        // Keep initial response parsing and request writing independent:
        // legitimate multi-header, chunked Spring JSON responses must continue to work.
        var connections =
                org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory.builder()
                        .http1Config(
                                org.apache.hc.core5.http.config.Http1Config.custom()
                                        .setMaxHeaderCount(1)
                                        .build())
                        .responseParserFactory(
                                new org.apache.hc.client5.http.impl.io
                                        .DefaultHttpResponseParserFactory(
                                        org.apache.hc.core5.http.config.Http1Config.custom()
                                                .setMaxHeaderCount(64)
                                                .setMaxLineLength(8192)
                                                .build()))
                        .requestWriterFactory(
                                org.apache.hc.core5.http.impl.io.DefaultHttpRequestWriterFactory
                                        .INSTANCE)
                        .build();
        var manager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setConnectionFactory(connections)
                        .setDefaultConnectionConfig(
                                ConnectionConfig.custom()
                                        .setConnectTimeout(Timeout.ofMilliseconds(1000))
                                        .setSocketTimeout(Timeout.ofMilliseconds(2000))
                                        .build())
                        .build();
        client =
                HttpClients.custom()
                        .setConnectionManager(manager)
                        .disableAutomaticRetries()
                        .disableRedirectHandling()
                        .disableCookieManagement()
                        .disableAuthCaching()
                        .disableContentCompression()
                        .disableConnectionState()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setAuthenticationEnabled(false)
                                        .setHardCancellationEnabled(true)
                                        .build())
                        .addRequestInterceptorLast(
                                (r, e, c) -> {
                                    if (!(c.getAttribute(CHECK) instanceof Runnable check))
                                        throw unavailable();
                                    // 5.4.4 ConnectExec obtains/connects the endpoint before
                                    // MainClientExec processes this interceptor.
                                    check.run();
                                })
                        .build();
    }

    public static URI origin(URI uri, boolean loopback) {
        if (uri == null
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65535
                || !("https".equals(uri.getScheme())
                        || (loopback
                                && "http".equals(uri.getScheme())
                                && "127.0.0.1".equals(uri.getHost()))))
            throw new IllegalArgumentException("TRUSTED_ORIGIN_REQUIRED");
        return uri;
    }

    /** Terminal wins even when a worker installs its real handle after cancellation. */
    public static final class Cancellation {
        private boolean cancelled;
        private HttpUriRequestBase active;
        private int installed, cancelledHandles;

        synchronized void install(HttpUriRequestBase request) {
            installed++;
            if (cancelled) {
                cancelledHandles++;
                request.cancel();
                throw unavailable();
            }
            if (active != null) throw new IllegalStateException("CONCURRENT_HTTP_CALL");
            active = request;
        }

        synchronized void release(HttpUriRequestBase request) {
            if (active == request) active = null;
        }

        synchronized void deadline(HttpUriRequestBase request) {
            if (active == request) {
                cancelledHandles++;
                request.cancel();
            }
        }

        public synchronized void cancel() {
            cancelled = true;
            if (active != null) {
                cancelledHandles++;
                active.cancel();
            }
        }

        public synchronized boolean cancelled() {
            return cancelled;
        }

        public synchronized int installedHandles() {
            return installed;
        }

        public synchronized int cancelledHandles() {
            return cancelledHandles;
        }
    }

    public Response get(
            URI uri,
            String ticket,
            int maximum,
            long deadline,
            Cancellation cancel,
            Runnable check) {
        return execute(new HttpGet(uri), ticket, maximum, deadline, cancel, check);
    }

    public Response post(
            URI uri,
            String ticket,
            byte[] body,
            int maximum,
            long deadline,
            Cancellation cancel,
            Runnable check) {
        var request = new HttpPost(uri);
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        return execute(request, ticket, maximum, deadline, cancel, check);
    }

    private Response execute(
            HttpUriRequestBase request,
            String ticket,
            int maximum,
            long totalDeadline,
            Cancellation cancellation,
            Runnable beforeSend) {
        long hopDeadline =
                Math.min(totalDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000));
        Runnable check =
                () -> {
                    if (closed.get()
                            || cancellation.cancelled()
                            || hopDeadline - System.nanoTime() <= 0) throw unavailable();
                    beforeSend.run();
                };
        check.run();
        cancellation.install(request);
        active.add(request);
        ScheduledFuture<?> timer = null;
        try {
            check.run();
            long millis =
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(hopDeadline - System.nanoTime()));
            request.setConfig(
                    RequestConfig.custom()
                            .setAuthenticationEnabled(false)
                            .setHardCancellationEnabled(true)
                            .setConnectionRequestTimeout(
                                    Timeout.ofMilliseconds(Math.min(1000, millis)))
                            .setResponseTimeout(Timeout.ofMilliseconds(Math.min(2000, millis)))
                            .build());
            request.setHeader("Authorization", "Bearer " + ticket);
            request.setHeader("Accept", "application/json");
            request.setHeader("Accept-Encoding", "identity");
            var context = HttpClientContext.create();
            context.setAttribute(CHECK, check);
            timer =
                    deadlines.schedule(
                            () -> cancellation.deadline(request),
                            Math.max(1, hopDeadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
            return client.execute(
                    request,
                    context,
                    response -> {
                        int status = response.getCode();
                        if (status != 200) {
                            if (Set.of(401, 403, 409, 503).contains(status))
                                throw new PlatformException(
                                        status, "DEPENDENCY_REJECTED", "下游拒绝当前请求");
                            throw unavailable();
                        }
                        for (String header :
                                List.of("Content-Encoding", "Set-Cookie", "Location", "Trailer"))
                            if (response.containsHeader(header)) throw unavailable();
                        var types = response.getHeaders("Content-Type");
                        if (types.length != 1
                                || !types[0].getValue()
                                        .matches(
                                                "(?i)application/json(?:\\s*;\\s*charset\\s*=\\s*(?:UTF-8|utf-8))?"))
                            throw unavailable();
                        var entity = response.getEntity();
                        if (entity == null || entity.getContentLength() > maximum)
                            throw unavailable();
                        var output = new ByteArrayOutputStream();
                        try (var in = entity.getContent()) {
                            byte[] bytes = new byte[8192];
                            int n;
                            while ((n = in.read(bytes)) != -1) {
                                check.run();
                                if (n > maximum - output.size()) throw unavailable();
                                output.write(bytes, 0, n);
                            }
                        }
                        check.run();
                        return new Response(status, output.toByteArray());
                    });
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        } finally {
            if (timer != null) timer.cancel(false);
            request.cancel();
            active.remove(request);
            cancellation.release(request);
        }
    }

    private static PlatformException unavailable() {
        return new PlatformException(503, "DEPENDENCY_UNAVAILABLE", "服务暂不可用");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        active.forEach(HttpUriRequestBase::cancel);
        deadlines.shutdownNow();
        try {
            client.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
            if (!deadlines.awaitTermination(3, TimeUnit.SECONDS))
                throw new IllegalStateException("GATEWAY_HTTP_TIMER_STILL_RUNNING");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GATEWAY_HTTP_CLOSE_INTERRUPTED", e);
        }
    }
}
