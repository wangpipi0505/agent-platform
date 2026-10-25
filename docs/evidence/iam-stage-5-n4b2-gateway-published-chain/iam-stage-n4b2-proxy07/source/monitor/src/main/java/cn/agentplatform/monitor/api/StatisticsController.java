package cn.agentplatform.monitor.api;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.application.StatisticsService;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class StatisticsController {
    private final StatisticsService statistics;

    public StatisticsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/admin/statistics")
    public Object query(
            @RequestParam Map<String, String> query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        Actor.current().requireRole("ADMIN");
        StatisticsService.validatePage(page, pageSize);
        return ApiResponse.ok(
                StatisticsService.page(
                        statistics.query(statistics.filters(query, false)), page, pageSize));
    }

    @PostMapping("/admin/statistics/rebuild")
    public Object rebuild(@RequestBody Map<String, Object> query) {
        return ApiResponse.ok(StatisticsService.page(statistics.rebuild(query), 1, 50));
    }

    @GetMapping("/admin/statistics/snapshots")
    public Object snapshots() {
        return ApiResponse.ok(statistics.snapshots());
    }

    @GetMapping("/admin/statistics/snapshots/{id}")
    public Object snapshot(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        StatisticsService.validatePage(page, pageSize);
        return ApiResponse.ok(StatisticsService.page(statistics.snapshot(id), page, pageSize));
    }

    @GetMapping("/admin/statistics/runs/{id}")
    public Object drilldown(@PathVariable String id) {
        return ApiResponse.ok(statistics.drilldown(id));
    }
}
