package cn.agentplatform.skill.application;

import java.util.List;
import java.util.Map;

/**
 * 发现批次中的单个插件版本（库稿 §4.8）：执行器差异分类与目录合并的最小单元。
 * 编码/名称/版本/manifest/能力元素的形状校验随合并入口（PluginGovernance
 * recordDiscoveredVersion 契约）继承，DB CHECK 兜底。
 */
public record DiscoveredPlugin(
        String code,
        String name,
        String version,
        Map<String, Object> manifest,
        List<Map<String, Object>> capabilities) {}
