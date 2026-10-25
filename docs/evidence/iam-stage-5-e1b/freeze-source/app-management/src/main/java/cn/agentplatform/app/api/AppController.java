package cn.agentplatform.app.api;

import cn.agentplatform.app.application.*;
import cn.agentplatform.common.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class AppController {
    private final AppService apps;
    private final TemplateService templates;

    public AppController(AppService apps, TemplateService templates) {
        this.apps = apps;
        this.templates = templates;
    }

    private void edit() {
        Actor.current().requireRole("EDITOR");
    }

    @GetMapping("/admin/apps")
    public Object list(@RequestParam Map<String, String> filters) {
        return ApiResponse.ok(apps.list(filters));
    }

    @PostMapping("/admin/apps")
    public Object create(@RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.create(b));
    }

    @PostMapping("/admin/apps/{code}/copy")
    public Object copy(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.copy(code, b));
    }

    @GetMapping("/admin/apps/{code}/lifecycle-logs")
    public Object lifecycleLogs(@PathVariable String code) {
        return ApiResponse.ok(apps.lifecycleLogs(code));
    }

    @GetMapping("/admin/apps/{code}")
    public Object get(@PathVariable String code) {
        return ApiResponse.ok(apps.get(code));
    }

    @PutMapping("/admin/apps/{code}")
    public Object metadata(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.metadata(code, b));
    }

    @DeleteMapping("/admin/apps/{code}")
    public Object delete(@PathVariable String code) {
        edit();
        apps.delete(code);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PutMapping("/admin/apps/{code}/config")
    public Object config(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.save(code, b, false));
    }

    @GetMapping("/admin/flows/{code}")
    public Object flow(@PathVariable String code) {
        return ApiResponse.ok(apps.get(code));
    }

    @PutMapping("/admin/flows/{code}")
    public Object flowSave(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.save(code, b, true));
    }

    @GetMapping("/admin/apps/{code}/versions")
    public Object versions(@PathVariable String code) {
        return ApiResponse.ok(apps.versions(code));
    }

    @GetMapping("/admin/apps/{code}/versions/diff")
    public Object versionDiff(
            @PathVariable String code, @RequestParam int fromVersion, @RequestParam int toVersion) {
        return ApiResponse.ok(apps.versionDiff(code, fromVersion, toVersion));
    }

    @GetMapping("/admin/apps/{code}/versions/{versionNo}")
    public Object adminVersion(@PathVariable String code, @PathVariable int versionNo) {
        apps.get(code);
        return ApiResponse.ok(apps.version(code, versionNo));
    }

    @GetMapping("/v1/apps/{code}/input-schema")
    public Object inputSchema(
            @PathVariable String code, @RequestParam(required = false) Integer versionNo) {
        return ApiResponse.ok(apps.publishedInputs(code, versionNo));
    }

    @PostMapping("/admin/apps/{code}/publish/precheck")
    public Object precheck(@PathVariable String code) {
        edit();
        return ApiResponse.ok(apps.precheck(code));
    }

    @PostMapping("/admin/apps/{code}/publish/commit")
    public Object commit(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.commit(code, b));
    }

    @PostMapping("/admin/apps/{code}/{action:disable|enable|archive|rollback}")
    public Object lifecycle(
            @PathVariable String code,
            @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> b) {
        edit();
        return ApiResponse.ok(apps.lifecycle(code, action, b == null ? Map.of() : b));
    }

    @GetMapping("/internal/apps/{code}/candidate")
    public Object candidate(
            @PathVariable String code,
            @RequestParam long draftRev,
            @RequestParam String candidateSha256) {
        return ApiResponse.ok(apps.frozenCandidate(code, draftRev, candidateSha256));
    }

    @GetMapping("/internal/apps/{code}/versions/{version}")
    public Object version(@PathVariable String code, @PathVariable int version) {
        var app = apps.get(code);
        if (Set.of("DISABLED", "ARCHIVED").contains(app.get("status"))
                || app.get("currentVersionNo") == null)
            throw AppService.error(409, "APP_UNAVAILABLE", "应用未发布或已停用，不能新建运行");
        return ApiResponse.ok(apps.version(code, version));
    }

    @GetMapping("/internal/apps/{code}/snapshot")
    public Object snapshot(
            @PathVariable String code, @RequestParam(defaultValue = "false") boolean draft) {
        if (!draft) return ApiResponse.ok(apps.published(code));
        var a = apps.get(code);
        return ApiResponse.ok(
                apps.frozenCandidate(
                        code,
                        ((Number) a.get("draftRev")).longValue(),
                        a.get("candidateSha256").toString()));
    }

    @GetMapping("/internal/apps/{code}/published")
    public Object published(@PathVariable String code) {
        return ApiResponse.ok(apps.published(code));
    }

    @GetMapping("/internal/skills/{code}/references")
    public Object skillReferences(@PathVariable String code) {
        return ApiResponse.ok(apps.references(code));
    }

    @GetMapping("/admin/classifications")
    public Object classifications() {
        return ApiResponse.ok(templates.classifications());
    }

    @PostMapping("/admin/classifications")
    public Object classification(@RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(templates.classification(b));
    }

    @GetMapping("/internal/prompt-templates/{code}/versions/{version}")
    public Object templateVersion(@PathVariable String code, @PathVariable int version) {
        var v = templates.version(code, version);
        if (!"PUBLISHED".equals(v.get("status")))
            throw AppService.error(409, "TEMPLATE_UNPUBLISHED", "模板版本未发布");
        return ApiResponse.ok(v);
    }

    @GetMapping("/admin/prompt-templates")
    public Object templateList() {
        return ApiResponse.ok(templates.list());
    }

    @PostMapping("/admin/prompt-templates")
    public Object templateCreate(@RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(templates.create(b));
    }

    @PutMapping("/admin/prompt-templates/{code}")
    public Object templateRevise(@PathVariable String code, @RequestBody Map<String, Object> b) {
        edit();
        return ApiResponse.ok(templates.revise(code, b));
    }

    @GetMapping("/admin/prompt-templates/{code}/versions")
    public Object templateVersions(@PathVariable String code) {
        return ApiResponse.ok(templates.versions(code));
    }

    @PostMapping("/admin/prompt-templates/{code}/versions/{version}/publish")
    public Object templatePublish(@PathVariable String code, @PathVariable int version) {
        edit();
        return ApiResponse.ok(templates.publish(code, version));
    }

    @PostMapping("/admin/prompt-templates/{code}/versions/{version}/render")
    public Object templateRender(
            @PathVariable String code,
            @PathVariable int version,
            @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(templates.render(code, version, b));
    }
}
