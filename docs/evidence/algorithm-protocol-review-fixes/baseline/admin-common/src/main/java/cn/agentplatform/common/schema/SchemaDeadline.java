package cn.agentplatform.common.schema;

/** Scoped monotonic deadline including synchronous work; no background validation workers. */
public final class SchemaDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private SchemaDeadline(long deadline) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? deadline : Math.min(deadline, previous));
    }

    public static SchemaDeadline until(long deadline) {
        return new SchemaDeadline(deadline);
    }

    public static void check() {
        Long deadline = CURRENT.get();
        if (Thread.currentThread().isInterrupted()
                || (deadline != null && System.nanoTime() >= deadline))
            throw new IllegalArgumentException("Schema校验总时限已到");
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
