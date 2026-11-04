package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.*;
import cn.agentplatform.common.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实PG验证独立来源和应用保存门；外部发布metadata/批准facts是明确协议夹具，非真实发布结论。 */
@EnabledIfEnvironmentVariable(named="CR05_DB_URL",matches="jdbc:postgresql://127[.]0[.]0[.]1:35432/agent_cr05_20261104")
class OnboardingSourcePostgresTest {
    JdbcTemplate db;
    OnboardingImportSourceRepository repository;
    OnboardingApplicationSources sources;
    Map<String,Object> fixture,request,draft,definition,actual,session,pkg,prepared,connectionFacts;
    Actor actor;
    @BeforeAll static void database() {OnboardingAssemblyPostgresTest.database();}
    @BeforeEach void seed() throws Exception {
        db=OnboardingAssemblyPostgresTest.db;
        repository=new OnboardingImportSourceRepository(db,OnboardingAssemblyPostgresTest.manager);
        sources=new OnboardingApplicationSources(repository);
        actor=new Actor("cr05-source-"+UUID.randomUUID(),"editor",Set.of("EDITOR"),Map.of());
        var jwt=Jwt.withTokenValue("component-fixture").header("alg","HS256").subject(actor.principalId())
                .claim("tenant_id",actor.tenantId()).claim("roles",List.of("EDITOR")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        fixture=object(Json.read(Files.readString(Path.of(System.getenv("CR05_IMPORT_FIXTURE"))),Map.class));
        request=object(fixture.get("request"));draft=object(fixture.get("draft"));definition=object(draft.get("definition"));
        actual=new LinkedHashMap<>(definition);actual.putAll(draft);actual.put("status","PUBLISHED");actual.put("skillStatus","PUBLISHED");
        actual.put("id",draft.get("skillVersionId"));actual.put("versionNo",draft.get("version"));actual.put("digest",draft.get("sha256"));actual.put("registered",true);
        var tool=new LinkedHashMap<String,Object>();tool.put("id","query-tool");tool.put("operation","readItems");tool.put("binding","http");
        tool.put("exposure","READ_ONLY");tool.put("readonly",true);tool.put("sideEffect",false);tool.put("idempotent",true);
        tool.put("inputSchema",definition.get("inputSchema"));tool.put("outputSchema",definition.get("outputSchema"));
        tool.put("description",definition.get("description"));tool.put("errorSemantics",definition.get("errorSemantics"));
        var declaration=Map.<String,Object>of("software",Map.of("environments",Map.of("dev","https://standard.invalid")),
                "interfaces",List.of(Map.of("id","http","kind","HTTP_SYNC","endpoint","https://standard.invalid/items","httpMethod","GET","timeoutMs",1000)),
                "routeProfiles",List.of(Map.of("id","query-route","execution","http","tools",List.of("query-tool"))),
                "identity",Map.of("guard",definition.get("guard")),"tools",List.of(tool));
        Long pkgId=db.queryForObject("insert into app.capability_package_version(tenant_id,package_id,package_version,package_sha256,declaration,software_id,binding_status,created_by)"
                +" values(?,'source-package','1.0',?,?::jsonb,'standard','UNRESOLVED','editor') returning id",Long.class,actor.tenantId(),"b".repeat(64),Json.write(declaration));
        pkg=new CapabilityPackageRepository(db,OnboardingAssemblyPostgresTest.manager).bindingSource(pkgId,actor.tenantId());
        db.update("insert into app.onboarding_template(tenant_id,code,current_rev,status,created_by) values(?,'standard_read',1,'PUBLISHED','editor')",actor.tenantId());
        var source=object(request.get("source"));
        var fields=new LinkedHashMap<>(OnboardingPlan.fields(object(definition.get("inputSchema"))));
        fields.put("returns",Map.of("summaryTemplate","状态{{status}}","cardTemplateEnabled",false,"cardFields",List.of(),"fileRefPaths",List.of()));
        var plan=OnboardingPlan.normalize(Map.of("fieldsBundle",fields,"values",Map.of(),"selectedOperations",List.of("readItems"),"connectionRef",AppService.objects(request.get("bindingRefs")).getFirst()));
        var seed=Map.<String,Object>of("sessionId",source.get("sessionId"),"templateCode","standard_read","templateRev",1,"appCode","imported_flow","appName","Imported flow",
                "template",Map.of("sourcePackageVersionId",pkgId),"plan",plan,"planSha256",source.get("planSha256"));
        session=new OnboardingSessionRepository(db,OnboardingAssemblyPostgresTest.manager).create(actor,seed,"create","c".repeat(64));
        var facts=new LinkedHashMap<String,Object>();facts.put("profileId","standard-profile");facts.put("profileRev",1);facts.put("status","APPROVED");facts.put("protocol","HTTP_SYNC");facts.put("authKind","NONE");facts.put("resolutionDigest","d".repeat(64));
        facts.put("operationAllowlist",List.of("readItems"));facts.put("endpointPolicy",Map.of("hosts",List.of("https://standard.invalid"),"pathPrefixes",List.of("/"),"httpMethods",List.of("GET")));
        facts.put("bindings",List.of(Map.of("bindingId","standard-dev","bindingRev",1,"environment","dev","baseEndpoint","https://standard.invalid","secretRef","")));
        connectionFacts=facts;
        prepared=OnboardingImportRules.prepare(request.get("document").toString(),object(fixture.get("preview")),declaration,
                Map.of("environment","dev","routeProfileId","query-route"),List.of("readItems"),facts,object(request.get("overrides")));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void canonicalSessionSourceSupportsActualFlowSaveAndRejectsForgedOrRemovedBindings() throws Exception {
        var frozen=sources.prepare(session,pkg,prepared,request);var resolved=sources.resolve(session,List.of(actual));
        assertEquals(frozen.get("sourceId"),resolved.get("sessionSourceId"));assertEquals(resolved,sources.resolve(session,List.of(actual)));
        assertEquals("UNRESOLVED",new CapabilityPackageRepository(db,OnboardingAssemblyPostgresTest.manager)
                .bindingSource(((Number)pkg.get("packageVersionId")).longValue(),actor.tenantId()).get("bindingStatus"));
        var apps=new AppService(db,OnboardingAssemblyPostgresTest.manager,"https://skill.invalid","https://model.invalid","https://monitor.invalid","https://engine.invalid"){
            @Override public Map<String,Object> remote(String base,String path){return Map.of("status","PUBLISHED","versions",List.of(actual));}
            @Override public Map<String,Object> remotePost(String base,String path,Map<String,Object> body){assertEquals("/internal/graphs/validate",path);assertTrue(cn.agentplatform.app.domain.AppRules.graphErrors(AppService.object(body.get("definition"))).isEmpty());return Map.of("valid",true);}
        };apps.onboardingSources(sources);
        var client=mock(OnboardingSkillImportsClient.class);when(client.version(draft.get("code").toString(),1)).thenReturn(actual);
        var assembler=new CapabilityPackageApplicationsService(new CapabilityPackageRepository(db,OnboardingAssemblyPostgresTest.manager),apps,mock(CapabilitySkillClient.class),OnboardingAssemblyPostgresTest.manager);
        assembler.onboardingSources(sources);assembler.onboardingSkills(client);
        var service=new OnboardingSessions(null,null,null,null,null,apps,null,db,null);
        var sourceClass=Class.forName(OnboardingSessions.class.getName()+"$Source");var ctor=sourceClass.getDeclaredConstructor(Map.class,List.class,String.class);ctor.setAccessible(true);
        var operation=Map.<String,Object>of("definition",definition,"binding",AppService.objects(resolved.get("bindings")).getFirst(),"inputSchema",definition.get("inputSchema"));
        var method=OnboardingSessions.class.getDeclaredMethod("applicationPlan",Map.class,sourceClass,Map.class);method.setAccessible(true);
        var stored=new LinkedHashMap<>(pkg);stored.put("resolvedMapping",resolved);
        @SuppressWarnings("unchecked") var input=(Map<String,Object>)method.invoke(service,session,ctor.newInstance(stored,List.of(operation),"dev"),operation);
        var application=assembler.prepareForOnboardingSource(session,input);
        var created=new TransactionTemplate(OnboardingAssemblyPostgresTest.manager).execute(status->assembler.assembleForSession(application,null));
        assertTrue(sources.assertIfPresent(created));
        assertEquals(frozen.get("sourceId"),db.queryForObject("select session_source_id from app.capability_app_binding where tenant_id=?",String.class,actor.tenantId()));
        var tampered=new LinkedHashMap<>(AppService.object(created.get("config")));tampered.remove("capabilityPackages");
        assertThrows(PlatformException.class,()->apps.save("imported_flow",Map.of("draftRev",created.get("draftRev"),"config",tampered),false));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("update app.onboarding_import_source set snapshot='{}'::jsonb where tenant_id=?",actor.tenantId()));
    }
    @Test void frozenSourceSurvivesRecreationButChangedInputsAndNonOwnerAreRejected() {
        var first=sources.prepare(session,pkg,prepared,request);
        assertEquals(first.get("sourceId"),new OnboardingApplicationSources(new OnboardingImportSourceRepository(db,OnboardingAssemblyPostgresTest.manager)).prepare(session,pkg,prepared,request).get("sourceId"));
        var changed=new LinkedHashMap<>(session);changed.put("plan",Map.of("different",true));
        assertThrows(PlatformException.class,()->sources.prepare(changed,pkg,prepared,request));
        assertNull(repository.get(new Actor(actor.tenantId(),"other",Set.of("EDITOR"),Map.of()),session.get("sessionId").toString(),session.get("planSha256").toString()));
        var bad=new LinkedHashMap<>(object(object(request.get("overrides")).get("readItems")));
        var returns=new LinkedHashMap<>(object(bad.get("returnSpec")));returns.put("modelSummary",Map.of("mode","EXEC","tpl","unsafe"));bad.put("returnSpec",returns);
        assertTrue(assertThrows(PlatformException.class,()->OnboardingImportRules.prepare(request.get("document").toString(),object(fixture.get("preview")),
                object(pkg.get("declaration")),Map.of("environment","dev","routeProfileId","query-route"),List.of("readItems"),connectionFacts,Map.of("readItems",bad)))
                .getMessage().contains("modelSummary"));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object raw){return (Map<String,Object>)raw;}
}
