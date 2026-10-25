package cn.agentplatform.engine.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tests use a separate database on the dedicated new-project PG, never a running service's rows.
 */
final class EngineTestDatabase {
    static String url() {
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

    static String user() {
        return System.getenv().getOrDefault("AGENT_DB_USER", "agent_v12");
    }

    static String password() {
        return System.getenv("AGENT_DB_PASSWORD");
    }
}
