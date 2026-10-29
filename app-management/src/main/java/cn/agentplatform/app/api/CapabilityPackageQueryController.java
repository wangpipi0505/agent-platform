package cn.agentplatform.app.api;

import cn.agentplatform.app.application.CapabilityPackageQueryService;
import cn.agentplatform.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 管理目录只读入口；身份和租户仍由服务端已验签上下文决定。 */
@RestController
public class CapabilityPackageQueryController {
    private final CapabilityPackageQueryService packages;
    public CapabilityPackageQueryController(CapabilityPackageQueryService packages) { this.packages = packages; }

    @GetMapping("/admin/capability-packages")
    public Object list(@RequestParam Map<String, String> filters) { return ApiResponse.ok(packages.list(filters)); }

    @GetMapping("/admin/capability-packages/{id}")
    public Object detail(@PathVariable long id) { return ApiResponse.ok(packages.detail(id)); }
}
