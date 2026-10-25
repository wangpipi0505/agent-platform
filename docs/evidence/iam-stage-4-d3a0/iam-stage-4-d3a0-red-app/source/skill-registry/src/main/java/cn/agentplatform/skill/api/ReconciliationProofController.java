package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.ReconciliationProofs;

import org.springframework.web.bind.annotation.*;

/** Historical evidence is readable only by the original principal's verified internal ticket. */
@RestController
public class ReconciliationProofController {
    private final ReconciliationProofs proofs;

    public ReconciliationProofController(ReconciliationProofs proofs) {
        this.proofs = proofs;
    }

    @GetMapping("/internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}")
    public Object get(@PathVariable long callId, @PathVariable long attemptId) {
        return ApiResponse.ok(proofs.get(callId, attemptId));
    }
}
