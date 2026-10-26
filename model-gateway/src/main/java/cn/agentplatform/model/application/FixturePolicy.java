package cn.agentplatform.model.application;

import cn.agentplatform.common.PlatformException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AP-S06 fixture 隔离的统一判定。
 *
 * <p>现状：演示/冒烟数据不是迁移或代码 seed 注入的，而是各 smoke 脚本经管理面 API（dev-token）
 * 写进 PG 的。运行时只能力守这条事实上已存在多年的命名约定：fixture/smoke_model_ 前缀或含
 * "fixture" 的编码即演示数据。新演示数据也必须遵守该约定，否则会被当作托管数据对待。</p>
 *
 * <p>设计不变量：agent.demo-mode（默认 false）是唯一开关。生产/默认模式下 fixture 行只读不
 * 用——不能新建、不能参与真实调用选择；演示模式下才允许完整读写，保证本地 smoke 流程可用。</p>
 */
@Service
public class FixturePolicy {
    private final boolean demoMode;

    public FixturePolicy(@Value("${agent.demo-mode:false}") boolean demoMode) {
        this.demoMode = demoMode;
    }

    public boolean demoMode() {
        return demoMode;
    }

    /** 模型供应商侧约定：编码含 fixture，或 smoke_model_ 前缀（冒烟脚本创建的模型供应商）。 */
    public static boolean isFixtureProviderCode(String code) {
        return code != null && (code.contains("fixture") || code.startsWith("smoke_model_"));
    }

    /** 技能侧约定由 skill-registry 的 FixtureSkills 定义；此处不做技能判定。 */
    public void assertProviderCreationAllowed(String code) {
        if (!demoMode && isFixtureProviderCode(code))
            throw new PlatformException(
                    409,
                    "SYS-MODEL-FIXTURE",
                    "演示 fixture 供应商只能在演示模式（AGENT_DEMO_MODE=true）下注册；"
                            + "请改用正式命名或启用演示模式");
    }
}
