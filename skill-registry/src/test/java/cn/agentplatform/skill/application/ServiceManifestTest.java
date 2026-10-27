package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.domain.ServiceManifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class ServiceManifestTest {
    @TempDir Path dir;

    @Test
    void parsesExplicitCommandAndRejectsShellFragments() throws Exception {
        Path executable = Files.createFile(dir.resolve("service.exe"));
        var body = new HashMap<String, Object>();
        body.put("code", "space_demo");
        body.put("name", "空间演示服务");
        body.put("command", List.of(executable.toString(), "--port", "8080"));
        body.put("workDir", dir.toString());
        body.put("port", 8080);
        body.put("healthPath", "/health");
        body.put("startupTimeoutMs", 15_000);
        body.put("stopTimeoutMs", 2_000);

        ServiceManifest manifest = ServiceManifest.parse(body);

        assertEquals("space_demo", manifest.code());
        assertEquals(List.of(executable.toString(), "--port", "8080"), manifest.command());
        assertEquals("http://127.0.0.1:8080/health", manifest.healthUri().toString());
        assertEquals(15_000, manifest.startupTimeoutMs());
        assertTrue(manifest.enabled());
    }

    @Test
    void rejectsRelativeExecutableAndWorkingDirectory() throws Exception {
        Path executable = Files.createFile(dir.resolve("service.exe"));
        var body = valid(executable);
        body.put("command", List.of("service.exe"));
        invalid(body);

        body = valid(executable);
        body.put("workDir", "relative-dir");
        invalid(body);
    }

    @Test
    void rejectsPortAndHealthPathThatCouldEscapeTheManagedEndpoint() throws Exception {
        Path executable = Files.createFile(dir.resolve("service.exe"));
        var body = valid(executable);
        body.put("port", 0);
        invalid(body);

        body = valid(executable);
        body.put("healthPath", "/health?token=secret");
        invalid(body);

        body = valid(executable);
        body.put("healthPath", "/../admin");
        invalid(body);
    }

    @Test
    void rejectsNewlinesInArgumentsSoTheyCannotBecomeShellCommands() throws Exception {
        Path executable = Files.createFile(dir.resolve("service.exe"));
        var body = valid(executable);
        body.put("command", List.of(executable.toString(), "ok\nrm -rf /"));
        invalid(body);
    }

    private void invalid(Map<String, Object> body) {
        assertEquals("INVALID_SERVICE",
                assertThrows(PlatformException.class, () -> ServiceManifest.parse(body)).code());
    }

    private Map<String, Object> valid(Path executable) {
        var body = new HashMap<String, Object>();
        body.put("code", "space_demo");
        body.put("name", "空间演示服务");
        body.put("command", List.of(executable.toString()));
        body.put("workDir", dir.toString());
        body.put("port", 18090);
        body.put("healthPath", "/health");
        return body;
    }
}
