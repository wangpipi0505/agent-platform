package cn.agentplatform.skill.infra.localspike;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * IN-09 spike：LOCAL 算法子进程托管器（生命周期：start / health / auto-restart / shutdown-hook）。
 *
 * <p>spike 边界：验证平台托管本地算法进程的可行性语义，不是 AP-S05 的正式实现。
 * 正式实现需补：manifest 驱动配置、skill_version 契约、PG 状态持久化、多实例与并发度策略、
 * 结构化日志采集；并把 Windows 专属的 taskkill 换成可移植的进程树终止方案。
 *
 * <p>设计不变量：
 * <ul>
 *   <li>子进程必须用显式 JDK8 路径启动（算法是 Java 8 字节码，平台是 JDK21）。</li>
 *   <li>就绪判定 = 健康端点 200 且返回体 "OK"；连接拒绝/非 200/超时一律视为不健康。</li>
 *   <li>平台 JVM 退出时（含正常停服与崩溃退出）必须终止全部子进程，否则 Windows 上
 *       留孤儿进程占端口——shutdown hook + 显式 shutdown() 双保险，kill 用 taskkill /T /F
 *       杀整棵进程树，destroy() 兜底。</li>
 *   <li>自动重启有次数上限，防止"崩溃循环"拖垮平台。</li>
 * </ul>
 */
public class LocalProcessManager {

    public enum State { NEW, STARTING, READY, UNHEALTHY, RESTARTING, STOPPED, FAILED }

    private final LocalSpikeConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /** 当前活动进程；历史进程进 spawned 以便 shutdown 时全部回收（杀树/兜底 destroy 双保险）。 */
    private volatile Process process;
    private final List<Process> spawned = new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile long pid = -1;
    private volatile long coldStartMillis = -1;
    private volatile Instant spawnedAt;
    private volatile Instant lastUnhealthyAt;
    private volatile long lastRecoveryMillis = -1;
    private final AtomicInteger autoRestartCount = new AtomicInteger();
    private volatile boolean watchdogRunning;
    private volatile Thread watchdog;

    public LocalProcessManager(LocalSpikeConfig config) {
        this.config = config;
    }

    /** 启动子进程。只做拉起，不等待就绪；就绪等待见 {@link #awaitReady()}。返回子进程 PID。 */
    public synchronized long start() throws IOException {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("已有存活实例 pid=" + pid + "，禁止重复启动");
        }
        if (!Files.isRegularFile(Path.of(config.javaPath()))) {
            throw new IllegalArgumentException("javaPath 不存在: " + config.javaPath());
        }
        // 端口冲突预检：失败即抛，不拉起进程（否则算法会静默退出，只能靠超时兜底定位）
        if (!LocalSpikePorts.isPortFree(config.port())) {
            throw new LocalSpikeException.PortInUseException(
                    "端口 " + config.port() + " 已被占用，启动预检失败（未拉起进程）");
        }
        if (config.launchArgs() == null && !Files.isRegularFile(Path.of(config.jarPath()))) {
            throw new IllegalArgumentException("jarPath 不存在: " + config.jarPath());
        }
        Files.createDirectories(config.logFile().getParent());
        ProcessBuilder builder = new ProcessBuilder(config.processCommand());
        builder.directory(new File(config.workDir()));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(config.logFile().toFile()));
        spawnedAt = Instant.now();
        process = builder.start();
        spawned.add(process);
        pid = process.pid();
        state.set(State.STARTING);
        return pid;
    }

    /**
     * 轮询健康端点直到就绪，返回冷启动耗时（毫秒）。
     * 失败语义：进程早退 → {@link LocalSpikeException.ProcessExitedException}；
     * 超时 → {@link LocalSpikeException.StartupTimeoutException}，并回收进程防孤儿。
     */
    public synchronized long awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        Instant t0 = spawnedAt != null ? spawnedAt : Instant.now();
        while (true) {
            Process current = process;
            if (current == null || !current.isAlive()) {
                int exit = current == null ? -1 : exitCodeOf(current);
                state.set(State.FAILED);
                throw new LocalSpikeException.ProcessExitedException(
                        "子进程在就绪前退出: pid=" + pid + " exitCode=" + exit
                                + "，日志尾部: " + logTail(),
                        exit);
            }
            if (probe()) {
                coldStartMillis = Duration.between(t0, Instant.now()).toMillis();
                state.set(State.READY);
                return coldStartMillis;
            }
            if (System.nanoTime() > deadline) {
                killTree(current);
                state.set(State.FAILED);
                throw new LocalSpikeException.StartupTimeoutException(
                        "等待就绪超时(" + config.startupTimeout() + "): " + config.healthUrl()
                                + "，日志尾部: " + logTail());
            }
            Thread.sleep(config.pollInterval().toMillis());
        }
    }

    /** 单次健康检查（对外语义：连接拒绝/非 200/超时 = 不健康），不健康时同步状态位。 */
    public boolean isHealthy() {
        boolean healthy = probe();
        if (!healthy && state.get() == State.READY) {
            lastUnhealthyAt = Instant.now();
            state.set(State.UNHEALTHY);
        }
        return healthy;
    }

    /** 崩溃/不健康后的恢复入口：杀残留进程树 → 等端口释放 → 重新拉起 → 等就绪。返回新实例冷启动耗时。 */
    public synchronized long restart() throws Exception {
        state.set(State.RESTARTING);
        Process current = process;
        if (current != null && current.isAlive()) {
            killTree(current);
        }
        waitPortFree(config.port(), Duration.ofSeconds(15));
        try {
            start();
            long ms = awaitReady();
            lastRecoveryMillis = lastUnhealthyAt == null
                    ? -1
                    : Duration.between(lastUnhealthyAt, Instant.now()).toMillis();
            return ms;
        } catch (Exception e) {
            state.set(State.FAILED);
            throw e;
        }
    }

    /** 终止全部曾拉起的子进程并停巡检。shutdown hook 与测试清理共用此入口。 */
    public synchronized void shutdown() {
        watchdogRunning = false;
        if (watchdog != null) {
            watchdog.interrupt();
        }
        for (Process p : spawned) {
            if (p.isAlive()) {
                killTree(p);
            }
        }
        spawned.clear();
        state.set(State.STOPPED);
    }

    /** 注册 JVM 退出钩子（双保险之一）：平台进程无论正常停服还是崩溃退出都回收子进程。 */
    public Thread installShutdownHook() {
        Thread hook = new Thread(this::shutdown, "local-spike-shutdown-" + config.port());
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /** 启动健康巡检守护线程：连续失败达到阈值即自动重启（验证目标 3 的"托管器检测并拉起"）。 */
    public synchronized void startWatchdog() {
        if (watchdogRunning) {
            return;
        }
        watchdogRunning = true;
        watchdog = new Thread(this::watchLoop, "local-spike-watchdog-" + config.port());
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void watchLoop() {
        int misses = 0;
        while (watchdogRunning) {
            try {
                Thread.sleep(config.watchdogInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (state.get() != State.READY) {
                continue;
            }
            if (probe()) {
                misses = 0;
                continue;
            }
            if (lastUnhealthyAt == null) {
                lastUnhealthyAt = Instant.now();
            }
            state.set(State.UNHEALTHY);
            if (++misses < config.failuresBeforeRestart()) {
                continue;
            }
            misses = 0;
            if (autoRestartCount.incrementAndGet() > config.maxAutoRestarts()) {
                state.set(State.FAILED);
                watchdogRunning = false;
                return;
            }
            try {
                restart();
            } catch (Exception e) {
                state.set(State.FAILED);
                watchdogRunning = false;
                return;
            }
        }
    }

    /** 健康探测（不改动状态位）：GET 健康端点，200 且返回体 "OK" 才算健康。 */
    private boolean probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.healthUrl()))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && "OK".equals(response.body().trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** Windows 语义：taskkill /T /F 杀整棵进程树，destroy()/destroyForcibly() 兜底。 */
    private void killTree(Process target) {
        try {
            Process kill = new ProcessBuilder(
                            "taskkill", "/T", "/F", "/PID", String.valueOf(target.pid()))
                    .redirectErrorStream(true)
                    .start();
            kill.getInputStream().readAllBytes();
            kill.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // taskkill 失败（如进程已死）不阻断；下方 waitFor/destroy 兜底
        }
        try {
            if (!target.waitFor(5, TimeUnit.SECONDS)) {
                target.destroy();
                if (!target.waitFor(5, TimeUnit.SECONDS)) {
                    target.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }

    private void waitPortFree(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!LocalSpikePorts.isPortFree(port)) {
            if (System.nanoTime() > deadline) {
                throw new LocalSpikeException.PortInUseException(
                        "重启前等待端口 " + port + " 释放超时(" + timeout + ")");
            }
            Thread.sleep(200);
        }
    }

    private int exitCodeOf(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** 读子进程合并输出尾部用于异常信息定位（spike 不做结构化日志采集）。 */
    private String logTail() {
        try {
            byte[] all = Files.readAllBytes(config.logFile());
            int from = Math.max(0, all.length - 2048);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            return "(无日志: " + config.logFile() + ")";
        }
    }

    public State state() {
        return state.get();
    }

    public long pid() {
        return pid;
    }

    public long coldStartMillis() {
        return coldStartMillis;
    }

    public boolean isProcessAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    public Instant lastUnhealthyAt() {
        return lastUnhealthyAt;
    }

    /** 最近一次自动重启从"判定不健康"到"重新就绪"的耗时；无数据为 -1。 */
    public long lastRecoveryMillis() {
        return lastRecoveryMillis;
    }

    public int autoRestartCount() {
        return autoRestartCount.get();
    }

    public LocalSpikeConfig config() {
        return config;
    }
}
