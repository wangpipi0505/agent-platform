package cn.agentplatform.app.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_DB_URL_ROLE_BOOTSTRAP", matches = ".+")
class PlatformAdminBootstrapTest {
    @Test
    void concurrentStartsGrantOneAdministratorAndKeepOneDurableAuditEvent() throws Exception {
        var source = new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_ROLE_BOOTSTRAP"),
                TestDatabase.user(), TestDatabase.password());
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class))
                .matches("agent_platform_role_bootstrap_test(?:_[a-z0-9]+)?");
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").outOfOrder(true).load().migrate();
        assertThat(db.queryForObject("select count(*) from app.platform_user_role", Integer.class)).isZero();
        var manager = new DataSourceTransactionManager(source);
        assertThatThrownBy(() -> new PlatformAdminBootstrap(db, manager, "missing-tenant"))
                .isInstanceOf(IllegalArgumentException.class);
        var first = new PlatformAdminBootstrap(db, manager, "tenant-a:first");
        var second = new PlatformAdminBootstrap(db, manager, "tenant-b:second");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timed out");
                first.run(null);
                return null;
            });
            var two = workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timed out");
                second.run(null);
                return null;
            });
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        }
        new PlatformAdminBootstrap(db, manager, "tenant-c:late").run(null);
        assertThat(db.queryForList("select platform_role from app.platform_user_role", String.class))
                .containsExactly("ADMIN");
        assertThat(db.queryForList("select event_type from app.platform_role_audit", String.class))
                .containsExactly("BOOTSTRAP_ADMIN_GRANTED");
        assertThat(db.queryForObject("select count(*) from app.platform_user_role r"
                + " join app.platform_role_audit e using (tenant_id, subject, platform_role)", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> db.update("delete from app.platform_role_audit"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
