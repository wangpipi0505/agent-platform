package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * PLAT-21 契约结构校验：字段形态、缺省值、越界错误码。
 *
 * <p>只校验契约本身，不启动任何进程；可执行文件是否存在由发布期（T21b）与执行期共同把关。
 */
class ProcessContractTest {

    @TempDir Path root;

    @Test
    void minimalContractUsesDocumentedDefaults() {
        ObjectNode json = Json.object();
        json.putArray("command").add(javaExecutable());

        ProcessContract contract = ProcessContract.parse(json);

        assertEquals(1, contract.command().size());
        assertEquals(javaExecutable(), contract.command().getFirst());
        assertNull(contract.workDirRoot(), "未声明 workDirRoot 时留给执行期配置的允许根");
        assertEquals(Duration.ofSeconds(60), contract.timeout());
        assertEquals(1024 * 1024, contract.maxOutputBytes());
        assertTrue(contract.allowedEnv().isEmpty());
        assertNull(contract.stdinJson());
    }

    @Test
    void fullContractIsParsedVerbatim() {
        ObjectNode json = Json.object();
        json.putArray("command").add(javaExecutable()).add("arg with space").add("中文");
        json.put("workDirRoot", root.toString());
        json.put("timeoutMs", 1500);
        json.put("maxOutputBytes", 4096);
        json.putObject("env").put("AGENT_PROCESS_TOKEN", "abc").put("AGENT_PROCESS_LANG", "zh");
        ObjectNode stdin = json.putObject("stdin");
        stdin.put("hello", "世界");

        ProcessContract contract = ProcessContract.parse(json);

        assertEquals(3, contract.command().size());
        assertEquals("arg with space", contract.command().get(1));
        assertEquals("中文", contract.command().get(2));
        assertEquals(root.toString(), contract.workDirRoot());
        assertEquals(Duration.ofMillis(1500), contract.timeout());
        assertEquals(4096, contract.maxOutputBytes());
        assertEquals(
                Map.of("AGENT_PROCESS_TOKEN", "abc", "AGENT_PROCESS_LANG", "zh"),
                contract.allowedEnv());
        assertEquals("世界", contract.stdinJson().path("hello").asText());
    }

    @Test
    void rejectsMissingOrEmptyCommand() {
        ObjectNode missing = Json.object();
        assertInvalid(missing, "command");

        ObjectNode empty = Json.object();
        empty.putArray("command");
        assertInvalid(empty, "command");
    }

    @Test
    void rejectsRelativeExecutable() {
        ObjectNode json = Json.object();
        json.putArray("command").add("bin/worker");

        assertInvalid(json, "command");
    }

    @Test
    void rejectsNonAbsoluteWorkDir() {
        ObjectNode json = Json.object();
        json.putArray("command").add(javaExecutable());
        json.put("workDirRoot", "relative/dir");

        assertInvalid(json, "workDirRoot");
    }

    @Test
    void rejectsTimeoutOutsideDocumentedRange() {
        ObjectNode zero = base();
        zero.put("timeoutMs", 0);
        assertInvalid(zero, "timeoutMs");

        ObjectNode tooLong = base();
        tooLong.put("timeoutMs", Duration.ofMinutes(30).toMillis() + 1);
        assertInvalid(tooLong, "timeoutMs");

        ObjectNode notNumber = base();
        notNumber.put("timeoutMs", "60s");
        assertInvalid(notNumber, "timeoutMs");
    }

    @Test
    void rejectsOutputBudgetAboveHardLimit() {
        ObjectNode zero = base();
        zero.put("maxOutputBytes", 0);
        assertInvalid(zero, "maxOutputBytes");

        ObjectNode tooBig = base();
        tooBig.put("maxOutputBytes", 1024 * 1024 + 1);
        assertInvalid(tooBig, "maxOutputBytes");
    }

    @Test
    void rejectsInvalidEnvironmentEntries() {
        ObjectNode badName = base();
        badName.putObject("env").put("bad-name", "v");
        assertInvalid(badName, "env");

        ObjectNode newlineValue = base();
        newlineValue.putObject("env").put("AGENT_PROCESS_X", "a\nb");
        assertInvalid(newlineValue, "env");

        ObjectNode reserved = base();
        reserved.putObject("env").put("PATH", "/tmp/evil");
        assertInvalid(reserved, "env");
    }

    @Test
    void rejectsNonObjectContract() {
        assertInvalid(Json.read("[]"), "process");
        assertInvalid(Json.read("null"), "process");
    }

    // ---- 与契约 §4.2 的 executable + args 形态对齐（T21c-fix）----

    @Test
    void executableAndArgsAreNormalizedToCommand() {
        ObjectNode json = Json.object();
        json.put("executable", javaExecutable());
        json.putArray("args").add("-jar").add("worker.jar").add("--mode").add("search");

        ProcessContract contract = ProcessContract.parse(json);

        assertEquals(
                java.util.List.of(javaExecutable(), "-jar", "worker.jar", "--mode", "search"),
                contract.command());
    }

    @Test
    void executableWithoutArgsIsSingleElementCommand() {
        ObjectNode json = Json.object();
        json.put("executable", javaExecutable());

        assertEquals(java.util.List.of(javaExecutable()), ProcessContract.parse(json).command());
    }

    @Test
    void commandWinsWhenBothFormsArePresent() {
        ObjectNode json = Json.object();
        json.putArray("command").add(javaExecutable()).add("-version");
        json.put("executable", "C:/nowhere/other.exe");

        assertEquals(2, ProcessContract.parse(json).command().size());
    }

    @Test
    void documentedAliasesAreAccepted() {
        ObjectNode json = Json.object();
        json.put("executable", javaExecutable());
        json.put("workDir", root.toAbsolutePath().toString());
        json.put("maxStdoutBytes", 65536);
        json.putObject("envAllowlist").put("AGENT_PROCESS_X", root.toString());
        json.put("cleanup", "ALWAYS");

        ProcessContract contract = ProcessContract.parse(json);

        assertEquals(root.toAbsolutePath().toString(), contract.workDirRoot());
        assertEquals(65536, contract.maxOutputBytes());
        assertEquals(root.toString(), contract.allowedEnv().get("AGENT_PROCESS_X"));
    }

    @Test
    void unknownCleanupValueIsRejected() {
        ObjectNode json = base();
        json.put("cleanup", "NEVER");

        assertInvalid(json, "cleanup");
    }

    @Test
    void envAllowlistArrayIsRejectedWithGuidance() {
        ObjectNode json = base();
        json.putArray("envAllowlist").add("JAVA_HOME").add("TEMP");

        assertInvalid(json, "envAllowlist");
    }

    @Test
    void maxStderrBytesIsRangeChecked() {
        ObjectNode ok = base();
        ok.put("maxStderrBytes", 65536);
        ProcessContract.parse(ok);

        ObjectNode bad = base();
        bad.put("maxStderrBytes", 0);
        assertInvalid(bad, "maxStderrBytes");
    }

    @Test
    void missingCommandAndExecutableIsRejected() {
        assertInvalid(Json.object(), "executable");
    }

    @Test
    void rejectsNonBooleanFilesOptIn() {
        for (String value : java.util.List.of("\"true\"", "1", "{}", "[]", "null")) {
            ObjectNode json = base();
            json.putObject("stdin").set("files", Json.read(value));

            assertInvalid(json, "stdin.files");
        }
    }

    @Test
    void falseFilesOptInAndLegacyStdinRemainUnchanged() {
        ObjectNode disabled = base();
        disabled.putObject("stdin").put("files", false).put("hello", "世界");
        assertEquals(disabled.path("stdin"), ProcessContract.parse(disabled).stdinJson());

        ObjectNode legacy = base();
        legacy.putObject("stdin").put("hello", "世界");
        assertEquals(legacy.path("stdin"), ProcessContract.parse(legacy).stdinJson());
    }

    private ObjectNode base() {
        ObjectNode json = Json.object();
        json.putArray("command").add(javaExecutable());
        return json;
    }

    private static void assertInvalid(JsonNode json, String field) {
        PlatformException ex =
                assertThrows(PlatformException.class, () -> ProcessContract.parse(json));
        assertEquals(400, ex.status());
        assertEquals("PROCESS_CONTRACT_INVALID", ex.code());
        assertTrue(
                ex.getMessage().contains(field),
                "错误消息应指明字段 " + field + "，实际: " + ex.getMessage());
    }

    private static String javaExecutable() {
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }
}
