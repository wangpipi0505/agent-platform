package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.OntologyConsumeIdentity;
import cn.agentplatform.skill.infra.OntologyManifestClient;
import cn.agentplatform.skill.infra.SemanticDefinitionClient;
import java.util.*;
import org.springframework.stereotype.Service;

/** 当前主体正式版本选择集；部署引用只是候选，每次读取仍由OR当前授权/READY/摘要复核。 */
@Service
public class SemanticCatalog {
    private final OntologyConsumeIdentity identities;
    private final OntologyManifestClient sources;
    private final SemanticDefinitionClient definitions;
    public SemanticCatalog(OntologyConsumeIdentity identities, OntologyManifestClient sources, SemanticDefinitionClient definitions) {
        this.identities=identities; this.sources=sources; this.definitions=definitions;
    }
    public Map<String,Object> list(Actor actor) {
        if (Collections.disjoint(actor.roles(), Set.of("VIEWER","EDITOR","APPROVER","ADMIN")))
            throw new PlatformException(403,"SYS-APP-4031","当前账号没有语义目录查看权限");
        var candidates=identities.releaseIds(actor).stream().sorted().toList();
        if(candidates.size()>16) throw new PlatformException(400,"SEMANTIC_FIELD_OUT_OF_RANGE","正式版本候选超过16项，请收窄部署的主体绑定");
        var releases=new ArrayList<Map<String,Object>>(); var errors=new ArrayList<Map<String,Object>>();
        for (var id:candidates) {
            try {
                var source=sources.resolve(id,null);
                var projection=definitions.query(id,Map.of("kinds",List.of("objects","actions","properties","relations","rules"),"limit",200));
                if(!source.toMap().get("releaseRef").equals(projection.releaseRef()))
                    throw new PlatformException(502,"SEMANTIC_DIGEST_MISMATCH","正式来源与定义版本不一致");
                var row=new LinkedHashMap<String,Object>(source.toMap());
                row.put("projection",projection.toMap());
                releases.add(row);
            } catch(PlatformException failure) {
                // 失败保留分项：撤权/不可达不冒充空目录，也不把旧缓存回填为可选版本。
                errors.add(Map.of("releaseId",id.toString(),"code",failure.code(),"message",failure.getMessage()));
            }
        }
        return Map.of("releases",releases,"errors",errors,"complete",errors.isEmpty());
    }
}
