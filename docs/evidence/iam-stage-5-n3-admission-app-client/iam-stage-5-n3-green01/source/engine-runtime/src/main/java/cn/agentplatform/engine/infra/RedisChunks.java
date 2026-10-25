package cn.agentplatform.engine.infra;

import cn.agentplatform.common.Json;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.*;
import io.lettuce.core.resource.DefaultClientResources;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Redis7 PubSub is lossy observation, never an event log or a model retry mechanism. */
final class RedisChunks implements AutoCloseable {
    private static final String CHANNEL = "agent-platform:v1.2:engine:chunks";
    private final DefaultClientResources resources =
            DefaultClientResources.builder()
                    .ioThreadPoolSize(2)
                    .computationThreadPoolSize(2)
                    .build();
    private final ScheduledExecutorService connector =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        var thread = new Thread(r, "engine-chunk-connect");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Semaphore pending = new Semaphore(64);
    private final RedisClient client;
    private final Runnable lost;
    private volatile StatefulRedisConnection<String, String> publisher;
    private volatile StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile boolean subscribed;
    private volatile boolean closed;

    RedisChunks(
            String host,
            int port,
            String password,
            BiConsumer<Long, String> receive,
            Runnable lost) {
        this.lost = lost;
        var uri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(1));
        if (!password.isEmpty()) uri.withPassword(password.toCharArray());
        client = RedisClient.create(resources, uri.build());
        client.setOptions(
                ClientOptions.builder()
                        // Reconnect may replay accepted-but-unacknowledged PUBLISH commands.
                        // A fresh connection deliberately resumes future deltas only.
                        .autoReconnect(false)
                        .requestQueueSize(64)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(
                                SocketOptions.builder()
                                        .connectTimeout(Duration.ofSeconds(1))
                                        .build())
                        .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(1)))
                        .build());
        client.addListener(
                new RedisConnectionStateListener() {
                    @Override
                    public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
                        StatefulRedisConnection<String, String> oldPublisher;
                        StatefulRedisPubSubConnection<String, String> oldSubscriber;
                        synchronized (RedisChunks.this) {
                            if (connection != publisher && connection != subscriber) return;
                            subscribed = false;
                            oldPublisher = publisher;
                            oldSubscriber = subscriber;
                            publisher = null;
                            subscriber = null;
                        }
                        // Netty invokes this callback: synchronous close can wait on itself.
                        // Retire the whole pair, including a still-open peer connection.
                        if (oldPublisher != null) oldPublisher.closeAsync();
                        if (oldSubscriber != null) oldSubscriber.closeAsync();
                        if (!closed) lost.run();
                    }
                });
        connector.scheduleWithFixedDelay(() -> connect(receive), 0, 1, TimeUnit.SECONDS);
    }

    private void connect(BiConsumer<Long, String> receive) {
        if (closed || available()) return;
        StatefulRedisConnection<String, String> nextPublisher = null;
        StatefulRedisPubSubConnection<String, String> nextSubscriber = null;
        try {
            // The single connector owns uncommitted connections. Never hold the lifecycle
            // monitor while waiting for network I/O or a Netty disconnect callback.
            nextPublisher = client.connect();
            nextSubscriber = client.connectPubSub();
            var receiving = nextSubscriber;
            nextSubscriber.addListener(
                    new RedisPubSubAdapter<>() {
                        @Override
                        public void message(String channel, String message) {
                            if (receiving != subscriber || !subscribed) return;
                            // Only this service publishes this fixed private channel; no
                            // browser channel input.
                            if (!CHANNEL.equals(channel) || message.length() > 65536) return;
                            try {
                                var packet = Json.read(message);
                                long run = packet.path("run").asLong(-1);
                                String text = packet.path("text").asText();
                                if (run > 0
                                        && text.length() <= ChunkBus.MAX_CHUNK_BYTES
                                        && text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                                        .length
                                                <= ChunkBus.MAX_CHUNK_BYTES)
                                    receive.accept(run, text);
                            } catch (RuntimeException ignored) {
                                lost.run();
                            }
                        }
                    });
            nextSubscriber.async().subscribe(CHANNEL).get(1, TimeUnit.SECONDS);
            synchronized (this) {
                // Publish only a complete, live pair. Disconnect and shutdown use this same
                // monitor, so an old callback cannot clear a replacement pair.
                if (!closed && nextPublisher.isOpen() && nextSubscriber.isOpen()) {
                    publisher = nextPublisher;
                    subscriber = nextSubscriber;
                    subscribed = true;
                    nextPublisher = null;
                    nextSubscriber = null;
                }
            }
        } catch (Exception ignored) {
            // Keep PG-only observation alive during startup failure. Reconnect only transport,
            // never replay tokens.
        } finally {
            // Partial creation, failed subscription, disconnect-before-publication, or close
            // racing connection setup must unregister every uncommitted Lettuce object too.
            if (nextPublisher != null) nextPublisher.closeAsync();
            if (nextSubscriber != null) nextSubscriber.closeAsync();
        }
    }

    boolean available() {
        return !closed && subscribed && publisher != null && publisher.isOpen();
    }

    void publish(long run, String text) {
        if (!available()) return;
        if (!pending.tryAcquire()) {
            lost.run();
            return;
        }
        try {
            publisher
                    .async()
                    .publish(CHANNEL, Json.write(Map.of("run", run, "text", text)))
                    .whenComplete(
                            (ignored, failure) -> {
                                pending.release();
                                if (failure != null && !closed) lost.run();
                            });
        } catch (RuntimeException failure) {
            pending.release();
            if (!closed) lost.run();
        }
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> oldPublisher;
        StatefulRedisPubSubConnection<String, String> oldSubscriber;
        synchronized (this) {
            if (closed) return;
            closed = true;
            subscribed = false;
            oldPublisher = publisher;
            oldSubscriber = subscriber;
            publisher = null;
            subscriber = null;
        }
        connector.shutdownNow();
        if (oldSubscriber != null) oldSubscriber.closeAsync();
        if (oldPublisher != null) oldPublisher.closeAsync();
        try {
            connector.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        client.shutdown(Duration.ZERO, Duration.ofSeconds(1));
        resources.shutdown(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }
}
