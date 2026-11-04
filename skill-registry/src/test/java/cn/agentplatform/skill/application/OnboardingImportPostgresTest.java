package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.OnboardingServiceProof;
import cn.agentplatform.skill.infra.OnboardingImportReceipts;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 专用DEV库验证真实导入/回执事务与专用证明；JWT解析后的Actor是本组件边界夹具。 */
@EnabledIfEnvironmentVariable(named="CR05_SKILL_DB_URL",matches="jdbc:postgresql://127[.]0[.]0[.]1:35432/agent_cr05_skill_20261104")
class OnboardingImportPostgresTest {
    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static final String REF="env:AGENT_ONBOARDING_DEV_APP_KEY";
    OnboardingSkillImports service;
    Actor actor;
    Map<String,Object> body;

    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(System.getenv("CR05_SKILL_DB_URL"),System.getenv("CR05_DB_USER"),System.getenv("CR05_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("skill").defaultSchema("skill")
                .locations("filesystem:"+System.getenv("CR05_SKILL_MIGRATIONS")).load().migrate();
        db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
    }
    @BeforeEach void setup() {
        actor=new Actor("cr05-close-"+UUID.randomUUID(),"editor",Set.of("SERVICE","EDITOR"),Map.of());
        authenticate(actor);
        service=service();
        String document=Json.write(Map.of("openapi","3.0.3","info",Map.of("title","Standard read","version","1.0"),
                "paths",Map.of("/items",Map.of("get",Map.of("operationId","readItems","responses",Map.of("200",Map.of("description","ok","content",Map.of("application/json",Map.of("schema",Map.of("type","object","properties",Map.of("status",Map.of("type","string"))))))))))));
        body=new LinkedHashMap<>();
        body.put("source",Map.of("sessionId","os_"+UUID.randomUUID().toString().replace("-",""),"planSha256","a".repeat(64),
                "templateCode","standard_read","templateRev",1,"packageVersionId",1,"packageSha256","b".repeat(64),"documentSha256",Digest.sha256(document)));
        body.put("document",document);body.put("includeOps",List.of("readItems"));body.put("defaults",Map.of("baseUrl","https://standard.invalid"));
        body.put("overrides",Map.of("readItems",Map.of("name","Read items","description","Standard readonly import",
                "execution",Map.of("readonly",true,"sideEffect",false,"idempotent",true,"cancellable",false),
                "guard",Map.of("roles",List.of("EDITOR"),"attributes",Map.of()),
                "returnSpec",Map.of("structuredPath","$","modelSummary",Map.of("tpl","{{status}}"),"fileRefPaths",List.of()),
                "errorSemantics",List.of(),"timeoutMs",1000)));
        body.put("bindingRefs",List.of(Map.of("profileId","standard-profile","profileRev",1,"bindingId","standard-dev","bindingRev",1,"operation","readItems","environment","dev")));
    }
    OnboardingSkillImports service() {
        var catalog=new SkillCatalog(db,manager,mock(cn.agentplatform.skill.infra.SkillClients.class),"https://unused.invalid");
        var result=new OnboardingSkillImports(new OpenApiImports(catalog),catalog,new OnboardingImportReceipts(db),manager);
        ReflectionTestUtils.setField(result,"securityMode","dev-hmac");ReflectionTestUtils.setField(result,"devAppKeyRef",REF);return result;
    }
    void authenticate(Actor current) {
        var token=Jwt.withTokenValue("component-fixture").header("alg","HS256").subject(current.principalId())
                .claim("tenant_id",current.tenantId()).claim("roles",new ArrayList<>(current.roles()))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }
    MockHttpServletRequest request(String method,String path,Object payload) {
        var parts=path.split("\\?",2);var request=new MockHttpServletRequest(method,parts[0]);
        if(parts.length==2)request.setQueryString(parts[1]);
        request.addHeader(OnboardingServiceProof.HEADER,OnboardingServiceProof.issue(REF,Actor.current(),method,path,payload));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));return request;
    }
    @AfterEach void clear() {RequestContextHolder.resetRequestAttributes();SecurityContextHolder.clearContext();}

    @Test void nativeDraftAndReceiptAreAtomicDurableAndIdempotent() {
        request("POST","/internal/onboarding-skill-imports",body);
        var first=service.create(body);var replay=service.create(body);
        assertEquals(Json.tree(first).path("drafts"),Json.tree(replay).path("drafts"));
        assertEquals("DRAFT",Json.tree(first).path("drafts").path(0).path("status").asText());
        assertEquals(1,db.queryForObject("select count(*) from skill.skill where tenant_id=?",Integer.class,actor.tenantId()));
        assertEquals(1,db.queryForObject("select count(*) from skill.onboarding_skill_import_receipt where tenant_id=?",Integer.class,actor.tenantId()));
        var source=Json.tree(body).path("source");String id=source.path("sessionId").asText(),sha=source.path("planSha256").asText();
        request("GET","/internal/onboarding-skill-imports/"+id+"?planSha256="+sha,null);
        assertEquals(Json.tree(first).path("drafts"),Json.tree(service().get(id,sha)).path("drafts"));
        if (System.getenv("CR05_IMPORT_FIXTURE") != null) {
            try {
                // 下个组件使用真实parser/导入器产物；发布状态仍是显式协议夹具，不伪称真实发布。
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getenv("CR05_IMPORT_FIXTURE")),
                        Json.write(Map.of("request",body,"preview",new OpenApiImports(null).preview(Map.of("document",body.get("document"))),
                                "draft",Json.tree(first).path("drafts").path(0))));
            } catch (java.io.IOException failure) {throw new AssertionError(failure);}
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update skill.onboarding_skill_import_receipt set principal_id='other' where tenant_id=?",actor.tenantId()));
    }
    @Test void proofBindsBodyPathActorAndModeAndNoUnauthorizedDraftIsCreated() {
        var request=request("POST","/internal/onboarding-skill-imports",body);request.removeHeader(OnboardingServiceProof.HEADER);
        assertThrows(PlatformException.class,()->service.create(body));
        request("POST","/internal/onboarding-skill-imports",body);var changed=new LinkedHashMap<>(body);changed.put("defaults",Map.of("baseUrl","https://other.invalid"));
        assertThrows(PlatformException.class,()->service.create(changed));
        request("POST","/internal/onboarding-skill-imports/wrong",body);assertThrows(PlatformException.class,()->service.create(body));
        ReflectionTestUtils.setField(service,"securityMode","iam");request("POST","/internal/onboarding-skill-imports",body);
        assertThrows(PlatformException.class,()->service.create(body));
        assertEquals(0,db.queryForObject("select count(*) from skill.skill where tenant_id=?",Integer.class,actor.tenantId()));
    }
    @Test void samePlanDifferentRequestAndOtherPrincipalCannotOverwriteReceipt() {
        request("POST","/internal/onboarding-skill-imports",body);service.create(body);
        var changed=new LinkedHashMap<>(body);changed.put("defaults",Map.of("baseUrl","https://changed.invalid"));request("POST","/internal/onboarding-skill-imports",changed);
        assertEquals(409,assertThrows(PlatformException.class,()->service.create(changed)).status());
        authenticate(new Actor(actor.tenantId(),"other",Set.of("SERVICE","EDITOR"),Map.of()));
        var source=Json.tree(body).path("source");String id=source.path("sessionId").asText(),sha=source.path("planSha256").asText();
        request("GET","/internal/onboarding-skill-imports/"+id+"?planSha256="+sha,null);
        assertThrows(PlatformException.class,()->service.get(id,sha));
        assertEquals(1,db.queryForObject("select count(*) from skill.onboarding_skill_import_receipt where tenant_id=?",Integer.class,actor.tenantId()));
    }
}
