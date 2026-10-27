package cn.agentplatform.skill.infra;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
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
 * <p>模式（第一个参数）。协议模式先读 stdin 的请求信封（§4.4），只在 stdout 写一个响应信封（§4.5）：
 *
 * <ul>
 *   <li>{@code envelope}：把收到的整个 stdin 原文放进 {@code result.stdin} 回显（断言信封四键）；
 *   <li>{@code ok}：{@code result.lines=["ok-stdout","中文-正常退出"]}；
 *   <li>{@code echoargs}：{@code result.lines} 回显 cwd 与每个 argv（空格/中文原样到达、不过 shell）；
 *   <li>{@code env}：{@code result.lines} 按名字排序打印全部环境变量；
 *   <li>{@code tamper}：把回显的 requestId 改一个字符（响应与请求不再对应）；
 *   <li>{@code banner}：信封前后各打印一行日志（stdout 不再“恰好一个 JSON 对象”）；
 *   <li>{@code badprotocol}：响应 protocol 改成 {@code algorithm-process/0}；
 *   <li>{@code rejected}：{@code ok=false} + {@code error.message}，退出码 5（“其它非 0”）；
 *   <li>{@code schemabad}：{@code result} 是 {@code {"count":"不是数字"}}（类型违反 outputSchema）；
 *   <li>{@code flood}：stdout 写 2 MiB 垃圾（超过 1 MiB 上限，必须被杀）；
 *   <li>{@code flooderr}：正常信封 + 2 MiB stderr（只截断、不影响结果）。
 * </ul>
 *
 * <p>非协议模式（不读 stdin，只用于超时/杀树/退出码等“信封之外”的路径）：{@code plain}（只打印两行
 * 纯文本，供进程树原语测试）、{@code exit2}/{@code exit3}/{@code exit4}（stderr 打印一行后按码退出）、
 * {@code sleep <pidFile|""> <millis>}、{@code tree}。
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
            case "envelope" -> {
                String stdin = readStdin();
                String requestId = field(stdin, "requestId");
                out.println(
                        success(requestId, "{\"stdin\":" + json(stdin) + "}"));
                out.flush();
            }
            case "plain" -> {
                out.println("ok-stdout");
                out.println("中文-正常退出");
            }
            case "ok" ->
                    out.println(success(field(readStdin(), "requestId"), lines("ok-stdout", "中文-正常退出")));
            case "echoargs" -> {
                String requestId = field(readStdin(), "requestId");
                List<String> lines = new ArrayList<>();
                lines.add("cwd=" + Path.of("").toAbsolutePath());
                lines.add("argc=" + (args.length - 1));
                for (int i = 1; i < args.length; i++) lines.add("arg=" + args[i]);
                out.println(success(requestId, lines(lines.toArray(String[]::new))));
            }
            case "env" -> {
                String requestId = field(readStdin(), "requestId");
                List<String> lines = new ArrayList<>();
                lines.add("cwd=" + Path.of("").toAbsolutePath());
                Map<String, String> environment = System.getenv();
                for (String key : environment.keySet().stream().sorted().toList())
                    lines.add("env=" + key + "=" + environment.get(key));
                out.println(success(requestId, lines(lines.toArray(String[]::new))));
            }
            case "tamper" -> {
                String requestId = field(readStdin(), "requestId");
                out.println(success(requestId + "-tampered", lines("tampered")));
            }
            case "banner" -> {
                String requestId = field(readStdin(), "requestId");
                out.println("worker-banner-before");
                out.println(success(requestId, lines("banner")));
                out.println("worker-banner-after");
            }
            case "badprotocol" -> {
                String requestId = field(readStdin(), "requestId");
                out.println(success(requestId, lines("bad-protocol")).replace("algorithm-process/1", "algorithm-process/0"));
            }
            case "rejected" -> {
                String requestId = field(readStdin(), "requestId");
                out.println(
                        "{\"protocol\":\"algorithm-process/1\",\"requestId\":"
                                + json(requestId)
                                + ",\"ok\":false,\"error\":{\"code\":\"DATA_INVALID\",\"message\":\"输入表缺少必填列 amount\"}}");
                out.flush();
                System.exit(5);
            }
            case "schemabad" -> {
                String requestId = field(readStdin(), "requestId");
                out.println(success(requestId, "{\"count\":\"不是数字\"}"));
            }
            case "silent" -> {
                // 读掉信封后什么都不写：退出码 0 但 stdout 为空，协议上不成立。
                readStdin();
            }
            case "flood" -> {
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                for (int i = 0; i < 32; i++) out.write(chunk);
                out.flush();
            }
            case "flooderr" -> {
                String requestId = field(readStdin(), "requestId");
                out.println(success(requestId, lines("flooderr-ok")));
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'e');
                for (int i = 0; i < 32; i++) err.write(chunk);
                err.flush();
            }
            case "exit2", "exit3", "exit4" -> {
                err.println("boom-stderr: 故意退出 " + mode.substring(4));
                err.flush();
                System.exit(Integer.parseInt(mode.substring(4)));
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

    private static String readStdin() throws IOException {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 从请求信封里取 requestId：本文件刻意不引 Jackson，保证 worker 只依赖 JDK。 */
    private static String field(String json, String name) {
        String marker = "\"" + name + "\"";
        int at = json.indexOf(marker);
        if (at < 0) return "";
        int colon = json.indexOf(':', at + marker.length());
        if (colon < 0) return "";
        int start = json.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : json.indexOf('"', start + 1);
        return start < 0 || end < 0 ? "" : json.substring(start + 1, end);
    }

    private static String success(String requestId, String resultJson) {
        return "{\"protocol\":\"algorithm-process/1\",\"requestId\":"
                + json(requestId)
                + ",\"ok\":true,\"result\":"
                + resultJson
                + ",\"files\":[],\"warnings\":[],\"metrics\":{\"durationMs\":1}}";
    }

    private static String lines(String... values) {
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(json(values[i]));
        }
        return sb.append("]}").toString();
    }

    private static String json(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
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
