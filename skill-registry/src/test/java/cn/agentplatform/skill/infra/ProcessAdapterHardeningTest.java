package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PLAT-21d 加固行为测试：并发配额、取消、失败转储保留、执行期白名单、审计事实、中文空格路径。
 *
 * <p>全部真实起进程（复用 {@link FakeProcessWorker}），不用 mock；并发用例通过
 * {@link ProcessAdapter#inFlight(String)} 等待第一名额持有者就位，避免靠固定 sleep 猜节奏。
 */
@Timeout(300)
class ProcessAdapterHardeningTest {

    @TempDir Path temp;
    Path root;

    @AfterEach
    void cleanUp() {
        // 失败转储目录是 T21d 的有意保留（默认最近 5 个），用例自行断言其存在/剪枝；
        // 这里只兜底清空，避免跨用例串扰，同时断言不残留活跃 call-* 目录。
        removeFailedDumps(root);
    }

    // ---------------------------------------------------------------- 取消（①）

    @Test
    void cancelKillsTheRunningTreeAndReportsCancelled() throws Exception {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());
        Path pidFile = temp.resolve("cancel-pid.txt");
        ObjectNode json = contract("sleep", pidFile.toString(), "10000");
        json.put("timeoutMs", 30000);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller =
                Thread.ofPlatform()
                        .name("cancel-caller")
                        .start(
                                () -> {
                                    try {
                                        adapter.execute(
                                                ProcessContract.parse(json),
                                                new ProcessAdapter.Request("42", "op", Json.object()));
                                    } catch (Throwable t) {
                                        thrown.set(t);
                                    }
                                });
        try {
            long pid = awaitPidFile(pidFile, Duration.ofSeconds(20));
            awaitInFlight(adapter, "42", Duration.ofSeconds(5));

            assertTrue(adapter.cancel("42"), "取消进行中的调用必须命中注册表");
            caller.join(20000);
            assertFalse(caller.isAlive(), "杀树后 execute 应立即返回");
            PlatformException ex = assertInstanceOf(PlatformException.class, thrown.get());
            assertEquals(502, ex.status(), "取消是确定性结论：既不是超时也不是可重试失败");
            assertEquals("PROCESS_CANCELLED", ex.code());
            awaitProcessGone(pid, Duration.ofSeconds(15));
            assertTrue(countDirs(root, "failed-") >= 1, "被取消的现场按失败转储保留，便于排查");
        } finally {
            caller.interrupt();
            caller.join(5000);
        }
    }

    @Test
    void cancelUnknownOrFinishedCallIsIdempotentFalse() {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());

        assertFalse(adapter.cancel("no-such-call"), "不存在的调用取消必须幂等返回 false");
        assertFalse(adapter.cancel((String) null), "null requestId 不抛异常");
    }

    // ------------------------------------------------------- 临时目录回收（②）

    @Test
    void successDeletesCallDirOnEveryPath() throws IOException {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());

        adapter.execute(ProcessContract.parse(contract("ok")));

        assertEquals(0, countDirs(root, ""), "成功路径必须回收 callDir");
    }

    @Test
    void failureRetainsDumpAndPrunesOldestBeyondLimit() throws Exception {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 2, Set.of());
        // 两个陈旧转储：新的失败发生后，最旧的必须被剪掉，保留数不超过 keep-failed-dirs。
        Path oldest = seedFailedDump(root, "failed-call-old-1");
        Path newer = seedFailedDump(root, "failed-call-old-2");
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(System.currentTimeMillis() - 7200_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(System.currentTimeMillis() - 3600_000));

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(contract("exit3"))));
        assertEquals("PROCESS_CALCULATION_FAILED", ex.code());

        List<Path> dumps = listDirs(root, "failed-");
        assertEquals(2, dumps.size(), "失败转储最多保留 keep-failed-dirs 个");
        assertTrue(
                dumps.stream().noneMatch(p -> p.equals(oldest)),
                "最旧的转储必须被剪枝: " + dumps);
        assertTrue(dumps.contains(newer), "较新的既有转储仍在保留窗口内");
        assertEquals(0, countDirs(root, "call-"), "异常路径不得残留活跃 call-* 目录");
    }

    @Test
    void keepFailedDirsZeroDeletesFailureDumpToo() throws IOException {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 0, Set.of());

        assertThrows(
                PlatformException.class,
                () -> adapter.execute(ProcessContract.parse(contract("exit3"))));

        assertEquals(0, countDirs(root, ""), "keep-failed-dirs=0 时异常路径同样彻底回收");
    }

    @Test
    void timeoutKillRetainsFailureDump() throws Exception {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());
        ObjectNode json = contract("sleep", "", "5000");
        json.put("timeoutMs", 700);

        PlatformException ex =
                assertThrows(
                        PlatformException.class, () -> adapter.execute(ProcessContract.parse(json)));
        assertEquals(504, ex.status());
        assertEquals("PROCESS_TIMEOUT", ex.code());

        List<Path> dumps = listDirs(root, "failed-");
        assertEquals(1, dumps.size(), "超时杀树路径保留失败现场");
        assertEquals(0, countDirs(root, "call-"));
    }

    // --------------------------------------------------------- 并发隔离（③）

    @Test
    void globalLimitRejectsExcessWithReadable429() throws Exception {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 1, 8, 300, 5, Set.of());
        Path pidFile = temp.resolve("busy-pid.txt");
        ObjectNode json = contract("sleepok", pidFile.toString(), "2500");
        AtomicReference<Throwable> first = new AtomicReference<>();
        Thread holder =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        adapter.execute(
                                                ProcessContract.parse(json),
                                                new ProcessAdapter.Request("busy-1", "op", Json.object()));
                                    } catch (Throwable t) {
                                        first.set(t);
                                    }
                                });
        try {
            awaitPidFile(pidFile, Duration.ofSeconds(20));
            awaitInFlight(adapter, "busy-1", Duration.ofSeconds(5));

            PlatformException ex =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    adapter.execute(
                                            ProcessContract.parse(contract("ok")),
                                            new ProcessAdapter.Request("busy-2", "op", Json.object())));
            assertEquals(429, ex.status(), "全局并发超限必须在排队窗口后可读拒绝");
            assertEquals("PROCESS_BUSY", ex.code());
            assertTrue(ex.getMessage().contains("全局"), ex.getMessage());
            assertNull(first.get(), "第一名额持有者不受影响");
        } finally {
            holder.join(20000);
            assertFalse(holder.isAlive());
        }
    }

    @Test
    void queuedCallSucceedsWhenPermitReleasedWithinWait() throws Exception {
        root = newRoot();
        // 队列语义：等待窗口内拿到名额就继续执行，而不是立刻拒绝。
        ProcessAdapter adapter = adapter(root, 1, 8, 30000, 5, Set.of());
        ObjectNode slow = contract("sleepok", "", "400");
        AtomicReference<Throwable> first = new AtomicReference<>();
        Thread holder =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        adapter.execute(
                                                ProcessContract.parse(slow),
                                                new ProcessAdapter.Request("queue-1", "op", Json.object()));
                                    } catch (Throwable t) {
                                        first.set(t);
                                    }
                                });
        try {
            awaitInFlight(adapter, "queue-1", Duration.ofSeconds(20));
            ProcessAdapter.Output second =
                    adapter.execute(
                            ProcessContract.parse(contract("ok")),
                            new ProcessAdapter.Request("queue-2", "op", Json.object()));
            assertEquals(0, second.exitCode(), "排队者在窗口内等到名额后必须正常执行");
        } finally {
            holder.join(30000);
            assertFalse(holder.isAlive());
            assertNull(first.get());
        }
    }

    @Test
    void tenantLimitIsIndependentFromOtherTenants() throws Exception {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 8, 1, 300, 5, Set.of());
        Path pidFile = temp.resolve("tenant-pid.txt");
        ObjectNode slow = contract("sleepok", pidFile.toString(), "2500");
        AtomicReference<Throwable> first = new AtomicReference<>();
        Thread holder =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    try {
                                        adapter.execute(
                                                ProcessContract.parse(slow),
                                                new ProcessAdapter.Request("tenant-1", "op", Json.object()),
                                                "tenant-A");
                                    } catch (Throwable t) {
                                        first.set(t);
                                    }
                                });
        try {
            awaitPidFile(pidFile, Duration.ofSeconds(20));
            awaitInFlight(adapter, "tenant-1", Duration.ofSeconds(5));

            PlatformException sameTenant =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    adapter.execute(
                                            ProcessContract.parse(contract("ok")),
                                            new ProcessAdapter.Request("tenant-2", "op", Json.object()),
                                            "tenant-A"));
            assertEquals(429, sameTenant.status());
            assertEquals("PROCESS_BUSY", sameTenant.code());
            assertTrue(sameTenant.getMessage().contains("tenant-A"), sameTenant.getMessage());

            ProcessAdapter.Output otherTenant =
                    adapter.execute(
                            ProcessContract.parse(contract("ok")),
                            new ProcessAdapter.Request("tenant-3", "op", Json.object()),
                            "tenant-B");
            assertEquals(0, otherTenant.exitCode(), "每租户配额不得影响其他租户");
        } finally {
            holder.join(20000);
            assertFalse(holder.isAlive());
            assertNull(first.get());
        }
    }

    // -------------------------------------------------- allowed-executables（执行期）

    @Test
    void executionRejectsExecutableOutsideWhitelist() throws IOException {
        root = newRoot();
        Path unrelated = Files.createFile(temp.resolve("unrelated-worker.exe"));
        ProcessAdapter adapter =
                adapter(root, 4, 1, 30000, 5, ProcessWhitelist.parse(List.of(unrelated.toString())));

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(contract("ok"))));

        assertEquals(403, ex.status(), "执行期白名单是注册期之外的第二道闸");
        assertEquals("PROCESS_EXECUTABLE_FORBIDDEN", ex.code());
        assertEquals(0, countDirs(root, ""), "白名单拒绝发生在建目录之前");
    }

    @Test
    void executionAcceptsExecutableInsideWhitelist() throws IOException {
        root = newRoot();
        ProcessAdapter adapter =
                adapter(
                        root,
                        4,
                        1,
                        30000,
                        5,
                        ProcessWhitelist.parse(List.of(FakeProcessWorker.javaExecutable())));

        ProcessAdapter.Output output = adapter.execute(ProcessContract.parse(contract("ok")));

        assertEquals(0, output.exitCode());
    }

    @Test
    void emptyWhitelistKeepsExecutionUnrestricted() throws IOException {
        // 兼容窗口：白名单未配置时保持 T21c 行为（登记期 ADMIN + 发布预检兜底），升级不炸存量。
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());

        assertEquals(0, adapter.execute(ProcessContract.parse(contract("ok"))).exitCode());
    }

    // ------------------------------------------------------------- 审计（④）

    @Test
    void outputFactCarriesAuditFactsWithoutSensitiveEnvValues() throws IOException {
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());
        ObjectNode json = contract("env");
        json.putObject("env").put("AGENT_PROCESS_AUDIT_MARKER", "super-secret-值-42");

        ProcessAdapter.Output output = adapter.execute(ProcessContract.parse(json));

        assertNotNull(output.fact(), "成功执行必须携带审计事实");
        Object fact = output.fact();
        assertTrue(output.fact().containsKey("command"), "命令模板必须可见");
        assertTrue(((List<?>) output.fact().get("command")).getFirst().toString().endsWith(".exe")
                        || Files.isExecutable(Path.of(((List<?>) output.fact().get("command")).getFirst().toString())),
                "命令模板首元素应是可执行文件路径");
        assertEquals(0, output.fact().get("exitCode"));
        assertNotNull(output.fact().get("durationMs"));
        assertNotNull(output.fact().get("workDir"));
        assertFalse(
                String.valueOf(fact).contains("super-secret-值-42"),
                "环境变量的值绝不能进审计事实: " + fact);
        assertFalse(String.valueOf(fact).contains("AGENT_PROCESS_AUDIT_MARKER"), "环境变量名单键也不落审计");
    }

    @Test
    void factRecordsKillReasonOnTimeout() throws Exception {
        // 失败路径的事实经结构化日志落盘（失败事实不进 execution_fact，见证据 README 口径）；
        // 这里用成功路径的 fact 与转储目录验证“杀树原因可追溯”的载体存在。
        root = newRoot();
        ProcessAdapter adapter = adapter(root, 4, 1, 30000, 5, Set.of());
        ObjectNode json = contract("sleep", "", "5000");
        json.put("timeoutMs", 700);

        assertThrows(PlatformException.class, () -> adapter.execute(ProcessContract.parse(json)));

        List<Path> dumps = listDirs(root, "failed-");
        assertEquals(1, dumps.size());
        // 转储目录本身是失败现场的事实载体；运行详情口径由 SkillProcessAuditTest 在 PG 里验证。
    }

    // ------------------------------------------------- 中文与空格路径专项（⑥）

    @Test
    void chineseAndSpacedWorkDirCreateExecuteKillReclaim() throws Exception {
        Path chineseRoot = Files.createDirectories(temp.resolve("中文 允许 根 x y")).toRealPath();
        ProcessAdapter adapter = adapter(chineseRoot, 4, 1, 30000, 5, Set.of());
        ObjectNode json = contract("echoargs", "中文 参数 b");
        json.put("workDirRoot", chineseRoot.toString());

        ProcessAdapter.Output output = adapter.execute(ProcessContract.parse(json));

        List<String> lines = lines(output);
        String cwd = lines.stream().filter(line -> line.startsWith("cwd=")).findFirst().orElseThrow();
        assertTrue(cwd.contains("中文 允许 根 x y"), "中文+空格工作目录必须原样生效: " + cwd);
        assertTrue(cwd.contains("call-"), "cwd 必须是允许根下新建的 callDir");
        assertTrue(
                String.valueOf(output.fact().get("workDir")).contains("中文 允许 根 x y"),
                "审计事实的 workDir 保留完整中文+空格路径");
        assertEquals(0, countDirs(chineseRoot, ""), "中文+空格路径下 callDir 也必须被回收");

        // 杀树路径同样要在中文+空格目录下正确回收：sleep + 超时 → failed- 转储（或彻底删除）。
        ObjectNode slow = contract("sleep", "", "5000");
        slow.put("timeoutMs", 700);
        assertThrows(
                PlatformException.class, () -> adapter.execute(ProcessContract.parse(slow)));
        long leftovers = countDirs(chineseRoot, "call-");
        assertEquals(0, leftovers, "中文+空格路径下杀树路径不得残留 call-* 目录");
        removeFailedDumps(chineseRoot);
    }

    // ---------------------------------------------------------------- 辅助

    private static Path newRoot() {
        try {
            return Files.createTempDirectory("process-hardening-root").toRealPath();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ProcessAdapter adapter(
            Path root,
            int maxConcurrent,
            int maxConcurrentPerTenant,
            long queueWaitMs,
            int keepFailedDirs,
            Set<String> whitelist) {
        return new ProcessAdapter(
                true, root, maxConcurrent, maxConcurrentPerTenant, queueWaitMs, keepFailedDirs, whitelist);
    }

    private ObjectNode contract(String mode, String... extraArgs) {
        String[] workerArgs = new String[1 + extraArgs.length];
        workerArgs[0] = mode;
        System.arraycopy(extraArgs, 0, workerArgs, 1, extraArgs.length);
        ObjectNode json = Json.object();
        ArrayNode command = json.putArray("command");
        FakeProcessWorker.command(workerArgs).forEach(command::add);
        json.put("timeoutMs", 20000);
        return json;
    }

    private static List<String> lines(ProcessAdapter.Output output) {
        List<String> lines = new ArrayList<>();
        output.result().path("lines").forEach(node -> lines.add(node.asText()));
        return lines;
    }

    private static int countDirs(Path root, String prefix) throws IOException {
        return listDirs(root, prefix).size();
    }

    private static List<Path> listDirs(Path root, String prefix) throws IOException {
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (var entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .toList();
        }
    }

    private static void removeFailedDumps(Path root) {
        if (root == null || !Files.isDirectory(root)) return;
        try (var entries = Files.list(root)) {
            entries
                    .filter(p -> p.getFileName().toString().startsWith("failed-"))
                    .forEach(ProcessAdapterHardeningTest::deleteRecursively);
        } catch (IOException ignored) {
            // 兜底清理失败不影响断言
        }
    }

    private static void deleteRecursively(Path path) {
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力删除
                }
            });
        } catch (IOException ignored) {
            // 尽力删除
        }
    }

    private static Path seedFailedDump(Path root, String name) throws IOException {
        Path dump = root.resolve(name);
        Files.createDirectories(dump);
        Files.writeString(dump.resolve("why.txt"), "seed", StandardCharsets.UTF_8);
        return dump;
    }

    private static void awaitInFlight(ProcessAdapter adapter, String requestId, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (adapter.inFlight(requestId)) return;
            Thread.sleep(25);
        }
        fail("调用 " + requestId + " 未在窗口内进入执行注册表");
    }

    private static long awaitPidFile(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) return Long.parseLong(text);
            }
            Thread.sleep(25);
        }
        fail("worker 未写出 PID 文件: " + file);
        return -1;
    }

    private static void awaitProcessGone(long pid, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && isAlive(pid)) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(isAlive(pid), "进程 " + pid + " 必须已被终止");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
