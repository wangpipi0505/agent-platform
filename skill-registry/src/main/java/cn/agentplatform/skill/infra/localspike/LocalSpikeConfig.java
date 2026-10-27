package cn.agentplatform.skill.infra.localspike;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * IN-09 spike（路线二 LOCAL 服务生命周期预研）最小配置集。
 *
 * <p>spike 边界：只验证进程托管语义本身，不做 manifest 解析、不接 skill_version 契约
 * （那是 AP-S05 正式项）。正式实现将把本配置替换为 @ConfigurationProperties +
 * manifest 驱动的启动参数；本类仅证明"最小配置面足够跑通五条能力"。
 *
 * @param javaPath              子进程 java 可执行文件。算法 jar 是 Java 8 字节码而平台运行在 JDK21，
 *                              必须显式指向 JDK8 的 java.exe——设计输入：LOCAL 技能的运行时路径是一等配置，
 *                              不能默认继承平台 JVM。
 * @param jarPath               Spring Boot fat jar 路径（launchArgs 为 null 时使用）。
 * @param workDir               子进程工作目录。visibility-trajectory 的载荷里 csvPath 是相对路径，
 *                              必须能解析到算法仓库根——设计输入：工作目录必须显式配置，不能默认继承平台进程。
 * @param port                  子进程监听端口；启动前做端口冲突预检（本机 18090 有 owner 实例，spike 用 18091-18095）。
 * @param host                  子进程绑定地址；沿用平台约定只绑 127.0.0.1（--server.address）。
 * @param healthPath            健康端点路径，返回体 "OK" 即就绪。
 * @param searchPath            业务端点路径（spike 用 visibility/search 验证真实调用）。
 * @param startupTimeout        就绪判定超时：超时仍未健康则判启动失败并回收进程。
 * @param pollInterval          就绪轮询间隔。
 * @param callTimeout           单次业务 HTTP 调用超时。
 * @param watchdogInterval      健康巡检间隔。
 * @param failuresBeforeRestart 连续多少次健康失败触发自动重启（防网络抖动误杀）。
 * @param maxAutoRestarts       自动重启次数上限，超过则判 FAILED 停止重试（防崩溃循环拖垮平台）。
 * @param launchArgs            spike 测试保留：覆盖 [-jar, jarPath, --server.port=…] 的启动参数，
 *                              用于无算法 jar 的假进程失败路径测试；正式实现由 manifest 决定命令行。
 * @param logFile               子进程合并输出落盘文件（诊断与"进程早退"定位用）。
 */
public record LocalSpikeConfig(
        String javaPath,
        String jarPath,
        String workDir,
        int port,
        String host,
        String healthPath,
        String searchPath,
        Duration startupTimeout,
        Duration pollInterval,
        Duration callTimeout,
        Duration watchdogInterval,
        int failuresBeforeRestart,
        int maxAutoRestarts,
        List<String> launchArgs,
        Path logFile) {

    public static LocalSpikeConfig defaults(String javaPath, String jarPath, String workDir, int port) {
        return new LocalSpikeConfig(
                javaPath,
                jarPath,
                workDir,
                port,
                "127.0.0.1",
                "/api/orbit/health",
                "/api/orbit/visibility/search",
                Duration.ofSeconds(120),
                Duration.ofMillis(500),
                Duration.ofSeconds(120),
                Duration.ofMillis(500),
                1,
                3,
                null,
                Path.of(System.getProperty("java.io.tmpdir"), "agent-localspike-" + port + ".log"));
    }

    /** spike 便利方法：只覆盖启动参数（失败路径测试用假进程）。 */
    public LocalSpikeConfig withLaunchArgs(List<String> args) {
        return new LocalSpikeConfig(javaPath, jarPath, workDir, port, host, healthPath, searchPath,
                startupTimeout, pollInterval, callTimeout, watchdogInterval, failuresBeforeRestart,
                maxAutoRestarts, List.copyOf(args), logFile);
    }

    /** spike 便利方法：只覆盖就绪超时（失败路径测试要短超时）。 */
    public LocalSpikeConfig withStartupTimeout(Duration d) {
        return new LocalSpikeConfig(javaPath, jarPath, workDir, port, host, healthPath, searchPath,
                d, pollInterval, callTimeout, watchdogInterval, failuresBeforeRestart,
                maxAutoRestarts, launchArgs, logFile);
    }

    /** spike 便利方法：只覆盖轮询间隔（失败路径测试要快轮询）。 */
    public LocalSpikeConfig withPollInterval(Duration d) {
        return new LocalSpikeConfig(javaPath, jarPath, workDir, port, host, healthPath, searchPath,
                startupTimeout, d, callTimeout, watchdogInterval, failuresBeforeRestart,
                maxAutoRestarts, launchArgs, logFile);
    }

    public String healthUrl() {
        return "http://" + host + ":" + port + healthPath;
    }

    public String searchUrl() {
        return "http://" + host + ":" + port + searchPath;
    }

    /** 组装子进程命令行；host 显式传入 --server.address，避免算法默认绑 0.0.0.0。 */
    public List<String> processCommand() {
        List<String> command = new ArrayList<>();
        command.add(javaPath);
        if (launchArgs != null) {
            command.addAll(launchArgs);
        } else {
            command.add("-jar");
            command.add(jarPath);
            command.add("--server.port=" + port);
            command.add("--server.address=" + host);
        }
        return List.copyOf(command);
    }
}
