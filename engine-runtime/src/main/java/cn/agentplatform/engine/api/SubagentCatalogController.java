package cn.agentplatform.engine.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Actor;
import cn.agentplatform.engine.application.SubagentPlanning;

import org.springframework.web.bind.annotation.*;

/**
 * T2-7（台账 §7.3，接口稿 §5.3）：子代理目录查询端点——GET /v1/sessions/{id}/subagents。
 * 独立控制器承载（会话域只读投影，不动既有 ChatSessionController 契约面）；分角色口径与
 * 404/403 处理由 {@link SubagentPlanning#catalog} 承接（USER 本人父会话+停因展示摘要、
 * OPERATOR/ADMIN 本租户+终态原因码、SERVICE 票据面经 authority integrationAllowed=false 拒绝）。
 */
@RestController
public class SubagentCatalogController {

    private final SubagentPlanning planning;

    public SubagentCatalogController(SubagentPlanning planning) {
        this.planning = planning;
    }

    /** 目录查询：按 startedAt,childSessionId 稳定排序，after/limit 分页（nextAfter 游标）。 */
    @GetMapping("/v1/sessions/{id}/subagents")
    public Object subagents(
            @PathVariable("id") String id,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) String limit) {
        return ApiResponse.ok(planning.catalog(id, after, limit, Actor.current()));
    }
}
