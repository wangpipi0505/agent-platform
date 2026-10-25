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
    private enum Policy { LEGACY, OBSERVATION_METADATA, STATUS }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper(
            com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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
                                        .setMaxLineLength(8192)
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

    public Response getRunObservationMetadata(URI origin,String run,String ticket,long deadline,Cancellation cancel,Runnable check){
        requireRun(run);return execute(new HttpGet(origin.resolve("/internal/runs/"+run+"/observation-metadata")),ticket,8192,deadline,cancel,check,Policy.OBSERVATION_METADATA);
    }
    public Response getRunStatus(URI origin,String run,String ticket,long deadline,Cancellation cancel,Runnable check){
        requireRun(run);return execute(new HttpGet(origin.resolve("/admin/runs/"+run)),ticket,2*1024*1024,deadline,cancel,check,Policy.STATUS);
    }
    private static void requireRun(String run){if(run==null||!run.matches("r_[0-9a-f]{32}"))throw unavailable();}

    private Response execute(
            HttpUriRequestBase request,
            String ticket,
            int maximum,
            long totalDeadline,
            Cancellation cancellation,
            Runnable beforeSend) {
        return execute(request,ticket,maximum,totalDeadline,cancellation,beforeSend,Policy.LEGACY);
    }
    private Response execute(HttpUriRequestBase request,String ticket,int maximum,long totalDeadline,
            Cancellation cancellation,Runnable beforeSend,Policy policy) {
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
                        try {
                        int status = response.getCode();
                        if (status != 200) {
                            if(policy!=Policy.LEGACY){if(status!=403&&status!=404)throw unavailable();}
                            else if (Set.of(401, 403, 409, 503).contains(status))
                                throw new PlatformException(
                                        status, "DEPENDENCY_REJECTED", "下游拒绝当前请求");
                            else throw unavailable();
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
                        int limit=status==200?maximum:8192;
                        if (entity == null || entity.getContentLength() > limit)
                            throw unavailable();
                        var output = new ByteArrayOutputStream();
                        try (var in = entity.getContent()) {
                            try {
                                byte[] bytes = new byte[8192];
                                int n;
                                while ((n = in.read(bytes)) != -1) {
                                    check.run();
                                    if (n > limit - output.size()) throw unavailable();
                                    output.write(bytes, 0, n);
                                }
                            }catch(IOException|RuntimeException failure){
                                // Core's chunk stream close drains unread frames. Abort a terminal
                                // observation before that close; preserve legacy POST/get mechanics.
                                if(policy!=Policy.LEGACY&&!cancellation.cancelled())cancellation.cancel();
                                throw failure;
                            }
                        }
                        check.run();
                        byte[] body=output.toByteArray();
                        if(status!=200)throw observationError(status,body,policy);
                        return new Response(status, body);
                        } catch(IOException|RuntimeException failure) {
                            // Apache closes its response after this handler returns or throws.
                            // Abort rejected observations before graceful close drains an unread body.
                            if(policy!=Policy.LEGACY&&!cancellation.cancelled())cancellation.cancel();
                            throw failure;
                        }
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
    private static PlatformException observationError(int status,byte[] bytes,Policy policy){
        try{
            var text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            var r=JSON.readTree(text.toString());
            if(r==null||!r.isObject()||r.size()!=4||!r.has("code")||!r.has("message")||!r.has("data")||!r.has("traceId")
                    ||!r.path("code").isTextual()||!r.path("message").isTextual()||r.path("message").textValue().length()>512
                    ||!r.path("data").isNull()||!(r.path("traceId").isNull()||r.path("traceId").isTextual()&&r.path("traceId").textValue().length()<=128))return unavailable();
            String code=r.path("code").textValue();
            if(status==404&&code.equals("RUN_NOT_FOUND"))return new PlatformException(404,code,"运行不存在");
            if(status==403&&code.equals("RUN_STATUS_TOO_LARGE")&&policy==Policy.STATUS)return new PlatformException(403,code,"运行状态超出范围");
            if(status==403&&Set.of("SYS-AUTH-403","AUTHORITY_EXPIRED","AUTHORITY_CONTEXT_MISSING","RUN_AUTHORITY_DENIED","MATERIAL_SCOPE_MISSING_OR_CHANGED","AUTHORITY_DENIED").contains(code))return new PlatformException(403,code,"当前授权不允许读取运行状态");
            return unavailable();
        }catch(Exception invalid){return unavailable();}
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
