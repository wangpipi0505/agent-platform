package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.api.RunObservationMetadataController;
import cn.agentplatform.engine.application.RunObservationMetadataService;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.*;
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
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.*;

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
    static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("candidate"));
    static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
    record Sql(String sql,int connection,int isolation,boolean readOnly,boolean autoCommit){}
    static final List<Sql> statements=new CopyOnWriteArrayList<>();
    static volatile Consumer<String> afterQuery=sql->{};
    static volatile Runnable afterReadCommit=()->{};
    static volatile Consumer<jakarta.servlet.http.HttpServletRequest> beforeController=r->{};
    static final AtomicInteger controllerBoundaries=new AtomicInteger();
    static volatile boolean omitGuard;
    static final List<String> rawQueries=new CopyOnWriteArrayList<>();

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
        @Bean RunObservationMetadataService observations(){return new RunObservationMetadataService(db);}
        @Bean RunObservationMetadataController metadata(RunObservationMetadataService service){return new RunObservationMetadataController(service);}
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true);}
        @Bean ServiceAuthorization authorization(){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return omitGuard?new org.springframework.web.servlet.handler.MappedInterceptor(null,new org.springframework.web.servlet.HandlerInterceptor(){}):a.mappedInterceptor();}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor requestBoundary(){return new org.springframework.web.servlet.handler.MappedInterceptor(null,new org.springframework.web.servlet.HandlerInterceptor(){
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler){
                if(handler instanceof HandlerMethod h&&h.getBeanType()==RunObservationMetadataController.class){
                    controllerBoundaries.incrementAndGet();rawQueries.add(request.getQueryString()==null?"<null>":request.getQueryString());beforeController.accept(request);
                }return true;
            }
        });}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),mappings);}
    }
    @BeforeAll static void start()throws Exception {
        iam=new LoopbackAuthority();engine=iam.client("engine");
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean existed=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,DATABASE));
        if(!existed)admin.execute("create database "+DATABASE);
        ds=new DelegatingDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password)){
            @Override public Connection getConnection()throws SQLException{return observe(super.getConnection());}
        };db=new JdbcTemplate(ds);
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
        afterQuery=s->{};afterReadCommit=()->{};beforeController=r->{};statements.clear();rawQueries.clear();controllerBoundaries.set(0);
    }
    @AfterEach void evidence(TestInfo info){
        System.out.println("O1A_CASE="+Json.write(Map.of("test",info.getDisplayName(),"sql",List.copyOf(statements),"controllerBoundaries",controllerBoundaries.get(),"rawQueries",List.copyOf(rawQueries),"authorityCalls",iam.authorityCalls())));
        afterQuery=s->{};afterReadCommit=()->{};beforeController=r->{};
    }
    @AfterAll static void close(){if(context!=null)context.close();if(gateway!=null)gateway.close();if(engine!=null)engine.close();if(iam!=null)iam.close();System.out.println("O1A_RESOURCES_CLOSED=true");}
    static ResourceBinding selector(String run){return RunObservationSelectors.metadata(new SubjectKey(tenant,"alice"),run);}
    static ServiceCall query(String run){return new ServiceCall("engine",RunObservationSelectors.ACTION,selector(run));}
    static String path(String run){return "/internal/runs/"+run+"/observation-metadata";}
    static ServiceTicket ticket(String run){return gateway.exchange(source,query(run));}
    static Connection observe(Connection connection){
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(RunObservationMetadataHttpPostgresTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{
                Object result=m.invoke(connection,a);
                if(m.getName().equals("commit")&&connection.isReadOnly())afterReadCommit.run();
                if(result instanceof Statement statement&&(m.getName().equals("prepareStatement")||m.getName().equals("createStatement"))){
                    String prepared=a!=null&&a.length>0&&a[0] instanceof String?(String)a[0]:null;
                    Class<?> type=result instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                    return java.lang.reflect.Proxy.newProxyInstance(RunObservationMetadataHttpPostgresTest.class.getClassLoader(),new Class[]{type},(sp,sm,sa)->{
                        try{
                            String sql=prepared!=null?prepared:sa!=null&&sa.length>0&&sa[0] instanceof String?(String)sa[0]:null;
                            boolean execute=sm.getName().startsWith("execute")&&sql!=null;
                            if(execute)statements.add(new Sql(sql,System.identityHashCode(connection),connection.getTransactionIsolation(),connection.isReadOnly(),connection.getAutoCommit()));
                            Object value=sm.invoke(statement,sa);
                            if(execute&&sm.getName().equals("executeQuery"))afterQuery.accept(sql);
                            return value;
                        }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                }return result;
            }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
    }
    static <T>T withFrame(ServiceTicket ticket,ServiceCall call,String method,String path,String template,Function<RetainedAuthorization,T> work)throws Exception {
        try(var decoder=new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true)){
            var request=new MockHttpServletRequest(method,path);var response=new MockHttpServletResponse();request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,template);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));
            var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);
            var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));
            try{guard.preHandle(request,response,handler);return work.apply(AuthorityContext.requireInitialFor(Actor.current(),call.resource()));}
            finally{guard.afterCompletion(request,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
        }
    }
    static long create()throws Exception {
        var admission=gateway.exchange(source,MAIN);
        return withFrame(admission,MAIN,"POST","/admin/apps/app-a/runs","/admin/apps/{code}/runs",proof->{
            var snapshot=Json.read("{\"appCode\":\"app-a\",\"type\":\"FLOW\",\"versionNo\":1,\"appVersionId\":4294967297,\"candidateSha256\":\""+ROOT.versionDigest()+"\",\"config\":{},\"definition\":{\"nodes\":[{\"id\":\"start\",\"type\":\"START\"},{\"id\":\"end\",\"type\":\"END\"}],\"edges\":[{\"source\":\"start\",\"target\":\"end\"}]}}");
            var budget=Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",iam.clock().instant().plusSeconds(599).toString()));
            return new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->new RunAuthorityStore(ds,iam.clock()).createPublished(proof,snapshot,Json.read("{\"privateInput\":\"do-not-return\"}"),Json.read("{\"appVer\":1}"),budget,"FULL",UUID.randomUUID().toString(),Set.of("initial")));
        });
    }
    static String publicId(long id){return db.queryForObject("select public_id from engine.run where id=?",String.class,id);}
    static void assertMetadataOnly(){
        assertThat(statements).isNotEmpty();
        assertThat(statements).allSatisfy(s->{
            assertThat(s.sql()).startsWith("select ");assertThat(s.sql()).doesNotContain("select *","snapshot::","inputs::","version_refs::","output::","error::","run_event","node_instance","engine.attempt"," for update");
            assertThat(s.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(s.readOnly()).isTrue();assertThat(s.autoCommit()).isFalse();
            assertThat(s.sql()).matches("(?s)select (r\\.id,r\\.public_id,.*from engine\\.run r where r\\.public_id=.*|r\\.id,r\\.public_id,.*a\\.\\* from engine\\.run r join engine\\.run_authority.*|tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope::text as scope from engine\\.material_scope.*)");
        });
        assertThat(statements.stream().map(Sql::connection).distinct().count()).isEqualTo(1);
    }
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
    @Test void actualHttpProjectsSevenFieldsFromReadOnlyRepeatableReadWithoutPayload()throws Exception {
        long id=create();String run=publicId(id);
        var expected=new RunAuthorityStore(ds,iam.clock()).load(id);
        db.update("update engine.run_authority set blocked_reason='AUTHORITY_DENIED',retry_not_before=null where run_id=?",id);
        bypass("update engine.run_authority set grant_expires_at=now()-interval '1 day' where run_id=?",id);
        var before=db.queryForMap("select check_version,blocked_reason,last_decision_id from engine.run_authority where run_id=?",id);
        var ticket=ticket(run);int authority=iam.authorityCalls();statements.clear();
        var response=request("GET",path(run),ticket,null);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(iam.authorityCalls()-authority).isEqualTo(2);assertThat(controllerBoundaries.get()).isEqualTo(1);
        JsonNode data=Json.read(response.body()).get("data");
        assertThat(data.size()).isEqualTo(7);assertThat(data.properties().stream().map(Map.Entry::getKey)).containsExactlyInAnyOrder("runId","owner","root","appVersionId","appVersionNo","acceptedSnapshotSha256","initialCeilingSha256");
        assertThat(data.get("runId").asText()).isEqualTo(run);assertThat(data.get("owner")).isEqualTo(Json.tree(new SubjectKey(tenant,"alice")));assertThat(data.get("root")).isEqualTo(Json.tree(ROOT));
        assertThat(data.get("appVersionId").longValue()).isEqualTo(4294967297L);assertThat(data.get("appVersionNo").longValue()).isEqualTo(1L);
        assertThat(data.get("acceptedSnapshotSha256").asText()).isEqualTo(expected.snapshotSha());assertThat(data.get("initialCeilingSha256").asText()).isEqualTo(expected.initialSha());
        assertThat(Json.write(data).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        assertThat(response.body()).doesNotContain("grant_ref","grantRef","initial_ceiling","initialCeiling\"","Bearer","privateInput","do-not-return","snapshot\"","inputs\"","output\"","events\"");
        assertMetadataOnly();assertThat(statements).hasSize(3);
        System.out.println("O1A_SUCCESS="+Json.write(Map.of("response",Json.read(response.body()),"dataUtf8Bytes",Json.write(data).getBytes(StandardCharsets.UTF_8).length,"sql",List.copyOf(statements),"authorityDelta",iam.authorityCalls()-authority)));
        statements.clear();assertThat(db.queryForMap("select check_version,blocked_reason,last_decision_id from engine.run_authority where run_id=?",id)).isEqualTo(before);
    }
    @Test void selectorPathOwnerTenantAndDigestCannotSubstituteAnotherRun()throws Exception {
        long id=create(),other=create();String run=publicId(id),otherRun=publicId(other);
        var ticket=ticket(run);statements.clear();int calls=iam.authorityCalls();
        assertThat(request("GET",path(otherRun),ticket,null).statusCode()).isEqualTo(403);
        assertThat(statements).isEmpty();assertThat(iam.authorityCalls()-calls).isEqualTo(1);
        for(String field:List.of("id","version_digest")){
            var claims=claims(ticket);var resource=(Map<String,Object>)claims.get("resource");resource.put(field,field.equals("id")?selector(otherRun).id():Digest.sha256("tamper"));
            var forged=sign(claims);statements.clear();int count=iam.authorityCalls();
            assertThat(sendValue("GET",path(run),forged,null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();
            assertThat(iam.authorityCalls()-count).isLessThanOrEqualTo(1);
        }
        for(SubjectKey owner:List.of(new SubjectKey(tenant,"bob"),new SubjectKey("other-tenant","alice"))){
            var claims=claims(ticket);claims.put("tenant_id",owner.tenantId());claims.put("sub",owner.principalId());
            var bound=RunObservationSelectors.metadata(owner,run);claims.put("resource",Map.of("kind",bound.kind(),"id",bound.id(),"version_digest",bound.versionDigest()));
            statements.clear();assertThat(sendValue("GET",path(run),sign(claims),null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();
        }
    }
    static Map<String,Object> claims(ServiceTicket ticket){
        return Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(ticket.value().split("\\.")[1]),StandardCharsets.UTF_8)),Map.class);
    }
    static String sign(Map<String,Object> claims){return iam.sign(claims,iam.authoritySigningKey(),"at+jwt");}
    static HttpResponse<String> sendValue(String method,String path,String value,String body)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).header("Authorization","Bearer "+value).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void wrongPurposeClaimsAndQueryTicketOnOtherRoutesFailBeforeMetadata()throws Exception {
        long id=create();String run=publicId(id);var ticket=ticket(run);
        for(String field:List.of("action","caller","target","authority_mode","source_kind","origin_kind")){
            var c=claims(ticket);
            if(field.equals("source_kind")||field.equals("origin_kind"))((Map<String,Object>)c.get("operation")).put(field,"INTEGRATION");
            else c.put(field,switch(field){case "action"->"run.advance";case "caller"->"app";case "target"->"app";default->"workload";});
            int before=iam.authorityCalls();statements.clear();
            assertThat(sendValue("GET",path(run),sign(c),null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();assertThat(iam.authorityCalls()-before).isZero();
        }
        for(String target:List.of("/admin/runs/"+run,"/admin/runs/"+run+"/events","/admin/debug-runs/"+run+"/status")){
            int before=iam.authorityCalls();statements.clear();assertThat(request("GET",target,ticket,null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();assertThat(iam.authorityCalls()-before).isZero();
        }
        int before=iam.authorityCalls();statements.clear();assertThat(request("POST","/admin/apps/app-a/runs",ticket,"{}").statusCode()).isEqualTo(403);assertThat(iam.authorityCalls()-before).isZero();assertThat(statements).isEmpty();
    }
    @Test void notFoundAndOtherOwnerHaveTheSame404WhileOwnedLegacyHas403()throws Exception {
        long id=create();String run=publicId(id);
        var nonexistent=ticket(RUN);statements.clear();var missing=request("GET",path(RUN),nonexistent,null);assertThat(missing.statusCode()).isEqualTo(404);assertThat(statements).hasSize(1);assertMetadataOnly();
        iam.portalActor(new Actor(tenant,"bob",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));gateway.close();gateway=iam.client("gateway");String bob=iam.portal();
        var bobSelector=RunObservationSelectors.metadata(new SubjectKey(tenant,"bob"),run);var bobTicket=gateway.exchange(bob,new ServiceCall("engine",RunObservationSelectors.ACTION,bobSelector));
        statements.clear();var hidden=request("GET",path(run),bobTicket,null);assertThat(hidden.statusCode()).isEqualTo(404);assertThat(Json.read(hidden.body())).isEqualTo(Json.read(missing.body()));assertThat(statements).hasSize(1);assertMetadataOnly();
        iam.portalActor(new Actor("other-tenant","alice",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));gateway.close();gateway=iam.client("gateway");
        var otherSelector=RunObservationSelectors.metadata(new SubjectKey("other-tenant","alice"),run);var otherTicket=gateway.exchange(iam.portal(),new ServiceCall("engine",RunObservationSelectors.ACTION,otherSelector));
        statements.clear();var otherResponse=request("GET",path(run),otherTicket,null);assertThat(otherResponse.statusCode()).isEqualTo(404);assertThat(Json.read(otherResponse.body())).isEqualTo(Json.read(missing.body()));assertMetadataOnly();
        iam.portalActor(new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));gateway.close();gateway=iam.client("gateway");source=iam.portal();
        bypass("delete from engine.material_scope where run_id=?",id);bypass("delete from engine.run_authority where run_id=?",id);
        var legacy=ticket(run);statements.clear();assertThat(request("GET",path(run),legacy,null).statusCode()).isEqualTo(403);assertMetadataOnly();
    }
    static void bypass(String sql,long id){
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{
            db.execute("alter table engine.run disable trigger user");db.execute("alter table engine.run_authority disable trigger user");db.execute("alter table engine.material_scope disable trigger user");
            try{db.update(sql,id);}finally{db.execute("alter table engine.material_scope enable trigger user");db.execute("alter table engine.run_authority enable trigger user");db.execute("alter table engine.run enable trigger user");}return null;
        });
    }
    @Test void damagedControlMetadataAndMissingOrInconsistentRootManifestsFailClosed()throws Exception {
        for(String mutation:List.of(
            "delete from engine.material_scope where run_id=? and kind='run-inputs'",
            "update engine.run_authority set root_id='different@1' where run_id=?",
            "update engine.run_authority set app_version_id=4294967298 where run_id=?",
            "update engine.run_authority set app_version_no=2 where run_id=?",
            "update engine.run_authority set principal_id='bob' where run_id=?",
            "update engine.run_authority set initial_ceiling_sha256=repeat('a',64) where run_id=?",
            "update engine.run_authority set accepted_snapshot_sha256=repeat('b',64) where run_id=?",
            "update engine.run_authority set initial_ceiling=jsonb_set(initial_ceiling,'{roles}','[]'::jsonb) where run_id=?",
            "update engine.material_scope set scope_sha256=repeat('c',64) where run_id=? and kind='run-snapshot'",
            "update engine.material_scope set scope=jsonb_set(scope,'{parentScopeSha256}','[]'::jsonb) where run_id=? and kind='run-version-refs'",
            "update engine.run set mode='DEBUG' where id=?")){
            long id=create();String run=publicId(id);bypass(mutation,id);var ticket=ticket(run);statements.clear();
            var response=request("GET",path(run),ticket,null);assertThat(response.statusCode()).as(mutation+" -> "+response.body()).isEqualTo(403);assertMetadataOnly();
            System.out.println("O1A_CORRUPTION="+Json.write(Map.of("mutation",mutation,"status",response.statusCode(),"sql",List.copyOf(statements))));
        }
    }
    @Test void rawQueryBareQuestionBodyAndDispatcherAreRejectedAtRealHandlerBoundary()throws Exception {
        long id=create();String run=publicId(id);var ticket=ticket(run);
        for(String suffix:List.of("?x=1","?")){
            statements.clear();int before=controllerBoundaries.get();String response=raw("GET",path(run)+suffix,ticket.value(),"");
            assertThat(response).startsWith("HTTP/1.1 400");assertThat(controllerBoundaries.get()-before).isEqualTo(1);assertThat(rawQueries.getLast()).isEqualTo(suffix.substring(1));assertThat(statements).isEmpty();
            System.out.println("O1A_RAW="+Json.write(Map.of("requestTarget",path(run)+suffix,"responseStatus",response.lines().findFirst().orElseThrow(),"queryString",rawQueries.getLast())));
        }
        statements.clear();assertThat(raw("GET",path(run),ticket.value(),"{}")).startsWith("HTTP/1.1 400");assertThat(statements).isEmpty();
        // A real HTTP request is forwarded by an interceptor after its outer service frame exists.
        var dispatched=new AtomicBoolean();beforeController=r->{if(dispatched.compareAndSet(false,true))try{r.getRequestDispatcher(path(run)).forward(r,(jakarta.servlet.ServletResponse)((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getResponse());}catch(Exception e){throw new IllegalStateException(e);}};
        statements.clear();assertThat(request("GET",path(run),ticket,null).statusCode()).isEqualTo(403);assertThat(dispatched).isTrue();assertThat(statements).isEmpty();beforeController=r->{};
        statements.clear();assertThat(request("GET",path(run)+"/alias",ticket,null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();
    }
    static String raw(String method,String target,String ticket,String body)throws Exception {
        try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){
            socket.setSoTimeout(10000);var output=socket.getOutputStream();byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            output.write((method+" "+target+" HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "+ticket+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void currentRevocationOutageAndExpiryBlockBeforeSqlAndNoLegacyFallback()throws Exception {
        long id=create();String run=publicId(id);
        var ticket=ticket(run);statements.clear();iam.revoked(true);assertThat(request("GET",path(run),ticket,null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();iam.revoked(false);
        ticket=ticket(run);statements.clear();iam.unavailable(true);assertThat(request("GET",path(run),ticket,null).statusCode()).isEqualTo(503);assertThat(statements).isEmpty();iam.unavailable(false);
        ticket=ticket(run);statements.clear();iam.advanceClock(Duration.ofSeconds(61));assertThat(request("GET",path(run),ticket,null).statusCode()).isEqualTo(403);assertThat(statements).isEmpty();
    }
    @Test void localExpiryOrClosedFrameAfterReadAndCommitPreventsAValidProjection()throws Exception {
        for(String boundary:List.of("manifest-expiry","manifest-frame-close","post-commit-expiry","post-commit-frame-close")){
            long id=create();String run=publicId(id);var ticket=ticket(run);var armed=new AtomicBoolean();
            Runnable invalidate=()->{if(armed.compareAndSet(false,true)){if(boundary.endsWith("expiry"))iam.advanceClock(Duration.ofSeconds(6));else{
                var attrs=(ServletRequestAttributes)RequestContextHolder.currentRequestAttributes();var frame=attrs.getRequest().getAttribute(ServiceAuthorization.class.getName()+".frame");((AuthorityContext)frame).close();
            }}};
            if(boundary.startsWith("manifest"))afterQuery=s->{if(s.contains("from engine.material_scope"))invalidate.run();};else afterReadCommit=invalidate;
            statements.clear();var response=request("GET",path(run),ticket,null);assertThat(response.statusCode()).as(boundary+" -> "+response.body()).isEqualTo(403);assertThat(armed).isTrue();assertMetadataOnly();
            afterQuery=s->{};afterReadCommit=()->{};source=iam.portal();
            System.out.println("O1A_LOCAL_TERMINAL="+Json.write(Map.of("boundary",boundary,"status",response.statusCode(),"sql",List.copyOf(statements))));
        }
    }
    @Test void ambientTransactionIsRejectedBeforeOnlineRefreshAndLegacyAssemblyNeedsNoIamBean()throws Exception {
        long id=create();String run=publicId(id);var ticket=ticket(run);var service=new RunObservationMetadataService(db);
        withFrame(ticket,query(run),"GET",path(run),"/internal/runs/{id}/observation-metadata",proof->{
            int before=iam.authorityCalls();statements.clear();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(()->tx.execute(s->service.metadata(run))).isInstanceOf(IllegalStateException.class).hasMessage("OBSERVATION_REQUIRES_NO_CALLER_TRANSACTION");
            assertThat(iam.authorityCalls()-before).isZero();assertThat(statements).isEmpty();return null;
        });
        assertThatThrownBy(()->service.metadata(run)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(403));
        try(var legacy=new AnnotationConfigApplicationContext()){
            legacy.registerBean(JdbcTemplate.class,()->db);legacy.register(RunObservationMetadataService.class,RunObservationMetadataController.class);legacy.refresh();
            assertThat(legacy.getBean(RunObservationMetadataController.class)).isNotNull();
            assertThatThrownBy(()->legacy.getBean(RunObservationMetadataService.class).metadata(run)).isInstanceOf(PlatformException.class);
        }
    }
    @Test void actualMappingWithoutInstalledGuardFailsStartupBeforeListening(){
        omitGuard=true;var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");
        app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/o1a-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","OFF")));
        try{assertThatThrownBy(app::run).hasStackTraceContaining("ROUTE_GUARD_MISSING");}finally{omitGuard=false;}
    }
}
