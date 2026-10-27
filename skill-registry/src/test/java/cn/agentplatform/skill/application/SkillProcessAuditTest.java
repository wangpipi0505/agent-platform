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
 * PLAT-21d：PROCESS 执行事实的审计与运行详情（复用既有 skill.execution_fact 写入路径，不新造通道）。
 *
 * <p>成功执行的 PROCESS 事实（命令模板、退出码、时长、workDir）随 Raw 落
 * {@code execution_fact.result.process}，经 {@code GET /admin/skills/tasks/{callId}/result} 可查；
 * 环境变量的键与值绝不落事实。失败路径仍按 Y-7 原样透出 PROCESS_*（错误码+exitCode+stderr 尾部），
 * 不写 execution_fact——失败现场由错误响应、结构化日志与失败转储目录承载（口径见证据 README §2）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class SkillProcessAuditTest {

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
        // 启用开关 + 白名单含假 worker 的可执行文件：与生产装配同一构造路径（enabled 必须配白名单）。
        adapters =
                new SkillAdapters(
                        new ArtifactStore(db),
                        new AlgorithmAdapter(db),
                        null,
                        new ProcessAdapter(
                                true,
                                processRoot.toString(),
                                4,
                                1,
                                30000,
                                2,
                                FakeProcessWorker.javaExecutable()));
        executor = new SkillExecution(db, manager, catalog, adapters, clients, "http://engine.test");

        code = "process_audit_" + UUID.randomUUID().toString().replace("-", "");
        version = ((Number) catalog.create(processContract()).get("skillVersionId")).longValue();
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
                                                        "r_audit",
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

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulExecutionLandsAuditFactInExecutionFactAndTaskResult() throws IOException {
        long callId = ids.incrementAndGet();
        var request = request(callId);

        var delivered = executor.execute(code, request);
        assertEquals("SUCCEEDED", delivered.get("status"));

        // 审计事实可查（execution_fact 是唯一业务权威源）：
        var rows =
                db.queryForList(
                        "select result from skill.execution_fact where tenant_id=? and call_id=?",
                        "skill-tests",
                        callId);
        assertEquals(1, rows.size(), "执行事实必须已落库");
        var envelope = Json.read(rows.get(0).get("result").toString());
        var process = envelope.path("process");
        assertTrue(process.isObject(), "envelope 必须携带 process 审计事实: " + envelope);
        assertTrue(process.has("command"), "命令模板必须可见");
        assertTrue(process.path("command").isArray() && process.path("command").size() > 0);
        assertEquals(0, process.path("exitCode").asInt(-1));
        assertTrue(process.path("durationMs").isNumber());
        assertTrue(process.path("workDir").asText().startsWith(processRoot.toString()));
        assertFalse(
                envelope.toString().contains("audit-secret-值-42"),
                "环境变量值绝不能进执行事实: " + envelope);
        assertFalse(envelope.toString().contains("AGENT_PROCESS_AUDIT_MARKER"), "环境变量名单键也不落事实");

        // 运行详情可查（门户口径 GET /admin/skills/tasks/{callId}/result）：
        var view = executor.taskResult(callId);
        @SuppressWarnings("unchecked")
        var resultMap = (Map<String, Object>) view.get("result");
        assertNotNull(resultMap.get("process"), "运行详情必须可见 process 审计事实");
    }

    @Test
    void failedExecutionSurfacesReadableProcessErrorWithoutFactRow() throws IOException {
        // exit=3 → 502 PROCESS_CALCULATION_FAILED（Y-7 语义保持：原样透出，不折叠、不写终态事实）。
        var process = new LinkedHashMap<String, Object>();
        process.put(
                "command",
                List.of(
                        FakeProcessWorker.javaExecutable(),
                        "-cp",
                        FakeProcessWorker.classpath(),
                        FakeProcessWorker.class.getName(),
                        "exit3"));
        process.put("timeoutMs", 20000);
        code = "process_audit_fail_" + UUID.randomUUID().toString().replace("-", "");
        version = ((Number) catalog.create(processContract(process)).get("skillVersionId")).longValue();
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
        // mock 的 claim 返回旧 version，重建桩。
        org.mockito.Mockito.reset(clients);
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
                                                Digest.sha256(Json.canonical(Map.of("region", "A"))))));
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
                                                                Json.canonical(Map.of("region", "A"))),
                                                        "node_instance_id",
                                                        123,
                                                        "runId",
                                                        "r_audit",
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

        long callId = ids.incrementAndGet();
        var ex = assertThrows(PlatformException.class, () -> executor.execute(code, request(callId)));
        assertEquals(502, ex.status());
        assertEquals("PROCESS_CALCULATION_FAILED", ex.code());
        assertTrue(ex.getMessage().contains("3"), "错误消息必须带 exitCode");
        var rows =
                db.queryForList(
                        "select result from skill.execution_fact where tenant_id=? and call_id=?",
                        "skill-tests",
                        callId);
        assertTrue(rows.isEmpty(), "Y-7 语义下失败结论原样上抛，不落终态执行事实（失败审计走错误响应+日志+转储）");
    }

    private Map<String, Object> request(long callId) {
        return new LinkedHashMap<>(
                Map.of(
                        "callId", callId,
                        "runId", "r_audit",
                        "nodeInstanceId", 123,
                        "attemptId", 456,
                        "skillVersionId", version,
                        "arguments", Map.of("region", "A"),
                        "idempotencyKey", "logical-key",
                        "leaseOwner", "worker-generation",
                        "triggerType", "FORMAL"));
    }

    private Map<String, Object> processContract() throws IOException {
        var process = new LinkedHashMap<String, Object>();
        process.put(
                "command",
                List.of(
                        FakeProcessWorker.javaExecutable(),
                        "-cp",
                        FakeProcessWorker.classpath(),
                        FakeProcessWorker.class.getName(),
                        "ok"));
        process.put("timeoutMs", 20000);
        process.put(
                "env",
                Map.of("AGENT_PROCESS_AUDIT_MARKER", "audit-secret-值-42"));
        return processContract(process);
    }

    private Map<String, Object> processContract(Map<String, Object> process) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", code);
        body.put("name", "本地算法进程审计");
        body.put("type", "PROCESS");
        body.put("description", "PLAT-21d 审计与运行详情");
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
