package cn.agentplatform.skill.infra;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 可执行文件白名单（PLAT-21d / 方案契约 §5.5-§5.6）。
 *
 * <p>算法可执行文件本身是信任根：白名单只允许已审核的绝对路径，不允许用户上传的可执行文件。
 * 匹配口径（实现时定死）：
 *
 * <ul>
 *   <li>条目与待检路径都先 {@code toRealPath()} 归一（解析 symlink 与 {@code ..}），文件不存在时
 *       退回 {@code toAbsolutePath().normalize()}；两侧用同一实现，避免一边 real 一边 nominal。
 *   <li>Windows 文件系统大小写不敏感，统一转小写比较；其他平台精确比较。
 *   <li>整表精确匹配，不支持通配符与前缀目录——白名单是"已审核程序清单"，不是"信任目录"，
 *       目录前缀会把目录内后续放入的任意二进制自动变成受信程序。
 *   <li>白名单为空集 = 未配置 = 不限制（兼容窗口）。这是有意降级：契约 §7.1 的"未配置白名单不能
 *       开启"由 Spring 装配在 {@code agent.process.enabled=true} 且白名单为空时拒绝启动来兜底，
 *       运行期 empty-set 放行只存在于单测直连构造与存量升级路径。
 * </ul>
 */
public final class ProcessWhitelist {

    private ProcessWhitelist() {}

    /** 解析逗号/分号/换行分隔的配置值（{@code agent.process.allowed-executables}）。 */
    public static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return parse(Arrays.asList(csv.split("[,;\n]")));
    }

    /** 归一化一组条目；空条目丢弃，重复条目去重。 */
    public static Set<String> parse(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) return Set.of();
        var normalized = new LinkedHashSet<String>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            normalized.add(canonical(entry.trim()));
        }
        return Set.copyOf(normalized);
    }

    /**
     * 是否放行：白名单未配置（空集）时放行——由登记期 ADMIN + 发布预检 + 启动装配检查兜底；
     * 已配置时要求待检可执行文件精确命中。
     */
    public static boolean contains(Set<String> whitelist, String executable) {
        if (whitelist == null || whitelist.isEmpty()) return true;
        return whitelist.contains(canonical(executable));
    }

    /** toRealPath 优先（消 symlink），文件不存在时退回绝对归一化路径；Windows 大小写归一。 */
    static String canonical(String path) {
        String normalized;
        try {
            Path p = Path.of(path);
            try {
                normalized = p.toRealPath().toString();
            } catch (IOException | UnsupportedOperationException notResolvable) {
                normalized = p.toAbsolutePath().normalize().toString();
            }
        } catch (InvalidPathException ex) {
            normalized = path;
        }
        return isWindows() ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
