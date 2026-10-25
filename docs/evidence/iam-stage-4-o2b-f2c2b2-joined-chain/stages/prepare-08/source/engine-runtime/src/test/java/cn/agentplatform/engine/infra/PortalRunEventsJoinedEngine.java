package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.GraphRules;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** New database only. Seed transactions exercise production writes; scheduler/model execution is suppressed. */
public final class PortalRunEventsJoinedEngine {
    static DataSource source;static JdbcTemplate db;static TraceStore trace;static RunAuthorityStore store;
    static HttpCurrentAuthorityClient current;static AuthorityProperties properties;
    static PortalEventsJoinedAuthorityFixture.TestClock clock;static Path run;static RunAuthorityStore.Binding binding;
    static final List<Map<String,Object>> packets=new CopyOnWriteArrayList<>();
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    @Import(cn.agentplatform.engine.EnginePortalEventsAssembly.class)
    static class Receiver {
        @Bean DataSource source(){return source;}
        @Bean Clock clock(){return clock;}
        @Bean(destroyMethod="") HttpCurrentAuthorityClient current(){return current;}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(){return new TrustedJwtDecoder(properties,clock,true);}
        @Bean ServiceAuthorization authorization(HttpCurrentAuthorityClient c){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),c);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor mapped(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> m){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),m);}
        @Bean PlatformErrors errors(){return new PlatformErrors();}
        @Bean(destroyMethod="close") ChunkBus chunks(){return new ChunkBus(2,8192);}
        @Bean(destroyMethod="close") RuntimeClients clients(){return new RuntimeClients(properties,current,"http://127.0.0.1:1",true);}
        @Bean(destroyMethod="close") RunScheduler scheduler(){return new RunScheduler(trace){@Override public void start(){}};}
        @Bean RunService service(RuntimeClients c,RunScheduler s){return new RunService(trace,c,null,null,s,new RunAuthority(store,current,clock,Duration.ofSeconds(3)),store,clock);}
        @Bean RunController controller(RunService s,ChunkBus c){return new RunController(s,trace,c,null);}
        @Bean RunObservationMetadataService metadata(){return new RunObservationMetadataService(db);}
        @Bean RunObservationMetadataController metadataController(RunObservationMetadataService m){return new RunObservationMetadataController(m);}
        @Bean FilterRegistrationBean<Filter> wireObserver(){
            var b=new FilterRegistrationBean<Filter>();b.setAsyncSupported(true);b.setOrder(Integer.MAX_VALUE);b.setFilter((r,s,c)->c.doFilter(r,new HttpServletResponseWrapper((HttpServletResponse)s){
                ServletOutputStream observed;
                @Override public ServletOutputStream getOutputStream()throws IOException {
                    if(observed!=null)return observed;var actual=super.getOutputStream();var frame=new ByteArrayOutputStream();
                    observed=new ServletOutputStream(){boolean overflow;
                        public boolean isReady(){return actual.isReady();}public void setWriteListener(WriteListener l){actual.setWriteListener(l);}
                        public void write(int value)throws IOException{actual.write(value);if(frame.size()<1048576)frame.write(value);else overflow=true;}
                        public void write(byte[] bytes,int o,int n)throws IOException{actual.write(bytes,o,n);if(frame.size()+n<=1048576)frame.write(bytes,o,n);else overflow=true;}
                        public void flush()throws IOException{actual.flush();if(!overflow&&PortalEventsPacketWriter.MIME.equals(getContentType()))observePacket(frame.toByteArray());frame.reset();}
                        public void close()throws IOException{actual.close();}
                    };return observed;
                }
            }));return b;
        }
    }
    /** Observes completed actual encoder output only. No packet, receipt, grant, or payload is persisted. */
    static void observePacket(byte[] bytes){
        if(bytes.length<68||packets.size()>=64)return;var prefix=ByteBuffer.wrap(bytes);if(prefix.getInt()!=0x41504556)return;prefix.getShort();short kind=prefix.getShort();prefix.getLong();int h=prefix.getInt(),m=prefix.getInt();if(h<1||m<1||24L+h+m+44>bytes.length)return;
        var header=Json.read(new String(bytes,24,h,java.nio.charset.StandardCharsets.UTF_8));int payload=header.path("payloadBytes").asInt();if(24L+h+m+payload+44!=bytes.length)return;
        if(kind==2&&header.path("eventSeq").isIntegralNumber()){
            var metadata=Json.read(new String(bytes,24+h,m,java.nio.charset.StandardCharsets.UTF_8));var facts=new LinkedHashMap<String,Object>();facts.put("seq",header.path("eventSeq").asLong());facts.put("type",header.path("eventType").asText());facts.put("payloadBytes",payload);
            facts.put("actualPayloadSha256",java.util.HexFormat.of().formatHex(sha(Arrays.copyOfRange(bytes,24+h+m,24+h+m+payload))));facts.put("snapshot",metadata.path("acceptedSnapshotSha").asText());facts.put("scopeCount",metadata.path("scopes").size());facts.put("rootScopesSha256",Digest.sha256(metadata.path("rootScopes").toString()));packets.add(facts);
            try{save("engine-packets.json",packets);}catch(Exception failure){throw new IllegalStateException("OBSERVATION_WRITE",failure);}
        }
    }
    static byte[] sha(byte[] bytes){try{return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception impossible){throw new AssertionError(impossible);}}
    static JsonNode snapshot(){
        var s=Json.object();s.put("appCode","app-a");s.put("type","FLOW");s.put("versionNo",1);s.put("appVersionId",4294967297L);s.put("candidateSha256",PortalEventsJoinedAuthorityFixture.ROOT.versionDigest());s.set("config",Json.object());
        var d=Json.object();var n=d.putArray("nodes");var e=d.putArray("edges");for(int i=0;i<15;i++){var node=n.addObject().put("id",nodeId(i)).put("type",i==0?"START":i==14?"END":"CODE");if(i>0&&i<14)node.put("expression","1");if(i>0)e.addObject().put("from",nodeId(i-1)).put("to",nodeId(i));}s.set("definition",d);return s;
    }
    static String nodeId(int i){return i==0?"start":i==14?"end":"c%02d".formatted(i);}
    static void seed(String ticket){
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));var snap=snapshot();GraphRules.requirePublishedFlow(snap);
        long id=PortalEventsJoinedAuthorityFixture.admitted(current,ticket,proof->tx.execute(status->{var budget=Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",clock.instant().plusSeconds(599).toString()));long r=store.createPublished(proof,snap,Json.object(),Json.read("{\"appVer\":1}"),budget,"FULL",UUID.randomUUID().toString(),Set.of("initial"));for(int i=0;i<15;i++)trace.createNode(r,nodeId(i),i+1,i==0?"START":i==14?"END":"CODE",snap.path("definition").path("nodes").get(i),proof);return r;}));binding=store.load(id);
        append("JOINED_NULL",Json.read("null"));append("观察".repeat(16),Json.read("{\"integer\":123456789012345678901234567890,\"text\":\"escaped\\n\\\"观察\\\\\"}"));
    }
    static HttpCurrentAuthorityClient.RetainedAuthorization writer(){return new RunAuthority(store,current,clock,Duration.ofSeconds(3)).currentRetained(binding.runId(),new ServiceCall("engine","run.advance",PortalEventsJoinedAuthorityFixture.ROOT));}
    static void append(String type,Object value){trace.event(binding.runId(),type,value,writer());}
    static void finish(){var repository=new RunScheduleRepository(trace,true);var d=repository.claimSpecific(binding.runId(),cn.agentplatform.engine.domain.RunDispatch.Queue.INTERACTIVE,"joined-"+UUID.randomUUID(),Duration.ofSeconds(60));if(d==null)throw new AssertionError("DISPATCH");var w=writer();trace.consume(binding.runId(),"steps",25,25,w);trace.stopFlowForBudget(d,w,"BUDGET_STEPS");}
    static void save(String file,Object value)throws Exception{Files.writeString(run.resolve(file),Json.write(value));}
    /** Complete bounded metadata inventory; raw scopes, credentials and business payloads stay in PG. */
    static Map<String,Object> inventory(){
        var rows=db.queryForList("select kind,material_id,material_version,material_revision,payload_sha256,scope_sha256,scope->'parentScopeSha256' as parents from engine.material_scope where run_id=? order by material_revision limit 4097",binding.runId());
        if(rows.size()>4096)throw new IllegalStateException("FINITE_INVENTORY");var manifests=new ArrayList<Map<String,Object>>();
        for(var row:rows){var item=new LinkedHashMap<String,Object>();for(String key:List.of("kind","material_id","material_version","material_revision","payload_sha256","scope_sha256"))item.put(key,row.get(key));item.put("parents",Json.read(row.get("parents").toString()));manifests.add(item);}
        var facts=new LinkedHashMap<String,Object>();facts.put("publicRunId",binding.publicId());facts.put("runDbId",binding.runId());facts.put("owner",binding.owner());facts.put("root",binding.root());facts.put("snapshot",binding.snapshotSha());facts.put("initialCeilingSha256",binding.initialSha());facts.put("versionRefsSha256",db.queryForObject("select payload_sha256 from engine.material_scope where run_id=? and kind='run-version-refs'",String.class,binding.runId()));facts.put("scopeCount",manifests.size());facts.put("scopeInventorySha256",Digest.sha256(Json.canonical(manifests)));facts.put("scopes",manifests);facts.put("eventRows",db.queryForObject("select count(*) from engine.run_event where run_id=?",Long.class,binding.runId()));facts.put("pgWallClock",db.queryForObject("select current_timestamp::text",String.class));facts.put("fixtureClock",clock.instant().toString());return facts;
    }
    public static void main(String[] args)throws Exception {
        run=Path.of(args[0]);try(var input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))){
            var config=Json.read(input.readLine());properties=Json.read(config.path("authority").toString(),AuthorityProperties.class);clock=new PortalEventsJoinedAuthorityFixture.TestClock(config.path("epoch").asLong());
            String database=config.path("database").asText();if(!database.matches("joined_[a-f0-9]{24}"))throw new IllegalArgumentException("NEW_DATABASE_ONLY");
            String password=System.getenv("AGENT_DB_PASSWORD");var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));boolean existing=Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,database));if(!existing)admin.execute("create database "+database);
            source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+database,"agent_v12",password);db=new JdbcTemplate(source);if(!existing)Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();else if(db.queryForObject("select count(*) from engine.flyway_schema_history where success and version is not null",Integer.class)!=18)throw new IllegalStateException("OWN_SCHEMA_EPOCH");
            current=PortalEventsJoinedAuthorityFixture.client(properties,config.path("secret").asText(),clock);
            try(var ownedCurrent=current){store=new RunAuthorityStore(source,clock);trace=new TraceStore(db,new DataSourceTransactionManager(source),clock);seed(config.path("admission").asText());
                var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");var values=new HashMap<String,Object>();values.put("server.address","127.0.0.1");values.put("server.port",0);values.put("server.max-http-request-header-size",32768);values.put("server.ssl.enabled",true);values.put("server.ssl.key-store",run.resolve("server.p12").toUri().toString());values.put("server.ssl.key-store-password","f2a-test-only");values.put("server.ssl.key-alias","normal");values.put("server.ssl.enabled-protocols","TLSv1.3");values.put("server.ssl.ciphers","TLS_AES_128_GCM_SHA256");values.put("spring.config.location","optional:classpath:/joined-no-config/");values.put("spring.main.banner-mode","off");values.put("spring.flyway.enabled",false);values.put("springdoc.api-docs.enabled",false);values.put("management.endpoints.web.exposure.exclude","*");values.put("management.endpoints.web.discovery.enabled",false);values.put("logging.level.root","ERROR");values.put("logging.level.cn.agentplatform.engine.application.PortalRunEventsService","DEBUG");app.setDefaultProperties(values);
                try(var context=(ServletWebServerApplicationContext)app.run()){
                    var max=db.queryForObject("select max(jsonb_array_length(scope->'parentScopeSha256')) from engine.material_scope where run_id=?",Integer.class,binding.runId());
                    save("engine-ready.json",Map.of("port",context.getWebServer().getPort(),"run",binding.publicId(),"snapshot",binding.snapshotSha(),"root",binding.root(),"database",database,"maxParents",max==null?0:max,"beans",List.of(context.getBean(RunController.class).getClass().getName(),context.getBean(PortalRunEventsService.class).getClass().getName(),context.getBean(RunEventReader.class).getClass().getName())));
                    String line;while((line=input.readLine())!=null){var cmd=Json.read(line);String op=cmd.path("op").asText();if(op.equals("stop"))break;if(op.equals("finish"))finish();else if(op.equals("append"))append("JOINED_LIVE",Json.read("{\"live\":true}"));else if(op.equals("bulk")){for(int i=0;i<32;i++)append("JOINED_BULK",Map.of("text","x".repeat(220000)));}else if(op.equals("clock"))clock.set(cmd.path("epoch").asLong());else if(op.equals("retain"))db.update("delete from engine.run_event where run_id=? and seq<=?",binding.runId(),cmd.path("through").asLong());else if(op.equals("inventory"))save("engine-inventory-"+cmd.path("id").asInt()+".json",inventory());else if(!op.equals("observe"))throw new IllegalArgumentException("FINITE_COMMAND");
                        var service=context.getBean(PortalRunEventsService.class);var f=service.getClass().getDeclaredField("writers");f.setAccessible(true);var pool=(ThreadPoolExecutor)f.get(service);var owners=service.getClass().getDeclaredField("owners");owners.setAccessible(true);save("engine-ack-"+cmd.path("id").asInt()+".json",Map.of("op",op,"fixtureClock",clock.instant().toString(),"activeWriters",pool.getActiveCount(),"queued",pool.getQueue().size(),"owners",((Set<?>)owners.get(service)).size(),"watermark",db.queryForObject("select event_seq from engine.run where id=?",Long.class,binding.runId()),"status",db.queryForObject("select status from engine.run where id=?",String.class,binding.runId()),"threads",PortalEventsJoinedAuthorityFixture.boundedThreads(),"materialScopes",db.queryForObject("select count(*) from engine.material_scope where run_id=?",Long.class,binding.runId()),"eventRows",db.queryForObject("select count(*) from engine.run_event where run_id=?",Long.class,binding.runId())));
                    }
                }
                save("engine-closed.json",Map.of("contextClosed",true,"authorityClosing",true));
            }
        }
    }
}
