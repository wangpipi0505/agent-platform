package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.PluginGovernance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 插件治理基础 API（接口稿 §5.6；主方案 §6.2）。ADMIN 面：来源登记/列表、发现触发（202 QUEUED）
 * 与结果查询、版本启停（disable 带 reason）与授权/撤权。GUIDE 双轨走既有 /admin/skills 链路，
 * 不在本控制器。发现执行器（认领/接管/批次串行发布）属 A3-5，本控制器只暴露数据面骨架。
 */
@RestController
public class PluginGovernanceController {
    private final PluginGovernance governance;

    public PluginGovernanceController(PluginGovernance governance) {
        this.governance = governance;
    }

    @PostMapping("/admin/plugin-sources")
    public Object create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.createSource(body));
    }

    @GetMapping("/admin/plugin-sources")
    public Object sources() {
        return ApiResponse.ok(governance.listSources());
    }

    /** 发现受理（异步）：202 承载 {discoveryId,status:'QUEUED'}，执行与状态推进属 A3-5。 */
    @PostMapping("/admin/plugin-sources/{id}/discover")
    public ResponseEntity<Object> discover(@PathVariable long id) {
        return ResponseEntity.accepted().body(ApiResponse.ok(governance.triggerDiscovery(id)));
    }

    @GetMapping("/admin/plugin-sources/{id}/discoveries/{discoveryId}")
    public Object discovery(@PathVariable long id, @PathVariable String discoveryId) {
        return ApiResponse.ok(governance.discovery(id, discoveryId));
    }

    @GetMapping("/admin/plugins")
    public Object plugins(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long source) {
        return ApiResponse.ok(governance.listPlugins(status, source));
    }

    @PostMapping("/admin/plugins/{versionId}/enable")
    public Object enable(@PathVariable long versionId) {
        return ApiResponse.ok(governance.enableVersion(versionId));
    }

    @PostMapping("/admin/plugins/{versionId}/disable")
    public Object disable(@PathVariable long versionId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.disableVersion(versionId, body));
    }

    @PostMapping("/admin/plugins/{versionId}/grants")
    public Object grants(@PathVariable long versionId, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.grant(versionId, body));
    }

    @DeleteMapping("/admin/plugins/grants/{grantId}")
    public Object revoke(
            @PathVariable String grantId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(governance.revoke(grantId, body));
    }
}
