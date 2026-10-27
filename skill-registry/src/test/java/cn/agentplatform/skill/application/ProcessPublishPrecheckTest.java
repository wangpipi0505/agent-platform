package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.FakeProcessWorker;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * PLAT-21c 发布预检：把“命令/工作目录/outputSchema 写错”挡在 PUBLISHED 之前。
 *
 * <p>口径：publish 整体在数据库事务里，静态预检被抽成包私有 {@code processPrecheck} 以便无库直测；
 * 断言的是 400 + {@code PROCESS_PRECHECK_FAILED}，因为这类问题只能改契约，重试发布没有意义。
 */
class ProcessPublishPrecheckTest {

    @TempDir Path temp;

    private final SkillCatalog catalog = new SkillCatalog(null, null, null, "http://unused", true);

    @Test
    void acceptsExecutableAndSchemaThatExist() {
        ObjectNode candidate = candidate(FakeProcessWorker.javaExecutable());
        candidate.withObject("/process").putObject("outputSchema").put("type", "object");

        assertDoesNotThrow(() -> catalog.processPrecheck(asMap(candidate)));
    }

    @Test
    void rejectsRelativeCommand() {
        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.processPrecheck(asMap(candidate("java"))));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("绝对路径"), ex.getMessage());
    }

    @Test
    void rejectsCommandFileThatDoesNotExist() {
        Path missing = temp.resolve("没有这个可执行文件");
        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.processPrecheck(asMap(candidate(missing.toString()))));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
    }

    @Test
    void rejectsDirectoryAsCommand() {
        PlatformException ex =
                assertThrows(
                        PlatformException.class,
                        () -> catalog.processPrecheck(asMap(candidate(temp.toString()))));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("普通文件"), ex.getMessage());
    }

    @Test
    void rejectsRelativeWorkDirRoot() {
        ObjectNode candidate = candidate(FakeProcessWorker.javaExecutable());
        candidate.withObject("/process").put("workDirRoot", "relative/work");

        PlatformException ex =
                assertThrows(PlatformException.class, () -> catalog.processPrecheck(asMap(candidate)));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("workDirRoot"), ex.getMessage());
    }

    @Test
    void rejectsFileAsWorkDirRoot() throws Exception {
        Path file = Files.writeString(temp.resolve("workdir-is-a-file.txt"), "x");
        ObjectNode candidate = candidate(FakeProcessWorker.javaExecutable());
        candidate.withObject("/process").put("workDirRoot", file.toString());

        PlatformException ex =
                assertThrows(PlatformException.class, () -> catalog.processPrecheck(asMap(candidate)));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("不是目录"), ex.getMessage());
    }

    @Test
    void acceptsWorkDirRootThatCanBeCreatedWithoutCreatingIt() {
        Path notYet = temp.resolve("未来 工作目录").resolve("v1");
        ObjectNode candidate = candidate(FakeProcessWorker.javaExecutable());
        candidate.withObject("/process").put("workDirRoot", notYet.toString());

        assertDoesNotThrow(() -> catalog.processPrecheck(asMap(candidate)));
        // 发布事务可能回滚，预检不得真的留下目录。
        assertFalse(Files.exists(notYet), "预检只能探测可创建性，不能真的建目录");
    }

    @Test
    void rejectsOutputSchemaThatSkillRulesCannotLoad() {
        ObjectNode candidate = candidate(FakeProcessWorker.javaExecutable());
        candidate.withObject("/process").putObject("outputSchema");

        PlatformException ex =
                assertThrows(PlatformException.class, () -> catalog.processPrecheck(asMap(candidate)));

        assertEquals(400, ex.status());
        assertEquals("PROCESS_PRECHECK_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("outputSchema"), ex.getMessage());
    }

    @Test
    void timeoutOutOfRangeIsRejectedByContractParse() {
        ObjectNode tooLarge = candidate(FakeProcessWorker.javaExecutable());
        tooLarge.withObject("/process").put("timeoutMs", 1_800_001);
        PlatformException ex =
                assertThrows(PlatformException.class, () -> catalog.processPrecheck(asMap(tooLarge)));
        assertEquals(400, ex.status());

        ObjectNode zero = candidate(FakeProcessWorker.javaExecutable());
        zero.withObject("/process").put("timeoutMs", 0);
        PlatformException zeroEx =
                assertThrows(PlatformException.class, () -> catalog.processPrecheck(asMap(zero)));
        assertEquals(400, zeroEx.status());
    }

    @Test
    void nonProcessCandidateSkipsProcessRules() {
        ObjectNode http = Json.object();
        http.put("type", "HTTP");
        http.put("code", "orders.create");

        assertDoesNotThrow(() -> catalog.processPrecheck(asMap(http)));
    }

    private ObjectNode candidate(String command) {
        ObjectNode candidate = Json.object();
        candidate.put("type", "PROCESS");
        candidate.put("code", "revenue.forecast");
        ObjectNode process = candidate.putObject("process");
        process.putArray("command").add(command);
        process.put("timeoutMs", 60_000);
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(ObjectNode node) {
        return Json.mapper().convertValue(node, Map.class);
    }
}
