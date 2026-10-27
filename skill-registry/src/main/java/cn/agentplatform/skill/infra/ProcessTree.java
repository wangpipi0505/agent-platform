package cn.agentplatform.skill.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 进程启动与“整棵进程树”终止的共享原语（PLAT-21）。
 *
 * <p>为什么 Windows 必须用 {@code taskkill /T /F}：JDK 的 {@link Process#destroyForcibly()}
 * 只终止直接子进程；算法 worker 常常再启动解释器/运行时子进程（例如 Java、Python 的
 * 包装脚本），父进程被单独杀掉后，孙子进程仍持有端口、日志文件与工作目录，变成孤儿并
 * 持续占用资源。{@code /T} 让 taskkill 沿父子关系递归终止整棵树，{@code /F} 强制结束，
 * 避免 Ctrl+C 式软终止被忽略。非 Windows 上没有等价的内置命令，先递归 {@code destroy()}
 * 子进程，超时后再逐级 {@code destroyForcibly()}。
 *
 * <p>为什么先快照 PID 再杀：父进程一旦退出，操作系统可能立即回收其句柄，
 * {@code Process.descendants()} / {@code ProcessHandle.children()} 就再也枚举不到原来的
 * 子树；因此必须趁父进程仍存活时把后代句柄收集到列表，随后即使父进程先死也能按快照
 * 逐个终止。快照顺序反转后即是“先子后父”，避免父进程先死导致子进程被重新挂到 init
 * 而失去终止目标。
 *
 * <p>{@link #builder(List, Path, Map)} 的 {@code environment == null} 表示继承父进程环境，
 * 保持既有服务生命周期语义；{@link ProcessAdapter} 必须传入非 null（可为空）映射，
 * 以白名单方式构造最小环境，不把编排器进程的全部环境变量泄露给算法进程。
 */
public final class ProcessTree {

    private static final long TASKKILL_WAIT_SECONDS = 10;

    private ProcessTree() {}

    /**
     * 构造 {@link ProcessBuilder}：argv 原样传入（不经过 shell），cwd 由调用方指定，
     * stderr 不合并到 stdout。
     *
     * @param environment null 表示继承父进程环境；非 null 表示清空后注入最小必需变量与
     *     该映射（即白名单模式）
     */
    public static ProcessBuilder builder(
            List<String> command, Path workingDirectory, Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.redirectErrorStream(false);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        if (environment != null) {
            Map<String, String> child = builder.environment();
            child.clear();
            child.putAll(minimalEnvironment());
            child.putAll(environment);
        }
        return builder;
    }

    /** 继承父进程环境的构造器，供服务生命周期沿用既有行为。 */
    public static ProcessBuilder builder(List<String> command, Path workingDirectory) {
        return builder(command, workingDirectory, null);
    }

    /** 启动进程；IOException 原样抛给调用方，保持 ServiceLifecycle 既有错误映射（500）。 */
    public static Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    /** 等待退出；返回 false 表示超时，调用方必须随后 {@link #killTree(Process)}。 */
    public static boolean awaitExit(Process process, Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 终止整棵树并等待父进程收敛。保持 ServiceLifecycle 原有节奏：
     * taskkill（Windows）或先子后父 destroy，然后 3s → destroy，2s → destroyForcibly。
     */
    public static void killTree(Process process) {
        if (process == null || !process.isAlive()) return;
        if (isWindows()) {
            taskkill(process.pid());
        } else {
            // 同样先快照再动手；反转后先子后父，父进程不会被提前回收。
            List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
            Collections.reverse(descendants);
            descendants.forEach(ProcessHandle::destroy);
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * 终止以 {@code handle} 为根的进程树。先在句柄仍有效时快照后代（此时父进程还活着），
     * 再交给 taskkill /T 或反向逐个 destroy，最后兜底 destroyForcibly。
     */
    public static void killTree(ProcessHandle handle) {
        if (handle == null || !handle.isAlive()) return;

        // 快照必须发生在父进程退出之前：父进程一死，children()/descendants() 就枚举不到了。
        List<ProcessHandle> descendants = new ArrayList<>(handle.descendants().toList());
        Collections.reverse(descendants); // 先子后父，避免父先死使子进程被重新挂接

        if (isWindows()) {
            taskkill(handle.pid());
        } else {
            descendants.forEach(ProcessHandle::destroy);
        }
        handle.destroy();
        if (handle.isAlive()) handle.destroyForcibly();
    }

    /** 与 taskkill /T /F 等价；失败时保留 destroy 兜底，调用方无感。 */
    private static void taskkill(long pid) {
        Process kill = null;
        try {
            kill =
                    new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                            .redirectErrorStream(true)
                            .start();
            // 必须排空输出，否则 taskkill 可能因管道写满而阻塞。
            kill.getInputStream().readAllBytes();
            kill.waitFor(TASKKILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // taskkill 不可用时由调用方的 destroy/destroyForcibly 兜底。
        } finally {
            if (kill != null && kill.isAlive()) kill.destroyForcibly();
        }
    }

    /**
     * 子进程最小必需环境：只从父进程按名单逐个读取，绝不整体复制 {@code System.getenv()}。
     * 不含 JAVA_HOME、CLASSPATH、HTTP(S)_PROXY 等非必需变量，避免凭据/代理/构建路径泄露。
     */
    public static Map<String, String> minimalEnvironment() {
        List<String> names =
                isWindows()
                        ? List.of(
                                "SYSTEMROOT",
                                "SYSTEMDRIVE",
                                "WINDIR",
                                "PATH",
                                "PATHEXT",
                                "TEMP",
                                "TMP",
                                "COMSPEC",
                                "NUMBER_OF_PROCESSORS",
                                "PROCESSOR_ARCHITECTURE",
                                "PROCESSOR_IDENTIFIER",
                                "OS",
                                "USERPROFILE",
                                "APPDATA",
                                "LOCALAPPDATA",
                                "PROGRAMDATA")
                        : List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "OS");
        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : names) {
            String value = parentEnvironment(name);
            if (value != null && !value.isEmpty()) environment.put(name, value);
        }
        return environment;
    }

    /** Windows 环境变量名大小写不敏感；逐个按名读取时统一走忽略大小写匹配。 */
    private static String parentEnvironment(String name) {
        String direct = System.getenv(name);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
