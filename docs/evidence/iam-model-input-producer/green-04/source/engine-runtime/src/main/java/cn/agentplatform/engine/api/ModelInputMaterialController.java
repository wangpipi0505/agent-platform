package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.engine.application.ModelInputMaterials;
import cn.agentplatform.engine.domain.ModelInputManifest;
import cn.agentplatform.engine.infra.ModelInputMaterialStore;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Authenticated reusable metadata; no request payload and no first-send authorization. */
@RestController
public final class ModelInputMaterialController {
 private final ModelInputMaterials materials;
 public ModelInputMaterialController(ModelInputMaterials materials){this.materials=materials;}
 @GetMapping("/internal/material-scopes/{kind}/{id}")
 public ApiResponse<ModelInputManifest> manifest(@PathVariable String kind,@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
  var parameters=request.getParameterMap();if(!"model-input".equals(kind)||parameters.size()!=1||!parameters.containsKey("version")||parameters.get("version").length!=1)throw new PlatformException(400,"MATERIAL_SELECTOR_INVALID","材料选择器无效");
  String version=parameters.get("version")[0];ModelInputMaterialStore.selector(id,"m_");ModelInputMaterialStore.hash(version);
  var current=AuthorityContext.current();var proof=AuthorityContext.requireInitialFor(Actor.current(),current.call().resource());
  var manifest=materials.manifest(id,version,proof);ModelInputMaterialStore.exportPurpose(proof);
  var result=ApiResponse.ok(manifest);ModelInputMaterialStore.bounded(Json.tree(result),ModelInputMaterialStore.MANIFEST_BYTES);
  response.setHeader("X-Agent-Material-Ref","/internal/material-scopes/model-input/"+id+"?version="+version);response.setHeader("X-Agent-Material-Sha256",manifest.scope().scopeSha256());
  return result;
 }
}
