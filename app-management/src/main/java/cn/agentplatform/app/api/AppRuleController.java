package cn.agentplatform.app.api;

import cn.agentplatform.app.application.PlatformAccessService;
import cn.agentplatform.app.application.RuleAssets;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.authority.AuthorityProperties;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 应用的规则目录（A3-3，接口稿 §5.5）：GET 列出 scope.app 等于该应用的规则，POST 新建规则草稿。
 *
 * 规则主档按应用维护（scope.app 由路径定格）；版本链与启停在 /admin/rules/{code} 下。
 */
@RestController
@RequestMapping("/admin/apps/{code}/rules")
public class AppRuleController {

    private final RuleAssets rules;
    private final AuthorityProperties authority;
    private final PlatformAccessService access;

    public AppRuleController(RuleAssets rules, AuthorityProperties authority, PlatformAccessService access) {
        this.rules = rules;
        this.authority = authority;
        this.access = access;
    }

    /** requireRole("ADMIN")：规则决定运行时行为边界，先鉴权再看请求体。 */
    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @GetMapping
    public Object list(@PathVariable String code) {
        var actor = Actor.current();
        if ("iam".equals(authority.mode())) access.require(actor, PlatformAccessService.BUILD, code);
        else actor.requireRole("ADMIN");
        return ApiResponse.ok(rules.list(actor, code));
    }

    @PostMapping
    public Object create(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(rules.create(Actor.current(), code, body));
    }
}
