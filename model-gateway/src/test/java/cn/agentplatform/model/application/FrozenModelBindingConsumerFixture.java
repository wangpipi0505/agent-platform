package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.infra.ModelStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Owned real PG plus the accepted signed consumer fixture. Config files are never authority. */
public final class FrozenModelBindingConsumerFixture implements AutoCloseable {
  public final InitialModelInputConsumerFixture consumer;
  public final HikariDataSource pool;
  public final ObservedDataSource ds;
  public final JdbcTemplate db;
  public final JsonNode binding;
  public final long entry,provider,id;
  public final String original;
  public Actor initialActorOverride;
  public final AtomicInteger providerHttp=new AtomicInteger(),tokenizerHttp=new AtomicInteger();
  public FrozenModelBindingConsumerFixture() throws Exception {
    consumer=new InitialModelInputConsumerFixture();
    consumer.server.createContext("/provider",x->{providerHttp.incrementAndGet();x.sendResponseHeaders(204,-1);x.close();});
    consumer.server.createContext("/tokenizer",x->{tokenizerHttp.incrementAndGet();x.sendResponseHeaders(204,-1);x.close();});
    pool=pool(Path.of(System.getProperty("binding.fixture")).resolve("model-db.json"));
    ds=new ObservedDataSource(pool); db=new JdbcTemplate(ds);
    var store=new ModelStore(db);
    var actor=new Actor("tenant-a","alice",Set.of("ADMIN"),Map.of());
    provider=((Number)store.createProvider(Json.tree(Map.of("code","binding_"+UUID.randomUUID().toString().replace("-",""),"name","owned fixture","baseUrl",consumer.origin()+"/provider","trustLabels",List.of("LOCAL","initial","restricted","parent","config"))),actor).get("id")).longValue();
    entry=((Number)store.createEntry(Json.tree(Map.of("providerId",provider,"modelName","fixture-model","displayName","Fixture","contextWindow",8192,"maxOutputTokens",4096,"capabilities",List.of("chat","tools","json_mode"),"tokenizerUrl",consumer.origin()+"/tokenizer")),actor).get("id")).longValue();
    db.update("update model.provider set status='ENABLED' where id=?",provider);
    db.update("update model.entry set status='ENABLED' where id=?",entry);
    binding=Json.tree(store.freeze(entry,actor)); id=binding.path("bindingId").longValue();
    original=db.queryForObject("select snapshot::text from model.binding_snapshot where id=?",String.class,id);
    // Poison added after the actual freeze; no credential ever enters the frozen snapshot.
    db.update("update model.provider set secret_ref='env:AGENT_SECRET_FROZEN_BINDING_POISON' where id=?",provider);
    selectBinding(); ds.reset();
  }
  public void selectBinding(){
    var request=(ObjectNode)consumer.request;
    request.put("modelEntryId",entry);
    request.set("modelBindingRef",Json.tree(Map.of("bindingId",id,"sha256",binding.path("sha256").asText())));
    consumer.rebuildManifest(); consumer.manifest.put("modelEntryId",entry); consumer.refreshResponse();
  }
  public void snapshot(JsonNode value,boolean rehash){
    String sha=rehash?Digest.sha256(Json.canonical(value)):binding.path("sha256").asText();
    db.update("update model.binding_snapshot set snapshot=?::jsonb,sha256=? where id=?",Json.write(value),sha,id);
    if(rehash){((ObjectNode)consumer.request.path("modelBindingRef")).put("sha256",sha); consumer.rebuildManifest();consumer.manifest.put("modelEntryId",entry);consumer.refreshResponse();}
    ds.reset();
  }
  public <T> T accepted(java.util.function.Function<InitialModelInputVerifier.VerifiedInitialModelInput,T> action)throws Exception{
    try(var verifier=new InitialModelInputVerifier(consumer.model,consumer.origin(),consumer.iam.clock())){
      java.util.function.Function<HttpCurrentAuthorityClient.RetainedAuthorization,T> work=p->{
        var input=verifier.verify(consumer.bytes(),consumer.ref(),p);
        boolean observable=pool.getHikariPoolMXBean().getActiveConnections()<2;long callsBefore=observable?rawCount("model.call_log"):-1,quotaBefore=observable?rawCount("model.quota_reservation"):-1;
        String outcome="completed";
        try{return action.apply(input);}
        catch(RuntimeException|Error e){outcome=e.getClass().getSimpleName();throw e;}
        finally{
          var row=new LinkedHashMap<String,Object>();
          row.put("case",Arrays.stream(Thread.currentThread().getStackTrace()).filter(e->e.getClassName().endsWith("FrozenModelBindingVerifierTest")).map(StackTraceElement::getMethodName).findFirst().orElse("fixture"));
          row.put("entry",entry);row.put("bindingId",id);row.put("outcome",outcome);row.put("sql",List.copyOf(ds.sql));row.put("transactions",List.copyOf(ds.transactions));row.put("bindingSelects",ds.reads.get());row.put("borrowed",ds.borrowed.get());
          row.put("providerHttp",providerHttp.get());row.put("tokenizerHttp",tokenizerHttp.get());row.put("callLogBefore",callsBefore);row.put("callLogAfter",observable?rawCount("model.call_log"):-1);row.put("quotaBefore",quotaBefore);row.put("quotaAfter",observable?rawCount("model.quota_reservation"):-1);
          row.put("authorityOperations",consumer.iam.observedOperations());
          try{Files.writeString(Path.of(System.getProperty("binding.fixture")).resolve("focused-observations.jsonl"),Json.write(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException e){throw new IllegalStateException(e);}
        }
      };
      if(initialActorOverride==null)return consumer.bound(work);
      var main=new ServiceCall("engine","proxy.engine.post.RunController.run",InitialModelInputConsumerFixture.ROOT);
      // The generic gateway fixture configuration cannot express an empty label ceiling. This
      // test-only issuer protocol authenticates the real exchange, then immediately re-enters the
      // actual strict receiver mapper; it never constructs a retained proof or changes a guard.
      String token=exchangeEmptyPortal(main);
      var inbound=consumer.engine.authorizeRetained(token,main);
      var child=consumer.engine.resume(inbound.current().delegation(),new ServiceCall("model","model.chat-stream",InitialModelInputConsumerFixture.ROOT));
      return InitialModelInputConsumerFixture.received(consumer.model,consumer.iam.config("model"),consumer.iam.clock(),child.value(),"model.chat-stream",work);
    }
  }
  private String exchangeEmptyPortal(ServiceCall main)throws Exception{
    var root=main.resource();String nonce=UUID.randomUUID().toString();
    var request=Json.tree(Map.of("operation","exchange","target",main.target(),"action",main.action(),"resource",Map.of("kind",root.kind(),"id",root.id(),"version_digest",root.versionDigest()),"subject_token",consumer.iam.portal(),"nonce",nonce));
    String basic="Basic "+Base64.getEncoder().encodeToString(("gateway:"+consumer.iam.testClientSecret("gateway")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try(var http=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()){
      var response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(consumer.iam.issuer()+"/token")).timeout(java.time.Duration.ofSeconds(2)).header("Authorization",basic).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(Json.write(request))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()!=200)throw new AssertionError("EMPTY_PORTAL_EXCHANGE_HTTP_FAILED");
      var jwt=com.nimbusds.jwt.SignedJWT.parse(Json.read(response.body()).path("assertion").asText());
      if(!jwt.getHeader().getAlgorithm().equals(com.nimbusds.jose.JWSAlgorithm.RS256)||!jwt.getHeader().getType().toString().equals("agent-authority-decision+jwt")||!jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(consumer.iam.authoritySigningKey().toPublicJWK())))throw new AssertionError("EMPTY_PORTAL_EXCHANGE_SIGNATURE_FAILED");
      var claims=Json.read(jwt.getPayload().toString());
      if(!jwt.getJWTClaimsSet().getAudience().equals(List.of("urn:agent-platform:service:gateway"))||!claims.path("target").asText().equals(main.target())||!claims.path("action").asText().equals(main.action())||!Json.canonical(claims.path("resource")).equals(Json.canonical(request.path("resource")))||!claims.path("token_use").asText().equals("current-decision"))throw new AssertionError("EMPTY_PORTAL_EXCHANGE_CALL_FAILED");
      if(!claims.path("nonce").asText().equals(nonce)||!claims.path("request_sha256").asText().equals(Digest.sha256(Json.canonical(request)))||!claims.path("client_id").asText().equals("gateway")||!claims.path("active").asBoolean()||!claims.path("current").asBoolean()||!claims.path("iss").asText().equals(consumer.iam.issuer())||claims.path("exp").asLong()<=consumer.iam.clock().instant().getEpochSecond())throw new AssertionError("EMPTY_PORTAL_EXCHANGE_BINDING_FAILED");
      return claims.path("ticket").asText();
    }
  }
  private long rawCount(String table){return new JdbcTemplate(pool).queryForObject("select count(*) from "+table,Long.class);}
  public void close(){
    int http=providerHttp.get()+tokenizerHttp.get(),borrowed=ds.borrowed.get();
    pool.close();consumer.close();
    if(http!=0)throw new AssertionError("UNEXPECTED_PROVIDER_OR_TOKENIZER_HTTP");
    if(borrowed!=0)throw new AssertionError("BORROWED_CONNECTION_LEAK");
  }
  public static HikariDataSource pool(Path config)throws Exception{
    var n=Json.read(Files.readString(config));var c=new HikariConfig();
    c.setJdbcUrl(n.path("url").asText());c.setUsername(n.path("username").asText());c.setPassword(n.path("password").asText());
    c.setConnectionTimeout(2000);c.setValidationTimeout(1000);c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setInitializationFailTimeout(-1);
    return new HikariDataSource(c);
  }
  /** Transparent observer only: real JDBC return values/errors are never replaced. */
  public static final class ObservedDataSource extends DelegatingDataSource {
    public final AtomicInteger borrowed=new AtomicInteger(),reads=new AtomicInteger(),acquisitions=new AtomicInteger();
    public final List<String> sql=new ArrayList<>();
    public Runnable beforeRead=()->{},afterRelease=()->{};
    public final List<Map<String,Object>> transactions=new ArrayList<>();
    public ObservedDataSource(DataSource source){super(source);}
    public void reset(){sql.clear();transactions.clear();reads.set(0);acquisitions.set(0);}
    @Override public Connection getConnection()throws SQLException{
      acquisitions.incrementAndGet();var c=super.getConnection();borrowed.incrementAndGet();boolean[] closed={false};
      return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
        try{
          if(m.getName().equals("prepareStatement")){String query=(String)a[0];sql.add(query);if(query.contains("from model.binding_snapshot")){reads.incrementAndGet();transactions.add(Map.of("readOnly",c.isReadOnly(),"isolation",c.getTransactionIsolation(),"autoCommit",c.getAutoCommit(),"borrowed",borrowed.get()));beforeRead.run();}}
          var result=m.invoke(c,a);
          if(m.getName().equals("close")&&!closed[0]){closed[0]=true;borrowed.decrementAndGet();afterRelease.run();}
          return result;
        }catch(InvocationTargetException e){throw e.getCause();}
      });
    }
  }
  /** Admin setup is a separate process epoch, never a verifier collaborator. */
  public static void main(String[] args)throws Exception{
    Path dir=Path.of(System.getProperty("binding.fixture"));Files.createDirectories(dir);
    if(args.length>0 && args[0].equals("serve")){
      try(var owned=new FrozenModelBindingConsumerFixture()){
        Files.writeString(dir.resolve("binding.json"),Json.write(owned.binding));
        Files.writeString(dir.resolve("model-seed-ready.json"),Json.write(Map.of("pid",ProcessHandle.current().pid(),"providerOrigin",owned.consumer.origin().toString())));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(12);
        while(!Files.exists(dir.resolve("model-stop"))&&System.nanoTime()<deadline)Thread.sleep(100);
        if(!Files.exists(dir.resolve("model-stop")))throw new AssertionError("MODEL_SEED_STOP_DEADLINE");
        Files.writeString(dir.resolve("model-seed-closed.json"),Json.write(Map.of("pid",ProcessHandle.current().pid(),"providerHttp",owned.providerHttp.get(),"tokenizerHttp",owned.tokenizerHttp.get(),"borrowed",owned.ds.borrowed.get(),"callLogRows",owned.db.queryForObject("select count(*) from model.call_log",Long.class),"quotaRows",owned.db.queryForObject("select count(*) from model.quota_reservation",Long.class))));
      }
      return;
    }
    String database="iam_model_frozen_binding_"+UUID.randomUUID().toString().replace("-","");
    String suffix=UUID.randomUUID().toString().replace("-","");
    String model="imfb_m_"+suffix,engine="imfb_e_"+suffix;
    String mp=UUID.randomUUID().toString(),ep=UUID.randomUUID().toString();
    var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_v12?connectTimeout=2&socketTimeout=3","agent_v12",System.getenv("AGENT_DB_PASSWORD")));
    if(admin.queryForObject("select count(*) from pg_database where datname=?",Integer.class,database)!=0)throw new AssertionError("DATABASE_MUST_BE_ABSENT");
    if(admin.queryForObject("select count(*) from pg_roles where rolname in (?,?)",Integer.class,model,engine)!=0)throw new AssertionError("ROLES_MUST_BE_ABSENT");
    Path registry=Path.of(System.getProperty("binding.registry"));
    long registered=Files.exists(registry)?Files.readAllLines(registry).size():0;
    if(registered>=12)throw new AssertionError("DATABASE_RESOURCE_BUDGET_EXHAUSTED");
    Files.writeString(registry,Json.write(Map.of("database",database,"modelRole",model,"engineRole",engine,"owner","iam-model-frozen-binding","absentBeforeCreate",true,"pid",ProcessHandle.current().pid()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    admin.execute("create role "+model+" login password '"+mp+"'");admin.execute("create role "+engine+" login password '"+ep+"'");admin.execute("create database "+database);
    String url="jdbc:postgresql://127.0.0.1:25432/"+database+"?connectTimeout=2&socketTimeout=3";
    var db=new JdbcTemplate(new DriverManagerDataSource(url,"agent_v12",System.getenv("AGENT_DB_PASSWORD")));
    db.execute("revoke connect on database "+database+" from public");db.execute("grant connect,create on database "+database+" to "+model+","+engine);
    db.execute("create schema model authorization "+model);db.execute("create schema engine authorization "+engine);
    db.execute("revoke all on schema public from public");db.execute("revoke all on schema model from public");db.execute("revoke all on schema engine from public");
    Files.writeString(dir.resolve("model-db.json"),Json.write(Map.of("url",url,"username",model,"password",mp,"database",database)));
    Files.writeString(dir.resolve("engine-db.json"),Json.write(Map.of("url",url,"username",engine,"password",ep,"database",database)));
    try(var pool=pool(dir.resolve("model-db.json"))){Flyway.configure().dataSource(pool).schemas("model").defaultSchema("model").locations("classpath:db/migration").load().migrate();}
    Files.writeString(dir.resolve("setup.json"),Json.write(Map.of("database",database,"modelRole",model,"engineRole",engine,"pid",ProcessHandle.current().pid(),"closed",true)));
  }
}
