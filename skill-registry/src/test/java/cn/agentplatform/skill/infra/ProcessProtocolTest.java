package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PLAT-21c 协议测试：请求信封（§4.4）、响应信封校验顺序（§4.5）、退出码语义（§4.6）。
 *
 * <p>每条用例都真实起 {@link FakeProcessWorker}，只用 worker 的可控模式制造“算法写错协议”的场景；
 * 断言平台给出的是机器可判定的 code，而不是笼统的“外部输出失败”。
 */
@Timeout(120)
class ProcessProtocolTest {

    private static final String REQUEST_ID = "call-42";
    private static final String OPERATION = "revenue.forecast";

    @TempDir Path temp;
    Path root;
    ProcessAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temp.resolve("allowed-root")).toRealPath();
        adapter = new ProcessAdapter(true, root);
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (var entries = Files.list(root)) {
            assertEquals(
                    0,
                    entries.filter(p -> p.getFileName().toString().startsWith("call-")).count(),
                    "callDir 必须在 finally 中删除（failed-* 失败转储是 T21d 的有意保留，用后清理）");
        }
        deleteFailureDumps();
    }

    private void deleteFailureDumps() throws IOException {
        try (var entries = Files.list(root)) {
            entries
                    .filter(p -> p.getFileName().toString().startsWith("failed-"))
                    .forEach(
                            dump -> {
                                try (var walk = Files.walk(dump)) {
                                    walk.sorted(java.util.Comparator.reverseOrder())
                                            .forEach(
                                                    path -> {
                                                        try {
                                                            Files.deleteIfExists(path);
                                                        } catch (IOException ignored) {
                                                            // 兜底清理
                                                        }
                                                    });
                                } catch (IOException ignored) {
                                    // 兜底清理
                                }
                            });
        }
    }

    @Test
    void writesEnvelopeWithProtocolRequestIdOperationAndParameters() {
        ObjectNode parameters = Json.object();
        parameters.put("month", "2026-10");
        parameters.put("topN", 5);

        ProcessAdapter.Output output = execute(contract("envelope"), parameters);

        // worker 在 result.stdin 里回显它读到的原始请求，逐键断言信封形状。
        JsonNode envelope = Json.read(output.result().path("stdin").asText());
        assertEquals(ProcessContract.PROTOCOL, envelope.path("protocol").asText());
        assertEquals(REQUEST_ID, envelope.path("requestId").asText());
        assertEquals(OPERATION, envelope.path("operation").asText());
        assertEquals(parameters, envelope.path("parameters"));
        assertTrue(envelope.path("files").isObject(), "files 必须是对象（本卡为空）");
        assertEquals(0, envelope.path("files").size());
        // 信封里不得夹带凭据类字段（§4.4）。
        assertFalse(envelope.has("token"));
        assertFalse(envelope.has("tenantId"));
        assertFalse(envelope.has("principalId"));
    }

    @Test
    void rejectedRequestIdIsProtocolMismatch() {
        PlatformException ex = assertThrows(PlatformException.class, () -> execute(contract("tamper")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_PROTOCOL_MISMATCH", ex.code());
        assertTrue(ex.getMessage().contains(REQUEST_ID), "消息应指出期望的 requestId");
    }

    @Test
    void bannerAroundEnvelopeIsOutputInvalid() {
        PlatformException ex = assertThrows(PlatformException.class, () -> execute(contract("banner")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_OUTPUT_INVALID", ex.code());
        assertTrue(ex.getMessage().contains("多余文本"), "消息应说明 stdout 不止一个 JSON 对象");
    }

    @Test
    void wrongProtocolVersionIsProtocolMismatch() {
        PlatformException ex =
                assertThrows(PlatformException.class, () -> execute(contract("badprotocol")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_PROTOCOL_MISMATCH", ex.code());
    }

    @Test
    void emptyStdoutIsOutputInvalid() {
        PlatformException ex = assertThrows(PlatformException.class, () -> execute(contract("silent")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_OUTPUT_INVALID", ex.code());
    }

    @Test
    void explicitRejectionOnOtherExitCodeKeepsWorkerMessage() {
        PlatformException ex =
                assertThrows(PlatformException.class, () -> execute(contract("rejected")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_REJECTED", ex.code());
        assertTrue(ex.getMessage().contains("amount"), "应透出算法自己的 error.message: " + ex.getMessage());
    }

    @Test
    void exitCodeTwoIsInputInvalid() {
        PlatformException ex = assertThrows(PlatformException.class, () -> execute(contract("exit2")));

        assertEquals(422, ex.status());
        assertEquals("PROCESS_INPUT_INVALID", ex.code());
        assertTrue(ex.getMessage().contains("boom-stderr"), "应带 stderr 尾部便于定位");
    }

    @Test
    void exitCodeFourIsOutputWriteFailed() {
        PlatformException ex = assertThrows(PlatformException.class, () -> execute(contract("exit4")));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_OUTPUT_WRITE_FAILED", ex.code());
    }

    @Test
    void resultViolatingOutputSchemaIsRejected() {
        ObjectNode json = contract("schemabad");
        ObjectNode schema = json.putObject("outputSchema");
        schema.put("type", "object");
        schema.putArray("required").add("count");
        schema.putObject("properties").putObject("count").put("type", "integer");

        PlatformException ex = assertThrows(PlatformException.class, () -> execute(json));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_OUTPUT_SCHEMA_INVALID", ex.code());
        assertTrue(ex.getMessage().contains("count"), "消息应带字段路径: " + ex.getMessage());
    }

    @Test
    void resultSatisfyingOutputSchemaIsReturnedAsRawOutput() {
        ObjectNode json = contract("ok");
        ObjectNode schema = json.putObject("outputSchema");
        schema.put("type", "object");
        schema.putArray("required").add("lines");
        schema.putObject("properties").putObject("lines").put("type", "array");

        ProcessAdapter.Output output = execute(json);

        // 平台交给下游的是 result 节点，不是整段 stdout（否则 protocol/ok/metrics 会污染 Raw）。
        assertEquals(List.of("ok-stdout", "中文-正常退出"), textList(output.result().path("lines")));
        assertFalse(output.result().has("protocol"));
        assertFalse(output.result().has("requestId"));
        assertFalse(output.result().has("ok"));
    }

    @Test
    void requestEnvelopeBeyondOneMiBIsRejectedBeforeStart() throws IOException {
        ObjectNode parameters = Json.object();
        parameters.put("blob", "x".repeat(ProcessContract.MAX_REQUEST_BYTES));

        PlatformException ex =
                assertThrows(PlatformException.class, () -> execute(contract("ok"), parameters));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_INPUT_TOO_LARGE", ex.code());
        // 超限必须在起进程之前判定：被拒绝的调用不得留下 callDir。
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count());
        }
    }

    private ProcessAdapter.Output execute(ObjectNode json) {
        return execute(json, Json.object());
    }

    private ProcessAdapter.Output execute(ObjectNode json, ObjectNode parameters) {
        return adapter.execute(
                ProcessContract.parse(json),
                new ProcessAdapter.Request(REQUEST_ID, OPERATION, parameters));
    }

    private ObjectNode contract(String mode) {
        ObjectNode json = Json.object();
        ArrayNode command = json.putArray("command");
        FakeProcessWorker.command(mode).forEach(command::add);
        json.put("timeoutMs", 5000);
        return json;
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
