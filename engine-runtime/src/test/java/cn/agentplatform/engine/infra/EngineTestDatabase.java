package cn.agentplatform.engine.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tests use a separate database on the dedicated new-project PG, never a running service's rows.
 * 坐标统一从环境读取（{@code ENGINE_PG_URL} 优先，其次 {@code AGENT_DB_URL}）；仅在未显式给出
 * {@code ENGINE_PG_URL} 时保留 25432 专用库守卫与自动建库，显式 URL 由调用方负责已准备。
 */
public final class EngineTestDatabase {
    private EngineTestDatabase() {}

    public static String url() {
        // 本机开发窗口：默认仍是专用 25432 引擎库；显式 ENGINE_PG_URL 才切换，保持隐式跨库为零。
        String override = System.getenv("ENGINE_PG_URL");
        if (override != null && !override.isBlank()) return override;
        String base =
                System.getenv()
                        .getOrDefault(
                                "AGENT_DB_URL",
                                "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12");
        if (!base.contains(":25432/"))
            throw new IllegalStateException("Dedicated PG25432 is required");
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, user(), password()));
        if (admin.queryForObject(
                        "select count(*) from pg_database where"
                            + " datname='agent_platform_engine_test'",
                        Integer.class)
                == 0) {
            try {
                admin.execute("create database agent_platform_engine_test");
            } catch (org.springframework.dao.DataAccessException concurrent) {
                if (admin.queryForObject(
                                "select count(*) from pg_database where"
                                    + " datname='agent_platform_engine_test'",
                                Integer.class)
                        == 0) throw concurrent;
            }
        }
        return base.replaceFirst("(?<=:25432/)[^?]+", "agent_platform_engine_test");
    }

    public static String user() {
        return System.getenv().getOrDefault("AGENT_DB_USER", "agent_v12");
    }

    public static String password() {
        return System.getenv("AGENT_DB_PASSWORD");
    }
}
