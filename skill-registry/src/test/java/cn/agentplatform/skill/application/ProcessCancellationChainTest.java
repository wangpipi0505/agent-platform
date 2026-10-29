package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.api.SkillController;
import cn.agentplatform.skill.infra.*;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 PG/管理 Controller/签名 engine peer/本地进程。此处只验 skill 取消桥，不冒充 run 出站端到端。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@Timeout(120)
class ProcessCancellationChainTest {
    @TempDir Path temp;
    SkillCatalog catalog;
    SkillAdapters adapters;
    ProcessAdapter process;
    MockMvc mvc;
    HttpServer engine;
    ExecutorService peerExecutor;
    String tenant, principal, code, engineUrl;
    long version;
    final AtomicReference<Map<String, Object>> authority = new AtomicReference<>();
    final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    final List<Thread> callers = new ArrayList<>();
    final List<Long> callIds = new ArrayList<>();

    @BeforeAll static void database() {
        SkillExecutionTest.migrate();
        var env = new MockEnvironment().withProperty("agent.security.mode", "test")
                .withProperty("agent.security.service-id", "skill").withProperty("server.address", "127.0.0.1");
        env.setActiveProfiles("test"); SecurityConfiguration.bindDeployment(env);
    }
    @BeforeEach void setup() throws Exception {
        tenant = "process-cancel-" + UUID.randomUUID(); principal = "process-owner";
        actor(tenant, principal, "ADMIN", "EDITOR", "SERVICE");
        Path root = Files.createDirectories(temp.resolve("取消 中文 空格 根")).toRealPath();
        process = new ProcessAdapter(true, root.toString(), 1, 1, 30000, 0, FakeProcessWorker.javaExecutable());
        adapters = new SkillAdapters(null, null, null, process);
        engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peerExecutor = Executors.newSingleThreadExecutor(); engine.setExecutor(peerExecutor);
        engine.createContext("/internal/tool-calls/", exchange -> {
            try (exchange) {
                try {
                    var token = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                    assertTrue(token.verify(new MACVerifier(ServiceTokens.secretBytes())));
                    var claims = token.getJWTClaimsSet();
                    boolean owned = tenant.equals(claims.getStringClaim("tenant_id")) && principal.equals(claims.getSubject());
                    var current = authority.get();
                    boolean sameCall = current != null && exchange.getRequestURI().getPath().endsWith("/" + current.get("callId"));
                    int status = owned && sameCall ? 200 : 404;
                    byte[] bytes = Json.write(Map.of("code", status == 200 ? "OK" : "CALL_NOT_FOUND",
                            "message", "", "data", status == 200 ? current : Map.of())).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) { peerFailure.compareAndSet(null, failure); }
            }
        });
        engine.start(); engineUrl = "http://127.0.0.1:" + engine.getAddress().getPort();
        var clients = new SkillClients();
        catalog = new SkillCatalog(SkillExecutionTest.db, SkillExecutionTest.manager, clients, "http://127.0.0.1:1");
        code = "cancel_" + UUID.randomUUID().toString().replace("-", "");
        var created = catalog.create(Map.of("code", code, "name", "取消桥测试", "type", "PROCESS", "description", "真实进程取消夹具",
                "authScheme", "NONE", "inputSchema", Map.of("type", "object"), "outputSchema", Map.of("type", "object"),
                "execution", Map.of("readonly", true, "sideEffect", false, "idempotent", true, "cancellable", true),
                "process", Map.of("command", FakeProcessWorker.command("ok"), "timeoutMs", 60000)));
        version = ((Number) created.get("skillVersionId")).longValue();
        var execution = new SkillExecution(SkillExecutionTest.db, SkillExecutionTest.manager, catalog, adapters, clients, engineUrl);
        mvc = MockMvcBuilders.standaloneSetup(new SkillController(catalog, execution, null)).setControllerAdvice(new PlatformErrors()).build();
    }
    @AfterEach void cleanup() throws Exception {
        for (long id : callIds) process.cancel(id); // 只回收本用例自行启动的句柄，不扫描系统进程。
        for (Thread caller : callers) { caller.join(5000); if (caller.isAlive()) { caller.interrupt(); caller.join(5000); } }
        if (engine != null) engine.stop(0);
        if (peerExecutor != null) peerExecutor.shutdownNow();
        actor(tenant, principal, "ADMIN", "EDITOR", "SERVICE");
        if (catalog != null && code != null) catalog.delete(code);
        SecurityContextHolder.clearContext();
        if (peerFailure.get() != null) throw new AssertionError("取消权威 peer 失败", peerFailure.get());
    }
    static void actor(String tenant, String principal, String... roles) {
        var jwt = Jwt.withTokenValue("verified-fixture").header("alg", "HS256").subject(principal)
                .claim("tenant_id", tenant).claim("roles", List.of(roles)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    Map<String, Object> plan(long call, long attempt) { return Map.of("runId", "r_cancel", "attemptId", attempt, "skillVersionId", version); }
    void permission(long call, long attempt, String state) {
        authority.set(Map.of("callId", call, "runId", "r_cancel", "attempt_id", attempt, "skill_version_id", version,
                "skill_code", code, "runStatus", state, "status", "SUBMITTED"));
    }
    Map<String, Object> cancel(long call, Map<String, Object> body, int status) throws Exception {
        var response = mvc.perform(post("/internal/skill-calls/{callId}/cancel", call).contentType("application/json").content(Json.write(body))).andReturn().getResponse();
        assertEquals(status, response.getStatus(), "PROCESS_CANCEL_BRIDGE_STATUS");
        return SkillCatalog.object(Json.read(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
    }
    AtomicReference<Throwable> start(long call, long attempt, Path pidFile) {
        var contract = new LinkedHashMap<>(catalog.versionId(version));
        var command = new ArrayList<>(FakeProcessWorker.command("ok"));
        command.set(command.size() - 2, Worker.class.getName()); // 保持同一纯 JDK 测试 classpath。
        command.set(command.size() - 1, pidFile.toString());
        contract.put("process", Map.of("command", command, "timeoutMs", 60000));
        var result = new AtomicReference<Throwable>();
        Thread caller = Thread.ofPlatform().start(() -> {
            actor(tenant, principal, "SERVICE");
            try { adapters.observe(contract, Map.of(), "cancel-key", call, false, "r_cancel", attempt); }
            catch (Throwable failure) { result.set(failure); }
            finally { SecurityContextHolder.clearContext(); }
        });
        callers.add(caller); callIds.add(call); return result;
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "等待确定性进程见证超时");
    }
    List<Long> pids(Path file) throws Exception {
        await(() -> Files.isRegularFile(file) && size(file) > 3);
        return Files.readAllLines(file).stream().map(Long::parseLong).toList();
    }
    static long size(Path file) { try { return Files.size(file); } catch (Exception ex) { return 0; } }
    static boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }

    @Test void runningTreeStopsAndDuplicateCancellationIsExplained() throws Exception {
        Path pidFile = temp.resolve("进程树 pid.txt"); var failure = start(101, 1, pidFile); var ids = pids(pidFile);
        assertEquals(2, ids.size()); permission(101, 1, "CANCELLED");
        var receipt = SkillCatalog.object(cancel(101, plan(101, 1), 200).get("data"));
        assertEquals(true, receipt.get("localCallMatched"));
        await(() -> ids.stream().noneMatch(ProcessCancellationChainTest::alive));
        callers.getFirst().join(5000);
        assertEquals("PROCESS_CANCELLED", assertInstanceOf(PlatformException.class, failure.get()).code());
        var duplicate = SkillCatalog.object(cancel(101, plan(101, 1), 200).get("data"));
        assertEquals(false, duplicate.get("localCallMatched")); assertEquals("NO_LOCAL_MATCH", duplicate.get("reason"));
    }
    @Test void rolesOwnershipAndCommittedRunStateAreRequiredBeforeCancellation() throws Exception {
        Path pidFile = temp.resolve("权限 pid.txt"); start(102, 1, pidFile); var ids = pids(pidFile); permission(102, 1, "CANCELLED");
        actor(tenant, principal, "EDITOR"); cancel(102, plan(102, 1), 403);
        actor(tenant + "-other", principal, "SERVICE"); cancel(102, plan(102, 1), 404);
        actor(tenant, principal + "-other", "SERVICE"); cancel(102, plan(102, 1), 404);
        actor(tenant, principal, "SERVICE"); cancel(102, plan(102, 2), 409);
        cancel(102, Map.of("runId", "r_other", "attemptId", 1, "skillVersionId", version), 409);
        permission(102, 1, "RUNNING"); cancel(102, plan(102, 1), 409);
        assertTrue(ids.stream().allMatch(ProcessCancellationChainTest::alive));
    }
    @Test void authorizedOldAttemptCannotKillANewerLocalGeneration() throws Exception {
        Path pidFile = temp.resolve("新代 pid.txt"); var failure = start(103, 2, pidFile); var ids = pids(pidFile);
        permission(103, 1, "CANCELLED");
        var stale = SkillCatalog.object(cancel(103, plan(103, 1), 200).get("data"));
        assertEquals(false, stale.get("localCallMatched")); assertTrue(ids.stream().allMatch(ProcessCancellationChainTest::alive));
        permission(103, 2, "CANCELLED"); cancel(103, plan(103, 2), 200);
        callers.getFirst().join(5000); assertEquals("PROCESS_CANCELLED", assertInstanceOf(PlatformException.class, failure.get()).code());
    }
    @Test void cancellingQueuedGenerationPreventsItsWorkerFromStarting() throws Exception {
        Path firstPid = temp.resolve("占用 pid.txt"), queuedPid = temp.resolve("排队 pid.txt");
        start(104, 1, firstPid); pids(firstPid);
        var queued = start(105, 1, queuedPid); await(() -> process.inFlight("105"));
        permission(105, 1, "CANCELLED");
        assertEquals(true, SkillCatalog.object(cancel(105, plan(105, 1), 200).get("data")).get("localCallMatched"));
        permission(104, 1, "CANCELLED"); cancel(104, plan(104, 1), 200);
        callers.get(1).join(10000); assertFalse(callers.get(1).isAlive());
        assertEquals("PROCESS_CANCELLED", assertInstanceOf(PlatformException.class, queued.get()).code());
        assertFalse(Files.exists(queuedPid), "已取消的排队调用不得迟到启动 worker");
    }
    /** 双层真实 JVM 仅写 PID 见证；禁止触碰已有服务、数据库进程或任意 PID。 */
    public static final class Worker {
        public static void main(String[] args) throws Exception {
            Process child = new ProcessBuilder(FakeProcessWorker.command("sleepquiet", "", "60000")).start();
            Files.writeString(Path.of(args[0]), ProcessHandle.current().pid() + "\n" + child.pid() + "\n", StandardCharsets.UTF_8);
            Thread.sleep(60000);
        }
    }
}
