package cn.agentplatform.app.api;

import cn.agentplatform.app.application.CapabilityPackageService;
import cn.agentplatform.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CapabilityPackageController {
    private final CapabilityPackageService packages;

    public CapabilityPackageController(CapabilityPackageService packages) {
        this.packages = packages;
    }

    @PostMapping(path = "/admin/capability-packages/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object importPackage(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(packages.importPackage(body));
    }
}
