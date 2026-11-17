package cn.agentplatform.skill.application;

import java.util.List;

/**
 * 来源扫描适配器（库稿 §4.8 发现执行器；主方案 §6.2"来源登记→发现（扫描/导入）"）。
 *
 * <p>契约：返回来源当前<b>完整</b>目录快照（"仅当前持有者的完整批次可更新目录"——分页或
 * 部分失败不得发布半批次）；来源为空目录时返回空列表（差异全记 removed，不删历史行）。
 * 真实扫描器（REGISTRY/GIT/DIRECTORY/CAPABILITY_PACKAGE 适配）随来源适配卡交付；
 * CAPABILITY_PACKAGE 对接既有能力包导入（V108-V110）作为来源适配器保留（主方案 §6.2）。
 */
public interface PluginSourceScanner {

    /**
     * 扫描来源。租户来自 plugin_discovery 行（执行器不经 HTTP 面）；batchNo 供适配器区分
     * 批次（快照语义按当前目录，不按批次累加）。实现不得依赖 Actor 上下文。
     */
    List<DiscoveredPlugin> scan(
            String tenantId, long sourceId, String sourceType, String location, long batchNo);
}
