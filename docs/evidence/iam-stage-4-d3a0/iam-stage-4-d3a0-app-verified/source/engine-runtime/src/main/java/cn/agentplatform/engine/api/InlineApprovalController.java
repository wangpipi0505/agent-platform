package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.ApprovalService;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

/**
 * Portal approvals use tenant + declared roles; external tickets are additionally run-owner scoped.
 */
@RestController
public class InlineApprovalController {
    private final TraceStore store;
    private final ApprovalService approvals;

    public InlineApprovalController(TraceStore store, ApprovalService approvals) {
        this.store = store;
        this.approvals = approvals;
    }

    @GetMapping("/v1/runs/{id}/approvals")
    public Object list(@PathVariable String id) {
        Actor actor = Actor.current();
        store.run(id, actor);
        return ApiResponse.ok(
                approvals.list(actor).stream()
                        .filter(item -> id.equals(item.get("runId")))
                        .toList());
    }

    @PostMapping("/v1/runs/{id}/approvals/{nodeId}/decision")
    public Object decide(
            @PathVariable String id, @PathVariable long nodeId, @RequestBody JsonNode request) {
        Actor actor = Actor.current();
        store.run(id, actor);
        return ApiResponse.ok(approvals.approve(id, nodeId, request, actor));
    }
}
