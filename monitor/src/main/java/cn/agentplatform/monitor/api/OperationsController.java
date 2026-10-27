package cn.agentplatform.monitor.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.monitor.application.OperationsService;

import org.springframework.web.bind.annotation.*;

@RestController
public class OperationsController {
    private final OperationsService operations;

    public OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/admin/operations/overview")
    public Object overview(@RequestParam(defaultValue = "60") int windowMinutes) {
        return ApiResponse.ok(operations.overview(windowMinutes));
    }

    @GetMapping("/admin/operations/alerts")
    public Object alerts() {
        return ApiResponse.ok(operations.alerts());
    }

    @PostMapping("/admin/operations/alerts/evaluate")
    public Object evaluate(@RequestParam(defaultValue = "60") int windowMinutes) {
        return ApiResponse.ok(operations.evaluate(windowMinutes));
    }

    @PostMapping("/admin/operations/alerts/{id}/ack")
    public Object acknowledge(@PathVariable String id) {
        return ApiResponse.ok(operations.acknowledge(id));
    }

    @PostMapping("/admin/operations/retention/purge")
    public Object purge(@RequestParam String confirm) {
        return ApiResponse.ok(operations.purge(confirm));
    }
}
