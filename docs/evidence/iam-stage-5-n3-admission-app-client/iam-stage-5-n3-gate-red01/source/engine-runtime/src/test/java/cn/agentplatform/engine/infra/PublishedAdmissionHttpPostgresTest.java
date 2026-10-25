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
    static RunAuthorityFixture.CountingDataSource observed;
    static volatile java.util.function.Consumer<String> afterQuery=sql->{};
    static volatile Runnable beforeCreated=()->{};
    static volatile Runnable afterCreated=()->{};
    static final AtomicInteger createdCalls=new AtomicInteger(),legacyCalls=new AtomicInteger();
    record Sql(String sql,boolean transaction,int isolation,boolean readOnly){}
    static final List<Sql> statements=new CopyOnWriteArrayList<>();
    static final AtomicReference<String> sqlState=new AtomicReference<>();

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({RunController.class,PlatformErrors.class})
    static class Receiver {
        @Bean AuthorityProperties authorityProperties(){return iam.config("engine");}
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return db;}
        @Bean TraceStore trace(){return trace=new TraceStore(db,new DataSourceTransactionManager(ds),iam.clock());}
        @Bean RuntimeClients clients(){return clients=new RuntimeClients(iam.config("engine"),engine,"http://127.0.0.1:"+appPort,true){@Override public JsonNode get(String s,String p,Actor a){legacyCalls.incrementAndGet();return super.get(s,p,a);}@Override public JsonNode post(String s,String p,Object b,Actor a){legacyCalls.incrementAndGet();return super.post(s,p,b,a);}};}
        @Bean RunScheduler scheduler(TraceStore t){return scheduler=new RunScheduler(new RunScheduleRepository(t,true),2,1,10000,90000,1000){@Override public void created(long id,Admission admission){createdCalls.incrementAndGet();beforeCreated.run();super.created(id,admission);afterCreated.run();}};}
        @Bean RunService service(TraceStore t,RuntimeClients c,RunScheduler s){return service=new RunService(t,c,new FlowDriver(t,null),null,s,new RunAuthority(new RunAuthorityStore(ds,iam.clock()),engine,iam.clock(),Duration.ofSeconds(3)),new RunAuthorityStore(ds,iam.clock()),iam.clock());}
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
        observed=new RunAuthorityFixture.CountingDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password));
        ds=new DelegatingDataSource(observed){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{return observe(super.getConnection());}};db=new JdbcTemplate(ds);
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
        observed.beforeSql=sql->{};observed.afterMaterialCommit=()->{};observed.beforePayload=()->{};afterQuery=sql->{};beforeCreated=()->{};afterCreated=()->{};createdCalls.set(0);legacyCalls.set(0);statements.clear();sqlState.set(null);
        tenant="n3-"+UUID.randomUUID();iam.clearFiniteGrants();iam.revoked(false);iam.unavailable(false);iam.decisionMutation(c->{});iam.ticketMutation(c->{});iam.portalActor(actor(Set.of("A","B")));gateway.close();gateway=iam.client("gateway");source=iam.portal();
        command(Map.of("action","clock","now",iam.clock().instant().toString()));versionId=command(Map.of("action","seed","tenant",tenant,"snapshot",snapshot(),"sha",SHA,"sha2",SHA2)).path("versionId").asLong();
        var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/n3-engine-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));context=(ServletWebServerApplicationContext)app.run();System.out.println("N3_ENGINE_STARTED="+context.getWebServer().getPort());
    }
    @AfterEach void stop(TestInfo info){observed.beforeSql=sql->{};observed.afterMaterialCommit=()->{};observed.beforePayload=()->{};afterQuery=sql->{};beforeCreated=()->{};afterCreated=()->{};if(context!=null){context.close();context=null;}System.out.println("N3_CASE="+Json.write(Map.of("test",info.getDisplayName(),"tenant",tenant,"runs",count("run"),"nodes",count("node_instance"),"attempts",count("attempt"),"scopes",count("material_scope"),"createdCalls",createdCalls.get(),"legacyExternalCalls",legacyCalls.get())));db.update("update engine.run_authority set blocked_reason='AUTHORITY_DENIED',retry_not_before=null where tenant_id=?",tenant);}
    @AfterAll static void close()throws Exception{
        try{if(control!=null){control.write("{\"action\":\"STOP\"}\n");control.flush();control.close();}if(child!=null){assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();drain.join(2000);assertThat(child.exitValue()).isZero();System.out.println("N3_CHILD_EXIT="+child.pid()+":0");}synchronized(childOutput){System.out.print(childOutput);}}
        finally{if(child!=null&&child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);}}if(engine!=null)engine.close();if(gateway!=null)gateway.close();if(iam!=null)iam.close();}
    }
    static Actor actor(Set<String> regions){return new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("region",regions,"dataLabels",Set.of("initial")));}
    static JsonNode snapshot(){return Json.tree(Map.of("appCode",CODE,"appVersionId",CODE+"@1","versionNo",1,"candidateSha256",SHA,"type","FLOW","config",Map.of("guard",Map.of("roles",List.of("EDITOR"))),"definition",Map.of("entry","start","nodes",List.of(Map.of("id","start","type","START"),Map.of("id","end","type","END","args","${start.output}")),"edges",List.of(Map.of("from","start","to","end")))));}
    static String awaitLine(String prefix,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){String line=lines.poll(100,TimeUnit.MILLISECONDS);if(line!=null&&line.startsWith(prefix))return line.substring(prefix.length());if(!child.isAlive())break;}synchronized(childOutput){throw new AssertionError("Finite child deadline: "+prefix+"\n"+childOutput);}}
    static JsonNode command(Object c)throws Exception{control.write(Json.write(c));control.newLine();control.flush();return Json.read(awaitLine("N3_APP_REPLY=",5));}
    static long count(String table){String sql=switch(table){case "run"->"select count(*) from engine.run where tenant_id=?";case "material_scope"->"select count(*) from engine.material_scope where tenant_id=?";case "node_instance"->"select count(*) from engine.node_instance n join engine.run r on r.id=n.run_id where r.tenant_id=?";case "attempt"->"select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id join engine.run r on r.id=n.run_id where r.tenant_id=?";default->throw new IllegalArgumentException();};return db.queryForObject(sql,Long.class,tenant);}
    static JsonNode input(String requestId){return Json.tree(Map.of("requestId",requestId,"inputs",Map.of("a",7)));}
    static java.sql.Connection observe(java.sql.Connection connection){return(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(PublishedAdmissionHttpPostgresTest.class.getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{
        try{Object value=method.invoke(connection,args);if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql){var statement=(java.sql.PreparedStatement)value;return java.lang.reflect.Proxy.newProxyInstance(PublishedAdmissionHttpPostgresTest.class.getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{try{if(m.getName().startsWith("execute"))statements.add(new Sql(sql,org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),connection.getTransactionIsolation(),connection.isReadOnly()));var result=m.invoke(statement,a);if(m.getName().equals("executeQuery"))afterQuery.accept(sql);return result;}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof java.sql.SQLException failure)sqlState.set(failure.getSQLState());throw e.getCause();}});}return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });}
    static void latch(CountDownLatch value){try{if(!value.await(5,TimeUnit.SECONDS))throw new AssertionError("finite latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void setSnapshot(JsonNode snapshot)throws Exception{
        // Published rows are immutable. Every negative variant seeds another real published app;
        // no fixture UPDATE bypasses the app_version protection trigger.
        tenant="n3-"+UUID.randomUUID();iam.clearFiniteGrants();iam.portalActor(actor(Set.of("A","B")));gateway.close();gateway=iam.client("gateway");source=iam.portal();versionId=command(Map.of("action","seed","tenant",tenant,"snapshot",snapshot,"sha",SHA,"sha2",SHA2)).path("versionId").asLong();
    }
    static int appRequests()throws Exception{return command(Map.of("action","stats")).path("publishedRequests").asInt();}
    static void noAdmission(){assertThat(count("run")).isZero();assertThat(count("node_instance")).isZero();assertThat(count("material_scope")).isZero();assertThat(count("attempt")).isZero();}
    static JsonNode success(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return Json.read(response.body()).path("data");}
    static void denied(HttpResponse<String> response,int...codes){assertThat(response.statusCode()).as(response.body()).isIn(Arrays.stream(codes).boxed().toList());assertThat(Json.read(response.body()).path("data").isNull()||Json.read(response.body()).path("data").isMissingNode()).isTrue();}
    static void terminal()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(scheduler.activeWorkers()>0&&System.nanoTime()<until)Thread.sleep(10);assertThat(scheduler.activeWorkers()).isZero();}
    static HttpResponse<String> post(JsonNode request)throws Exception{return post(request,gateway.exchange(source,MAIN).value(),"/admin/apps/"+CODE+"/runs");}
    static HttpResponse<String> post(JsonNode request,String ticket,String path)throws Exception{try(var http=HttpClient.newHttpClient()){return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Authorization","Bearer "+ticket).POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),HttpResponse.BodyHandlers.ofString());}}
    @Test void actualPublishedAdmissionAndOwnedWorkerReturnFullStatus()throws Exception{
        var result=post(input("normal"));assertThat(result.statusCode()).as(result.body()).isEqualTo(200);var data=Json.read(result.body()).path("data");assertThat(data.path("runId").asText()).startsWith("r_");
        for(String field:List.of("appCode","status","eventsWatermark","inputs","output","fileRefs","error","versionRefs","nodes","events","paused"))assertThat(data.has(field)).as(field).isTrue();assertThat(data.path("nodes")).hasSize(2);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!"SUCCEEDED".equals(db.queryForObject("select status from engine.run where tenant_id=?",String.class,tenant))&&System.nanoTime()<deadline)Thread.sleep(10);
        var replay=post(input("normal"));assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);var complete=Json.read(replay.body()).path("data");assertThat(complete.path("runId")).isEqualTo(data.path("runId"));assertThat(complete.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(complete.path("output").path("a").asInt()).isEqualTo(7);assertThat(count("run")).isEqualTo(1);assertThat(count("attempt")).isEqualTo(2);assertThat(count("material_scope")).isGreaterThan(15);
        assertThat(db.queryForObject("select app_version_id from engine.run where tenant_id=?",Long.class,tenant)).isEqualTo(versionId);
        assertThat(db.queryForObject("select input_ref='pg:run/'||public_id||'/input' from engine.run where tenant_id=?",Boolean.class,tenant)).isTrue();assertThat(complete.path("events")).isNotEmpty();assertThat(complete.path("events").findValuesAsText("type")).contains("RUN_STARTED","NODE_STARTED","RUN_FINISHED");assertThat(legacyCalls).hasValue(0);
    }

    @Test void explicitVersionDebugStepAndV1AliasAreClosedBeforeAppSend()throws Exception{
        int sends=appRequests();
        for(String key:List.of("versionNo","candidateSha256","draftRev","breakpoints","mocks","sessionId","historyRefs","materialRefs","controlledReplay")){var request=(com.fasterxml.jackson.databind.node.ObjectNode)input(key);request.set(key,key.equals("versionNo")?Json.tree(1):Json.object());denied(post(request),403);}
        var step=(com.fasterxml.jackson.databind.node.ObjectNode)input("step");step.put("mode","STEP");denied(post(step),403);
        denied(post(input("alias"),gateway.exchange(source,MAIN).value(),"/v1/apps/"+CODE+"/runs"),403);
        assertThat(appRequests()).isEqualTo(sends);noAdmission();
    }
    @Test void unsupportedGraphAndEveryDependencyAreRejectedBeforeLegacyPreparation()throws Exception{
        for(String type:List.of("AGENT","LLM","SKILL","RETRIEVE","CONFIRM","UNKNOWN")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)value.path("definition").path("nodes").get(1)).put("type",type);setSnapshot(value);denied(post(input("node-"+type)),403);noAdmission();}
        for(String key:List.of("skillRefs","promptRefs","modelEntryId","modelBinding","tools","frozenPrompts","dataSources")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)value.path("config")).set(key,Json.tree(key.equals("modelEntryId")?1:List.of(Map.of("code","external","version",1))));setSnapshot(value);denied(post(input("config-"+key)),403);noAdmission();}
        for(String key:List.of("skillRef","promptRef","modelOverride")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)value.path("definition").path("nodes").get(1)).set(key,Json.tree(Map.of("code","external","version",1)));setSnapshot(value);denied(post(input("node-"+key)),403);noAdmission();}
        var model=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();model.set("dependencyVersions",Json.tree(Map.of("modelRefs",List.of(Map.of("modelEntryId",1)))));setSnapshot(model);denied(post(input("models")),403);noAdmission();assertThat(legacyCalls).hasValue(0);
    }
    @Test void explicitFlowNonemptyGraphAndOriginalInputDagBudgetGuardRemainRequired()throws Exception{
        for(String type:Arrays.asList(null,"CHAT","UNKNOWN")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();if(type==null)value.remove("type");else value.put("type",type);setSnapshot(value);denied(post(input("type-"+type)),403);noAdmission();}
        var empty=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)empty.path("definition")).set("nodes",Json.tree(List.of()));setSnapshot(empty);denied(post(input("empty")),403);noAdmission();
        var invalid=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("definition")).set("inputs",Json.tree(List.of(Map.of("name","required","type","string","required",true))));setSnapshot(invalid);denied(post(input("missing-input")),400);noAdmission();
        invalid=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("definition")).set("edges",Json.tree(List.of(Map.of("from","start","to","end"),Map.of("from","end","to","start"))));setSnapshot(invalid);denied(post(input("cycle")),400);noAdmission();
        invalid=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("config")).set("budgets",Json.tree(Map.of("maxSteps",0)));setSnapshot(invalid);denied(post(input("budget")),400);noAdmission();
        invalid=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("config")).set("guard",Json.tree(Map.of("roles",List.of("ADMIN"))));setSnapshot(invalid);denied(post(input("guard")),403);noAdmission();
    }
    @Test void emptyDependencyContainersAndNullsAreLegalPublishedStructure()throws Exception{
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();var config=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("config");for(String key:List.of("skillRefs","promptRefs","tools","frozenPrompts","dataSources"))config.set(key,Json.tree(List.of()));config.set("modelEntryId",Json.read("null"));config.set("modelBinding",Json.object());var node=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("definition").path("nodes").get(1);node.set("skillRef",Json.object());node.set("promptRef",Json.read("null"));node.set("modelOverride",Json.tree(List.of()));value.set("dependencyVersions",Json.tree(Map.of("modelRefs",List.of())));setSnapshot(value);success(post(input("empty-deps")));terminal();assertThat(count("run")).isEqualTo(1);assertThat(legacyCalls).hasValue(0);
    }
    @Test void remainingOwnedWorkerDependenciesAreRejectedBeforeAdmission()throws Exception{
        for(String key:List.of("skillRefs","promptRefs","ontology")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();value.set("dependencyVersions",Json.tree(Map.of(key,List.of("unsupported"))));setSnapshot(value);denied(post(input("dep-"+key)),403);noAdmission();}
        for(String key:List.of("tools","dataSources")){var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();((com.fasterxml.jackson.databind.node.ObjectNode)value.path("definition").path("nodes").get(1)).set(key,Json.tree(List.of("unsupported")));setSnapshot(value);denied(post(input("node-"+key)),403);noAdmission();}
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot();value.put("config","invalid-shape");setSnapshot(value);denied(post(input("config-shape")),403);noAdmission();
    }
    @Test void actualAppPointerChangeAndDisableCreateNothing()throws Exception{
        command(Map.of("action","pointer","tenant",tenant,"version",2,"status","PUBLISHED"));denied(post(input("changed")),409);noAdmission();
        command(Map.of("action","pointer","tenant",tenant,"version",1,"status","DISABLED"));denied(post(input("disabled")),409);noAdmission();
    }
    @Test void repeatedPostUsesOneProtectedRrViewAndPreservesOriginalGrant()throws Exception{
        var initial=success(post(input("replay")));terminal();var binding=db.queryForMap("select grant_ref,initial_ceiling,origin_authentication_id from engine.run_authority where tenant_id=?",tenant);int sends=appRequests();statements.clear();int reads=observed.payloadReads.get();var again=success(post(input("replay")));assertThat(again.path("runId")).isEqualTo(initial.path("runId"));assertThat(appRequests()).isEqualTo(sends);assertThat(count("run")).isEqualTo(1);assertThat(count("attempt")).isEqualTo(2);assertThat(db.queryForMap("select grant_ref,initial_ceiling,origin_authentication_id from engine.run_authority where tenant_id=?",tenant)).isEqualTo(binding);assertThat(observed.payloadReads.get()).isGreaterThan(reads);
        assertThat(statements.stream().filter(s->s.sql.contains("request_id=?"))).allSatisfy(s->{assertThat(s.sql).doesNotContain("select *","snapshot","inputs");assertThat(s.transaction).isFalse();});
        var payload=statements.stream().filter(s->s.sql.startsWith("select row_to_json(material)::text")||s.sql.matches("select (snapshot|inputs|version_refs|budget|output|error|definition)::text.*")).toList();assertThat(payload).isNotEmpty();assertThat(payload).allSatisfy(s->{assertThat(s.transaction).isTrue();assertThat(s.isolation).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);assertThat(s.readOnly).isTrue();});
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)input("replay");changed.set("inputs",Json.tree(Map.of("a",8)));denied(post(changed),409);assertThat(count("run")).isEqualTo(1);
    }
    @Test void changedMainRootAndMissingScopesRejectBeforeEnginePayload()throws Exception{
        success(post(input("root")));terminal();int reads=observed.payloadReads.get();var changed=new ServiceCall("engine",MAIN.action(),new ResourceBinding("app-version",CODE+"@2",SHA2));denied(post(input("root"),gateway.exchange(source,changed).value(),"/admin/apps/"+CODE+"/runs"),403,409);assertThat(observed.payloadReads.get()).isEqualTo(reads);
        new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(tx->{db.execute("alter table engine.material_scope disable trigger material_scope_immutable");try{db.update("delete from engine.material_scope where tenant_id=? and kind='run-inputs'",tenant);}finally{db.execute("alter table engine.material_scope enable trigger material_scope_immutable");}return null;});
        assertThat(db.queryForObject("select tgenabled::text from pg_trigger where tgrelid='engine.material_scope'::regclass and tgname='material_scope_immutable'",String.class)).isEqualTo("O");
        denied(post(input("root")),403);assertThat(observed.payloadReads.get()).isEqualTo(reads);
    }
    @Test void narrowedCurrentCannotAdmitInitialABOrReadItsAcceptedMaterials()throws Exception{
        String ticket=gateway.exchange(source,MAIN).value();iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));});denied(post(input("narrow"),ticket,"/admin/apps/"+CODE+"/runs"),403);noAdmission();iam.decisionMutation(c->{});success(post(input("full")));terminal();int reads=observed.payloadReads.get();String retry=gateway.exchange(source,MAIN).value();iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));});denied(post(input("full"),retry,"/admin/apps/"+CODE+"/runs"),403);assertThat(observed.payloadReads.get()).isEqualTo(reads);assertThat(count("run")).isEqualTo(1);
    }
    @Test void everyAdmissionWriteFailureRollsBackRunAuthorityNodesAndScopes()throws Exception{
        for(String prefix:List.of("insert into engine.run(","insert into engine.run_authority(","insert into engine.node_instance(")){observed.beforeSql=sql->{if(sql.startsWith(prefix))throw new PlatformException(500,"N3_INJECTED_WRITE_FAILURE","有限故障注入");};denied(post(input("failure-"+prefix.hashCode())),500);observed.beforeSql=sql->{};noAdmission();}
        for(int index:List.of(1,4,7,8,12,15)){var writes=new AtomicInteger();observed.beforeSql=sql->{if(sql.startsWith("insert into engine.material_scope")&&writes.incrementAndGet()==index)throw new PlatformException(500,"N3_INJECTED_SCOPE_FAILURE_"+index,"有限故障注入");};denied(post(input("scope-failure-"+index)),500);observed.beforeSql=sql->{};assertThat(writes).hasValue(index);noAdmission();}
    }
    @Test void expiryDuringNodeMaterialsRollsBackButAfterActualCommitPreservesPendingAndSlot()throws Exception{
        var nodes=new AtomicInteger();observed.beforeSql=sql->{if(sql.startsWith("insert into engine.node_instance")&&nodes.incrementAndGet()==2)iam.advanceClock(Duration.ofSeconds(6));};denied(post(input("node-expiry")),403);observed.beforeSql=sql->{};noAdmission();command(Map.of("action","clock","now",iam.clock().instant().toString()));
        var once=new AtomicBoolean();observed.afterMaterialCommit=()->{if(once.compareAndSet(false,true))iam.advanceClock(Duration.ofSeconds(6));};denied(post(input("commit-expiry")),403);observed.afterMaterialCommit=()->{};assertThat(once).isTrue();assertThat(count("run")).isEqualTo(1);assertThat(count("node_instance")).isEqualTo(2);assertThat(count("material_scope")).isEqualTo(15);assertThat(count("attempt")).isZero();assertThat(createdCalls).hasValue(0);assertThat(scheduler.available(cn.agentplatform.engine.domain.RunDispatch.Queue.INTERACTIVE)).isEqualTo(2);
        assertThat(db.queryForObject("select status from engine.run where tenant_id=?",String.class,tenant)).isEqualTo("PENDING");success(post(input("commit-expiry")));assertThat(count("run")).isEqualTo(1);scheduler.poll();terminal();assertThat(db.queryForObject("select status from engine.run where tenant_id=?",String.class,tenant)).isEqualTo("SUCCEEDED");
    }
    @Test void ambientTransactionAndMissingFrameRejectBeforeAnyAuthorityOrAppIo()throws Exception{
        int sends=appRequests(),calls=iam.requestAttempts();var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));tx.execute(status->{assertThatThrownBy(()->service.create(CODE,input("ambient"),"TASK",actor(Set.of("A","B")))).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.code()).isEqualTo("AUTHORITY_TRANSACTION_FORBIDDEN"));return null;});assertThatThrownBy(()->service.create(CODE,input("missing"),"TASK",actor(Set.of("A","B")))).isInstanceOf(PlatformException.class);assertThat(iam.requestAttempts()).isEqualTo(calls);assertThat(appRequests()).isEqualTo(sends);noAdmission();
    }
    @Test void workerCanFinishBeforeCreationStatusAndNarrowingStillLeaksNoPayload()throws Exception{
        afterCreated=()->{try{terminal();}catch(Exception e){throw new AssertionError(e);}};var data=success(post(input("fast-worker")));assertThat(data.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(data.path("output").path("a").asInt()).isEqualTo(7);assertThat(data.path("events").findValuesAsText("type")).contains("RUN_STARTED","RUN_FINISHED");
        terminal();iam.clearFiniteGrants();setSnapshot(snapshot());var atBarrier=new AtomicInteger();afterCreated=()->{try{terminal();}catch(Exception e){throw new AssertionError(e);}atBarrier.set(observed.payloadReads.get());iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));});};denied(post(input("narrow-response")),403);assertThat(observed.payloadReads.get()).isEqualTo(atBarrier.get());assertThat(count("run")).isEqualTo(1);assertThat(db.queryForObject("select status from engine.run where tenant_id=?",String.class,tenant)).isEqualTo("SUCCEEDED");
    }
    @Test void legacyAcceptedRowWithoutAuthorityIsNeverReadOrBackfilled()throws Exception{
        db.update("insert into engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,request_id) values(?, ?,?,'TASK','PENDING','alice',?,'fixture','{}','{}','{}','{}','{}','legacy')","r_legacy_"+UUID.randomUUID(),CODE,versionId,tenant);int reads=observed.payloadReads.get(),sends=appRequests();denied(post(input("legacy")),403);assertThat(observed.payloadReads.get()).isEqualTo(reads);assertThat(appRequests()).isEqualTo(sends);assertThat(count("material_scope")).isZero();assertThat(db.queryForObject("select count(*) from engine.run_authority where tenant_id=?",Long.class,tenant)).isZero();
    }
    @Test void persistLockRaceReturnsSameRunOutsideRcWithoutDuplicateNudge()throws Exception{
        var snapshots=new CountDownLatch(2);var release=new CountDownLatch(1);
        afterQuery=sql->{if(sql.contains("request_id=?")&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){snapshots.countDown();latch(release);}};
        try(var tasks=Executors.newFixedThreadPool(2)){
            String one=gateway.exchange(source,MAIN).value(),two=gateway.exchange(source,MAIN).value();var first=tasks.submit(()->post(input("race"),one,"/admin/apps/"+CODE+"/runs"));var second=tasks.submit(()->post(input("race"),two,"/admin/apps/"+CODE+"/runs"));latch(snapshots);release.countDown();var a=success(first.get(12,TimeUnit.SECONDS));var b=success(second.get(12,TimeUnit.SECONDS));assertThat(a.path("runId")).isEqualTo(b.path("runId"));terminal();assertThat(count("run")).isEqualTo(1);assertThat(count("node_instance")).isEqualTo(2);assertThat(count("attempt")).isEqualTo(2);assertThat(createdCalls).hasValue(1);assertThat(statements.stream().filter(s->s.sql.contains("request_id=?")&&s.transaction).count()).isEqualTo(2);
        }finally{release.countDown();afterQuery=sql->{};}
    }
    @Test void capacityFullRecheckWaitsForCommittedSameRequestAndReadsOnlyAfterLock()throws Exception{
        var firstInsert=new CountDownLatch(1);var allowInsert=new CountDownLatch(1);var firstCommitted=new CountDownLatch(1);var allowCreated=new CountDownLatch(1);var secondSnapshot=new CountDownLatch(1);var allowSecond=new CountDownLatch(1);var firstTime=new AtomicBoolean();
        observed.beforeSql=sql->{if(sql.startsWith("insert into engine.run(")&&firstTime.compareAndSet(false,true)){firstInsert.countDown();latch(allowInsert);}};
        beforeCreated=()->{firstCommitted.countDown();latch(allowCreated);};
        try(var occupied=scheduler.reserveInteractive();var tasks=Executors.newFixedThreadPool(2)){
            String one=gateway.exchange(source,MAIN).value(),two=gateway.exchange(source,MAIN).value();var first=tasks.submit(()->post(input("capacity"),one,"/admin/apps/"+CODE+"/runs"));latch(firstInsert);
            afterQuery=sql->{if(sql.contains("request_id=?")&&!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){secondSnapshot.countDown();latch(allowSecond);}};
            var second=tasks.submit(()->post(input("capacity"),two,"/admin/apps/"+CODE+"/runs"));latch(secondSnapshot);allowInsert.countDown();latch(firstCommitted);allowSecond.countDown();var b=success(second.get(10,TimeUnit.SECONDS));allowCreated.countDown();var a=success(first.get(10,TimeUnit.SECONDS));assertThat(a.path("runId")).isEqualTo(b.path("runId"));assertThat(count("run")).isEqualTo(1);assertThat(createdCalls).hasValue(1);assertThat(statements.stream().filter(s->s.sql.contains("request_id=?")&&s.transaction).count()).isEqualTo(2);
        }finally{allowInsert.countDown();allowCreated.countDown();allowSecond.countDown();observed.beforeSql=sql->{};beforeCreated=()->{};afterQuery=sql->{};}terminal();
    }
    @Test void actualAdvisoryWaitCannotOutliveBoundProofAndLockTimeoutIsFinite()throws Exception{
        try(var lock=ds.getConnection()){
            lock.setAutoCommit(false);try(var sql=lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){sql.setString(1,tenant+":alice:locked");sql.execute();}
            var waiting=new CountDownLatch(1);observed.beforeSql=sql->{if(sql.contains("pg_advisory_xact_lock"))waiting.countDown();};
            try(var task=Executors.newSingleThreadExecutor()){
                String ticket=gateway.exchange(source,MAIN).value();var request=task.submit(()->post(input("locked"),ticket,"/admin/apps/"+CODE+"/runs"));latch(waiting);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(db.queryForObject("select count(*) from pg_locks where locktype='advisory' and database=(select oid from pg_database where datname=current_database()) and not granted",Long.class)==0&&System.nanoTime()<until)Thread.sleep(5);iam.advanceClock(Duration.ofSeconds(6));lock.commit();denied(request.get(5,TimeUnit.SECONDS),403);noAdmission();
            }finally{observed.beforeSql=sql->{};lock.rollback();}
        }
        command(Map.of("action","clock","now",iam.clock().instant().toString()));
        try(var lock=ds.getConnection()){
            lock.setAutoCommit(false);try(var sql=lock.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){sql.setString(1,tenant+":alice:timeout");sql.execute();}
            long start=System.nanoTime();denied(post(input("timeout")),403);assertThat(sqlState).hasValue("55P03");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));lock.rollback();noAdmission();
        }
    }
}
