package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IdentityMappingService;
import cn.agentplatform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前主体的登记/读取；不接收 tenant、principal、角色或外部 subject 的自报字段。 */
@RestController
public class IdentityMappingController {
    private final IdentityMappingService mappings;
    public IdentityMappingController(IdentityMappingService mappings) { this.mappings = mappings; }

    @PutMapping("/admin/system/identity-mapping/current")
    public Object registerCurrent() { return ApiResponse.ok(mappings.registerCurrent()); }

    @GetMapping("/internal/identity-mappings/current")
    public Object current() { return ApiResponse.ok(mappings.current()); }
}
