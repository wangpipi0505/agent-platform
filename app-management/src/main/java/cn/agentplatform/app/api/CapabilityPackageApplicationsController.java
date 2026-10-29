package cn.agentplatform.app.api;

import cn.agentplatform.app.application.CapabilityPackageApplicationsService;
import cn.agentplatform.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CapabilityPackageApplicationsController {
    private final CapabilityPackageApplicationsService applications;
    public CapabilityPackageApplicationsController(CapabilityPackageApplicationsService applications) {
        this.applications = applications;
    }
    @PostMapping(path = "/admin/capability-packages/{id}/applications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object create(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(applications.create(id, body));
    }
}
