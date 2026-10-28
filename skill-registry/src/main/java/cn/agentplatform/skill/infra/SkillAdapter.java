package cn.agentplatform.skill.infra;

import java.util.Map;
import java.util.Set;

/**
 * PLAT-22C（A-01 适配器注册表）：一种执行方式的注册单元。注册表按 {@link #type()} 建 kind→单元索引，
 * {@link SkillAdapters} 的分发（invoke/observe/bind/persistentBinding）全部经注册表查找，平台主链不再
 * 出现 kind→实现的硬编码分支——新执行方式"实现本接口 + 注册"即接入。
 *
 * <p><b>两层 kind 关系（定死）</b>：能力包 schema（contracts/capability-package.schema.json 的
 * {@code interfaces.kind}，枚举 HTTP_SYNC/HTTP_ASYNC/PROCESS/MCP/ALGORITHM）是<b>声明层</b>，新值必须
 * 修订 schema（契约纪律 §6.5）；本接口与 {@link AdapterRegistry} 是<b>运行层</b>，允许在不改平台分发
 * 代码的前提下注册新执行方式。运行层先行注册不构成声明层上线，schema 扩容是独立的治理决策。
 *
 * <p><b>注册来源（定死）</b>：①Spring 装配自动发现——实现类标注 {@code @Component} 即被
 * {@link AdapterRegistry} 构造器收集；②显式注册——直接构造（既有单测的组织方式）或嵌入式场景用
 * {@link AdapterRegistry#register}/{@link AdapterRegistry#withPlatformDefaults}。两条路注册的是同一批
 * 单元类，声明清单只有一份（{@code type()}/{@code supportedKinds()} 在单元类内唯一）。
 *
 * <p><b>执行语义宿主</b>：内置单元不复制任何执行逻辑——它们把注册表路由交回 {@code SkillAdapters} 的
 * 既有通道（HTTP 同步/异步、MCP、ALGORITHM、本地进程），T05 错误信封、T06 offload、T12 身份注入、
 * T21d 进程加固等已落地行为原地不动。外部新单元自持执行逻辑，通过 {@code host} 可复用平台共享设施。
 */
public interface SkillAdapter {
    /** 适配器类型（契约 {@code type} 字段取值），注册表索引键。 */
    String type();

    /**
     * 该类型接受的调用形态（契约 {@code invocationKind}），注册期组合白名单的唯一来源。
     * 同一 type 的多形态单元（HTTP 同步/异步）各声明自己的子集，注册表取并集。
     */
    Set<String> supportedKinds();

    /** 注册期 kind 缺省：未显式声明 invocationKind 时落库的值（ALGORITHM=SUBMIT_QUERY，其余 SYNC）。 */
    default String defaultKind() {
        return "SYNC";
    }

    /**
     * 同一 type 多形态单元的运行期二次路由（如 HTTP 按异步形态分流）；单形态单元缺省全收。
     * 注册表只对 {@link #type()} 已匹配的单元询问本方法。
     */
    default boolean handles(Map<String, Object> contract) {
        return true;
    }

    /**
     * 执行入口。{@code host} 是平台分发器：内置单元经它回调既有通道，外部单元可用它复用
     * 结果信封（{@code read}）、产物存储等共享设施。实现必须保持七字段错误语义（{@code Failure}）。
     */
    SkillAdapters.Raw invoke(
            SkillAdapters host,
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt);

    /**
     * 观测形态（幂等重放的对账重调用）。缺省 = invoke + 空观测源，与既有非 ALGORITHM 语义一致；
     * 有独立观测模型的单元（ALGORITHM）覆写。
     */
    default SkillAdapters.Observation observe(
            SkillAdapters host,
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return new SkillAdapters.Observation(
                host.invoke(contract, arguments, key, callId, queryOnly, runId, expectedAttempt),
                Map.of());
    }

    /** 提交前绑定（幂等键登记）。仅持久绑定形态需要；缺省无操作（HTTP 同步/MCP/PROCESS 的既有语义）。 */
    default void bind(
            SkillAdapters host, Map<String, Object> contract, Map<String, Object> request) {}

    /** 持久绑定形态（幂等键→外部任务/Job 需要落库）：ALGORITHM 与 HTTP 异步为 true，其余 false。 */
    default boolean persistentBinding(Map<String, Object> contract) {
        return false;
    }

    /** 是否支持 MCP 工具目录发现（SkillCatalog.mcpTools 的能力门唯一来源）。 */
    default boolean supportsToolDiscovery() {
        return false;
    }

    /**
     * 注册期"类型×调用形态不支持"的定制文案；返回 null 用注册表缺省文案。PROCESS 覆写以保留
     * 面向编辑器的可读诊断（错误码与触发条件不变，只换正文）。
     */
    default String unsupportedKindMessage(String kind) {
        return null;
    }
}
