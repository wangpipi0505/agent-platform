package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内置注册单元（PLAT-22C）：算法 Job（type=ALGORITHM，invocationKind=SUBMIT_QUERY）。
 *
 * <p>唯一有独立观测模型的内置单元：observe 走 {@link AlgorithmAdapter#observe}（返回观测源供
 * ReconciliationProofs 复核），bind/persistentBinding 承载原 {@code "ALGORITHM".equals(type)} 分支；
 * kind 缺省 SUBMIT_QUERY 也在此声明。
 */
@Component
public class AlgorithmExecutionAdapter implements SkillAdapter {
    @Override
    public String type() {
        return "ALGORITHM";
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SUBMIT_QUERY");
    }

    @Override
    public String defaultKind() {
        return "SUBMIT_QUERY";
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
        return host.algorithmInvoke(
                contract, arguments, key, callId, queryOnly, runId, expectedAttempt);
    }

    @Override
    public SkillAdapters.Observation observe(
            SkillAdapters host,
            Map<String, Object> contract,
            Map<String, Object> arguments,
            String key,
            long callId,
            boolean queryOnly,
            String runId,
            long expectedAttempt) {
        return host.algorithmObserve(
                contract, arguments, key, callId, queryOnly, runId, expectedAttempt);
    }

    @Override
    public void bind(
            SkillAdapters host, Map<String, Object> contract, Map<String, Object> request) {
        host.algorithmBind(contract, request);
    }

    @Override
    public boolean persistentBinding(Map<String, Object> contract) {
        return true;
    }
}
