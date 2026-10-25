package cn.agentplatform.app.api;

import cn.agentplatform.app.application.IntegrationService;
import cn.agentplatform.common.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class IntegrationController {
    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping("/admin/apps/{code}/integration")
    public Object list(@PathVariable String code) {
        Actor.current().requireRole("ADMIN");
        return ApiResponse.ok(service.list(code));
    }

    @PostMapping("/admin/apps/{code}/integration/credentials")
    public Object issue(@PathVariable String code, @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(service.issue(code, b));
    }

    @PostMapping("/admin/apps/{code}/integration/credentials/{biz}/revoke")
    public Object revoke(@PathVariable String code, @PathVariable String biz) {
        return ApiResponse.ok(service.revoke(code, biz));
    }

    @PutMapping("/admin/apps/{code}/integration/credentials/{biz}")
    public Object policy(
            @PathVariable String code,
            @PathVariable String biz,
            @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(service.policy(code, biz, b));
    }

    @GetMapping("/internal/integration-origins")
    public Object origins(@RequestParam String origin) {
        return ApiResponse.ok(service.origins(origin));
    }

    @GetMapping("/internal/integration-credentials/{id}")
    public Object authenticate(@PathVariable long id) {
        return ApiResponse.ok(service.authenticate(id));
    }

    @GetMapping("/internal/integration/{code}/{biz}")
    public Object get(@PathVariable String code, @PathVariable String biz) {
        Actor.current().requireRole("SERVICE");
        return ApiResponse.ok(service.get(code, biz, true));
    }
}
