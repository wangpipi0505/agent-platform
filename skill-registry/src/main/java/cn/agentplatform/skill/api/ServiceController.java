package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.ServiceLifecycle;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AP-S05 服务生命周期管理面。路径独立于 /admin/skills，避免和技能编码冲突；
 * 具体服务 URL 和日志仍由后端解析，前端不能提交任意路径。
 */
@RestController
public class ServiceController {
    private final ServiceLifecycle services;

    public ServiceController(ServiceLifecycle services) {
        this.services = services;
    }

    @GetMapping("/admin/services")
    public Object list() {
        return ApiResponse.ok(services.list());
    }

    @GetMapping("/admin/services/{code}")
    public Object get(@PathVariable String code) {
        return ApiResponse.ok(services.get(code));
    }

    @PostMapping("/admin/services")
    public Object create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(services.create(body));
    }

    @PutMapping("/admin/services/{code}")
    public Object update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(services.update(code, body));
    }

    @DeleteMapping("/admin/services/{code}")
    public Object delete(
            @PathVariable String code,
            @RequestParam(defaultValue = "") String confirm) {
        return ApiResponse.ok(services.delete(code, code.equals(confirm)));
    }

    @PostMapping("/admin/services/{code}/start")
    public Object start(@PathVariable String code) {
        return ApiResponse.ok(services.start(code));
    }

    @PostMapping("/admin/services/{code}/stop")
    public Object stop(
            @PathVariable String code,
            @RequestParam(defaultValue = "") String confirm) {
        return ApiResponse.ok(services.stop(code, code.equals(confirm)));
    }

    @PostMapping("/admin/services/{code}/restart")
    public Object restart(
            @PathVariable String code,
            @RequestParam(defaultValue = "") String confirm) {
        return ApiResponse.ok(services.restart(code, code.equals(confirm)));
    }

    @GetMapping("/admin/services/{code}/health")
    public Object health(@PathVariable String code) {
        return ApiResponse.ok(services.health(code));
    }

    @GetMapping("/admin/services/{code}/logs")
    public Object logs(
            @PathVariable String code,
            @RequestParam(defaultValue = "200") int lines) {
        return ApiResponse.ok(services.logs(code, lines));
    }
}
