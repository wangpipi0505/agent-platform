package cn.agentplatform.monitor.api;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.application.EvaluationService;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class EvaluationController {
    private final EvaluationService evaluations;

    public EvaluationController(EvaluationService evaluations) {
        this.evaluations = evaluations;
    }

    @PostMapping("/admin/evaluations")
    public Object evaluate(@RequestBody Map<String, Object> b) {
        Actor.current().requireRole("EDITOR");
        return ApiResponse.ok(evaluations.evaluate(b));
    }

    @GetMapping("/admin/evaluations")
    public Object list() {
        return ApiResponse.ok(evaluations.list());
    }

    @PostMapping("/admin/evaluations/{id}/resume")
    public Object resume(@PathVariable String id) {
        Actor.current().requireRole("EDITOR");
        return ApiResponse.ok(evaluations.resume(id));
    }

    @GetMapping("/admin/evaluations/{id}")
    public Object report(@PathVariable String id) {
        return ApiResponse.ok(evaluations.report(id));
    }

    @GetMapping("/internal/evaluations/accepted")
    public Object accepted(
            @RequestParam String appCode,
            @RequestParam long draftRev,
            @RequestParam String candidateSha256) {
        return ApiResponse.ok(evaluations.accepted(appCode, draftRev, candidateSha256));
    }

    @GetMapping("/admin/{kind:approvals|audit}")
    public Object projection(@PathVariable String kind) {
        return ApiResponse.ok(evaluations.project(kind));
    }

    @PostMapping("/admin/approvals/{runId}/{nodeId}")
    public Object approve(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody Map<String, Object> b) {
        return ApiResponse.ok(evaluations.approve(runId, nodeId, b));
    }
}
