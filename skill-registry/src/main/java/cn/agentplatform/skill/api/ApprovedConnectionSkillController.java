package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.ApprovedConnectionResolver;

import org.springframework.web.bind.annotation.*;

/**
 * 受控连接解析的内部入口：{@code GET /internal/skills/{code}/approved-connections/{profileId}/versions/{rev}}。
 *
 * 只给内部调用方使用（服务票据 + 路由守卫），不做 ADMIN 判定；返回前已经把解析摘要写进回执表，
 * 因此调用方拿到的每一份事实都能在 skill 侧被追溯。
 */
@RestController
public class ApprovedConnectionSkillController {

    private final ApprovedConnectionResolver resolver;

    public ApprovedConnectionSkillController(ApprovedConnectionResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/internal/skills/{code}/approved-connections/{profileId}/versions/{profileRev}")
    public Object resolve(@PathVariable String code, @PathVariable String profileId, @PathVariable int profileRev,
            @RequestParam(name = "environment", required = false) String environment) {
        return ApiResponse.ok(resolver.resolve(code, profileId, profileRev, environment));
    }
}
