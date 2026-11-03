package cn.agentplatform.app.api;

import cn.agentplatform.app.application.AssistantExperiences;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * ADMIN 体验描述目录（INT-03）。契约见 contracts/configuration-profile.md §2。
 *
 * 这里只维护"应用怎么说自己"：标题、欢迎语、能力说明、展示形态与提示词引用。不提供发布入口，
 * 也不读写 app_version：对外可见由配置发布（config release）决定，避免体验文案绕过发布门禁。
 * 修订只能追加，因此没有 PUT/PATCH；批准与停用都要带 expectedRev 做 CAS。
 */
@RestController
@RequestMapping("/admin/apps/{code}/experiences")
public class AssistantExperienceController {

    private final AssistantExperiences experiences;

    public AssistantExperienceController(AssistantExperiences experiences) {
        this.experiences = experiences;
    }

    /** requireRole("ADMIN")：体验描述决定用户看到什么，先鉴权再看请求体。 */
    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object get(@PathVariable String code) {
        admin();
        return ApiResponse.ok(experiences.get(code));
    }

    @PostMapping
    public Object create(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(experiences.create(code, body));
    }

    @PostMapping("/versions")
    public Object revise(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(experiences.revise(code, body));
    }

    @PostMapping("/approve")
    public Object approve(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(experiences.approve(code, body));
    }

    @PostMapping("/disable")
    public Object disable(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(experiences.disable(code, body));
    }
}
