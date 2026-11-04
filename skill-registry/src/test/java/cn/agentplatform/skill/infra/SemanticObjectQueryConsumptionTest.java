package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 正式清单→原导入/normalize→注册表→真实语义transport→原响应Schema投影，不替换生产链逻辑。 */
class SemanticObjectQueryConsumptionTest {
    @TempDir Path vault;
    String previousDir,previousKey;
    final UUID release=UUID.randomUUID();
    final String base="http://or.test",code="semantic_query_task_1234",key="ontopa_"+"a".repeat(43);
    @BeforeEach void actor(){
        previousDir=System.getProperty("agent.secret.dir");previousKey=System.getProperty("agent.master.key");
        System.setProperty("agent.secret.dir",vault.toString());System.setProperty("agent.master.key","cr11-test-only-master-key-0123456789");
        var jwt=Jwt.withTokenValue("verified-test").header("alg","HS256").subject("alice").claim("tenant_id","tenant")
                .claim("roles",List.of("ADMIN","EDITOR")).claim("attributes",Map.of("region",List.of("A"))).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();
        if(previousDir==null)System.clearProperty("agent.secret.dir");else System.setProperty("agent.secret.dir",previousDir);
        if(previousKey==null)System.clearProperty("agent.master.key");else System.setProperty("agent.master.key",previousKey);}
    Map<String,Object> reference(){return Map.of("spaceCode","test","releaseId",release.toString(),"version","v1","contentDigest","a".repeat(64));}
    Map<String,Object> manifest(){
        var tool=new LinkedHashMap<String,Object>();tool.put("tool_code",code);tool.put("name","标准查询");tool.put("description","固定版本有界查询");
        tool.put("category","ontology_service");tool.put("adapter_type","HTTP");tool.put("invocation_kind","SYNC");tool.put("http_method","POST");
        tool.put("endpoint",base+"/onto/releases/"+release+"/semantic/objects/query");tool.put("ontology_meta",reference());
        tool.put("input_schema",Map.of("type","object","required",List.of("opCode","query"),"properties",Map.of("opCode",Map.of("const",code),"query",Map.of("type","object"))));
        var metaProperties=new LinkedHashMap<String,Object>();for(String field:reference().keySet())metaProperties.put(field,Map.of("type","string"));
        metaProperties.put("contentDigest",Map.of("type","string","pattern","^[0-9a-f]{64}$"));
        tool.put("output_schema",Map.of("type","object","required",List.of("code","data","meta"),"properties",Map.of("code",Map.of("const","OK"),"data",Map.of("type","object"),
                "meta",Map.of("type","object","required",List.of("spaceCode","releaseId","version","contentDigest"),"properties",metaProperties))));
        tool.put("guard_attrs",List.of("region"));tool.put("exec_attrs",Map.of("readonly_flag",true,"side_effect",false,"idempotent",true,"cancellable",false,"timeout_ms",10000,"max_rows",200));
        tool.put("risk_level","READ_ONLY");tool.put("error_semantics",List.of());tool.put("return_spec",Map.of("structuredPath","$.data","ontologyMetaPath","$.meta","fileRefPaths",List.of(),"modelSummary",Map.of("mode","TEMPLATE","tpl","读取完成")));
        return tool;
    }
    void identity(MockRestServiceServer server){
        server.expect(requestTo(base+"/onto/releases/"+release+"/semantic/identity"))
                .andExpect(header("X-API-Key",key)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(Json.write(Map.of("code","OK","data",Map.of("releaseId",release.toString(),"platformTenant","tenant","platformActor","alice",
                        "spaceId",UUID.randomUUID().toString(),"principalId",UUID.randomUUID().toString(),"credentialId",UUID.randomUUID().toString()))),MediaType.APPLICATION_JSON));
    }
    @Test @SuppressWarnings("unchecked") void importAndExecuteThroughExistingPipeline(){
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var ref=SecretVault.put(key);var binding=Json.write(List.of(Map.of("tenantId","tenant","principalId","alice","releaseId",release.toString(),"secretRef",ref)));
        var transport=new OntologySemanticTransport(new OntologyConsumeIdentity(binding,(secret,actor)->SecretVault.resolve(secret,actor)),base,builder.build());
        var client=new SemanticObjectClient(transport,base);var semantic=new SemanticObjectQuerySkills(client);
        var registry=new AdapterRegistry(List.of(new HttpSyncExecutionAdapter(),new HttpAsyncExecutionAdapter(),semantic));
        var catalog=new SkillCatalog(null,null,null,"http://app.test",false,true,"",registry){
            @Override public Map<String,Object> create(Map<String,Object> body){return normalize(body);}};
        var imports=new OntologyManifestImports(catalog);imports.semanticObjects(client);
        identity(server);server.expect(requestTo(base+"/onto/releases/"+release+"/semantic/objects/capabilities"))
                .andRespond(withSuccess(Json.write(Map.of("code","OK","data",Map.of("releaseId",release.toString(),"manifests",List.of(manifest())))),MediaType.APPLICATION_JSON));
        identity(server);server.expect(requestTo(base+"/onto/releases/"+release+"/semantic/objects/query"))
                .andExpect(header("X-API-Key",key)).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().json(Json.write(Map.of("opCode",code,"query",Map.of("limit",5)))))
                .andRespond(withSuccess(Json.write(Map.of("code","OK","data",Map.of("releaseRef",reference(),"rows",List.of(Map.of("objectId","task:1","name","ok"))))),MediaType.APPLICATION_JSON));
        var result=imports.importPublishedQueries(Map.of("releaseId",release.toString(),"authRef",ref,"opCodes",List.of(code)));
        var contract=((List<Map<String,Object>>)result.get("drafts")).getFirst();
        assertSame(semantic,registry.dispatch(contract));
        var host=new SkillAdapters(null,null,null,null,registry,"DEGRADED",8192);
        var output=host.invoke(contract,Map.of("opCode",code,"query",Map.of("limit",5)),"key",1,false,"run",1);
        assertEquals("task:1",output.structured().path("rows").get(0).path("objectId").asText());
        assertEquals(release.toString(),output.ontologyMeta().path("releaseId").asText());server.verify();
    }
    @Test void dispatchDoesNotDependOnBeanOrder(){
        var semantic=new SemanticObjectQuerySkills(null);var http=new HttpSyncExecutionAdapter();var async=new HttpAsyncExecutionAdapter();
        for(var units:List.of(List.<SkillAdapter>of(http,semantic,async),List.<SkillAdapter>of(semantic,async,http))){
            var registry=new AdapterRegistry(units);var contract=Map.<String,Object>of("type","HTTP","endpoint",base+"/onto/releases/"+release+"/semantic/objects/join");
            assertSame(semantic,registry.dispatch(contract));assertSame(http,registry.dispatch(Map.of("type","HTTP","endpoint","http://old.test/read")));
        }
    }
    @Test void operationAndReleaseCannotBeChangedByArguments(){
        var adapter=new SemanticObjectQuerySkills(null);var contract=Map.<String,Object>of("type","HTTP","endpoint",base+"/onto/releases/"+release+"/semantic/objects/query",
                "importSource",Map.of("releaseId",release.toString(),"toolCode",code));
        assertThrows(SkillAdapters.Failure.class,()->adapter.invoke(null,contract,Map.of("opCode","other","query",Map.of()),"k",1,false,"r",1));
    }
}
