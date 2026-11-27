package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.infra.PortalRuntimeAccess;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

/** A3-4（接口稿 §5.4）：跨会话记忆五条端点——列表/单条/显式保存/CAS 更新+审批/撤销。 */
@RestController
public class MemoryController {
    private final Memories memories;
    private PortalRuntimeAccess runtime;

    public MemoryController(Memories memories) {
        this.memories = memories;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void runtime(PortalRuntimeAccess access) {
        this.runtime = access;
    }

    private PortalRuntimeAccess.Grant requireView(String code) {
        if (runtime == null) return null;
        var grant = runtime.current();
        grant.requireView(code);
        return grant;
    }

    /** 列表：scope/status/tags/q/sessionId 过滤+分页；先权限过滤再检索；列表不含正文。 */
    @GetMapping("/v1/apps/{code}/memories")
    public Object list(
            @PathVariable String code,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        return ApiResponse.ok(
                memories.list(code, scope, status, tags, q, sessionId, page, pageSize, Actor.current()));
    }

    /** 单条：先核验主体/智能体/条目归属及读范围，返回含正文（策略版本段：可带 sessionId）。 */
    @GetMapping("/v1/apps/{code}/memories/{memoryId}")
    public Object get(
            @PathVariable String code,
            @PathVariable String memoryId,
            @RequestParam(required = false) String sessionId) {
        requireView(code);
        return ApiResponse.ok(memories.get(code, memoryId, sessionId, Actor.current()));
    }

    /** 显式保存：requestId 幂等；携带 sessionId 核验会话归属并按钉住版本判写范围→ACTIVE。 */
    @PostMapping("/v1/apps/{code}/memories")
    public Object create(@PathVariable String code, @RequestBody JsonNode body) {
        return ApiResponse.ok(memories.create(code, body, Actor.current()));
    }

    /** 更新/审批：expectedVersion CAS（409 MEMORY_VERSION_CONFLICT）；status 增量即 DRAFT 审批。 */
    @PatchMapping("/v1/apps/{code}/memories/{memoryId}")
    public Object patch(
            @PathVariable String code, @PathVariable String memoryId, @RequestBody JsonNode body) {
        var grant = requireView(code);
        var actor = Actor.current();
        boolean admin = grant == null ? actor.roles().contains("ADMIN") : grant.rolesFor(code).contains("ADMIN");
        return ApiResponse.ok(memories.patch(code, memoryId, body, actor, admin));
    }

    /** 删除：先置 REVOKED 立即退出检索，异步清理正文；幂等（重复删除返回原终态）。 */
    @DeleteMapping("/v1/apps/{code}/memories/{memoryId}")
    public Object delete(
            @PathVariable String code,
            @PathVariable String memoryId,
            @RequestBody(required = false) JsonNode body) {
        var grant = requireView(code);
        var actor = Actor.current();
        return ApiResponse.ok(memories.delete(code, memoryId, body, grant == null ? actor : grant.actorFor(actor, code)));
    }
}
