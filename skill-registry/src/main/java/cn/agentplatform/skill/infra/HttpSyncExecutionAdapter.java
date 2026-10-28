package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内置注册单元（PLAT-22C）：HTTP 同步形态（invocationKind=SYNC）。
 *
 * <p>零执行语义：只把注册表路由交回 {@link SkillAdapters} 的既有同步通道。T05 错误信封、T06 offload、
 * T12 身份注入、PLAT-15 PREPARE 预演通道全部原地不动；本类与 {@link HttpAsyncExecutionAdapter} 的
 * handles 按 asyncForm 互斥分流，替代原 switch 的 {@code case "HTTP"} 三目分支。
 */
@Component
public class HttpSyncExecutionAdapter implements SkillAdapter {
    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SYNC");
    }

    @Override
    public boolean handles(Map<String, Object> contract) {
        return !SkillAdapters.asyncForm(contract);
    }

    @Override
    public SkillAdapters.Raw invoke(
            SkillAdapters host,
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return host.http(contract, arguments, key, callId, runId, expectedAttempt);
    }
}
