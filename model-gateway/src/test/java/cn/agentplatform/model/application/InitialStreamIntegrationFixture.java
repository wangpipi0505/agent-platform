package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.api.ModelController;
import cn.agentplatform.model.infra.ModelStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Explicit isolated receiver; never scans ModelApplication or imports production common config. */
public final class InitialStreamIntegrationFixture implements AutoCloseable {
    static InitialStreamIntegrationFixture current;
    public final Path dir = Path.of(System.getProperty("initial.fixture"));
    public final com.zaxxer.hikari.HikariDataSource pool;
    public final JdbcTemplate db;
    public final SendFaultDataSource dataSource;
    final AuthorityProperties config;
    final Clock clock;
    final HttpCurrentAuthorityClient authority;
    final InitialModelInputVerifier verifier;
    final LettuceConnectionFactory redisFactory;
    final ModelQuota quota;
    final ModelCalls calls;
    final ModelStore store;
    final ServletWebServerApplicationContext context;
    public final JsonNode ready;
    public final byte[] request;
    public final String ticket;
    public final AtomicInteger resolutions = new AtomicInteger();
    public final AtomicReference<String> receiverThread = new AtomicReference<>();
    public final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    Runnable afterContextClose = () -> {}, afterHttpClose = () -> {};
    public InitialStreamIntegrationFixture() throws Exception { this(f -> {}); }
    InitialStreamIntegrationFixture(java.util.function.Consumer<InitialStreamIntegrationFixture> receiverSetup) throws Exception {
        try {
        ready = Json.read(Files.readString(dir.resolve("engine/ready.json")));
        config = Json.read(Files.readString(dir.resolve("engine/model-config.json")), AuthorityProperties.class);
        clock = Clock.fixed(Instant.parse(ready.path("clockInstant").asText()), ZoneOffset.UTC);
        var a=config.authority();
        var credentialConfig=new AuthorityProperties("iam","model",null,new AuthorityProperties.Authority(
                "https://fixture.invalid","https://fixture.invalid/jwks","https://fixture.invalid/token","https://fixture.invalid/decision",
                a.clientId(),a.clientSecretRef(),a.allowedAlgorithm(),a.audience(),a.maxTicketSeconds(),a.maxDecisionSeconds(),a.connectTimeoutMs(),a.readTimeoutMs()),null,null,null,false,null);
        authority = new HttpCurrentAuthorityClient(config, clock, new WorkloadCredentialResolver(credentialConfig), true);
        verifier = new InitialModelInputVerifier(authority, URI.create(ready.path("engineOrigin").asText()), clock);
        pool = FrozenModelBindingConsumerFixture.pool(dir.resolve("model-db.json"));
        dataSource = new SendFaultDataSource(pool);
        db = new JdbcTemplate(dataSource);
        store = new ModelStore(db) {
            @Override public Map<String,Object> resolve(JsonNode request, Actor actor) {
                resolutions.incrementAndGet(); return super.resolve(request, actor);
            }
        };
        redisFactory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("initial.redis.port")));
        redisFactory.afterPropertiesSet(); redisFactory.start();
        quota = new ModelQuota(new StringRedisTemplate(redisFactory));
        calls = new ModelCalls(store, quota, verifier, new FrozenModelBindingVerifier(dataSource), dataSource);
        request = Files.readAllBytes(dir.resolve("engine/request.json"));
        ticket = Files.readString(dir.resolve("engine/first-ticket.txt"));
        current = this;
        var app = new SpringApplication(Receiver.class);
        app.setAdditionalProfiles("test");
        app.setDefaultProperties(Map.ofEntries(
                Map.entry("spring.config.location", "optional:classpath:/initial-no-config/"),
                Map.entry("server.address", "127.0.0.1"), Map.entry("server.port", 0),
                Map.entry("spring.flyway.enabled", false), Map.entry("spring.main.banner-mode", "off"),
                Map.entry("springdoc.api-docs.enabled", false), Map.entry("management.endpoints.web.exposure.exclude", "*"),
                Map.entry("management.endpoints.web.discovery.enabled", false), Map.entry("logging.level.root", "ERROR")));
        receiverSetup.accept(this);
        context = (ServletWebServerApplicationContext) app.run();
        } catch (Exception | Error failure) { closeOwned(failure); throw failure; }
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class Receiver {
        @Bean(destroyMethod="") DataSource dataSource() { return current.dataSource; }
        @Bean ModelController controller() { return new ModelController(current.store, current.calls); }
        @Bean PlatformErrors errors() { return new PlatformErrors(); }
        @Bean(destroyMethod="close") JwtDecoder decoder() { return new TrustedJwtDecoder(current.config, current.clock, true); }
        @Bean ServiceAuthorization authorization() { return new ServiceAuthorization("model", new ServiceRoutePolicy(), current.authority); }
        @Bean SecurityFilterChain security(HttpSecurity h, JwtDecoder d, ServiceAuthorization a) throws Exception {
            return SecurityConfiguration.serviceSecurity(h, d, a);
        }
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a) { return a.mappedInterceptor(); }
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings) {
            return new RouteCoverageVerifier("model", new ServiceRoutePolicy(), mappings);
        }
        @Bean org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> uploadDeadline() {
            return factory -> factory.addConnectorCustomizers(connector -> {
                var protocol=(org.apache.coyote.http11.AbstractHttp11Protocol<?>)connector.getProtocolHandler();
                protocol.setConnectionTimeout(1000);protocol.setConnectionUploadTimeout(1000);protocol.setDisableUploadTimeout(false);
            });
        }
        @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> threadObservation() {
            return new org.springframework.boot.web.servlet.FilterRegistrationBean<>((request,response,chain)->{
                current.receiverThread.set(Thread.currentThread().getName());
                try { chain.doFilter(request,response); }
                finally { Files.writeString(current.dir.resolve("receiver-completed.json"),Json.write(Map.of(
                        "at",Instant.now().toString(),"committed",response.isCommitted(),
                        "status",((jakarta.servlet.http.HttpServletResponse)response).getStatus()))); }
            });
        }
    }

    public URI url(String query) { return URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/internal/model/chat/stream" + query); }
    public String selected() { return "?profile=initial-chat-main-v1&materialRef=" + URLEncoder.encode(ready.path("materialRef").asText(), StandardCharsets.UTF_8); }
    public HttpResponse<String> post(String query, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(url(query)).timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + ticket).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    public void control(String mode) throws Exception { Files.writeString(dir.resolve("provider-control.txt"), mode); }
    public JsonNode authorityControl(String action) throws Exception {
        String origin=Json.read(Files.readString(dir.resolve("engine/control-ready.json"))).path("origin").asText();
        var response=http.send(HttpRequest.newBuilder(URI.create(origin+"/control?"+action)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("CONTROL_HTTP_FAILED");return Json.read(response.body());
    }
    public void duringReservation(String action) { dataSource.onReservation=()->{try{authorityControl(action);}catch(Exception e){throw new IllegalStateException(e);}}; }
    public int providerCalls() throws Exception { return Files.exists(dir.resolve("provider-events.jsonl")) ? Files.readAllLines(dir.resolve("provider-events.jsonl")).size() : 0; }
    public int tokenizerCalls() throws Exception { return Files.exists(dir.resolve("tokenizer-events.jsonl")) ? Files.readAllLines(dir.resolve("tokenizer-events.jsonl")).size() : 0; }
    public long logs() { return db.queryForObject("select count(*) from model.call_log where invocation_ref=?", Long.class, Json.read(new String(request,StandardCharsets.UTF_8)).path("invocationRef").asText()); }
    public JsonNode fact() { return Json.read(db.queryForObject("select metering_snapshot::text from model.call_log where invocation_ref=?", String.class, Json.read(new String(request,StandardCharsets.UTF_8)).path("invocationRef").asText())); }
    public void reconcile() { calls.reconcilePendingUsage(); }
    public String invocation() { return Json.read(new String(request,StandardCharsets.UTF_8)).path("invocationRef").asText(); }
    @Override public void close() {
        Throwable failure=closeOwned(null);
        if(failure instanceof RuntimeException runtime)throw runtime;
        if(failure instanceof Error error)throw error;
        if(failure!=null)throw new IllegalStateException(failure);
    }
    private Throwable closeOwned(Throwable primary) {
        // Fixture owns manually assembled dependencies; Spring borrows them. Always attempt every stage.
        try {
            for(Runnable close:List.<Runnable>of(
                    ()->{if(context!=null){context.close();afterContextClose.run();}},
                    ()->{if(http!=null){http.close();afterHttpClose.run();}}, ()->{if(verifier!=null)verifier.close();},
                    ()->{if(authority!=null)authority.close();}, ()->{if(redisFactory!=null)redisFactory.destroy();},
                    ()->{if(pool!=null)pool.close();})) {
                try {close.run();} catch(Throwable failure){if(primary==null)primary=failure;else if(primary!=failure)primary.addSuppressed(failure);}
            }
        } finally {if(current==this)current=null;}
        return primary;
    }

    /** Faults execute at a real PG connection boundary; no row, proof or usage is manufactured. */
    public static final class SendFaultDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
        public volatile String fault="none";
        public volatile Runnable onReservation=()->{}, onFinished=()->{};
        public final AtomicReference<String> sendingThread=new AtomicReference<>();
        public final AtomicInteger commitsThenThrown=new AtomicInteger(),realSqlErrors=new AtomicInteger();
        SendFaultDataSource(DataSource target) { super(target); }
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection=super.getConnection(); boolean[] sending={false};
            return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{java.sql.Connection.class},(proxy,method,args)->{
                        try {
                            if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("insert into model.call_log")) {
                                sendingThread.set(Thread.currentThread().getName());
                                sending[0]=true;
                                if(fault.equals("rollback")) {
                                    realSqlErrors.incrementAndGet();
                                    try(var statement=connection.createStatement()){statement.execute("select 1 / 0");}
                                }
                            }
                            Object result=method.invoke(connection,args);
                            if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("insert into model.quota_reservation")) {
                                var statement=(java.sql.PreparedStatement)result;
                                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{
                                    try {Object value=m.invoke(statement,a);if(m.getName().equals("executeUpdate"))onReservation.run();return value;}
                                    catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                                });
                            }
                            if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("update model.call_log set status='SUCCEEDED'")) {
                                var statement=(java.sql.PreparedStatement)result;
                                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{
                                    try {Object value=m.invoke(statement,a);if(m.getName().equals("executeUpdate"))onFinished.run();return value;}
                                    catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                                });
                            }
                            if(method.getName().equals("commit")&&sending[0]&&fault.equals("ack-loss")) {
                                commitsThenThrown.incrementAndGet();
                                throw new java.sql.SQLTransientConnectionException("CONTROLLED_COMMIT_ACK_LOST_AFTER_REAL_PG_COMMIT");
                            }
                            return result;
                        } catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                    });
        }
    }

    /** Separate provider/seed JVM with its own immutable binding and no production controller. */
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(System.getProperty("initial.fixture"));
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(InitialModelInputConsumerFixture.serverSsl()));
        var workers=Executors.newCachedThreadPool(); server.setExecutor(workers);
        try (var pool=FrozenModelBindingConsumerFixture.pool(dir.resolve("model-db.json"))) {
            var db=new JdbcTemplate(pool); var store=new ModelStore(db);
            var actor=new Actor("tenant-a","alice",Set.of("ADMIN"),Map.of());
            String base="https://localhost:"+server.getAddress().getPort();
            long provider=((Number)store.createProvider(Json.tree(Map.of("code","initial_"+UUID.randomUUID().toString().replace("-",""),
                    "name","controlled initial fixture","baseUrl",base,"trustLabels",List.of("LOCAL","initial","restricted","parent","config"))),actor).get("id")).longValue();
            long entry=((Number)store.createEntry(Json.tree(Map.of("providerId",provider,"modelName","fixture-model","displayName","Controlled",
                    "contextWindow",8192,"maxOutputTokens",4096,"capabilities",List.of("chat","json_mode"),"tokenizerUrl",base+"/tokenize")),actor).get("id")).longValue();
            db.update("update model.provider set status='ENABLED',secret_ref=? where id=?",SecretVault.put("controlled-live-tenant-credential",actor),provider);
            db.update("update model.entry set status='ENABLED' where id=?",entry);
            Files.writeString(dir.resolve("binding.json"),Json.write(store.freeze(entry,actor)));
            server.createContext("/tokenize",x->{
                try {
                    byte[] body=x.getRequestBody().readNBytes(2097153); var n=Json.read(new String(body,StandardCharsets.UTF_8));
                    String mode=Files.exists(dir.resolve("provider-control.txt"))?Files.readString(dir.resolve("provider-control.txt")):"normal";
                    Files.writeString(dir.resolve("tokenizer-events.jsonl"),Json.write(Map.of("requestSha",n.path("request_sha256").asText(),"fullRequestSha",Digest.sha256(Json.canonical(n.path("request"))),"model",n.path("model").asText(),"authorizationMatches",("Bearer controlled-live-tenant-credential").equals(x.getRequestHeaders().getFirst("Authorization"))))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    if(Set.of("revoke","outage","narrow").contains(mode)) {
                        String origin=Json.read(Files.readString(dir.resolve("engine/control-ready.json"))).path("origin").asText();
                        try(var http=HttpClient.newHttpClient()){http.send(HttpRequest.newBuilder(URI.create(origin+"/control?"+mode)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                    }
                    Object count=Integer.valueOf(64); if(mode.equals("fractional"))count=Double.valueOf(64.5);
                    if(mode.equals("zero"))count=0;if(mode.equals("overflow"))count=Long.MAX_VALUE;if(mode.equals("context-overflow"))count=8000;
                    var response=new LinkedHashMap<String,Object>();response.put("count",count);
                    if(!mode.equals("missing-ack"))response.put("request_sha256",mode.equals("wrong-ack")?"0".repeat(64):n.path("request_sha256").asText());
                    byte[] bytes=(mode.equals("oversized")?"x".repeat(65537):Json.write(response)).getBytes(StandardCharsets.UTF_8);
                    if(mode.equals("redirect")) {x.getResponseHeaders().set("Location",base+"/v1/chat/completions");x.sendResponseHeaders(302,-1);return;}
                    if(mode.equals("drip")) {
                        x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,0);
                        Files.writeString(dir.resolve("tokenizer-drip-started"),Instant.now().toString());
                        try { for(int i=0;i<30;i++){x.getResponseBody().write(' ');x.getResponseBody().flush();Thread.sleep(500);} }
                        catch(java.io.IOException cancelled){Files.writeString(dir.resolve("tokenizer-peer-cancelled"),Instant.now().toString());}
                        catch(InterruptedException stopped){Thread.currentThread().interrupt();}
                        return;
                    }
                    x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
                } finally {x.close();}
            });
            server.createContext("/v1/chat/completions",x->{
                try {
                    byte[] body=x.getRequestBody().readNBytes(2097153);Files.write(dir.resolve("provider-wire.bin"),body);
                    var facts=db.queryForList("select l.id,l.status,q.status as reservation_status from model.call_log l left join model.quota_reservation q on q.tenant_id=l.tenant_id and q.reservation_id=l.quota_receipt->>'id' where l.model_entry_id=? order by l.id",entry);
                    Files.writeString(dir.resolve("provider-events.jsonl"),Json.write(Map.of("rawWireSha",Digest.sha256(new String(body,StandardCharsets.UTF_8)),"factsBeforeResponse",facts,"authorizationMatches",("Bearer controlled-live-tenant-credential").equals(x.getRequestHeaders().getFirst("Authorization"))))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    String mode=Files.exists(dir.resolve("provider-control.txt"))?Files.readString(dir.resolve("provider-control.txt")):"normal";
                    if(mode.equals("provider-error")){x.sendResponseHeaders(503,-1);return;}
                    x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,0);
                    var out=x.getResponseBody();
                    out.write(("data: {\"id\":\"controlled\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"受控响应\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
                    Files.writeString(dir.resolve("provider-first-flushed"),Instant.now().toString());
                    if(mode.equals("provider-eof"))return;
                    if(Files.exists(dir.resolve("hold-final"))) {
                        // Pinned Spring AI 1.0.0 buffers two deltas (one-element lookahead), even without usage.
                        // This second delta is NOT a finish/usage/DONE event; final remains client-gated.
                        out.write(("data: {\"id\":\"controlled\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"后段\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
                        Files.writeString(dir.resolve("provider-second-nonfinal-flushed"),Instant.now().toString());
                        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                        while(!Files.exists(dir.resolve("allow-final"))&&System.nanoTime()<until)Thread.sleep(10);
                        if(!Files.exists(dir.resolve("allow-final"))) {
                            Files.writeString(dir.resolve("provider-gate-timeout"),Instant.now().toString());
                            throw new java.io.IOException("CLIENT_DID_NOT_RELEASE_FINAL");
                        }
                    }
                    Files.writeString(dir.resolve("provider-final-released"),Instant.now().toString());
                    String finish=mode.startsWith("finish-")?mode.substring(7):"stop";
                    out.write(("data: {\"id\":\"controlled\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""+finish+"\"}]}\n\n").getBytes(StandardCharsets.UTF_8));
                    if(mode.equals("controlled-usage"))out.write(("data: {\"id\":\"controlled\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":64,\"completion_tokens\":2,\"total_tokens\":66}}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();
                } catch(InterruptedException e){Thread.currentThread().interrupt();} finally{x.close();Files.writeString(dir.resolve("provider-request-closed"),Instant.now().toString());}
            });
            server.start();Files.writeString(dir.resolve("seed-ready.json"),Json.write(Map.of("pid",ProcessHandle.current().pid(),"providerOrigin",base,"entry",entry)));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(85);
            while(!Files.exists(dir.resolve("seed-stop"))&&System.nanoTime()<until)Thread.sleep(20);
        } finally {server.stop(0);workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
