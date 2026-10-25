package cn.agentplatform.engine.infra;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;
class RunAuthorityDatabasePreflightTest {
 @Test void fixedDatabaseHistoryIsInspectedBeforeMigrationAllocation(){
  String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
  var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
  boolean exists=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname='agent_platform_iam_engine_v12')",Boolean.class));
  System.out.println("E1B_DATABASE_EXISTED="+exists);if(!exists)admin.execute("CREATE DATABASE agent_platform_iam_engine_v12");
  var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_engine_v12","agent_v12",password);var db=new JdbcTemplate(ds);
  assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("agent_platform_iam_engine_v12");
  System.out.println("E1B_HISTORY_BEFORE="+db.queryForObject("select to_regclass('engine.flyway_schema_history')::text",String.class));
  if(db.queryForObject("select to_regclass('engine.flyway_schema_history')::text",String.class)!=null)System.out.println("E1B_ROWS_BEFORE="+db.queryForList("select version,description,success from engine.flyway_schema_history order by installed_rank"));
  Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").target("215").load().migrate();
  System.out.println("E1B_HISTORY_AFTER="+db.queryForList("select version,description,success from engine.flyway_schema_history order by installed_rank"));
  System.out.println("E1B_TABLE_COUNT="+db.queryForObject("select count(*) from information_schema.tables where table_schema='engine'",Integer.class));
 }
}
