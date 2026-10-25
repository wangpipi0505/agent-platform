package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

class McpResourceLifecycleTest {
    @Test
    void gracefulCompletionMustWaitForActualReaderExit() throws Exception {
        try (var peer = new McpAdapterTest.Peer()) {
            var transport =
                    new BoundedMcpSseTransport(
                            peer.url(), null, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            CompletableFuture<Void> completion = null;
            var permit = new Semaphore(0);
            try {
                transport
                        .connect(
                                message ->
                                        message.map(
                                                value -> {
                                                    entered.countDown();
                                                    // Controlled lifecycle gate, no large input or
                                                    // heavy computation.
                                                    boolean done = false;
                                                    while (!done)
                                                        try {
                                                            done =
                                                                    release.await(
                                                                            20,
                                                                            TimeUnit.MILLISECONDS);
                                                        } catch (InterruptedException ignored) {
                                                        }
                                                    return value;
                                                }))
                        .block(Duration.ofSeconds(2));
                peer.event(
                        "message",
                        Json.write(Map.of("jsonrpc", "2.0", "id", 1, "result", Map.of())));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                transport.whenTerminated(permit::release);
                long beforeClose = System.nanoTime();
                transport.close();
                assertTrue(
                        System.nanoTime() - beforeClose < TimeUnit.MILLISECONDS.toNanos(300),
                        "API cleanup must have bounded latency");
                completion = transport.closeGracefully().toFuture();
                Thread.sleep(1200);
                assertFalse(
                        completion.isDone(),
                        "close completion must not claim an active reader has terminated");
                assertEquals(
                        0,
                        permit.availablePermits(),
                        "an active decoder must keep its permit occupied");
            } finally {
                release.countDown();
                transport.close();
                if (completion != null) completion.get(3, TimeUnit.SECONDS);
                assertTrue(permit.tryAcquire(1, TimeUnit.SECONDS));
                assertEquals(
                        0,
                        permit.availablePermits(),
                        "termination callback must release exactly once");
            }
        }
    }
}
