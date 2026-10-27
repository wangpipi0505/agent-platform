package cn.agentplatform.skill.infra.localspike;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * IN-09 spike 真实生命周期验证（五条验证目标按序执行并打印 [IN-09-EVIDENCE] 耗时数据）。
 *
 * <p>门控：AGENT_SPIKE_JDK8=JDK8 java.exe 路径才执行（算法 jar 是 Java 8 字节码，平台是 JDK21）。
 * 可选覆盖：AGENT_SPIKE_JAR / AGENT_SPIKE_WORKDIR / AGENT_SPIKE_PAYLOAD。
 * 端口从 18091-18095 择空（18090 是 owner 实例，禁止触碰）。
 * 防孤儿双保险：@AfterAll 显式 shutdown() + JVM shutdown hook。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_SPIKE_JDK8", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalSpikeLifecycleLiveTest {

    private static final String JDK8 = System.getenv("AGENT_SPIKE_JDK8");
    private static final String JAR = envOrDefault(
            "AGENT_SPIKE_JAR", "G:/Code/Code-V3/visibility-trajectory/target/alg-toolkit-1.0.0.jar");
    private static final String WORKDIR = envOrDefault("AGENT_SPIKE_WORKDIR", "G:/Code/Code-V3/visibility-trajectory");
    private static final Path PAYLOAD = Path.of(envOrDefault(
            "AGENT_SPIKE_PAYLOAD",
            "G:/Code/Code-V3/visibility-trajectory/examples/orbit_visibility_multi_payload_default.json"));

    static LocalProcessManager manager;
    static LocalSpikeInvoker invoker;
    static Thread hook;
    static int port;
    static String payload;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void boot() throws Exception {
        assumeRegularFile(JDK8, "AGENT_SPIKE_JDK8 指向的 java.exe 不存在");
        assumeRegularFile(JAR, "算法 jar 不存在: " + JAR);
        assumeRegularFile(PAYLOAD.toString(), "示例请求不存在: " + PAYLOAD);
        payload = Files.readString(PAYLOAD, StandardCharsets.UTF_8);
        port = LocalSpikePorts.findFree(18091, 18095);
        manager = new LocalProcessManager(LocalSpikeConfig.defaults(JDK8, JAR, WORKDIR, port));
        invoker = new LocalSpikeInvoker(manager.config());
        manager.startWatchdog();
        hook = manager.installShutdownHook();
        evidence("实例配置: port=%d jar=%s workdir=%s jdk8=%s".formatted(port, JAR, WORKDIR, JDK8));
    }

    @AfterAll
    static void teardown() throws Exception {
        if (manager != null) {
            manager.shutdown();
        }
        if (invoker != null) {
            invoker.close();
        }
        if (hook != null) {
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    /** 验证目标 1：平台拉起子进程并轮询到就绪，记录冷启动耗时；验证目标 2 前置：就绪后健康检查通过。 */
    @Test
    @Order(1)
    void coldStartAndReadyHealth() throws Exception {
        long pid = manager.start();
        assertTrue(pid > 0, "应拿到子进程 PID");
        assertEquals(LocalProcessManager.State.STARTING, manager.state());
        long coldStartMillis = manager.awaitReady();
        assertEquals(LocalProcessManager.State.READY, manager.state());
        assertTrue(manager.isHealthy(), "就绪后健康检查应通过");
        long probeStart = System.nanoTime();
        assertTrue(manager.isHealthy());
        long probeMillis = (System.nanoTime() - probeStart) / 1_000_000;
        evidence("目标1-冷启动耗时(ms)=" + coldStartMillis + " pid=" + pid);
        evidence("目标2-健康检查单次延迟(ms)=" + probeMillis);
    }

    /** 验证目标 5：两个真实 visibility/search 并发提交，串行排队执行（区间不重叠、FIFO）且结果都符合契约。 */
    @Test
    @Order(2)
    void concurrentCallsExecuteSeriallyWithCorrectResults() throws Exception {
        assertEquals(LocalProcessManager.State.READY, manager.state());
        long submitStart = System.currentTimeMillis();
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> invoker.call(payload));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> invoker.call(payload));
        String firstBody = first.get(8, TimeUnit.MINUTES);
        String secondBody = second.get(8, TimeUnit.MINUTES);
        for (String body : List.of(firstBody, secondBody)) {
            JsonNode tree = MAPPER.readTree(body);
            assertTrue(tree.hasNonNull("referenceUtc"), "响应应含 referenceUtc");
            assertTrue(tree.has("allResults") && tree.get("allResults").isArray()
                            && !tree.get("allResults").isEmpty(),
                    "响应应含非空 allResults 数组");
        }
        List<LocalSpikeInvoker.Interval> log = invoker.executionLog();
        assertTrue(log.size() >= 2, "应记录两次执行区间");
        LocalSpikeInvoker.Interval firstInterval = log.get(log.size() - 2);
        LocalSpikeInvoker.Interval secondInterval = log.get(log.size() - 1);
        // 串行不变量：第一段结束 <= 第二段开始（时间不重叠），且先提交者先执行（FIFO）
        assertTrue(firstInterval.end().compareTo(secondInterval.start()) <= 0,
                "执行区间重叠，违反实例级串行不变量: " + firstInterval + " vs " + secondInterval);
        assertTrue(firstInterval.seq() < secondInterval.seq(), "应按提交顺序 FIFO 执行");
        evidence("目标5-串行调用: #1(seq=%d, 耗时=%dms) #2(seq=%d, 耗时=%dms) 并发提交总耗时=%dms"
                .formatted(firstInterval.seq(), firstInterval.millis(),
                        secondInterval.seq(), secondInterval.millis(),
                        System.currentTimeMillis() - submitStart));
    }

    /** 验证目标 3：外部 taskkill 杀死进程后，托管器检测不健康并自动重新拉起恢复就绪。 */
    @Test
    @Order(3)
    void externalKillIsDetectedAndAutoRestarted() throws Exception {
        assertEquals(LocalProcessManager.State.READY, manager.state());
        long oldPid = manager.pid();
        Process kill = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(oldPid))
                .redirectErrorStream(true)
                .start();
        assertEquals(0, kill.waitFor(), "taskkill 应成功杀死旧进程");
        evidence("目标3-外部 taskkill 完成 pid=" + oldPid + " @ " + java.time.Instant.now());
        // 托管器 watchdog 自动检测并拉起：等待状态回到 READY 且 PID 换新
        long deadline = System.currentTimeMillis() + 240_000;
        while (manager.state() != LocalProcessManager.State.READY || manager.pid() == oldPid) {
            assertTrue(System.currentTimeMillis() < deadline,
                    "自动恢复超时：state=" + manager.state() + " pid=" + manager.pid());
            Thread.sleep(250);
        }
        assertNotEquals(oldPid, manager.pid(), "恢复后应是新进程");
        assertTrue(manager.isHealthy(), "自动恢复后健康检查应通过");
        assertTrue(manager.lastUnhealthyAt() != null, "应记录不健康判定时刻");
        evidence("目标3-自动重启次数=" + manager.autoRestartCount()
                + " 不健康判定@=" + manager.lastUnhealthyAt()
                + " 检测+恢复就绪耗时(ms)=" + manager.lastRecoveryMillis()
                + " 新实例冷启动耗时不适用(restart 内含 awaitReady)");
    }

    /** 验证目标 4：shutdown（即 shutdown hook 同一入口）后子进程被杀、端口释放。 */
    @Test
    @Order(4)
    void shutdownKillsChildrenAndReleasesPort() throws Exception {
        assertEquals(LocalProcessManager.State.READY, manager.state());
        long pid = manager.pid();
        long start = System.currentTimeMillis();
        manager.shutdown();
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(manager.isProcessAlive(), "shutdown 后托管进程应已终止");
        assertEquals(LocalProcessManager.State.STOPPED, manager.state());
        // tasklist 独立核验进程确实消失（不只信 Process.isAlive）
        Process tasklist = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV")
                .redirectErrorStream(true)
                .start();
        String output = new String(tasklist.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        tasklist.waitFor();
        assertFalse(output.contains("java.exe"), "tasklist 仍能看到被托管进程: " + output);
        // 端口释放独立核验
        assertTrue(LocalSpikePorts.isPortFree(port), "shutdown 后端口 " + port + " 应释放");
        evidence("目标4-shutdown→进程终止+端口释放耗时(ms)=" + elapsed + " pid=" + pid);
    }

    private static void assumeRegularFile(String path, String message) {
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw new org.opentest4j.TestAbortedException(message);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static void evidence(String message) {
        System.out.println("[IN-09-EVIDENCE] " + message);
    }
}
