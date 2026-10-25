import cn.agentplatform.common.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;

/** Explicit test DB whitelist; migrations run without starting any domain background workers. */
class TestInputMigrations {
    static final String[] DOMAINS={"app","engine","monitor"};
    static final String[] MODULES={"app-management","engine-runtime","monitor"};
    static final String[] OLD={"103","213","501"},LATEST={"103","214","502"};
    static final String PASSWORD=System.getenv("AGENT_DB_PASSWORD");
    static DriverManagerDataSource ds(String database){return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+database,"agent_v12",PASSWORD);}
    static Flyway migration(DriverManagerDataSource ds,int i,String target){return Flyway.configure().dataSource(ds).schemas(DOMAINS[i]).defaultSchema(DOMAINS[i]).locations("filesystem:"+MODULES[i]+"/src/main/resources/db/migration").target(target).load();}
    public static void main(String[] args)throws Exception{
        var results=new ArrayList<Map<String,Object>>();var admin=new JdbcTemplate(ds("postgres"));
        for(String database:List.of("agent_platform_test_input_empty_test","agent_platform_test_input_upgrade_test")){
            boolean fresh=admin.queryForObject("select count(*) from pg_database where datname=?",Integer.class,database)==0;
            if(!fresh)throw new IllegalStateException("Migration acceptance needs a new empty test DB; refusing to overwrite "+database);
            admin.execute("create database "+database);var ds=ds(database);var db=new JdbcTemplate(ds);
            boolean upgrade=database.contains("upgrade");
            if(upgrade){
                for(int i=0;i<DOMAINS.length;i++)migration(ds,i,OLD[i]).migrate();
                db.update("insert into monitor.dataset(tenant_id,app_code,dataset_version,cases,sha256) values('migration','demo','legacy','[{\"id\":\"old\",\"inputs\":{},\"expected\":null,\"safety\":true}]','"+"a".repeat(64)+"')");
            }
            for(int i=0;i<DOMAINS.length;i++){
                var flyway=migration(ds,i,LATEST[i]);var outcome=flyway.migrate();
                if(!LATEST[i].equals(flyway.info().current().getVersion().toString()))throw new AssertionError("Wrong migration version");
                results.add(Map.of("database",database,"domain",DOMAINS[i],"version",LATEST[i],"migrationsExecuted",outcome.migrationsExecuted,"path",upgrade?"upgrade":"empty"));
            }
            if(!"engine.debug_input".equals(db.queryForObject("select to_regclass('engine.debug_input')::text",String.class)))throw new AssertionError("Input table missing");
            if(upgrade && db.queryForObject("select count(*) from monitor.dataset where tenant_id='migration' and cases->0->>'id'='old' and promoted_by is null",Integer.class)!=1)throw new AssertionError("Legacy dataset changed");
        }
        String result=Json.write(Map.of("result","PASS","versions",results));Files.writeString(Path.of("docs/evidence/test-input-migrations.json"),result);System.out.println(result);
    }
}
