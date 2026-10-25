package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.*;
import cn.agentplatform.gateway.infra.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Parent gateway has no engine business dependency; only HTTP + private child management cross. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class GatewayRunStatusChainPostgresTest {
    static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    static LoopbackAuthority iam;static Child engine;static int enginePort;
    static ServletWebServerApplicationContext context;static HttpClient browser=HttpClient.newHttpClient();
    static String tenant,source,run,sha;static long runId;static JsonNode baseline;
    static final AtomicReference<Consumer<jakarta.servlet.http.HttpServletRequest>> streamAction=new AtomicReference<>(r->{});
    static final AtomicInteger streamWindows=new AtomicInteger(),businessWrites=new AtomicInteger();static volatile boolean partial;
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class Receiver {
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> outputWindow(){
            var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>();bean.setOrder(Integer.MIN_VALUE);
            bean.setFilter((request,response,chain)->{
                var r=(jakarta.servlet.http.HttpServletRequest)request;
                if(r.getRequestURI().equals("/o1c-forward")){r.getRequestDispatcher("/admin/runs/"+run).forward(r,response);return;}
                chain.doFilter(request,new jakarta.servlet.http.HttpServletResponseWrapper((jakarta.servlet.http.HttpServletResponse)response){
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream()throws IOException {
                        var actual=super.getOutputStream();streamWindows.incrementAndGet();streamAction.getAndSet(q->{}).accept(((org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest());
                        return new jakarta.servlet.ServletOutputStream(){
                            @Override public boolean isReady(){return actual.isReady();}@Override public void setWriteListener(jakarta.servlet.WriteListener l){actual.setWriteListener(l);}
                            @Override public void write(int b)throws IOException{actual.write(b);}
                            @Override public void write(byte[] b,int o,int n)throws IOException{if(getStatus()==200)businessWrites.addAndGet(partial?Math.min(n,128):n);if(partial&&getStatus()==200){actual.write(b,o,Math.min(n,128));actual.flush();throw new IOException("O1C_FINITE_PARTIAL_WRITE");}actual.write(b,o,n);}
                            @Override public void close()throws IOException{actual.close();}
                        };
                    }
                });
            });return bean;
        }
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor deployment(org.springframework.core.env.Environment e){return f->f.registerSingleton("authorityProperties",SecurityConfiguration.bindDeployment(e));}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean Clock clock(){return iam.clock();}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess("http://127.0.0.1:"+enginePort,"http://127.0.0.1:"+enginePort,d);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient authority(){return iam.client("gateway");}
        @Bean(destroyMethod="close") PublishedRunHttp runStatusHttp(){return new PublishedRunHttp();}
        @Bean(destroyMethod="close") RunObservationMetadataClient metadata(PublishedRunHttp h,HttpCurrentAuthorityClient a){return new RunObservationMetadataClient(URI.create("http://127.0.0.1:"+enginePort),a,h,true);}
        @Bean(destroyMethod="close") RunStatusProxy status(PublishedRunHttp h,HttpCurrentAuthorityClient a,RunObservationMetadataClient m){return new RunStatusProxy(URI.create("http://127.0.0.1:"+enginePort),m,a,h,true);}
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity @Import({GatewayController.class,GatewayIamAssembly.class,PlatformErrors.class})
    static class NoStatusReceiver {
        @Bean AuthorityProperties properties(){return iam.config("gateway");}
        @Bean(destroyMethod="close") TrustedJwtDecoder decoder(AuthorityProperties p){return new TrustedJwtDecoder(p,iam.clock(),true);}
        @Bean Clock clock(){return iam.clock();}
        @Bean IntegrationAccess integration(TrustedJwtDecoder d){return new IntegrationAccess("http://127.0.0.1:"+enginePort,"http://127.0.0.1:"+enginePort,d);}
    }
    @BeforeAll static void start()throws Exception {
        assertThat(System.getProperty("java.class.path")).doesNotContain("engine-runtime","app-management");
        iam=new LoopbackAuthority();iam.portalActor(actor("o1c-initial"));
        try{
            engine=new Child();engine.send(Map.of("trust",iam.config("engine"),"secret",iam.secrets.get("engine"),"now",iam.clock().instant().toString()));
            var ready=engine.await("READY",35);enginePort=ready.path("port").asInt();System.out.println("O1C_ENGINE_ID="+ready);
            var boot=new SpringApplication(Receiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/o1c-gateway-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("logging.level.cn.agentplatform.gateway.application.RunStatusProxy","WARN")));
            boot.addInitializers(c->{var p=new LinkedHashMap<String,Object>();flatten("agent.security",Json.tree(iam.config("gateway")),p);p.put("agent.dev-auth",false);c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("o1c-trust",p));});
            context=(ServletWebServerApplicationContext)boot.run();
            System.out.println("O1C_GATEWAY_ID="+Json.write(Map.of("pid",ProcessHandle.current().pid(),"port",context.getWebServer().getPort())));
        }catch(Throwable e){try{close();}catch(Throwable cleanup){e.addSuppressed(cleanup);}throw e;}
    }
    static Actor actor(String t){return new Actor(t,"alice",Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial")));}
    @BeforeEach void seed()throws Exception {
        streamAction.set(r->{});streamWindows.set(0);businessWrites.set(0);partial=false;
        iam.clearFiniteGrants();iam.revoked(false);iam.unavailable(false);iam.ticketMutation(c->{});iam.decisionMutation(c->{});
        tenant="o1c-"+UUID.randomUUID();iam.portalActor(actor(tenant));source=iam.portal();
        engine.command(Map.of("action","clock","now",iam.clock().instant().toString()));
        try(var gateway=iam.client("gateway")){
            var ticket=gateway.exchange(source,new ServiceCall("engine","proxy.engine.post.RunController.run",new ResourceBinding("app-version","app-a@1",Digest.sha256("o1c-candidate"))));
            var seed=engine.command(Map.of("action","seed","tenant",tenant,"ticket",ticket.value()));run=seed.path("run").asText();sha=seed.path("sha").asText();runId=seed.path("id").asLong();
            assertThat(run).matches("r_[0-9a-f]{32}");assertThat(seed.path("gate").asText()).contains("realDag");
            iam.registerObservation(new SubjectKey(tenant,"alice"),run,sha);System.out.println("O1C_TRUSTED_SEED="+seed);
        }
        iam.clearFiniteGrants();iam.registerObservation(new SubjectKey(tenant,"alice"),run,sha);engine.command(Map.of("action","reset"));baseline=stats();
    }
    @AfterEach void evidence(TestInfo info)throws Exception {streamAction.set(r->{});partial=false;System.out.println("O1C_CASE="+Json.write(Map.of("test",info.getDisplayName(),"engine",stats(),"operations",iam.observedOperations(),"authorityCalls",iam.authorityCalls(),"streamWindows",streamWindows.get(),"businessWriteBytes",businessWrites.get())));}
    @AfterAll static void close()throws Exception {if(context!=null){context.close();context=null;}if(engine!=null){engine.close();engine=null;}browser.close();if(iam!=null){iam.close();iam=null;}System.out.println("O1C_PARENT_RESOURCES_CLOSED=true");}
    static JsonNode stats()throws Exception{return engine.command(Map.of("action","stats"));}
    static HttpResponse<byte[]> get(String path,String token)throws Exception{return browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    @Test void realGatewayQueryAndStatusReturnOriginalBytes()throws Exception {
        var response=get("/admin/runs/"+run,source);
        assertThat(response.statusCode()).as(new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(200);
        var root=Json.read(new String(response.body(),StandardCharsets.UTF_8));assertThat(root.path("code").asText()).isEqualTo("OK");assertThat(root.path("data").path("runId").asText()).isEqualTo(run);
        assertThat(response.body()).isEqualTo(Base64.getDecoder().decode(engine.command(Map.of("action","last")).path("base64").asText()));
        assertThat(new String(response.body(),StandardCharsets.UTF_8)).contains("123456789012345678901234567890","1.2345678901234567890123456789","\"nullValue\":null","观察\\n\\\"\\\\");assertThat(root.path("data").size()).isEqualTo(12);
        // Tomcat may compute a fresh length when the entire finite body fits its buffer.
        response.headers().firstValue("Content-Length").ifPresent(v->assertThat(Long.parseLong(v)).isEqualTo(response.body().length));
        var after=stats();assertThat(after.path("facts")).isEqualTo(baseline.path("facts"));assertThat(after.path("dependencies").asInt()).isZero();assertThat(after.path("workers").asInt()).isZero();
        assertThat(after.path("metadata").asInt()).isEqualTo(1);assertThat(after.path("status").asInt()).isEqualTo(1);
        var exchanges=iam.requests.stream().filter(r->r.path("operation").asText().equals("exchange")).toList();assertThat(exchanges).hasSize(2);for(var q:exchanges)assertThat(q.path("subject_token").asText()).isEqualTo(source);
        assertThat(iam.grants).hasSize(2);assertThat(iam.operations).hasSize(2);
        assertThat(iam.observedOperations()).containsExactly("exchange","authorize","authorize","exchange","authorize","authorize");
        var payload=new ArrayList<JsonNode>();after.path("sql").forEach(s->{if(payload(s.path("sql").asText()))payload.add(s);});assertThat(payload).isNotEmpty();assertThat(payload.stream().map(s->s.path("connection").asInt()).distinct()).hasSize(1);for(var s:payload){assertThat(s.path("readOnly").asBoolean()).isTrue();assertThat(s.path("isolation").asInt()).isEqualTo(4);}
        int firstPayload=-1;for(int i=0;i<after.path("sql").size();i++)if(payload(after.path("sql").get(i).path("sql").asText())){firstPayload=i;break;}assertThat(firstPayload).isGreaterThan(3);
        for(var w:after.path("wire")){assertThat(w.path("authorizationCount").asInt()).isEqualTo(1);assertThat(w.path("cookie").asBoolean()).isFalse();assertThat(w.path("ticketSha").asText()).isNotEqualTo(Digest.sha256("Bearer "+source));assertThat(w.path("query").asText()).isEqualTo("<null>");}
        System.out.println("O1C_RAW_EQUAL="+Json.write(Map.of("bytes",response.body().length,"sha",Digest.sha256(new String(response.body(),StandardCharsets.UTF_8)),"payloadConnection",payload.getFirst().path("connection").asInt(),"qControlPayload",0)));
    }
    static boolean payload(String sql){return sql.startsWith("select row_to_json(material)::text")||sql.matches("select (snapshot|inputs|version_refs|budget|output|error|definition)::text from engine\\.(run|node_instance).* ".stripTrailing());}
    static void resetObservation()throws Exception {iam.clearFiniteGrants();iam.ticketMutation(c->{});iam.registerObservation(new SubjectKey(tenant,"alice"),run,sha);engine.command(Map.of("action","reset"));businessWrites.set(0);streamWindows.set(0);}
    static void fault(String path,int status,String body)throws Exception {engine.command(Map.of("action","fault","path",path,"status",status,"body",body));}
    static void noBusiness(HttpResponse<byte[]> r,int status){assertThat(r.statusCode()).isEqualTo(status);assertThat(new String(r.body(),StandardCharsets.UTF_8)).doesNotContain("\"nodes\"","O1C_NUMERIC_FIXTURE","privateInput");assertThat(businessWrites.get()).isZero();r.headers().firstValue("Content-Length").ifPresent(v->assertThat(Integer.parseInt(v)).isEqualTo(r.body().length));assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();}
    @Test void actualOutputStreamAcquisitionRechecksCaptureAndMain()throws Exception {
        for(String mode:List.of("capture-close","capture-replace","internal-expiry","source-expiry")){
            resetObservation();streamAction.set(r->{switch(mode){case "capture-close"->context.getBean(GatewayIngressBoundary.class).afterCompletion(r,null,null,null);case "capture-replace"->r.setAttribute(GatewayIngressBoundary.class.getName()+".proof",new Object());case "internal-expiry"->iam.advanceClock(Duration.ofSeconds(6));case "source-expiry"->iam.advanceClock(Duration.ofSeconds(41));default->throw new AssertionError();}});
            var r=get("/admin/runs/"+run,source);noBusiness(r,mode.equals("source-expiry")?401:mode.equals("internal-expiry")?503:403);assertThat(streamWindows.get()).isPositive();assertThat(stats().path("status").asInt()).isEqualTo(1);
            iam.advanceClock(Duration.ofSeconds(mode.equals("source-expiry")?-41:mode.equals("internal-expiry")?-6:0));
        }
    }
    @Test void initialPortalVerifierInfrastructureIs503AndExplicitInvalidSourceIs401()throws Exception {
        try{
            for(boolean malformed:List.of(false,true)){
                resetObservation();iam.server.removeContext("/portal-jwks");iam.server.createContext("/portal-jwks",x->LoopbackAuthority.send(x,malformed?200:503,malformed?"{bad-jwks":"unavailable"));
                noBusiness(get("/admin/runs/"+run,source),503);assertThat(iam.authorityCalls()).isZero();assertThat(stats().path("wire")).isEmpty();
            }
        }finally{iam.server.removeContext("/portal-jwks");iam.server.createContext("/portal-jwks",x->LoopbackAuthority.send(x,200,new com.nimbusds.jose.jwk.JWKSet(iam.portalKey.toPublicJWK()).toString()));}
        var unknownClaims=iam.base("user","portal-gateway",20);unknownClaims.putAll(Map.of("iss",iam.issuer()+"/portal","sub","alice","tenant_id",tenant,"client_id","portal-web","roles",Set.of("EDITOR"),"attributes",actor(tenant).attributes(),"scope","platform.access"));
        for(String invalid:List.of("bad",iam.portal(-1),iam.sign(unknownClaims,LoopbackIssuer.key("unknown-portal-kid"),"at+jwt"))){var r=get("/admin/runs/"+run,invalid);noBusiness(r,401);assertThat(Json.read(new String(r.body(),StandardCharsets.UTF_8)).path("code").asText()).isEqualTo("PORTAL_TOKEN_INVALID");assertThat(iam.authorityCalls()).isZero();}
    }
    @Test void internal401NeverBecomesPortalLogout()throws Exception {
        for(String path:List.of("metadata","status")){
            resetObservation();fault(path,401,"");noBusiness(get("/admin/runs/"+run,source),503);assertThat(iam.requests.stream().filter(r->r.path("operation").asText().equals("exchange"))).hasSize(path.equals("metadata")?1:2);assertThat(stats().path("wire")).hasSize(path.equals("metadata")?1:2);
        }
        resetObservation();iam.ticketMutation(c->c.put("aud","wrong-internal"));noBusiness(get("/admin/runs/"+run,source),503);assertThat(stats().path("wire")).isEmpty();assertThat(iam.authorityCalls()).isEqualTo(1);
    }
    @Test void metadataFailureStopsBeforeMainExchangeAndKeepsControlReadsPure()throws Exception {
        String unknown="r_"+"f".repeat(32);noBusiness(get("/admin/runs/"+unknown,source),404);assertThat(iam.requests.stream().filter(r->r.path("operation").asText().equals("exchange"))).hasSize(1);assertThat(stats().path("status").asInt()).isZero();for(var s:stats().path("sql"))assertThat(payload(s.path("sql").asText())).isFalse();
        resetObservation();iam.portalActor(actor("o1c-other-owner"));String other=iam.portal();noBusiness(get("/admin/runs/"+run,other),404);iam.portalActor(actor(tenant));
        String valid=Json.write(new ApiResponse<>("OK","",Map.of("runId",run,"owner",new SubjectKey(tenant,"alice"),"root",new ResourceBinding("app-version","app-a@4294967297",Digest.sha256("candidate")),"appVersionId",4294967297L,"appVersionNo",4294967297L,"acceptedSnapshotSha256",sha,"initialCeilingSha256",Digest.sha256("initial")),null));
        var bad=new ArrayList<String>(List.of("","null","[]","{}",valid+" {}",valid.replace("\"runId\":","\"extra\":1,\"runId\":"),valid.replace("\"appVersionNo\":4294967297","\"appVersionNo\":9223372036854775808"),valid.replace(run,"r_"+"e".repeat(32)),valid.replace("\"owner\":","\"owner\":null,\"duplicateOwner\":")));
        for(String body:bad){resetObservation();fault("metadata",200,body);noBusiness(get("/admin/runs/"+run,source),503);assertThat(iam.authorityCalls()).isEqualTo(1);assertThat(stats().path("wire")).hasSize(1);}
        resetObservation();fault("metadata",200,valid);assertThat(get("/admin/runs/"+run,source).statusCode()).isEqualTo(200);assertThat(iam.requests.stream().filter(r->r.path("operation").asText().equals("exchange"))).hasSize(2);
    }
    @Test void missingStatusBeanKeepsActualIngressClosed()throws Exception {
        var boot=new SpringApplication(NoStatusReceiver.class);boot.setAdditionalProfiles("test");boot.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/o1c-closed-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
        try(var closed=(ServletWebServerApplicationContext)boot.run()){
            assertThat(closed.getBeansOfType(RunStatusProxy.class)).isEmpty();var r=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+closed.getWebServer().getPort()+"/admin/runs/"+run)).header("Authorization","Bearer "+source).GET().build(),HttpResponse.BodyHandlers.ofByteArray());noBusiness(r,403);assertThat(iam.authorityCalls()).isZero();assertThat(stats().path("wire")).isEmpty();System.out.println("O1C_NO_STATUS_RECEIVER="+closed.getWebServer().getPort());
        }
    }
    @Test void partialOutputNeverAppendsOrRetries()throws Exception {
        partial=true;try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){socket.setSoTimeout(5000);socket.getOutputStream().write(("GET /admin/runs/"+run+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+source+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();String raw=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(raw).contains("HTTP/1.1 200","\"code\":\"OK\"").doesNotContain("RUN_STATUS_UNAVAILABLE","PORTAL_TOKEN_INVALID");assertThat(businessWrites.get()).isEqualTo(128);assertThat(stats().path("wire")).hasSize(2);System.out.println("O1C_PARTIAL_PEER="+Json.write(Map.of("rawBytes",raw.getBytes(StandardCharsets.UTF_8).length,"attemptedBusinessPrefix",128,"actualBelowStream","unknown","wire",raw)));}
    }
    @Test void statusLimitMalformedAndWrongRunNeverReleaseBusinessBytes()throws Exception {
        String base="{\"code\":\"OK\",\"message\":\"\",\"data\":{\"runId\":\""+run+"\",\"padding\":\"%s\"},\"traceId\":null}";int overhead=base.formatted("").getBytes(StandardCharsets.UTF_8).length;
        resetObservation();fault("status",200,base.formatted("x".repeat(2*1024*1024-overhead)));var exact=get("/admin/runs/"+run,source);assertThat(exact.statusCode()).isEqualTo(200);assertThat(exact.body()).hasSize(2*1024*1024);
        for(String body:List.of(base.formatted("x".repeat(2*1024*1024-overhead+1)),"","null","[]",base.formatted("")+" {}",base.formatted("").replace(run,"r_"+"e".repeat(32)),base.formatted("").replace("\"runId\":","\"runId\":\"duplicate\",\"runId\":"))){resetObservation();fault("status",200,body);noBusiness(get("/admin/runs/"+run,source),503);assertThat(stats().path("wire")).hasSize(2);}
        resetObservation();try(var gateway=iam.client("gateway")){var ticket=gateway.exchange(source,new ServiceCall("engine","proxy.engine.post.RunController.run",new ResourceBinding("app-version","app-a@1",Digest.sha256("o1c-candidate"))));var seeded=engine.command(Map.of("action","seed","tenant",tenant,"ticket",ticket.value(),"padBytes",2*1024*1024));run=seeded.path("run").asText();sha=seeded.path("sha").asText();runId=seeded.path("id").asLong();}resetObservation();var before=stats().path("facts");var large=get("/admin/runs/"+run,source);noBusiness(large,403);assertThat(Json.read(new String(large.body(),StandardCharsets.UTF_8)).path("code").asText()).isEqualTo("RUN_STATUS_TOO_LARGE");assertThat(stats().path("facts")).isEqualTo(before);
    }
    @Test void nonUtf8StatusWireIsRejectedBeforeBusinessWrite()throws Exception {
        String valid=Json.write(new ApiResponse<>("OK","",Map.of("runId",run),null));
        engine.command(Map.of("action","fault","path","status","status",200,"base64",Base64.getEncoder().encodeToString(valid.getBytes(StandardCharsets.UTF_16))));
        noBusiness(get("/admin/runs/"+run,source),503);
    }
    @Test void metadataEncodingAndWireBudgetAreVerifiedIndependently()throws Exception {
        String valid=Json.write(new ApiResponse<>("OK","",Map.of("runId",run,"owner",new SubjectKey(tenant,"alice"),"root",new ResourceBinding("app-version","app-a@4294967297",Digest.sha256("candidate")),"appVersionId",4294967297L,"appVersionNo",4294967297L,"acceptedSnapshotSha256",sha,"initialCeilingSha256",Digest.sha256("initial")),null));
        for(String path:List.of("metadata","status")){
            String body=path.equals("metadata")?valid:Json.write(new ApiResponse<>("OK","",Map.of("runId",run),null));
            var samples=new ArrayList<byte[]>();for(var charset:List.of(StandardCharsets.UTF_16,StandardCharsets.UTF_16LE,java.nio.charset.Charset.forName("UTF-32BE")))samples.add(body.getBytes(charset));
            var bad=new ByteArrayOutputStream();bad.write("{\"code\":\"OK\",\"message\":\"".getBytes(StandardCharsets.UTF_8));bad.write(0xc3);bad.write("\",\"data\":{},\"traceId\":null}".getBytes(StandardCharsets.UTF_8));samples.add(bad.toByteArray());
            for(byte[] bytes:samples){resetObservation();engine.command(Map.of("action","fault","path",path,"status",200,"base64",Base64.getEncoder().encodeToString(bytes)));noBusiness(get("/admin/runs/"+run,source),503);assertThat(stats().path("wire")).hasSize(path.equals("metadata")?1:2);}
        }
        int data=Json.mapper().writeValueAsBytes(Json.read(valid).path("data")).length,wire=valid.getBytes(StandardCharsets.UTF_8).length;assertThat(data).isLessThan(4096);
        resetObservation();fault("metadata",200,valid+" ".repeat(8192-wire));var exact=get("/admin/runs/"+run,source);assertThat(exact.statusCode()).isEqualTo(200);
        resetObservation();fault("metadata",200,valid+" ".repeat(8193-wire));noBusiness(get("/admin/runs/"+run,source),503);assertThat(iam.authorityCalls()).isEqualTo(1);assertThat(stats().path("wire")).hasSize(1);
        System.out.println("O1C_Q_BUDGET="+Json.write(Map.of("dataUtf8",data,"baseWireUtf8",wire,"validWireExact",8192,"rejectedWire",8193,"data4096LegalExtreme","unreachable under exact field bounds; no illegal DTO fabricated")));
    }
    @Test void strictKnownDenialsRetain403AndUnsafePairsBecome503()throws Exception {
        for(String code:List.of("AUTHORITY_EXPIRED","RUN_AUTHORITY_DENIED","MATERIAL_SCOPE_MISSING_OR_CHANGED","AUTHORITY_DENIED","UNKNOWN","RUN_METADATA_TOO_LARGE")){
            resetObservation();fault("status",403,Json.write(new ApiResponse<>(code,"private error",null,"private trace")));var r=get("/admin/runs/"+run,source);noBusiness(r,Set.of("UNKNOWN","RUN_METADATA_TOO_LARGE").contains(code)?503:403);assertThat(new String(r.body(),StandardCharsets.UTF_8)).doesNotContain("private error","private trace");
        }
    }
    @Test void realEngineCurrentDenialAndActualStreamExpiryRemain403()throws Exception {
        iam.decisionMutation(c->{var last=iam.requests.getLast();if(last.path("operation").asText().equals("authorize")&&last.path("action").asText().equals(RunObservationSelectors.STATUS_ACTION))c.put("active",false);});
        var denial=get("/admin/runs/"+run,source);noBusiness(denial,403);assertThat(Json.read(new String(denial.body(),StandardCharsets.UTF_8)).path("code").asText()).isEqualTo("SYS-AUTH-403");assertThat(stats().path("wire")).hasSize(2);assertThat(stats().path("lastStatusBytes").asInt()).isZero();
        resetObservation();engine.command(Map.of("action","expireAtStream"));var expiry=get("/admin/runs/"+run,source);noBusiness(expiry,403);assertThat(Json.read(new String(expiry.body(),StandardCharsets.UTF_8)).path("code").asText()).isEqualTo("AUTHORITY_EXPIRED");assertThat(stats().path("lastStatusBytes").asInt()).isZero();engine.command(Map.of("action","clock","now",iam.clock().instant().toString()));
    }
    @Test void wrongMethodAliasQueryBodyHeadersAndForwardStopBeforeExchange()throws Exception {
        var requests=new ArrayList<HttpRequest>();String origin="http://127.0.0.1:"+context.getWebServer().getPort();
        for(String path:List.of("/admin/runs/"+run+"?x=1","/admin/debug-runs/"+run+"/status","/v1/runs/"+run,"/admin/runs/"+run+"/","/o1c-forward"))requests.add(HttpRequest.newBuilder(URI.create(origin+path)).header("Authorization","Bearer "+source).GET().build());
        for(String header:List.of("Cookie","X-Api-Key","X-User-Id","X-Tenant","X-Principal","X-Actor","X-Resource","X-Target","X-Action"))requests.add(HttpRequest.newBuilder(URI.create(origin+"/admin/runs/"+run)).header("Authorization","Bearer "+source).header(header,"forged").GET().build());
        requests.add(HttpRequest.newBuilder(URI.create(origin+"/admin/runs/"+run)).header("Authorization","Bearer "+source).header("Authorization","Bearer "+source).GET().build());requests.add(HttpRequest.newBuilder(URI.create(origin+"/admin/runs/"+run)).header("Authorization","Bearer "+source).method("GET",HttpRequest.BodyPublishers.ofString("{}")).build());requests.add(HttpRequest.newBuilder(URI.create(origin+"/admin/runs/"+run)).header("Authorization","Bearer "+source).POST(HttpRequest.BodyPublishers.noBody()).build());
        for(var request:requests){var r=browser.send(request,HttpResponse.BodyHandlers.ofByteArray());System.out.println("O1C_INGRESS_CASE="+Json.write(Map.of("path",request.uri().getRawPath(),"method",request.method(),"headerNames",request.headers().map().keySet(),"status",r.statusCode())));assertThat(r.statusCode()).as(request.uri()+" "+request.method()+" "+request.headers().map().keySet()).isIn(401,403);assertThat(new String(r.body(),StandardCharsets.UTF_8)).doesNotContain("\"nodes\"");}
        // JDK HttpClient drops the bare '?'; send the literal target over the owned real socket.
        for(boolean bare:List.of(true,false))try(var socket=new Socket("127.0.0.1",context.getWebServer().getPort())){socket.setSoTimeout(4000);socket.getOutputStream().write(("GET /admin/runs/"+run+(bare?"?":"")+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+source+"\r\n"+(bare?"":"Proxy-Authorization: forged\r\n")+"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();String raw=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(raw).startsWith("HTTP/1.1 403").doesNotContain("\"nodes\"");System.out.println(bare?"O1C_LITERAL_BARE_QUERY=403":"O1C_LITERAL_PROXY_AUTHORIZATION=403");}
        assertThat(iam.authorityCalls()).isZero();assertThat(stats().path("wire")).isEmpty();
    }
    @Test void zzOwnerClientCloseAfterActualStreamCannotReleaseStatus()throws Exception {streamAction.set(r->context.getBean(HttpCurrentAuthorityClient.class).close());noBusiness(get("/admin/runs/"+run,source),503);assertThat(stats().path("status").asInt()).isEqualTo(1);assertThat(streamWindows.get()).isPositive();}
    static void flatten(String prefix,JsonNode v,Map<String,Object> p){if(v.isObject())v.fields().forEachRemaining(e->{String k=prefix.endsWith("attribute-value-allowlist")?"["+e.getKey()+"]":"."+e.getKey().replaceAll("([a-z])([A-Z])","$1-$2").toLowerCase(Locale.ROOT);flatten(prefix+k,e.getValue(),p);});else if(v.isArray()){for(int i=0;i<v.size();i++)flatten(prefix+"["+i+"]",v.get(i),p);}else if(!v.isNull())p.put(prefix,v.isBoolean()?v.booleanValue():v.isNumber()?v.numberValue():v.asText());}
    static final class Child implements AutoCloseable {
        final Process process;final BufferedWriter stdin;final BlockingQueue<String> lines=new LinkedBlockingQueue<>();final StringBuilder output=new StringBuilder();final Thread drain;
        Child()throws Exception {
            String cp=Files.readString(ROOT.resolve(".local/iam-o1c-gateway-status/engine-classpath.txt")).strip();
            for(String e:cp.split(";")){String rel=ROOT.relativize(Path.of(e).toRealPath()).toString().replace('\\','/');assertThat(rel.startsWith(".tools/m2/")||rel.startsWith("engine-runtime/target/")||rel.startsWith("admin-common/target/")).as(rel).isTrue();}
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-cp",cp,"cn.agentplatform.common.authority.GatewayRunStatusEngineFixtureMain").directory(ROOT.toFile()).redirectErrorStream(true).start();stdin=process.outputWriter(StandardCharsets.UTF_8);
            drain=Thread.ofPlatform().daemon(true).start(()->{try(var r=process.inputReader(StandardCharsets.UTF_8)){for(String l;(l=r.readLine())!=null;){lines.add(l);synchronized(output){output.append(l).append('\n');}}}catch(IOException e){lines.add("CHILD_IO_FAILURE");}});
        }
        void send(Object value)throws Exception{stdin.write(Json.write(value));stdin.newLine();stdin.flush();}
        JsonNode command(Object v)throws Exception{send(v);return await("REPLY",12);}
        JsonNode await(String kind,int seconds)throws Exception{String key="O1C_ENGINE_"+kind+"=";long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){String l=lines.poll(100,TimeUnit.MILLISECONDS);if(l!=null&&l.startsWith(key))return Json.read(l.substring(key.length()));if(!process.isAlive())break;}throw new AssertionError("MISSING_"+kind+"\n"+output);}
        public void close()throws Exception{try{if(process.isAlive()){send(Map.of("action","STOP"));stdin.close();if(!process.waitFor(15,TimeUnit.SECONDS))throw new AssertionError("OWN_CHILD_STOP_TIMEOUT");}assertThat(process.exitValue()).isZero();}finally{if(process.isAlive()){process.destroy();if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}}drain.join(2000);Files.writeString(ROOT.resolve(".local/iam-o1c-gateway-status/engine-"+process.pid()+".log"),output);System.out.println("O1C_CHILD_EXIT="+Json.write(Map.of("pid",process.pid(),"alive",process.isAlive(),"exit",process.exitValue())));}}
    }
}
