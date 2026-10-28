package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内置注册单元（PLAT-22C）：HTTP 异步形态（invocationKind=SUBMIT_QUERY/CALLBACK）。
 *
 * <p>提交绑定（幂等键登记）与持久绑定语义随单元声明（原 {@code bind}/persistentBinding 的
 * {@code asynchronous(contract)} 分支收敛于此）；执行交回既有 {@link HttpAsyncAdapter} 通道，
 * 对账/attempt 栅栏/externalJob 信封零改动。
 */
@Component
public class HttpAsyncExecutionAdapter implements SkillAdapter {
    @Override
    public String type() {
        return "HTTP";
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SUBMIT_QUERY", "CALLBACK");
    }

    @Override
    public boolean handles(Map<String, Object> contract) {
        return SkillAdapters.asyncForm(contract);
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
        return host.httpAsync(contract, arguments, key, callId, queryOnly, runId, expectedAttempt);
    }

    @Override
    public void bind(
            SkillAdapters host, Map<String, Object> contract, Map<String, Object> request) {
        host.httpAsyncBind(contract, request);
    }

    @Override
    public boolean persistentBinding(Map<String, Object> contract) {
        return true;
    }
}
