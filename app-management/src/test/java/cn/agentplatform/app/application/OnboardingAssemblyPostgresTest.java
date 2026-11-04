package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.*;
import cn.agentplatform.common.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际prepare/validate/AppService/PG来源关系；仅外部元数据与图验证边界用明确协议替身。 */
@EnabledIfEnvironmentVariable(named="CR05_DB_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:35432/agent_cr05_20261104")
class OnboardingAssemblyPostgresTest {
    static JdbcTemplate db;static DataSourceTransactionManager manager;
    @BeforeAll static void database(){var ds=new DriverManagerDataSource(System.getenv("CR05_DB_URL"),System.getenv("CR05_DB_USER"),System.getenv("CR05_DB_PASSWORD"));Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app").locations("filesystem:"+System.getenv("CR05_MIGRATIONS")).load().migrate();db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void sameResolvedTemplateBuildsTwoOwnedAppsThroughOriginalSourceChecksAndDoesNotOverwriteManualEdits()throws Exception{
        String tenant="cr05-assembly-"+UUID.randomUUID();var jwt=Jwt.withTokenValue("verified").header("alg","HS256").subject("editor").claim("tenant_id",tenant).claim("roles",List.of("EDITOR")).build();SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var schema=Map.<String,Object>of("type","object","properties",Map.of("flag",Map.of("type","boolean"),"count",Map.of("type","number"),"note",Map.of("type",List.of("string","null"))),"required",List.of("flag","count","note"));
        var definition=new LinkedHashMap<String,Object>();definition.put("type","HTTP");definition.put("invocationKind","SYNC");definition.put("endpoint","http://127.0.0.1:1/query");definition.put("execution",Map.of("readonly",true,"sideEffect",false));definition.put("guard",Map.of("roles",List.of("EDITOR"),"attributes",Map.of()));definition.put("inputSchema",schema);definition.put("outputSchema",Map.of("type","object","properties",Map.of("status",Map.of("type","string"))));
        String skillSha=Digest.sha256(Json.canonical(definition));var actual=new LinkedHashMap<>(definition);actual.put("code","query_read");actual.put("version",1);actual.put("versionNo",1);actual.put("skillVersionId",123L);actual.put("status","PUBLISHED");actual.put("sha256",skillSha);actual.put("definition",definition);
        var binding=Map.<String,Object>of("toolId","query-tool","skillCode","query_read","version",1,"skillVersionId",123L,"sha256",skillSha);
        var mapping=Map.<String,Object>of("environment","dev","routeProfileId","primary","bindings",List.of(binding));
        long packageId=db.queryForObject("insert into app.capability_package_version(tenant_id,package_id,package_version,package_sha256,declaration,software_id,binding_status,created_by,request_plan_sha256,resolved_mapping,resolved_by,resolved_at,application_plan_sha256,application_receipt) values(?,'query-pkg','1.0.0',?,?::jsonb,'standard','RESOLVED','editor',?,?::jsonb,'editor',now(),?,'[]'::jsonb) returning id",Long.class,tenant,"a".repeat(64),Json.write(Map.of("tools",List.of(Map.of("id","query-tool","operation","query.read","inputSchema",schema)))),"b".repeat(64),Json.write(mapping),"9".repeat(64));
        var skillClient=new CapabilitySkillClient("http://127.0.0.1:1"){@Override public Map<String,Object> version(String code,int version,Actor actor,long deadline){return actual;}};
        var apps=new AppService(db,manager,"http://skill.test","http://model.test","http://monitor.test","http://engine.test"){
            @Override public Map<String,Object> remote(String base,String path){if(path.equals("/internal/skills/query_read"))return Map.of("status","PUBLISHED","versions",List.of(actual));throw new AssertionError(path);}
            @Override public Map<String,Object> remotePost(String base,String path,Map<String,Object> body){assertEquals("/internal/graphs/validate",path);assertTrue(cn.agentplatform.app.domain.AppRules.graphErrors(AppService.object(body.get("definition"))).isEmpty());return Map.of("valid",true);}
        };
        var packages=new CapabilityPackageRepository(db,manager);var assembler=new CapabilityPackageApplicationsService(packages,apps,skillClient,manager);
        var service=new OnboardingSessions(null,null,packages,skillClient,assembler,apps,null,db,null);
        var sourceClass=Class.forName("cn.agentplatform.app.application.OnboardingSessions$Source");var constructor=sourceClass.getDeclaredConstructor(Map.class,List.class,String.class);constructor.setAccessible(true);
        var operation=Map.<String,Object>of("definition",definition,"binding",binding,"inputSchema",schema);
        var source=constructor.newInstance(packages.bindingSource(packageId,tenant),List.of(operation),"dev");
        var method=OnboardingSessions.class.getDeclaredMethod("applicationPlan",Map.class,sourceClass,Map.class);method.setAccessible(true);
        var values=new LinkedHashMap<String,Object>();values.put("flag",false);values.put("count",0);values.put("note",null);
        var fields=new LinkedHashMap<>(OnboardingPlan.fields(schema));fields.put("returns",Map.of("summaryTemplate","状态{{status}}","cardTemplateEnabled",true,"cardFields",List.of(Map.of("label","状态","from","status")),"fileRefPaths",List.of()));
        var plan=OnboardingPlan.normalize(Map.of("fieldsBundle",fields,"values",values,"selectedOperations",List.of("query.read"),"connectionRef",Map.of("profileId","query-profile","profileRev",1,"bindingId","query-dev","bindingRev",1,"operation","query.read","environment","dev")));
        Map<String,Object> first=null;CapabilityPackageApplicationsService.SessionApplication preparedFirst=null;
        for(String code:List.of("query_one","query_two")){
            @SuppressWarnings("unchecked") var input=(Map<String,Object>)method.invoke(service,Map.of("plan",plan,"appCode",code,"appName",code),source,operation);
            var prepared=assembler.prepareForSession(packageId,input);var created=new TransactionTemplate(manager).execute(tx->assembler.assembleForSession(prepared,null));
            var graph=Json.tree(created.get("definition"));assertEquals(Map.of("code","query_read","version",1),AppService.object(AppService.objects(AppService.object(created.get("definition")).get("nodes")).get(1).get("skillRef")));
            assertEquals("${input.flag}",graph.path("nodes").path(1).path("args").path("flag").asText());
            assertEquals("状态${query.output.structured.status}",graph.path("nodes").path(2).path("args").path("modelSummary").asText());
            assertEquals("${query.output.structured.status}",graph.path("nodes").path(2).path("args").path("cardPayload").path("状态").asText());
            if(first==null){first=created;preparedFirst=prepared;}
        }
        assertEquals(2,db.queryForObject("select count(*) from app.capability_app_binding where tenant_id=? and package_version_id=?",Integer.class,tenant,packageId));
        assertEquals("9".repeat(64),db.queryForObject("select application_plan_sha256 from app.capability_package_version where id=?",String.class,packageId));
        var owned=Map.<String,Object>of("appId",first.get("id"),"draftRev",first.get("draftRev"));var changed=new LinkedHashMap<>(AppService.object(first.get("config")));changed.put("manualNote","preserve");apps.save("query_one",Map.of("draftRev",first.get("draftRev"),"config",changed),false);
        final var oldPrepared=preparedFirst;assertThrows(PlatformException.class,()->new TransactionTemplate(manager).execute(tx->assembler.assembleForSession(oldPrepared,owned)));
        assertEquals("preserve",AppService.object(apps.get("query_one").get("config")).get("manualNote"));
    }
}
