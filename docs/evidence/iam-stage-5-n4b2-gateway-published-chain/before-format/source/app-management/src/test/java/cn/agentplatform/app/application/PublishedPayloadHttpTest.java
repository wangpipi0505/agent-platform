package cn.agentplatform.app.application;

import cn.agentplatform.app.api.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
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
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import static org.assertj.core.api.Assertions.*;

/** Real app receiver + actual signed authority HTTP + dedicated PG. No Gateway/RunController execution. */
class PublishedPayloadHttpTest {
    static final String CODE="payload-demo", SHA=Digest.sha256("stored-candidate-one"), SHA2=Digest.sha256("stored-candidate-two");
    static LoopbackAuthority fixture;
    static DataSource ds;
    static JdbcTemplate db;
    static ServletWebServerApplicationContext app;
    static String tenant, source;
    static long versionId;
    static final AtomicInteger currents=new AtomicInteger(), writes=new AtomicInteger(), commits=new AtomicInteger();
    record Sql(String category,String sql,boolean transaction,boolean autoCommit,int connection,int isolation,boolean readOnly,int backendPid){}
    static final List<Sql> statements=new CopyOnWriteArrayList<>();
    static volatile Runnable beforeCurrent=()->{}, afterCurrent=()->{}, afterPayload=()->{}, afterCommit=()->{};
    static volatile Consumer<String> beforeSql=s->{}, afterSql=s->{};
    static volatile String mode="iam";

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({PublishedMetadataController.class,PublishedMetadataService.class,AppController.class,TemplateService.class,PlatformErrors.class})
    static class Receiver {
        @Bean DataSource dataSource(){return ds;}
        @Bean AuthorityProperties authorityProperties(){return mode.equals("iam")?fixture.config("app"):new AuthorityProperties(mode,"app",null,null,null,null,null,false,null);}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(ds){
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){
                String category=classify(sql);var con=DataSourceUtils.getConnection(ds);
                try{statements.add(new Sql(category,sql,TransactionSynchronizationManager.isActualTransactionActive(),con.getAutoCommit(),System.identityHashCode(con),con.getTransactionIsolation(),con.isReadOnly(),con.unwrap(org.postgresql.PGConnection.class).getBackendPID()));}
                catch(java.sql.SQLException e){throw new IllegalStateException(e);}finally{DataSourceUtils.releaseConnection(con,ds);}
                beforeSql.accept(category);var rows=super.query(sql,mapper,args);afterSql.accept(category);if(category.equals("payload"))afterPayload.run();return rows;
            }
            @Override public int update(String sql,Object...args){writes.incrementAndGet();return super.update(sql,args);}
        };}
        @Bean AppService apps(JdbcTemplate jdbc){
            // Pause only after actual PostgreSQL commit; commit() itself is final in Spring.
            var manager=new DataSourceTransactionManager(ds){@Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);commits.incrementAndGet();afterCommit.run();}};
            return new AppService(jdbc,manager,"http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1");
        }
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(fixture.config("app"),fixture.clock(),true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient wireCurrent(){return fixture.client("app");}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient actual){
            // Observe timing around the real client. Every result still comes from verified HTTP.
            CurrentAuthorityClient observed=(CurrentAuthorityClient)java.lang.reflect.Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),new Class<?>[]{CurrentAuthorityClient.class},(p,m,a)->{
                boolean current=m.getName().equals("authorizeRetained");int n=current?currents.incrementAndGet():0;
                if(current&&n==2)beforeCurrent.run();
                try{Object result=m.invoke(actual,a);if(current&&n==2)afterCurrent.run();return result;}
                catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
            return new ServiceAuthorization("app",new ServiceRoutePolicy(),observed);
        }
        @Bean SecurityFilterChain security(HttpSecurity http,JwtDecoder decoder,ServiceAuthorization receiver)throws Exception{return SecurityConfiguration.serviceSecurity(http,decoder,receiver);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization receiver){return receiver.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("app",new ServiceRoutePolicy(),mappings);}
    }
    static String classify(String sql){
        String s=sql.toLowerCase(Locale.ROOT);
        if(s.contains("snapshot")||s.contains("app_draft")||s.contains("a.*")||s.contains("definition")||s.contains("layout"))return "payload";
        if(s.contains("for share of a"))return "application-lock";
        if(s.contains("left join app.app_version"))return TransactionSynchronizationManager.isActualTransactionActive()?"locked-metadata":"initial-metadata";
        return "other";
    }
    static long count(String category){return statements.stream().filter(s->s.category.equals(category)).count();}
    @BeforeAll static void start()throws Exception{
        String password=System.getenv("AGENT_DB_PASSWORD");assertThat(password).isNotBlank();
        var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
        boolean existed=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname='agent_platform_iam_app_payload_v12')",Boolean.class));
        System.out.println("D3B_DATABASE_EXISTED="+existed);if(!existed)admin.execute("CREATE DATABASE agent_platform_iam_app_payload_v12");
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_app_payload_v12","agent_v12",password);
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("classpath:db/migration").target("104").load().migrate();db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo("agent_platform_iam_app_payload_v12");
        fixture=new LoopbackAuthority();startReceiver();
    }
    static void startReceiver(){
        var application=new SpringApplication(Receiver.class);application.setAdditionalProfiles("test");
        application.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/d3b-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
        app=(ServletWebServerApplicationContext)application.run();
    }
    @AfterAll static void close(){if(app!=null)app.close();if(fixture!=null)fixture.close();}
    @BeforeEach void seed(){
        fixture.clearFiniteGrants();fixture.ticketMutation(c->{});fixture.revoked(false);fixture.unavailable(false);tenant="d3b-"+UUID.randomUUID();fixture.portalActor(new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("dataLabels",Set.of("confidential","restricted"),"region",Set.of("A"))));
        source=fixture.portal();seedApp(CODE,"PUBLISHED",1);seedApp("other","PUBLISHED",1);versionId=db.queryForObject("select id from app.app_version where tenant_id=? and app_code=? and version_no=1",Long.class,tenant,CODE);resetObservers();
    }
    static void resetObservers(){currents.set(0);writes.set(0);commits.set(0);statements.clear();beforeCurrent=()->{};afterCurrent=()->{};afterPayload=()->{};afterCommit=()->{};beforeSql=s->{};afterSql=s->{};}
    @AfterEach void audit(TestInfo info){
        beforeCurrent=()->{};afterCurrent=()->{};afterPayload=()->{};afterCommit=()->{};beforeSql=s->{};afterSql=s->{};fixture.revoked(false);fixture.unavailable(false);
        System.out.println("D3B_CASE="+Json.write(Map.of("test",info.getDisplayName(),"current",currents.get(),"businessWrites",writes.get(),"commits",commits.get(),"sql",statements)));
        // Finite same-row write proves no leaked FOR SHARE lock after success/failure.
        lockBoundedUpdate("status=status");assertThat(writes).hasValue(0);System.out.println("D3B_LOCK_RELEASED="+info.getDisplayName());
    }
    static void seedApp(String code,String state,Integer pointer){
        db.update("insert into app.application(tenant_id,code,name,type,status,current_version_no,created_by) values(?,?,'Payload fixture','FLOW',?,?,'alice')",tenant,code,state,pointer);
        db.update("insert into app.app_draft(tenant_id,app_id,app_code,config) select tenant_id,id,code,'{\"private\":\"draft-secret\"}'::jsonb from app.application where tenant_id=? and code=?",tenant,code);
        if(pointer!=null){seedVersion(code,1,SHA,Map.of());seedVersion(code,2,SHA2,Map.of());}
    }
    static void seedVersion(String code,int version,String sha,Map<String,Object> overrides){
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("appCode",code);snapshot.put("versionNo",version);snapshot.put("candidateSha256",sha);snapshot.put("appVersionId",code+"@"+version);snapshot.put("type","FLOW");snapshot.put("config",Map.of("private","snapshot-secret","guard",Map.of("roles",List.of("EDITOR"))));snapshot.put("definition",Map.of("nodes",List.of(Map.of("id","start"))));snapshot.put("layout",Map.of("x",12));snapshot.putAll(overrides);
        db.update("insert into app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id) select a.tenant_id,a.code,?,?,?,?::jsonb,'finite','alice',a.id,d.id from app.application a join app.app_draft d on d.tenant_id=a.tenant_id and d.app_code=a.code where a.tenant_id=? and a.code=?",version,version,sha,Json.write(snapshot),tenant,code);
    }
    static ResourceBinding root(String code,int no,String sha){return new ResourceBinding("app-version",code+"@"+no,sha);}
    static String path(String code){return "/internal/apps/"+code+"/published";}
    static ServiceCall payload(ResourceBinding root){return new ServiceCall("app","app.published.read",root);}
    static String ticket(ResourceBinding root){try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
        var main=new ServiceCall("engine","proxy.engine.post.RunController.run",root);var initial=engine.authorizeRetained(gateway.exchange(source,main).value(),main);return engine.forward(initial.current(),payload(root)).value();
    }}
    static String ticket(){return ticket(root(CODE,1,SHA));}
    static HttpResponse<String> request(String method,String path,String token)throws Exception{
        try(var http=HttpClient.newHttpClient()){var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(12));if(token!=null)r.header("Authorization","Bearer "+token);return http.send(r.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
    }
    static HttpResponse<String> read(String token)throws Exception{return request("GET",path(CODE),token);}
    static void denied(HttpResponse<String> r,int...statuses){assertThat(r.statusCode()).as(r.body()).isIn(Arrays.stream(statuses).boxed().toList());assertThat(Json.read(r.body()).path("data").isNull()||Json.read(r.body()).path("data").isMissingNode()).isTrue();}
    static void success(HttpResponse<String> r,int no,long id,String sha){assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var n=Json.read(r.body()).path("data");assertThat(n.path("appCode").asText()).isEqualTo(CODE);assertThat(n.path("versionNo").asInt()).isEqualTo(no);assertThat(n.path("appVersionId").isIntegralNumber()).isTrue();assertThat(n.path("appVersionId").asLong()).isEqualTo(id);assertThat(n.path("versionId").asLong()).isEqualTo(id);assertThat(n.path("appVersionKey").asText()).isEqualTo(CODE+"@"+no);assertThat(n.path("candidateSha256").asText()).isEqualTo(sha);assertThat(n.path("config").path("private").asText()).isEqualTo("snapshot-secret");assertThat(n.path("definition").path("nodes").size()).isEqualTo(1);assertThat(n.path("layout").path("x").asInt()).isEqualTo(12);}
    static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("finite latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void lockBoundedUpdate(String clause){new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{db.execute("set local lock_timeout='350ms'");return db.update("update app.application set "+clause+" where tenant_id=? and code=?",tenant,CODE);});}
    static String changed(String token,Consumer<Map<String,Object>> change){var c=Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});change.accept(c);return fixture.sign(c,fixture.authoritySigningKey(),"at+jwt");}

    @Test void normalSavedPortalMetadataThenIndependentMainAndForwardReadsOnlyCurrentSnapshot()throws Exception{
        try(var gateway=fixture.client("gateway")){
            var query=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(tenant,CODE));var metadata=gateway.exchange(source,query);var r=request("GET","/internal/apps/"+CODE+"/published-metadata",metadata.value());assertThat(r.statusCode()).isEqualTo(200);var n=Json.read(r.body()).path("data");assertThat(n.path("versionId").asLong()).isEqualTo(versionId);assertThat(n.path("versionSha256").asText()).isEqualTo(SHA);
            String t=ticket();assertThat(Json.read(new String(Base64.getUrlDecoder().decode(t.split("\\.")[1]))).path("grant_ref").asText()).isNotEqualTo(metadata.operation().grantRef());resetObservers();success(read(t),1,versionId,SHA);
        }
        assertThat(currents).hasValue(2);assertThat(count("initial-metadata")).isEqualTo(1);assertThat(count("application-lock")).isEqualTo(1);assertThat(count("locked-metadata")).isEqualTo(1);assertThat(count("payload")).isEqualTo(1);
        assertThat(statements.stream().filter(s->!s.category.equals("initial-metadata")).map(Sql::connection).distinct().count()).isEqualTo(1);assertThat(statements.getFirst().transaction).isFalse();
        assertThat(statements.stream().skip(1)).allSatisfy(s->{assertThat(s.transaction).isTrue();assertThat(s.autoCommit).isFalse();assertThat(s.isolation).isEqualTo(java.sql.Connection.TRANSACTION_READ_COMMITTED);assertThat(s.readOnly).isFalse();});assertThat(commits).hasValue(1);
    }
    @Test void secondCurrentDenialDoesNotTouchPayload()throws Exception{String t=ticket();beforeCurrent=()->fixture.revoked(true);denied(read(t),403);assertThat(currents).hasValue(2);assertThat(count("payload")).isZero();}
    @Test void secondCurrentOutageDoesNotTouchPayload()throws Exception{String t=ticket();beforeCurrent=()->fixture.unavailable(true);denied(read(t),503);assertThat(currents).hasValue(2);assertThat(count("payload")).isZero();}
    @Test void secondCurrentExpirationDoesNotTouchPayload()throws Exception{String t=ticket();beforeCurrent=()->fixture.advanceClock(Duration.ofSeconds(41));denied(read(t),403);assertThat(currents).hasValue(2);assertThat(count("payload")).isZero();}
    @Test void authorizedNarrowerCurrentProjectionStillReadsPayload()throws Exception{String t=ticket();fixture.narrowAdminAtResourceCheck();success(read(t),1,versionId,SHA);assertThat(currents).hasValue(2);}
    @Test void actualResumeWithDelegatedSourceAndPortalOriginRemainsLegal()throws Exception{
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            var main=new ServiceCall("engine","proxy.engine.post.RunController.run",root(CODE,1,SHA));var first=engine.authorizeRetained(gateway.exchange(source,main).value(),main);fixture.advanceClock(Duration.ofSeconds(41));var resumed=engine.authorizeRetained(gateway.resume(first.current().delegation(),main).value(),main);assertThat(resumed.current().operation().sourceKind()).isEqualTo("DELEGATED");assertThat(resumed.current().operation().originKind()).isEqualTo("PORTAL");success(read(engine.forward(resumed.current(),payload(main.resource())).value()),1,versionId,SHA);
        }
    }
    @Test void missingInitialOriginProofCannotReadPayload()throws Exception{
        fixture.ticketMutation(c->c.remove("origin_authentication_id"));fixture.decisionMutation(c->c.remove("origin_authentication_id"));String t=ticket();denied(read(t),403);assertThat(count("payload")).isZero();
    }
    @Test void forgedRootCodeKindAndShaNeverReadPayload()throws Exception{
        for(var r:List.of(root("other",1,SHA),root(CODE,1,Digest.sha256("wrong")),new ResourceBinding("run",CODE+"@1",SHA))){denied(read(ticket(r)),403);assertThat(count("payload")).isZero();}
    }
    @Test void oldVersionTicketConflictsRatherThanSelectingNewSnapshot()throws Exception{String t=ticket();lockBoundedUpdate("current_version_no=2");denied(read(t),409);assertThat(count("payload")).isZero();}
    @Test void portalAndInvalidSignatureCallerTargetActionMethodAndMetadataTicketsStopBeforeBusinessSql()throws Exception{
        String t=ticket();denied(read(null),401);for(String raw:List.of(source,"not-a-jwt",t.substring(0,t.length()-8)+"deadbeef"))denied(read(raw),401,403);
        for(String field:List.of("caller","target","action","aud","tenant_id"))denied(read(changed(t,c->c.put(field,"wrong"))),401,403);
        for(String method:List.of("HEAD","POST","PUT")){var r=request(method,path(CODE),t);assertThat(r.statusCode()).isIn(401,403);}
        try(var gateway=fixture.client("gateway")){var q=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(tenant,CODE));denied(read(gateway.exchange(source,q).value()),403);}
        assertThat(statements).isEmpty();
    }
    @Test void publishedTicketCannotEnterOtherActualAppAliases()throws Exception{String t=ticket();for(String p:List.of("/internal/apps/"+CODE+"/snapshot?draft=false","/internal/apps/"+CODE+"/snapshot?draft=true","/internal/apps/"+CODE+"/candidate?draftRev=1&candidateSha256="+SHA,"/internal/apps/"+CODE+"/versions/1","/admin/apps/"+CODE,"/admin/apps/"+CODE+"/versions/1","/v1/apps/"+CODE+"/input-schema"))denied(request("GET",p,t),403);assertThat(statements).isEmpty();}
    @Test void unknownTenantAndBusinessUnavailableStatesKeepOriginalStatuses()throws Exception{
        for(String state:List.of("DISABLED","ARCHIVED")){lockBoundedUpdate("status='"+state+"'");denied(read(ticket()),409);}
        lockBoundedUpdate("status='DRAFT',current_version_no=null");denied(read(ticket()),409);lockBoundedUpdate("current_version_no=99");denied(read(ticket()),409);
        denied(request("GET",path("missing"),ticket(root("missing",1,SHA))),404);
        fixture.portalActor(new Actor(tenant+"-hidden","alice",Set.of("EDITOR"),Map.of("dataLabels",Set.of("confidential","restricted"),"region",Set.of("A"))));source=fixture.portal();denied(read(ticket()),404);assertThat(count("payload")).isZero();
    }
    @Test void draftWithPublishedPointerRemainsLegal()throws Exception{lockBoundedUpdate("status='DRAFT'");success(read(ticket()),1,versionId,SHA);}
    @Test void missingRequestFrameCannotFallBackToLegacyPayload()throws Exception{
        String t=ticket();var jwt=new TrustedJwtDecoder(fixture.config("app"),fixture.clock(),true).decode(t);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        try{assertThatThrownBy(()->app.getBean(AppController.class).published(CODE)).isInstanceOf(PlatformException.class).satisfies(e->assertThat(((PlatformException)e).status()).isEqualTo(403));assertThat(statements).isEmpty();}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void ambientBusinessTransactionIsRejectedBeforeAnyRefreshOrSql(){
        new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{
            assertThatThrownBy(()->app.getBean(AppService.class).publishedAuthorized(CODE)).isInstanceOf(PlatformException.class).satisfies(e->assertThat(((PlatformException)e).code()).isEqualTo("AUTHORITY_TRANSACTION_FORBIDDEN"));
            assertThat(statements).isEmpty();assertThat(currents).hasValue(0);return null;
        });
    }
    @Test void explicitLegacyTestModePreservesExistingSnapshotProjection()throws Exception{
        String t=ticket();app.close();mode="test";
        try{startReceiver();success(read(t),1,versionId,SHA);assertThat(currents).hasValue(1);assertThat(count("payload")).isEqualTo(2);
            app.close();mode="dev-hmac";resetObservers();startReceiver();success(read(t),1,versionId,SHA);assertThat(currents).hasValue(1);assertThat(count("payload")).isEqualTo(2);
            app.close();mode="unknown";resetObservers();startReceiver();denied(read(t),403);assertThat(statements).isEmpty();
        }
        finally{app.close();mode="iam";startReceiver();}
    }
    @Test void onlineCurrentWaitHoldsNoApplicationLockAndPointerChangeRejectsOldRequest()throws Exception{
        String t=ticket();var reached=new CountDownLatch(1);var release=new CountDownLatch(1);beforeCurrent=()->{reached.countDown();await(release);};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var response=pool.submit(()->read(t));try{await(reached);lockBoundedUpdate("current_version_no=2");}finally{release.countDown();}denied(response.get(8,TimeUnit.SECONDS),409);}
        assertThat(count("payload")).isZero();resetObservers();
        long id;
        try(var gateway=fixture.client("gateway")){
            var q=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(tenant,CODE));var response=request("GET","/internal/apps/"+CODE+"/published-metadata",gateway.exchange(source,q).value());assertThat(response.statusCode()).as(response.body()).isEqualTo(200);var n=Json.read(response.body()).path("data");assertThat(n.path("versionNo").asInt()).isEqualTo(2);assertThat(n.path("versionSha256").asText()).isEqualTo(SHA2);id=n.path("versionId").asLong();assertThat(id).isPositive();assertThat(count("payload")).isZero();System.out.println("D3B_NEW_METADATA="+n);
        }
        resetObservers();success(read(ticket(root(CODE,2,SHA2))),2,id,SHA2);
    }
    @Test void statusChangeAfterRefreshBeforeLockRejectsWithoutPayload()throws Exception{String t=ticket();afterCurrent=()->lockBoundedUpdate("status='DISABLED'");denied(read(t),409);assertThat(count("payload")).isZero();}
    @Test void actualAuthorityResponseWaitAlsoHoldsNoPgLock()throws Exception{
        String t=ticket();var reached=new CountDownLatch(1);var release=new CountDownLatch(1);var n=new AtomicInteger();fixture.decisionMutation(c->{if(c.containsKey("roles")&&n.incrementAndGet()==2){reached.countDown();await(release);}});
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var response=pool.submit(()->read(t));try{await(reached);lockBoundedUpdate("status='ARCHIVED'");}finally{release.countDown();}denied(response.get(8,TimeUnit.SECONDS),409);}assertThat(count("payload")).isZero();
    }
    @Test void heldShareLockBlocksRealWriterThenReleasesAfterRead()throws Exception{
        String t=ticket();var reached=new CountDownLatch(1);var release=new CountDownLatch(1);afterSql=c->{if(c.equals("application-lock")){reached.countDown();await(release);}};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var response=pool.submit(()->read(t));try{await(reached);for(String change:List.of("current_version_no=2","status='DISABLED'"))assertThatThrownBy(()->lockBoundedUpdate(change)).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(e->{var cause=((org.springframework.dao.DataAccessException)e).getMostSpecificCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("55P03");System.out.println("D3B_WRITER_LOCK_TIMEOUT="+change+":55P03");});}finally{release.countDown();}success(response.get(8,TimeUnit.SECONDS),1,versionId,SHA);}lockBoundedUpdate("current_version_no=2");assertThat(currents).hasValue(2);
    }
    @Test void proofExpiryWhileWaitingForShareLockRejectsBeforePayload()throws Exception{
        String t=ticket();var attempting=new CountDownLatch(1);beforeSql=c->{if(c.equals("application-lock"))attempting.countDown();};
        try(var writer=ds.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
            writer.setAutoCommit(false);try(var p=writer.prepareStatement("update app.application set status=status where tenant_id=? and code=?")){p.setString(1,tenant);p.setString(2,CODE);p.executeUpdate();}
            var response=pool.submit(()->read(t));try{await(attempting);int pid=statements.stream().filter(s->s.category.equals("application-lock")).findFirst().orElseThrow().backendPid();var waiting=db.queryForMap("select wait_event_type,cardinality(pg_blocking_pids(pid)) as blockers from pg_stat_activity where pid=?",pid);System.out.println("D3B_SHARE_WAIT="+Json.write(waiting));assertThat(waiting.get("wait_event_type")).isEqualTo("Lock");assertThat(((Number)waiting.get("blockers")).intValue()).isPositive();fixture.advanceClock(Duration.ofSeconds(6));}finally{writer.rollback();}denied(response.get(8,TimeUnit.SECONDS),403);
        }assertThat(count("payload")).isZero();assertThat(currents).hasValue(2);
    }
    @Test void expiryAfterPayloadBeforeCallbackEndsRejectsWithoutSuccessData()throws Exception{String t=ticket();afterPayload=()->fixture.advanceClock(Duration.ofSeconds(6));denied(read(t),403);assertThat(count("payload")).isEqualTo(1);assertThat(commits).hasValue(0);assertThat(currents).hasValue(2);}
    @Test void expiryAfterActualCommitBeforeServiceReturnsRejectsWithoutSuccessData()throws Exception{
        String t=ticket();var committed=new CountDownLatch(1);var release=new CountDownLatch(1);afterCommit=()->{committed.countDown();await(release);};
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var response=pool.submit(()->read(t));try{await(committed);lockBoundedUpdate("status='DISABLED'");fixture.advanceClock(Duration.ofSeconds(6));}finally{release.countDown();}denied(response.get(8,TimeUnit.SECONDS),403);}assertThat(commits).hasValue(1);assertThat(count("payload")).isEqualTo(1);assertThat(currents).hasValue(2);
    }
    @Test void malformedPersistedSnapshotIdentityIsRejectedAfterItsSinglePayloadRead()throws Exception{
        for(var patch:List.<Map<String,Object>>of(Map.of("appCode","wrong"),Map.of("versionNo",4),Map.of("candidateSha256",Digest.sha256("wrong")),Map.of("appVersionId","wrong@1"))){
            String code="bad"+UUID.randomUUID().toString().replace("-","").substring(0,10);seedApp(code,"DRAFT",null);seedVersion(code,1,SHA,patch);db.update("update app.application set current_version_no=1 where tenant_id=? and code=?",tenant,code);denied(request("GET",path(code),ticket(root(code,1,SHA))),409);
        }assertThat(count("payload")).isEqualTo(4);
    }
}
