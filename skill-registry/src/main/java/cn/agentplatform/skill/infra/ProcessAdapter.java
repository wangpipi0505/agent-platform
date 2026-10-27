package cn.agentplatform.skill.infra;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * 本地算法进程的一次性执行适配器（PLAT-21 / 方案 §12）。
 *
 * <p>每次调用：在允许根 {@code agent.process.work-dir} 下新建独立的 {@code call-*} 目录作为
 * cwd，argv 原样交给 {@link ProcessBuilder}（不经过 shell），stdout/stderr 各自只保留末尾
 * 至多 {@code maxOutputBytes} 字节，超时或中断时终止整棵进程树，最后无论成败都尝试删除
 * callDir。开关 {@code agent.process.enabled} 默认 false，关闭时在创建任何目录/进程之前
 * 直接拒绝。
 *
 * <p>五条不变量（与测试一一对应）：
 *
 * <ol>
 *   <li>cwd 永远位于配置允许根之下；越界或不允许的根统一 403 PROCESS_WORKDIR_DENIED。
 *   <li>超时 504 PROCESS_TIMEOUT，且 finally 必定杀树，不留下孤儿。
 *   <li>每条流硬上限 1MiB（可下调），超出只截断并置 trunc 标志，不 OOM。
 *   <li>环境变量白名单：仅最小必需变量 + 契约 allowedEnv，绝不透传整个父进程环境。
 *   <li>开关关闭 403 PROCESS_DISABLED，且不产生任何副作用（不建目录、不起进程）。
 * </ol>
 *
 * <p>v1 有意不读取 stdin 中的 artifact 引用（防 SSRF 的解析属于 T21b），也不注入代理；
 * 取消（cancel）不在本卡范围。
 */
@Component
public class ProcessAdapter {

    private static final System.Logger LOG = System.getLogger(ProcessAdapter.class.getName());
    private static final int STDERR_TAIL_BYTES = 2048;
    private static final int READER_BUFFER_BYTES = 8192;
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(10);

    private final boolean enabled;
    private final Path allowedRoot;

    @Autowired
    public ProcessAdapter(
            @Value("${agent.process.enabled:false}") boolean enabled,
            @Value("${agent.process.work-dir:}") String workDirRoot) {
        this(
                enabled,
                blankToNull(workDirRoot) == null ? null : Path.of(workDirRoot));
    }

    /** 测试入口：直接注入开关与允许根，不依赖 Spring 配置。 */
    ProcessAdapter(boolean enabled, Path allowedRoot) {
        this.enabled = enabled;
        this.allowedRoot = allowedRoot;
    }

    /**
     * 执行一次进程调用。成功（exitCode=0）返回捕获结果；非 0 退出返回 502 并带 stderr 尾部，
     * 超时 504，工作目录越界 403，开关关闭 403。任何异常路径都不会留下存活的子进程树。
     */
    public Result execute(ProcessContract contract) {
        Objects.requireNonNull(contract, "contract");
        if (!enabled)
            throw new PlatformException(
                    403, "PROCESS_DISABLED", "本地进程执行未启用（agent.process.enabled=false）");

        long started = System.nanoTime();
        Path callDir = null;
        Process process = null;
        try {
            Path root = resolveAllowedRoot(contract.workDirRoot());
            try {
                callDir = Files.createTempDirectory(root, "call-");
            } catch (IOException ex) {
                throw denied("无法在允许根下创建调用目录: " + ex.getMessage());
            }

            ProcessBuilder builder =
                    ProcessTree.builder(contract.command(), callDir, contract.allowedEnv());
            try {
                process = ProcessTree.start(builder);
            } catch (IOException ex) {
                String message =
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                throw new PlatformException(
                        502, "PROCESS_START_FAILED", "进程启动失败: " + message);
            }

            Capture stdout = new Capture(contract.maxOutputBytes());
            Capture stderr = new Capture(contract.maxOutputBytes());
            Process running = process;
            Thread stdoutReader =
                    Thread.ofVirtual()
                            .name("process-stdout-" + process.pid())
                            .start(() -> stdout.drain(running.getInputStream()));
            Thread stderrReader =
                    Thread.ofVirtual()
                            .name("process-stderr-" + process.pid())
                            .start(() -> stderr.drain(running.getErrorStream()));

            boolean exited;
            try {
                exited = ProcessTree.awaitExit(process, contract.timeout());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PlatformException(504, "PROCESS_TIMEOUT", "等待进程退出被中断，已终止进程树");
            }
            if (!exited) {
                // 先杀树让读取线程拿到 EOF，再抛出 504；finally 里的检查是第二道保险。
                ProcessTree.killTree(process);
                join(stdoutReader);
                join(stderrReader);
                throw new PlatformException(
                        504,
                        "PROCESS_TIMEOUT",
                        "进程在 " + contract.timeout().toMillis() + "ms 内未退出，已终止进程树");
            }
            join(stdoutReader);
            join(stderrReader);

            long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            int exitCode = process.exitValue();
            if (exitCode != 0)
                throw new PlatformException(
                        502,
                        "PROCESS_EXITED",
                        "进程非 0 退出，exitCode=" + exitCode + "，stderr 尾部: " + stderr.tailText());
            return new Result(
                    exitCode,
                    stdout.text(),
                    stderr.text(),
                    durationMs,
                    stdout.truncated(),
                    stderr.truncated());
        } finally {
            if (process != null && process.isAlive()) {
                ProcessTree.killTree(process);
            }
            deleteCallDir(callDir);
        }
    }

    /**
     * 解析实际 cwd 允许根：配置根必须存在；契约若显式给出 workDirRoot，则其真实路径必须
     * 落在配置根之内（用 toRealPath 防 symlink/.. 逃逸）。
     */
    private Path resolveAllowedRoot(String contractRoot) {
        if (allowedRoot == null)
            throw denied("未配置允许根 agent.process.work-dir，拒绝执行本地进程");
        Path configured;
        try {
            configured = allowedRoot.toAbsolutePath().normalize().toRealPath();
        } catch (IOException ex) {
            throw denied("允许根不存在或不可访问: " + allowedRoot);
        }
        if (!Files.isDirectory(configured)) throw denied("允许根不是目录: " + configured);
        if (contractRoot == null) return configured;

        Path requested;
        try {
            requested = Path.of(contractRoot).toAbsolutePath().normalize().toRealPath();
        } catch (IOException | InvalidPathException ex) {
            throw denied("workDirRoot 不存在或不可访问: " + contractRoot);
        }
        if (!requested.startsWith(configured))
            throw denied("workDirRoot 必须位于允许根 " + configured + " 之内，实际: " + requested);
        return requested;
    }

    private static PlatformException denied(String message) {
        return new PlatformException(403, "PROCESS_WORKDIR_DENIED", message);
    }

    private static void join(Thread reader) {
        try {
            reader.join(READER_JOIN_TIMEOUT.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (reader.isAlive()) reader.interrupt();
    }

    /** 尽力删除 callDir；删除失败只记日志，不影响已经得到的执行结果。 */
    private static void deleteCallDir(Path callDir) {
        if (callDir == null || !Files.exists(callDir)) return;
        try (var paths = Files.walk(callDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ex) {
                                    LOG.log(
                                            System.Logger.Level.DEBUG,
                                            "调用目录条目删除失败: " + path + " (" + ex.getMessage() + ")");
                                }
                            });
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "调用目录清理失败: " + callDir, ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 一次调用的可观测结果；stdoutTail/stderrTail 是各自保留窗口内的文本。 */
    public record Result(
            int exitCode,
            String stdoutTail,
            String stderrTail,
            long durationMs,
            boolean truncStdout,
            boolean truncStderr) {}

    /**
     * 定长尾部缓冲：无论子进程输出多少，只保留最后 capacity 字节；总量超过 capacity 时
     * trunc=true。使用环形数组而非无限 ByteArrayOutputStream，保证 2MiB 甚至更大洪泛也不 OOM。
     */
    private static final class Capture {

        private final int capacity;
        private final byte[] ring;
        private int start;
        private int size;
        private long total;

        private Capture(int capacity) {
            this.capacity = capacity;
            this.ring = new byte[capacity];
        }

        private synchronized void drain(InputStream in) {
            byte[] buffer = new byte[READER_BUFFER_BYTES];
            try (in) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) append(buffer, read);
                }
            } catch (IOException ex) {
                LOG.log(System.Logger.Level.DEBUG, "读取子进程输出失败: " + ex.getMessage());
            }
        }

        private void append(byte[] bytes, int length) {
            total += length;
            if (length >= capacity) {
                System.arraycopy(bytes, length - capacity, ring, 0, capacity);
                start = 0;
                size = capacity;
                return;
            }
            int overflow = Math.max(0, size + length - capacity);
            if (overflow > 0) {
                start = (start + overflow) % capacity;
                size -= overflow;
            }
            int end = (start + size) % capacity;
            int first = Math.min(length, capacity - end);
            System.arraycopy(bytes, 0, ring, end, first);
            if (length > first) System.arraycopy(bytes, first, ring, 0, length - first);
            size += length;
        }

        private synchronized boolean truncated() {
            return total > capacity;
        }

        private synchronized String text() {
            return decode(bytes(), truncated());
        }

        private synchronized String tailText() {
            byte[] all = bytes();
            int keep = Math.min(STDERR_TAIL_BYTES, all.length);
            byte[] tail = new byte[keep];
            System.arraycopy(all, all.length - keep, tail, 0, keep);
            return decode(tail, truncated() || keep < all.length);
        }

        private byte[] bytes() {
            byte[] result = new byte[size];
            if (size == 0) return result;
            int first = Math.min(size, capacity - start);
            System.arraycopy(ring, start, result, 0, first);
            if (size > first) System.arraycopy(ring, 0, result, first, size - first);
            return result;
        }
    }

    /**
     * 窗口可能从 UTF-8 多字节序列中间开始/结束；丢弃不完整边界而不是产生替换字符，
     * 避免 tail 文本出现乱码。正常未截断的输出走同一实现，结果与 new String(UTF_8) 一致。
     */
    private static String decode(byte[] bytes, boolean truncated) {
        int start = 0;
        int end = bytes.length;
        if (truncated) {
            while (start < end && start < 3 && (bytes[start] & 0xC0) == 0x80) start++;
        }
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        int attemptEnd = end;
        for (int dropped = 0; dropped <= 3 && attemptEnd > start; dropped++) {
            try {
                return decoder
                        .decode(ByteBuffer.wrap(bytes, start, attemptEnd - start))
                        .toString();
            } catch (CharacterCodingException ex) {
                decoder.reset();
                attemptEnd--;
            }
        }
        return new String(bytes, start, Math.max(0, attemptEnd - start), StandardCharsets.UTF_8);
    }
}
