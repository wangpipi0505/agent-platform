package cn.agentplatform.skill.infra;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内置注册单元（PLAT-22C）：MCP 工具（type=MCP，invocationKind=SYNC）。
 *
 * <p>对账保护（queryOnly → RESULT_UNKNOWN）从原 switch 的 MCP 臂原样搬入本单元：MCP SSE 没有原调用
 * 结果查询通道，重跑一次不是对账而是第二次副作用。工具目录发现能力（SkillCatalog.mcpTools 的门）
 * 在此声明。
 */
@Component
public class McpExecutionAdapter implements SkillAdapter {
    @Override
    public String type() {
        return "MCP";
    }

    @Override
    public Set<String> supportedKinds() {
        return Set.of("SYNC");
    }

    @Override
    public boolean supportsToolDiscovery() {
        return true;
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
                    "RESULT_UNKNOWN", true, "MCP SSE不提供原调用结果查询，禁止以对账名义重新调用工具");
        return new McpAdapter().invoke(contract, arguments, callId, runId, expectedAttempt, host);
    }
}
