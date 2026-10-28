package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内置注册单元（PLAT-22C）：本地一次性进程（type=PROCESS，invocationKind=SYNC）。
 *
 * <p>对账保护与原 PROCESS 臂一致：本地进程没有结果查询通道，重跑不是对账而是第二次副作用。
 * 进程搬运（契约解析、信封写 stdin、T21d 并额/白名单/失败转储）全部留在
 * {@link SkillAdapters#executeLocalProcess}，本单元只承载注册与路由；注册期 PROCESS 只支持 SYNC 的
 * 定制诊断文案也由此声明（错误码与触发条件不变）。
 */
@Component
public class ProcessExecutionAdapter implements SkillAdapter {
    @Override
    public String type() {
        return "PROCESS";
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SYNC");
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
        if (queryOnly)
            throw new SkillAdapters.Failure(
                    "RESULT_UNKNOWN", true, "本地进程没有可查询的原调用结果，禁止以对账名义重新执行");
        return host.executeLocalProcess(contract, arguments, callId);
    }

    @Override
    public String unsupportedKindMessage(String kind) {
        return "PROCESS 仅支持 SYNC 调用形态（本地进程没有异步提交或回调通道），收到: " + kind;
    }
}
