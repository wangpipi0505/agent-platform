package cn.agentplatform.skill.application;

import cn.agentplatform.common.Digest;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首版演示桩（主方案 §6.2：能力包导入作为一个来源适配器保留）。REGISTRY/GIT/DIRECTORY/
 * CAPABILITY_PACKAGE 的真实扫描器随来源适配卡交付，本桩只演示"来源→完整批次快照"形状：
 * 每个来源确定性产出一个 demo 插件，manifest.demo=true 明示演示数据、不冒充真实源数据；
 * 版本固定 1.0.0——重复发现同摘要幂等（差异为空、目录不变），供演示与连通验证。
 */
@Component
public class DemoPluginSourceScanner implements PluginSourceScanner {

    @Override
    public List<DiscoveredPlugin> scan(
            String tenantId, long sourceId, String sourceType, String location, long batchNo) {
        String code = "demo-" + sourceId;
        String ref = "demo-skill-" + sourceId;
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("name", "演示插件 " + sourceId);
        manifest.put("version", "1.0.0");
        manifest.put("demo", true);
        manifest.put("sourceType", sourceType);
        manifest.put("location", location);
        // CAPABILITY_PACKAGE 真实适配器将改读既有能力包导入目录（V108-V110）；桩阶段形状一致。
        return List.of(
                new DiscoveredPlugin(
                        code,
                        "演示插件 " + sourceId,
                        "1.0.0",
                        manifest,
                        List.of(Map.of("type", "SKILL", "ref", ref, "digest", Digest.sha256(ref)))));
    }
}
