package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlowMaterialDatabasePreflightTest {
    @Test
    void migration217UpgradesAnIsolatedVersion216Database() {
        var server = "jdbc:postgresql://127.0.0.1:25432/";
        var user = EngineTestDatabase.user();
        var password = EngineTestDatabase.password();
        var name = "flow_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(server + "postgres", user, password));
        admin.execute("create database " + name);
        try {
            var source = new DriverManagerDataSource(server + name, user, password);
            var before = Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                    .locations("classpath:db/migration").target("216").load();
            before.migrate();
            assertThat(before.info().current().getVersion().getVersion()).isEqualTo("216");

            var after = Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine")
                    .locations("classpath:db/migration").target("217").load();
            assertThat(after.info().pending()).anyMatch(migration -> "217".equals(migration.getVersion().getVersion()));
            assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(after.info().current().getVersion().getVersion()).isEqualTo("217");
            assertThat(after.migrate().migrationsExecuted).isZero();
        } finally {
            admin.execute("drop database " + name);
        }
    }
}
