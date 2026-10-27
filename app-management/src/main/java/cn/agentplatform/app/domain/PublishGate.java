package cn.agentplatform.app.domain;

/**
 * 评测门禁的三种模式。门禁只约束"发布前是否有满足安全与回归要求的候选报告"，不改变评测本身。
 *
 * <p>BLOCKING：没有合格报告即阻断发布；ADVISORY：缺失报告只记告警并放行；OFF：完全跳过评测结论。
 * 解析对未知值一律回落到 BLOCKING（失败关闭）：配置拼写错误不能把阻断放宽成放行。
 */
public enum PublishGate {
    BLOCKING,
    ADVISORY,
    OFF;

    public static PublishGate parse(String raw) {
        if (raw == null || raw.isBlank()) return BLOCKING;
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ADVISORY" -> ADVISORY;
            case "OFF" -> OFF;
            default -> BLOCKING;
        };
    }

    public boolean blocking() {
        return this == BLOCKING;
    }
}
