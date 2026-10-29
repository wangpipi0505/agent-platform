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
    private final FixtureSkills fixtures;

    /** 兼容既有测试构造；生产装配必须走四参构造注入 FixtureSkills，创建守卫才生效。 */
    public SkillController(
            SkillCatalog catalog, SkillExecution execution, SkillGovernance governance) {
        this(catalog, execution, governance, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SkillController(
            SkillCatalog catalog,
            SkillExecution execution,
            SkillGovernance governance,
            FixtureSkills fixtures) {
        this.catalog = catalog;
        this.execution = execution;
        this.governance = governance;
        this.fixtures = fixtures;
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
        // AP-S06：列表携带 source=FIXTURE|MANAGED，前端据此区分演示数据，不误标“可用”。
        return ApiResponse.ok(FixtureSkills.withSource(publicView(catalog.list())));
    }

    @PostMapping("/admin/skills")
    public Object create(@RequestBody Map<String, Object> b) {
        edit();
        // AP-S06：非演示模式禁止注入 fixture 命名约定的新技能（demo-mode 为唯一开关）。
        if (fixtures != null)
            fixtures.assertCreatable(java.util.Objects.toString(b.get("code"), ""));
        return ApiResponse.ok(FixtureSkills.withSource(publicView(catalog.create(b))));
    }

    @GetMapping("/admin/skills/{code}")
    public Object get(@PathVariable String code) {
        return ApiResponse.ok(FixtureSkills.withSource(publicView(catalog.get(code, null))));
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

    /** AP-S12：管理台服务接入卡片只探测已保存端点，不接受请求自带 URL，避免把探测变成任意网络访问。 */
    @PostMapping("/admin/skills/{code}/versions/{version}/service-probe")
    public Object serviceProbe(@PathVariable String code, @PathVariable int version) {
        edit();
        var definition = catalog.version(code, version);
        return ApiResponse.ok(
                ServiceProbe.probeOrigin(
                        Objects.toString(definition.get("endpoint"), ""),
                        Objects.toString(definition.get("httpMethod"), "")));
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
            @PathVariable String code, @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "false") boolean requireRegistered) {
        Actor.current().requireRole("SERVICE");
        return ApiResponse.ok(requireRegistered ? catalog.registeredVersion(code, version) : catalog.get(code, version));
    }

    public Object internal(String code, Integer version) {
        return internal(code, version, false);
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

    @PostMapping("/internal/skill-calls/{callId}/cancel")
    public Object cancelProcess(@PathVariable long callId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(execution.cancelProcess(callId, body));
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
