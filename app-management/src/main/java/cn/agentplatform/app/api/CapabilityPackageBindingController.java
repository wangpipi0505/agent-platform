package cn.agentplatform.app.api;

import cn.agentplatform.app.application.CapabilityPackageBindingService;
import cn.agentplatform.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CapabilityPackageBindingController {
    private final CapabilityPackageBindingService bindings;
    public CapabilityPackageBindingController(CapabilityPackageBindingService bindings) { this.bindings = bindings; }

    @PostMapping(path = "/admin/capability-packages/{id}/bindings/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object resolve(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(bindings.resolve(id, body));
    }
}
