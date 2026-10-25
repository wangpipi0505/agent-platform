package cn.agentplatform.app.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;

/** Bootstrap touches only the explicitly dedicated D2 database. No skipped/memory fallback. */
class IntegrationPolicyDatabaseTest {
    @Test void dedicatedDatabaseAndRevisionMigrationAreRealPostgres(){
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean exists=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname='agent_platform_iam_policy_v12')",Boolean.class));
        System.out.println("D2_DATABASE_EXISTED="+exists);
        if(!exists)admin.execute("CREATE DATABASE agent_platform_iam_policy_v12");
        var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_policy_v12","agent_v12",password);
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("classpath:db/migration").load().migrate();
        var db=new JdbcTemplate(ds);assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("agent_platform_iam_policy_v12");
        assertThat(db.queryForObject("select count(*) from information_schema.columns where table_schema='app' and table_name='integration_credential' and column_name='policy_revision'",Integer.class)).isEqualTo(1);
    }
}
