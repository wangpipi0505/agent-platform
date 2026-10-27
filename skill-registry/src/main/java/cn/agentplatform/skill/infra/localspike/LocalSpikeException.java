package cn.agentplatform.skill.infra.localspike;

/**
 * IN-09 spike 异常族：让失败路径测试能精确断言异常类型与语义
 * （AGENTS.md 要求关键失败路径先行测试，异常类型即测试契约）。
 */
public class LocalSpikeException extends RuntimeException {
    public LocalSpikeException(String message) {
        super(message);
    }

    public LocalSpikeException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 端口已被占用：启动预检失败，尚未拉起任何进程（AP-S05 设计输入：启动前必须做端口冲突检测）。 */
    public static final class PortInUseException extends LocalSpikeException {
        public PortInUseException(String message) {
            super(message);
        }
    }

    /** 就绪判定超时：进程仍存活但健康端点在 startupTimeout 内一直不通过。 */
    public static final class StartupTimeoutException extends LocalSpikeException {
        public StartupTimeoutException(String message) {
            super(message);
        }
    }

    /** 子进程在就绪前退出（jar 路径错误、JDK 版本不符等），必须与"超时"区分开以便定位。 */
    public static final class ProcessExitedException extends LocalSpikeException {
        public final int exitCode;

        public ProcessExitedException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    /** 业务调用失败：非 200 或响应缺 referenceUtc/allResults 契约字段。 */
    public static final class InvocationException extends LocalSpikeException {
        public InvocationException(String message) {
            super(message);
        }
    }
}
