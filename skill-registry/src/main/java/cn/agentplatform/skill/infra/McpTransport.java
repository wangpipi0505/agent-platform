package cn.agentplatform.skill.infra;

import java.util.List;
import java.util.Map;

/**
 * MCP 传输适配器 SPI（A3-6，主方案 §6.3 连接档案）。按次建连语义：每次连接测试（test）与
 * 发现（discover）新建一次会话、用完即弃，无常驻连接与状态订阅（后置增强）。
 *
 * <p>接口把"握手/鉴权"与"工具列举（分页）"分成两步，使连接测试能分别上报具体失败类别
 * （接口稿 §5.6：连通测试返回具体失败类别）。{@code secretRef} 只携带凭据引用，实现方在
 * 建连时经 SecretVault 解析，不得把凭据值落日志或回执。
 *
 * <p>实现登记约定：现有 SSE 适配器路径（{@link McpAdapter}）继续承担技能调用的按次会话；
 * 本 SPI 的生产实现（SSE_LEGACY 复用既有 SDK 路径、STREAMABLE_HTTP 真实握手/调用/异常行为
 * 测试接入）随接入卡交付。V322 首期仅登记 {@link StubMcpTransport} 测试/演示桩。
 */
public interface McpTransport {

    /** 握手+鉴权。返回 ok=false 时 category 为具体失败类别，连接测试按类别回执。 */
    McpHandshake handshake(McpEndpointSpec spec);

    /**
     * 工具列举（分页）。{@code cursor=null} 取首页；返回的 {@code nextCursor} 为 null/空白
     * 表示列举完整（完整批次才可发布目录）。中途失败抛 {@link McpTransportFailure}，
     * 发现任意一页失败即整批作废、保留上一代目录。
     */
    McpToolPage listTools(McpEndpointSpec spec, String cursor);

    /** 失败类别（连接测试与发现共用）：鉴权失败、超时、不可达、协议错误。 */
    final class Categories {
        public static final String OK = "OK";
        public static final String AUTH_FAILED = "AUTH_FAILED";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String UNREACHABLE = "UNREACHABLE";
        public static final String PROTOCOL_ERROR = "PROTOCOL_ERROR";

        private Categories() {}
    }

    /** 按次建连的端点规格：绑定连接修订号，旧修订的会话结果在发布端拦截丢弃。 */
    record McpEndpointSpec(
            String tenantId,
            String connectionCode,
            int connectionRevision,
            String transport,
            String endpoint,
            String secretRef,
            int timeoutMs) {}

    /** 握手+鉴权结果。失败类别取 {@link Categories} 枚举。 */
    record McpHandshake(
            boolean ok, String category, String message, String protocolVersion, String serverInfo) {
        public static McpHandshake ok(String protocolVersion, String serverInfo) {
            return new McpHandshake(true, Categories.OK, null, protocolVersion, serverInfo);
        }

        public static McpHandshake fail(String category, String message) {
            return new McpHandshake(false, category, message, null, null);
        }
    }

    /** 远端工具描述：名称、展示描述与输入 Schema（JSON Schema 对象）。 */
    record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {}

    /** 一页列举结果；{@code nextCursor} 为 null/空白表示本页之后列举完整。 */
    record McpToolPage(List<McpToolDescriptor> tools, String nextCursor) {}

    /** 列举中途失败：携带具体失败类别，整批作废（分页不完整不生效）。 */
    final class McpTransportFailure extends RuntimeException {
        private final String category;

        public McpTransportFailure(String category, String message) {
            super(message);
            this.category = category;
        }

        public String category() {
            return category;
        }
    }
}
