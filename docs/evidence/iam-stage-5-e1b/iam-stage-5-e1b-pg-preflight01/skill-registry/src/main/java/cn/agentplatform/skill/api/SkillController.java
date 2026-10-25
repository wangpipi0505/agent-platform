package cn.agentplatform.skill.api;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class SkillController {
    private final SkillCatalog catalog;
    private final SkillExecution execution;
    private final SkillGovernance governance;

    public SkillController(
            SkillCatalog catalog, SkillExecution execution, SkillGovernance governance) {
        this.catalog = catalog;
        this.execution = execution;
        this.governance = governance;
    }

    private void edit() {
        Actor.current().requireRole("EDITOR");
    }

    /** 公共管理回包仅显示已配置标记，opaque secret_ref也不成为编辑者可任意转接的能力票据。 */
    private Object publicView(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                String key = entry.getKey().toString();
                if (key.equals("authRef") || key.equals("secretRef"))
                    result.put(
                            key.equals("authRef") ? "authConfigured" : "secretConfigured",
                            entry.getValue() != null && !entry.getValue().toString().isBlank());
                else result.put(key, publicView(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection<?> list)
            return list.stream().map(this::publicView).toList();
        return value;
    }

    @GetMapping("/admin/skills")
    public Object list() {
        return ApiResponse.ok(publicView(catalog.list()));
    }

    @PostMapping("/admin/skills")
    public Object create(@RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(publicView(catalog.create(b)));
    }

    @GetMapping("/admin/skills/{code}")
    public Object get(@PathVariable String code) {
        return ApiResponse.ok(publicView(catalog.get(code, null)));
    }

    @PutMapping({"/admin/skills/{code}", "/admin/skills/{code}/draft"})
    public Object save(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(publicView(catalog.save(code, b)));
    }

    @DeleteMapping("/admin/skills/{code}")
    public Object delete(@PathVariable String code) {
        edit();
        catalog.delete(code);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/admin/skills/{code}/versions")
    public Object versions(@PathVariable String code) {
        return ApiResponse.ok(publicView(catalog.versions(code)));
    }

    @PostMapping({
        "/admin/skills/{code}/versions/{version}/publish",
        "/admin/skills/{code}/drafts/{version}/publish"
    })
    public Object publish(@PathVariable String code, @PathVariable int version) {
        edit();
        return ApiResponse.ok(publicView(catalog.publish(code, version)));
    }

    @PostMapping("/admin/skills/{code}/drafts/{version}/connection-test")
    public Object connection(
            @PathVariable String code,
            @PathVariable int version,
            @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(governance.connection(code, version, b));
    }

    @PostMapping("/admin/skills/{code}/drafts/{version}/acceptance")
    public Object acceptance(
            @PathVariable String code,
            @PathVariable int version,
            @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(governance.acceptance(code, version, b));
    }

    @PostMapping("/admin/skills/{code}/drafts/{version}/mcp-tools")
    public Object mcpTools(@PathVariable String code, @PathVariable int version) {
        edit();
        return ApiResponse.ok(catalog.mcpTools(code, version));
    }

    @PostMapping("/admin/skills/{code}/disable")
    public Object disable(@PathVariable String code) {
        edit();
        return ApiResponse.ok(publicView(catalog.disable(code)));
    }

    @GetMapping("/admin/skills/{code}/referenced-by")
    public Object references(@PathVariable String code) {
        return ApiResponse.ok(catalog.references(code));
    }

    @GetMapping("/admin/skills/{code}/stats")
    public Object stats(
            @PathVariable String code,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "day") String groupBy,
            @RequestParam(required = false) Integer version) {
        return ApiResponse.ok(execution.statistics(code, from, to, groupBy, version));
    }

    @GetMapping("/internal/statistics")
    public Object statistics(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "day") String groupBy,
            @RequestParam(required = false) Integer version) {
        Actor.current().requireRole("SERVICE");
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(execution.statistics(code, from, to, groupBy, version));
    }

    @GetMapping("/internal/skills/{code}")
    public Object internal(
            @PathVariable String code, @RequestParam(required = false) Integer version) {
        Actor.current().requireRole("SERVICE");
        return ApiResponse.ok(catalog.get(code, version));
    }

    @GetMapping("/internal/skill-versions/{id}")
    public Object internalVersion(@PathVariable long id) {
        Actor.current().requireRole("SERVICE");
        return ApiResponse.ok(catalog.versionId(id));
    }

    @PostMapping("/internal/skills/{code}/execute")
    public Object execute(@PathVariable String code, @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(execution.execute(code, b));
    }

    @GetMapping("/internal/skill-facts/{callId}")
    public Object proof(@PathVariable long callId) {
        return ApiResponse.ok(execution.proof(callId));
    }

    @PostMapping("/internal/skill-facts/{callId}/reconcile")
    public Object reconcile(@PathVariable long callId) {
        return ApiResponse.ok(execution.reconcile(callId));
    }

    @PostMapping("/internal/skill-facts/{callId}/retry")
    public Object retry(@PathVariable long callId) {
        return ApiResponse.ok(execution.retry(callId));
    }

    @PostMapping("/internal/skill-facts/{callId}/reproduce-artifacts")
    public Object reproduceArtifacts(
            @PathVariable long callId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(execution.reproduceArtifacts(callId, body));
    }
}
