package cn.agentplatform.app.api;

import cn.agentplatform.app.application.PlatformAccessService;
import cn.agentplatform.app.application.WorkspaceManifests;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.authority.AuthorityProperties;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工作区 manifest（A3-1，接口稿 §5.5）：GET/PUT /admin/apps/{code}/manifest 与
 * GET /admin/apps/{code}/manifests。
 *
 * PUT 请求 {manifest, baseRevision, requestId}——baseRevision 过期 409 MANIFEST_REVISION_CONFLICT；
 * 响应 {revision, digest, validation:{ok, issues}}。修订链不可变，保存即追加。
 */
@RestController
@RequestMapping("/admin/apps/{code}")
public class WorkspaceManifestController {

    private final WorkspaceManifests manifests;
    private final AuthorityProperties authority;
    private final PlatformAccessService access;

    public WorkspaceManifestController(WorkspaceManifests manifests, AuthorityProperties authority,
                                       PlatformAccessService access) {
        this.manifests = manifests;
        this.authority = authority;
        this.access = access;
    }

    private Actor builder(String code) {
        var actor = Actor.current();
        if ("iam".equals(authority.mode())) access.require(actor, PlatformAccessService.BUILD, code);
        else actor.requireRole("EDITOR");
        return actor;
    }

    @GetMapping("/manifest")
    public Object get(@PathVariable String code, @RequestParam(defaultValue = "false") boolean allowMissing) {
        return ApiResponse.ok(manifests.get(builder(code), code, allowMissing));
    }

    @PutMapping("/manifest")
    public Object put(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(manifests.put(builder(code), code, body));
    }

    @GetMapping("/manifests")
    public Object versions(@PathVariable String code) {
        return ApiResponse.ok(manifests.versions(builder(code), code));
    }
}
