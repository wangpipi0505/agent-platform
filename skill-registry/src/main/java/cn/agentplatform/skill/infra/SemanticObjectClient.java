package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/** 正式查询manifest与执行共用CR03逐主体通路，不采纳调用参数中的端点或凭据。 */
@Component
public class SemanticObjectClient {
    private final OntologySemanticTransport transport;
    private final String base;
    public SemanticObjectClient(OntologySemanticTransport transport,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String base){this.transport=transport;this.base=base.replaceAll("/+$","");}
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> manifests(UUID release){
        var result=transport.get(base+"/onto/releases/"+release+"/semantic/objects/capabilities");
        if(!release.toString().equals(result.path("releaseId").asText())||!result.path("manifests").isArray()||result.path("manifests").size()>64)throw malformed();
        var manifests=new ArrayList<Map<String,Object>>();for(JsonNode item:result.path("manifests")){
            if(!item.isObject()||!release.toString().equals(item.path("ontology_meta").path("releaseId").asText())||item.path("ontology_meta").path("preview").asBoolean())throw malformed();
            manifests.add(Json.read(item.toString(),Map.class));
        }return List.copyOf(manifests);
    }
    public JsonNode execute(String endpoint,Map<String,Object> arguments){
        var result=transport.post(endpoint,arguments);
        var release=SemanticObjectQuerySkills.release(endpoint);
        JsonNode ref=result.path("releaseRef");
        if(release==null||!release.toString().equals(ref.path("releaseId").asText())
                ||ref.path("spaceCode").asText().isBlank()||ref.path("version").asText().isBlank()
                ||!ref.path("contentDigest").asText().matches("[0-9a-f]{64}"))throw malformed();
        return Json.tree(Map.of("code","OK","data",result,"meta",ref));
    }
    private static PlatformException malformed(){return new PlatformException(502,"DEPENDENCY_UNAVAILABLE","本体标准查询响应与正式版本不一致");}
}
