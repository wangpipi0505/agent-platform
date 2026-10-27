package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

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
 * PLAT-21b 执行层：SkillAdapters 的 PROCESS 分支只做“契约→进程→Raw”的搬运。
 *
 * <p>两条硬约束在这里被钉住：进程失败的 403/502/504 原样冒泡（不能折叠成 DEPENDENCY_UNAVAILABLE 之类的
 * 通用依赖错误，否则调用方会把“本地开关没开/工作目录越界”误判为可重试的上游故障）；开关关闭时在创建任何
 * 调用目录之前就拒绝，不留半截现场。
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
            assertTrue(entries.findAny().isEmpty(), "允许根下不应残留 call-* 调用目录");
        }
    }

    private static Map<String, Object> contract(String... workerArgs) {
        var process = new LinkedHashMap<String, Object>();
        process.put("command", FakeProcessWorker.command(workerArgs));
        process.put("timeoutMs", 5000);
        var contract = new LinkedHashMap<String, Object>();
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

    @Test
    void processSkillReturnsExitCodeAndStdoutTailAsStructuredOutput() {
        var raw = adapters.invoke(contract("ok"), Map.of(), "idem-1", 1L, false, "run-1", 1L);

        var structured = raw.structured();
        assertNotNull(structured, "PROCESS 必须给出结构化输出，不能只有摘要文本");
        assertEquals(0, structured.path("exitCode").asInt());
        assertTrue(
                structured.path("stdout").asText().contains("ok-stdout"),
                "stdout 尾部要保留进程输出: " + structured.path("stdout").asText());
        assertTrue(structured.path("stdout").asText().contains("中文-正常退出"));
        assertEquals("", structured.path("stderr").asText());
        assertFalse(structured.path("truncStdout").asBoolean());
        assertFalse(structured.path("truncStderr").asBoolean());
        assertTrue(structured.path("durationMs").isIntegralNumber());
        assertTrue(raw.fileRefs().isEmpty(), "PROCESS 不产生文件引用");
        assertFalse(raw.summary().isBlank());
    }

    @Test
    void nonZeroExitPropagatesProcessExitedInsteadOfDependencyUnavailable() {
        var ex =
                assertThrows(
                        PlatformException.class,
                        () ->
                                adapters.invoke(
                                        contract("exit3"),
                                        Map.of(),
                                        "idem-2",
                                        2L,
                                        false,
                                        "run-2",
                                        1L));

        assertEquals(502, ex.status());
        assertEquals("PROCESS_EXITED", ex.code());
        assertTrue(ex.getMessage().contains("boom-stderr"), "异常消息要带 stderr 尾部");
        assertTrue(ex.getMessage().contains("3"), "异常消息要带 exitCode");
    }

    @Test
    void disabledSwitchRejectsBeforeCreatingAnyCallDirectory() throws IOException {
        var disabled = new SkillAdapters(null, null, null, new ProcessAdapter(false, root));

        var ex =
                assertThrows(
                        PlatformException.class,
                        () ->
                                disabled.invoke(
                                        contract("ok"), Map.of(), "idem-3", 3L, false, "run-3", 1L));

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
                                        contract("ok"), Map.of(), "idem-4", 4L, true, "run-4", 1L));

        assertEquals("RESULT_UNKNOWN", ex.semantic);
        assertTrue(ex.unknown, "本地进程没有可查询的原调用结果，必须标记为结果未知");
    }
}
