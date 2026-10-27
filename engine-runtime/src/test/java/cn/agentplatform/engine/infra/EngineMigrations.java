package cn.agentplatform.engine.infra;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 测试里“期望的迁移号”的唯一真值是 classpath 上的 {@code db/migration/V<数字>__*.sql}。
 *
 * <p>测断言的命题应当是“DB 实际迁移号 == 代码期望”，而不是“DB 恰好停在 217/218 这类历史版本”：
 * 后者每新增一条迁移就红一次，且红的是测试夹具而不是被测行为，久而久之掩盖真实失败；更糟的是夹具
 * 会拿着 {@code target("217")} 在旧 schema 上继续跑，被测代码却已是新 schema 的代码。所以这里改为
 * 按 classpath 现算，新增 V221 后所有断言自动跟上，无需再改任何数字。
 *
 * <p>版本号按数字解析（{@code V20__x.sql} 这类两位编号得到 20，既不会被漏掉也不会被读成 200），
 * 去重后升序；多个 classpath 根含同一份迁移（如 target/classes 被额外拷贝）时只算一次。结果缓存，
 * 避免每个夹具重复扫 classpath 上的全部 jar。
 */
public final class EngineMigrations {

    /** 与 Flyway 默认命名一致：V<数字>__<描述>.sql；描述里的点号、下划线不影响解析。 */
    private static final Pattern FILE = Pattern.compile("V(\\d+)__.*\\.sql");

    /** 用本类自己的 ClassLoader：独立 JVM 的子进程（fixture main）里上下文 ClassLoader 未必相同。 */
    private static final PathMatchingResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver(EngineMigrations.class.getClassLoader());

    private static volatile List<Integer> versions;

    private EngineMigrations() {}

    /** classpath 上的迁移版本号，升序去重。 */
    public static List<Integer> versions() {
        var cached = versions;
        if (cached != null) return cached;
        synchronized (EngineMigrations.class) {
            if (versions == null) versions = load();
            return versions;
        }
    }

    /** 当前代码（classpath）期望的最高迁移号。 */
    public static int max() {
        return versions().getLast();
    }

    /** 闭区间 {@code [fromInclusive,toInclusive]} 内的迁移条数，用于“200..head 全部应用”类断言。 */
    public static int count(int fromInclusive, int toInclusive) {
        return (int)
                versions().stream()
                        .filter(v -> v >= fromInclusive && v <= toInclusive)
                        .count();
    }

    private static List<Integer> load() {
        var found = new TreeSet<Integer>();
        try {
            for (Resource resource : RESOLVER.getResources("classpath*:db/migration/V*__*.sql")) {
                String name = resource.getFilename();
                if (name == null) continue;
                Matcher matcher = FILE.matcher(name);
                if (matcher.matches()) found.add(Integer.parseInt(matcher.group(1)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("ENGINE_MIGRATIONS_UNREADABLE", e);
        }
        if (found.isEmpty()) throw new IllegalStateException("ENGINE_MIGRATIONS_ABSENT");
        return List.copyOf(found);
    }
}
