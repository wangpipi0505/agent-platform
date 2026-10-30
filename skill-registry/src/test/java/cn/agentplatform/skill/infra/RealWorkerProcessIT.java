package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D2：真实算法 worker 接入 PROCESS 执行链的集成证据（方案 §4.4 请求信封、§4.5 响应信封、§4.6 退出码）。
 *
 * <p>为什么必须用真实 jar：{@code ProcessProtocolTest}/{@code ProcessFilesTest} 用 FakeProcessWorker
 * 只证明"平台能拒绝坏协议"，不能证明已交付的算法真的能被平台按同一份契约启动、喂入输入、收回结果。
 * 本类用 {@link ProcessContract} + {@link ProcessAdapter} 真实启动 {@code algorithm-worker.jar}，
 * 把外部交付物与平台放在同一条链上验收。
 *
 * <p>为什么 jar 缺失时 abort 而不是静默跳过：真实 jar 是另一代码库的构建产物，本仓库无法也不应该
 * 代为构建；缺它时任何"通过"都是假证据，因此用 {@link Assumptions#abort} 明确标成 skipped，
 * 并在消息里给出构建路径。
 *
 * <p>为什么保留"直接起 jar"的 {@code rawWorker*} 用例：平台的非 0 退出路径只保留退出码语义
 * （§4.6），不会把 worker 的止损信封透给调用方；要断言 §4.5 的 {@code ok=false}/{@code error.code}
 * 与 stderr 只含日志，只能在不经平台的情况下读原始三流。两条路径互为对照，缺一不可。
 */
@Tag("integration")
@Timeout(180)
class RealWorkerProcessIT {

    /** 交付物：visibility-trajectory 的 algorithm-worker.jar（外部构建产物，测试不代为构建）。 */
    private static final Path WORKER_JAR =
            Path.of("G:/Code/Code-V3/visibility-trajectory/target/algorithm-worker.jar");

    /** 平台侧算法调用码，必须与 worker 的 {@code operation} 白名单一致，否则 exit 2。 */
    private static final String OPERATION = "orbit_visibility_search";

    private static final String REQUEST_ID = "real-worker-1";
    private static final String TENANT = "tenant-d2-real-worker";
    private static final String INPUT_NAME = "trajectory";
    private static final String INPUT_PATH = "input/target.csv";

    /**
     * 轨迹样例：worker 仓库 {@code examples/worker/input/target.csv} 的逐字节副本（3 行、684 字节、
     * 每行 20 列、LF 换行）。内嵌而不是跨仓库读文件，是为了让"输入"成为本测试自己的契约；
     * 每次取用都用 {@link #FIXTURE_SHA256} 校验，防止手抄漂移。
     */
    private static final String TRAJECTORY =
            """
            2026,07,01,10,00,00.000,4098051.677449370,1046582.574129067,-5324531.656820513,0,1,467902.471241204,4201909.349125518,-5325882.565278542,-4062.239844841,4230.603368534,2980.891822139,2579.751896059,5011.023213281,2970.478377824
            2026,07,01,10,00,01.000,4100629.888070424,1051592.925939699,-5321558.685120096,0,1,463840.010825316,4206137.987409831,-5322899.179509748,-4062.672603337,4226.664475398,2985.873556789,2576.664936492,5009.666350686,2975.458861320
            2026,07,01,10,00,02.000,4103205.013536204,1056601.906720198,-5318580.734331913,0,1,459777.119555398,4210362.684820590,-5319910.813406903,-4063.101552057,4222.721629590,2990.852479963,2573.575400870,5008.305263249,2980.436543107
            """;

    private static final String FIXTURE_SHA256 =
            "fb7e53bdcd58a7438bb0f608d523714524e605f1424b2caaa773e2fdefe94199";

    /**
     * 最小合法参数（取自 {@code examples/worker/request-minimal.json}）：1 个弹道目标 × 1 颗卫星 ×
     * 1 个采样点。真实 jar 的 {@code startOffsetSec}/{@code stepSizeSec}/{@code numCalculations}
     * 都有硬上下界，最小取值才能让集成测试稳定在 4s 内跑完。
     */
    private static final String MINIMAL_PARAMETERS =
            """
            {"ballistics":[{"file":"trajectory"}],"satellites":[{"semiMajorAxisM":7530612.0,"eccentricity":0.00085,"inclinationDeg":64.8,"raanDeg":120.0,"argumentOfPerigeeDeg":270.0,"meanAnomalyDeg":30.0,"epochUtc":"2026/07/01 10:00:00.000"}],"startOffsetSec":0,"stepSizeSec":1,"numCalculations":1}
            """;

    @TempDir Path temp;
    Path root;
    ProcessAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        if (!Files.isRegularFile(WORKER_JAR))
            Assumptions.abort(
                    "真实算法 worker 未构建: "
                            + WORKER_JAR
                            + "（先构建 visibility-trajectory 的 algorithm-worker.jar；本测试不代为构建，也不许跳过当通过）");
        root = Files.createDirectory(temp.resolve("allowed-root")).toRealPath();
        adapter = new ProcessAdapter(true, root);
    }

    @AfterEach
    void cleanUp() throws IOException {
        if (root == null) return;
        try (var entries = Files.list(root)) {
            assertEquals(
                    0,
                    entries.filter(p -> p.getFileName().toString().startsWith("call-")).count(),
                    "callDir 必须在 finally 中删除（failed-* 失败转储是有意保留，用后清理）");
        }
        try (var entries = Files.list(root)) {
            entries
                    .filter(p -> p.getFileName().toString().startsWith("failed-"))
                    .forEach(RealWorkerProcessIT::deleteTree);
        }
    }

    /**
     * 正向集成：平台按 §4.4 组信封 → 真实 worker 计算 → 平台按 §4.5 验收。
     *
     * <p>退出码 0 且 {@link ProcessAdapter.Output} 能返回，本身就是平台侧协议校验通过：stdout 只有一个
     * JSON、protocol 一致、ok=true、requestId 原样回显；任何一项不符 adapter 抛 502 而不是返回，
     * 因此这里断言的是"真实算法与平台在同一份契约上"这个结论。
     */
    @Test
    void platformEntryRunsRealWorkerAndAcceptsEnvelope() {
        AtomicReference<Artifact> artifact = new AtomicReference<>();
        ProcessAdapter.Output output =
                adapter.execute(
                        contract(),
                        request(parameters()),
                        TENANT,
                        (callDir, deadline) -> materializeInputs(callDir),
                        (callDir, produced, deadline) -> artifact.set(inspectOutput(callDir, produced)));

        assertEquals(0, output.exitCode());
        assertEquals(0, ((Number) output.fact().get("exitCode")).intValue());
        assertEquals(expectedCommand(), output.fact().get("command"), "执行事实必须记录实际 argv");
        assertTrue(output.durationMs() > 0, "执行事实必须记录真实耗时");

        JsonNode result = output.result();
        assertEquals("SUMMARY", result.path("resultMode").asText());
        assertEquals(1, result.path("counts").path("results").asInt(), "1 个采样点只能得到 1 条结果");
        assertEquals(1, result.path("counts").path("visibleArcs").asInt());
        assertEquals(3, result.path("counts").path("visiblePoints").asInt());
        assertEquals(2.0, result.path("best").path("visibleDurationSec").asDouble(), 1e-9);

        assertEquals(1, output.files().size(), "成功响应必须声明本次写出的明细文件");
        JsonNode file = output.files().get(0);
        assertEquals("visibility_detail", file.path("name").asText());
        assertEquals("output/visibility_detail.json", file.path("path").asText());
        assertEquals("application/json", file.path("contentType").asText());

        // 声明的文件必须真的写出来，且摘要/字节数对得上——平台记账用的就是这两个字段。
        Artifact persisted = artifact.get();
        assertNotNull(persisted, "outputFiles 回调必须在 callDir 回收前收到成功响应");
        assertEquals(file.path("bytes").asLong(), persisted.bytes());
        assertEquals(file.path("sha256").asText(), persisted.sha256());
        assertEquals(
                REQUEST_ID,
                persisted.detail().path("requestId").asText(),
                "落盘明细必须回显本次 requestId，才能证明算的是这一次请求");
        assertEquals(ProcessContract.PROTOCOL, persisted.detail().path("protocol").asText());

        assertTrue(output.warnings().isArray());
        assertEquals(0, output.warnings().size());
        assertTrue(output.metrics().isObject());

        // 成功调用的目录必须在返回前回收，否则允许根会被每次调用永久占用。
        String workDir = String.valueOf(output.fact().get("workDir"));
        assertTrue(Path.of(workDir).startsWith(root), "调用目录必须位于允许根内: " + workDir);
        assertFalse(Files.exists(Path.of(workDir)), "成功调用必须回收 callDir: " + workDir);
    }

    /**
     * 负例（平台侧）：非法 parameters 必须按 §4.6 落到 exit 2 → 422 {@code PROCESS_INPUT_INVALID}，
     * 且平台的错误消息要带上 worker 自己的 stderr 尾部。
     *
     * <p>注意这里断言的是 worker 失败码与原因，而不是字段名：真实 worker 只把字段名写进 stdout 失败
     * 信封的 {@code error.details.field}，stderr 只写「数值字段超出范围或类型非法」；平台按 §4.6
     * 在 exit 2 时只把 stderr 尾部并入错误消息，不透出信封。字段级定位由直接起 jar 的
     * {@link #rawWorkerRejectsOutOfRangeParameterWithFailureEnvelope()} 钉住——两条路径合起来才完整。
     */
    @Test
    void platformEntryMapsOutOfRangeParameterToInputInvalid() {
        ObjectNode invalid = ((ObjectNode) parameters()).put("stepSizeSec", 0);

        PlatformException failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                adapter.execute(
                                        contract(),
                                        request(invalid),
                                        TENANT,
                                        (callDir, deadline) -> materializeInputs(callDir)));

        assertEquals(422, failure.status());
        assertEquals("PROCESS_INPUT_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("exitCode=2"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("INPUT_INVALID"),
                "stderr 尾部必须带 worker 自己的失败码: " + failure.getMessage());
        assertTrue(
                failure.getMessage().contains("数值字段超出范围或类型非法"),
                "stderr 尾部必须带 worker 自己的失败原因: " + failure.getMessage());
    }

    /**
     * 钉住三参公开入口的真实行为：它固定以 {@code files={}} 发请求，真实 worker 的 file 契约要求
     * 1..16 项，因此必然 exit 2。这条用例存在的意义是防止有人把"公开入口能跑通真实 worker"当成
     * 已修复——要跑通必须走生产同款的"物化输入 + 五参 execute"路径。
     */
    @Test
    void publicEntryWithoutInputFilesIsRejected() {
        PlatformException failure =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(contract(), request(parameters()), TENANT));

        assertEquals(422, failure.status());
        assertEquals("PROCESS_INPUT_INVALID", failure.code());
        assertTrue(
                failure.getMessage().contains("文件映射数量应为1至16"),
                "worker 先校验 files 再计算: " + failure.getMessage());
    }

    /**
     * 直接起 jar 的正向对照（不经平台）：断言 §4.5 的成功信封形状、requestId 回显、
     * files 声明与真实文件一致，以及 stderr 只含日志。
     */
    @Test
    void rawWorkerEnvelopeIsSingleJsonWithEchoedRequestId() throws Exception {
        Path scratch = Files.createDirectory(temp.resolve("raw-green"));
        Path stdout = scratch.resolve("worker.stdout.txt");
        Path stderr = scratch.resolve("worker.stderr.txt");

        int exit = runRaw(scratch, parameters(), stdout, stderr);
        String out = Files.readString(stdout, StandardCharsets.UTF_8);
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        System.out.println(
                "[D2-real-worker] exit=" + exit + " stdout=" + excerpt(out, 500) + " stderr="
                        + err.replace("\r", "").replace("\n", " | "));

        assertEquals(0, exit, () -> "stderr=" + err);
        JsonNode response = singleJson(out);
        assertEquals(ProcessContract.PROTOCOL, response.path("protocol").asText());
        assertEquals(REQUEST_ID, response.path("requestId").asText(), "requestId 必须原样回显");
        assertTrue(response.path("ok").asBoolean(), "成功响应必须 ok=true");
        JsonNode result = response.path("result");
        assertTrue(result.isObject(), "成功响应必须带 result 对象");
        assertEquals("SUMMARY", result.path("resultMode").asText());
        assertEquals(1, result.path("counts").path("results").asInt());

        JsonNode file = response.path("files").get(0);
        Path detail = scratch.resolve(file.path("path").asText());
        assertTrue(Files.isRegularFile(detail), "响应声明的明细文件必须真实写入: " + detail);
        byte[] bytes = Files.readAllBytes(detail);
        assertEquals(file.path("bytes").asLong(), bytes.length, "响应 bytes 必须等于真实文件字节数");
        assertEquals(file.path("sha256").asText(), sha256(bytes), "响应 sha256 必须等于真实文件摘要");
        assertEquals(
                REQUEST_ID,
                Json.read(new String(bytes, StandardCharsets.UTF_8)).path("requestId").asText(),
                "明细文件必须回显本次 requestId");

        List<String> lines = err.lines().filter(line -> !line.isBlank()).toList();
        assertEquals(List.of("读取请求", "校验文件", "开始计算", "写入明细", "完成"), lines);
        assertFalse(
                lines.stream().anyMatch(line -> line.contains("{")),
                "协议 JSON 只能走 stdout，stderr 只承担日志");
    }

    /**
     * 负例（worker 自身）：非法 parameters 必须给出 §4.5 的 {@code ok=false} 信封、非空
     * {@code error.code}，并以 exit 2 表达"输入非法"（§4.6）。
     */
    @Test
    void rawWorkerRejectsOutOfRangeParameterWithFailureEnvelope() throws Exception {
        Path scratch = Files.createDirectory(temp.resolve("raw-negative"));
        Path stdout = scratch.resolve("worker.stdout.txt");
        Path stderr = scratch.resolve("worker.stderr.txt");
        ObjectNode invalid = ((ObjectNode) parameters()).put("stepSizeSec", 0);

        int exit = runRaw(scratch, invalid, stdout, stderr);
        String out = Files.readString(stdout, StandardCharsets.UTF_8);
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        System.out.println(
                "[D2-real-worker 负例] exit=" + exit + " stdout=" + excerpt(out, 500));

        assertEquals(2, exit, () -> "stdout=" + out + " stderr=" + err);
        JsonNode response = singleJson(out);
        assertEquals(ProcessContract.PROTOCOL, response.path("protocol").asText());
        assertEquals(REQUEST_ID, response.path("requestId").asText());
        assertFalse(response.path("ok").asBoolean(), "负例必须以 ok=false 表达拒绝");
        assertEquals("INPUT_INVALID", response.path("error").path("code").asText());
        assertFalse(
                response.path("error").path("message").asText().isBlank(),
                "失败信封必须带可读 message");
        assertEquals(
                "stepSizeSec",
                response.path("error").path("details").path("field").asText(),
                "details 必须指出越界字段");
    }

    /** 平台侧契约：command 走 argv 数组、workDirRoot 指允许根、120s 上限、显式开启受控文件。 */
    private ProcessContract contract() {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("command", expectedCommand());
        raw.put("workDirRoot", root.toString());
        raw.put("timeoutMs", 120_000);
        raw.put("stdin", Map.of("files", true));
        raw.put("cleanup", "ALWAYS");
        return ProcessContract.parse(Json.tree(raw));
    }

    private static List<String> expectedCommand() {
        return List.of(
                javaExecutable().toString(), "-jar", WORKER_JAR.toAbsolutePath().toString());
    }

    private static ProcessAdapter.Request request(JsonNode parameters) {
        return new ProcessAdapter.Request(REQUEST_ID, OPERATION, parameters);
    }

    private static JsonNode parameters() {
        return Json.read(MINIMAL_PARAMETERS);
    }

    /** 当前 JVM 的 java（Windows 为 java.exe），保证子进程与平台自身同一运行时。 */
    private static Path javaExecutable() {
        String name =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe"
                        : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", name);
        assertTrue(Files.isRegularFile(java), "当前 JVM 的 java 可执行文件必须存在: " + java);
        return java;
    }

    private static byte[] fixture() {
        byte[] bytes = TRAJECTORY.getBytes(StandardCharsets.UTF_8);
        assertEquals(684, bytes.length, "内置轨迹样例必须是 684 字节");
        assertEquals(
                FIXTURE_SHA256,
                sha256(bytes),
                "内置轨迹样例必须是 examples/worker/input/target.csv 的逐字节副本");
        return bytes;
    }

    private static void writeFixture(Path workDir) throws IOException {
        Path inputDir = Files.createDirectories(workDir.resolve("input"));
        Files.write(inputDir.resolve("target.csv"), fixture());
    }

    /**
     * 输入物化，对应生产 {@code SkillAdapters#materializeProcessInputs}：文件落在
     * {@code <callDir>/input/} 下，信封里只出现相对路径——worker 的 {@code WorkerFiles.prepare}
     * 要求每个值以 {@code input/} 开头且必须存在于工作目录内。
     */
    private static Map<String, String> materializeInputs(Path callDir) {
        try {
            writeFixture(callDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("物化输入失败: " + callDir, ex);
        }
        return Map.of(INPUT_NAME, INPUT_PATH);
    }

    /**
     * 输出回调：成功返回前 adapter 就会回收 callDir，所以摘要必须在这里算完再带出去。
     *
     * <p>同时把响应声明与真实文件对齐校验：平台若直接把 {@code files[].sha256}/{@code bytes}
     * 记进账，声明与实物不符就等于记了一个不存在的摘要。
     */
    private static Artifact inspectOutput(Path callDir, ProcessAdapter.Output output) {
        try {
            JsonNode declared = output.files().get(0);
            Path inCallDir = callDir.resolve(declared.path("path").asText()).normalize();
            Path file = inCallDir.toRealPath();
            assertTrue(file.startsWith(callDir.toRealPath()), "输出文件必须落在本次调用目录内: " + file);
            assertTrue(Files.isRegularFile(file), "输出文件必须是普通文件: " + file);
            byte[] bytes = Files.readAllBytes(file);
            assertEquals(declared.path("bytes").asLong(), bytes.length, "响应 bytes 必须等于真实文件字节数");
            assertEquals(declared.path("sha256").asText(), sha256(bytes), "响应 sha256 必须等于真实文件摘要");
            JsonNode detail = Json.read(new String(bytes, StandardCharsets.UTF_8));
            assertTrue(detail.isObject(), "明细文件顶层必须是 JSON 对象");
            return new Artifact(file, bytes.length, sha256(bytes), detail);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取输出失败: " + callDir, ex);
        }
    }

    /** 回调里只能带出纯数据：callDir 随后即被删除，路径仅用于诊断。 */
    record Artifact(Path path, long bytes, String sha256, JsonNode detail) {}

    /**
     * 直接起 jar 的一次调用：与平台一样用 stdin 喂信封、以 cwd 为工作目录，但 stdout/stderr 重定向
     * 到文件而不是管道——算法输出再大也不会把子进程堵在管道缓冲区上，测试能拿到完整原文。
     */
    private static int runRaw(Path workDir, JsonNode parameters, Path stdout, Path stderr)
            throws IOException, InterruptedException {
        writeFixture(workDir);
        ObjectNode envelope = Json.object();
        envelope.put("protocol", ProcessContract.PROTOCOL);
        envelope.put("requestId", REQUEST_ID);
        envelope.put("operation", OPERATION);
        envelope.set("parameters", parameters);
        envelope.set("files", Json.tree(Map.of(INPUT_NAME, INPUT_PATH)));

        ProcessBuilder builder =
                new ProcessBuilder(
                        javaExecutable().toString(),
                        "-jar",
                        WORKER_JAR.toAbsolutePath().toString());
        builder.directory(workDir.toFile());
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(Json.write(envelope).getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "真实 worker 必须在 120s 内退出");
        return process.exitValue();
    }

    /** stdout 必须"有且只有一个" JSON 对象：解析出一个值后再取 token 必须为 null（§4.5）。 */
    private static JsonNode singleJson(String text) throws IOException {
        String stripped = text.strip();
        assertTrue(stripped.startsWith("{"), "stdout 必须是 JSON 对象: " + excerpt(text, 200));
        assertTrue(stripped.endsWith("}"), "stdout 不能有多余尾文本: " + excerpt(text, 200));
        JsonNode node;
        try (JsonParser parser = Json.mapper().createParser(text)) {
            node = Json.mapper().readTree(parser);
            assertNotNull(node, "stdout 必须包含一个 JSON 对象");
            assertNull(parser.nextToken(), "stdout 只能有一个 JSON 对象，不能有多余文本");
        }
        assertTrue(node.isObject(), "stdout 顶层必须是 JSON 对象");
        return node;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 必须提供 SHA-256", ex);
        }
    }

    private static String excerpt(String text, int max) {
        String oneLine = text.replace("\r", "").replace("\n", "\\n");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static void deleteTree(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(RealWorkerProcessIT::deleteQuietly);
        } catch (IOException ignored) {
            // 兜底清理：失败转储残留不应把测试结论从红改绿，但也不该污染其他用例。
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 同 deleteTree
        }
    }
}
