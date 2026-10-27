package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;
import cn.agentplatform.skill.domain.SkillRules;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
 * <p>PLAT-21c 起每次调用都是一次协议交换（方案 §4.4–§4.6）：stdin 写入请求信封后立即关闭，
 * stdout 只接受唯一一个响应信封，退出码 2/3/4 分别映射为输入非法、计算失败、写输出失败，
 * 其余非 0 仍是 502 PROCESS_EXITED。stdout 超过上限不是“截断就好”，信封已不可信，必须
 * 杀树并判协议违规——算法被要求只回 SUMMARY，大结果走文件。
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

    /** 请求信封（§4.4）：requestId 由调用事实的 callId 派生，算法必须原样回显。 */
    public record Request(String requestId, String operation, JsonNode parameters) {

        public Request {
            Objects.requireNonNull(requestId, "requestId");
            operation = operation == null ? "" : operation;
            parameters = parameters == null ? Json.object() : parameters;
        }
    }

    /** 成功响应里平台需要继续处理的部分（§4.5）；文件转存由调用方完成。 */
    public record Output(
            JsonNode result,
            JsonNode files,
            JsonNode warnings,
            JsonNode metrics,
            int exitCode,
            long durationMs) {}

    /**
     * PLAT-21a 兼容入口：不携带业务参数，等价于 {@code operation=""}、{@code parameters={}}。
     * 生产接线走 {@link #execute(ProcessContract, Request)}。
     */
    public Output execute(ProcessContract contract) {
        return execute(contract, new Request(UUID.randomUUID().toString(), "", Json.object()));
    }

    /**
     * 执行一次契约化进程调用。开关关闭 403、允许根越界 403、超时 504、输入信封超限 400、
     * 退出码 2/3/4 与其它非 0 按 §4.6 映射、stdout 协议违规 502；任何异常路径都不留存活子进程树。
     */
    public Output execute(ProcessContract contract, Request request) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(request, "request");
        if (!enabled)
            throw new PlatformException(
                    403, "PROCESS_DISABLED", "本地进程执行未启用（agent.process.enabled=false）");

        byte[] envelope = envelope(contract, request);
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

            Process running = process;
            // stdout 超限意味着信封不可能完整，直接杀树；stderr 超限仍只截断（诊断用途）。
            Capture stdout =
                    new Capture(contract.maxOutputBytes(), () -> ProcessTree.killTree(running));
            Capture stderr = new Capture(contract.maxOutputBytes(), null);
            Thread stdoutReader =
                    Thread.ofVirtual()
                            .name("process-stdout-" + process.pid())
                            .start(() -> stdout.drain(running.getInputStream()));
            Thread stderrReader =
                    Thread.ofVirtual()
                            .name("process-stderr-" + process.pid())
                            .start(() -> stderr.drain(running.getErrorStream()));

            writeEnvelope(process, envelope);

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
            if (stdout.truncated())
                throw new PlatformException(
                        502,
                        "PROCESS_OUTPUT_TOO_LARGE",
                        "stdout 超过上限 " + contract.maxOutputBytes() + " 字节，已终止进程树");
            int exitCode = process.exitValue();
            if (exitCode != 0) throw exitFailure(exitCode, stderr, stdout);
            return success(contract, request, stdout, exitCode, durationMs);
        } finally {
            if (process != null && process.isAlive()) {
                ProcessTree.killTree(process);
            }
            deleteCallDir(callDir);
        }
    }

    /**
     * 请求信封（§4.4）。参数只放业务入参，凭据一律不进信封；{@code files} 目前是空对象——
     * artifact 引用到相对路径的映射属于后续卡，本卡不假装支持。
     */
    static byte[] envelope(ProcessContract contract, Request request) {
        var node = Json.object();
        node.put("protocol", contract.protocol());
        node.put("requestId", request.requestId());
        node.put("operation", request.operation());
        node.set("parameters", request.parameters());
        node.set("files", Json.object());
        byte[] bytes = Json.write(node).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ProcessContract.MAX_REQUEST_BYTES)
            throw new PlatformException(
                    400,
                    "PROCESS_INPUT_TOO_LARGE",
                    "请求信封序列化后 " + bytes.length + " 字节，超过上限 "
                            + ProcessContract.MAX_REQUEST_BYTES + " 字节");
        return bytes;
    }

    /**
     * 写入信封后立刻关闭 stdin：算法以 EOF 作为“参数已完整”的开始信号（§4.4）。子进程可能
     * 在读完之前就退出（例如立刻 exit 2），此时写失败不应该覆盖真实退出码，因此只记日志。
     */
    private static void writeEnvelope(Process process, byte[] envelope) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(envelope);
            stdin.flush();
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.DEBUG, "写入请求信封失败: " + ex.getMessage());
        }
    }

    /**
     * 非 0 退出的语义（§4.6）：2=输入/数据非法、3=计算失败、4=写输出失败，其余维持
     * PROCESS_EXITED。退出码是机器可判定的契约，因此优先于 stdout 里的自述；只有“其它非 0”
     * 才会在进程仍按协议给出 {@code ok=false} 时透出它自己的 error.message。
     */
    private static PlatformException exitFailure(int exitCode, Capture stderr, Capture stdout) {
        String tail = stderr.tailText();
        String suffix = tail.isEmpty() ? "" : "，stderr 尾部: " + tail;
        return switch (exitCode) {
            case 2 ->
                    new PlatformException(
                            422, "PROCESS_INPUT_INVALID", "算法进程判定输入或数据非法（exitCode=2）" + suffix);
            case 3 ->
                    new PlatformException(
                            502, "PROCESS_CALCULATION_FAILED", "算法进程计算失败（exitCode=3）" + suffix);
            case 4 ->
                    new PlatformException(
                            502, "PROCESS_OUTPUT_WRITE_FAILED", "算法进程写输出失败（exitCode=4）" + suffix);
            default -> {
                PlatformException rejected = rejectedEnvelope(stdout);
                yield rejected != null
                        ? rejected
                        : new PlatformException(
                                502, "PROCESS_EXITED", "进程非 0 退出，exitCode=" + exitCode + suffix);
            }
        };
    }

    /** 其它非 0 退出时，尽力解析 stdout 信封；解析不了就返回 null，绝不覆盖真实退出原因。 */
    private static PlatformException rejectedEnvelope(Capture stdout) {
        try {
            JsonNode root = singleObject(strictText(stdout));
            JsonNode ok = root.get("ok");
            if (ok != null && ok.isBoolean() && !ok.booleanValue()) {
                JsonNode protocol = root.get("protocol");
                if (protocol == null
                        || !protocol.isTextual()
                        || !ProcessContract.PROTOCOL.equals(protocol.asText())) return null;
                return rejected(root);
            }
        } catch (PlatformException ignored) {
            // 不是信封 = 以退出码为准。
        }
        return null;
    }

    /**
     * 退出码 0 的唯一成功路径（§4.5）：恰好一个 JSON 对象 → protocol → ok → requestId →
     * outputSchema。任何一项不成立都说明算法与平台不在同一份契约上，不能当作“输出验收失败”重试。
     */
    private static Output success(
            ProcessContract contract,
            Request request,
            Capture stdout,
            int exitCode,
            long durationMs) {
        JsonNode root = singleObject(strictText(stdout));

        JsonNode protocol = root.get("protocol");
        if (protocol == null || !protocol.isTextual() || !contract.protocol().equals(protocol.asText()))
            throw mismatch(
                    "响应 protocol 与请求不一致: 期望 " + contract.protocol() + "，实际 "
                            + (protocol == null || protocol.isNull() ? "null" : protocol.asText()));

        JsonNode okNode = root.get("ok");
        if (okNode == null || !okNode.isBoolean()) throw mismatch("响应缺少布尔字段 ok");
        if (!okNode.booleanValue())
            throw mismatch("退出码 0 却声明 ok=false，算法必须以 exitCode=2/3/4 表达拒绝");

        JsonNode requestId = root.get("requestId");
        if (requestId == null
                || !requestId.isTextual()
                || !request.requestId().equals(requestId.asText()))
            throw mismatch(
                    "响应 requestId 与请求不一致: 期望 " + request.requestId() + "，实际 "
                            + (requestId == null || requestId.isNull() ? "null" : requestId.asText()));

        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) throw invalid("成功响应缺少 result 对象");
        validateResult(contract, result);

        return new Output(
                result.deepCopy(),
                arrayOrEmpty(root.get("files")),
                arrayOrEmpty(root.get("warnings")),
                objectOrEmpty(root.get("metrics")),
                exitCode,
                durationMs);
    }

    private static void validateResult(ProcessContract contract, JsonNode result) {
        if (!contract.hasOutputSchema()) return;
        try {
            SkillRules.validate(
                    Json.mapper().convertValue(contract.outputSchema(), Object.class),
                    Json.mapper().convertValue(result, Object.class));
        } catch (IllegalArgumentException ex) {
            throw new PlatformException(
                    502, "PROCESS_OUTPUT_SCHEMA_INVALID", "result 不满足 outputSchema: " + ex.getMessage());
        }
    }

    /** ok=false 且退出码不属于 2/3/4 时使用：保留算法自己的业务错误，而不是笼统的“非 0 退出”。 */
    private static PlatformException rejected(JsonNode root) {
        JsonNode error = root.get("error");
        String message = error == null ? "" : error.path("message").asText("");
        if (message.isBlank()) message = "算法进程拒绝了本次调用";
        if (message.length() > 512) message = message.substring(0, 512);
        return new PlatformException(502, "PROCESS_REJECTED", message);
    }

    private static JsonNode arrayOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return Json.mapper().createArrayNode();
        if (!node.isArray()) throw invalid("响应字段 files/warnings 必须是数组");
        return node.deepCopy();
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return Json.object();
        if (!node.isObject()) throw invalid("响应字段 metrics 必须是对象");
        return node.deepCopy();
    }

    /**
     * 恰好一个 JSON 对象：前后多余文本（Banner、进度条、日志行）都是协议违规。先看有没有
     * 第一个 token，再要求读完整个对象后不存在第二个 token。
     */
    private static JsonNode singleObject(String text) {
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        if (first < text.length() && text.charAt(first) != '{')
            throw invalid("stdout 除响应信封外还包含多余文本（必须以 JSON 对象开始）");
        try (JsonParser parser = Json.mapper().createParser(text)) {
            if (parser.nextToken() == null) throw invalid("stdout 为空，没有响应信封");
            JsonNode node = Json.mapper().readTree(parser);
            if (parser.nextToken() != null) throw invalid("stdout 除响应信封外还包含多余文本");
            if (node == null || !node.isObject()) throw invalid("stdout 的响应信封不是 JSON 对象");
            return node;
        } catch (IOException ex) {
            throw invalid("stdout 不是合法 JSON: " + ex.getMessage());
        }
    }

    private static String strictText(Capture capture) {
        byte[] bytes = capture.bytesCopy();
        if (bytes.length == 0) throw invalid("stdout 为空，没有响应信封");
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw invalid("stdout 不是合法 UTF-8");
        }
    }

    private static PlatformException mismatch(String message) {
        return new PlatformException(502, "PROCESS_PROTOCOL_MISMATCH", message);
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(502, "PROCESS_OUTPUT_INVALID", message);
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

    /**
     * 定长尾部缓冲：无论子进程输出多少，只保留最后 capacity 字节；总量超过 capacity 时
     * trunc=true。使用环形数组而非无限 ByteArrayOutputStream，保证 2MiB 甚至更大洪泛也不 OOM。
     * onOverflow 在首次超限时触发一次（stdout 用它杀树；stderr 只截断）。
     */
    private static final class Capture {

        private final int capacity;
        private final byte[] ring;
        private final Runnable onOverflow;
        private int start;
        private int size;
        private long total;
        private boolean overflowFired;

        private Capture(int capacity, Runnable onOverflow) {
            this.capacity = capacity;
            this.ring = new byte[capacity];
            this.onOverflow = onOverflow;
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
                fireOverflow();
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
            fireOverflow();
        }

        private void fireOverflow() {
            if (onOverflow == null || overflowFired || total <= capacity) return;
            overflowFired = true;
            try {
                onOverflow.run();
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.DEBUG, "处理输出超限失败: " + ex.getMessage());
            }
        }

        private synchronized boolean truncated() {
            return total > capacity;
        }

        private synchronized byte[] bytesCopy() {
            return bytes();
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
