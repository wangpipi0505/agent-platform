package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * R-A6：非审批类外部事件唤醒（幂等 wake）。
 *
 * <p>与 ApprovalService 的分工：审批批准/拒绝已自带即时唤醒（dispatch_next_at=now()）；
 * 本服务面向其余外部事件源——算法任务完成回调、集成系统通知等——提供一个通用的
 * "踢一下调度"入口：只把 dispatch_next_at 提前到 now()，不改变 run 状态、不代替
 * 任何业务判定（WAITING_EXTERNAL 的事实采纳仍由 ExecutionRecovery 对账完成；
 * 本唤醒只让调度器尽早重查）。
 *
 * <p>幂等性两层：①动作本身幂等——重复把 dispatch_next_at 设为 now() 无副作用；
 * ②requestId 去重——同一 requestId 的重复唤醒返回 deduped=true 且只落一条
 * WAKE_REQUESTED 事件（复用 run_event 上 requestId 载荷判重，同 RESUMED 的做法）。
 * 终态 run 唤醒是无害 no-op，返回终态与 woken=false。
 */
@Service
public class RunWakeService {
    private final TraceStore store;

    public RunWakeService(TraceStore store) {
        this.store = store;
    }

    public Map<String, Object> wake(String publicId, JsonNode request, Actor actor) {
        String requestId = request.path("requestId").asText();
        String source = request.path("source").asText();
        if (requestId.isBlank() || source.isBlank())
            throw new PlatformException(400, "WAKE_INVALID", "唤醒需要requestId与source");
        return store.atomic(
                () -> {
                    var runs =
                            store.db()
                                    .queryForList(
                                            "select * from engine.run where public_id=? and"
                                                    + " tenant_id=? for update",
                                            publicId,
                                            actor.tenantId());
                    if (runs.isEmpty()) throw new PlatformException(404, "RUN_NOT_FOUND", "唤醒目标不存在");
                    var run = runs.getFirst();
                    long rid = TraceStore.number(run, "id");
                    String status = Objects.toString(run.get("status"), "");
                    if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status))
                        return Map.of(
                                "runId", publicId, "status", status, "woken", false, "deduped", false);
                    // requestId 判重：同一外部事件的重复投递不产生第二条唤醒事实。
                    boolean deduped =
                            Boolean.TRUE.equals(
                                    store.db()
                                            .queryForObject(
                                                    "select exists(select 1 from engine.run_event"
                                                        + " where run_id=? and type='WAKE_REQUESTED'"
                                                        + " and payload->>'requestId'=?)",
                                                    Boolean.class,
                                                    rid,
                                                    requestId));
                    if (!deduped) {
                        store.event(
                                rid,
                                "WAKE_REQUESTED",
                                Map.of(
                                        "requestId", requestId,
                                        "source", source,
                                        "wokeBy", actor.principalId()));
                        // 仅提前调度时刻；状态与事实判定一律留给原有路径。
                        store.db()
                                .update(
                                        "update engine.run set"
                                                + " dispatch_next_at=least(coalesce(dispatch_next_at"
                                                + ", now()), now()) where id=?",
                                        rid);
                    }
                    return Map.of(
                            "runId", publicId,
                            "status", Objects.toString(run.get("status"), ""),
                            "woken", !deduped,
                            "deduped", deduped);
                });
    }
}
