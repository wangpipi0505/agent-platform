package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Genuine separate engine JVM/PG publication, signed metadata HTTPS, own model PG freeze. */
class FrozenModelBindingConsumerHttpTest {
  static Path dir;
  static JsonNode ready,descriptor;
  static HttpCurrentAuthorityClient authority;
  static AuthorityProperties config;
  static Clock clock;
  static InitialModelInputVerifier consumer;
  static com.zaxxer.hikari.HikariDataSource pool;
  static FrozenModelBindingConsumerFixture.ObservedDataSource ds;
  static final List<Map<String,Object>> observations=new ArrayList<>();
  @BeforeAll static void start()throws Exception{
    assertThat(System.getProperty("binding.fixture")).as("explicit genuine integration fixture").isNotBlank();
    dir=Path.of(System.getProperty("binding.fixture"));
    ready=Json.read(Files.readString(dir.resolve("ready.json")));
    descriptor=Json.read(Files.readString(dir.resolve("binding.json")));
    assertThat(ready.path("enginePid").longValue()).isNotEqualTo(ProcessHandle.current().pid());
    config=Json.read(Files.readString(dir.resolve("model-config.json")),AuthorityProperties.class);
    clock=Clock.fixed(Instant.parse(ready.path("clockInstant").asText()),ZoneOffset.UTC);
    assertThat(Digest.sha256(System.getenv("AGENT_SECRET_IAM_MODEL_CLIENT"))).isEqualTo(Digest.sha256(Files.readString(dir.resolve("model-secret.txt"))));
    var a=config.authority();
    var credentialConfig=new AuthorityProperties("iam","model",null,new AuthorityProperties.Authority("https://fixture.invalid","https://fixture.invalid/jwks","https://fixture.invalid/token","https://fixture.invalid/decision",a.clientId(),a.clientSecretRef(),a.allowedAlgorithm(),a.audience(),a.maxTicketSeconds(),a.maxDecisionSeconds(),a.connectTimeoutMs(),a.readTimeoutMs()),null,null,null,false,null);
    authority=new HttpCurrentAuthorityClient(config,clock,new WorkloadCredentialResolver(credentialConfig),true);
    consumer=new InitialModelInputVerifier(authority,URI.create(ready.path("engineOrigin").asText()),clock);
    pool=FrozenModelBindingConsumerFixture.pool(dir.resolve("model-db.json"));ds=new FrozenModelBindingConsumerFixture.ObservedDataSource(pool);
    try(var c=pool.getConnection();var s=c.createStatement()){
      assertThatThrownBy(()->s.executeQuery("select count(*) from engine.run")).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("permission denied for schema engine");
    }
  }
  @AfterAll static void close()throws Exception{
    if(consumer!=null)consumer.close();if(authority!=null)authority.close();
    if(pool!=null)pool.close();
    if(dir!=null)Files.writeString(dir.resolve("binding-model-results.json"),Json.write(Map.of("modelPid",ProcessHandle.current().pid(),"observations",observations,"borrowed",ds==null?-1:ds.borrowed.get(),"poolClosed",pool==null||pool.isClosed(),"resourcesClosed",true)));
  }
  static <T>T received(String ticket,java.util.function.Function<HttpCurrentAuthorityClient.RetainedAuthorization,T> action)throws Exception{
    return InitialModelInputConsumerFixture.received(authority,config,clock,Files.readString(dir.resolve(ticket+"-ticket.txt")),"model.chat-stream",action);
  }
  @Test void realSeparateJvmFrozenBindingJoinAndNumericDigest()throws Exception{
    var bytes=Files.readAllBytes(dir.resolve("request.json"));
    for(String ticket:List.of("first","repeat","newGrant"))received(ticket,p->{
      var accepted=consumer.verify(bytes,ready.path("materialRef").asText(),p);
      assertThat(accepted.manifest().modelEntryId()).isEqualTo(descriptor.path("modelEntryId").longValue());
      assertThat(accepted.manifest().modelBindingRef().sha256()).isEqualTo(descriptor.path("sha256").asText());
      assertThat(new String(accepted.acceptedCanonicalRequestUtf8(),StandardCharsets.UTF_8)).contains("0.00001");
      ds.reset();int before=ds.borrowed.get();
      var proof=new FrozenModelBindingVerifier(ds).verify(accepted);
      assertThat(proof.bindingId()).isEqualTo(descriptor.path("bindingId").longValue());
      assertThat(proof.bindingSha256()).isEqualTo(Digest.sha256(Json.canonical(proof.frozenMetadata())));
      assertThat(proof.requestSha256()).isEqualTo(ready.path("requestSha256").asText());
      assertThat(ds.reads).hasValue(1);assertThat(ds.borrowed).hasValue(0);assertThat(before).isZero();
      observations.add(Map.of("case",ticket,"bindingId",proof.bindingId(),"requestSha256",proof.requestSha256(),"bindingSha256",proof.bindingSha256(),"selects",ds.reads.get(),"sql",List.copyOf(ds.sql),"borrowedAfter",ds.borrowed.get()));
      assertThatThrownBy(accepted::requireVerified).isInstanceOf(PlatformException.class);
      return null;
    });
  }
  @Test void separateProducerRejectsWrongOwnerRootOriginBeforeBindingSql()throws Exception{
    for(String ticket:List.of("wrongOwner","wrongRoot","differentOrigin"))received(ticket,p->{
      ds.reset();assertThatThrownBy(()->consumer.verify(Files.readAllBytes(dir.resolve("request.json")),ready.path("materialRef").asText(),p)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(403));assertThat(ds.reads).hasValue(0);observations.add(Map.of("case",ticket,"bindingSelects",0,"status",403));return null;
    });
  }
}
