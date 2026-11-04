package cn.agentplatform.app.api;

import cn.agentplatform.app.application.OnboardingTemplates;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ONB-01/02：业务接入模板目录与服务端预检；全部入口只对管理员开放。 */
@RestController
@RequestMapping("/admin/onboarding-templates")
public class OnboardingController {

    private final OnboardingTemplates templates;

    public OnboardingController(OnboardingTemplates templates) {
        this.templates = templates;
    }

    @GetMapping
    public Object list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String kind) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.list(q, kind));
    }

    @GetMapping("/{code}")
    public Object get(@PathVariable String code) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.get(code));
    }

    @PostMapping
    public Object create(@RequestBody Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.create(body));
    }

    @PostMapping("/{code}/versions")
    public Object revise(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.revise(code, body));
    }

    @PostMapping("/{code}/publish")
    public Object publish(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.publish(code, body));
    }

    @PostMapping("/{code}/disable")
    public Object disable(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.disable(code, body));
    }

    @PostMapping("/{code}/versions/{version}/preflight")
    public Object preflight(@PathVariable String code, @PathVariable int version,
            @RequestBody Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(templates.preflight(code, version, body));
    }
}
