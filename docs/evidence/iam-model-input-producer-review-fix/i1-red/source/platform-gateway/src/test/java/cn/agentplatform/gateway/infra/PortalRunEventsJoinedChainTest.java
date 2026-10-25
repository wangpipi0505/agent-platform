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
    static final AtomicLong lastWriteNanos=new AtomicLong(),lastFlushNanos=new AtomicLong(),lastReadyFalseNanos=new AtomicLong();
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
                @Override public ServletOutputStream getOutputStream()throws IOException {var actual=super.getOutputStream();return new ServletOutputStream(){public boolean isReady(){boolean ready=actual.isReady();if(!ready){notReady.incrementAndGet();lastReadyFalseNanos.set(System.nanoTime());}return ready;}public void setWriteListener(WriteListener l){actual.setWriteListener(l);}public void write(int v)throws IOException{actual.write(v);writes.incrementAndGet();lastWriteNanos.set(System.nanoTime());}public void write(byte[] v,int o,int n)throws IOException{actual.write(v,o,n);writes.incrementAndGet();lastWriteNanos.set(System.nanoTime());}public void flush()throws IOException{actual.flush();lastFlushNanos.set(System.nanoTime());}public void close()throws IOException{actual.close();}};}
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
            save(name+"-stop-request.json",Map.of("pid",process.pid(),"alive",process.isAlive(),"wallTime",Instant.now().toString(),"monotonicNanos",System.nanoTime()));
            if(process.isAlive())try{send(Map.of("op","stop"));input.close();}catch(IOException ignored){}
            boolean graceful=process.waitFor(15,TimeUnit.SECONDS);if(!graceful)process.destroyForcibly();boolean waited=process.waitFor(5,TimeUnit.SECONDS);collector.join(5000);
            save(name+"-exit.json",Map.of("pid",process.pid(),"exit",waited?process.exitValue():-1,"waitForReturned",waited,"graceful",graceful,"collectorJoined",!collector.isAlive()));assertTrue(waited);assertFalse(collector.isAlive());assertTrue(graceful,"bounded graceful child shutdown");assertEquals(0,process.exitValue(),"inspect "+name+"-out.log");
        }
    }
    int commandId;
    JsonNode command(Child e,String op,Map<String,Object> extra)throws Exception {var cmd=new LinkedHashMap<String,Object>(extra);cmd.put("op",op);cmd.put("id",++commandId);e.send(cmd);return awaitFile(run.resolve("engine-ack-"+commandId+".json"),e.process);}
    HttpRequest request(int port,String path,String token,String cursor){var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+token);if(cursor!=null)r.header("Last-Event-ID",cursor);return r.GET().build();}
    JsonNode gatewayCommand(Child g,String op,Map<String,Object> extra)throws Exception{var cmd=new LinkedHashMap<String,Object>(extra);cmd.put("op",op);cmd.put("id",++commandId);g.send(cmd);return awaitFile(run.resolve("gateway-ack-"+commandId+".json"),g.process);}
    static Map<String,Object> gatewayFacts(){var facts=new LinkedHashMap<String,Object>();facts.put("owners",PortalEventsPacketBudget.SHARED.owners());facts.put("bytes",PortalEventsPacketBudget.SHARED.reservedBytes());facts.put("requests",requests.get());facts.put("actualNotReady",notReady.get());facts.put("writes",writes.get());facts.put("completeCallbacks",completes.get());facts.put("readerThreads",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive()&&t.getName().equals("portal-events-sse-reader")).count());facts.put("wallTime",Instant.now().toString());facts.put("monotonicNanos",System.nanoTime());facts.put("tlsSlots",tlsFacts());facts.put("lastWriteReturnedNanos",lastWriteNanos.get());facts.put("lastFlushReturnedNanos",lastFlushNanos.get());facts.put("lastReadyFalseNanos",lastReadyFalseNanos.get());facts.put("fixtureClock",clock.instant().toString());return facts;}
    /** Same-module read-only diagnostics; no field is changed and no close/refund is triggered. */
    static List<Map<String,Object>> tlsFacts(){
        var rows=new ArrayList<Map<String,Object>>();try{
            var field=PortalEventsTls.class.getDeclaredField("SLOTS");field.setAccessible(true);var slots=(Object[])field.get(null);
            for(var slot:slots){if(slot==null)continue;var row=new LinkedHashMap<String,Object>();synchronized(slot){
                for(String key:List.of("stopped","closeScheduled","terminated","knownClosed","knownInputShutdown","knownOutputShutdown","active")){var f=slot.getClass().getDeclaredField(key);f.setAccessible(true);row.put(key,f.get(slot));}
                var close=slot.getClass().getDeclaredField("closeFailure");close.setAccessible(true);var failure=(Throwable)close.get(slot);row.put("closeFailureClass",failure==null?"":failure.getClass().getName());if(failure!=null){var cause=failure.getCause();row.put("causeClass",cause==null?"":cause.getClass().getName());row.put("causeStack",cause==null?List.of():Arrays.stream(cause.getStackTrace()).limit(12).map(StackTraceElement::toString).toList());}
            }rows.add(row);}
        }catch(ReflectiveOperationException unavailable){rows.add(Map.of("diagnosticExceptionClass",unavailable.getClass().getName()));}return rows;
    }
    void checkpoint(Child e,Child g,PortalEventsJoinedAuthorityFixture issuer,String phase)throws Exception{
        save("phase-"+phase+".json",Map.of("phase",phase,"wallTime",Instant.now().toString(),"monotonicNanos",System.nanoTime(),"fixtureClock",issuer.iam.clock().instant().toString(),"gateway",gatewayCommand(g,"snapshot",Map.of()),"engine",command(e,"observe",Map.of()),"authority",issuer.observations()));
    }
    JsonNode inventory(Child e)throws Exception{command(e,"inventory",Map.of());return Json.read(Files.readString(run.resolve("engine-inventory-"+commandId+".json")));}
    void automaticCleanup(Child e,Child g,PortalEventsJoinedAuthorityFixture issuer,String phase)throws Exception{
        JsonNode gw=null,eng=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        do{gw=gatewayCommand(g,"observe",Map.of());eng=command(e,"observe",Map.of());if(gw.path("owners").asInt()==0&&eng.path("owners").asInt()==0&&eng.path("activeWriters").asInt()==0)break;Thread.sleep(100);}while(System.nanoTime()<until);
        checkpoint(e,g,issuer,phase);assertEquals(0,gw.path("owners").asInt());assertEquals(0,gw.path("readerThreads").asInt());assertEquals(3145728,gw.path("bytes").asLong());assertTrue(gw.path("tlsSlots").isEmpty());assertEquals(0,eng.path("activeWriters").asInt());assertEquals(0,eng.path("owners").asInt());
    }
    static Map<String,Object> responseSummary(HttpResponse<String> response)throws IOException{
        var result=new LinkedHashMap<String,Object>();result.put("http",response.statusCode());result.put("bodyBytes",response.body().getBytes(StandardCharsets.UTF_8).length);result.put("bodySha256",Digest.sha256(response.body()));var frames=new ArrayList<Map<String,Object>>();
        try(var reader=new BufferedReader(new StringReader(response.body()))){for(int i=0;i<64;i++){var one=frame(reader);if(one.isEmpty())break;var row=new LinkedHashMap<String,Object>();row.put("event",one.getOrDefault("event",""));row.put("id",one.getOrDefault("id",""));if(one.containsKey("data")){row.put("dataSha256",Digest.sha256(one.get("data")));if("stream_reset".equals(one.get("event"))){var control=Json.read(one.get("data"));row.put("reason",control.path("reason").asText());row.put("status",control.path("status").asText());row.put("watermark",control.path("eventsWatermark").asLong());}}frames.add(row);}}
        result.put("frames",frames);return result;
    }
    static Map<String,String> frame(BufferedReader reader)throws IOException{var fields=new LinkedHashMap<String,String>();String line;while((line=reader.readLine())!=null){if(line.isEmpty()){if(!fields.isEmpty())return fields;continue;}int colon=line.indexOf(':');if(colon>=0)fields.put(line.substring(0,colon),line.substring(colon+1).replaceFirst("^ ",""));}return fields;}
    static String tail(BufferedReader reader)throws IOException{var text=new StringBuilder();String line;while((line=reader.readLine())!=null){if(text.length()+line.length()>524288)throw new IOException("BOUNDED_TAIL");text.append(line).append('\n');}return text.toString();}
    static void closeContext(ServletWebServerApplicationContext context)throws Exception{
        boolean returned=false;String exception="";
        try{save("gateway-context-close-entry.json",Map.of("metrics",gatewayFacts(),"threads",PortalEventsJoinedAuthorityFixture.boundedThreads()));}
        finally{
            try{context.close();returned=true;}
            catch(RuntimeException|Error failure){exception=failure.getClass().getName();throw failure;}
            finally{save("gateway-context-close-result.json",Map.of("actualReturn",returned,"exceptionClass",exception,"metrics",gatewayFacts(),"threads",PortalEventsJoinedAuthorityFixture.boundedThreads()));}
        }
    }
    @Test void exactPayloadAndRealRenewal()throws Exception {focused(false);}
    @Test void slowReaderCurrentAuthorityAndExpiry()throws Exception {focused(true);}
    @Test void retainedTerminalHistoryScopeInventory()throws Exception {focused(true,"retention");}
    @Test void retainedStreamControlledExpiry()throws Exception {focused(true,"expiry");}
    void focused(boolean pressureOnly)throws Exception {focused(pressureOnly,pressureOnly?"slow":"renewal");}
    void focused(boolean pressureOnly,String remaining)throws Exception {
        run=Files.createDirectories(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/runs/focused-"+UUID.randomUUID()));Files.writeString(run.resolve("hosts"),"127.0.0.1 engine.f2a.test\n");try(var in=PortalRunEventsJoinedChainTest.class.getResourceAsStream("/iam-o2b-f2a/tls-fixtures.p12")){Files.copy(Objects.requireNonNull(in),run.resolve("server.p12"));}PortalRunEventsHttpTest.ca(run,"root");
        var issuer=new PortalEventsJoinedAuthorityFixture();String issuerUrl=issuer.iam.issuer();
        try(issuer;var http=HttpClient.newHttpClient();var reads=Executors.newSingleThreadExecutor()){
            var ec=new LinkedHashMap<>(issuer.privateConfig("engine"));ec.put("admission",issuer.admission());ec.put("database",Json.read(Files.readString(root().resolve(".local/iam-o2b-f2c2b2-joined-chain/database.json"))).path("database").asText());
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
                    checkpoint(e,g,issuer,"renewed");issuer.narrow(true);String narrowed=reads.submit(()->tail(reader)).get(12,TimeUnit.SECONDS);response.body().close();checkpoint(e,g,issuer,"narrowed");save("narrowing-renew.json",Map.of("gatewayCheck",true,"engineChildCheck",true,"tailBytes",narrowed.getBytes(StandardCharsets.UTF_8).length,"eofIsTerminal",false));issuer.narrow(false);save("authority.json",issuer.observations());return;}
                    command(e,"bulk",Map.of());if(remaining.equals("slow")){JsonNode baseline=gatewayCommand(g,"observe",Map.of());assertEquals(0,baseline.path("owners").asInt());int requestBefore=baseline.path("requests").asInt();
                    try(var socket=new Socket()){
                        socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port));socket.setSoTimeout(12000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nLast-Event-ID: 2\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();var head=new ByteArrayOutputStream();while(head.size()<16384&&!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")){int value=socket.getInputStream().read();assertTrue(value>=0);head.write(value);}assertTrue(head.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 200"));
                        JsonNode pressure=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){pressure=gatewayCommand(g,"observe",Map.of());if(pressure.path("actualNotReady").asInt()>0)break;Thread.sleep(100);}assertNotNull(pressure);assertTrue(pressure.path("actualNotReady").asInt()>0,"delegated actual isReady=false");assertEquals(1,pressure.path("requests").asInt()-requestBefore);save("slow-reader-active.json",pressure);
                        checkpoint(e,g,issuer,"ready-false");issuer.narrow(true);checkpoint(e,g,issuer,"narrow-requested");JsonNode ended=null;long narrowStart=System.nanoTime();until=narrowStart+TimeUnit.SECONDS.toNanos(21);boolean twelveRecorded=false;
                        // The browser socket stays open and unread. This observes existing idle/physical gates;
                        // no lifecycle handle is reaped or refunded by this fixture.
                        while(System.nanoTime()<until){ended=gatewayCommand(g,"observe",Map.of());if(!twelveRecorded&&System.nanoTime()-narrowStart>=TimeUnit.SECONDS.toNanos(12)){checkpoint(e,g,issuer,"narrow-12s");twelveRecorded=true;}if(ended.path("owners").asInt()==0)break;Thread.sleep(100);}
                        checkpoint(e,g,issuer,"narrow-final-socket-still-open");save("slow-reader-narrowed-cleanup.json",Map.of("metrics",ended,"observationNanos",System.nanoTime()-narrowStart,"browserSocketClosed",socket.isClosed(),"bodyReadsAfterHeaders",0));assertFalse(socket.isClosed());assertEquals(0,ended.path("owners").asInt());assertEquals(0,ended.path("readerThreads").asInt());assertEquals(3145728,ended.path("bytes").asLong());assertTrue(ended.path("tlsSlots").isEmpty());var engineEnded=command(e,"observe",Map.of());assertEquals(0,engineEnded.path("activeWriters").asInt());assertEquals(0,engineEnded.path("owners").asInt());
                    }finally{checkpoint(e,g,issuer,"socket-closed");}issuer.narrow(false);save("authority.json",issuer.observations());return;}
                    if(remaining.equals("retention")){var finished=command(e,"finish",Map.of());assertEquals("FAILED",finished.path("status").asText());var retainedBefore=inventory(e);command(e,"retain",Map.of("through",1));var retainedAfter=inventory(e);assertEquals(retainedBefore.path("scopeCount"),retainedAfter.path("scopeCount"));assertEquals(retainedBefore.path("scopeInventorySha256"),retainedAfter.path("scopeInventorySha256"));assertEquals(retainedBefore.path("eventRows").asLong()-1,retainedAfter.path("eventRows").asLong());var reset=http.send(request(port,path,token,"0"),HttpResponse.BodyHandlers.ofString());save("retention-response.json",responseSummary(reset));checkpoint(e,g,issuer,"retention-response");assertEquals(200,reset.statusCode());assertTrue(reset.body().contains("CURSOR_EXPIRED"));automaticCleanup(e,g,issuer,"retention-automatic-cleanup");save("retained-scope-proof.json",Map.of("scopeCount",retainedAfter.path("scopeCount").asInt(),"inventorySha256",retainedAfter.path("scopeInventorySha256").asText(),"eventRowsBefore",retainedBefore.path("eventRows").asLong(),"eventRowsAfter",retainedAfter.path("eventRows").asLong(),"readerReset",true,"testSeededMaintenance",true));save("authority.json",issuer.observations());return;}
                    var expired=http.send(request(port,path,token,"34"),HttpResponse.BodyHandlers.ofInputStream());assertEquals(200,expired.statusCode());var expiryReader=new BufferedReader(new InputStreamReader(expired.body(),StandardCharsets.UTF_8));assertEquals("stream_status",reads.submit(()->frame(expiryReader)).get(12,TimeUnit.SECONDS).get("event"));checkpoint(e,g,issuer,"before-clock-advance");issuer.iam.advanceClock(Duration.ofSeconds(121));long epoch=issuer.iam.clock().instant().getEpochSecond();var engineClock=command(e,"clock",Map.of("epoch",epoch));var gatewayClock=gatewayCommand(g,"clock",Map.of("epoch",epoch));assertEquals(issuer.iam.clock().instant().toString(),engineClock.path("fixtureClock").asText());assertEquals(issuer.iam.clock().instant().toString(),gatewayClock.path("fixtureClock").asText());String expiryTail=reads.submit(()->tail(expiryReader)).get(12,TimeUnit.SECONDS);expired.body().close();automaticCleanup(e,g,issuer,"after-expiry");var expiredToken=http.send(request(port,path,token,null),HttpResponse.BodyHandlers.ofString());assertEquals(401,expiredToken.statusCode());save("expiry.json",Map.of("epoch",epoch,"tailBytes",expiryTail.getBytes(StandardCharsets.UTF_8).length,"eofIsTerminal",false,"newRequestStatus",expiredToken.statusCode(),"allThreeClocksAligned",true));
                    var after=command(e,"observe",Map.of());assertEquals(0,after.path("activeWriters").asInt());assertEquals(0,after.path("owners").asInt());save("authority.json",issuer.observations());
                }
            }
        }finally{
            try(var probe=HttpClient.newHttpClient()){assertThrows(IOException.class,()->probe.send(HttpRequest.newBuilder(URI.create(issuerUrl+"/jwks")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()));}save("issuer-closed.json",Map.of("closed",true,"actualEndpointRefused",true));
        }
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
                var context=(ServletWebServerApplicationContext)app.run();try{
                    var p=((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler();assertEquals(PortalRunEventsSse.ControlledProtocol.class,p.getClass());var protocol=(PortalRunEventsSse.ControlledProtocol)p;assertEquals(64,protocol.getMaxThreads());assertEquals(1024,protocol.getMaxConnections());assertEquals(64,protocol.getProcessorCache());assertEquals(PortalRunEventsHttp.class,context.getBean(PortalRunEventsHttp.class).getClass());assertEquals(RunEventsProxy.class,context.getBean(RunEventsProxy.class).getClass());
                    save("gateway-ready.json",Map.of("port",context.getWebServer().getPort(),"protocol",p.getClass().getName(),"productionBeans",true));String line;while((line=input.readLine())!=null){var cmd=Json.read(line);if(cmd.path("op").asText().equals("stop"))break;if(cmd.path("op").asText().equals("clock"))clock.set(cmd.path("epoch").asLong());if(cmd.path("op").asText().equals("diagnose"))diagnose(context.getBean(PortalRunEventsHttp.class),cmd);if(cmd.has("id"))save("gateway-ack-"+cmd.path("id").asInt()+".json",cmd.path("op").asText().equals("snapshot")?Map.of("metrics",gatewayFacts(),"threads",PortalEventsJoinedAuthorityFixture.boundedThreads()):gatewayFacts());}
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(20);save("gateway-cleanup-pre-context-close.json",Map.of("owners",PortalEventsPacketBudget.SHARED.owners(),"bytes",PortalEventsPacketBudget.SHARED.reservedBytes(),"requests",requests.get(),"actualNotReady",notReady.get(),"writes",writes.get()));assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
                }finally{closeContext(context);}save("gateway-closed.json",Map.of("contextClosed",true));
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
