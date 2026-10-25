package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.GraphRules;
import cn.agentplatform.engine.infra.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/** Dedicated JVM/PG; trusted finite stdin management is not a public admission endpoint. */
public final class GatewayRunStatusEngineFixtureMain {
    static final String DATABASE="agent_platform_iam_o1c_gateway_status_v12";
    static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("o1c-candidate"));
    static final AtomicReference<Instant> now=new AtomicReference<>();
    static final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
    static AuthorityProperties trust;static String secret;static DataSource ds;static JdbcTemplate db;
    static HttpCurrentAuthorityClient authority;static TraceStore trace;static RunService service;
    static RunScheduler scheduler;static RuntimeClients runtime;
    static final List<Map<String,Object>> sql=new CopyOnWriteArrayList<>();
    static final AtomicInteger dependencyCalls=new AtomicInteger(),metadataCalls=new AtomicInteger(),statusCalls=new AtomicInteger();
    static com.sun.net.httpserver.HttpServer dependency;
    static volatile String faultPath="";static volatile int faultStatus;static volatile byte[] faultBody=new byte[0],lastStatus=new byte[0];
    static volatile boolean expireAtActualStream;
    static final List<Map<String,Object>> wire=new CopyOnWriteArrayList<>();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import({RunObservationMetadataController.class,PlatformErrors.class})
    static class Receiver {
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> wire(){
            var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();bean.setOrder(Integer.MIN_VALUE);
            bean.setFilter((request,response,chain)->{
                var r=(jakarta.servlet.http.HttpServletRequest)request;var s=(jakarta.servlet.http.HttpServletResponse)response;
                String path=r.getRequestURI(),kind=path.startsWith("/internal/runs/")?"metadata":path.startsWith("/admin/runs/")?"status":"other";
                var auth=Collections.list(r.getHeaders("Authorization"));wire.add(Map.of("path",path,"kind",kind,"authorizationCount",auth.size(),"ticketSha",auth.isEmpty()?"":Digest.sha256(auth.getFirst()),"cookie",r.getHeader("Cookie")!=null,"query",r.getQueryString()==null?"<null>":r.getQueryString()));
                if(kind.equals(faultPath)){s.setStatus(faultStatus);s.setContentType("application/json");s.setHeader("WWW-Authenticate","Basic realm=finite");s.getOutputStream().write(faultBody);return;}
                if(!kind.equals("status")){chain.doFilter(r,s);return;}
                chain.doFilter(r,new jakarta.servlet.http.HttpServletResponseWrapper(s){
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream()throws IOException{var actual=super.getOutputStream();if(expireAtActualStream){expireAtActualStream=false;now.updateAndGet(t->t.plusSeconds(6));}return new jakarta.servlet.ServletOutputStream(){
                        @Override public boolean isReady(){return actual.isReady();}@Override public void setWriteListener(jakarta.servlet.WriteListener l){actual.setWriteListener(l);}
                        @Override public void write(int b)throws IOException{actual.write(b);}
                        @Override public void write(byte[] b,int o,int n)throws IOException{actual.write(b,o,n);if(getStatus()==200)lastStatus=Arrays.copyOfRange(b,o,o+n);}
                        @Override public void close()throws IOException{actual.close();}
                    };}
                });
            });return bean;
        }
        @Bean AuthorityProperties authorityProperties(){return trust;}
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return db;}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return authority=new HttpCurrentAuthorityClient(trust,clock,new WorkloadCredentialResolver(trust,n->secret,true),true);}
        @Bean RunObservationMetadataService metadata(){return new RunObservationMetadataService(db);}
        @Bean RunController runs(HttpCurrentAuthorityClient a){
            trace=new TraceStore(db,new DataSourceTransactionManager(ds),clock);
            runtime=new RuntimeClients(trust,authority,"http://127.0.0.1:"+dependency.getAddress().getPort(),true);
            scheduler=new RunScheduler(trace){@Override public void start(){throw new AssertionError("NO_SCHEDULER_START");}@Override public void created(long id,Admission a){throw new AssertionError("NO_WORKER_WAKE");}};
            service=new RunService(trace,runtime,null,null,scheduler,null,new RunAuthorityStore(ds,clock),clock);
            return new RunController(service,trace,null,null);
        }
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(trust,clock,true);}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient a){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),a);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor observations(){return new org.springframework.web.servlet.handler.MappedInterceptor(null,new org.springframework.web.servlet.HandlerInterceptor(){
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest r,jakarta.servlet.http.HttpServletResponse s,Object h){if(r.getRequestURI().startsWith("/internal/runs/"))metadataCalls.incrementAndGet();if(r.getRequestURI().startsWith("/admin/runs/"))statusCalls.incrementAndGet();return true;}
        });}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> m){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),m);}
    }
    static Connection observe(Connection c){
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(GatewayRunStatusEngineFixtureMain.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{Object result=m.invoke(c,a);if(result instanceof Statement statement&&(m.getName().equals("prepareStatement")||m.getName().equals("createStatement"))){String prepared=a!=null&&a.length>0&&a[0] instanceof String?(String)a[0]:null;
                return java.lang.reflect.Proxy.newProxyInstance(GatewayRunStatusEngineFixtureMain.class.getClassLoader(),new Class[]{result instanceof PreparedStatement?PreparedStatement.class:Statement.class},(sp,sm,sa)->{try{
                    String text=prepared!=null?prepared:sa!=null&&sa.length>0&&sa[0] instanceof String?(String)sa[0]:null;
                    if(sm.getName().startsWith("execute")&&text!=null)sql.add(Map.of("sql",text,"connection",System.identityHashCode(c),"isolation",c.getTransactionIsolation(),"readOnly",c.isReadOnly(),"autoCommit",c.getAutoCommit()));
                    return sm.invoke(statement,sa);
                }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
            }return result;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
    }
    static JsonNode snapshot(){return Json.read("""
        {"appCode":"app-a","type":"FLOW","versionNo":1,"appVersionId":4294967297,"candidateSha256":"%s","config":{},"definition":{"nodes":[{"id":"start","type":"START"},{"id":"end","type":"END"}],"edges":[{"from":"start","to":"end"}]}}
        """.formatted(ROOT.versionDigest()));}
    static Object seed(JsonNode command)throws Exception {
        var snap=snapshot();GraphRules.requirePublishedFlow(snap);
        var sentinel=new IllegalStateException("O1C_GATE_REACHED_STORAGE");
        var validating=new TraceStore(db,new DataSourceTransactionManager(ds),clock){@Override public <T>T atomic(java.util.function.Supplier<T> write){throw sentinel;}};
        try(var idle=new RunScheduler(validating){@Override public Admission reserveInteractive(){throw new AssertionError("NO_QUEUE_CLAIM");}@Override public void created(long id,Admission a){throw new AssertionError("NO_WAKE");}}){
            try{new RunService(validating,null,null,null,idle).createReservedSessionTurn("app-a",Json.read("{\"inputs\":{},\"mode\":\"FULL\"}"),snap,new Actor(command.path("tenant").asText(),"alice",Set.of("EDITOR"),Map.of()),null);throw new AssertionError("GATE_NOT_RUN");}
            catch(IllegalStateException e){if(e!=sentinel)throw e;}
            var wrong=snap.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("definition")).set("edges",Json.read("[{\"source\":\"start\",\"target\":\"end\"}]"));
            try{new RunService(validating,null,null,null,idle).createReservedSessionTurn("app-a",Json.read("{\"inputs\":{},\"mode\":\"FULL\"}"),wrong,new Actor(command.path("tenant").asText(),"alice",Set.of("EDITOR"),Map.of()),null);throw new AssertionError("BAD_DAG_ACCEPTED");}catch(PlatformException e){if(!"UNKNOWN_EDGE".equals(e.code()))throw e;}
        }
        String ticket=command.path("ticket").asText();var request=new MockHttpServletRequest("POST","/admin/apps/app-a/runs");var response=new MockHttpServletResponse();request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try(var decoder=new TrustedJwtDecoder(trust,clock,true)){
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket),List.of()));
            var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),authority);var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));
            try{
                guard.preHandle(request,response,handler);var proof=AuthorityContext.requireInitialFor(Actor.current(),ROOT);
                var budget=Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",clock.instant().plusSeconds(599).toString()));
                long id=new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s->{long run=new RunAuthorityStore(ds,clock).createPublished(proof,snap,Json.read("{\"privateInput\":\"not-status\"}"),Json.read("{\"appVer\":1}"),budget,"FULL",UUID.randomUUID().toString(),Set.of("initial"));trace.createNode(run,"start",1,"START",snap.path("definition").path("nodes").get(0),proof);trace.createNode(run,"end",2,"END",snap.path("definition").path("nodes").get(1),proof);return run;});
                var binding=new RunAuthorityStore(ds,clock).load(id);
                // Writer refresh is fixture setup, never counted as a gateway observation grant.
                var writer=new RunAuthority(new RunAuthorityStore(ds,clock),authority,clock,Duration.ofSeconds(3)).currentRetained(id,new ServiceCall("engine","run.advance",ROOT));
                var numeric=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree("{\"integer\":123456789012345678901234567890,\"decimal\":1.2345678901234567890123456789,\"nullValue\":null}");
                ((com.fasterxml.jackson.databind.node.ObjectNode)numeric).put("text","观察\n\"\\");trace.event(id,"O1C_NUMERIC_FIXTURE",numeric,writer);
                int padding=command.path("padBytes").asInt();if(padding<0||padding>2*1024*1024)throw new IllegalArgumentException("FINITE_PAD");if(padding>0)trace.event(id,"O1C_PAD_FIXTURE",Json.tree(Map.of("padding","x".repeat(padding))),writer);
                return Map.of("run",binding.publicId(),"sha",binding.snapshotSha(),"owner",binding.owner(),"id",id,"gate","finiteFlow+realDag;production-storage-seam");
            }finally{guard.afterCompletion(request,response,handler,null);}
        }finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
    }
    static Object stats(){var result=new LinkedHashMap<String,Object>();result.put("facts",db.queryForMap("select (select count(*) from engine.run) as runs,(select count(*) from engine.node_instance) as nodes,(select count(*) from engine.attempt) as attempts,(select count(*) from engine.run_event) as events,(select count(*) from engine.tool_call) as tools"));result.put("sql",List.copyOf(sql));result.put("metadata",metadataCalls.get());result.put("status",statusCalls.get());result.put("dependencies",dependencyCalls.get());result.put("workers",scheduler.activeWorkers());result.put("wire",List.copyOf(wire));result.put("lastStatusBytes",lastStatus.length);return result;}
    public static void main(String[] args)throws Exception {
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            var init=Json.read(Objects.requireNonNull(input.readLine()));trust=Json.read(init.path("trust").toString(),AuthorityProperties.class);trust.validate(true);secret=init.path("secret").asText();now.set(Instant.parse(init.path("now").asText()));
            String password=System.getenv("AGENT_DB_PASSWORD");if(password==null||password.isBlank())throw new IllegalStateException("OWN_PG_CREDENTIAL_REQUIRED");
            var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));boolean existed=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,DATABASE));if(!existed)admin.execute("create database "+DATABASE);
            ds=new DelegatingDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password)){@Override public Connection getConnection()throws SQLException{return observe(super.getConnection());}};db=new JdbcTemplate(ds);
            if(!DATABASE.equals(db.queryForObject("select current_database()",String.class)))throw new AssertionError("WRONG_DATABASE");
            Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").target("217").load().migrate();
            int count=db.queryForObject("select count(*) from engine.flyway_schema_history where success and version::integer between 200 and 217",Integer.class);if(count!=18)throw new AssertionError("MIGRATIONS");
            dependency=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);dependency.createContext("/",x->{dependencyCalls.incrementAndGet();x.sendResponseHeaders(503,-1);x.close();});dependency.start();
            var boot=new SpringApplication(Receiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/o1c-engine-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
            try(var context=(ServletWebServerApplicationContext)boot.run()){
                System.out.println("O1C_ENGINE_READY="+Json.write(Map.of("port",context.getWebServer().getPort(),"pid",ProcessHandle.current().pid(),"database",DATABASE,"existed",existed,"migrations",count)));System.out.flush();
                for(String line;(line=input.readLine())!=null;){var c=Json.read(line);String action=c.path("action").asText();if(action.equals("STOP"))break;Object reply=switch(action){case "seed"->seed(c);case "stats"->stats();case "last"->Map.of("base64",Base64.getEncoder().encodeToString(lastStatus));case "expireAtStream"->{expireAtActualStream=true;yield Map.of("configured",true);}case "fault"->{faultPath=c.path("path").asText();faultStatus=c.path("status").asInt();faultBody=c.has("base64")?Base64.getDecoder().decode(c.path("base64").asText()):c.path("body").asText().getBytes(StandardCharsets.UTF_8);yield Map.of("configured",true);}case "reset"->{sql.clear();wire.clear();lastStatus=new byte[0];metadataCalls.set(0);statusCalls.set(0);faultPath="";expireAtActualStream=false;yield Map.of("reset",true);}case "clock"->{now.set(Instant.parse(c.path("now").asText()));yield Map.of("clock",now.get().toString());}default->throw new IllegalArgumentException("FINITE_COMMAND");};System.out.println("O1C_ENGINE_REPLY="+Json.write(reply));System.out.flush();}
            }
        }finally{if(scheduler!=null)scheduler.close();if(runtime!=null)runtime.close();if(dependency!=null)dependency.stop(0);secret=null;System.out.println("O1C_ENGINE_CLOSED="+ProcessHandle.current().pid());System.out.flush();}
    }
}
