package cn.agentplatform.app.api;

import cn.agentplatform.app.application.AppService;
import cn.agentplatform.common.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 发布门禁的显式豁免入口：仅 ADMIN 可授予与撤销，路径固定在应用资源下便于审计归属。
 * 豁免是本项目唯一允许"带欠缺发布"的通道，因此鉴权放在最前，早于任何 JSON 校验。
 */
@RestController
@RequestMapping("/admin/apps/{code}/publish-waivers")
public class PublishWaiverController {
    private final AppService apps;

    public PublishWaiverController(AppService apps) {
        this.apps = apps;
    }

    /** requireRole("ADMIN") 在 admin-common 中已实现为"必须持有 ADMIN"，非 ADMIN 一律 403。 */
    private void admin() {
        Actor.current().requireRole("ADMIN");
    }

    @PostMapping
    public Object create(@PathVariable String code, @RequestBody Map<String, Object> body) {
        admin();
        return ApiResponse.ok(apps.createWaiver(code, body));
    }

    @DeleteMapping({"", "/{waiverId}"})
    public Object revoke(
            @PathVariable String code,
            @PathVariable(required = false) String waiverId,
            @RequestBody(required = false) Map<String, Object> body) {
        admin();
        String id =
                waiverId != null
                        ? waiverId
                        : body == null
                                ? ""
                                : Objects.toString(body.getOrDefault("waiverId", ""), "");
        return ApiResponse.ok(apps.revokeWaiver(code, id));
    }
}
