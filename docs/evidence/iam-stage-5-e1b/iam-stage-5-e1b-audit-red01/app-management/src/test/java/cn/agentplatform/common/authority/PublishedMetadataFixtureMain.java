package cn.agentplatform.common.authority;

import cn.agentplatform.app.api.*;
import cn.agentplatform.app.application.*;
import cn.agentplatform.common.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Finite smoke fixture: real app controller/SQL/global guard; no authority implementation here. */
public final class PublishedMetadataFixtureMain {
    static AuthorityProperties trust;static String secret;static Clock clock;static DataSource ds;
    static final AtomicInteger metadata=new AtomicInteger(),payload=new AtomicInteger();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({PublishedMetadataController.class,PublishedMetadataService.class,AppController.class,TemplateService.class,PlatformErrors.class})
    static class Receiver {
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(ds){@Override public <T> List<T> query(String sql,RowMapper<T> row,Object...args){if(sql.contains("left join")&&sql.contains("candidate_sha256"))metadata.incrementAndGet();else payload.incrementAndGet();return super.query(sql,row,args);}};}
        @Bean AppService apps(JdbcTemplate db){return new AppService(db,new DataSourceTransactionManager(ds),"http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1");}
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(trust,clock,true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient current(){return new HttpCurrentAuthorityClient(trust,clock,new WorkloadCredentialResolver(trust,n->secret,true),true);}
        @Bean ServiceAuthorization authorization(CurrentAuthorityClient c){return new ServiceAuthorization("app",new ServiceRoutePolicy(),c);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("app",new ServiceRoutePolicy(),mappings);}
    }
    public static void main(String[] args)throws Exception{
        try(var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
            var init=Json.read(Objects.requireNonNull(input.readLine()));trust=Json.read(init.get("trust").toString(),AuthorityProperties.class);secret=init.path("secret").textValue();clock=Clock.fixed(Instant.parse(init.path("now").textValue()),ZoneOffset.UTC);
            String tenant=init.path("tenant").textValue();
            ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_metadata_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));var db=new JdbcTemplate(ds);
            if(!"agent_platform_iam_metadata_v12".equals(db.queryForObject("select current_database()",String.class))||db.queryForObject("select count(*) from app.flyway_schema_history where success and version in ('100','101','102','103','104')",Integer.class)!=5)throw new IllegalStateException("SMOKE_DATABASE_PREREQUISITE_REQUIRED");
            db.update("insert into app.application(tenant_id,code,name,type,status,current_version_no,created_by) values(?,'Smoke','Smoke','CHAT','PUBLISHED',1,'alice')",tenant);
            db.update("insert into app.app_draft(tenant_id,app_id,app_code) select tenant_id,id,code from app.application where tenant_id=? and code='Smoke'",tenant);
            db.update("insert into app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id) select a.tenant_id,a.code,1,1,?,'{\"private\":\"never-read\"}','smoke','alice',a.id,d.id from app.application a join app.app_draft d on d.tenant_id=a.tenant_id and d.app_code=a.code where a.tenant_id=? and a.code='Smoke'",Digest.sha256("smoke-stored-candidate"),tenant);
            var application=new SpringApplication(Receiver.class);application.setAdditionalProfiles("test");application.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/d3a0-smoke-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
            try(var app=(ServletWebServerApplicationContext)application.run()){
                System.out.println("D3A0_READY="+Json.write(Map.of("port",app.getWebServer().getPort())));System.out.flush();
                if(!"STOP".equals(input.readLine()))throw new IllegalStateException("SMOKE_CONTROL_CLOSED");
                if(metadata.get()!=1||payload.get()!=0)throw new IllegalStateException("SMOKE_READ_COUNTS_INVALID");
                System.out.println("D3A0_APP_RESULT="+Json.write(Map.of("metadataReads",metadata.get(),"payloadReads",payload.get(),"globalGuard",true,"runtimeCoverage",true)));
            }
        }finally{secret=null;}
    }
}
