package cn.agentplatform.engine.infra;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 服务级验收（EX-26）专用的只读桥：把包内可见的 {@code MaterialScopeStore.payloadDigest} 暴露给
 * {@code cn.agentplatform.engine.application} 里的真实 18086 用例。
 *
 * <p>为什么不直接在测试里重写摘要算法：材料范围的 payload 摘要是"落库行 ↔ 恢复期读回"的同一性根，
 * 一旦测试自己实现一份 normalize（对象键排序、数字 stripTrailingZeros），它就和产品实现成为两个
 * 事实来源，摘要不一致时只会把真实缺陷掩盖成测试假象。这里只做可见性转发，不复制任何逻辑。
 *
 * <p>仅测试源码可见，不进入生产 classpath。
 */
public final class LiveMaterialScopeDigest {
    private LiveMaterialScopeDigest() {}

    /** {@code kind} ∈ {run-snapshot, run-inputs, run-version-refs}；规则与 createBound 写行时完全一致。 */
    public static String digest(JdbcTemplate db, String kind, long runId) {
        return MaterialScopeStore.payloadDigest(db, kind, runId);
    }
}
