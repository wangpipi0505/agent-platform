package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** EX-15b: real child processes exercise the existing API before files support is implemented. */
@Timeout(60)
class ProcessFilesTest {
    @TempDir Path temp;

    @Test
    void acceptsValidEnvelopeWithoutOutputFiles() throws Exception {
        var output = execute("empty");

        assertEquals(0, output.exitCode());
        assertEquals("process-files-probe", output.result().path("marker").asText());
        var received = output.result().path("received");
        assertEquals(ProcessContract.PROTOCOL, received.path("protocol").asText());
        assertTrue(received.path("files").isObject());
        assertEquals(0, received.path("files").size());
        assertTrue(output.files().isArray());
        assertEquals(0, output.files().size());
    }

    @Test
    void rejectsParentRelativeOutputPathBeforeReturningSuccess() {
        var failure = assertThrows(PlatformException.class, () -> execute("escape"));

        assertEquals(502, failure.status());
        assertEquals("PROCESS_OUTPUT_INVALID", failure.code());
    }

    private ProcessAdapter.Output execute(String mode) throws Exception {
        Path root = Files.createDirectory(temp.resolve("中文 空格 PROCESS 文件根")).toRealPath();
        String executable =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name", "")
                                                .toLowerCase(Locale.ROOT)
                                                .contains("win")
                                        ? "java.exe"
                                        : "java")
                        .toRealPath()
                        .toString();
        Path classpath =
                Path.of(ProcessFilesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toRealPath();
        assertTrue(Files.isDirectory(classpath), "worker must use this test's compiled classes");
        var adapter = new ProcessAdapter(true, root.toString(), 1, 1, 0, 0, executable);
        String requestId = "process_files_" + UUID.randomUUID().toString().replace("-", "");
        var contract = Json.object();
        contract.set(
                "command",
                Json.tree(
                        List.of(
                                executable,
                                "-Xmx64m",
                                "-cp",
                                classpath.toString(),
                                Worker.class.getName(),
                                mode)));
        contract.put("timeoutMs", 20000);
        var request = new ProcessAdapter.Request(requestId, "files-probe", Json.object());
        var caller = Executors.newSingleThreadExecutor();
        var future =
                caller.submit(
                        () -> adapter.execute(ProcessContract.parse(contract), request, "files-test"));
        OwnedWorker owned = null;
        try {
            owned = awaitWorker(root, requestId, executable);
            // The child waits inside its own callDir until its exact process identity is registered.
            Path release = owned.callDir().resolve("process-files.release.tmp");
            Files.writeString(
                    release,
                    requestId,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.move(release, owned.callDir().resolve("process-files.release"));
            try {
                return future.get(25, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw new AssertionError("unexpected worker invocation failure", failure.getCause());
            }
        } finally {
            // This adapter and requestId belong only to this invocation, including startup failures.
            adapter.cancel(requestId);
            if (owned != null && owned.sameLiveProcess()) ProcessTree.killTree(owned.handle());
            future.cancel(true);
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS), "owned caller must terminate");
            assertFalse(adapter.inFlight(requestId), "owned invocation must leave the active registry");
            if (owned != null) assertFalse(owned.sameLiveProcess(), "owned worker must have exited");
            try (var entries = Files.list(root)) {
                assertEquals(0, entries.count(), "keepFailedDirs=0 must reclaim this callDir");
            }
        }
    }

    private static OwnedWorker awaitWorker(Path root, String requestId, String executable)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try (var entries = Files.list(root)) {
                for (Path dir : entries.toList()) {
                    if (!dir.getFileName().toString().startsWith("call-")
                            || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path identity = dir.resolve("process-files.identity");
                    if (!Files.isRegularFile(identity, LinkOption.NOFOLLOW_LINKS)) continue;
                    List<String> fields = Files.readAllLines(identity, StandardCharsets.UTF_8);
                    assertEquals(3, fields.size());
                    assertEquals(requestId, fields.get(2));
                    var handle = ProcessHandle.of(Long.parseLong(fields.get(0))).orElseThrow();
                    Instant started = Instant.parse(fields.get(1));
                    assertEquals(started, handle.info().startInstant().orElseThrow());
                    assertEquals(ProcessHandle.current().pid(), handle.parent().orElseThrow().pid());
                    assertEquals(
                            Path.of(executable).toRealPath(),
                            Path.of(handle.info().command().orElseThrow()).toRealPath());
                    Path realDir = dir.toRealPath();
                    assertEquals(root, realDir.getParent());
                    return new OwnedWorker(handle, started, realDir);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("owned worker did not publish its identity before the deadline");
    }

    private record OwnedWorker(ProcessHandle handle, Instant started, Path callDir) {
        boolean sameLiveProcess() {
            return handle.isAlive() && handle.info().startInstant().filter(started::equals).isPresent();
        }
    }

    /** Pure JDK fixture: no database, HTTP, dependency JARs, or path access from the files descriptor. */
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            if (args.length != 1 || !(args[0].equals("empty") || args[0].equals("escape")))
                throw new IllegalArgumentException("unknown fixture mode");
            byte[] bytes = System.in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("fixture request too large");
            String received = new String(bytes, StandardCharsets.UTF_8).trim();
            var request =
                    Pattern.compile("\"requestId\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"").matcher(received);
            if (!request.find()) throw new IllegalArgumentException("fixture requestId missing");
            String requestId = request.group(1);
            Path cwd = Path.of("").toRealPath();
            Path pending = cwd.resolve("process-files.identity.tmp");
            Files.write(
                    pending,
                    List.of(
                            Long.toString(ProcessHandle.current().pid()),
                            ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                            requestId),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.move(pending, cwd.resolve("process-files.identity"));
            Path release = cwd.resolve("process-files.release");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
                if (System.nanoTime() >= deadline)
                    throw new IllegalStateException("fixture release timed out");
                Thread.sleep(10);
            }
            if (!requestId.equals(Files.readString(release, StandardCharsets.UTF_8)))
                throw new IllegalStateException("fixture release identity mismatch");
            // The hostile path is only JSON text. Neither mode creates or opens escape.bin.
            String files =
                    args[0].equals("empty")
                            ? "[]"
                            : "[{\"name\":\"escape\",\"path\":\"../escape.bin\","
                                    + "\"contentType\":\"application/octet-stream\",\"bytes\":0,"
                                    + "\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}]";
            String response =
                    "{\"protocol\":\"algorithm-process/1\",\"requestId\":\""
                            + requestId
                            + "\",\"ok\":true,\"result\":{\"marker\":\"process-files-probe\",\"received\":"
                            + received
                            + "},\"files\":"
                            + files
                            + ",\"warnings\":[],\"metrics\":{}}";
            System.out.write(response.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }
    }
}
