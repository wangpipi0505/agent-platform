package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PLAT-21 执行原语的行为测试：真实起进程，不用 mock。
 *
 * <p>每个用例都断言允许根下不留 {@code call-*} 目录，确保 finally 清理生效。
 */
@Timeout(120)
class ProcessAdapterTest {

    private static final Set<String> MINIMAL_ENV_NAMES =
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
                    "USERPROFILE",
                    "APPDATA",
                    "LOCALAPPDATA",
                    "PROGRAMDATA",
                    "OS",
                    "HOME",
                    "TMPDIR",
                    "LANG",
                    "LC_ALL");

    @TempDir Path temp;
    Path root;
    Path outside;
    ProcessAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temp.resolve("allowed-root")).toRealPath();
        outside = Files.createDirectory(temp.resolve("outside-root")).toRealPath();
        adapter = new ProcessAdapter(true, root);
    }

    @AfterEach
    void cleanUp() {
        assertNoCallDirs();
    }

    @Test
    void executesAndReturnsZeroExit() {
        ProcessAdapter.Result result = adapter.execute(ProcessContract.parse(contract("ok")));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdoutTail().contains("ok-stdout"));
        assertTrue(result.stdoutTail().contains("中文-正常退出"));
        assertEquals("", result.stderrTail());
        assertFalse(result.truncStdout());
        assertFalse(result.truncStderr());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void nonZeroExitCarriesStderrTail() {
        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(contract("exit3"))));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_EXITED", ex.code());
        assertTrue(ex.getMessage().contains("boom-stderr"), "异常消息应带 stderr 尾部");
        assertTrue(ex.getMessage().contains("3"), "异常消息应带 exitCode");
    }

    @Test
    void timeoutKillsTheChildProcess() throws Exception {
        Path pidFile = temp.resolve("timeout-pid.txt");
        ObjectNode json = contract("sleep", pidFile.toString(), "10000");
        json.put("timeoutMs", 800);

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(json)));

        assertEquals(504, ex.status());
        assertEquals("PROCESS_TIMEOUT", ex.code());
        long pid = awaitPidFile(pidFile, Duration.ofSeconds(15));
        awaitProcessGone(pid, Duration.ofSeconds(15));
    }

    @Test
    void floodIsTruncatedAtOneMiB() {
        ProcessAdapter.Result result = adapter.execute(ProcessContract.parse(contract("flood")));

        assertEquals(0, result.exitCode());
        assertTrue(result.truncStdout(), "2 MiB 输出必须标记截断");
        assertEquals(
                ProcessContract.DEFAULT_MAX_OUTPUT_BYTES,
                result.stdoutTail().getBytes(StandardCharsets.UTF_8).length);
        assertFalse(result.truncStderr());
        assertEquals("", result.stderrTail());
    }

    @Test
    void stderrFloodIsAlsoTruncatedAtOneMiB() {
        ProcessAdapter.Result result = adapter.execute(ProcessContract.parse(contract("flooderr")));

        assertEquals(0, result.exitCode());
        assertTrue(result.truncStderr(), "stderr 2 MiB 也必须标记截断");
        assertEquals(
                ProcessContract.DEFAULT_MAX_OUTPUT_BYTES,
                result.stderrTail().getBytes(StandardCharsets.UTF_8).length);
        assertFalse(result.truncStdout());
        assertEquals("", result.stdoutTail());
    }

    @Test
    void passesArgumentsWithoutShell() {
        // 用 shell 元字符验证“不过 shell”：若经过 sh/cmd，$HOME 或 %PATH% 会被展开。
        // 注意不测内嵌双引号：Windows JDK 的 ProcessImpl 本身会丢弃它（JDK 已知限制），
        // 与是否过 shell 无关；空格、中文、$、% 已足以证明 argv 是原样传递的。
        ObjectNode json = contract("echoargs", "a b", "中文 参数", "$HOME;%PATH%");

        ProcessAdapter.Result result = adapter.execute(ProcessContract.parse(json));

        List<String> lines = result.stdoutTail().lines().toList();
        assertTrue(lines.contains("argc=3"), "实际输出: " + result.stdoutTail());
        assertTrue(lines.contains("arg=a b"), "含空格参数必须原样到达，实际输出: " + result.stdoutTail());
        assertTrue(lines.contains("arg=中文 参数"), "中文参数必须原样到达，实际输出: " + result.stdoutTail());
        assertTrue(
                lines.contains("arg=$HOME;%PATH%"),
                "shell 元字符不得被解释，实际输出: " + result.stdoutTail());
        String cwdLine =
                lines.stream().filter(line -> line.startsWith("cwd=")).findFirst().orElseThrow();
        Path cwd = Path.of(cwdLine.substring("cwd=".length()));
        assertTrue(cwd.startsWith(root), "cwd 必须在允许根内: " + cwd);
        assertTrue(
                cwd.getFileName().toString().startsWith("call-"),
                "cwd 必须是允许根下新建的 callDir: " + cwd);
    }

    @Test
    void chineseAndSpaceInWorkingDirectoryArePreserved() throws IOException {
        // 路径本身含中文与空格：验证 cwd 不是被 shell/编码层改写的拼接字符串。
        Path chineseRoot = Files.createDirectories(temp.resolve("中文 允许根")).toRealPath();
        ProcessAdapter local = new ProcessAdapter(true, chineseRoot);
        ObjectNode json = contract("echoargs", "x");
        json.put("workDirRoot", chineseRoot.toString());

        ProcessAdapter.Result result = local.execute(ProcessContract.parse(json));

        List<String> lines = result.stdoutTail().lines().toList();
        String cwd = lines.stream().filter(line -> line.startsWith("cwd=")).findFirst().orElseThrow();
        assertTrue(cwd.contains("中文 允许根"), "cwd 应保留中文目录名: " + cwd);
        assertTrue(cwd.contains("call-"), "cwd 应是允许根下的 callDir: " + cwd);
        assertTrue(lines.contains("argc=1"), result.stdoutTail());
    }

    @Test
    void disabledSwitchSkipsProcessAndCallDir() {
        ProcessAdapter disabled = new ProcessAdapter(false, root);

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> disabled.execute(ProcessContract.parse(contract("ok"))));

        assertEquals(403, ex.status());
        assertEquals("PROCESS_DISABLED", ex.code());
        assertNoCallDirs();
    }

    @Test
    void childEnvironmentIsWhitelistOnly() {
        String parentOnly = parentOnlyVariable();
        ObjectNode json = contract("env");
        json.putObject("env").put("AGENT_PROCESS_ALLOWED", "yes-中文");

        ProcessAdapter.Result result = adapter.execute(ProcessContract.parse(json));

        Map<String, String> environment = FakeProcessWorker.parseEnvironment(result.stdoutTail());
        assertEquals("yes-中文", environment.get("AGENT_PROCESS_ALLOWED"));
        assertFalse(
                environment.containsKey(parentOnly),
                "父进程变量 " + parentOnly + " 不是白名单，不得透传");
        assertFalse(environment.containsKey("HTTP_PROXY"), "默认不注入代理");
        assertFalse(environment.containsKey("HTTPS_PROXY"), "默认不注入代理");
        assertTrue(
                environment.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("PATH")),
                "最小必需变量 PATH 必须在");
    }

    @Test
    void rejectsWorkDirOutsideAllowedRoot() {
        ObjectNode json = contract("ok");
        json.put("workDirRoot", outside.toString());

        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(json)));

        assertEquals(403, ex.status());
        assertEquals("PROCESS_WORKDIR_DENIED", ex.code());
        assertNoCallDirs();
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count(), "被拒绝的根下不得创建 callDir");
        } catch (IOException ex2) {
            fail("无法列出被拒绝的根: " + ex2);
        }

        ObjectNode traversal = contract("ok");
        traversal.put("workDirRoot", root.resolve("..").resolve("outside-root").toString());
        PlatformException escaped =
                assertThrows(
                        PlatformException.class,
                        () -> adapter.execute(ProcessContract.parse(traversal)));
        assertEquals(403, escaped.status());
        assertEquals("PROCESS_WORKDIR_DENIED", escaped.code());
    }

    /** 模式 + 附加参数 → 契约 JSON；workDirRoot 省略，走构造器注入的允许根。 */
    private ObjectNode contract(String mode, String... extraArgs) {
        String[] workerArgs = new String[1 + extraArgs.length];
        workerArgs[0] = mode;
        System.arraycopy(extraArgs, 0, workerArgs, 1, extraArgs.length);
        ObjectNode json = Json.object();
        ArrayNode command = json.putArray("command");
        FakeProcessWorker.command(workerArgs).forEach(command::add);
        json.put("timeoutMs", 5000);
        return json;
    }

    private void assertNoCallDirs() {
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count(), "callDir 必须在 finally 中删除");
        } catch (IOException ex) {
            fail("无法列出允许根: " + ex);
        }
    }

    private static String parentOnlyVariable() {
        if (System.getenv("JAVA_HOME") != null) return "JAVA_HOME";
        return System.getenv().keySet().stream()
                .filter(name -> !MINIMAL_ENV_NAMES.contains(name.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到非白名单父进程变量用于断言"));
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
        assertFalse(isAlive(pid), "超时后进程 " + pid + " 必须已被杀树");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
