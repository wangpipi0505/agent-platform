package cn.agentplatform.skill.infra.localspike;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * IN-09 spike 失败路径测试（关键失败测试先行，AGENTS.md 约定）。
 * 不依赖 JDK8 与算法 jar（用测试 JVM 自带 java 拉起假进程），始终可跑。
 * 真实 jar 的生命周期验证见 {@link LocalSpikeLifecycleLiveTest}（环境变量门控）。
 */
class LocalSpikeManagerFailurePathTest {

    @TempDir Path tmp;

    private static String testJava() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    /** 端口被占：start() 预检快速失败且不拉起任何进程。 */
    @Test
    void startFailsFastWhenPortOccupied() throws Exception {
        int port = LocalSpikePorts.findFree(18091, 18095);
        ServerSocket blocker = new ServerSocket();
        blocker.setReuseAddress(false);
        blocker.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        try {
            LocalSpikeConfig config = LocalSpikeConfig.defaults(testJava(), "G:/非路径.jar", tmp.toString(), port)
                    .withLaunchArgs(List.of("-version"));
            LocalProcessManager manager = new LocalProcessManager(config);
            LocalSpikeException.PortInUseException ex =
                    assertThrows(LocalSpikeException.PortInUseException.class, manager::start);
            assertTrue(ex.getMessage().contains(String.valueOf(port)), "异常应包含端口号: " + ex.getMessage());
            assertFalse(manager.isProcessAlive(), "预检失败不允许拉起进程");
            assertEquals(LocalProcessManager.State.NEW, manager.state());
        } finally {
            blocker.close();
        }
    }

    /** 进程就绪前退出（如 jar 路径错/JDK 版本不符）：awaitReady 给出明确异常而非干等超时。 */
    @Test
    void processExitBeforeReadyFailsWithClearError() throws Exception {
        int port = LocalSpikePorts.findFree(18091, 18095);
        LocalSpikeConfig config = LocalSpikeConfig.defaults(testJava(), null, tmp.toString(), port)
                .withLaunchArgs(List.of("-version")) // 立即退出，永不监听
                .withStartupTimeout(Duration.ofSeconds(15));
        LocalProcessManager manager = new LocalProcessManager(config);
        manager.start();
        LocalSpikeException.ProcessExitedException ex =
                assertThrows(LocalSpikeException.ProcessExitedException.class, manager::awaitReady);
        assertTrue(ex.getMessage().contains("退出"), "异常语义应指明进程早退: " + ex.getMessage());
        assertEquals(LocalProcessManager.State.FAILED, manager.state());
    }

    /** 启动超时（进程活着但健康端点永远不通）：给出明确异常并回收假死进程，不留孤儿。 */
    @Test
    void startupTimeoutFailsAndReapsDeafProcess() throws Exception {
        Path testClasses = Paths.get("target", "test-classes").toAbsolutePath();
        assumeTestClassesCompiled(testClasses);
        int port = LocalSpikePorts.findFree(18091, 18095);
        LocalSpikeConfig config = LocalSpikeConfig.defaults(testJava(), null, tmp.toString(), port)
                .withLaunchArgs(List.of("-cp", testClasses.toString(),
                        "cn.agentplatform.skill.infra.localspike.FakeDeafMain"))
                .withStartupTimeout(Duration.ofSeconds(3))
                .withPollInterval(Duration.ofMillis(100));
        LocalProcessManager manager = new LocalProcessManager(config);
        manager.start();
        long pid = manager.pid();
        assertTrue(pid > 0);
        LocalSpikeException.StartupTimeoutException ex =
                assertThrows(LocalSpikeException.StartupTimeoutException.class, manager::awaitReady);
        assertTrue(ex.getMessage().contains("超时"), "异常语义应指明启动超时: " + ex.getMessage());
        // 超时路径必须回收进程，防止假死进程变成孤儿
        assertFalse(manager.isProcessAlive(), "超时后假死进程应被回收 pid=" + pid);
        assertTrue(LocalSpikePorts.isPortFree(port), "超时回收后端口应可用（本用例未监听，验证无副作用）");
    }

    /** 空闲端口选择落在指定区间且确实可绑定。 */
    @Test
    void freePortFinderStaysInRange() {
        int port = LocalSpikePorts.findFree(18091, 18095);
        assertTrue(port >= 18091 && port <= 18095, "应从 18091-18095 选端口，实际: " + port);
        assertTrue(LocalSpikePorts.isPortFree(port));
    }

    private static void assumeTestClassesCompiled(Path testClasses) {
        if (!Files.exists(testClasses)) {
            throw new org.opentest4j.TestAbortedException("test-classes 未编译: " + testClasses);
        }
    }
}
