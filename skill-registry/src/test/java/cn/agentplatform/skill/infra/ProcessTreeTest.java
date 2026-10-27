package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/** PLAT-21 进程树原语：等待退出与整树终止（含孙子进程）。 */
@Timeout(120)
class ProcessTreeTest {

    @TempDir Path temp;

    @Test
    void startsAndWaitsForExit() throws Exception {
        Process process =
                ProcessTree.start(
                        ProcessTree.builder(FakeProcessWorker.command("ok"), temp, null));

        assertTrue(
                ProcessTree.awaitExit(process, Duration.ofSeconds(30)),
                "waitFor 超时，worker 未按预期退出");
        assertEquals(0, process.exitValue());
        String stdout =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stdout.contains("ok-stdout"));
    }

    @Test
    void killTreeKillsParentAndDescendant() throws Exception {
        Process process =
                ProcessTree.start(
                        ProcessTree.builder(FakeProcessWorker.command("tree"), temp, null));
        long parentPid = process.pid();
        long childPid;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(parentPid, readPid(reader, "parent="));
            childPid = readPid(reader, "child=");
        }
        assertTrue(isAlive(childPid), "孙进程应先存活，才能证明杀树有效");

        ProcessTree.killTree(process);

        awaitGone(parentPid, Duration.ofSeconds(15));
        awaitGone(childPid, Duration.ofSeconds(15));
        assertFalse(isAlive(parentPid), "父进程必须已终止");
        assertFalse(isAlive(childPid), "子进程不得被孤儿化后继续存活");
    }

    @Test
    void killTreeByHandleAlsoKillsDescendants() throws Exception {
        Process process =
                ProcessTree.start(
                        ProcessTree.builder(FakeProcessWorker.command("tree"), temp, null));
        long parentPid = process.pid();
        long childPid;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            readPid(reader, "parent=");
            childPid = readPid(reader, "child=");
        }

        ProcessTree.killTree(process.toHandle());

        awaitGone(parentPid, Duration.ofSeconds(15));
        awaitGone(childPid, Duration.ofSeconds(15));
        assertFalse(isAlive(parentPid), "父进程必须已终止");
        assertFalse(isAlive(childPid), "子进程不得被孤儿化后继续存活");
    }

    private static long readPid(BufferedReader reader, String prefix) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(prefix)) return Long.parseLong(line.substring(prefix.length()).trim());
        }
        fail("worker 未输出 " + prefix + " 行");
        return -1;
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void awaitGone(long pid, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && isAlive(pid)) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
