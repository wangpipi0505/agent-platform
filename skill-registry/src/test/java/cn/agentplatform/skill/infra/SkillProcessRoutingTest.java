package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ProcessContract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PLAT-21b/21c 执行层：SkillAdapters 的 PROCESS 分支负责“契约+调用参数 → 请求信封 → 进程 → Raw”。
 *
 * <p>这里钉住三件事：worker 确实收到 {@code protocol/requestId/operation/parameters} 四键（T21c）；交给下游的
 * Raw 是响应 {@code result} 而不是整段 stdout（否则 protocol/ok/metrics 会污染下游 schema）；进程失败的
 * 403/502/504 原样冒泡，不折叠成通用依赖错误，否则调用方会把“开关没开/目录越界”误判为可重试的上游故障。
 */
@Timeout(120)
class SkillProcessRoutingTest {

    @TempDir Path temp;
    Path root;
    ProcessAdapter process;
    SkillAdapters adapters;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temp.resolve("allowed-root")).toRealPath();
        process = new ProcessAdapter(true, root);
        // 构造注入，与容器装配一致，不新起 Spring 上下文（既有测试的组织方式）。
        adapters = new SkillAdapters(null, null, null, process);
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (var entries = Files.list(root)) {
            assertTrue(
                    entries
                            .filter(p -> p.getFileName().toString().startsWith("call-"))
                            .findAny()
                            .isEmpty(),
                    "允许根下不应残留 call-* 调用目录（failed-* 失败转储是 T21d 的有意保留，用后清理）");
        }
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
    void workerReceivesProtocolEnvelopeBuiltFromCallArguments() {
        Map<String, Object> arguments = Map.of("month", "2026-10", "topN", 5);

        var raw = adapters.invoke(contract("envelope"), arguments, "idem-1", 7L, false, "run-1", 1L);

        // worker 把读到的 stdin 原文放进 result.stdin；先断言 Raw 就是 result，再逐键验证信封。
        var envelope = Json.read(raw.structured().path("stdin").asText());
        assertEquals(ProcessContract.PROTOCOL, envelope.path("protocol").asText());
        assertEquals("7", envelope.path("requestId").asText(), "requestId 必须是 callId");
        assertEquals("revenue.forecast", envelope.path("operation").asText(), "operation 必须是技能 code");
        assertEquals(arguments, Json.mapper().convertValue(envelope.path("parameters"), Map.class));
        assertTrue(envelope.path("files").isObject(), "files 必须是对象");
        assertFalse(raw.structured().has("protocol"), "Raw 只能是 result，不能是整段 stdout");
        assertFalse(raw.structured().has("ok"));
    }

    @Test
    void rawOutputIsResultNodeNotTheWholeEnvelope() {
        var raw = adapters.invoke(contract("ok"), Map.of(), "idem-2", 2L, false, "run-2", 1L);

        java.util.List<String> lines = new java.util.ArrayList<>();
        raw.structured().path("lines").forEach(node -> lines.add(node.asText()));
        assertEquals(
                java.util.List.of("ok-stdout", "中文-正常退出"),
                lines,
                "result.lines 应原样成为 Raw 的内容");
        assertFalse(raw.structured().has("protocol"));
        assertFalse(raw.structured().has("requestId"));
        assertFalse(raw.structured().has("metrics"));
        assertTrue(raw.fileRefs().isEmpty(), "PROCESS 不产生文件引用");
        assertFalse(raw.summary().isBlank());
    }

    @Test
    void calculationFailureIsNotFoldedIntoDependencyUnavailable() {
        var ex =
                assertThrows(
                        PlatformException.class,
                        () ->
                                adapters.invoke(
                                        contract("exit3"),
                                        Map.of(),
                                        "idem-3",
                                        3L,
                                        false,
                                        "run-3",
                                        1L));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_CALCULATION_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("boom-stderr"), "异常消息要带 stderr 尾部");
    }

    @Test
    void disabledSwitchRejectsBeforeCreatingAnyCallDirectory() throws IOException {
        var disabled = new SkillAdapters(null, null, null, new ProcessAdapter(false, root));

        var ex =
                assertThrows(
                        PlatformException.class,
                        () ->
                                disabled.invoke(
                                        contract("ok"), Map.of(), "idem-4", 4L, false, "run-4", 1L));

        assertEquals(403, ex.status());
        assertEquals("PROCESS_DISABLED", ex.code());
        try (var entries = Files.list(root)) {
            assertTrue(entries.findAny().isEmpty(), "开关关闭时不得创建 call-* 调用目录");
        }
    }

    @Test
    void reconciliationQueryMustNotRerunTheProcess() {
        var ex =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        contract("ok"), Map.of(), "idem-5", 5L, true, "run-5", 1L));

        assertEquals("RESULT_UNKNOWN", ex.semantic);
        assertTrue(ex.unknown, "本地进程没有可查询的原调用结果，必须标记为结果未知");
    }

    private static Map<String, Object> contract(String... workerArgs) {
        var process = new LinkedHashMap<String, Object>();
        process.put("command", FakeProcessWorker.command(workerArgs));
        process.put("timeoutMs", 5000);
        var contract = new LinkedHashMap<String, Object>();
        contract.put("code", "revenue.forecast");
        contract.put("type", "PROCESS");
        contract.put("description", "本地执行测试进程");
        contract.put("invocationKind", "SYNC");
        contract.put("process", process);
        contract.put("inputSchema", Map.of("type", "object"));
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", false,
                        "cancellable", false));
        return contract;
    }
}
