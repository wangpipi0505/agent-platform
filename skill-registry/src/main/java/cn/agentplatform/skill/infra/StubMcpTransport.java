package cn.agentplatform.skill.infra;

import cn.agentplatform.skill.infra.McpTransport.McpEndpointSpec;
import cn.agentplatform.skill.infra.McpTransport.McpHandshake;
import cn.agentplatform.skill.infra.McpTransport.McpToolDescriptor;
import cn.agentplatform.skill.infra.McpTransport.McpToolPage;
import cn.agentplatform.skill.infra.McpTransport.McpTransportFailure;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 传输测试/演示桩（A3-6）：实现 {@link McpTransport} SPI，按 (tenant, connectionCode)
 * 登记可编程场景——握手结果（OK/鉴权失败/超时）与分页链（含中途失败页），覆盖连接测试
 * 三分支与"分页不完整不生效"的确定性驱动。
 *
 * <p><b>如实边界</b>：本桩不发起任何网络请求。未登记场景的连接一律握手失败
 * UNREACHABLE（桩不做静默成功）。真实 Streamable HTTP 适配器（目标服务真实握手/调用/
 * 异常行为测试接入）与 SSE_LEGACY 复用既有 SDK 路径的生产实现随接入卡交付，届时按
 * transport 类型选择实现，本桩仅供测试与本地演示。
 */
@Component
public final class StubMcpTransport implements McpTransport {

    /** 一页列举结果。failureCategory 非空表示"进入本页"时抛出失败（分页中断模拟）。 */
    public record StubPage(
            List<McpToolDescriptor> tools,
            String nextCursor,
            String failureCategory,
            String failureMessage) {
        public static StubPage of(List<McpToolDescriptor> tools, String nextCursor) {
            return new StubPage(List.copyOf(tools), nextCursor, null, null);
        }

        public static StubPage failing(String category, String message) {
            return new StubPage(List.of(), null, category, message);
        }
    }

    /**
     * 可编程场景：握手结果（null=默认 OK）+ 分页链（第 i 页入口游标 = 第 i-1 页 nextCursor）。
     * beforeHandshake 在握手前执行——测试用它确定性模拟"探针/发现在途期间连接配置被变更"
     * （修订绑定拦截路径），无需并发。
     */
    public record Scenario(McpHandshake handshake, List<StubPage> pages, Runnable beforeHandshake) {
        public static Scenario of(McpHandshake handshake, StubPage... pages) {
            return new Scenario(handshake, List.of(pages), null);
        }

        public static Scenario of(McpHandshake handshake, Runnable beforeHandshake, StubPage... pages) {
            return new Scenario(handshake, List.of(pages), beforeHandshake);
        }
    }

    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();

    /** 登记/覆盖场景（测试装配与演示数据用；键 = tenant + '\n' + connectionCode）。 */
    public void scenario(String tenantId, String connectionCode, Scenario scenario) {
        scenarios.put(key(tenantId, connectionCode), Objects.requireNonNull(scenario));
    }

    public void clear() {
        scenarios.clear();
    }

    private static String key(String tenantId, String connectionCode) {
        return tenantId + "\n" + connectionCode;
    }

    @Override
    public McpHandshake handshake(McpEndpointSpec spec) {
        var scenario = scenarios.get(key(spec.tenantId(), spec.connectionCode()));
        if (scenario == null)
            return McpHandshake.fail(
                    Categories.UNREACHABLE,
                    "StubMcpTransport 未登记该连接的传输场景（测试/演示桩不做真实建连）");
        if (scenario.beforeHandshake() != null) scenario.beforeHandshake().run();
        return scenario.handshake() != null
                ? scenario.handshake()
                : McpHandshake.ok("2025-06-18", "stub-mcp-server");
    }

    @Override
    public McpToolPage listTools(McpEndpointSpec spec, String cursor) {
        var scenario = scenarios.get(key(spec.tenantId(), spec.connectionCode()));
        if (scenario == null)
            throw new McpTransportFailure(
                    Categories.UNREACHABLE,
                    "StubMcpTransport 未登记该连接的传输场景（测试/演示桩不做真实建连）");
        var pages = scenario.pages();
        for (int i = 0; i < pages.size(); i++) {
            String entry = i == 0 ? null : pages.get(i - 1).nextCursor();
            boolean entered =
                    (cursor == null || cursor.isBlank())
                            ? i == 0
                            : entry != null && entry.equals(cursor);
            if (!entered) continue;
            var page = pages.get(i);
            if (page.failureCategory() != null)
                throw new McpTransportFailure(page.failureCategory(), page.failureMessage());
            return new McpToolPage(page.tools(), page.nextCursor());
        }
        // 游标指向不存在的页：分页链断裂（协议错误），发现整批作废。
        throw new McpTransportFailure(
                Categories.PROTOCOL_ERROR,
                "分页游标无效或分页链断裂: " + (cursor == null ? "null" : cursor));
    }
}
