package cn.agentplatform.app.application;

import cn.agentplatform.app.api.*;
import cn.agentplatform.app.domain.*;
import cn.agentplatform.app.infra.PolicyResolverSecurity;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import static org.assertj.core.api.Assertions.*;

class IntegrationPolicyResolverHttpTest {
    static LoopbackAuthority fixture;
    static final String TENANT="d2-policy-tests", APP="policy-app", BIZ="partner";
    static final AtomicInteger queries=new AtomicInteger();
    static boolean includeProof=true;
    static DataSource ds;
    static JdbcTemplate db;
    static long id;
    static ServletWebServerApplicationContext app;
    static final String LONG_USER="u".repeat(90);
    static Map<String,Object> policy(String user){return Map.of("claimLimits",Map.of("principalIds",List.of(user,LONG_USER),"roles",List.of("reader","ADMIN","EDITOR","SERVICE"),"attributes",Map.of("region",List.of("A"),"dataLabels",List.of("restricted"))),"allowedOrigins",List.of("https://embed.example.invalid"));}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity @EnableTransactionManagement
    @Import({PolicyResolverSecurity.class,IntegrationPolicyResolverController.class,IntegrationController.class,IntegrationService.class,IntegrationPolicyResolver.class,PlatformErrors.class})
    static class Receiver {
        @Bean DataSource dataSource(){return ds;}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(ds){@Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){if(sql.contains("policy_revision")&&sql.contains("credentialId"))queries.incrementAndGet();return super.query(sql,mapper,args);}};}
        @Bean DataSourceTransactionManager transactions(){return new DataSourceTransactionManager(ds);}
        @Bean AppService apps(JdbcTemplate jdbc,DataSourceTransactionManager tx){return new AppService(jdbc,tx,"http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:1");}
        @Bean JwtDecoder decoder(){return new TrustedJwtDecoder(fixture.config("app"),fixture.clock(),true);}
        @Bean(destroyMethod="close") HttpCurrentAuthorityClient current(){return fixture.client("app");}
        @Bean ServiceAuthorization authorization(CurrentAuthorityClient current,PolicyRequestVerifier verifier){return new ServiceAuthorization("app",new ServiceRoutePolicy(),current,null,includeProof?PolicyResolverSecurity.handler():null,includeProof?verifier:null);}
        @Bean @Order(2) SecurityFilterChain service(HttpSecurity http,JwtDecoder decoder,ServiceAuthorization receiver)throws Exception{return SecurityConfiguration.serviceSecurity(http,decoder,receiver);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization receiver){return receiver.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings){return new RouteCoverageVerifier("app",new ServiceRoutePolicy(),mappings,Set.of(PolicyResolverSecurity.ingress()));}
    }
    @BeforeAll static void start()throws Exception{
        new IntegrationPolicyDatabaseTest().dedicatedDatabaseAndRevisionMigrationAreRealPostgres();
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_policy_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));db=new JdbcTemplate(ds);
        fixture=new LoopbackAuthority();fixture.portalActor(new Actor(TENANT,"admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));
        var application=new SpringApplication(Receiver.class);application.setAdditionalProfiles("test");
        application.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/d2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.policy-resolver.enabled",true),Map.entry("agent.policy-resolver.issuer",fixture.policyIssuer()+"/policy-issuer"),Map.entry("agent.policy-resolver.public-jwks",new JWKSet(fixture.policySigningKey().toPublicJWK()).toString())));
        app=(ServletWebServerApplicationContext)application.run();
    }
    @AfterAll static void close(){if(app!=null)app.close();if(fixture!=null)fixture.close();}
    @BeforeEach void seed(){
        db.update("delete from app.integration_credential where tenant_id=?",TENANT);
        db.update("insert into app.application(tenant_id,code,name,type,status,current_version_no,created_by) values(?,?,'Policy test','CHAT','PUBLISHED',1,'admin-d2') on conflict(tenant_id,code) do nothing",TENANT,APP);
        db.update("insert into app.app_draft(tenant_id,app_id,app_code) select tenant_id,id,code from app.application where tenant_id=? and code=? on conflict do nothing",TENANT,APP);
        db.update("insert into app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id) select a.tenant_id,a.code,1,1,?,'{}','finite','admin-d2',a.id,d.id from app.application a join app.app_draft d on d.tenant_id=a.tenant_id and d.app_code=a.code where a.tenant_id=? and a.code=? on conflict do nothing",Digest.sha256("published-d2"),TENANT,APP);
        var p=policy("alice");id=db.queryForObject("insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by) values(?,?,?,'d2-only-reference',?,'ACTIVE',?::jsonb,?::jsonb,'admin-d2') returning id",Long.class,TENANT,APP,BIZ,Digest.sha256("not-a-key"),Json.write(p.get("claimLimits")),Json.write(p.get("allowedOrigins")));queries.set(0);
    }
    static Map<String,Object> requestBody(String user){var r=new LinkedHashMap<String,Object>();r.put("schemaVersion",1);r.put("credentialId",id);r.put("expectedTenantId",TENANT);r.put("expectedAppCode",APP);r.put("expectedBizSystemCode",BIZ);r.put("userId",user);r.put("expectedPrincipalId",IntegrationClaims.principal(BIZ,user));r.put("expectedPolicyVersion",null);r.put("nonce",UUID.randomUUID().toString());return r;}
    static String assertion(String body){var c=fixture.base("policy-query","app-policy-resolver",30);long now=Instant.now().getEpochSecond();c.put("iat",now);c.put("nbf",now);c.put("exp",now+30);c.put("iss",fixture.policyIssuer()+"/policy-issuer");c.put("sub","iam-policy-resolver");c.put("caller","iam-policy-resolver");c.put("target","app-policy-resolver");c.put("action","integration.policy.resolve");c.put("nonce",Json.read(body).path("nonce").asText());c.put("bodySha256",Digest.sha256(body));return fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt");}
    static HttpResponse<String> http(String method,String path,String token,String body)throws Exception{
        try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    static HttpResponse<String> resolve(Map<String,Object> r)throws Exception{String body=Json.write(r);return http("POST",PolicyRequestVerifier.PATH,assertion(body),body);}
    static ResourceBinding credentialResource(){var row=db.queryForMap("select id,policy_revision,secret_ref,claim_limits,allowed_origins from app.integration_credential where id=?",id);var c=Json.tree(Map.of("secretRef",row.get("secret_ref"),"claimLimits",Json.read(row.get("claim_limits").toString()),"allowedOrigins",Json.read(row.get("allowed_origins").toString())));return new ResourceBinding("integration-credential",Long.toString(id),Digest.sha256(Json.canonical(Map.of("credentialId",id,"policyVersion",id+":"+row.get("policy_revision"),"policyDigest",IntegrationClaims.digest(c)))));}
    static HttpResponse<String> admin(String method,String path,String template,ResourceBinding resource,String body)throws Exception{try(var gateway=fixture.client("gateway")){var route=new ServiceRoutePolicy().require("gateway","app",method,template);var call=new ServiceCall("app",route.action(),resource);return http(method,path,gateway.exchange(fixture.portal(),call).value(),body);}}
    static HttpResponse<String> update(Map<String,Object> p)throws Exception{return admin("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,"/admin/apps/{code}/integration/credentials/{biz}",credentialResource(),Json.write(p));}
    @Test void policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak()throws Exception{
        var r=resolve(requestBody("alice"));assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var n=Json.read(r.body());assertThat(n.path("principalId").asText()).isEqualTo("partner:alice");assertThat(n.path("policyVersion").asText()).isEqualTo(id+":1");assertThat(n.path("roleCeiling").toString()).isEqualTo("[\"reader\"]");assertThat(n.path("attributeCeiling").has("dataLabels")).isFalse();assertThat(n.path("dataLabels").toString()).isEqualTo("[\"restricted\"]");assertThat(n.size()).isEqualTo(17);assertThat(r.body()).doesNotContain("secretRef","d2-only-reference","principalIds",LONG_USER);
        var updated=update(policy("alice"));assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects()throws Exception{
        var p=update(policy("bob"));assertThat(p.statusCode()).as(p.body()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(2);
        var old=requestBody("alice");old.put("expectedPolicyVersion",id+":1");assertThat(resolve(old).statusCode()).isEqualTo(409);
        var gone=resolve(requestBody("alice"));assertThat(Json.read(gone.body()).path("subjectAllowed").asBoolean()).isFalse();
        var revoked=admin("POST","/admin/apps/"+APP+"/integration/credentials/"+BIZ+"/revoke","/admin/apps/{code}/integration/credentials/{biz}/revoke",credentialResource(),"");assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(3);
    }
}



