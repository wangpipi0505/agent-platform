package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.ModelInputMaterialController;
import cn.agentplatform.engine.application.ModelInputMaterials;
import cn.agentplatform.engine.domain.ModelInputManifest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Actual controller/security/catalog, owned PG and normal TLS trust/hostname validation. */
class ModelInputMaterialHttpTest {
 static ModelInputMaterialFixture f;static ServletWebServerApplicationContext context;static HttpClient http;static int port;
 static Path tls;static final List<Integer> statuses=new java.util.concurrent.CopyOnWriteArrayList<>();
 @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity @Import(PlatformErrors.class)
 static class Receiver {
  @Bean DataSource dataSource(){return f.ds;}
  @Bean ModelInputMaterials materials(){return f.materials;}
  @Bean ModelInputMaterialController controller(ModelInputMaterials m){return new ModelInputMaterialController(m);}
  @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(f.iam.config("engine"),f.iam.clock(),true);}
  @Bean ServiceAuthorization authorization(){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),f.engine);}
  @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
  @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
  @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),mappings);}
 }
 @BeforeAll static void start()throws Exception {
  f=new ModelInputMaterialFixture();tls=Path.of(System.getProperty("model.input.tls"));
  var trust=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(tls)){trust.load(in,"fixture-only".toCharArray());}var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
  http=HttpClient.newBuilder().sslContext(ssl).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
  var app=new SpringApplication(Receiver.class);app.setAdditionalProfiles("test");app.setDefaultProperties(Map.ofEntries(Map.entry("spring.config.location","optional:classpath:/model-input-no-config/"),Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("server.ssl.enabled",true),Map.entry("server.ssl.key-store",tls.toUri().toString()),Map.entry("server.ssl.key-store-password","fixture-only"),Map.entry("server.ssl.key-store-type","PKCS12"),Map.entry("server.ssl.key-alias","engine"),Map.entry("spring.main.banner-mode","off"),Map.entry("spring.flyway.enabled",false),Map.entry("springdoc.api-docs.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR")));
  context=(ServletWebServerApplicationContext)app.run();port=context.getWebServer().getPort();assertThat(port).isNotIn(18080,18081,18082,18083,18084,18085,15173,15174,15175);
 }
 @AfterAll static void close()throws Exception {if(context!=null)context.close();if(http!=null)http.close();if(f!=null)f.close();String evidence=System.getProperty("model.input.evidence");if(evidence!=null)Files.writeString(Path.of(evidence,"http.json"),Json.write(Map.of("port",port,"tls","normal trust + SAN validation","statuses",statuses,"resourcesClosed",true,"jvmPid",ProcessHandle.current().pid())));}
 @AfterEach void reset(){f.ds.beforeSql=f.sql::add;f.ds.afterReadCommit=()->{};f.iam.unavailable(false);f.iam.decisionMutation(c->{});f.iam.ticketMutation(c->{});}
 static ServiceTicket ticket(long run){return f.model.resume(f.runs.load(run).delegation(),new ServiceCall("engine","material.scope.read",ModelInputMaterialFixture.ROOT));}
 static String path(ModelInputManifest m){return "/internal/material-scopes/model-input/"+m.material().id()+"?version="+m.requestSha256();}
 static HttpResponse<String> get(String host,String path,String token)throws Exception {var request=HttpRequest.newBuilder(URI.create("https://"+host+":"+port+path)).timeout(Duration.ofSeconds(5)).GET();if(token!=null)request.header("Authorization","Bearer "+token);var result=http.send(request.build(),HttpResponse.BodyHandlers.ofString());statuses.add(result.statusCode());return result;}
 static void noPayloadSql(){assertThat(f.sql).isNotEmpty().noneMatch(s->s.contains("request::text")||s.matches("(?s).*select .*\\bsnapshot::text.*")||s.contains("inputs::text")||s.contains("version_refs::text"));}
 @Test void actualSignedHttpsReadsReusableMetadataAndHeadersWithoutPayload()throws Exception {
  long run=f.create();var manifest=f.materials.prepareInitial(run);var ticket=ticket(run);f.sql.clear();var response=get("localhost",path(manifest),ticket.value());assertThat(response.statusCode()).isEqualTo(200);noPayloadSql();
  assertThat(response.headers().firstValue("X-Agent-Material-Ref")).contains(path(manifest));assertThat(response.headers().firstValue("X-Agent-Material-Sha256")).contains(manifest.scope().scopeSha256());assertThat(response.body()).doesNotContain("sensitive input","Hello Alice","grant-","service_ticket","source_request");
  var data=Json.read(response.body()).path("data");var unsigned=(ObjectNode)data.deepCopy();unsigned.remove("manifestSha256");assertThat(Digest.sha256(Json.canonical(unsigned))).isEqualTo(data.path("manifestSha256").asText());assertThat(Json.canonical(data)).isEqualTo(Json.canonical(manifest));assertThat(get("localhost",path(manifest),ticket(run).value()).statusCode()).isEqualTo(200);
  var catalog=new ServiceRoutePolicy().routes().stream().filter(r->r.template().equals("/internal/material-scopes/{kind}/{id}")).toList();assertThat(catalog).hasSize(1);assertThat(catalog.getFirst().caller()).isEqualTo("model");assertThat(catalog.getFirst().integrationAllowed()).isFalse();
 }
 @Test void unsignedPortalWrongCallerAndWrongSelectorsCannotRead()throws Exception {
  long run=f.create();var m=f.materials.prepareInitial(run);assertThat(get("localhost",path(m),null).statusCode()).isEqualTo(401);assertThat(get("localhost",path(m),f.iam.portal()).statusCode()).isIn(401,403);
  var gateway=f.gateway.exchange(f.iam.portal(),ModelInputMaterialFixture.MAIN);assertThat(get("localhost",path(m),gateway.value()).statusCode()).isEqualTo(403);
  for(String bad:List.of(path(m)+"&version="+m.requestSha256(),path(m)+"&extra=x",path(m).replace("model-input/","other/"),path(m).replace(m.requestSha256(),"ABC"),path(m).replace(m.material().id(),"m_bad")))assertThat(get("localhost",bad,ticket(run).value()).statusCode()).isEqualTo(400);
 }
 @Test void signatureIssuerAudienceTargetActionAndOwnerMutationsAreDenied()throws Exception {
  long run=f.create();var m=f.materials.prepareInitial(run);var good=ticket(run).value();String[] bits=good.split("\\.");String bad=bits[0]+"."+bits[1]+"."+(bits[2].charAt(0)=='A'?"B":"A")+bits[2].substring(1);assertThat(get("localhost",path(m),bad).statusCode()).isIn(401,403);
  for(String key:List.of("iss","aud","caller","target","action","sub","tenant_id")){
   var claims=Json.mapper().convertValue(Json.read(new String(Base64.getUrlDecoder().decode(bits[1]),java.nio.charset.StandardCharsets.UTF_8)),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});claims.put(key,key.equals("aud")?List.of("wrong"):"wrong");var token=f.iam.sign(claims,f.iam.authoritySigningKey(),"at+jwt");assertThat(get("localhost",path(m),token).statusCode()).as(key).isIn(401,403);
  }
 }
 @Test void currentNarrowingWrongRootMissingParentAndStoredScopeMismatchDenyAtTheirOwnBoundary()throws Exception {
  long run=f.create();var m=f.materials.prepareInitial(run);var t=ticket(run);
  f.iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")));});assertThat(get("localhost",path(m),t.value()).statusCode()).isEqualTo(403);f.iam.decisionMutation(c->{});
  f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");f.db.update("delete from engine.material_scope where run_id=? and kind='run-inputs'",run);f.sql.clear();assertThat(get("localhost",path(m),t.value()).statusCode()).isEqualTo(403);noPayloadSql();f.db.execute("alter table engine.material_scope enable trigger material_scope_immutable");
  long second=f.create();var other=f.materials.prepareInitial(second);var ot=ticket(second);f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");f.db.update("update engine.material_scope set payload_sha256=? where run_id=? and kind='model-input'",Digest.sha256("different"),second);f.sql.clear();var response=get("localhost",path(other),ot.value());assertThat(response.statusCode()).isEqualTo(403);assertThat(Json.read(response.body()).path("code").asText()).isEqualTo("MATERIAL_SCOPE_MISSING_OR_CHANGED");noPayloadSql();f.db.execute("alter table engine.material_scope enable trigger material_scope_immutable");
 }
 @Test void authorityOutageRemains503AndNoSuccessfulManifest()throws Exception {long run=f.create();var m=f.materials.prepareInitial(run);var ticket=ticket(run);f.iam.unavailable(true);f.sql.clear();assertThat(get("localhost",path(m),ticket.value()).statusCode()).isEqualTo(503);assertThat(f.sql).isEmpty();}
 @Test void hostnameAndUntrustedCertificateFailNormalTls()throws Exception {
  long run=f.create();var m=f.materials.prepareInitial(run);var token=ticket(run).value();assertThatThrownBy(()->get("127.0.0.1",path(m),token)).hasCauseInstanceOf(SSLHandshakeException.class);
  try(var untrusted=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){assertThatThrownBy(()->untrusted.send(HttpRequest.newBuilder(URI.create("https://localhost:"+port+path(m))).GET().build(),HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);}
 }
 @Test void legacyConstructionNeedsNoIamBeanAndDeniesBeforeProtectedReads(){
  var beans=new DefaultListableBeanFactory();var legacy=new ModelInputMaterials(f.ds,beans.getBeanProvider(HttpCurrentAuthorityClient.class));f.sql.clear();assertThatThrownBy(()->legacy.prepareInitial(1)).isInstanceOf(PlatformException.class);assertThatThrownBy(()->legacy.manifest("m_"+"a".repeat(32),"a".repeat(64),null)).isInstanceOf(PlatformException.class);assertThat(f.sql).isEmpty();assertThat(new ModelInputMaterialController(legacy)).isNotNull();
 }
 @Test void unchangedDefaultIamDeploymentGuardStillRejectsBeforeListener(){
  var props=new LinkedHashMap<String,Object>();props.put("agent.security.mode","iam");props.put("agent.security.service-id","engine");props.put("server.address","127.0.0.1");props.put("agent.security.authority.client-id","engine");props.put("agent.security.authority.issuer","https://authority.invalid");props.put("agent.security.authority.jwks-uri","https://authority.invalid/jwks");props.put("agent.security.authority.token-uri","https://authority.invalid/token");props.put("agent.security.authority.decision-uri","https://authority.invalid/decision");props.put("agent.security.authority.client-secret-ref","env:AGENT_SECRET_IAM_ENGINE_CLIENT");props.put("agent.security.authority.allowed-algorithm","RS256");props.put("agent.security.authority.audience","urn:agent-platform:service:engine");
  var ctx=new AnnotationConfigApplicationContext();ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("closed-iam",props));ctx.register(SecurityConfiguration.class);assertThatThrownBy(ctx::refresh).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");ctx.close();
 }
}
