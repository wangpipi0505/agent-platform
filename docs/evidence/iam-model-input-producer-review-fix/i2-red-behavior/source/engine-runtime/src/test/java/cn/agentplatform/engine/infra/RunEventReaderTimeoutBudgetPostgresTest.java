package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

class RunEventReaderTimeoutBudgetPostgresTest {
    static final String DATABASE="timeout_fix_"+UUID.randomUUID().toString().replace("-", "");
    static DataSource database;

    @BeforeAll static void freshDatabase() throws Exception {
        String password=System.getenv("AGENT_DB_PASSWORD");
        assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        assertThat(admin.queryForObject("select current_database()",String.class)).isEqualTo("postgres");
        admin.execute("create database "+DATABASE);
        database=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password);
        assertThat(new JdbcTemplate(database).queryForObject("select current_database()",String.class)).isEqualTo(DATABASE);
        Path directory=Path.of(System.getProperty("timeout.fix.evidence"));Files.createDirectories(directory);
        Files.writeString(directory.resolve("database.json"),Json.write(Map.of("database",DATABASE,"jvmPid",ProcessHandle.current().pid(),"verifiedCurrentDatabase",true)));
    }

    @Test void liveBudgetSurvivesRealTimeoutSettingRoundTrip() throws Exception {
        try(var auth=new RunEventReaderAuthorityFixture();var response=open(auth)) {
            var source=new ObservedSource(database);var db=new JdbcTemplate(source);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            // Establish the connection before anchoring Work, without extending its allowance.
            try {
                tx.execute(status->{
                    source.work=new RunEventReader.Work(db,response,response.currentCapture(),System.nanoTime());
                    assertThat(source.work.millis()).isGreaterThan(1000);
                    var row=source.work.query("select ?::int as answer, current_database() as database, (select setting::bigint from pg_settings where name='statement_timeout') as timeout_ms",1,42).getFirst();
                    assertThat(row.get("answer")).isEqualTo(42);
                    assertThat(row.get("database")).isEqualTo(DATABASE);
                    assertThat(((Number)row.get("timeout_ms")).longValue()).isEqualTo(source.installed);
                    assertThat(source.businessCalls).isEqualTo(1);
                    assertThat(source.installed).isPositive().isLessThanOrEqualTo(source.remainingAtBusiness);
                    assertThat(source.settings).hasSizeBetween(1,3).allMatch(v->v>0);
                    return null;
                });
            } finally { source.witness("live"); }
        }
    }

    @Test void consumedBudgetDeniesBeforeBusinessSelect() throws Exception {
        try(var auth=new RunEventReaderAuthorityFixture();var response=open(auth)) {
            var source=new ObservedSource(database);var db=new JdbcTemplate(source);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            try {
                assertThatThrownBy(()->tx.execute(status->{
                    var capture=response.currentCapture();
                    // Older anchor consumes the real allowance; setup's 4ms exceeds this remainder.
                    source.work=new RunEventReader.Work(db,response,capture,System.nanoTime()-capture.remainingNanos()+TimeUnit.MILLISECONDS.toNanos(3));
                    return source.work.query("select 42 as answer",1);
                })).isInstanceOf(PlatformException.class);
                assertThat(source.businessCalls).isZero();
                assertThat(source.settings).allMatch(v->v>0);
            } finally { source.witness("consumed"); }
        }
    }

    @Test void timeoutSettingFailureDeniesBeforeBusinessSelect() throws Exception {
        try(var auth=new RunEventReaderAuthorityFixture();var response=open(auth)) {
            var source=new ObservedSource(database);source.failSetting=true;var db=new JdbcTemplate(source);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            try {
                assertThatThrownBy(()->tx.execute(status->{
                    source.work=new RunEventReader.Work(db,response,response.currentCapture(),System.nanoTime());
                    return source.work.query("select 42 as answer",1);
                })).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(source.businessCalls).isZero();
                assertThat(source.settings).hasSize(1).allMatch(v->v>0);
            } finally { source.witness("setting-failure"); }
        }
    }

    static PortalEventsResponse open(RunEventReaderAuthorityFixture auth) {
        return auth.open(new SubjectKey("tenant-a","alice"),"r_"+UUID.randomUUID().toString().replace("-",""),Digest.sha256("timeout-budget"),0);
    }

    /** Delegates all SQL to PG; only observes statements and delays actual SET completion. */
    static final class ObservedSource extends DelegatingDataSource {
        final List<Long> settings=new ArrayList<>(),afterSettingRemaining=new ArrayList<>();
        RunEventReader.Work work;long installed,remainingAtBusiness;int businessCalls;boolean failSetting;
        ObservedSource(DataSource target){super(target);}
        @Override public Connection getConnection() throws SQLException {
            var connection=super.getConnection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                try {
                    Object value=method.invoke(connection,args);
                    if(method.getName().equals("prepareStatement")&&value instanceof PreparedStatement statement) {
                        boolean setting=args[0].equals("select set_config('statement_timeout', ?, true)");
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                            try {
                                if(setting&&m.getName().equals("setString"))installed=Long.parseLong((String)a[1]);
                                if(m.getName().equals("executeQuery")&&!setting){businessCalls++;remainingAtBusiness=work.remaining()/1000000L;}
                                Object result=m.invoke(statement,a);
                                if(setting&&m.getName().equals("executeQuery")) {
                                    settings.add(installed);
                                    try { Thread.sleep(4); } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new SQLException("SETTING_INTERRUPTED",interrupted);}
                                    afterSettingRemaining.add(work.remaining()/1000000L);
                                    if(failSetting){((ResultSet)result).close();throw new SQLException("OBSERVED_SETTING_FAILURE");}
                                }
                                return result;
                            } catch(InvocationTargetException failure){throw failure.getCause();}
                        });
                    }
                    return value;
                } catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        void witness(String name) throws Exception {
            Path directory=Path.of(System.getProperty("timeout.fix.evidence"));Files.createDirectories(directory);
            Files.writeString(directory.resolve(name+".json"),Json.write(Map.of("database",DATABASE,"jvmPid",ProcessHandle.current().pid(),"settings",settings,"afterSettingRemainingMs",afterSettingRemaining,"remainingAtBusinessMs",remainingAtBusiness,"businessCalls",businessCalls,"workRemainingMs",work==null?0:work.remaining()/1000000L)));
        }
    }
}
