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
    static final AtomicInteger queries=new AtomicInteger(),auditErrors=new AtomicInteger();
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
        @Bean TestDatabaseErrors databaseErrors(){return new TestDatabaseErrors();}
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
    @org.springframework.web.bind.annotation.RestControllerAdvice static class TestDatabaseErrors {
        @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DataAccessException.class)
        org.springframework.http.ResponseEntity<Object> failed(){auditErrors.incrementAndGet();return org.springframework.http.ResponseEntity.status(500).body(Map.of("code","FINITE_DATABASE_FAILURE"));}
    }
    @BeforeAll static void start()throws Exception{
        new IntegrationPolicyDatabaseTest().dedicatedDatabaseAndRevisionMigrationAreRealPostgres();
        ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_policy_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));db=new JdbcTemplate(ds);
        fixture=new LoopbackAuthority();fixture.portalActor(new Actor(TENANT,"admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));
        app=startReceiver();fixture.externalPolicy(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+PolicyRequestVerifier.PATH));
    }
    static ServletWebServerApplicationContext startReceiver(){
        var application=new SpringApplication(Receiver.class);application.setAdditionalProfiles("test");
        application.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","0"),Map.entry("spring.config.location","optional:classpath:/d2-no-config/"),Map.entry("spring.main.banner-mode","off"),Map.entry("springdoc.api-docs.enabled",false),Map.entry("spring.flyway.enabled",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("logging.level.root","ERROR"),Map.entry("agent.policy-resolver.enabled",true),Map.entry("agent.policy-resolver.issuer",fixture.policyIssuer()+"/policy-issuer"),Map.entry("agent.policy-resolver.public-jwks",new JWKSet(fixture.policySigningKey().toPublicJWK()).toString())));
        return (ServletWebServerApplicationContext)application.run();
    }
    @AfterAll static void close(){if(app!=null)app.close();if(fixture!=null)fixture.close();}
    @BeforeEach void seed(){
        fixture.clearFiniteGrants();auditErrors.set(0);
        db.update("delete from app.integration_credential where tenant_id=?",TENANT);
        db.update("insert into app.application(tenant_id,code,name,type,status,current_version_no,created_by) values(?,?,'Policy test','CHAT','PUBLISHED',1,'admin-d2') on conflict(tenant_id,code) do nothing",TENANT,APP);
        db.update("insert into app.app_draft(tenant_id,app_id,app_code) select tenant_id,id,code from app.application where tenant_id=? and code=? on conflict do nothing",TENANT,APP);
        db.update("insert into app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id) select a.tenant_id,a.code,1,1,?,'{}','finite','admin-d2',a.id,d.id from app.application a join app.app_draft d on d.tenant_id=a.tenant_id and d.app_code=a.code where a.tenant_id=? and a.code=? on conflict do nothing",Digest.sha256("published-d2"),TENANT,APP);
        var p=policy("alice");id=db.queryForObject("insert into app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by) values(?,?,?,'d2-only-reference',?,'ACTIVE',?::jsonb,?::jsonb,'admin-d2') returning id",Long.class,TENANT,APP,BIZ,Digest.sha256("not-a-key"),Json.write(p.get("claimLimits")),Json.write(p.get("allowedOrigins")));queries.set(0);
    }
    static Map<String,Object> requestBody(String user){var r=new LinkedHashMap<String,Object>();r.put("schemaVersion",1);r.put("credentialId",id);r.put("expectedTenantId",TENANT);r.put("expectedAppCode",APP);r.put("expectedBizSystemCode",BIZ);r.put("userId",user);r.put("expectedPrincipalId",IntegrationClaims.principal(BIZ,user));r.put("expectedPolicyVersion",null);r.put("nonce",UUID.randomUUID().toString());return r;}
    static Map<String,Object> assertionClaims(String body){var c=fixture.base("policy-query","app-policy-resolver",30);long now=Instant.now().getEpochSecond();c.put("iat",now);c.put("nbf",now);c.put("exp",now+30);c.put("iss",fixture.policyIssuer()+"/policy-issuer");c.put("sub","iam-policy-resolver");c.put("caller","iam-policy-resolver");c.put("target","app-policy-resolver");c.put("action","integration.policy.resolve");c.put("nonce",Json.read(body).path("nonce").asText());c.put("bodySha256",Digest.sha256(body));return c;}
    static String assertion(String body){return fixture.sign(assertionClaims(body),fixture.policySigningKey(),"agent-policy-request+jwt");}
    static HttpResponse<String> http(String method,String path,String token,String body)throws Exception{
        try(var client=HttpClient.newHttpClient()){return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    static HttpResponse<String> resolve(Map<String,Object> r)throws Exception{String body=Json.write(r);return http("POST",PolicyRequestVerifier.PATH,assertion(body),body);}
    static ResourceBinding credentialResource(){var row=db.queryForMap("select id,policy_revision,secret_ref,claim_limits,allowed_origins from app.integration_credential where id=?",id);var c=Json.tree(Map.of("secretRef",row.get("secret_ref"),"claimLimits",Json.read(row.get("claim_limits").toString()),"allowedOrigins",Json.read(row.get("allowed_origins").toString())));return new ResourceBinding("integration-credential",Long.toString(id),Digest.sha256(Json.canonical(Map.of("credentialId",id,"policyVersion",id+":"+row.get("policy_revision"),"policyDigest",IntegrationClaims.digest(c)))));}
    static HttpResponse<String> admin(String method,String path,String template,ResourceBinding resource,String body)throws Exception{try(var gateway=fixture.client("gateway")){var route=new ServiceRoutePolicy().require("gateway","app",method,template);var call=new ServiceCall("app",route.action(),resource);return http(method,path,gateway.exchange(fixture.portal(),call).value(),body);}}
    static HttpResponse<String> update(Map<String,Object> p)throws Exception{return admin("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,"/admin/apps/{code}/integration/credentials/{biz}",credentialResource(),Json.write(p));}
    @Test void policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak()throws Exception{
        var r=resolve(requestBody("alice"));assertThat(fixture.authorityCalls()).isZero();assertThat(queries).hasValue(1);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var n=Json.read(r.body());assertThat(n.path("principalId").asText()).isEqualTo("partner:alice");assertThat(n.path("policyVersion").asText()).isEqualTo(id+":1");assertThat(n.path("roleCeiling").toString()).isEqualTo("[\"reader\"]");assertThat(n.path("attributeCeiling").has("dataLabels")).isFalse();assertThat(n.path("dataLabels").toString()).isEqualTo("[\"restricted\"]");assertThat(n.fieldNames()).toIterable().containsExactlyInAnyOrder("schemaVersion","credentialId","tenantId","appCode","bizSystemCode","userId","principalId","active","subjectAllowed","roleCeiling","attributeCeiling","dataLabels","allowedOrigins","policyVersion","policyDigest","nonce");assertThat(r.body()).doesNotContain("secretRef","d2-only-reference","principalIds",LONG_USER);
        var updated=update(policy("alice"));assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(2);
    }
    @Test void policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects()throws Exception{
        var p=update(policy("bob"));assertThat(p.statusCode()).as(p.body()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(2);
        var old=requestBody("alice");old.put("expectedPolicyVersion",id+":1");assertThat(resolve(old).statusCode()).isEqualTo(409);
        var gone=resolve(requestBody("alice"));assertThat(Json.read(gone.body()).path("subjectAllowed").asBoolean()).isFalse();
        var revoked=admin("POST","/admin/apps/"+APP+"/integration/credentials/"+BIZ+"/revoke","/admin/apps/{code}/integration/credentials/{biz}/revoke",credentialResource(),"");assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(3);
    }

    @Test void wrongSignatureClaimsAlgorithmsMethodPathAndBindingNeverQueryPg()throws Exception{
        String body=Json.write(requestBody("alice"));
        for(String field:List.of("iss","caller","sub","target","action","aud","token_use","nonce","bodySha256")){
            var c=assertionClaims(body);c.put(field,"wrong");var r=http("POST",PolicyRequestVerifier.PATH,fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt"),body);assertThat(r.statusCode()).as(field+r.body()).isIn(401,403);
        }
        for(String field:List.of("iat","nbf","exp")){var c=assertionClaims(body);c.put(field,field.equals("exp")?0:Instant.now().plusSeconds(120).getEpochSecond());assertThat(http("POST",PolicyRequestVerifier.PATH,fixture.sign(c,fixture.policySigningKey(),"agent-policy-request+jwt"),body).statusCode()).isIn(401,403);}
        for(String token:List.of(fixture.sign(assertionClaims(body),fixture.authoritySigningKey(),"agent-policy-request+jwt"),fixture.sign(assertionClaims(body),fixture.policySigningKey(),"at+jwt"),fixture.portal(),fixture.integration(fixture.integrationClaims()),"eyJhbGciOiJub25lIn0.e30."))assertThat(http("POST",PolicyRequestVerifier.PATH,token,body).statusCode()).isIn(401,403);
        assertThat(http("POST",PolicyRequestVerifier.PATH,assertion(body),body+" ").statusCode()).isIn(401,403);
        assertThat(http("GET",PolicyRequestVerifier.PATH,assertion(body),body).statusCode()).isIn(401,403);
        assertThat(http("POST","/internal/iam/integration-policy/other",assertion(body),body).statusCode()).isIn(401,403);
        String duplicate=body.replace("{","{\"schemaVersion\":1,");assertThat(http("POST",PolicyRequestVerifier.PATH,assertion(duplicate),duplicate).statusCode()).isIn(401,403);
        assertThat(queries).hasValue(0);assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void strictDtoTypesBoundsAndUnknownFieldsRejectBeforePg()throws Exception{
        for(String field:List.of("schemaVersion","credentialId","expectedTenantId","expectedAppCode","expectedBizSystemCode","userId","expectedPrincipalId","expectedPolicyVersion","nonce")){
            var missing=requestBody("alice");missing.remove(field);var r=resolve(missing);assertThat(r.statusCode()).as("missing "+field+r.body()).isIn(400,401);
            var wrong=requestBody("alice");wrong.put(field,List.of("wrong"));assertThat(resolve(wrong).statusCode()).as(field).isIn(400,401);
        }
        for(var entry:Map.<String,Object>of("schemaVersion",2,"credentialId",0,"expectedTenantId","t".repeat(129),"expectedAppCode","a".repeat(65),"userId","u".repeat(129),"expectedPolicyVersion","1:0").entrySet()){var r=requestBody("alice");r.put(entry.getKey(),entry.getValue());assertThat(resolve(r).statusCode()).isEqualTo(400);}
        var extra=requestBody("alice");extra.put("allowed",true);assertThat(resolve(extra).statusCode()).isEqualTo(400);
        assertThat(queries).hasValue(0);
    }
    @Test void tenantAppBizCredentialPrincipalIsolationLongMappingAndSubjectRemoval()throws Exception{
        for(String field:List.of("expectedTenantId","expectedAppCode","expectedBizSystemCode","credentialId","expectedPrincipalId")){
            var r=requestBody("alice");r.put(field,field.equals("credentialId")?Long.MAX_VALUE:"wrong");var out=resolve(r);assertThat(out.statusCode()).as(field+out.body()).isIn(403,404);assertThat(out.body()).doesNotContain("d2-only-reference","claimLimits","principalIds");
        }
        var outsider=resolve(requestBody("bob"));assertThat(outsider.statusCode()).isEqualTo(200);assertThat(Json.read(outsider.body()).path("subjectAllowed").asBoolean()).isFalse();
        var longResult=resolve(requestBody(LONG_USER));assertThat(longResult.statusCode()).as(longResult.body()).isEqualTo(200);assertThat(Json.read(longResult.body()).path("principalId").asText()).isEqualTo(IntegrationClaims.principal(BIZ,LONG_USER));
        var active=resolve(requestBody("alice"));String digest=Json.read(active.body()).path("policyDigest").asText();var meta=db.queryForMap("select secret_ref,claim_limits,allowed_origins from app.integration_credential where id=?",id);
        assertThat(digest).isEqualTo(Digest.sha256(Json.canonical(Map.of("secretRef",Json.tree(meta.get("secret_ref")),"claimLimits",Json.read(meta.get("claim_limits").toString()),"allowedOrigins",Json.read(meta.get("allowed_origins").toString())))));
    }
    @Test void crossedTrustDomainsAndWrongCredentialResourcesWriteNothing()throws Exception{
        String body=Json.write(requestBody("alice"));
        assertThat(http("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,assertion(body),Json.write(policy("bob"))).statusCode()).isIn(401,403);
        try(var gateway=fixture.client("gateway")){
            var route=new ServiceRoutePolicy().require("gateway","app","PUT","/admin/apps/{code}/integration/credentials/{biz}");
            var token=gateway.exchange(fixture.portal(),new ServiceCall("app",route.action(),credentialResource())).value();
            assertThat(http("POST",PolicyRequestVerifier.PATH,token,body).statusCode()).isIn(401,403);
        }
        for(var resource:List.of(new ResourceBinding("integration-credential",Long.toString(id+1),credentialResource().versionDigest()),new ResourceBinding("integration-credential",Long.toString(id),Digest.sha256("wrong")))){
            assertThat(admin("PUT","/admin/apps/"+APP+"/integration/credentials/"+BIZ,"/admin/apps/{code}/integration/credentials/{biz}",resource,Json.write(policy("bob"))).statusCode()).isEqualTo(403);
        }
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);assertThat(queries).hasValue(0);
    }
    @Test void invalidPolicyAndAuditFailureRollBackRevisionAndPayload()throws Exception{
        var invalid=new HashMap<>(policy("bob"));invalid.put("allowedOrigins",List.of("http://external.invalid"));assertThat(update(invalid).statusCode()).isEqualTo(400);
        db.execute("create or replace function app.d2_fail_audit() returns trigger language plpgsql as $$ begin if new.tenant_id='d2-policy-tests' and new.action='INTEGRATION_POLICY' then raise exception 'finite-audit-failure'; end if; return new; end $$");
        db.execute("create trigger d2_fail_audit before insert on app.lifecycle_log for each row execute function app.d2_fail_audit()");
        try{assertThat(update(policy("bob")).statusCode()).isEqualTo(500);assertThat(auditErrors).hasValue(1);}finally{db.execute("drop trigger d2_fail_audit on app.lifecycle_log");db.execute("drop function app.d2_fail_audit()");}
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("select claim_limits->'principalIds' @> '[\"alice\"]'::jsonb from app.integration_credential where id=?",Boolean.class,id)).isTrue();
    }
    @Test void issueBindsStoredPublishedVersionAndInitialRevisionIsOne()throws Exception{
        var body=new HashMap<>(policy("alice"));body.put("bizSystemCode","issued");
        var resource=new ResourceBinding("app-version",APP+"@1",Digest.sha256("published-d2"));
        assertThat(admin("POST","/admin/apps/"+APP+"/integration/credentials","/admin/apps/{code}/integration/credentials",new ResourceBinding("app-version",APP+"@1",Digest.sha256("wrong")),Json.write(body)).statusCode()).isEqualTo(403);
        var issued=admin("POST","/admin/apps/"+APP+"/integration/credentials","/admin/apps/{code}/integration/credentials",resource,Json.write(body));assertThat(issued.statusCode()).as(issued.body()).isEqualTo(200);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where tenant_id=? and app_code=? and biz_system_code='issued'",Long.class,TENANT,APP)).isEqualTo(1);
    }
    static Map<String,Object> sourceClaims(String digest){
        var c=fixture.integrationClaims();c.put("iss",ServiceTokens.ISSUER);c.put("tenant_id",TENANT);c.put("sub",IntegrationClaims.principal(BIZ,"alice"));c.put("app_code",APP);c.put("biz_system_code",BIZ);c.put("user_id","alice");c.put("credential_id",id);c.put("policy_digest",digest);
        c.put("roles",List.of("reader"));c.put("attributes",Map.of("region",List.of("A"),"dataLabels",List.of("source-only"),"bizSystemCode",List.of(BIZ),"userId",List.of("alice")));return c;
    }
    static String integrationToken(String digest){return fixture.integration(sourceClaims(digest));}
    static ServiceCall integrationCall(){return new ServiceCall("engine","proxy.engine.get.RunController.status",new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));}
    static com.fasterxml.jackson.databind.JsonNode ticketClaims(ServiceTicket ticket){return Json.read(new String(Base64.getUrlDecoder().decode(ticket.value().split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8));}
    static Map<String,Object> widePolicy(){return Map.of("claimLimits",Map.of("principalIds",List.of("alice",LONG_USER),"roles",List.of("reader","writer","ADMIN"),"attributes",Map.of("region",List.of("A","B"),"dataLabels",List.of("restricted"))),"allowedOrigins",List.of("https://embed.example.invalid"));}
    static String padBitsVariant(String token)throws Exception{
        String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";String result=token.substring(0,token.length()-1)+alphabet.charAt(alphabet.indexOf(token.charAt(token.length()-1))+1);
        var original=com.nimbusds.jose.JWSObject.parse(token);var changed=com.nimbusds.jose.JWSObject.parse(result);
        assertThat(changed.getSignature().decode()).isEqualTo(original.getSignature().decode());assertThat(changed.getPayload().toBytes()).isEqualTo(original.getPayload().toBytes());assertThat(result).isNotEqualTo(token);return result;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"pad-bits","reordered-claims"})
    void stableAuthenticationRejectsEquivalentOldTokenAfterSamePolicyRevision(String representation)throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);String original=fixture.integration(claims);
        String equivalent=representation.equals("pad-bits")?padBitsVariant(original):fixture.integration(new TreeMap<>(claims));assertThat(equivalent).isNotEqualTo(original);
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            var call=integrationCall();int before=queries.get();var ticket=gateway.exchange(original,call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);

            assertThat(update(policy("alice")).statusCode()).isEqualTo(200);assertThat(Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText()).isEqualTo(digest);
            int grants=fixture.grantCount();before=queries.get();assertThatThrownBy(()->gateway.exchange(equivalent,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants);
            before=queries.get();assertThatThrownBy(()->gateway.exchange(original,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.resume(current.delegation(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants);
            var fresh=new LinkedHashMap<>(claims);fresh.put("jti",UUID.randomUUID().toString());before=queries.get();var next=gateway.exchange(fixture.integration(fresh),call);assertThat(queries.get()-before).isEqualTo(1);assertThat(fixture.grantCount()).isEqualTo(grants+1);
            before=queries.get();assertThat(engine.authorize(next.value(),call).actor().roles()).containsExactly("reader");assertThat(queries.get()-before).isEqualTo(1);
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void externalGrantKeepsNarrowSourceClaimsAndUnionsLabelsThroughEveryDecision()throws Exception{
        assertThat(update(widePolicy()).statusCode()).isEqualTo(200);String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);claims.put("roles",List.of("reader","ADMIN"));
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            var call=integrationCall();int before=queries.get();var ticket=gateway.exchange(fixture.integration(claims),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var resumed=gateway.resume(current.delegation(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var resumedCurrent=engine.authorize(resumed.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var repeated=gateway.exchange(fixture.integration(new TreeMap<>(claims)),call);assertThat(queries.get()-before).isEqualTo(1);
            org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
                for(var candidate:List.of(ticket,resumed,repeated)){
                    var n=ticketClaims(candidate);for(var actor:List.of(n,n.get("initial_ceiling"))){
                        soft.assertThat(actor.path("roles").toString()).isEqualTo("[\"reader\"]");
                        soft.assertThat(actor.path("attributes").path("region").toString()).isEqualTo("[\"A\"]");
                        soft.assertThat(actor.path("attributes").path("dataLabels")).containsExactlyInAnyOrder(Json.tree("source-only"),Json.tree("restricted"));
                        soft.assertThat(actor.path("attributes").path("bizSystemCode").toString()).isEqualTo("[\"partner\"]");soft.assertThat(actor.path("attributes").path("userId").toString()).isEqualTo("[\"alice\"]");
                    }
                }
                for(var actor:List.of(current.actor(),resumedCurrent.actor())){soft.assertThat(actor.roles()).containsExactly("reader");soft.assertThat(actor.attributes().get("region")).containsExactly("A");soft.assertThat(actor.attributes().get("dataLabels")).containsExactlyInAnyOrder("source-only","restricted");}
            });
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }
    @Test void emptySourcePermissionsAreNeverReplenishedFromPolicy()throws Exception{
        assertThat(update(widePolicy()).statusCode()).isEqualTo(200);String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            for(boolean explicitEmptyRegion:List.of(false,true)){
                var claims=sourceClaims(digest);claims.put("roles",List.of());var attributes=new LinkedHashMap<String,Object>();attributes.put("bizSystemCode",List.of(BIZ));attributes.put("userId",List.of("alice"));if(explicitEmptyRegion)attributes.put("region",List.of());claims.put("attributes",attributes);
                int before=queries.get();var ticket=gateway.exchange(fixture.integration(claims),integrationCall());assertThat(queries.get()-before).isEqualTo(1);
                var initial=ticketClaims(ticket).path("initial_ceiling");assertThat(initial.path("roles")).isEmpty();assertThat(initial.path("attributes").path("region")).isEmpty();assertThat(initial.path("attributes").path("dataLabels").toString()).isEqualTo("[\"restricted\"]");
                before=queries.get();var current=engine.authorize(ticket.value(),integrationCall());assertThat(queries.get()-before).isEqualTo(1);assertThat(current.actor().roles()).isEmpty();assertThat(current.actor().attributes().getOrDefault("region",Set.of())).isEmpty();
            }
        }
    }
    @Test void malformedSourceAuthenticationRejectsBeforePolicyAndConsumesNoGrant()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        try(var gateway=fixture.client("gateway")){
            var valid=sourceClaims(digest);String signed=fixture.integration(valid);int signatureStart=signed.lastIndexOf('.')+1;
            String wrongSignature=signed.substring(0,signatureStart)+(signed.charAt(signatureStart)=='A'?'B':'A')+signed.substring(signatureStart+1);
            assertSourceTokenDeniedWithoutQuery(gateway,wrongSignature);assertSourceTokenDeniedWithoutQuery(gateway,fixture.sign(valid,fixture.authoritySigningKey(),"JWT"));
            for(String field:List.of("iss","jti","roles","attributes")){
                var missing=sourceClaims(digest);missing.remove(field);assertSourceDeniedWithoutQuery(gateway,missing);
                var wrong=sourceClaims(digest);wrong.put(field,7);assertSourceDeniedWithoutQuery(gateway,wrong);
            }
            for(Object jti:List.of(""," ","j".repeat(129),List.of("jti"))){var c=sourceClaims(digest);c.put("jti",jti);assertSourceDeniedWithoutQuery(gateway,c);}
            var issuer=sourceClaims(digest);issuer.put("iss","wrong-issuer");assertSourceDeniedWithoutQuery(gateway,issuer);
            var subject=sourceClaims(digest);subject.put("sub","other");assertSourceDeniedWithoutQuery(gateway,subject);
            for(var attributes:List.of(Map.of("unknown",List.of("A")),Map.of("bizSystemCode",List.of("other"),"userId",List.of("alice")),Map.of("bizSystemCode",List.of(BIZ),"userId",List.of("other")),Map.of("region",List.of(1),"bizSystemCode",List.of(BIZ),"userId",List.of("alice")))){var c=sourceClaims(digest);c.put("attributes",attributes);assertSourceDeniedWithoutQuery(gateway,c);}
        }
    }
    static void assertSourceDeniedWithoutQuery(HttpCurrentAuthorityClient gateway,Map<String,Object> claims){
        assertSourceTokenDeniedWithoutQuery(gateway,fixture.integration(claims));
    }
    static void assertSourceTokenDeniedWithoutQuery(HttpCurrentAuthorityClient gateway,String token){
        int before=queries.get(),grants=fixture.grantCount();assertThatThrownBy(()->gateway.exchange(token,integrationCall())).isInstanceOf(PlatformException.class);assertThat(queries.get()).isEqualTo(before);assertThat(fixture.grantCount()).isEqualTo(grants);
    }
    @Test void sameAuthenticationCannotChangeBindingsTimesOrOriginalCeiling()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();var claims=sourceClaims(digest);
        try(var gateway=fixture.client("gateway")){
            gateway.exchange(fixture.integration(claims),integrationCall());
            for(String field:List.of("tenant_id","sub","app_code","biz_system_code","user_id","credential_id","origin","policy_digest","iat","nbf","exp","roles","attributes")){
                var changed=new LinkedHashMap<>(claims);changed.put(field,switch(field){case "credential_id"->id+1;case "iat","nbf"->((Number)claims.get(field)).longValue()-1;case "exp"->((Number)claims.get(field)).longValue()+1;case "roles"->List.of("reader","writer");case "attributes"->Map.of("region",List.of("A","B"),"dataLabels",List.of("source-only"),"bizSystemCode",List.of(BIZ),"userId",List.of("alice"));default->"changed";});assertSourceDeniedWithoutQuery(gateway,changed);
            }
        }
    }

    @Test void realResolverBackedGrantsFailAfterRevisionAndExplicitNewAuthenticationRecovers()throws Exception{
        String digest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();String subject=integrationToken(digest);
        var call=new ServiceCall("engine","proxy.engine.get.RunController.status",new ResourceBinding("run","r-test",Digest.sha256("snapshot-v1")));
        try(var gateway=fixture.client("gateway");var engine=fixture.client("engine")){
            int before=queries.get();var ticket=gateway.exchange(subject,call);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();var current=engine.authorize(ticket.value(),call);assertThat(queries.get()-before).isEqualTo(1);
            assertThat(current.actor().roles()).containsExactly("reader");
            assertThat(update(policy("alice")).statusCode()).isEqualTo(200);
            before=queries.get();assertThatThrownBy(()->engine.authorize(ticket.value(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.resume(current.delegation(),call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            before=queries.get();assertThatThrownBy(()->gateway.exchange(subject,call)).isInstanceOf(PlatformException.class);assertThat(queries.get()-before).isEqualTo(1);
            var fresh=gateway.exchange(integrationToken(digest),call);assertThat(engine.authorize(fresh.value(),call).actor().roles()).containsExactly("reader");
            assertThat(admin("POST","/admin/apps/"+APP+"/integration/credentials/"+BIZ+"/revoke","/admin/apps/{code}/integration/credentials/{biz}/revoke",credentialResource(),"").statusCode()).isEqualTo(200);
            assertThatThrownBy(()->gateway.exchange(integrationToken(digest),call)).isInstanceOf(PlatformException.class);
        }
        assertThat(fixture.resolverToAuthorityCalls()).isZero();
    }

    @Test void concurrentStaleWritesSerializeAndSnapshotsNeverMixRevisionAndPolicy()throws Exception{
        String oldDigest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();
        var route=new ServiceRoutePolicy().require("gateway","app","PUT","/admin/apps/{code}/integration/credentials/{biz}");var call=new ServiceCall("app",route.action(),credentialResource());String first,second;
        try(var gateway=fixture.client("gateway")){first=gateway.exchange(fixture.portal(),call).value();second=gateway.exchange(fixture.portal(),call).value();}
        String path="/admin/apps/"+APP+"/integration/credentials/"+BIZ,body=Json.write(policy("bob"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(3)){
            var gate=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return http("PUT",path,first,body);});
            var b=pool.submit(()->{gate.await();return http("PUT",path,second,body);});
            var reads=pool.submit(()->{gate.await();var out=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();for(int i=0;i<4;i++){var r=resolve(requestBody("alice"));assertThat(r.statusCode()).isEqualTo(200);out.add(Json.read(r.body()));}return out;});gate.countDown();
            assertThat(List.of(a.get(8,java.util.concurrent.TimeUnit.SECONDS).statusCode(),b.get(8,java.util.concurrent.TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,403);
            String newDigest=Json.read(resolve(requestBody("alice")).body()).path("policyDigest").asText();assertThat(newDigest).isNotEqualTo(oldDigest);
            for(var n:reads.get(8,java.util.concurrent.TimeUnit.SECONDS)){if(n.path("policyVersion").asText().equals(id+":1")){assertThat(n.path("policyDigest").asText()).isEqualTo(oldDigest);assertThat(n.path("subjectAllowed").asBoolean()).isTrue();}else{assertThat(n.path("policyVersion").asText()).isEqualTo(id+":2");assertThat(n.path("policyDigest").asText()).isEqualTo(newDigest);assertThat(n.path("subjectAllowed").asBoolean()).isFalse();}}
        }
        assertThat(update(policy("alice")).statusCode()).isEqualTo(200);assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(3);
    }
    @Test void nonAdminAndWrongTenantCannotMutateEvenWithCorrectServiceRoute()throws Exception{
        try{
            fixture.portalActor(new Actor(TENANT,"reader-d2",Set.of("reader"),Map.of("dataLabels",Set.of("restricted"))));assertThat(update(policy("bob")).statusCode()).isEqualTo(403);
            fixture.portalActor(new Actor("other-tenant","admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));assertThat(update(policy("bob")).statusCode()).isEqualTo(404);
        }finally{fixture.portalActor(new Actor(TENANT,"admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))));}
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
    }

    @Test void adminRoleMustStillExistAtTheFinalResourceDecision()throws Exception{
        fixture.narrowAdminAtResourceCheck();assertThat(update(policy("bob")).statusCode()).isEqualTo(403);
        assertThat(db.queryForObject("select policy_revision from app.integration_credential where id=?",Long.class,id)).isEqualTo(1);
    }

    @Test void actualPolicyChainWithoutGlobalProofRegistrationCannotEnterResolver()throws Exception{
        var original=app;includeProof=false;
        try(var denied=startReceiver()){app=denied;assertThat(resolve(requestBody("alice")).statusCode()).isEqualTo(403);assertThat(queries).hasValue(0);}
        finally{app=original;includeProof=true;}
    }
}
