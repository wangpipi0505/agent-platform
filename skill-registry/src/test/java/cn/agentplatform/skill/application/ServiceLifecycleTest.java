package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.ServiceManifest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * AP-S05 生命周期集成测试。
 *
 * <p>测试进程是显式的 PowerShell 参数数组，健康端点用本机 TcpListener 实现；
 * 测试会真实启动、停止并清理进程，不使用模拟进程对象。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class ServiceLifecycleTest {
    static JdbcTemplate db;
    ServiceLifecycle lifecycle;
    String tenant;
    Path temp;
    int port;

    @BeforeAll
    static void migrate() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
    }

    @BeforeEach
    void prepare() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "生命周期进程夹具当前使用 Windows PowerShell");
        tenant = "service-test-" + UUID.randomUUID();
        temp = Files.createTempDirectory("service-lifecycle-").toAbsolutePath();
        port = freePort();
        identity(tenant);
        lifecycle = new ServiceLifecycle(db, temp.toString());
    }

    @AfterEach
    void cleanup() throws Exception {
        if (lifecycle != null) lifecycle.shutdown();
        db.update("delete from skill.service_audit where tenant_id=?", tenant);
        db.update("delete from skill.service_runtime where tenant_id=?", tenant);
        db.update("delete from skill.service_manifest where tenant_id=?", tenant);
        try (var files = Files.walk(temp)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void startsHealthChecksLogsStopsAndRestartsARealLocalProcess() throws Exception {
        var created = lifecycle.create(body(healthScript(port), port, "/health"));
        assertEquals("STOPPED", created.get("state"));

        var started = lifecycle.start("space_demo");
        assertEquals("READY", started.get("state"));
        assertTrue(((Number) started.get("pid")).longValue() > 0);
        assertEquals(true, started.get("healthy"));
        assertEquals("http://127.0.0.1:" + port + "/health", started.get("healthUrl"));

        var health = lifecycle.health("space_demo");
        assertEquals(true, health.get("healthy"));
        assertNotNull(health.get("httpStatus"));

        var logs = lifecycle.logs("space_demo", 50);
        assertEquals(true, logs.get("available"));

        var stopped = lifecycle.stop("space_demo", true);
        assertEquals("STOPPED", stopped.get("state"));
        assertFalse(processAlive(((Number) stopped.get("pid")).longValue()));

        var restarted = lifecycle.restart("space_demo", true);
        assertEquals("READY", restarted.get("state"));
        lifecycle.stop("space_demo", true);

        Integer audits =
                db.queryForObject(
                        "select count(*) from skill.service_audit where tenant_id=?", Integer.class,
                        tenant);
        assertNotNull(audits);
        assertTrue(audits >= 5, "CREATE/START/STOP/RESTART 都应留下审计");
    }

    @Test
    void refusesStopWithoutConfirmationAndNeverKillsTheProcess() {
        lifecycle.create(body(healthScript(port), port, "/health"));
        lifecycle.start("space_demo");

        PlatformException denied =
                assertThrows(PlatformException.class, () -> lifecycle.stop("space_demo", false));
        assertEquals(409, denied.status());
        assertEquals("SERVICE_CONFIRM_REQUIRED", denied.code());
        assertEquals("READY", lifecycle.get("space_demo").get("state"));
        lifecycle.stop("space_demo", true);
    }

    @Test
    void marksServiceFailedWhenProcessExitsBeforeHealthBecomesReady() {
        lifecycle.create(
                body(
                        powershellPath() + "|" + "-NoProfile" + "|" + "-Command" + "|exit 7",
                        port,
                        "/health"));

        PlatformException failed =
                assertThrows(PlatformException.class, () -> lifecycle.start("space_demo"));
        assertEquals(502, failed.status());
        assertEquals("SERVICE_EXITED", failed.code());

        var view = lifecycle.get("space_demo");
        assertEquals("FAILED", view.get("state"));
        assertTrue(view.get("lastError").toString().contains("exitCode=7"));
        Integer failedAudits =
                db.queryForObject(
                        "select count(*) from skill.service_audit where tenant_id=? and result='FAILED'",
                        Integer.class,
                        tenant);
        assertEquals(1, failedAudits);
    }

    private Map<String, Object> body(String command, int port, String healthPath) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", "space_demo");
        body.put("name", "空间演示服务");
        body.put("command", List.of(command.split("\\|")));
        body.put("workDir", temp.toString());
        body.put("port", port);
        body.put("healthPath", healthPath);
        body.put("startupTimeoutMs", 20_000);
        body.put("stopTimeoutMs", 4_000);
        return body;
    }

    private String healthScript(int port) {
        String script =
                "$l=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,"
                        + port
                        + ");$l.Start();while($true){$c=$l.AcceptTcpClient();$s=$c.GetStream();"
                        + "$b=New-Object byte[] 1024;$null=$s.Read($b,0,1024);"
                        + "$r=[Text.Encoding]::UTF8.GetBytes('HTTP/1.1 200 OK'+[char]13+[char]10"
                        + "+'Content-Length: 2'+[char]13+[char]10+'Connection: close'+[char]13+[char]10"
                        + "+[char]13+[char]10+'ok');"
                        + "$s.Write($r,0,$r.Length);$s.Close();$c.Close()}";
        return powershellPath() + "|" + "-NoProfile" + "|" + "-Command" + "|" + script;
    }

    private String powershellPath() {
        return Path.of(
                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                        "System32",
                        "WindowsPowerShell",
                        "v1.0",
                        "powershell.exe")
                .toString();
    }

    private int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean processAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private void identity(String tenant) {
        var jwt =
                Jwt.withTokenValue("verified-service-test")
                        .header("alg", "HS256")
                        .subject("service-admin")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
