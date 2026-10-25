package cn.agentplatform.app.application;

import cn.agentplatform.app.api.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
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
import static org.assertj.core.api.Assertions.*;

class PublishedMetadataHttpTest {
    static LoopbackAuthority fixture;
    static DataSource ds;static JdbcTemplate db;
    static ServletWebServerApplicationContext app;
    static String tenant;
    static final String CODE="published-demo", SHA=Digest.sha256("immutable-stored-candidate");
    static final AtomicInteger metadataReads=new AtomicInteger(),payloadReads=new AtomicInteger();
    static final List<String> statements=new ArrayList<>();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({PublishedMetadataController.class,PublishedMetadataService.class,AppController.class,TemplateService.class,PlatformErrors.class})
    static class Receiver {
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(ds){@Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){statements.add(sql);if(sql.contains("candidate_sha256")&&sql.contains("left join"))metadataReads.incrementAndGet();else payloadReads.incrementAndGet();return super.query(sql,mapper,args);}};}
        @Bean AppService apps(JdbcTemplate jdbc){return new AppService(jdbc,new DataSourceTransactionManager(ds),"http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1");}
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(fixture.config("app"),fixture.clock(),true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient current(){return fixture.client("app");}
        @Bean ServiceAuthorization authorization(CurrentAuthorityClient current){return new ServiceAuthorization("app",new ServiceRoutePolicy(),current);}
        @Bean SecurityFilterChain security(HttpSecurity http,JwtDecoder decoder,ServiceAuthorization receiver)throws Exception{return SecurityConfiguration.serviceSecurity(http,decoder,receiver);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization receiver){return receiver.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("app",new ServiceRoutePolicy(),mappings);}
    }
    @BeforeAll static void start()throws Exception {
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean exists=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname='agent_platform_iam_metadata_v12')",Boolean.class));
        System.out.println("D3A0_DATABASE_EXISTED="+exists);
        if(!exists)admin.execute("CREATE DATABASE agent_platform_iam_metadata_v12");
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_metadata_v12","agent_v12",password);
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("classpath:db/migration").target("104").load().migrate();db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("agent_platform_iam_metadata_v12");
        fixture=new LoopbackAuthority();fixture.portalActor(new Actor("initial","alice",Set.of("EDITOR"),Map.of()));
        var application=new SpringApplication(Receiver.class);application.setAdditionalProfiles("test");
        application.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/d3a0-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
        app=(ServletWebServerApplicationContext)application.run();
    }
    @AfterAll static void close(){if(app!=null)app.close();if(fixture!=null)fixture.close();}
    @BeforeEach void seed(){fixture.clearFiniteGrants();tenant="d3a0-"+UUID.randomUUID();fixture.portalActor(new Actor(tenant,"alice",Set.of("EDITOR"),Map.of()));seedApp(tenant,CODE,"PUBLISHED",1);seedApp(tenant,"other","PUBLISHED",1);metadataReads.set(0);payloadReads.set(0);statements.clear();}
    static void seedApp(String tenant,String code,String status,Integer version){
        db.update("insert into app.application(tenant_id,code,name,type,status,current_version_no,created_by) values(?,?,'Metadata test','CHAT',?,?,'alice')",tenant,code,status,version);
        db.update("insert into app.app_draft(tenant_id,app_id,app_code,config) select tenant_id,id,code,'{\"private\":\"payload-secret\"}'::jsonb from app.application where tenant_id=? and code=?",tenant,code);
        if(version!=null)db.update("insert into app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id) select a.tenant_id,a.code,1,1,?,'{\"private\":\"snapshot-secret\"}','finite','alice',a.id,d.id from app.application a join app.app_draft d on d.tenant_id=a.tenant_id and d.app_code=a.code where a.tenant_id=? and a.code=?",SHA,tenant,code);
    }
    static ServiceCall query(String code){return new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(tenant,code));}
    static String ticket(ServiceCall call){try(var gateway=fixture.client("gateway")){return gateway.exchange(fixture.portal(),call).value();}}
    static String path(String code){return "/internal/apps/"+code+"/published-metadata";}
    static HttpResponse<String> request(String method,String path,String token)throws Exception{
        try(var http=HttpClient.newHttpClient()){var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(8));if(token!=null)r.header("Authorization","Bearer "+token);return http.send(r.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
    }
    @Test void legalSelectorReturnsOnlyPersistedFourFieldsWithOneMetadataRead()throws Exception{
        String source=fixture.portal();try(var gateway=fixture.client("gateway")){var t=gateway.exchange(source,query(CODE));assertThat(t.value()).isNotEqualTo(source);
        var r=request("GET",path(CODE),t.value());assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var data=Json.read(r.body()).path("data");assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("appCode","versionId","versionNo","versionSha256");assertThat(data.path("versionSha256").asText()).isEqualTo(SHA);assertThat(data.path("versionId").asLong()).isPositive();assertThat(data.path("versionNo").asInt()).isEqualTo(1);assertThat(metadataReads).hasValue(1);assertThat(payloadReads).hasValue(0);assertThat(statements).allSatisfy(sql->assertThat(sql).doesNotContain("snapshot","config","definition","layout","select a.*"));}
    }
    @Test void forgedSelectorTenantCodeOrDigestCannotQueryPg()throws Exception{
        for(var resource:List.of(MetadataSelectors.published(tenant,"other"),MetadataSelectors.published("wrong-tenant",CODE),new ResourceBinding(MetadataSelectors.KIND,MetadataSelectors.published(tenant,CODE).id(),Digest.sha256("fake-descriptor")))){
            var r=request("GET",path(CODE),ticket(new ServiceCall("app",MetadataSelectors.ACTION,resource)));assertThat(r.statusCode()).as(r.body()).isEqualTo(403);assertThat(metadataReads).hasValue(0);
        }
    }
    @Test void deliberatelySignedMetadataKindCannotEnterActualPayloadController()throws Exception{
        String action=new ServiceRoutePolicy().require("gateway","app","GET","/admin/apps/{code}").action();
        var r=request("GET","/admin/apps/"+CODE,ticket(new ServiceCall("app",action,MetadataSelectors.published(tenant,CODE))));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);assertThat(payloadReads).hasValue(0);assertThat(metadataReads).hasValue(0);
    }
    @Test void payloadKindCannotEnterMetadataController()throws Exception{
        var r=request("GET",path(CODE),ticket(new ServiceCall("app",MetadataSelectors.ACTION,new ResourceBinding("app-version",CODE+"@1",SHA))));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(403);assertThat(metadataReads).hasValue(0);
    }
}
