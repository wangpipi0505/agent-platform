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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

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
 *
 * <p>PLAT-21d 加固（契约 §5.5–§5.7）：
 *
 * <ol>
 *   <li>并发配额：全局 {@code Semaphore(max-concurrent)} + 每租户
 *       {@code Semaphore(max-concurrent-per-tenant)}，排队窗口 {@code queue-wait-ms}，超窗
 *       429 PROCESS_BUSY；名额只在执行线程持有，不跨数据库事务等待。
 *   <li>取消句柄：{@link #cancel(String)} 按 requestId（=callId）终止整棵树，被取消的调用以
 *       502 PROCESS_CANCELLED 结束；引擎取消事件的出站接线是已登记缺口（见证据 README）。
 *   <li>失败现场：成功调用彻底回收 callDir；失败/杀树/取消把现场挪成 {@code failed-*} 转储，
 *       保留最近 {@code keep-failed-dirs} 个（0 = 全部回收），最旧先剪。
 *   <li>执行期白名单：可执行文件不在 {@code agent.process.allowed-executables} 时 403
 *       PROCESS_EXECUTABLE_FORBIDDEN（注册期之外的第二道闸）。
 *   <li>审计事实：成功事实随 Output 落执行事实信封（命令模板/退出码/时长/工作目录/截断标志），
 *       失败事实走结构化日志；环境变量的键与值一律不落。
 * </ol>
 */
@Component
public class ProcessAdapter {

    private static final System.Logger LOG = System.getLogger(ProcessAdapter.class.getName());
    private static final int STDERR_TAIL_BYTES = 2048;
    private static final int READER_BUFFER_BYTES = 8192;
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(10);

    /** 审计事实 killReason 的取值：谁决定杀树，排查时一眼可辨。 */
    public static final String KILL_TIMEOUT = "TIMEOUT";
    public static final String KILL_CANCELLED = "CANCELLED";
    public static final String KILL_OUTPUT_OVERFLOW = "OUTPUT_OVERFLOW";
    public static final String KILL_ABNORMAL = "ABNORMAL";

    private final boolean enabled;
    private final Path allowedRoot;
    private final Semaphore globalPermits;
    private final int maxConcurrentPerTenant;
    private final long queueWaitMs;
    private final int keepFailedDirs;
    private final Set<String> allowedExecutables;

    /** 每租户信号量按需创建；租户数量有界（来自已验签 JWT），不做淘汰。 */
    private final ConcurrentMap<String, Semaphore> tenantPermits = new ConcurrentHashMap<>();

    /** 进行中的调用注册表：cancel(requestId) 按它定位活进程树；键=requestId（即 callId）。 */
    private final ConcurrentMap<String, ActiveCall> active = new ConcurrentHashMap<>();

    /** 既有调用点与单测的兼容构造：配额默认 4/1、排队 30s、保留 5 个失败转储、无白名单。 */
    public ProcessAdapter(boolean enabled, String workDirRoot) {
        this(enabled, workDirRoot, 4, 1, 30000, 5, "");
    }

    /** 容器注入的正式构造。配置越界或“开着开关却不给白名单”都让进程起不来，不静默降级。 */
    @Autowired
    public ProcessAdapter(
            @Value("${agent.process.enabled:false}") boolean enabled,
            @Value("${agent.process.work-dir:}") String workDirRoot,
            @Value("${agent.process.max-concurrent:4}") int maxConcurrent,
            @Value("${agent.process.max-concurrent-per-tenant:1}") int maxConcurrentPerTenant,
            @Value("${agent.process.queue-wait-ms:30000}") long queueWaitMs,
            @Value("${agent.process.keep-failed-dirs:5}") int keepFailedDirs,
            @Value("${agent.process.allowed-executables:}") String allowedExecutables) {
        this(
                enabled,
                blankToNull(workDirRoot) == null ? null : Path.of(workDirRoot),
                maxConcurrent,
                maxConcurrentPerTenant,
                queueWaitMs,
                keepFailedDirs,
                ProcessWhitelist.parse(allowedExecutables));
        // 契约 §7.1：“未配置白名单不能开启”。开着开关而没有白名单等同把本机所有可执行文件
        // 都交给登记者挑选，必须在装配期失败，而不是第一起事件之后。
        if (enabled && this.allowedExecutables.isEmpty())
            throw new IllegalStateException(
                    "agent.process.enabled=true 时必须配置 agent.process.allowed-executables 白名单");
    }

    /** 测试入口：直接注入开关与允许根，不依赖 Spring 配置（默认配额/保留窗口/无白名单）。 */
    ProcessAdapter(boolean enabled, Path allowedRoot) {
        this(enabled, allowedRoot, 4, 1, 30000, 5, Set.of());
    }

    ProcessAdapter(
            boolean enabled,
            Path allowedRoot,
            int maxConcurrent,
            int maxConcurrentPerTenant,
            long queueWaitMs,
            int keepFailedDirs,
            Set<String> allowedExecutables) {
        if (maxConcurrent < 1)
            throw new IllegalStateException(
                    "agent.process.max-concurrent 必须 >= 1，实际: " + maxConcurrent);
        if (maxConcurrentPerTenant < 1)
            throw new IllegalStateException(
                    "agent.process.max-concurrent-per-tenant 必须 >= 1，实际: " + maxConcurrentPerTenant);
        if (queueWaitMs < 0)
            throw new IllegalStateException(
                    "agent.process.queue-wait-ms 必须 >= 0，实际: " + queueWaitMs);
        if (keepFailedDirs < 0)
            throw new IllegalStateException(
                    "agent.process.keep-failed-dirs 必须 >= 0，实际: " + keepFailedDirs);
        this.enabled = enabled;
        this.allowedRoot = allowedRoot;
        this.globalPermits = new Semaphore(maxConcurrent);
        this.maxConcurrentPerTenant = maxConcurrentPerTenant;
        this.queueWaitMs = queueWaitMs;
        this.keepFailedDirs = keepFailedDirs;
        this.allowedExecutables = allowedExecutables == null ? Set.of() : allowedExecutables;
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
            long durationMs,
            Map<String, Object> fact) {}

    /**
     * PLAT-21a 兼容入口：不携带业务参数，等价于 {@code operation=""}、{@code parameters={}}。
     * 生产接线走 {@link #execute(ProcessContract, Request, String)}。
     */
    public Output execute(ProcessContract contract) {
        return execute(contract, new Request(UUID.randomUUID().toString(), "", Json.object()));
    }

    /** 等价于 {@code tenantId=null}（不参与每租户配额，仍受全局配额约束）。 */
    public Output execute(ProcessContract contract, Request request) {
        return execute(contract, request, null);
    }

    /**
     * 执行一次契约化进程调用。开关关闭 403、白名单外 403、允许根越界 403、配额排队超窗 429、
     * 输入信封超限 400、退出码 2/3/4 与其它非 0 按 §4.6 映射、stdout 协议违规 502、取消 502
     * PROCESS_CANCELLED；任何异常路径都不留存活子进程树。{@code tenantId} 来自已验签调用上下文，
     * 只用于每租户并发配额（null 跳过该层，不参与出站信封）。
     */
    public Output execute(ProcessContract contract, Request request, String tenantId) {
        return execute(contract, request, tenantId, null);
    }

    @FunctionalInterface
    interface OutputFiles {
        void persist(Path callDir, Output output, long deadline);
    }

    /** 输入物化只在原执行门通过、callDir 创建之后运行；任何输入失败都先于 worker 启动。 */
    Output execute(
            ProcessContract contract,
            Request request,
            String tenantId,
            BiFunction<Path, Long, Map<String, String>> inputFiles) {
        return execute(contract, request, tenantId, inputFiles, null);
    }

    Output execute(
            ProcessContract contract,
            Request request,
            String tenantId,
            BiFunction<Path, Long, Map<String, String>> inputFiles,
            OutputFiles outputFiles) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(request, "request");
        if (!enabled)
            throw new PlatformException(
                    403, "PROCESS_DISABLED", "本地进程执行未启用（agent.process.enabled=false）");
        // 执行期白名单是注册期之外的第二道闸：契约可能先注册、白名单后收紧，或直接绕过目录写库。
        if (!ProcessWhitelist.contains(allowedExecutables, contract.command().get(0)))
            throw new PlatformException(
                    403,
                    "PROCESS_EXECUTABLE_FORBIDDEN",
                    "可执行文件不在白名单内: "
                            + contract.command().get(0)
                            + "（agent.process.allowed-executables）");

        Semaphore tenantPermit = acquirePermits(tenantId);
        try {
            return runOnce(contract, request, inputFiles, outputFiles);
        } finally {
            if (tenantPermit != null) tenantPermit.release();
            globalPermits.release();
        }
    }

    /**
     * 排队获取全局与每租户名额。窗口内拿不到即 429 PROCESS_BUSY 可读拒绝（契约 §5.5）；
     * 名额只在执行线程持有，绝不跨数据库事务等待。返回实际持有的每租户信号量（未租时 null），
     * 调用方在 finally 里按获取的逆序释放。
     */
    private Semaphore acquirePermits(String tenantId) {
        boolean global;
        try {
            global = globalPermits.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy("等待全局执行名额被中断（排队窗口 " + queueWaitMs + "ms）");
        }
        if (!global)
            throw busy(
                    "全局并发已达上限，等待 "
                            + queueWaitMs
                            + "ms 未获得执行名额（agent.process.max-concurrent）");
        Semaphore permit = null;
        if (tenantId != null) {
            permit =
                    tenantPermits.computeIfAbsent(
                            tenantId, key -> new Semaphore(maxConcurrentPerTenant));
            boolean acquired = false;
            try {
                acquired = permit.tryAcquire(queueWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (!acquired) {
                globalPermits.release();
                throw busy(
                        "租户 "
                                + tenantId
                                + " 的并发已达上限 "
                                + maxConcurrentPerTenant
                                + "，等待 "
                                + queueWaitMs
                                + "ms 未获得执行名额（agent.process.max-concurrent-per-tenant）");
            }
        }
        return permit;
    }

    private static PlatformException busy(String message) {
        return new PlatformException(429, "PROCESS_BUSY", message);
    }

    private static PlatformException cancelled() {
        return new PlatformException(502, "PROCESS_CANCELLED", "进程已被取消并终止整棵进程树");
    }

    /**
     * 名额持有后的单次执行体：建 callDir → 起进程 → 协议交换 → 无论成败回收/转储。
     * 失败事实在这里落结构化日志；成功事实随 {@link Output#fact()} 交给调用方写入执行事实信封。
     */
    private Output runOnce(
            ProcessContract contract,
            Request request,
            BiFunction<Path, Long, Map<String, String>> inputFiles,
            OutputFiles outputFiles) {
        byte[] envelope = envelope(contract, request);
        long started = System.nanoTime();
        long deadline = started + contract.timeout().toNanos();
        Path root = null;
        Path callDir = null;
        Process process = null;
        ActiveCall call = null;
        RuntimeException failure = null;
        boolean preparingInputs = false;
        try {
            root = resolveAllowedRoot(contract.workDirRoot());
            try {
                callDir = Files.createTempDirectory(root, "call-");
            } catch (IOException ex) {
                throw denied("无法在允许根下创建调用目录: " + ex.getMessage());
            }

            if (inputFiles != null) {
                preparingInputs = true;
                envelope = envelope(contract, request, inputFiles.apply(callDir, deadline));
                if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0)
                    throw new PlatformException(504, "PROCESS_TIMEOUT", "输入文件准备超过执行时限或被中断");
            }

            ProcessBuilder builder =
                    ProcessTree.builder(contract.command(), callDir, contract.allowedEnv());
            try {
                process = ProcessTree.start(builder);
                preparingInputs = false;
            } catch (IOException ex) {
                String message =
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                throw new PlatformException(
                        502, "PROCESS_START_FAILED", "进程启动失败: " + message);
            }
            call = new ActiveCall(process, callDir);
            active.put(request.requestId(), call);

            ActiveCall registered = call;
            // stdout 超限意味着信封不可能完整，直接杀树；stderr 超限仍只截断（诊断用途）。
            Process running = process;
            Capture stdout =
                    new Capture(contract.maxOutputBytes(), () -> kill(registered, KILL_OUTPUT_OVERFLOW));
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
                long remaining = deadline - System.nanoTime();
                exited = inputFiles == null
                        ? ProcessTree.awaitExit(process, contract.timeout())
                        : remaining > 0 && ProcessTree.awaitExit(process, Duration.ofNanos(remaining));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PlatformException(504, "PROCESS_TIMEOUT", "等待进程退出被中断，已终止进程树");
            }
            if (!exited) {
                // 先杀树让读取线程拿到 EOF，再抛出 504；finally 里的检查是第二道保险。
                kill(call, KILL_TIMEOUT);
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
            // 取消可能发生在进程自然退出前后：只要取消已登记，结论一律是 PROCESS_CANCELLED，
            // 避免把“杀到一半的退出码”误读成算法自己的业务失败。
            if (call.cancelled) throw cancelled();
            if (stdout.truncated())
                throw new PlatformException(
                        502,
                        "PROCESS_OUTPUT_TOO_LARGE",
                        "stdout 超过上限 " + contract.maxOutputBytes() + " 字节，已终止进程树");
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                if (call.cancelled) throw cancelled();
                throw exitFailure(exitCode, stderr, stdout);
            }
            Output output = success(contract, request, stdout, exitCode, durationMs, callDir, stderr.truncated());
            // 成功文件必须在本次目录回收前交接；旧入口不提供回调，行为不变。
            if (outputFiles != null) outputFiles.persist(callDir, output, deadline);
            return output;
        } catch (RuntimeException ex) {
            failure = ex;
            throw ex;
        } finally {
            if (call != null) active.remove(request.requestId(), call);
            if (process != null && process.isAlive())
                kill(call, call.killReason != null ? call.killReason : KILL_ABNORMAL);
            String dumpDir = null;
            if (callDir != null && root != null && Files.exists(callDir)) {
                // 准备阶段尚未启动 worker，不把已下载输入留入失败现场转储。
                if (failure == null || keepFailedDirs <= 0 || preparingInputs || outputFiles != null) deleteCallDir(callDir);
                else dumpDir = retainFailureDump(root, callDir);
            }
            if (failure != null)
                logFailureFact(contract, call, failure, dumpDir);
        }
    }

    /**
     * 请求信封（§4.4）。旧入口 files 为空；显式文件输入由调用方物化后提供受控相对路径。
     * 凭据、artifact 标识与本机绝对路径一律不进文件映射。
     */
    static byte[] envelope(ProcessContract contract, Request request) {
        return envelope(contract, request, Map.of());
    }

    private static byte[] envelope(
            ProcessContract contract, Request request, Map<String, String> inputFiles) {
        var node = Json.object();
        node.put("protocol", contract.protocol());
        node.put("requestId", request.requestId());
        node.put("operation", request.operation());
        node.set("parameters", request.parameters());
        node.set("files", Json.tree(inputFiles));
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
     * 审计事实（命令模板/工作目录/退出码/时长/截断标志）在这里组装，随 Output 交给调用方；
     * 环境变量的键与值刻意不进事实——它们是配置面数据，不是执行结论。
     */
    private static Output success(
            ProcessContract contract,
            Request request,
            Capture stdout,
            int exitCode,
            long durationMs,
            Path callDir,
            boolean stderrTruncated) {
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

        JsonNode files = arrayOrEmpty(root.get("files"));
        for (JsonNode file : files) {
            JsonNode path = file.get("path");
            if (contract.filesEnabled()) validateOutputFilePath(callDir, path);
            if (path == null || !path.isTextual()) continue;
            // 两种分隔符按路径段处理，拒绝父目录引用但保留含连续点的普通文件名。
            for (String segment : path.asText().replace('\\', '/').split("/", -1))
                if ("..".equals(segment)) throw invalid("响应 files.path 不允许父目录段 ..");
        }

        var fact = new LinkedHashMap<String, Object>();
        fact.put("command", contract.command());
        fact.put("workDir", callDir == null ? null : callDir.toString());
        fact.put("exitCode", exitCode);
        fact.put("durationMs", durationMs);
        fact.put("stdoutTruncated", stdout.truncated());
        fact.put("stderrTruncated", stderrTruncated);
        return new Output(
                result.deepCopy(),
                files,
                arrayOrEmpty(root.get("warnings")),
                objectOrEmpty(root.get("metrics")),
                exitCode,
                durationMs,
                fact);
    }

    /** 声明路径先过位置与存在门；此门不代替后续稳定句柄、摘要及持久化验收。 */
    private static void validateOutputFilePath(Path callDir, JsonNode path) {
        if (path == null || !path.isTextual() || path.asText().isBlank())
            throw invalid("响应 files.path 必须是 output 内的相对文件路径");
        String value = path.asText().replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*"))
            throw invalid("响应 files.path 不允许绝对路径或盘符");
        for (String segment : value.split("/", -1))
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
                throw invalid("响应 files.path 不允许空段或目录引用");
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute()
                    || relative.getNameCount() < 2
                    || !"output".equals(relative.getName(0).toString())
                    || !Files.isRegularFile(callDir.resolve(relative), LinkOption.NOFOLLOW_LINKS))
                throw invalid("响应 files.path 必须指向本次 output 内已存在的普通文件");
        } catch (InvalidPathException ex) {
            throw invalid("响应 files.path 不是合法文件路径");
        }
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

    /**
     * 取消一次进行中的本地进程调用：按 requestId（=callId）定位执行注册表，立即终止整棵进程树；
     * 被取消的调用随后以 502 PROCESS_CANCELLED 收尾（确定性结论，调用方不得当作可重试失败）。
     * 返回 false 表示该调用不存在或已经结束——取消是幂等操作。
     *
     * <p>接线缺口（T21d 明确登记）：引擎的 run/node 取消目前只在 engine-runtime 落库
     * （run/node 状态 CANCELLED），没有出站事件到达 skill-registry，因此本句柄尚无生产调用方。
     * 接通方案：引擎取消事件 → skill-registry 内部端点（SERVICE 鉴权）→ {@code SkillAdapters
     * .cancelLocalProcess(callId)} → 本方法；在接通之前，超时杀树（{@link #KILL_TIMEOUT}）仍是
     * 唯一的“平台侧终止”路径。
     */
    public boolean cancel(String requestId) {
        if (requestId == null) return false;
        ActiveCall call = active.get(requestId);
        if (call == null) return false;
        call.cancelled = true;
        kill(call, KILL_CANCELLED);
        return true;
    }

    /** long 形式的 callId 便捷入口。 */
    public boolean cancel(long callId) {
        return cancel(Long.toString(callId));
    }

    /** 该调用是否仍在执行（取消入口与配额观测共用）。 */
    public boolean inFlight(String requestId) {
        return requestId != null && active.containsKey(requestId);
    }

    /** 终止整棵树并记录首个杀树原因；后续兜底杀树不改写它，排查时一眼可辨“谁决定杀的”。 */
    private static void kill(ActiveCall call, String reason) {
        if (call == null) return;
        if (call.killReason == null) call.killReason = reason;
        ProcessTree.killTree(call.process);
    }

    /**
     * 尽力删除 callDir；删除失败只记日志，不影响已经得到的执行结果。
     */
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

    /**
     * 失败现场保留（契约 §5.5 的“可排查”要求）：call-* 挪成 failed-*，与活跃调用目录彻底区分，
     * 再按 {@code keep-failed-dirs} 剪枝（最旧先删）。挪不动（句柄未释放等）就退回直接回收，
     * 绝不让排查需求变成无限泄漏。返回转储路径（进失败日志事实）；返回 null 表示未保留。
     */
    private String retainFailureDump(Path root, Path callDir) {
        Path dump = root.resolve("failed-" + callDir.getFileName());
        try {
            if (Files.exists(dump)) deleteCallDir(dump);
            Files.move(callDir, dump);
        } catch (IOException ex) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "失败现场保留失败（可能是文件句柄未释放），改为直接回收: " + callDir,
                    ex);
            deleteCallDir(callDir);
            return null;
        }
        pruneFailedDumps(root);
        return dump.toString();
    }

    /** 只删 failed-* 目录，绝不触碰活跃的 call-*；超额删最旧。 */
    private void pruneFailedDumps(Path root) {
        List<Path> dumps;
        try (var entries = Files.list(root)) {
            dumps =
                    new ArrayList<>(
                            entries
                                    .filter(Files::isDirectory)
                                    .filter(p -> p.getFileName().toString().startsWith("failed-"))
                                    .toList());
        } catch (IOException ex) {
            LOG.log(System.Logger.Level.WARNING, "失败转储清点失败: " + root, ex);
            return;
        }
        int excess = dumps.size() - keepFailedDirs;
        if (excess <= 0) return;
        dumps.sort(Comparator.comparingLong(ProcessAdapter::lastModifiedOrZero));
        for (int i = 0; i < excess; i++) {
            LOG.log(System.Logger.Level.DEBUG, "剪枝最旧失败转储: " + dumps.get(i));
            deleteCallDir(dumps.get(i));
        }
    }

    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 失败路径的执行事实走结构化日志（skill.execution_fact 只收成功事实：Y-7 语义下 PROCESS_*
     * 失败原样上抛、不落终态事实行，失败审计由错误响应 + 本日志 + 失败转储承载）。
     * 事实只含执行结论，绝不包含环境变量的键与值。
     */
    private static void logFailureFact(
            ProcessContract contract, ActiveCall call, RuntimeException failure, String dumpDir) {
        String code =
                failure instanceof PlatformException pe ? pe.code() : failure.getClass().getSimpleName();
        LOG.log(
                System.Logger.Level.WARNING,
                "PROCESS 执行失败事实: code=" + code
                        + ", command=" + contract.command()
                        + ", workDir=" + (call == null ? null : call.callDir)
                        + ", killReason=" + (call == null ? null : call.killReason)
                        + ", dumpDir=" + dumpDir
                        + ", message=" + failure.getMessage());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 注册表条目：进程树与调用目录的活引用；cancelled/killReason 跨线程读写，必须 volatile。 */
    private static final class ActiveCall {
        final Process process;
        final Path callDir;
        volatile boolean cancelled;
        volatile String killReason;

        ActiveCall(Process process, Path callDir) {
            this.process = process;
            this.callDir = callDir;
        }
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
