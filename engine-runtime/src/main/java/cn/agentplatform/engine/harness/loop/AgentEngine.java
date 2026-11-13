package cn.agentplatform.engine.harness.loop;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.application.AgentDriver;
import cn.agentplatform.engine.domain.RuntimeFault;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * harness.loop.AgentEngine（H1-4，影子验证策略——主方案 R2：并行运行、默认路径不切）。
 *
 * <p>引擎拥有 TURN 生命周期（§5.2）：认领输入（Inbox NEXT_TURN）→ pre-step 责任链
 * （本卡仅挂点，H1-6/T2-9 填充）→ 步执行（复用既有 {@link AgentDriver#drive} 作为步机器）→
 * turn/end 收口。取消语义：取消中的 turn 不提交 system/user（直接 turn/end{aborted} 返回）。
 * 失败语义：步骤中断时为未闭合的 tool/call 补写合成结果（synthetic+UNKNOWN，契约④）并补
 * turn/end{interrupted}——结果未确认（待对账），禁止表述失败。
 *
 * <p>影子验证（行为清单 contracts/oracle-replay-adapter.md §3）：同输入下引擎驱动的事件流按
 * B1（工具可见=实际执行）/B2（调用-结果成对）/B4（turn 信封完整、恢复等价）逐项核对；
 * 与 legacy 路径的差异=引擎新增的 turn 信封与 inbox 事实（粒度差异，映射说明记录）。
 * <b>默认执行路径仍为 AgentDriver；引擎切换待影子验证通过+评审后另行提交。</b>
 *
 * <p>边界（如实标注）：consume 与请求材料同事务（§5.2）待请求材料写入移入引擎后闭合
 * （H1-5 现写在 drive 内）；本卡 consume 在 turn 成功后执行，失败轮次租约过期自然重投——
 * 语义兼容（提交前中断=未消费，恢复重投幂等），完整同事务在 H1-6 波次收口。
 */
public class AgentEngine {

    private final TraceStore store;
    private final AgentDriver driver;
    private final Inbox inbox;
    private final SessionStore sessions;
    private final PreStepChain preSteps;
    private volatile boolean cancelRequested;

    public AgentEngine(TraceStore store, AgentDriver driver, Inbox inbox, SessionStore sessions) {
        this(store, driver, inbox, sessions, PreStepChain.EMPTY);
    }

    public AgentEngine(TraceStore store, AgentDriver driver, Inbox inbox, SessionStore sessions, PreStepChain preSteps) {
        this.store = store;
        this.driver = driver;
        this.inbox = inbox;
        this.sessions = sessions;
        this.preSteps = preSteps;
    }

    /** 取消请求：置位后下一轮 turn 入口直接 aborted（取消中不提交 system/user）。 */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * 驱动一个 turn：认领 NEXT_TURN 输入 → turn/start → pre-step 链 → 步执行（driver.drive）→
     * turn/end{completed} + 确认消费；取消 → turn/end{aborted}；中断 → 未闭合调用补
     * UNKNOWN + turn/end{interrupted} 后原样上抛。
     *
     * @return 步执行的终态输出（取消返回 null）
     */
    public JsonNode runTurn(
            String sessionId,
            long turn,
            Map<String, Object> run,
            Map<String, Object> node,
            JsonNode config,
            JsonNode inputs,
            Actor actor,
            String inboxOwner) {
        // 取消语义：取消中的 turn 不提交 system/user——直接 aborted 收口。
        if (cancelRequested) {
            sessions.append(sessionId, null, SessionEvents.TURN_END, Json.tree(Map.of("turn", turn, "reason", "aborted")), null);
            return null;
        }
        // 双队列认领：turn 边界取一条 NEXT_TURN（step 边界的 NEXT_STEP 由步机器后续接入）。
        Inbox.Claim claim = inbox.claim(sessionId, "NEXT_TURN", inboxOwner, 90);
        if (claim == null)
            throw new PlatformException(409, "TURN_INPUT_NOT_CLAIMED", "无可认领的 turn 输入（未入队或已被消费）");
        sessions.append(sessionId, null, SessionEvents.TURN_START, Json.tree(Map.of("turn", turn)), null);
        JsonNode effective = preSteps.apply(sessionId, turn, inputs);
        JsonNode result;
        try {
            result = driver.drive(run, node, config, effective, actor, false);
        } catch (RuntimeFault fault) {
            synthesizeUnclosedCalls(sessionId);
            sessions.closeInterruptedTurn(sessionId, turn);
            throw fault;
        } catch (RuntimeException failure) {
            synthesizeUnclosedCalls(sessionId);
            sessions.closeInterruptedTurn(sessionId, turn);
            throw failure;
        }
        sessions.append(sessionId, null, SessionEvents.TURN_END, Json.tree(Map.of("turn", turn, "reason", "completed")), null);
        // 成功轮次确认消费（消费事件与轮次完成同事务边界由 TraceStore.atomic 保证；失败轮次不消费，租约过期重投幂等）。
        inbox.consume(sessionId, "NEXT_TURN", claim.dedupeKey(), claim.owner(), claim.generation(), TraceStore.number(node, "attemptId"));
        return result;
    }

    /** 契约④：为所有未闭合的 tool/call 补写 synthetic+UNKNOWN 结果（禁止 error 表述失败）。 */
    private void synthesizeUnclosedCalls(String sessionId) {
        Set<String> open = new HashSet<>();
        for (SessionStore.SessionEvent event : sessions.read(sessionId, 0, 0)) {
            if (SessionEvents.TOOL_CALL.equals(event.type())) {
                String callId = event.data().path("callId").asText();
                if (!callId.isBlank()) open.add(callId);
            } else if (SessionEvents.TOOL_RESULT.equals(event.type())) {
                open.remove(event.data().path("callId").asText());
            }
        }
        for (String callId : open)
            sessions.synthesizeMissingToolResult(sessionId, callId, Json.tree(Map.of("reason", "STEP_INTERRUPTED")));
    }
}
