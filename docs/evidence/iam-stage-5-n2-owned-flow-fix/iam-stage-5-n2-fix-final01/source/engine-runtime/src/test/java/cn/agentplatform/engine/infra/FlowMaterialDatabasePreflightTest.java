package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;

class FlowMaterialDatabasePreflightTest {
    @Test
    void inspectBothAuthorizedHistoriesAndActualSourceBeforeAllocating217() throws Exception {
        var password = System.getenv("AGENT_DB_PASSWORD");
        assertThat(password).isNotBlank();
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                password));
        boolean exists =
                Boolean.TRUE.equals(
                        admin.queryForObject(
                                "select exists(select 1 from pg_database where"
                                    + " datname='agent_platform_iam_flow_material_v12')",
                                Boolean.class));
        System.out.println("N1_DATABASE_EXISTED=" + exists);
        if (!exists) admin.execute("CREATE DATABASE agent_platform_iam_flow_material_v12");
        for (String name :
                new String[] {
                    "agent_platform_iam_flow_material_v12", "agent_platform_iam_engine_v12"
                }) {
            var ds =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://127.0.0.1:25432/" + name, "agent_v12", password);
            var db = new JdbcTemplate(ds);
            assertThat(db.queryForObject("select current_database()", String.class))
                    .isEqualTo(name);
            if (db.queryForObject(
                            "select to_regclass('engine.flyway_schema_history')::text",
                            String.class)
                    != null) {
                System.out.println(
                        "N1_HISTORY_BEFORE_"
                                + name
                                + "="
                                + db.queryForList(
                                        "select version,description,success from"
                                            + " engine.flyway_schema_history order by"
                                            + " installed_rank"));
                assertThat(
                                db.queryForObject(
                                        "select max(version::int) from engine.flyway_schema_history"
                                            + " where success and version is not null",
                                        Integer.class))
                        .isLessThanOrEqualTo(216);
            }
            if (name.equals("agent_platform_iam_flow_material_v12"))
                Flyway.configure()
                        .dataSource(ds)
                        .schemas("engine")
                        .defaultSchema("engine")
                        .locations("classpath:db/migration")
                        .target("216")
                        .load()
                        .migrate();
            assertThat(
                            db.queryForObject(
                                    "select max(version::int) from engine.flyway_schema_history"
                                        + " where success and version is not null",
                                    Integer.class))
                    .isEqualTo(216);
            System.out.println(
                    "N1_HISTORY_AFTER_"
                            + name
                            + "="
                            + db.queryForList(
                                    "select version,description,success from"
                                        + " engine.flyway_schema_history order by installed_rank"));
            System.out.println(
                    "N1_CONSTRAINTS_"
                            + name
                            + "="
                            + db.queryForList(
                                    "select conname,pg_get_constraintdef(oid) as definition from"
                                        + " pg_constraint where"
                                        + " conrelid='engine.material_scope'::regclass order by"
                                        + " conname"));
            System.out.println(
                    "N1_TRIGGERS_"
                            + name
                            + "="
                            + db.queryForList(
                                    "select tgname from pg_trigger where tgrelid in"
                                        + " ('engine.material_scope'::regclass,'engine.run_authority'::regclass)"
                                        + " and not tgisinternal order by tgname"));
        }
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            assertThat(
                            files.map(p -> p.getFileName().toString())
                                    .filter(n -> n.matches("V[0-9]+__.*"))
                                    .mapToInt(
                                            n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                                    .max()
                                    .orElseThrow())
                    .isEqualTo(216);
        }
    }
}
