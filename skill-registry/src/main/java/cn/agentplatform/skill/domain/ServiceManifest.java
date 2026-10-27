package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * AP-S05 外部服务 manifest。
 *
 * <p>设计不变量：命令行是显式参数数组，不经过 shell；启动程序、工作目录、端口和健康路径都在
 * 管理面预先校验。manifest 只描述“如何拉起一个本机服务”，不携带凭据，也不允许脚本片段拼进
 * 命令行。真正执行仍由 ADMIN 触发，且每次生命周期动作写入 skill.service_audit。
 */
public record ServiceManifest(
        String code,
        String name,
        List<String> command,
        String workDir,
        int port,
        String healthPath,
        long startupTimeoutMs,
        long stopTimeoutMs,
        boolean enabled) {

    private static final int MAX_COMMAND_ARGS = 64;
    private static final int MAX_ARG_LENGTH = 4096;

    public static ServiceManifest parse(Map<String, Object> body) {
        String code = text(body, "code", 64);
        if (!code.matches("[a-z][a-z0-9_]{1,63}"))
            throw error("服务编码需为小写字母开头，可含数字和下划线，长度 2-64");
        String name = text(body, "name", 128);
        List<String> command = command(body.get("command"));
        String workDir = text(body, "workDir", 1024);
        Path work = Path.of(workDir);
        if (!work.isAbsolute())
            throw error("工作目录必须是绝对路径");
        if (!Files.isDirectory(work))
            throw error("工作目录不存在或不是目录: " + workDir);
        int port = number(body.get("port"), "端口");
        if (port < 1 || port > 65535) throw error("端口必须在 1-65535");
        String healthPath = Objects.toString(body.getOrDefault("healthPath", "/"), "").trim();
        if (!healthPath.startsWith("/") || healthPath.length() > 512
                || healthPath.contains("..") || healthPath.contains("?") || healthPath.contains("#"))
            throw error("健康路径必须以 / 开头，且不能包含 ..、查询串或片段");
        long startup = timeout(body.get("startupTimeoutMs"), 30_000L, 1_000L, 600_000L, "启动超时");
        long stop = timeout(body.get("stopTimeoutMs"), 5_000L, 100L, 60_000L, "停止超时");
        boolean enabled = body.get("enabled") == null || Boolean.TRUE.equals(body.get("enabled"));
        return new ServiceManifest(code, name, command, work.toString(), port, healthPath,
                startup, stop, enabled);
    }

    public static ServiceManifest fromRow(Map<String, Object> row) {
        return new ServiceManifest(
                Objects.toString(row.get("code"), ""),
                Objects.toString(row.get("name"), ""),
                commandFromJson(row.get("command")),
                Objects.toString(row.get("work_dir"), ""),
                ((Number) row.get("port")).intValue(),
                Objects.toString(row.get("health_path"), "/"),
                ((Number) row.get("startup_timeout_ms")).longValue(),
                ((Number) row.get("stop_timeout_ms")).longValue(),
                Boolean.TRUE.equals(row.get("enabled")));
    }

    public Map<String, Object> view() {
        var result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("name", name);
        result.put("command", command);
        result.put("workDir", workDir);
        result.put("port", port);
        result.put("healthPath", healthPath);
        result.put("startupTimeoutMs", startupTimeoutMs);
        result.put("stopTimeoutMs", stopTimeoutMs);
        result.put("enabled", enabled);
        return result;
    }

    public URI healthUri() {
        return URI.create("http://127.0.0.1:" + port + healthPath);
    }

    public Path logFile(Path logDir) {
        return logDir.resolve(code + ".log");
    }

    private static String text(Map<String, Object> body, String key, int max) {
        String value = Objects.toString(body.get(key), "").trim();
        if (value.isBlank() || value.length() > max)
            throw error("缺少或无效字段: " + key);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> command(Object raw) {
        if (!(raw instanceof Collection<?> values) || values.isEmpty()
                || values.size() > MAX_COMMAND_ARGS)
            throw error("command 必须是 1-" + MAX_COMMAND_ARGS + " 个参数的数组");
        var result = new ArrayList<String>();
        for (Object item : values) {
            if (!(item instanceof String value))
                throw error("command 的每一项都必须是字符串");
            if (value.isBlank() || value.length() > MAX_ARG_LENGTH)
                throw error("command 参数不能为空且不能超过 " + MAX_ARG_LENGTH + " 字符");
            if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw error("command 参数不能包含换行或 NUL");
            result.add(value);
        }
        Path executable = Path.of(result.getFirst());
        if (!executable.isAbsolute()) throw error("command 第一项必须是绝对路径");
        if (!Files.isRegularFile(executable)) throw error("启动程序不存在: " + executable);
        return List.copyOf(result);
    }

    private static List<String> commandFromJson(Object raw) {
        if (raw instanceof Collection<?> values)
            return values.stream().map(Objects::toString).toList();
        if (raw == null) return List.of();
        Object parsed = Json.read(Objects.toString(raw), List.class);
        if (parsed instanceof Collection<?> values)
            return values.stream().map(Objects::toString).toList();
        return List.of();
    }

    private static int number(Object raw, String label) {
        if (!(raw instanceof Number value))
            throw error(label + "必须是数字");
        return value.intValue();
    }

    private static long timeout(Object raw, long fallback, long min, long max, String label) {
        if (raw == null) return fallback;
        if (!(raw instanceof Number value)) throw error(label + "必须是毫秒数字");
        long result = value.longValue();
        if (result < min || result > max)
            throw error(label + "必须在 " + min + "-" + max + " 毫秒");
        return result;
    }

    private static PlatformException error(String message) {
        return new PlatformException(400, "INVALID_SERVICE", message);
    }
}
