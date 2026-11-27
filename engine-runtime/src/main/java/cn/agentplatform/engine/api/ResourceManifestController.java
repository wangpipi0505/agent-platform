package cn.agentplatform.engine.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.engine.infra.PortalRuntimeAccess;

import org.springframework.web.bind.annotation.*;

/** A3-8：轻量装载资源清单端点（接口稿 §5.7，1109 §5.3）。 */
@RestController
public class ResourceManifestController {

  private final cn.agentplatform.engine.application.ResourceManifestService manifest;
  private PortalRuntimeAccess runtime;

  public ResourceManifestController(cn.agentplatform.engine.application.ResourceManifestService manifest) {
    this.manifest = manifest;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void runtime(PortalRuntimeAccess access) {
    this.runtime = access;
  }

  /** GET /v1/deploy/resource-manifest?releaseDigest=&environment=（BUILDER/OPERATOR/ADMIN）。 */
  @GetMapping("/v1/deploy/resource-manifest")
  public Object manifest(
          @RequestParam("appCode") String appCode,
          @RequestParam(value = "releaseDigest", required = false) String releaseDigest,
          @RequestParam(value = "environment", required = false) String environment) {
    var actor = Actor.current();
    if (runtime != null) actor = runtime.current().actorFor(actor, appCode);
    return ApiResponse.ok(manifest.manifest(appCode, releaseDigest, environment, actor));
  }
}
