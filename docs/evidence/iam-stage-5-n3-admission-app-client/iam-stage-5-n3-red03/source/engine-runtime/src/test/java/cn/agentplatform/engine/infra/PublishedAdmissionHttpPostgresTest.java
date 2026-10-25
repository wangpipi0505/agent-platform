package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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

/** Real engine HTTP + own-classpath app child HTTP + two fixed new PG databases. */
class PublishedAdmissionHttpPostgresTest {
    static final String DATABASE="agent_platform_iam_admission_engine_v12",CODE="admission-flow",SHA=Digest.sha256("n3-candidate-one"),SHA2=Digest.sha256("n3-candidate-two");
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static final ResourceBinding RESOURCE=new ResourceBinding("app-version",CODE+"@1",SHA);
    static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",RESOURCE);
    static LoopbackAuthority iam;static HttpCurrentAuthorityClient engine,gateway;static DataSource ds;static JdbcTemplate db;
    static Process child;static BufferedWriter control;static Thread drain;static int appPort;static final BlockingQueue<String> lines=new LinkedBlockingQueue<>();
    static final StringBuilder childOutput=new StringBuilder();
    static ServletWebServerApplicationContext context;static RuntimeClients clients;static RunScheduler scheduler;static TraceStore trace;static RunService service;
    static String tenant,source;static long versionId;

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({RunController.class,PlatformErrors.class})
    static class Receiver {
        @Bean AuthorityProperties authorityProperties(){return iam.config("engine");}
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return db;}
        @Bean TraceStore trace(){return trace=new TraceStore(db,new DataSourceTransactionManager(ds),iam.clock());}
        @Bean RuntimeClients clients(){var c=new RuntimeClients();c.app="http://127.0.0.1:"+appPort;return clients=c;}
        @Bean RunScheduler scheduler(TraceStore t){return scheduler=new RunScheduler(new RunScheduleRepository(t,true),2,1,10000,90000,1000);}
        @Bean RunService service(TraceStore t,RuntimeClients c,RunScheduler s){return service=new RunService(t,c,new FlowDriver(t,c),null,s,new RunAuthority(new RunAuthorityStore(ds,iam.clock()),engine,iam.clock(),Duration.ofSeconds(3)));}
        @Bean ApprovalService approvals(TraceStore t){return new ApprovalService(t);}
        @Bean ChunkBus chunks(){return new ChunkBus();}
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true);}
        @Bean ServiceAuthorization authorization(){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),mappings);}
    }
    @BeforeAll static void start()throws Exception{
        iam=new LoopbackAuthority();engine=iam.client("engine");gateway=iam.client("gateway");
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean existed=admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,DATABASE);System.out.println("N3_ENGINE_DB_EXISTED="+existed);if(!existed)admin.execute("create database "+DATABASE);
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password);db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo(DATABASE);
        try(var files=Files.list(Path.of("src/main/resources/db/migration"))){assertThat(files.map(p->p.getFileName().toString()).filter(n->n.matches("V\\d+__.*\\.sql")).mapToInt(n->Integer.parseInt(n.substring(1,n.indexOf("__")))).max().orElseThrow()).isEqualTo(217);}
        System.out.println("N3_ENGINE_SOURCE_MAX=217");boolean history=db.queryForObject("select to_regclass('engine.flyway_schema_history') is not null",Boolean.class);System.out.println("N3_ENGINE_HISTORY_BEFORE="+Json.write(history?db.queryForList("select version,description,checksum,success from engine.flyway_schema_history order by installed_rank"):List.of()));
        Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").target("217").load().migrate();
        System.out.println("N3_ENGINE_HISTORY_AFTER="+Json.write(db.queryForList("select version,description,checksum,success from engine.flyway_schema_history order by installed_rank")));
        assertThat(db.queryForObject("select count(*) from engine.flyway_schema_history where success and version::integer between 200 and 217",Integer.class)).isEqualTo(18);
        var cp=Files.readString(ROOT.resolve(".local/iam-n3-admission-app-client/app-classpath.txt"));
        for(String entry:cp.split(";")){String rel=ROOT.relativize(Path.of(entry)).toString().replace('\\','/');assertThat(rel.startsWith(".tools/m2/")||rel.startsWith("app-management/target/")||rel.startsWith("admin-common/target/")).as(rel).isTrue();}
        assertThat(System.getProperty("java.class.path")).doesNotContain("app-management");
        child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-cp",cp,"cn.agentplatform.common.authority.PublishedAdmissionFixtureMain").directory(ROOT.toFile()).redirectErrorStream(true).start();
        drain=Thread.ofPlatform().daemon(true).start(()->{try(var reader=child.inputReader(StandardCharsets.UTF_8)){for(String l;(l=reader.readLine())!=null;){lines.add(l);synchronized(childOutput){childOutput.append(l).append('\n');}}}catch(IOException e){lines.add("CHILD_IO_FAILURE");}});
        control=child.outputWriter(StandardCharsets.UTF_8);control.write(Json.write(Map.of("trust",iam.config("app"),"secret",iam.testClientSecret("app"),"now",iam.clock().instant().toString())));control.newLine();control.flush();
        var ready=awaitLine("N3_APP_READY=",30);appPort=Json.read(ready).path("port").asInt();assertThat(appPort).isPositive();System.out.println("N3_APP_STARTED="+ready);
    }
    @BeforeEach void seed()throws Exception{
        tenant="n3-"+UUID.randomUUID();iam.revoked(false);iam.unavailable(false);iam.decisionMutation(c->{});iam.ticketMutation(c->{});iam.portalActor(actor(Set.of("A","B")));source=iam.portal();
        command(Map.of("action","clock","now",iam.clock().instant().toString()));versionId=command(Map.of("action","seed","tenant",tenant,"snapshot",snapshot(),"sha",SHA,"sha2",SHA2)).path("versionId").asLong();
        var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n3-engine-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));context=(ServletWebServerApplicationContext)app.run();System.out.println("N3_ENGINE_STARTED="+context.getWebServer().getPort());
    }
    @AfterEach void stop(TestInfo info){if(context!=null){context.close();context=null;}System.out.println("N3_CASE="+Json.write(Map.of("test",info.getDisplayName(),"tenant",tenant,"runs",count("run"),"nodes",count("node_instance"),"attempts",count("attempt"),"scopes",count("material_scope"))));db.update("update engine.run_authority set blocked_reason='AUTHORITY_DENIED',retry_not_before=null where tenant_id=?",tenant);}
    @AfterAll static void close()throws Exception{
        try{if(control!=null){control.write("{\"action\":\"STOP\"}\n");control.flush();control.close();}if(child!=null){assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();drain.join(2000);assertThat(child.exitValue()).isZero();System.out.println("N3_CHILD_EXIT="+child.pid()+":0");}synchronized(childOutput){System.out.print(childOutput);}}
        finally{if(child!=null&&child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);}}if(engine!=null)engine.close();if(gateway!=null)gateway.close();if(iam!=null)iam.close();}
    }
    static Actor actor(Set<String> regions){return new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("region",regions,"dataLabels",Set.of("initial")));}
    static JsonNode snapshot(){return Json.tree(Map.of("appCode",CODE,"appVersionId",CODE+"@1","versionNo",1,"candidateSha256",SHA,"type","FLOW","config",Map.of("guard",Map.of("roles",List.of("EDITOR"))),"definition",Map.of("entry","start","nodes",List.of(Map.of("id","start","type","START"),Map.of("id","end","type","END")),"edges",List.of(Map.of("from","start","to","end")))));}
    static String awaitLine(String prefix,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){String line=lines.poll(100,TimeUnit.MILLISECONDS);if(line!=null&&line.startsWith(prefix))return line.substring(prefix.length());if(!child.isAlive())break;}synchronized(childOutput){throw new AssertionError("Finite child deadline: "+prefix+"\n"+childOutput);}}
    static JsonNode command(Object c)throws Exception{control.write(Json.write(c));control.newLine();control.flush();return Json.read(awaitLine("N3_APP_REPLY=",5));}
    static long count(String table){String sql=switch(table){case "run"->"select count(*) from engine.run where tenant_id=?";case "material_scope"->"select count(*) from engine.material_scope where tenant_id=?";case "node_instance"->"select count(*) from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?";case "attempt"->"select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id join engine.run r on r.id=n.run_id where r.tenant_id=?";default->throw new IllegalArgumentException();};return db.queryForObject(sql,Long.class,tenant);}
    static JsonNode input(String requestId){return Json.tree(Map.of("requestId",requestId,"inputs",Map.of("a",7)));}
    static HttpResponse<String> post(JsonNode request)throws Exception{return post(request,gateway.exchange(source,MAIN).value(),"/admin/apps/"+CODE+"/runs");}
    static HttpResponse<String> post(JsonNode request,String ticket,String path)throws Exception{try(var http=HttpClient.newHttpClient()){return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer "+ticket).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());}}
    @Test void actualPublishedAdmissionAndOwnedWorkerReturnFullStatus()throws Exception{
        var result=post(input("normal"));assertThat(result.statusCode()).as(result.body()).isEqualTo(200);var data=Json.read(result.body()).path("data");assertThat(data.path("runId").asText()).startsWith("r_");
        for(String field:List.of("appCode","status","eventsWatermark","inputs","output","fileRefs","error","versionRefs","nodes","events","paused"))assertThat(data.has(field)).as(field).isTrue();assertThat(data.path("nodes")).hasSize(2);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(count("attempt")<2&&System.nanoTime()<deadline)Thread.sleep(10);
        var replay=post(input("normal"));assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);var complete=Json.read(replay.body()).path("data");assertThat(complete.path("runId")).isEqualTo(data.path("runId"));assertThat(complete.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(complete.path("output").path("a").asInt()).isEqualTo(7);assertThat(count("run")).isEqualTo(1);assertThat(count("attempt")).isEqualTo(2);assertThat(count("material_scope")).isGreaterThan(15);
        assertThat(db.queryForObject("select app_version_id from engine.run where tenant_id=?",Long.class,tenant)).isEqualTo(versionId);
    }
}
