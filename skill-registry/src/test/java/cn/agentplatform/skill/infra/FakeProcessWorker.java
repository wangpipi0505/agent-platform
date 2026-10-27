package cn.agentplatform.skill.infra;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测试用假 worker：纯 Java、跨平台，不依赖 sh/cmd。
 *
 * <p>模式（第一个参数）：
 *
 * <ul>
 *   <li>{@code ok}：打印固定行并退出 0；
 *   <li>{@code exit3}：向 stderr 打印错误行并退出 3；
 *   <li>{@code sleep &lt;pidFile|""> &lt;millis&gt;}：把自身 PID 写入 pidFile（可选），然后睡眠；
 *   <li>{@code flood}：向 stdout 写 2 MiB；
 *   <li>{@code flooderr}：向 stderr 写 2 MiB；
 *   <li>{@code echoargs}：回显 cwd 与每个 argv（验证空格/中文原样到达、不过 shell）；
 *   <li>{@code env}：按名字排序打印全部环境变量；
 *   <li>{@code tree}：再拉起一个 sleep 子进程，打印 parent/child PID 后睡眠（验证杀树）。
 * </ul>
 */
public final class FakeProcessWorker {

    private FakeProcessWorker() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "ok" : args[0];
        PrintStream out =
                new PrintStream(
                        new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err =
                new PrintStream(
                        new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        switch (mode) {
            case "ok" -> {
                out.println("ok-stdout");
                out.println("中文-正常退出");
                out.flush();
            }
            case "exit3" -> {
                err.println("boom-stderr: 故意退出 3");
                err.flush();
                System.exit(3);
            }
            case "sleep" -> {
                Path pidFile = args.length > 1 && !args[1].isBlank() ? Path.of(args[1]) : null;
                long millis = args.length > 2 ? Long.parseLong(args[2]) : 10_000L;
                if (pidFile != null)
                    Files.writeString(
                            pidFile, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
                out.println("pid=" + ProcessHandle.current().pid());
                out.flush();
                Thread.sleep(millis);
            }
            case "flood" -> {
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                for (int i = 0; i < 32; i++) out.write(chunk);
                out.flush();
            }
            case "flooderr" -> {
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'e');
                for (int i = 0; i < 32; i++) err.write(chunk);
                err.flush();
            }
            case "echoargs" -> {
                out.println("cwd=" + Path.of("").toAbsolutePath());
                out.println("argc=" + (args.length - 1));
                for (int i = 1; i < args.length; i++) out.println("arg=" + args[i]);
                out.flush();
            }
            case "env" -> {
                out.println("cwd=" + Path.of("").toAbsolutePath());
                Map<String, String> environment = System.getenv();
                for (String key : environment.keySet().stream().sorted().toList())
                    out.println("env=" + key + "=" + environment.get(key));
                out.flush();
            }
            case "tree" -> {
                out.println("parent=" + ProcessHandle.current().pid());
                Process child = new ProcessBuilder(command("sleep", "", "10000")).start();
                out.println("child=" + child.pid());
                out.flush();
                Thread.sleep(10_000);
            }
            default -> {
                err.println("unknown-mode=" + mode);
                err.flush();
                System.exit(2);
            }
        }
    }

    /** 组装“用当前 JVM 运行本类”的 argv（首元素是绝对路径）。 */
    public static List<String> command(String... workerArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(classpath());
        command.add(FakeProcessWorker.class.getName());
        command.addAll(Arrays.asList(workerArgs));
        return command;
    }

    public static String javaExecutable() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    /** 优先用 test-classes 目录；取不到时退回 surefire booter classpath（其 manifest 带 Class-Path）。 */
    public static String classpath() {
        try {
            URL location =
                    FakeProcessWorker.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            if (Files.isDirectory(path)) return path.toString();
        } catch (Exception ignored) {
            // 退回 java.class.path
        }
        return System.getProperty("java.class.path");
    }

    /** 测试断言环境变量用：把 {@code env} 模式的输出解析成 name→value。 */
    public static Map<String, String> parseEnvironment(String stdout) {
        return stdout.lines()
                .filter(line -> line.startsWith("env="))
                .map(line -> line.substring(4))
                .filter(line -> line.indexOf('=') > 0)
                .collect(
                        Collectors.toMap(
                                line -> line.substring(0, line.indexOf('=')),
                                line -> line.substring(line.indexOf('=') + 1),
                                (left, right) -> right));
    }
}
