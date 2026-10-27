package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.infra.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Y-7：PROCESS_* 错误必须原样穿出执行器，不能被“外部输出接收失败”折叠。
 *
 * <p>开关关闭时进程根本没有启动，调用方拿到 403 {@code PROCESS_DISABLED} 才能提示“请联系管理员开启本机进程”，
 * 而 502/FAILED 会被上层当成可重试的上游故障，导致反复重试一个永远不会执行的技能。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillProcessExecutionTest {

    static JdbcTemplate db;
    static DataSourceTransactionManager manager;
    static AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);

    @TempDir Path temp;
    Path processRoot;
    SkillCatalog catalog;
    SkillClients clients;
    SkillAdapters adapters;
    SkillExecution executor;
    long version;
    String code;

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
        manager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void prepare() throws IOException {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("skill-tester")
                        .claim("tenant_id", "skill-tests")
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        processRoot = Files.createDirectory(temp.resolve("process-root")).toRealPath();

        clients = mock(SkillClients.class);
        catalog = new SkillCatalog(db, manager, clients, "http://unused");
        // 真实适配器 + 关闭开关的进程执行器：Y-7 的病灶就在这条路径上。
        adapters =
                new SkillAdapters(
                        new ArtifactStore(db),
                        new AlgorithmAdapter(db),
                        null,
                        new ProcessAdapter(false, processRoot.toString()));
        executor = new SkillExecution(db, manager, catalog, adapters, clients, "http://engine.test");

        code = "process_" + UUID.randomUUID().toString().replace("-", "");
        version = ((Number) catalog.create(processContract()).get("skillVersionId")).longValue();
        // 与既有执行器测试相同：绕过发布物料，只验证执行路径。
        db.update(
                "update skill.skill_version set status='PUBLISHED' where tenant_id=? and id=?",
                "skill-tests",
                version);
        db.update(
                "update skill.skill set status='PUBLISHED',current_version_id=? where tenant_id=?"
                        + " and code=?",
                version,
                "skill-tests",
                code);

        when(clients.get(anyString()))
                .thenAnswer(
                        invocation ->
                                Json.tree(
                                        Map.of(
                                                "approved",
                                                true,
                                                "skillVersionId",
                                                version,
                                                "parametersDigest",
                                                Digest.sha256(
                                                        Json.canonical(
                                                                Map.of("region", "A"))))));
        when(clients.post(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            String url = invocation.getArgument(0);
                            if (url.endsWith("/claim")) {
                                var claim =
                                        Json.tree(
                                                Map.of(
                                                        "claimed",
                                                        true,
                                                        "status",
                                                        "EXECUTING",
                                                        "skill_version_id",
                                                        version,
                                                        "skill_code",
                                                        code,
                                                        "args_digest",
                                                        Digest.sha256(
                                                                Json.canonical(
                                                                        Map.of("region", "A"))),
                                                        "node_instance_id",
                                                        123,
                                                        "runId",
                                                        "r_test",
                                                        "trigger_type",
                                                        "TASK",
                                                        "idempotency_key",
                                                        "logical-key",
                                                        "mock_snapshot",
                                                        Map.of()));
                                ((com.fasterxml.jackson.databind.node.ObjectNode) claim)
                                        .put("attempt_id", 456L);
                                return claim;
                            }
                            if (url.endsWith("/submitted")) return Json.tree(Map.of("submitted", true));
                            if (url.endsWith("/fact")) return Json.tree(Map.of("accepted", true));
                            throw new AssertionError("unexpected internal request " + url);
                        });
    }

    @Test
    void disabledProcessFailsWith403InsteadOfGenericOutputFailure() throws IOException {
        var request =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "callId", ids.incrementAndGet(),
                                "runId", "r_test",
                                "nodeInstanceId", 123,
                                "attemptId", 456,
                                "skillVersionId", version,
                                "arguments", Map.of("region", "A"),
                                "idempotencyKey", "logical-key",
                                "leaseOwner", "worker-generation",
                                "triggerType", "FORMAL"));

        var ex = assertThrows(PlatformException.class, () -> executor.execute(code, request));

        assertEquals(403, ex.status(), "开关关闭必须保持 403，而不是折叠成 502/FAILED");        assertEquals("PROCESS_DISABLED", ex.code(), "错误码必须原样透出 PROCESS_DISABLED");
        try (var entries = Files.list(processRoot)) {
            assertTrue(entries.findAny().isEmpty(), "开关关闭时执行器不得创建 call-* 目录");
        }
    }

    private Map<String, Object> processContract() {
        var process = new LinkedHashMap<String, Object>();
        process.put(
                "command",
                List.of(
                        FakeProcessWorker.javaExecutable(),
                        "-cp",
                        FakeProcessWorker.classpath(),
                        FakeProcessWorker.class.getName(),
                        "ok"));
        process.put("timeoutMs", 5000);
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", "本地算法进程");
        body.put("type", "PROCESS");
        body.put("description", "PLAT-21c 协议化执行：关闭开关时必须返回 PROCESS_DISABLED");
        body.put("invocationKind", "SYNC");
        body.put("process", process);
        body.put("inputSchema", Map.of("type", "object"));
        body.put("outputSchema", Map.of("type", "object"));
        body.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", false,
                        "cancellable", false));
        body.put(
                "guard",
                Map.of("roles", List.of("EDITOR"), "attributes", Map.of("region", List.of("A"))));
        return body;
    }
}
