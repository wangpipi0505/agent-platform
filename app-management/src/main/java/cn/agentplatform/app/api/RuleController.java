package cn.agentplatform.app.api;

import cn.agentplatform.app.application.RuleAssets;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 规则版本链与启停（A3-3，接口稿 §5.5）：PUT 草稿、POST 发布（发布预检同 scope ENFORCED 互斥，
 * 冲突输出规则对并阻断）、GET 版本链、POST 启停。
 *
 * {code} 是规则编码（区别于 /admin/apps/{code} 里的应用编码）。
 */
@RestController
@RequestMapping("/admin/rules/{code}")
public class RuleController {

    private final RuleAssets rules;

    public RuleController(RuleAssets rules) {
        this.rules = rules;
    }

    /** requireRole("ADMIN")：发布与启停改变运行时约束，先鉴权再看请求体。 */
    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @PutMapping("/draft")
    public Object saveDraft(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(rules.saveDraft(Actor.current(), code, body));
    }

    @PostMapping("/versions/{versionNo}/publish")
    public Object publish(@PathVariable String code, @PathVariable int versionNo,
            @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(rules.publish(Actor.current(), code, versionNo, body));
    }

    @GetMapping("/versions")
    public Object versions(@PathVariable String code) {
        admin();
        return ApiResponse.ok(rules.versions(Actor.current(), code));
    }

    @PostMapping("/{action:disable|enable}")
    public Object change(@PathVariable String code, @PathVariable String action,
            @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(rules.change(Actor.current(), code, action, body));
    }
}
