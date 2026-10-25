package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_PASSWORD", matches = ".+")
class ChunkBusRedisTest {
    static ChunkBus bus(int port) {
        return new ChunkBus(
                "127.0.0.1", port, System.getenv("AGENT_REDIS_PASSWORD"), 256, 16777216);
    }

    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    @Test
    void redisSevenDeliversAcrossIndependentBusesWithoutLocalDuplicationOrRunLeakage()
            throws Exception {
        try (var first = bus(26379);
                var second = bus(26379)) {
            await(() -> first.chunkAvailable() && second.chunkAvailable());
            long run = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
            try (var local = first.subscribe(run);
                    var remote = second.subscribe(run);
                    var other = second.subscribe(run - 1)) {
                first.publish(run, "cross replica");
                final List<String> localTexts = new ArrayList<>(), remoteTexts = new ArrayList<>();
                await(
                        () -> {
                            drain(local, localTexts);
                            drain(remote, remoteTexts);
                            return !localTexts.isEmpty() && !remoteTexts.isEmpty();
                        });
                Thread.sleep(50);
                drain(local, localTexts);
                drain(remote, remoteTexts);
                assertEquals(List.of("cross replica"), localTexts);
                assertEquals(List.of("cross replica"), remoteTexts);
                assertNull(other.poll());
                remote.close();
                first.publish(run, "after cancel");
                await(
                        () -> {
                            drain(local, localTexts);
                            return localTexts.size() == 2;
                        });
                assertNull(remote.poll());
            }
        }
    }

    static void drain(ChunkBus.Subscription subscription, List<String> texts) {
        ChunkBus.Frame frame;
        while ((frame = subscription.poll()) != null) {
            try (var current = frame) {
                texts.add(current.text());
            }
        }
    }

    @Test
    void unavailableRedisNeverPretendsLocalDeliveryAndPublishDoesNotBlock() throws Exception {
        int unused;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unused = socket.getLocalPort();
        }
        try (var bus = bus(unused);
                var observer = bus.subscribe(18)) {
            assertTimeoutPreemptively(
                    Duration.ofMillis(100), () -> bus.publish(18, "not distributed"));
            assertFalse(bus.chunkAvailable());
            assertNull(observer.poll());
        }
    }

    @Test
    void disconnectingOnlyTestTcpLinksResetsObserverAndNeverCancelsPublisher() throws Exception {
        try (var proxy = new TcpLinks();
                var bus = bus(proxy.port())) {
            await(bus::chunkAvailable);
            try (var observer = bus.subscribe(19)) {
                proxy.close();
                await(observer::stopped);
                assertEquals("CHUNK_TRANSPORT_RESET", observer.resetReason());
                assertTimeoutPreemptively(
                        Duration.ofMillis(100), () -> bus.publish(19, "lost delta"));
                assertNull(observer.poll());
            }
        }
    }

    @Test
    void reconnectDeliversOnlyFutureDeltasWithoutReplayingTheGap() throws Exception {
        try (var proxy = new TcpLinks();
                var bus = bus(proxy.port())) {
            await(bus::chunkAvailable);
            long run = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
            try (var oldObserver = bus.subscribe(run)) {
                proxy.cut();
                await(oldObserver::stopped);
                bus.publish(run, "during gap");
                proxy.resume();
                await(bus::chunkAvailable);
                try (var freshObserver = bus.subscribe(run)) {
                    bus.publish(run, "future only");
                    var texts = new ArrayList<String>();
                    await(
                            () -> {
                                drain(freshObserver, texts);
                                return !texts.isEmpty();
                            });
                    Thread.sleep(50);
                    drain(freshObserver, texts);
                    assertEquals(List.of("future only"), texts);
                }
            }
        }
    }

    @Test
    void repeatedRawDisconnectsRetireRegisteredConnectionsBeforeRebuilding() throws Exception {
        var bus = bus(26379);
        var client = client(bus);
        var resources = registered(client);
        var channelsField = io.lettuce.core.AbstractRedisClient.class.getDeclaredField("channels");
        channelsField.setAccessible(true);
        var channels = (io.netty.channel.group.ChannelGroup) channelsField.get(client);
        try (bus) {
            await(bus::chunkAvailable);
            assertEquals(2, resources.size());
            for (int cycle = 0; cycle < 4; cycle++) {
                try (var observer = bus.subscribe(91)) {
                    // Alternate a single broken link and both links; an intact peer must retire
                    // too.
                    if (cycle % 2 == 0) channels.iterator().next().close().awaitUninterruptibly();
                    else channels.close().awaitUninterruptibly();
                    await(observer::stopped);
                    await(bus::chunkAvailable);
                    await(() -> resources.size() <= 2);
                    assertEquals(2, resources.size(), "retired connections after cycle " + cycle);
                    assertEquals(2, channels.size());
                }
            }
        }
        assertEquals(0, resources.size());
        assertEquals(0, channels.size());
    }

    @Test
    void partialConnectionFailureAndConcurrentShutdownReleaseAllConnections() throws Exception {
        try (var proxy = new TcpLinks()) {
            proxy.maxAccepted = 1;
            var bus = bus(proxy.port());
            var resources = registered(client(bus));
            try (bus) {
                await(() -> proxy.accepted.get() >= 2);
                await(resources::isEmpty);
                assertFalse(bus.chunkAvailable());
            }
            assertEquals(0, resources.size());
        }
        try (var proxy = new TcpLinks()) {
            proxy.maxAccepted = 1;
            proxy.stallRejected = true;
            var bus = bus(proxy.port());
            var resources = registered(client(bus));
            await(() -> proxy.accepted.get() >= 2);
            // The second TCP link is accepted but receives no handshake response. Shutdown
            // races a real pending connection, rather than merely an already failed attempt.
            assertTimeoutPreemptively(Duration.ofSeconds(4), bus::close);
            assertEquals(0, resources.size());
            assertFalse(bus.chunkAvailable());
        }
    }

    static io.lettuce.core.RedisClient client(ChunkBus bus) throws Exception {
        var field = ChunkBus.class.getDeclaredField("redis");
        field.setAccessible(true);
        var redis = field.get(bus);
        var clientField = RedisChunks.class.getDeclaredField("client");
        clientField.setAccessible(true);
        return (io.lettuce.core.RedisClient) clientField.get(redis);
    }

    static Set<?> registered(io.lettuce.core.RedisClient client) throws Exception {
        var field =
                io.lettuce.core.AbstractRedisClient.class.getDeclaredField("closeableResources");
        field.setAccessible(true);
        return (Set<?>) field.get(client);
    }

    /**
     * Byte-transparent test link, not a Redis protocol client; only this test's two connections are
     * cut.
     */
    static final class TcpLinks implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final ExecutorService pumps = Executors.newFixedThreadPool(4);
        final Thread accept;
        volatile boolean cut;
        volatile int maxAccepted = Integer.MAX_VALUE;
        volatile boolean stallRejected;
        final java.util.concurrent.atomic.AtomicInteger accepted =
                new java.util.concurrent.atomic.AtomicInteger();

        TcpLinks() throws Exception {
            accept =
                    Thread.startVirtualThread(
                            () -> {
                                try {
                                    for (int i = 0; i < 12; i++) {
                                        var client = server.accept();
                                        if (cut || accepted.incrementAndGet() > maxAccepted) {
                                            if (!cut && stallRejected) {
                                                sockets.add(client);
                                                continue;
                                            }
                                            client.close();
                                            continue;
                                        }
                                        var upstream =
                                                new Socket(InetAddress.getLoopbackAddress(), 26379);
                                        sockets.add(client);
                                        sockets.add(upstream);
                                        pumps.submit(() -> copy(client, upstream));
                                        pumps.submit(() -> copy(upstream, client));
                                    }
                                } catch (Exception ignored) {
                                }
                            });
        }

        static void copy(Socket source, Socket target) {
            try {
                source.getInputStream().transferTo(target.getOutputStream());
            } catch (Exception ignored) {
            }
        }

        int port() {
            return server.getLocalPort();
        }

        void cut() throws Exception {
            cut = true;
            for (var socket : sockets) socket.close();
        }

        void resume() {
            cut = false;
        }

        @Override
        public void close() throws Exception {
            server.close();
            for (var socket : sockets) socket.close();
            pumps.shutdownNow();
            assertTrue(pumps.awaitTermination(2, TimeUnit.SECONDS));
            accept.join(1000);
        }
    }
}
