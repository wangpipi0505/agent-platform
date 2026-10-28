package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PLAT-22C（A-01 适配器注册表）：kind→执行单元的单一来源。执行分发（{@link SkillAdapters} 的
 * invoke/observe/bind/persistentBinding）与注册期白名单（{@code SkillCatalog} 的类型/组合校验）都从
 * 本注册表取答案，平台主链不再出现 kind→实现的硬编码 switch 与散落的白名单枚举。
 *
 * <p><b>两层 kind 关系（定死）</b>：能力包 schema（{@code contracts/capability-package.schema.json} 的
 * {@code interfaces.kind}，枚举 HTTP_SYNC/HTTP_ASYNC/PROCESS/MCP/ALGORITHM）是声明层，新值必须修订
 * schema；本注册表是运行层，允许不改平台分发代码注册新执行方式（实现 {@link SkillAdapter} 并注册）。
 * 运行层先行注册不构成声明层上线，两层各自独立治理。
 *
 * <p><b>注册来源（定死）</b>：①Spring 装配自动发现——构造器注入 {@code List<SkillAdapter>} 收集全部
 * {@code @Component} 单元（五个内置单元与未来外部适配器同一通道）；②显式注册——直接构造（既有单测的
 * 组织方式）用 {@link #withPlatformDefaults()}，测试用 {@link #register}。两条路注册同一批单元类，
 * 声明清单（type×invocationKind 接受面）只在单元类内维护一份。
 *
 * <p><b>未注册 kind 的语义（定死）</b>：注册期 400（既有 {@code INVALID_SKILL}/
 * {@code UNSUPPORTED_INVOCATION_KIND} 同码，正文可读）；执行期 {@code PARAM_ERROR} 失败（与原 switch
 * default 臂同码同 unknown 位），正文列出已注册类型与接入方式。bind/persistentBinding 等旁路询问对
 * 未注册 kind 保持既有静默语义（find 返回 null），不做新抛错。
 */
@Component
public class AdapterRegistry {
    private final Map<String, List<SkillAdapter>> byType = new LinkedHashMap<>();

    /** Spring 装配入口：自动发现全部 SkillAdapter 单元（含内置五个与外部新增）。 */
    public AdapterRegistry(List<SkillAdapter> adapters) {
        adapters.forEach(this::register);
    }

    /**
     * 直接构造（既有单测的组织方式）时使用的内置注册表：注册的单元类与 Spring 装配完全同一批，
     * 不存在"测试注册表 = 另一套行为"。
     */
    public static AdapterRegistry withPlatformDefaults() {
        var registry = new AdapterRegistry(List.of());
        registry.register(new AlgorithmExecutionAdapter());
        registry.register(new HttpAsyncExecutionAdapter());
        registry.register(new HttpSyncExecutionAdapter());
        registry.register(new McpExecutionAdapter());
        registry.register(new ProcessExecutionAdapter());
        return registry;
    }

    /**
     * 显式注册入口（测试/嵌入式扩展）。同一 type 允许多个单元（HTTP 的同步/异步两形态），
     * 分发按注册顺序取首个 {@link SkillAdapter#handles} 命中者；两形态的 handles 互斥，顺序不影响结论。
     */
    public synchronized void register(SkillAdapter adapter) {
        Objects.requireNonNull(adapter, "适配器单元不能为空");
        Objects.requireNonNull(adapter.type(), "适配器单元 type() 不能为空");
        byType.computeIfAbsent(adapter.type(), key -> new ArrayList<>()).add(adapter);
    }

    /**
     * 执行分发：按契约 {@code type} 找单元，同 type 多形态用 handles 二次路由。未注册 kind 抛
     * {@code PARAM_ERROR}（unknown=false，与原 switch default 臂同码），正文带可读诊断。
     */
    public synchronized SkillAdapter dispatch(Map<String, Object> contract) {
        SkillAdapter unit = find(contract);
        if (unit == null) throw new SkillAdapters.Failure("PARAM_ERROR", false, describe(contract));
        return unit;
    }

    /** 与 dispatch 同一查找的静默版本：bind/persistentBinding 等旁路询问保持既有"未注册=无操作"。 */
    public synchronized SkillAdapter find(Map<String, Object> contract) {
        var units = byType.get(Objects.toString(contract.get("type"), ""));
        if (units == null) return null;
        for (SkillAdapter unit : units) if (unit.handles(contract)) return unit;
        return null;
    }

    /** 注册期类型白名单的唯一来源（SkillCatalog.normalize 不再自持枚举）。 */
    public synchronized boolean isSupportedType(String type) {
        return byType.containsKey(type);
    }

    /** 注册期"类型×调用形态"组合白名单的唯一来源：该类型任一单元声明接受该 invocationKind。 */
    public synchronized boolean isSupportedCombination(String type, String kind) {
        var units = byType.get(type);
        if (units == null) return false;
        for (SkillAdapter unit : units) if (unit.supportedKinds().contains(kind)) return true;
        return false;
    }

    /** 注册期 kind 缺省值：由该类型的第一个单元声明（ALGORITHM=SUBMIT_QUERY，其余 SYNC）。 */
    public synchronized String defaultKind(String type) {
        var units = byType.get(type);
        return units == null || units.isEmpty() ? "SYNC" : units.getFirst().defaultKind();
    }

    /** MCP 工具目录发现能力的唯一来源（SkillCatalog.mcpTools）。 */
    public synchronized boolean supportsToolDiscovery(String type) {
        var units = byType.get(type);
        if (units == null) return false;
        for (SkillAdapter unit : units) if (unit.supportsToolDiscovery()) return true;
        return false;
    }

    /** 注册期组合拒绝的正文：单元可定制（PROCESS 的 SYNC-only 诊断），缺省为既有通用文案。 */
    public synchronized String combinationMessage(String type, String kind) {
        var units = byType.get(type);
        if (units != null)
            for (SkillAdapter unit : units) {
                String custom = unit.unsupportedKindMessage(kind);
                if (custom != null) return custom;
            }
        return "该适配器与调用形态组合尚未支持";
    }

    /** 当前注册的全部 kind（诊断用，字典序）。 */
    public synchronized Set<String> supportedTypes() {
        return Set.copyOf(byType.keySet());
    }

    private String describe(Map<String, Object> contract) {
        var types = new ArrayList<>(byType.keySet());
        Collections.sort(types);
        return "未支持的适配器类型: "
                + Objects.toString(contract.get("type"), "")
                + "（当前已注册: "
                + String.join(", ", types)
                + "）；新执行方式通过实现 SkillAdapter 并注册接入，无需修改平台分发代码";
    }
}
