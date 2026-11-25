package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class TaskAgentExecution {
    private final TraceStore store;
    private final RunService runs;
    private final StepExecutionMapping steps;
    private final TaskDispatcher dispatcher;

    public TaskAgentExecution(TraceStore store, RunService runs, StepExecutionMapping steps,
                              TaskDispatcher dispatcher) {
        this.store = store;
        this.runs = runs;
        this.steps = steps;
        this.dispatcher = dispatcher;
    }

    void start(long taskId, String code, JsonNode inputs, Set<String> allowedTools, Actor actor) {
        boolean accepted = Boolean.TRUE.equals(store.db().queryForObject(
                "select exists(select 1 from engine.task where id=? and tenant_id=? and principal_id=?"
                        + " and state='ACCEPTED')",
                Boolean.class, taskId, actor.tenantId(), actor.principalId()));
        if (!accepted) return;
        try {
            runs.createForTask(code, inputs, allowedTools, actor, runId -> steps.admit(taskId, runId, actor));
        } catch (StepExecutionMapping.EntryAlreadySettled raced) {
            // The admission transaction rolled back its run. The already settled task wins.
        } catch (PlatformException rejected) {
            dispatcher.rejectEntry(taskId, rejected.code());
        }
    }

    boolean cancel(long taskId) {
        return dispatcher.cancelEntry(taskId);
    }

    void startIntegration(String code, JsonNode inputs, Actor actor, java.util.function.LongUnaryOperator createTask) {
        runs.createForTask(code, inputs, Set.of(), actor,
                runId -> steps.admit(createTask.applyAsLong(runId), runId, actor));
    }
}
