package cn.agentplatform.monitor.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.monitor.application.DatasetService;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

@RestController
public class DatasetController {
    private final DatasetService datasets;

    public DatasetController(DatasetService datasets) {
        this.datasets = datasets;
    }

    @PostMapping("/internal/datasets/promotions")
    public Object promote(@RequestBody JsonNode b) {
        return ApiResponse.ok(datasets.promote(b));
    }

    @GetMapping("/admin/evaluation-datasets/{appCode}/{version}")
    public Object get(@PathVariable String appCode, @PathVariable String version) {
        return ApiResponse.ok(datasets.get(appCode, version));
    }

    @GetMapping("/admin/evaluation-datasets")
    public Object list(
            @RequestParam String appCode,
            @RequestParam(defaultValue = "") String afterVersion,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(datasets.list(appCode, afterVersion, limit));
    }
}
