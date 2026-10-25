package cn.agentplatform.skill.infra;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.SchemaDeadline;
import cn.agentplatform.skill.domain.SkillRules;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** SDK-native SSE tools only. No automatic reconnect or business retry. */
public final class McpAdapter {
    // Both raw JSON and SDK records/text can coexist while decoding. Bound the whole
    // session before opening sockets, not only the final OutputGate byte array.
    static final Semaphore SESSIONS = new Semaphore(2, true);

    public Map<String, Object> discover(Map<String, Object> contract) {
        return session(contract, null, null, 0, null);
    }

    public SkillAdapters.Raw invoke(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            long callId,
            String runId,
            long expectedAttempt,
            SkillAdapters gate) {
        SkillAdapters.requireAttempt(expectedAttempt);
        // SSE has no durable cross-generation send fence; the verified original attempt is
        // mandatory, and queryOnly remains rejected by SkillAdapters before opening a session.
        return (SkillAdapters.Raw) session(contract, arguments, gate, callId, runId).get("result");
    }

    private Map<String, Object> session(
            Map<String, Object> contract,
            Map<String, Object> arguments,
            SkillAdapters gate,
            long callId,
            String runId) {
        long millis = ((Number) contract.get("timeoutMs")).longValue();
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        boolean sideEffect =
                Boolean.TRUE.equals(object(contract.get("execution")).get("sideEffect"));
        if (!SESSIONS.tryAcquire())
            throw new SkillAdapters.Failure("QUOTA", false, "MCP本机并发会话已满，请稍后重试");
        BoundedMcpSseTransport transport = null;
        McpAsyncClient client = null;
        try (var ignored = SchemaDeadline.until(deadline)) {
            String ref = Objects.toString(contract.get("authRef"), "");
            String authorization = ref.isBlank() ? null : "Bearer " + SecretVault.resolve(ref);
            transport =
                    new BoundedMcpSseTransport(text(contract, "endpoint"), authorization, deadline);
            client =
                    McpClient.async(transport)
                            .requestTimeout(Duration.ofMillis(millis))
                            .initializationTimeout(Duration.ofMillis(millis))
                            .build();
            var init = client.initialize().block(transport.remaining());
            if (init == null || init.capabilities() == null || init.capabilities().tools() == null)
                throw new SkillAdapters.Failure("PARAM_ERROR", false, "MCP服务器没有声明工具能力");
            String cursor = null;
            var seen = new HashSet<String>();
            for (int page = 0; ; page++) {
                if (page >= 16)
                    throw new SkillAdapters.Failure("OUTPUT_INVALID", false, "MCP工具分页超过16页");
                var list = client.listTools(cursor).block(transport.remaining());
                if (list == null)
                    throw new SkillAdapters.Failure("OUTPUT_INVALID", false, "MCP工具列表为空响应");
                cursor = list.nextCursor();
                if (cursor == null || cursor.isBlank()) break;
                if (cursor.length() > 1024 || !seen.add(cursor))
                    throw new SkillAdapters.Failure("OUTPUT_INVALID", false, "MCP工具分页游标无效或循环");
            }
            var tools = transport.tools();
            if (tools.isEmpty())
                throw new SkillAdapters.Failure("PARAM_ERROR", false, "MCP服务器没有可用工具");
            if (arguments == null) {
                return Map.of(
                        "protocolVersion",
                        init.protocolVersion(),
                        "serverInfo",
                        init.serverInfo(),
                        "capabilities",
                        init.capabilities(),
                        "tools",
                        tools,
                        "limits",
                        Map.of(
                                "eventBytes",
                                BoundedMcpSseTransport.EVENT_BYTES,
                                "sessionBytes",
                                BoundedMcpSseTransport.TOTAL_BYTES,
                                "events",
                                BoundedMcpSseTransport.MAX_EVENTS,
                                "tools",
                                100,
                                "pages",
                                16,
                                "schemaBytes",
                                BoundedMcpSseTransport.SCHEMA_BYTES,
                                "localSessions",
                                2));
            }
            String name = text(contract, "toolName");
            var selected =
                    tools.stream()
                            .filter(tool -> name.equals(tool.get("name")))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new SkillAdapters.Failure(
                                                    "PARAM_ERROR", false, "绑定的MCP工具已不存在"));
            try {
                // The server cannot replace the frozen application contract. Validate
                // both, retaining all raw schema keywords (SDK 0.10 JsonSchema drops some).
                SkillRules.validate(contract.get("inputSchema"), arguments);
                SkillRules.validate(selected.get("inputSchema"), arguments);
            } catch (Exception ex) {
                throw new SkillAdapters.Failure("PARAM_ERROR", false, "参数不同时符合冻结与当前MCP工具Schema");
            }
            var result =
                    client.callTool(new McpSchema.CallToolRequest(name, arguments))
                            .block(transport.remaining());
            if (result == null) throw new IOException("Empty MCP result");
            if (Boolean.TRUE.equals(result.isError()))
                throw new SkillAdapters.Failure(
                        sideEffect ? "RESULT_UNKNOWN" : "RETRYABLE_FAILURE",
                        sideEffect,
                        "MCP工具返回执行错误，写入效果需核实");
            byte[] bytes;
            if (result.content() != null
                    && result.content().size() == 1
                    && result.content().getFirst() instanceof McpSchema.TextContent text)
                bytes = text.text().getBytes(StandardCharsets.UTF_8);
            else bytes = Json.write(result).getBytes(StandardCharsets.UTF_8);
            return Map.of(
                    "result", gate.read(new ByteArrayInputStream(bytes), contract, callId, runId));
        } catch (Exception ex) {
            if (ex instanceof PlatformException denied && denied.status() == 403)
                throw new SkillAdapters.Failure("NO_PERMISSION", false, "MCP认证引用不允许用于当前租户");
            var failure =
                    transport != null && transport.failure() != null
                            ? transport.failure()
                            : ex instanceof SkillAdapters.Failure f ? f : null;
            if (failure != null
                    && Set.of("NO_PERMISSION", "QUOTA", "PARAM_ERROR").contains(failure.semantic))
                throw failure;
            boolean unknown = sideEffect && transport != null && transport.callSent();
            if (unknown)
                throw new SkillAdapters.Failure("RESULT_UNKNOWN", true, "MCP调用已开始，外部效果尚未核实，禁止自动重发");
            if (failure != null) throw failure;
            throw new SkillAdapters.Failure("RETRYABLE_FAILURE", false, "MCP连接、协议或总时限检查失败");
        } finally {
            // Completion is actual reader/HTTP termination, not a one-second join.
            // A slow cleanup keeps this permit occupied while the API returns promptly.
            if (transport != null) transport.whenTerminated(SESSIONS::release);
            try {
                if (client != null) client.close();
            } finally {
                if (transport != null) transport.close();
                else SESSIONS.release();
            }
        }
    }
}
