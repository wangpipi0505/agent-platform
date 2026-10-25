package cn.agentplatform.engine.domain;

/** A PG scheduling generation; it never becomes a node's execution leaseOwner. */
public record RunDispatch(long runId, String owner, long generation, Queue queue) {
    public enum Queue {
        INTERACTIVE,
        BATCH
    }
}
