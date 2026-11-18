import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * G5-0 升级演练 runner：以 Flyway 编程调用驱动“存量基线（target 钉在升级前版本）”与“全链升级到链头”。
 *
 * <p>写法对齐 {@code cn.agentplatform.engine.infra.EngineTestDatabase}：数据库坐标一律从环境读取
 * （AGENT_DB_USER / AGENT_DB_PASSWORD，URL 由调用方显式传入），密钥不进命令行参数、不落日志。
 * Flyway 版本与本工程 Spring Boot 3.5.0 BOM 管理的 flyway-core 11.7.2 一致，checksum 与
 * 各服务启动时 autoconfig 迁移完全同源（同一批 db/migration 文件 + 同一 Flyway 版本）。
 *
 * <p>单文件源码启动（java 21）：{@code java -cp "<deps-classpath>" scripts/g50/DrillFlyway.java <args>}
 * 依赖 classpath 由 {@code mvn -pl engine-runtime dependency:build-classpath} 生成（见 CHECKLIST）。
 *
 * <p>用法：
 * <pre>
 *   DrillFlyway migrate &lt;jdbcUrl&gt; &lt;engine|app|skill&gt; [targetVersion]   # targetVersion 缺省=迁到链头
 *   DrillFlyway info    &lt;jdbcUrl&gt; &lt;engine|app|skill&gt;
 *   DrillFlyway validate&lt;jdbcUrl&gt; &lt;engine|app|skill&gt;
 * </pre>
 *
 * <p>与各服务 application.yml 同构：default-schema/schemas = engine|app|skill，
 * locations = filesystem:对应模块 src/main/resources/db/migration，createSchemas=true，
 * 历史表保持默认名 flyway_schema_history（Spring Boot 缺省同名同位）。
 */
public final class DrillFlyway {

    private static final Map<String, String> MODULE_DIR = new LinkedHashMap<>();
    private static final Map<String, String> MODULE_SCHEMA = new LinkedHashMap<>();

    static {
        MODULE_DIR.put("engine", "engine-runtime");
        MODULE_DIR.put("app", "app-management");
        MODULE_DIR.put("skill", "skill-registry");
        MODULE_SCHEMA.put("engine", "engine");
        MODULE_SCHEMA.put("app", "app");
        MODULE_SCHEMA.put("skill", "skill");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "usage: DrillFlyway <migrate|info|validate> <jdbcUrl> <engine|app|skill> [targetVersion]");
        }
        String mode = args[0];
        String url = args[1];
        String module = args[2];
        String target = args.length >= 4 ? args[3] : null;
        // 仓库根从 env G50_REPO_ROOT 读（shell 脚本负责设置）；缺省=当前目录。
        String repoRoot = Path.of(System.getenv().getOrDefault("G50_REPO_ROOT", ".")).toAbsolutePath().normalize().toString();
        String location = "filesystem:" + repoRoot + "/" + MODULE_DIR.get(module) + "/src/main/resources/db/migration";

        // 与 EngineTestDatabase 同源：用户/口令只从环境读； drills/dev 库都在专用 PG 25432 上。
        if (!url.contains(":25432/")) {
            throw new IllegalStateException("Dedicated PG25432 is required");
        }
        String user = System.getenv().getOrDefault("AGENT_DB_USER", "agent_v12");
        String password = System.getenv("AGENT_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("AGENT_DB_PASSWORD must come from env (.env), never argv");
        }

        org.flywaydb.core.api.configuration.FluentConfiguration cfg =
                Flyway.configure()
                        .dataSource(url, user, password)
                        .locations(location)
                        .schemas(MODULE_SCHEMA.get(module))
                        .defaultSchema(MODULE_SCHEMA.get(module))
                        .createSchemas(true)
                        .baselineOnMigrate(false);
        if (target != null && !target.isBlank()) {
            // 存量构造：钉在升级前版本（engine=228 / app=121 / skill=320），V901 等更高版本不执行。
            cfg.target(MigrationVersion.fromVersion(target));
        }
        Flyway flyway = cfg.load();

        switch (mode) {
            case "migrate" -> {
                MigrateResult r = flyway.migrate();
                System.out.println("[g50-drill] module=" + module
                        + " target=" + (target == null || target.isBlank() ? "HEAD" : target)
                        + " applied=" + r.migrationsExecuted
                        + " from=" + r.initialSchemaVersion
                        + " to=" + r.targetSchemaVersion
                        + " outcome=" + r.targetSchemaVersion);
            }
            case "info" -> {
                var infos = flyway.info().applied();
                for (var i : infos) {
                    System.out.println("[g50-drill] " + module
                            + " version=" + i.getVersion()
                            + " desc=" + i.getDescription()
                            + " state=" + i.getState());
                }
            }
            case "validate" -> {
                // validate 失败会抛出异常（checksum/序列不匹配即 fail-fast）。
                flyway.validate();
                System.out.println("[g50-drill] module=" + module + " validate=OK");
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private DrillFlyway() {}
}
