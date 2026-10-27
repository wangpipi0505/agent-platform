package cn.agentplatform.skill.api;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.SkillCatalog;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * T10 受控证据重绑定端点（PLAT-03/10）。
 *
 * <p>这是门户面 /admin/** 的管理动作，只允许 ADMIN：它把“别的版本已通过的证据”变成当前草稿的
 * 发布资格，等价于管理台对“语义未变”的人工担保，普通编辑者不能自行完成。之所以不放进
 * {@link SkillController}（那个控制器的写操作是 EDITOR），是为了让鉴权边界一眼可见，避免将来给
 * 编辑端点加角色时误把这个动作也放宽。
 *
 * <p>不需要在 admin-service-authority.json 增加路由：该文件登记的是服务票据可访问的能力，
 * 本端点只走用户 JWT 的 ADMIN 角色。
 */
@RestController
public class SkillEvidenceController {
    private final SkillCatalog catalog;

    public SkillEvidenceController(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/admin/skills/{code}/versions/{version}/rebind-evidence")
    public Object rebind(
            @PathVariable String code,
            @PathVariable int version,
            @RequestBody Map<String, Object> body) {
        // 默认拒绝：先验角色再解析任何请求字段，避免未授权调用探测版本/摘要是否存在。
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(catalog.rebindEvidence(code, version, body));
    }
}
