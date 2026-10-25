package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.infra.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Own engine classpath, fixed dedicated PG, production admission/worker/FlowDriver. Private STDIN only. */
public final class GatewayPublishedEngineFixtureMain {
    static final String DATABASE="agent_platform_iam_admission_engine_v12";
    static AuthorityProperties trust;static String secret,appOrigin;static DataSource ds;static JdbcTemplate db;
    static HttpCurrentAuthorityClient authority;static RunScheduler scheduler;static final AtomicInteger created=new AtomicInteger(),legacy=new AtomicInteger();
    static final AtomicReference<Instant> now=new AtomicReference<>();
    static final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({RunController.class,PlatformErrors.class})
    static class Receiver {
        @Bean AuthorityProperties authorityProperties(){return trust;}
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return db;}
        @Bean TraceStore trace(){return new TraceStore(db,new DataSourceTransactionManager(ds),clock);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return authority=new HttpCurrentAuthorityClient(trust,clock,new WorkloadCredentialResolver(trust,n->secret,true),true);}
        @Bean(destroyMethod="close") RuntimeClients clients(HttpCurrentAuthorityClient a){return new RuntimeClients(trust,a,appOrigin,true){@Override public com.fasterxml.jackson.databind.JsonNode get(String s,String p,Actor actor){legacy.incrementAndGet();return super.get(s,p,actor);}@Override public com.fasterxml.jackson.databind.JsonNode post(String s,String p,Object b,Actor actor){legacy.incrementAndGet();return super.post(s,p,b,actor);}};}
        @Bean RunScheduler scheduler(TraceStore t){return scheduler=new RunScheduler(new RunScheduleRepository(t,true),2,1,10000,90000,1000){@Override public void created(long id,Admission a){created.incrementAndGet();super.created(id,a);}};}
        @Bean RunService service(TraceStore t,RuntimeClients c,RunScheduler s,HttpCurrentAuthorityClient a){return new RunService(t,c,new FlowDriver(t,null),null,s,new RunAuthority(new RunAuthorityStore(ds,clock),a,clock,Duration.ofSeconds(3)),new RunAuthorityStore(ds,clock),clock);}
        @Bean ApprovalService approvals(TraceStore t){return new ApprovalService(t);}
        @Bean ChunkBus chunks(){return new ChunkBus();}
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(trust,clock,true);}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient a){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),a);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),mappings);}
    }
    public static void main(String[] args)throws Exception {
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            var init=Json.read(Objects.requireNonNull(input.readLine()));trust=Json.read(init.path("trust").toString(),AuthorityProperties.class);trust.validate(true);secret=init.path("secret").asText();now.set(Instant.parse(init.path("now").asText()));appOrigin=init.path("appOrigin").asText();
            String password=System.getenv("AGENT_DB_PASSWORD");if(password==null||password.isBlank())throw new IllegalStateException("FINITE_DB_CREDENTIAL_REQUIRED");
            ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password);db=new JdbcTemplate(ds);
            if(!DATABASE.equals(db.queryForObject("select current_database()",String.class)))throw new AssertionError("FINITE_ENGINE_DATABASE");
            try(var files=Files.list(Path.of("engine-runtime/src/main/resources/db/migration"))){int max=files.map(p->p.getFileName().toString()).filter(n->n.matches("V\\d+__.*\\.sql")).mapToInt(n->Integer.parseInt(n.substring(1,n.indexOf("__")))).max().orElseThrow();if(max!=217)throw new AssertionError("ENGINE_MAX");System.out.println("N4B2_ENGINE_SOURCE_MAX="+max);}
            System.out.println("N4B2_ENGINE_HISTORY_BEFORE="+Json.write(db.queryForList("select version,description,checksum,success from engine.flyway_schema_history order by installed_rank")));
            Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").target("217").load().migrate();
            var history=db.queryForList("select version,description,checksum,success from engine.flyway_schema_history order by installed_rank");if(history.size()!=18)throw new AssertionError("ENGINE_HISTORY");System.out.println("N4B2_ENGINE_HISTORY_AFTER="+Json.write(history));
            var boot=new SpringApplication(Receiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n4b2-engine-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
            try(var context=(ServletWebServerApplicationContext)boot.run()){
                System.out.println("N4B2_ENGINE_READY="+Json.write(Map.of("port",context.getWebServer().getPort(),"pid",ProcessHandle.current().pid(),"database",DATABASE)));System.out.flush();
                for(String line;(line=input.readLine())!=null;){var command=Json.read(line);String action=command.path("action").asText();if(action.equals("STOP"))break;
                    if(action.equals("clock")){now.set(Instant.parse(command.path("now").asText()));reply(Map.of("clock",now.get().toString()));continue;}
                    String tenant=command.path("tenant").asText();if(!tenant.startsWith("n3-"))throw new IllegalArgumentException("FINITE_TENANT_REQUIRED");
                    if(action.equals("stats")){var runs=db.queryForList("select public_id,status,app_version_id from engine.run where tenant_id=? order by id",tenant);long attempts=db.queryForObject("select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id join engine.run r on r.id=n.run_id where r.tenant_id=?",Long.class,tenant);long materials=db.queryForObject("select count(*) from engine.material_scope where tenant_id=?",Long.class,tenant);reply(Map.of("runs",runs,"attempts",attempts,"materials",materials,"created",created.get(),"activeWorkers",scheduler.activeWorkers(),"legacy",legacy.get()));}
                    else throw new IllegalArgumentException("FINITE_ENGINE_COMMAND");
                }
            }
            System.out.println("N4B2_ENGINE_STOPPED="+ProcessHandle.current().pid());System.out.flush();
        }finally{secret=null;}
    }
    static void reply(Object value){System.out.println("N4B2_ENGINE_REPLY="+Json.write(value));System.out.flush();}
}
