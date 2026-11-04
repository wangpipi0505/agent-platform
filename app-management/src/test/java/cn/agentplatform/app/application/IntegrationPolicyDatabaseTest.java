package cn.agentplatform.app.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;

/**
 * Bootstrap touches only the explicitly dedicated D2 database. No skipped/memory fallback.
 * 连接坐标走 {@link TestDatabase}：专用库 URL 由 {@code AGENT_DB_URL_POLICY} 显式给出
 * （库名被下方断言钉死为 agent_platform_iam_policy_v12，须先行独立准备，测试不建库）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_DB_URL_POLICY", matches = ".+")
class IntegrationPolicyDatabaseTest {
    @Test void dedicatedDatabaseAndRevisionMigrationAreRealPostgres(){
        var ds=new DriverManagerDataSource(TestDatabase.url("AGENT_DB_URL_POLICY"),TestDatabase.user(),TestDatabase.password());
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("classpath:db/migration").load().migrate();
        var db=new JdbcTemplate(ds);assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("agent_platform_iam_policy_v12");
        assertThat(db.queryForObject("select count(*) from information_schema.columns where table_schema='app' and table_name='integration_credential' and column_name='policy_revision'",Integer.class)).isEqualTo(1);
    }
}
