package cn.agentplatform.app.api;

import cn.agentplatform.app.application.WorkspaceManifests;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

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

    public WorkspaceManifestController(WorkspaceManifests manifests) {
        this.manifests = manifests;
    }

    /** requireRole("EDITOR")：工作区编辑面（BUILDER=现 EDITOR 口径，ADMIN 放行），先鉴权再看请求体。 */
    private void builder() {
        Actor.current().requireRole("EDITOR");
    }

    @GetMapping("/manifest")
    public Object get(@PathVariable String code) {
        builder();
        return ApiResponse.ok(manifests.get(Actor.current(), code));
    }

    @PutMapping("/manifest")
    public Object put(@PathVariable String code, @RequestBody Map<String, Object> body) {
        builder();
        return ApiResponse.ok(manifests.put(Actor.current(), code, body));
    }

    @GetMapping("/manifests")
    public Object versions(@PathVariable String code) {
        builder();
        return ApiResponse.ok(manifests.versions(Actor.current(), code));
    }
}
