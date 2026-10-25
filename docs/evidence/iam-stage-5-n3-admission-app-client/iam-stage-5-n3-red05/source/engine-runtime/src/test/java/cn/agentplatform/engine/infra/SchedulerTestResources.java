package cn.agentplatform.engine.infra;

import cn.agentplatform.engine.application.*;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Tests own real bounded schedulers and drain their workers before deleting fixture rows. */
final class SchedulerTestResources {
    private static final ConcurrentLinkedQueue<RunScheduler> owned = new ConcurrentLinkedQueue<>();

    static RunService runs(
            TraceStore store, RuntimeClients clients, FlowDriver flow, AgentDriver agent) {
        var scheduler = new RunScheduler(store);
        owned.add(scheduler);
        return new RunService(store, clients, flow, agent, scheduler);
    }

    static void close() {
        RunScheduler scheduler;
        while ((scheduler = owned.poll()) != null) scheduler.close();
    }
}
