package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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

/** Own engine HTTP, real signed authority, and a database used only by this class. */
class RunObservationMetadataHttpPostgresTest {
    static final String DATABASE="agent_platform_iam_o1a_metadata_v12";
    static LoopbackAuthority iam;
    static HttpCurrentAuthorityClient engine,gateway;
    static DataSource ds;
    static JdbcTemplate db;
    static ServletWebServerApplicationContext context;
    static final HttpClient http=HttpClient.newHttpClient();
    static String tenant,source;
    static final String RUN="r_"+"a".repeat(32);

    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    @Import(PlatformErrors.class)
    static class Receiver {
        @Bean AuthorityProperties authorityProperties(){return iam.config("engine");}
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return db;}
        // Only denial is exercised on the existing POST. Its handler must never execute.
        @Bean RunController runs(){return new RunController(null,null,null,null);}
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true);}
        @Bean ServiceAuthorization authorization(){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),mappings);}
    }
    @BeforeAll static void start()throws Exception {
        iam=new LoopbackAuthority();engine=iam.client("engine");
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean existed=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,DATABASE));
        if(!existed)admin.execute("create database "+DATABASE);
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password);db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo(DATABASE);
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").target("217").load().migrate();
        assertThat(db.queryForObject("select count(*) from engine.flyway_schema_history where success and version::integer between 200 and 217",Integer.class)).isEqualTo(18);
        System.out.println("O1A_DB="+Json.write(Map.of("database",DATABASE,"existed",existed,"migrationCount",18)));
        var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");
        app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/o1a-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
        context=(ServletWebServerApplicationContext)app.run();
        System.out.println("O1A_HTTP_READY="+Json.write(Map.of("pid",ProcessHandle.current().pid(),"port",context.getWebServer().getPort())));
    }
    @BeforeEach void seed(){
        tenant="o1a-"+UUID.randomUUID();iam.clearFiniteGrants();iam.revoked(false);iam.unavailable(false);iam.ticketMutation(c->{});iam.decisionMutation(c->{});
        iam.portalActor(new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));
        if(gateway!=null)gateway.close();gateway=iam.client("gateway");source=iam.portal();
    }
    @AfterAll static void close(){if(context!=null)context.close();if(gateway!=null)gateway.close();if(engine!=null)engine.close();if(iam!=null)iam.close();System.out.println("O1A_RESOURCES_CLOSED=true");}
    static ResourceBinding selector(String run){return new ResourceBinding("run-observation-selector-v1",Json.canonical(List.of("run-observation-selector.v1",tenant,"alice",run,"run-observation-metadata.v1")),Digest.sha256(Json.canonical(Map.of("schema","run-observation-selector.v1","tenant_id",tenant,"principal_id","alice","run_id",run,"projection","run-observation-metadata.v1"))));}
    static HttpResponse<String> request(String method,String path,ServiceTicket ticket,String body)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).header("Authorization","Bearer "+ticket.value()).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void querySelectorCannotBecomeExistingRunWritePermission()throws Exception {
        var bad=new ServiceCall("engine","proxy.engine.post.RunController.run",selector(RUN));
        var ticket=gateway.exchange(source,bad);
        int before=iam.authorityCalls();
        var response=request("POST","/admin/apps/app-a/runs",ticket,"{}");
        System.out.println("O1A_WRITE_PURPOSE="+Json.write(Map.of("status",response.statusCode(),"authorityDelta",iam.authorityCalls()-before,"body",response.body())));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(iam.authorityCalls()-before).isZero();
    }
}
