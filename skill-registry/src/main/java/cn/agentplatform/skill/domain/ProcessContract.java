package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 算法本地进程调用的不可变契约（方案 §4.21 / §12）。
 *
 * <p>本对象只描述“允许调用什么”，不携带任何凭据：命令是 argv 数组而非 shell 字符串，
 * 执行器不得再做字符串拼接，从结构上杜绝命令注入。首元素必须是绝对路径，
 * 由发布期（T21b）校验文件存在且属于允许目录；执行期只负责按绝对路径启动。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>{@code timeout} 在 (0, 30min] 内，缺省 60s；超时由执行器杀整棵进程树并按 504 返回。
 *   <li>{@code maxOutputBytes} 在 [1, 1MiB] 内，缺省 1MiB；执行器对每条流硬截断。
 *   <li>{@code allowedEnv} 只允许新增/覆盖白名单变量，禁止覆盖 PATH、SystemRoot 等
 *       执行器保底注入的最小必需变量（否则子进程可被指向任意可执行文件）。
 *   <li>{@code stdinJson} v1 只允许 artifact 引用形态；读取与解析属于 T21b，
 *       本卡只校验其结构与非空大小上限。
 *   <li>{@code protocol} 只接受 {@link #PROTOCOL}；{@code outputSchema} 是可选的成功响应
 *       {@code result} 校验器（方案 §4.5），本卡只校验它是 JSON 对象，Schema 的结构校验
 *       在发布预检（{@link cn.agentplatform.skill.application.SkillCatalog#publish}）里做。
 * </ul>
 */
public record ProcessContract(
        List<String> command,
        String workDirRoot,
        Duration timeout,
        JsonNode stdinJson,
        Map<String, String> allowedEnv,
        int maxOutputBytes,
        String protocol,
        JsonNode outputSchema) {

    /** 算法进程与平台之间的唯一线协议标识（方案 §4.4）。 */
    public static final String PROTOCOL = "algorithm-process/1";

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
    public static final int HARD_MAX_OUTPUT_BYTES = 1024 * 1024;

    /**
     * 请求信封 {protocol, requestId, operation, parameters, files} 序列化后的上限（契约 §4.4）。
     * 超过即 400：这不是算法能自行处理的输入，继续执行只会让算法读到半截 JSON。
     */
    public static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private static final int MAX_COMMAND_ARGS = 64;
    private static final int MAX_ARG_LENGTH = 4096;
    private static final int MAX_WORKDIR_LENGTH = 4096;
    private static final int MAX_STDIN_BYTES = 1024 * 1024;
    private static final int MAX_ENV_ENTRIES = 32;
    private static final int MAX_ENV_VALUE_LENGTH = 4096;
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    /**
     * 这些变量由执行器按宿主最小必需集注入，契约不得覆盖；否则调用方可改写 PATH 或
     * 动态库搜索路径，等同于绕过“首元素绝对路径”的约束。
     */
    private static final Set<String> RESERVED_ENV =
            Set.of(
                    "PATH",
                    "PATHEXT",
                    "SYSTEMROOT",
                    "SYSTEMDRIVE",
                    "WINDIR",
                    "TEMP",
                    "TMP",
                    "COMSPEC",
                    "NUMBER_OF_PROCESSORS",
                    "PROCESSOR_ARCHITECTURE",
                    "PROCESSOR_IDENTIFIER",
                    "OS",
                    "HOME",
                    "USERPROFILE",
                    "APPDATA",
                    "LOCALAPPDATA",
                    "PROGRAMDATA",
                    "PROGRAMFILES",
                    "PROGRAMFILES(X86)",
                    "JAVA_HOME",
                    "CLASSPATH",
                    "TMPDIR",
                    "LANG",
                    "LC_ALL",
                    "HTTP_PROXY",
                    "HTTPS_PROXY",
                    "NO_PROXY",
                    "ALL_PROXY");

    public ProcessContract {
        command = validCommand(command);
        workDirRoot = validWorkDirRoot(workDirRoot);
        timeout = validTimeout(timeout);
        stdinJson = validStdin(stdinJson);
        allowedEnv = validEnvironment(allowedEnv);
        maxOutputBytes = validMaxOutput(maxOutputBytes);
        protocol = validProtocol(protocol);
        outputSchema = validOutputSchema(outputSchema);
    }

    /**
     * 从发布记录/调用请求中的 JSON 解析契约。只做结构与范围校验，不检查可执行文件是否存在
     * （发布期校验）也不解析 stdin 引用（T21b）；所有失败统一为 400 / PROCESS_CONTRACT_INVALID。
     */
    public static ProcessContract parse(JsonNode contract) {
        if (contract == null || !contract.isObject())
            throw invalid("字段 process 必须是 JSON 对象");

        // 与《算法进程接入-平台侧方案与契约》§4.2 对齐：契约写 executable + args，自测清单写 command 数组。
        // 两种写法归一到同一个 argv，避免"按文档写的清单发布不了、按平台写法写的清单算法侧不认"。
        List<String> command =
                contract.has("command")
                        ? parseCommand(contract.get("command"))
                        : parseExecutable(contract.get("executable"), contract.get("args"));
        String workDirRoot =
                parseWorkDirRoot(
                        contract.has("workDirRoot")
                                ? contract.get("workDirRoot")
                                : contract.get("workDir"));
        Duration timeout = parseTimeout(contract.get("timeoutMs"));
        JsonNode stdin = contract.get("stdin");
        if (stdin == null || stdin.isNull()) stdin = null;
        Map<String, String> env =
                parseEnvironment(
                        contract.has("env") ? contract.get("env") : contract.get("envAllowlist"));
        int maxOutput =
                parseMaxOutput(
                        contract.has("maxOutputBytes")
                                ? contract.get("maxOutputBytes")
                                : contract.get("maxStdoutBytes"));
        checkMaxStderr(contract.get("maxStderrBytes"));
        requireCleanup(contract.get("cleanup"));
        String protocol = parseProtocol(contract.get("protocol"));
        JsonNode outputSchema = parseOutputSchema(contract.get("outputSchema"));
        return new ProcessContract(
                command, workDirRoot, timeout, stdin, env, maxOutput, protocol, outputSchema);
    }

    /** §4.2 的 {@code executable + args} 形态：executable 必须绝对路径，args 可省略。 */
    private static List<String> parseExecutable(JsonNode executable, JsonNode args) {
        if (executable == null || !executable.isTextual())
            throw invalid("字段 executable 必须是绝对路径字符串（或改用具名 command 数组）");
        if (args != null && (!args.isArray() || args.size() > MAX_COMMAND_ARGS))
            throw invalid("字段 args 必须是最多 " + MAX_COMMAND_ARGS + " 项的字符串数组");
        List<String> command = new ArrayList<>(1 + (args == null ? 0 : args.size()));
        command.add(executable.asText());
        if (args != null)
            for (JsonNode item : args) {
                if (!item.isTextual()) throw invalid("字段 args 的每个元素都必须是字符串");
                command.add(item.asText());
            }
        return command;
    }

    /**
     * §4.2 的 {@code maxStderrBytes}：平台 v1 统一只保留 stderr 尾部 2 KiB 写执行事实，该字段接受但不改变
     * 保留策略；这里只做范围校验，避免算法侧写了越界值却在运行期静默忽略。
     */
    private static void checkMaxStderr(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (!node.isIntegralNumber()) throw invalid("字段 maxStderrBytes 必须是整数");
        long value = node.asLong();
        if (value < 1 || value > HARD_MAX_OUTPUT_BYTES)
            throw invalid(
                    "字段 maxStderrBytes 必须在 1 到 " + HARD_MAX_OUTPUT_BYTES + " 之间，实际: " + value);
    }

    /** §4.2 的 cleanup：v1 只允许 ALWAYS（不留残留目录），显式写别的值直接拒绝。 */
    private static void requireCleanup(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (!node.isTextual() || !"ALWAYS".equals(node.asText()))
            throw invalid("字段 cleanup 在 v1 只允许 ALWAYS，实际: " + node);
    }

    /** 是否声明了成功响应 {@code result} 的校验器；没有时只要求 result 是对象。 */
    public boolean hasOutputSchema() {
        return outputSchema != null;
    }

    private static String parseProtocol(JsonNode node) {
        if (node == null || node.isNull()) return PROTOCOL;
        if (!node.isTextual())
            throw invalid("字段 protocol 必须是字符串 \"" + PROTOCOL + "\"，实际类型: " + node.getNodeType());
        return validProtocol(node.asText());
    }

    private static JsonNode parseOutputSchema(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return validOutputSchema(node);
    }

    private static List<String> parseCommand(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty())
            throw invalid("字段 command 必须是至少包含一个元素的字符串数组");
        List<String> args = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual())
                throw invalid("字段 command 的每个元素都必须是字符串");
            args.add(item.asText());
        }
        return args;
    }

    private static String parseWorkDirRoot(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw invalid("字段 workDirRoot 必须是绝对路径字符串");
        return node.asText();
    }

    private static Duration parseTimeout(JsonNode node) {
        if (node == null || node.isNull()) return DEFAULT_TIMEOUT;
        if (!node.isIntegralNumber())
            throw invalid("字段 timeoutMs 必须是整数毫秒（实际类型: " + node.getNodeType() + "）");
        return Duration.ofMillis(node.longValue());
    }

    private static Map<String, String> parseEnvironment(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        // 契约 §4.2 早期写的 envAllowlist 是"允许继承的变量名"，与平台保留变量策略冲突：允许继承等于让子进程
        // 拿到平台自己都不允许覆盖的变量。这里明确拒绝并指路，避免换个写法悄悄放宽。
        if (node.isArray())
            throw invalid("字段 envAllowlist 的继承语义不受支持：请改用 env 对象显式声明变量与值");
        if (!node.isObject()) throw invalid("字段 env 必须是字符串到字符串的对象");
        Map<String, String> env = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().isTextual())
                throw invalid("字段 env 的变量 " + entry.getKey() + " 的值必须是字符串");
            env.put(entry.getKey(), entry.getValue().asText());
        }
        return env;
    }

    private static int parseMaxOutput(JsonNode node) {
        if (node == null || node.isNull()) return DEFAULT_MAX_OUTPUT_BYTES;
        if (!node.isIntegralNumber())
            throw invalid("字段 maxOutputBytes 必须是整数（实际类型: " + node.getNodeType() + "）");
        long value = node.longValue();
        if (value < 1 || value > HARD_MAX_OUTPUT_BYTES)
            throw invalid("字段 maxOutputBytes 必须在 1 到 " + HARD_MAX_OUTPUT_BYTES + " 之间，实际: " + value);
        return (int) value;
    }

    private static List<String> validCommand(List<String> command) {
        if (command == null || command.isEmpty())
            throw invalid("字段 command 必须至少包含一个参数");
        if (command.size() > MAX_COMMAND_ARGS)
            throw invalid("字段 command 参数个数不能超过 " + MAX_COMMAND_ARGS + "，实际: " + command.size());
        List<String> copy = new ArrayList<>(command.size());
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg == null) throw invalid("字段 command 的第 " + (i + 1) + " 个参数为 null");
            if (arg.length() > MAX_ARG_LENGTH)
                throw invalid("字段 command 的第 " + (i + 1) + " 个参数超过 " + MAX_ARG_LENGTH + " 字符");
            if (arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0)
                throw invalid("字段 command 的第 " + (i + 1) + " 个参数包含控制字符");
            copy.add(arg);
        }
        String executable = copy.getFirst();
        if (executable.isBlank() || !isAbsolute(executable))
            throw invalid("字段 command 的首元素必须是可执行文件绝对路径，实际: " + executable);
        return List.copyOf(copy);
    }

    private static String validWorkDirRoot(String workDirRoot) {
        if (workDirRoot == null) return null;
        String value = workDirRoot;
        if (value.isBlank()) throw invalid("字段 workDirRoot 不能为空字符串");
        if (value.length() > MAX_WORKDIR_LENGTH)
            throw invalid("字段 workDirRoot 不能超过 " + MAX_WORKDIR_LENGTH + " 字符");
        if (value.indexOf('\0') >= 0 || !isAbsolute(value))
            throw invalid("字段 workDirRoot 必须是绝对路径，实际: " + value);
        return value;
    }

    private static Duration validTimeout(Duration timeout) {
        if (timeout == null) return DEFAULT_TIMEOUT;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0)
            throw invalid(
                    "字段 timeoutMs 必须在 1 到 " + MAX_TIMEOUT.toMillis() + " 之间，实际: "
                            + timeout.toMillis());
        return timeout;
    }

    public boolean filesEnabled() {
        return stdinJson != null && stdinJson.isObject() && stdinJson.path("files").asBoolean(false);
    }

    private static JsonNode validStdin(JsonNode stdin) {
        if (stdin == null || stdin.isNull()) return null;
        // 只有对象顶层的保留键启用受控文件；旧 scalar/array stdin 仍原样保存。
        if (stdin.isObject() && stdin.has("files") && !stdin.get("files").isBoolean())
            throw invalid("字段 stdin.files 必须是布尔值");
        int bytes;
        try {
            bytes = Json.write(stdin).getBytes(StandardCharsets.UTF_8).length;
        } catch (RuntimeException ex) {
            throw invalid("字段 stdin 不是可序列化的 JSON: " + ex.getMessage());
        }
        if (bytes > MAX_STDIN_BYTES)
            throw invalid("字段 stdin 序列化后不能超过 " + MAX_STDIN_BYTES + " 字节，实际: " + bytes);
        return stdin.deepCopy();
    }

    private static Map<String, String> validEnvironment(Map<String, String> allowedEnv) {
        if (allowedEnv == null || allowedEnv.isEmpty()) return Map.of();
        if (allowedEnv.size() > MAX_ENV_ENTRIES)
            throw invalid("字段 env 变量个数不能超过 " + MAX_ENV_ENTRIES + "，实际: " + allowedEnv.size());
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : allowedEnv.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !ENV_NAME.matcher(name).matches())
                throw invalid("字段 env 的变量名不合法: " + name + "（只允许字母、数字、下划线且不以数字开头）");
            if (RESERVED_ENV.contains(name.toUpperCase(Locale.ROOT)))
                throw invalid("字段 env 不允许覆盖执行器保留变量 " + name);
            if (value == null) throw invalid("字段 env 的变量 " + name + " 值不能为 null");
            if (value.length() > MAX_ENV_VALUE_LENGTH)
                throw invalid("字段 env 的变量 " + name + " 值不能超过 " + MAX_ENV_VALUE_LENGTH + " 字符");
            if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw invalid("字段 env 的变量 " + name + " 值包含控制字符");
            copy.put(name, value);
        }
        return Map.copyOf(copy);
    }

    private static int validMaxOutput(int maxOutputBytes) {
        if (maxOutputBytes < 1 || maxOutputBytes > HARD_MAX_OUTPUT_BYTES)
            throw invalid(
                    "字段 maxOutputBytes 必须在 1 到 " + HARD_MAX_OUTPUT_BYTES + " 之间，实际: "
                            + maxOutputBytes);
        return maxOutputBytes;
    }

    /**
     * 线协议只能有一个：写死 v1 而不是“任意字符串”，是让算法侧与平台侧的协议版本漂移在
     * 发布期就变成显式错误，而不是运行期静默降级。
     */
    private static String validProtocol(String protocol) {
        if (protocol == null) return PROTOCOL;
        if (!PROTOCOL.equals(protocol))
            throw invalid("字段 protocol 只支持 \"" + PROTOCOL + "\"，实际: " + protocol);
        return protocol;
    }

    private static JsonNode validOutputSchema(JsonNode outputSchema) {
        if (outputSchema == null || outputSchema.isNull()) return null;
        if (!outputSchema.isObject()) throw invalid("字段 outputSchema 必须是 JSON 对象（JSON Schema）");
        return outputSchema.deepCopy();
    }

    private static boolean isAbsolute(String value) {
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "PROCESS_CONTRACT_INVALID", message);
    }

    @Override
    public String toString() {
        return "ProcessContract[command="
                + command
                + ", workDirRoot="
                + workDirRoot
                + ", timeout="
                + timeout
                + ", stdin="
                + (stdinJson == null ? "none" : stdinJson.getNodeType())
                + ", envKeys="
                + allowedEnv.keySet()
                + ", maxOutputBytes="
                + maxOutputBytes
                + ", protocol="
                + protocol
                + ", outputSchema="
                + (outputSchema == null ? "none" : outputSchema.getNodeType())
                + "]";
    }
}
