package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.embedded.tomcat.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import static org.junit.jupiter.api.Assertions.*;

/** Real module-only JVMs and network IAM. Never calls historical parents or a synthetic private peer. */
public class PortalRunEventsJoinedChainTest {
    static Path root(){Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();return Files.exists(p.resolve("AGENTS.md"))?p:p.getParent();}
    static Path run;static AuthorityProperties properties;static PortalEventsJoinedAuthorityFixture.TestClock clock;
    static HttpCurrentAuthorityClient authority;static URI engine;static PortalEventsTls tls;
    static final AtomicInteger requests=new AtomicInteger(),notReady=new AtomicInteger(),writes=new AtomicInteger(),completes=new AtomicInteger();
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity @Import({GatewayController.class,GatewayIamAssembly.class,GatewayPortalEventsConfiguration.class,PlatformErrors.class})
    static class Receiver {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor properties(){return f->f.registerSingleton("authorityProperties",properties);}
        @Bean Clock clock(){return clock;}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(){return new TrustedJwtDecoder(properties,clock,true);}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess(properties.authority().issuer(),properties.authority().issuer(),d);}
        @Bean(destroyMethod="") CurrentAuthorityClient authority(){return authority;}
        @Bean(destroyMethod="") PortalEventsTls tls(){return tls;}
        @Bean(destroyMethod="close") PublishedRunHttp statusHttp(){return new PublishedRunHttp();}
        @Bean(destroyMethod="close") RunObservationMetadataClient metadata(PublishedRunHttp h){return new RunObservationMetadataClient(engine,authority,h);}
        @Bean(destroyMethod="close") RunStatusProxy status(RunObservationMetadataClient m,PublishedRunHttp h){return new RunStatusProxy(engine,m,authority,h);}
        @Bean FilterRegistrationBean<Filter> observer(){
            var b=new FilterRegistrationBean<Filter>();b.setAsyncSupported(true);b.setOrder(-200);b.setFilter((r,s,c)->{requests.incrementAndGet();c.doFilter(r,new HttpServletResponseWrapper((HttpServletResponse)s){
                @Override public ServletOutputStream getOutputStream()throws IOException {var actual=super.getOutputStream();return new ServletOutputStream(){public boolean isReady(){boolean ready=actual.isReady();if(!ready)notReady.incrementAndGet();return ready;}public void setWriteListener(WriteListener l){actual.setWriteListener(l);}public void write(int v)throws IOException{actual.write(v);writes.incrementAndGet();}public void write(byte[] v,int o,int n)throws IOException{actual.write(v,o,n);writes.incrementAndGet();}public void flush()throws IOException{actual.flush();}public void close()throws IOException{actual.close();}};}
            });if(r.isAsyncStarted())try{r.getAsyncContext().addListener(new AsyncListener(){public void onComplete(AsyncEvent e){completes.incrementAndGet();}public void onError(AsyncEvent e){}public void onTimeout(AsyncEvent e){}public void onStartAsync(AsyncEvent e){}});}catch(IllegalStateException alreadyComplete){} });return b;
        }
    }
    static void save(String f,Object value)throws Exception{Files.writeString(run.resolve(f),Json.write(value));}
    static JsonNode awaitFile(Path file,Process process)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(!Files.exists(file)&&process.isAlive()&&System.nanoTime()<end)Thread.sleep(30);assertTrue(Files.exists(file),"missing "+file+"; child alive="+process.isAlive());return Json.read(Files.readString(file));}
    static final class Child implements AutoCloseable {
        final Process process;final Thread collector;final BufferedWriter input;final String name;
        Child(String name,String cp,String main,Map<String,Object> config)throws Exception {
            this.name=name;assertFalse(cp.replace('\\','/').contains(name.equals("engine")?"platform-gateway/target":"engine-runtime/target"),"business classpath separation");
            var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-Djdk.tls.maxHandshakeMessageSize=32768","-Djdk.tls.maxCertificateChainLength=4","-Dcom.sun.security.enableAIAcaIssuers=false","-Djdk.tls.server.newSessionTicketCount=0","-Djdk.tls.server.enableStatusRequestExtension=false","--add-opens=java.base/java.lang=ALL-UNNAMED","--add-opens=java.base/java.util=ALL-UNNAMED","-Djdk.net.hosts.file="+run.resolve("hosts"),"-cp",cp,main,run.toString()));
            Files.writeString(run.resolve(name+"-args.json"),Json.write(args));process=new ProcessBuilder(args).directory(root().toFile()).redirectErrorStream(true).start();input=process.outputWriter(StandardCharsets.UTF_8);
            collector=Thread.ofPlatform().daemon().start(()->{try(var out=Files.newOutputStream(run.resolve(name+"-out.log"));var in=process.getInputStream()){byte[] b=new byte[4096];int n,total=0;while((n=in.read(b))!=-1){if((total+=n)>524288)throw new IOException("OUTPUT_BOUND");out.write(b,0,n);}}catch(Exception e){process.destroyForcibly();}});
            send(config);save(name+"-launch.json",Map.of("pid",process.pid(),"classpathSha256",Digest.sha256(cp),"main",main));
        }
        void send(Map<String,Object> value)throws Exception{input.write(Json.write(value));input.newLine();input.flush();}
        public void close()throws Exception{
            if(process.isAlive())try{send(Map.of("op","stop"));input.close();}catch(IOException ignored){}
            boolean graceful=process.waitFor(15,TimeUnit.SECONDS);if(!graceful)process.destroyForcibly();boolean waited=process.waitFor(5,TimeUnit.SECONDS);collector.join(5000);
            save(name+"-exit.json",Map.of("pid",process.pid(),"exit",waited?process.exitValue():-1,"waitForReturned",waited,"graceful",graceful,"collectorJoined",!collector.isAlive()));assertTrue(waited);assertFalse(collector.isAlive());assertTrue(graceful,"bounded graceful child shutdown");assertEquals(0,process.exitValue(),"inspect "+name+"-out.log");
        }
    }
    int commandId;
    JsonNode command(Child e,String op,Map<String,Object> extra)throws Exception {var cmd=new LinkedHashMap<String,Object>(extra);cmd.put("op",op);cmd.put("id",++commandId);e.send(cmd);return awaitFile(run.resolve("engine-ack-"+commandId+".json"),e.process);}
    HttpRequest request(int port,String path,String token,String cursor){var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token);if(cursor!=null)r.header("Last-Event-ID",cursor);return r.GET().build();}
    JsonNode gatewayCommand(Child g,String op,Map<String,Object> extra)throws Exception{var cmd=new LinkedHashMap<String,Object>(extra);cmd.put("op",op);cmd.put("id",++commandId);g.send(cmd);return awaitFile(run.resolve("gateway-ack-"+commandId+".json"),g.process);}
    static Map<String,Object> gatewayFacts(){return Map.of("owners",PortalEventsPacketBudget.SHARED.owners(),"bytes",PortalEventsPacketBudget.SHARED.reservedBytes(),"requests",requests.get(),"actualNotReady",notReady.get(),"writes",writes.get(),"completeCallbacks",completes.get(),"readerThreads",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().equals("portal-events-sse-reader")).count());}
    static Map<String,String> frame(BufferedReader reader)throws IOException{var fields=new LinkedHashMap<String,String>();String line;while((line=reader.readLine())!=null){if(line.isEmpty()){if(!fields.isEmpty())return fields;continue;}int colon=line.indexOf(':');if(colon>=0)fields.put(line.substring(0,colon),line.substring(colon+1).replaceFirst("^ ",""));}return fields;}
    static String tail(BufferedReader reader)throws IOException{var text=new StringBuilder();String line;while((line=reader.readLine())!=null){if(text.length()+line.length()>524288)throw new IOException("BOUNDED_TAIL");text.append(line).append('\n');}return text.toString();}
    @Test void exactPayloadAndRealRenewal()throws Exception {focused(false);}
    @Test void slowReaderCurrentAuthorityAndExpiry()throws Exception {focused(true);}
    void focused(boolean pressureOnly)throws Exception {
        run=Files.createDirectories(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/runs/focused-"+UUID.randomUUID()));Files.writeString(run.resolve("hosts"),"127.0.0.1 engine.f2a.test\n");try(var in=PortalRunEventsJoinedChainTest.class.getResourceAsStream("/iam-o2b-f2a/tls-fixtures.p12")){Files.copy(Objects.requireNonNull(in),run.resolve("server.p12"));}PortalRunEventsHttpTest.ca(run,"root");
        String issuerUrl;
        try(var issuer=new PortalEventsJoinedAuthorityFixture();var http=HttpClient.newHttpClient();var reads=Executors.newSingleThreadExecutor()){
            issuerUrl=issuer.iam.issuer();var ec=new LinkedHashMap<>(issuer.privateConfig("engine"));ec.put("admission",issuer.admission());ec.put("database",Json.read(Files.readString(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/database.json"))).path("database").asText());
            try(var e=new Child("engine",Files.readString(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/engine-classpath.txt")).trim(),"cn.agentplatform.engine.infra.PortalRunEventsJoinedEngine",ec)){
                var seed=awaitFile(run.resolve("engine-ready.json"),e.process);assertTrue(seed.path("maxParents").asInt()>=65);String id=seed.path("run").asText();issuer.register(id,seed.path("snapshot").asText());var gc=new LinkedHashMap<>(issuer.privateConfig("gateway"));gc.put("enginePort",seed.path("port").asInt());
                try(var g=new Child("gateway",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),PortalRunEventsJoinedChainTest.class.getName(),gc)){
                    int port=awaitFile(run.resolve("gateway-ready.json"),g.process).path("port").asInt();String path="/admin/runs/"+id+"/events",token=issuer.token();
                    if(!pressureOnly){var ahead=http.send(request(port,path,token,Long.toString(Long.MAX_VALUE)),HttpResponse.BodyHandlers.ofString());assertEquals(200,ahead.statusCode());assertTrue(ahead.body().contains("CURSOR_AHEAD"));assertTrue(ahead.body().contains(Long.toString(Long.MAX_VALUE)));save("long-cursor.json",Map.of("cursor",Long.toString(Long.MAX_VALUE),"reset",true,"bodySha256",Digest.sha256(ahead.body())));
                    var response=http.send(request(port,path,token,"0"),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,response.statusCode());var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8));var browser=new ArrayList<Map<String,Object>>();
                    while(browser.size()<2){var f=reads.submit(()->frame(reader)).get(12,TimeUnit.SECONDS);if(f.containsKey("id"))browser.add(Map.of("seq",Long.parseLong(f.get("id")),"type",f.get("event"),"payloadSha256",Digest.sha256(f.get("data")),"payloadBytes",f.get("data").getBytes(StandardCharsets.UTF_8).length));}
                    command(e,"append",Map.of());while(browser.size()<3){var f=reads.submit(()->frame(reader)).get(12,TimeUnit.SECONDS);if(f.containsKey("id"))browser.add(Map.of("seq",Long.parseLong(f.get("id")),"type",f.get("event"),"payloadSha256",Digest.sha256(f.get("data")),"payloadBytes",f.get("data").getBytes(StandardCharsets.UTF_8).length));}
                    var actual=Json.read(Files.readString(run.resolve("engine-packets.json")));for(var f:browser){boolean match=false;for(var packet:actual)if(packet.path("seq").asLong()==((Number)f.get("seq")).longValue()&&packet.path("type").asText().equals(f.get("type"))&&packet.path("actualPayloadSha256").asText().equals(f.get("payloadSha256"))&&packet.path("payloadBytes").asInt()==((Number)f.get("payloadBytes")).intValue())match=true;assertTrue(match,"actual E bytes must match browser bytes");}save("payload-equality.json",browser);
                    long renewalEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);while(System.nanoTime()<renewalEnd&&(!issuer.iam.observedOperations().contains("portal_gateway_events_check")||!issuer.iam.observedOperations().contains("portal_events_child_check")))Thread.sleep(100);save("renewal-observed.json",issuer.observations());assertTrue(issuer.iam.observedOperations().contains("portal_gateway_events_check"),"actual G renew");assertTrue(issuer.iam.observedOperations().contains("portal_events_child_check"),"actual E child renew");
                    issuer.narrow(true);String narrowed=reads.submit(()->tail(reader)).get(12,TimeUnit.SECONDS);response.body().close();save("narrowing-renew.json",Map.of("gatewayCheck",true,"engineChildCheck",true,"tailBytes",narrowed.getBytes(StandardCharsets.UTF_8).length,"eofIsTerminal",false));issuer.narrow(false);save("authority.json",issuer.observations());return;}
                    command(e,"bulk",Map.of());JsonNode baseline=gatewayCommand(g,"observe",Map.of());assertEquals(0,baseline.path("owners").asInt());int requestBefore=baseline.path("requests").asInt();
                    try(var socket=new Socket()){
                        socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port));socket.setSoTimeout(12000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nLast-Event-ID: 2\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();var head=new ByteArrayOutputStream();while(head.size()<16384&&!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")){int value=socket.getInputStream().read();assertTrue(value>=0);head.write(value);}assertTrue(head.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200"));
                        JsonNode pressure=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){pressure=gatewayCommand(g,"observe",Map.of());if(pressure.path("actualNotReady").asInt()>0)break;Thread.sleep(100);}assertNotNull(pressure);assertTrue(pressure.path("actualNotReady").asInt()>0,"delegated actual isReady=false");assertEquals(1,pressure.path("requests").asInt()-requestBefore);save("slow-reader-active.json",pressure);
                        issuer.narrow(true);JsonNode ended=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<until){ended=gatewayCommand(g,"observe",Map.of());if(ended.path("owners").asInt()==0)break;Thread.sleep(100);}assertEquals(0,ended.path("owners").asInt());assertEquals(0,ended.path("readerThreads").asInt());assertEquals(3145728,ended.path("bytes").asLong());save("slow-reader-narrowed-cleanup.json",ended);
                    }issuer.narrow(false);
                    var expired=http.send(request(port,path,token,"34"),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,expired.statusCode());var expiryReader=new BufferedReader(new InputStreamReader(expired.body(),StandardCharsets.UTF_8));assertEquals("stream_status",reads.submit(()->frame(expiryReader)).get(12,TimeUnit.SECONDS).get("event"));issuer.iam.advanceClock(Duration.ofSeconds(121));long epoch=issuer.iam.clock().instant().getEpochSecond();command(e,"clock",Map.of("epoch",epoch));gatewayCommand(g,"clock",Map.of("epoch",epoch));String expiryTail=reads.submit(()->tail(expiryReader)).get(12,TimeUnit.SECONDS);expired.body().close();save("expiry.json",Map.of("epoch",epoch,"tailBytes",expiryTail.getBytes(StandardCharsets.UTF_8).length,"eofIsTerminal",false));
                    var after=command(e,"observe",Map.of());assertEquals(0,after.path("activeWriters").asInt());assertEquals(0,after.path("owners").asInt());save("authority.json",issuer.observations());
                }
            }
        }
        try(var probe=HttpClient.newHttpClient()){assertThrows(IOException.class,()->probe.send(HttpRequest.newBuilder(URI.create(issuerUrl+"/jwks")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()));}save("issuer-closed.json",Map.of("closed",true,"actualEndpointRefused",true));
    }
    @Test void independentJoinedChainDeniesBeforeMaterialAndRelaysRealEngineOutput()throws Exception {
        run=Files.createDirectories(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/runs/epoch-"+UUID.randomUUID()));
        Files.writeString(run.resolve("hosts"),"127.0.0.1 engine.f2a.test\n");
        try(var in=PortalRunEventsJoinedChainTest.class.getResourceAsStream("/iam-o2b-f2a/tls-fixtures.p12")){Files.copy(Objects.requireNonNull(in),run.resolve("server.p12"));}
        PortalRunEventsHttpTest.ca(run,"root");
        String engineCp=Files.readString(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/engine-classpath.txt")).trim();
        String gatewayCp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        try(var issuer=new PortalEventsJoinedAuthorityFixture();var http=HttpClient.newHttpClient()){
            var ec=new LinkedHashMap<>(issuer.privateConfig("engine"));ec.put("admission",issuer.admission());Path dbFile=root().resolve(".local/iam-o2b-f2c2b2-joined-chain/database.json"); if(!Files.exists(dbFile))Files.writeString(dbFile,Json.write(Map.of("database","joined_"+UUID.randomUUID().toString().replace("-","").substring(0,24)))); ec.put("database",Json.read(Files.readString(dbFile)).path("database").asText());
            try(var e=new Child("engine",engineCp,"cn.agentplatform.engine.infra.PortalRunEventsJoinedEngine",ec)){
                var seed=awaitFile(run.resolve("engine-ready.json"),e.process);assertTrue(seed.path("maxParents").asInt()>=65,"complete production state parents");String id=seed.path("run").asText();issuer.register(id,seed.path("snapshot").asText());
                var gc=new LinkedHashMap<>(issuer.privateConfig("gateway"));gc.put("enginePort",seed.path("port").asInt());
                try(var g=new Child("gateway",gatewayCp,PortalRunEventsJoinedChainTest.class.getName(),gc)){
                    int port=awaitFile(run.resolve("gateway-ready.json"),g.process).path("port").asInt();String path="/admin/runs/"+id+"/events";
                    // Critical denial checks precede accepting any joined positive response.
                    for(var actor:List.of(List.of("tenant-a","bob"),List.of("tenant-b","alice"))){issuer.actor(actor.get(0),actor.get(1));var denial=http.send(request(port,path,issuer.token(),null),HttpResponse.BodyHandlers.ofString());assertTrue(Set.of(403,404).contains(denial.statusCode()));assertFalse(denial.body().contains(seed.path("snapshot").asText()));save("denial-"+actor.get(0)+"-"+actor.get(1)+".json",Map.of("http",denial.statusCode(),"code",Json.read(denial.body()).path("code").asText(),"materialLeak",false));}
                    issuer.actor("tenant-a","alice");String token=issuer.token();
                    HttpResponse<InputStream> live;
                    try{live=http.send(request(port,path,token,"0"),HttpResponse.BodyHandlers.ofInputStream());}
                    catch(IOException failure){g.send(Map.of("op","diagnose","token",token,"run",id));awaitFile(run.resolve("gateway-diagnostic.json"),g.process);save("authority-failed.json",issuer.observations());command(e,"observe",Map.of());throw failure;}
                    assertEquals(200,live.statusCode());
                    var lines=new BufferedReader(new InputStreamReader(live.body(),StandardCharsets.UTF_8));var consumed=new StringBuilder();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);String line;while(System.nanoTime()<deadline&&(line=lines.readLine())!=null){consumed.append(line).append('\n');if(line.equals("id: 2"))break;}
                    assertTrue(consumed.toString().contains("id: 1"));assertTrue(consumed.toString().contains("event: JOINED_NULL"));assertTrue(consumed.toString().contains("data: null"));assertTrue(consumed.toString().contains("id: 2"));
                    issuer.revoke(true);try(var reads=Executors.newSingleThreadExecutor()){String tail=reads.submit(()->{var b=new StringBuilder();String l;while((l=lines.readLine())!=null)b.append(l).append('\n');return b.toString();}).get(12,TimeUnit.SECONDS);save("live-revocation.json",Map.of("http",200,"firstTwoIds",true,"tailBytes",tail.getBytes(StandardCharsets.UTF_8).length,"eofIsTerminal",false));}finally{live.body().close();issuer.revoke(false);}
                    command(e,"finish",Map.of());
                    var backlog=http.send(request(port,path,token,null),HttpResponse.BodyHandlers.ofString());assertEquals(200,backlog.statusCode());assertTrue(backlog.body().contains("event: "+"观察".repeat(16)));assertTrue(backlog.body().contains("123456789012345678901234567890"));assertTrue(backlog.body().contains("event: stream_status"));save("backlog.json",Map.of("bodySha256",Digest.sha256(backlog.body()),"bytes",backlog.body().getBytes(StandardCharsets.UTF_8).length,"unicodeCodePoints",32,"unicodeUtf8Bytes",96,"highPrecision",true,"null",true));
                    var status=http.send(request(port,"/admin/runs/"+id,token,null),HttpResponse.BodyHandlers.ofString());assertEquals(200,status.statusCode());long watermark=Json.read(status.body()).path("data").path("eventsWatermark").asLong();assertTrue(watermark>=3);save("status.json",Map.of("watermark",watermark,"bodySha256",Digest.sha256(status.body())));
                    var reconnect=http.send(request(port,path+"?Last-Event-ID="+watermark,token,null),HttpResponse.BodyHandlers.ofString());assertEquals(200,reconnect.statusCode());assertFalse(reconnect.body().contains("event: JOINED_NULL"));
                    command(e,"retain",Map.of("through",1));var reset=http.send(request(port,path,token,"0"),HttpResponse.BodyHandlers.ofString());assertEquals(200,reset.statusCode());assertTrue(reset.body().contains("CURSOR_EXPIRED"));save("retention.json",Map.of("expiredCursor",true,"scopesRetained",true,"bodySha256",Digest.sha256(reset.body()),"testSeededRetention",true));
                    var observed=command(e,"observe",Map.of());assertEquals(0,observed.path("activeWriters").asInt());assertEquals(0,observed.path("owners").asInt());save("authority.json",issuer.observations());
                }
            }
        }
        save("issuer-closed.json",Map.of("closed",true));
    }
    public static void main(String[] args)throws Exception {
        run=Path.of(args[0]);try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){var c=Json.read(input.readLine());properties=Json.read(c.path("authority").toString(),AuthorityProperties.class);clock=new PortalEventsJoinedAuthorityFixture.TestClock(c.path("epoch").asLong());engine=URI.create("https://engine.f2a.test:"+c.path("enginePort").asInt());
            System.setProperty("javax.net.ssl.trustStore",run.resolve("server.p12").toString());System.setProperty("javax.net.ssl.trustStorePassword","f2a-test-only");
            authority=PortalEventsJoinedAuthorityFixture.client(properties,c.path("secret").asText(),clock);tls=new PortalEventsTls(engine,new byte[]{127,0,0,1},run.resolve("ca-root.pem"));
            try(var ownedAuthority=authority;var ownedTls=tls){var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");var v=new HashMap<String,Object>();v.put("server.address","127.0.0.1");v.put("server.port",0);v.put("server.tomcat.threads.max",64);v.put("server.tomcat.max-connections",1024);v.put("server.tomcat.processor-cache",64);v.put("server.http2.enabled",false);v.put("spring.config.location","optional:classpath:/joined-no-config/");v.put("spring.main.banner-mode","off");v.put("springdoc.api-docs.enabled",false);v.put("management.endpoints.web.exposure.exclude","*");v.put("management.endpoints.web.discovery.enabled",false);v.put("logging.level.root","ERROR");app.setDefaultProperties(v);
                try(var context=(ServletWebServerApplicationContext)app.run()){
                    var p=((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler();assertEquals(PortalRunEventsSse.ControlledProtocol.class,p.getClass());var protocol=(PortalRunEventsSse.ControlledProtocol)p;assertEquals(64,protocol.getMaxThreads());assertEquals(1024,protocol.getMaxConnections());assertEquals(64,protocol.getProcessorCache());assertEquals(PortalRunEventsHttp.class,context.getBean(PortalRunEventsHttp.class).getClass());assertEquals(RunEventsProxy.class,context.getBean(RunEventsProxy.class).getClass());
                    save("gateway-ready.json",Map.of("port",context.getWebServer().getPort(),"protocol",p.getClass().getName(),"productionBeans",true));String line;while((line=input.readLine())!=null){var cmd=Json.read(line);if(cmd.path("op").asText().equals("stop"))break;if(cmd.path("op").asText().equals("clock"))clock.set(cmd.path("epoch").asLong());if(cmd.path("op").asText().equals("diagnose"))diagnose(context.getBean(PortalRunEventsHttp.class),cmd);if(cmd.has("id"))save("gateway-ack-"+cmd.path("id").asInt()+".json",gatewayFacts());}
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(20);save("gateway-cleanup.json",Map.of("owners",PortalEventsPacketBudget.SHARED.owners(),"bytes",PortalEventsPacketBudget.SHARED.reservedBytes(),"requests",requests.get(),"actualNotReady",notReady.get(),"writes",writes.get()));assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
                }save("gateway-closed.json",Map.of("contextClosed",true));
            }
        }
    }
    static void diagnose(PortalRunEventsHttp transport,JsonNode command)throws Exception {
        var phases=new ArrayList<String>();var facts=new LinkedHashMap<String,Object>();
        try(var session=transport.begin(authority,command.path("token").asText(),command.path("run").asText(),0)){
            session.transportCheckpointForTest(phases::add);session.materialCheckpointForTest((phase,index)->{if(phases.size()<128)phases.add(phase+":"+index);});
            phases.add("before-Q");session.lookupMetadata();phases.add("Q-returned");
            try{session.consumeVerified(window->{phases.add("verified-window");throw new PlatformException(499,"DIAGNOSTIC_FIRST_WINDOW","bounded diagnostic exit");});}
            catch(Throwable failure){var f=session.getClass().getDeclaredField("child");f.setAccessible(true);var child=(HttpCurrentAuthorityClient.PortalEventsChildAuthorization)f.get(session);facts.put("childAssigned",child!=null);if(child!=null){String ticket=child.ticket().value();facts.put("actualChildTicketAsciiBytes",ticket.length());facts.put("childTicketSha256",Digest.sha256(ticket));}throw failure;}
        }catch(Throwable failure){facts.put("type",failure.getClass().getName());if(failure instanceof PlatformException p)facts.put("code",p.code());facts.put("stack",Arrays.stream(failure.getStackTrace()).limit(12).map(StackTraceElement::toString).toList());}
        facts.put("phases",phases);save("gateway-diagnostic.json",facts);
    }
}
