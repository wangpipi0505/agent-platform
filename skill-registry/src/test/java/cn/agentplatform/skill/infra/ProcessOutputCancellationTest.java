package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** 本地 PROCESS 协议与输出读取边界；存储为测试替身，不代表外部 worker、IAM 或 MinIO 联验。 */
@Timeout(45)
class ProcessOutputCancellationTest {
    @TempDir Path temp;

    @Test
    void sameAttemptCancellationStopsOutputReadsAndCompensatesOnlyItsUndeliveredBatch() throws Exception {
        try (var call = new OutputCall(1)) {
            assertTrue(call.secondUpload.await(15, TimeUnit.SECONDS), "worker must reach its second output");
            assertTrue(call.process.cancel(call.requestId, call.owner));
            call.releaseUpload.countDown();

            var completed = assertThrows(ExecutionException.class, () -> call.result.get(15, TimeUnit.SECONDS));
            var rejected = assertInstanceOf(PlatformException.class, completed.getCause());
            assertEquals(502, rejected.status());
            assertEquals("PROCESS_CANCELLED", rejected.code());
            assertEquals(0, call.readAfterRelease.get(), "cancelled output must stop before its next byte");
            assertEquals(2, call.uploads.get(), "the third output must never be registered");
            verify(call.store).discardProcessOutputs(
                    argThat(refs -> refs.size() == 1 && "art_output_1".equals(refs.getFirst().get("artifactId"))),
                    eq(call.runId), eq(call.callId), eq(1L), same(rejected));
            assertFalse(call.process.inFlight(call.requestId));
        }
    }

    @Test
    void staleAttemptCannotInterruptNewOutputOrDiscardResourcesAfterDelivery() throws Exception {
        try (var call = new OutputCall(2)) {
            assertTrue(call.secondUpload.await(15, TimeUnit.SECONDS), "new attempt must reach its second output");
            var stale = new ProcessAdapter.CancellationOwner(
                    call.owner.tenantId(), call.owner.principalId(), call.runId, 1, call.owner.skillVersionId());
            assertFalse(call.process.cancel(call.requestId, stale));
            call.releaseUpload.countDown();

            var output = call.result.get(15, TimeUnit.SECONDS);
            assertEquals(List.of("art_output_1", "art_output_2", "art_output_3"),
                    output.fileRefs().stream().map(ref -> ref.get("artifactId")).toList());
            assertEquals(3, call.uploads.get());
            assertEquals(1, call.readAfterRelease.get());
            assertFalse(call.process.cancel(call.requestId, call.owner), "delivered call has no active cancellation handle");
            verify(call.store, never()).discardProcessOutputs(anyList(), anyString(), anyLong(), anyLong(), any());
            assertFalse(call.process.inFlight(call.requestId));
        }
    }

    private final class OutputCall implements AutoCloseable {
        final long callId = 601;
        final String requestId = Long.toString(callId);
        final String runId = "r_output_cancel";
        final ProcessAdapter.CancellationOwner owner;
        final ArtifactStore store = mock(ArtifactStore.class);
        final AtomicInteger uploads = new AtomicInteger();
        final AtomicInteger readAfterRelease = new AtomicInteger();
        final CountDownLatch secondUpload = new CountDownLatch(1);
        final CountDownLatch releaseUpload = new CountDownLatch(1);
        final ExecutorService caller = Executors.newSingleThreadExecutor();
        final ProcessAdapter process;
        final Future<SkillAdapters.Raw> result;
        final Path root;

        OutputCall(long attempt) throws Exception {
            root = Files.createDirectory(temp.resolve("中文 输出交接 " + attempt)).toRealPath();
            String executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
                    .toRealPath().toString();
            Path classes = Path.of(ProcessOutputCancellationTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toRealPath();
            assertTrue(Files.isDirectory(classes));
            process = new ProcessAdapter(true, root.toString(), 1, 1, 0, 0, executable);
            owner = new ProcessAdapter.CancellationOwner("output-tenant", "output-owner", runId, attempt, 501);
            doAnswer(invocation -> {
                int ordinal = uploads.incrementAndGet();
                InputStream source = invocation.getArgument(3);
                var bytes = new ByteArrayOutputStream();
                if (ordinal == 2) {
                    secondUpload.countDown();
                    assertTrue(releaseUpload.await(15, TimeUnit.SECONDS), "test must release its own upload");
                    int first = source.read();
                    if (first >= 0) {
                        readAfterRelease.incrementAndGet();
                        bytes.write(first);
                    }
                }
                source.transferTo(bytes);
                assertArrayEquals(Worker.payload(ordinal), bytes.toByteArray());
                return Map.of("artifactId", "art_output_" + ordinal);
            }).when(store).storeProcessOutput(anyString(), anyLong(), anyString(), any(InputStream.class),
                    eq(runId), eq(callId), eq(attempt));

            Map<String, Object> contract = Map.of(
                    "code", "output-probe", "type", "PROCESS", "invocationKind", "SYNC", "skillVersionId", 501L,
                    "process", Map.of("command", List.of(executable, "-Xmx64m", "-cp", classes.toString(), Worker.class.getName()),
                            "timeoutMs", 30000, "stdin", Map.of("files", true)),
                    "inputSchema", Map.of("type", "object"), "outputSchema", Map.of("type", "object"));
            result = caller.submit(() -> {
                var jwt = Jwt.withTokenValue("verified-local-protocol-fixture").header("alg", "HS256")
                        .subject(owner.principalId()).claim("tenant_id", owner.tenantId())
                        .claim("roles", List.of("ADMIN")).build();
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
                try {
                    return new SkillAdapters(store, null, null, process).invoke(
                            contract, Map.of(), "output-probe-key", callId, false, runId, attempt);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        @Override public void close() throws Exception {
            // 仅回收本夹具登记的精确调用；不用系统进程名或其它调用的资源做清理。
            process.cancel(requestId, owner);
            releaseUpload.countDown();
            result.cancel(true);
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS), "owned caller must terminate");
            assertFalse(process.inFlight(requestId));
            try (var entries = Files.list(root)) {
                assertEquals(0, entries.count(), "owned callDir must be reclaimed");
            }
        }
    }

    /** 只用 JDK 生成三个真实文件和 v1 信封，输出消费发生在此子进程退出之后。 */
    public static final class Worker {
        static byte[] payload(int ordinal) {
            return new byte[] {0, (byte) ordinal, 2, 10, 13, 127, (byte) 255};
        }

        public static void main(String[] args) throws Exception {
            byte[] request = System.in.readNBytes(1024 * 1024 + 1);
            if (request.length > 1024 * 1024) throw new IllegalArgumentException("request too large");
            var id = Pattern.compile("\"requestId\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
                    .matcher(new String(request, StandardCharsets.UTF_8));
            if (!id.find()) throw new IllegalArgumentException("requestId missing");
            Path output = Files.createDirectory(Path.of("").toRealPath().resolve("output"));
            var declarations = new StringJoiner(",");
            for (int ordinal = 1; ordinal <= 3; ordinal++) {
                byte[] bytes = payload(ordinal);
                Files.write(output.resolve(ordinal + ".bin"), bytes, StandardOpenOption.CREATE_NEW);
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                declarations.add("{\"name\":\"binary-" + ordinal + "\",\"path\":\"output/" + ordinal
                        + ".bin\",\"contentType\":\"application/octet-stream\",\"bytes\":" + bytes.length
                        + ",\"sha256\":\"" + sha + "\"}");
            }
            System.out.print("{\"protocol\":\"algorithm-process/1\",\"requestId\":\"" + id.group(1)
                    + "\",\"ok\":true,\"result\":{\"binary\":true},\"files\":[" + declarations
                    + "],\"warnings\":[],\"metrics\":{}}");
        }
    }
}
