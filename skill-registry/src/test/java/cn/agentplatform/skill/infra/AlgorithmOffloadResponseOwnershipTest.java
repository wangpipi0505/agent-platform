package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioAsyncClient;

import okhttp3.Call;
import okhttp3.OkHttpClient;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class AlgorithmOffloadResponseOwnershipTest {
    @Test
    void interruptedAwaitOwnsResponseCompletedBeforeCallerStartsStopping() throws Throwable {
        boolean callerWasInterrupted = Thread.currentThread().isInterrupted();
        // This is the original private await/close boundary with real SDK I/O, not offload or PG.
        try (var peer = new AlgorithmOffloadInterruptionTest.LatchedS3();
                var shared = new ArtifactStore(peer.configuration());
                var operation =
                    shared.forAlgorithmDeadline(
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(2))) {
            var io = field(operation, "algorithmIo");
            var transport = (OkHttpClient) field(operation, "transport");
            var await = io.getClass().getDeclaredMethod("await", CompletableFuture.class);
            await.setAccessible(true);
            var asyncClient = ArtifactStore.class.getDeclaredMethod("asyncClient");
            asyncClient.setAccessible(true);
            var client = (MinioAsyncClient) asyncClient.invoke(operation);
            String key =
                    "0".repeat(64)
                            + "/artifacts/art_"
                            + UUID.randomUUID().toString().replace("-", "")
                            + ".json";
            peer.objects.put(
                    "/" + AlgorithmOffloadInterruptionTest.LatchedS3.BUCKET + "/" + key,
                    ("{\"value\":\"" + "x".repeat(600) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
            peer.stage = "GET";
            long preparationUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            CompletableFuture<GetObjectResponse> original =
                    client.getObject(
                            GetObjectArgs.builder()
                                    .bucket(AlgorithmOffloadInterruptionTest.LatchedS3.BUCKET)
                                    .region("us-east-1")
                                    .object(key)
                                    .build());
            var awaitReturned = new CountDownLatch(1);
            var allowCallerClose = new CountDownLatch(1);
            var awaitFailure = new AtomicReference<Throwable>();
            var closeFailure = new AtomicReference<Throwable>();
            var fixtureFailure = new AtomicReference<Throwable>();
            var interruptRestored = new AtomicBoolean();
            var worker =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        try {
                                            await.invoke(io, original);
                                            awaitFailure.set(
                                                    new AssertionError("await unexpectedly returned"));
                                        } catch (InvocationTargetException ex) {
                                            awaitFailure.set(ex.getCause());
                                        } catch (Throwable ex) {
                                            awaitFailure.set(ex);
                                        } finally {
                                            awaitReturned.countDown();
                                            // Model a caller descheduled between interrupted await and close.
                                            // No product monitor, SDK executor or response is held by this latch.
                                            try {
                                                if (!allowCallerClose.await(
                                                        remaining(preparationUntil),
                                                        TimeUnit.NANOSECONDS))
                                                    fixtureFailure.set(
                                                            new AssertionError("caller handoff deadline"));
                                            } catch (InterruptedException ex) {
                                                fixtureFailure.set(ex);
                                                Thread.currentThread().interrupt();
                                            }
                                            try {
                                                operation.close();
                                            } catch (Throwable ex) {
                                                closeFailure.set(ex);
                                            } finally {
                                                interruptRestored.set(
                                                        Thread.currentThread().isInterrupted());
                                            }
                                        }
                                    });
            Throwable primary = null;
            try {
                worker.start();
                assertTrue(peer.entered.await(remaining(preparationUntil), TimeUnit.NANOSECONDS));
                while (stages(io) != 1 && remaining(preparationUntil) > 0) Thread.sleep(1);
                assertEquals(1, stages(io), "original await registered its real SDK stage");
                var acceptedCalls = calls(io);
                assertEquals(1, acceptedCalls.size(), "one real GET Call, with fixed fixture region");
                Call originalCall = acceptedCalls.iterator().next();
                assertFalse(originalCall.isCanceled());
                assertFalse(original.isDone(), "peer has not sent the GET response headers");
                assertFalse((boolean) field(io, "stopping"));
                worker.interrupt();
                assertTrue(awaitReturned.await(remaining(preparationUntil), TimeUnit.NANOSECONDS));
                assertInstanceOf(InterruptedException.class, awaitFailure.get());
                assertFalse((boolean) field(io, "stopping"), "caller has not entered original close");
                assertEquals(1, stages(io));
                peer.release.countDown();
                GetObjectResponse response =
                        original.get(remaining(preparationUntil), TimeUnit.NANOSECONDS);
                assertNotNull(response);
                assertSame(response, original.getNow(null), "the SDK's original successful response");
                while (!callbacksExited(io, transport) && remaining(preparationUntil) > 0)
                    Thread.sleep(1);
                assertTrue(callbacksExited(io, transport), "real original completion callbacks exited");
                var retainedCalls = calls(io);
                assertTrue(retainedCalls.size() <= 1);
                if (!retainedCalls.isEmpty())
                    assertSame(originalCall, retainedCalls.iterator().next());
                System.out.println(
                        "ALGORITHM_RESPONSE_OWNERSHIP awaitInterrupted=true originalFutureSuccess=true"
                                + " sameResponse=true stoppingBeforeCallerClose="
                                + field(io, "stopping")
                                + " stages="
                                + stages(io)
                                + " calls="
                                + retainedCalls.size()
                                + " retainedOriginalCall="
                                + (!retainedCalls.isEmpty()
                                        && retainedCalls.iterator().next() == originalCall));
                allowCallerClose.countDown();
                worker.join(1000);
                assertFalse(
                        worker.isAlive(), "original close returned within the existing worker bound");
                assertNull(fixtureFailure.get());
                if (closeFailure.get() != null) {
                    assertEquals(IllegalStateException.class, closeFailure.get().getClass());
                    assertEquals("ALGORITHM_IO_STOP_NOT_CONFIRMED", closeFailure.get().getMessage());
                }
                assertTrue(interruptRestored.get(), "original close restores the caller interrupt");
                long completionUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                assertTrue(peer.finished.await(remaining(completionUntil), TimeUnit.NANOSECONDS));
                AlgorithmOffloadInterruptionTest.awaitOriginalQuiescence(operation, completionUntil);
            } catch (Throwable ex) {
                primary = ex;
                throw ex;
            } finally {
                // Cleanup occurs after the strict assertion. It cannot turn its false into a pass.
                allowCallerClose.countDown();
                peer.release.countDown();
                if (worker.isAlive()) worker.interrupt();
                Throwable cleanup = null;
                try {
                    if (original.isDone() && !original.isCompletedExceptionally()) {
                        GetObjectResponse response = original.getNow(null);
                        if (response != null) response.close();
                    }
                } catch (Throwable ex) {
                    cleanup = ex;
                }
                try {
                    operation.close();
                } catch (Throwable ex) {
                    if (cleanup == null) cleanup = ex;
                    else cleanup.addSuppressed(ex);
                } finally {
                    restoreCallerInterrupt(callerWasInterrupted);
                }
                try {
                    worker.join(1000);
                    assertFalse(worker.isAlive(), "owned fixture worker exited");
                } catch (Throwable ex) {
                    if (cleanup == null) cleanup = ex;
                    else cleanup.addSuppressed(ex);
                }
                if (cleanup != null) {
                    if (primary != null) primary.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        } finally {
            // The worker's preserved interrupt must not leak through close onto the JUnit caller.
            restoreCallerInterrupt(callerWasInterrupted);
        }
    }

    static void restoreCallerInterrupt(boolean wasInterrupted) {
        Thread.interrupted();
        if (wasInterrupted) Thread.currentThread().interrupt();
    }

    static long remaining(long until) {
        return Math.max(0, until - System.nanoTime());
    }

    static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static int stages(Object io) throws ReflectiveOperationException {
        synchronized (io) {
            return (int) field(io, "stages");
        }
    }

    @SuppressWarnings("unchecked")
    static Set<Call> calls(Object io) throws ReflectiveOperationException {
        synchronized (io) {
            return Set.copyOf((Set<Call>) field(io, "calls"));
        }
    }

    static boolean callbacksExited(Object io, OkHttpClient transport)
            throws ReflectiveOperationException {
        if (stages(io) != 0
                || transport.dispatcher().runningCallsCount() != 0
                || transport.dispatcher().queuedCallsCount() != 0) return false;
        var executor = transport.dispatcher().executorService();
        return executor instanceof ThreadPoolExecutor pool
                && pool.getActiveCount() == 0
                && pool.getQueue().isEmpty();
    }
}
