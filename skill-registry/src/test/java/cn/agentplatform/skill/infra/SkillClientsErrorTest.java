package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.SecurityConfiguration;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-8b：下游错误保留原码/状态，票据签发失败原样上抛。
 *
 * <p>桩服务器用 JDK {@code HttpServer}，每个用例只换"状态码 + 正文"；HTTP 传输本身不参与断言。
 * 父 JVM 需要 ServiceTokens 处于可签发的 test 部署模式（否则连桩都到不了），因此用例 1-4、6 在
 * {@link #start()} 里显式注册；IAM 是不可回退的一次性部署状态（{@code ServiceTokens.registerDeployment}
 * 只允许 legacy -> iam），会污染同 JVM 的其它用例/测试类，所以用例 5 放到子 JVM 里跑。
 */
class SkillClientsErrorTest {
    static final Actor ACTOR = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of());

    /** 只够 ServiceTokens 签发 service 票据；与本用例的断言无关。 */
    static final String JWT_SECRET = "g8b-skill-clients-test-secret-0123456789abcdef";

    record Stub(int status, String contentType, String body) {}

    static final AtomicReference<Stub> STUB = new AtomicReference<>();
    static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    static HttpServer server;
    static String url;
    static SkillClients clients;

    @BeforeAll
    static void start() throws Exception {
        // deploy mode 是类加载器级状态：先注册 test，父 JVM 里 ServiceTokens.issue 才能出票。
        System.setProperty("agent.jwt.secret", JWT_SECRET);
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "g8b-skill-clients-test",
                                Map.of(
                                        "agent.security.mode", "test",
                                        "agent.security.service-id", "skill",
                                        "server.address", "127.0.0.1")));
        environment.setActiveProfiles("test");
        SecurityConfiguration.bindDeployment(environment);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/g8b",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    REQUESTS.add(
                            exchange.getRequestMethod()
                                    + " "
                                    + exchange.getRequestURI().getPath()
                                    + " "
                                    + Objects.toString(
                                            exchange.getRequestHeaders().getFirst("Authorization"), ""));
                    var stub = STUB.get();
                    byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stub.contentType());
                    if (body.length == 0) {
                        exchange.sendResponseHeaders(stub.status(), -1);
                    } else {
                        exchange.sendResponseHeaders(stub.status(), body.length);
                        exchange.getResponseBody().write(body);
                    }
                    exchange.close();
                });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/g8b";
        clients = new SkillClients();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
        System.clearProperty("agent.jwt.secret");
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
    }

    @AfterEach
    void clearStub() {
        STUB.set(null);
    }

    @Test
    void downstreamNotFoundKeepsCodeAndStatus() {
        STUB.set(new Stub(404, "application/json", "{\"code\":\"SKILL_NOT_FOUND\",\"message\":\"技能不存在\"}"));

        var error = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));

        assertThat(error.code()).isEqualTo("SKILL_NOT_FOUND");
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.getMessage()).isEqualTo("技能不存在");
        // 只发生一次 POST；票据仍在 Authorization 头里，未因错误路径改协议。
        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.get(0)).startsWith("POST /g8b Bearer ");
    }

    @Test
    void downstreamCodesKeepTheirOwnStatusInsteadOf502() {
        STUB.set(new Stub(403, "application/json", "{\"code\":\"SCOPE_DENIED\",\"message\":\"越权\"}"));
        var denied = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));
        assertThat(denied.code()).isEqualTo("SCOPE_DENIED");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.getMessage()).isEqualTo("越权");

        // 卡片 statusOf 表的其余分支：冲突类 409、未知错误码 400。
        STUB.set(new Stub(409, "application/json", "{\"code\":\"SKILL_CONFLICT\",\"message\":\"已被占用\"}"));
        var conflict = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));
        assertThat(conflict.code()).isEqualTo("SKILL_CONFLICT");
        assertThat(conflict.status()).isEqualTo(409);

        STUB.set(new Stub(400, "application/json", "{\"code\":\"SKILL_BAD_INPUT\",\"message\":\"参数不合法\"}"));
        var badInput = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));
        assertThat(badInput.code()).isEqualTo("SKILL_BAD_INPUT");
        assertThat(badInput.status()).isEqualTo(400);
    }

    @Test
    void downstreamServerErrorStaysDependencyUnavailable() {
        STUB.set(new Stub(500, "text/plain", ""));

        var error = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));

        assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(502);
    }

    @Test
    void downstreamClientErrorWithoutEnvelopeStaysDependencyUnavailable() {
        STUB.set(new Stub(400, "text/plain", "not a json body"));

        var error = failure(() -> clients.post(url, Map.of("code", "skill-a"), ACTOR));

        assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(502);
    }

    @Test
    void ticketIssuanceFailureIsNotFoldedIntoDependencyUnavailable() throws Exception {
        Path output = Files.createTempFile("g8b-skill-clients-ticket", ".log");
        Process child =
                new ProcessBuilder(
                                javaBinary().toString(),
                                "-cp",
                                childClasspath(),
                                SkillClientsErrorTest.class.getName(),
                                "ticket-failure")
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            boolean finished = child.waitFor(45, TimeUnit.SECONDS);
            if (!finished) child.destroyForcibly();
            String raw = Files.readString(output);
            System.out.print(raw);
            assertThat(finished).as("有界子进程").isTrue();
            assertThat(child.exitValue()).as(raw).isZero();
            assertThat(raw)
                    .contains("G8B_TICKET_FAILURE=403/AUTHORITY_CONTEXT_MISSING")
                    .doesNotContain("DEPENDENCY_UNAVAILABLE");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            Files.deleteIfExists(output);
        }
    }

    @Test
    void successEnvelopeShapeIsUnchanged() {
        STUB.set(new Stub(200, "application/json", "{\"code\":\"OK\",\"data\":{\"value\":1}}"));
        assertThat(clients.post(url, Map.of("code", "skill-a"), ACTOR).path("value").asInt())
                .isEqualTo(1);

        // data 缺省时返回整个信封，与改动前一致。
        STUB.set(new Stub(200, "application/json", "{\"value\":2}"));
        assertThat(clients.post(url, Map.of("code", "skill-a"), ACTOR).path("value").asInt())
                .isEqualTo(2);
    }

    /**
     * 子 JVM 入口：只验证"票据签发失败原样上抛"这一条。IAM 部署一旦注册不可回退，放在父 JVM 会
     * 破坏同进程其它用例的 test 部署；子进程里注册 IAM 后调用真实 {@link SkillClients#post}，
     * 用不可达端口确保根本没走到 HTTP——失败只能来自票据。
     */
    public static void main(String[] args) {
        if (args.length != 1 || !"ticket-failure".equals(args[0]))
            throw new AssertionError("unknown child scenario");

        var properties = new LinkedHashMap<String, Object>();
        properties.put("agent.security.mode", "iam");
        properties.put("agent.security.service-id", "skill");
        // 测试环境导出的 AGENT_DEV_AUTH 会经 Spring 松散绑定成为 agent.dev-auth=true，IAM 下被
        // IAM_DEV_AUTH_FORBIDDEN 拒绝；子进程显式压成 false，保证这里只考察票据失败本身。
        properties.put("agent.dev-auth", false);
        properties.put("agent.security.authority.issuer", "https://authority.invalid");
        properties.put("agent.security.authority.jwks-uri", "https://authority.invalid/jwks");
        properties.put("agent.security.authority.token-uri", "https://authority.invalid/token");
        properties.put("agent.security.authority.decision-uri", "https://authority.invalid/decision");
        properties.put("agent.security.authority.client-id", "skill");
        properties.put("agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_SKILL_CLIENT");
        properties.put("agent.security.authority.allowed-algorithm", "RS256");
        properties.put("agent.security.authority.audience", "urn:agent-platform:service:skill");
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("g8b-iam-deployment", properties));
        SecurityConfiguration.bindDeployment(environment);

        try {
            new SkillClients()
                    .post("http://127.0.0.1:9/g8b-ticket-only", Map.of("code", "skill-a"), ACTOR);
        } catch (PlatformException error) {
            if (error.status() != 403 || !"AUTHORITY_CONTEXT_MISSING".equals(error.code()))
                throw new AssertionError(
                        "ticket failure folded: status=" + error.status() + " code=" + error.code());
            System.out.println(
                    "G8B_TICKET_FAILURE=" + error.status() + "/" + error.code());
            return;
        }
        throw new AssertionError("expected PlatformException from ticket issuance");
    }

    private static PlatformException failure(Call call) {
        try {
            call.run();
        } catch (PlatformException error) {
            return error;
        } catch (RuntimeException error) {
            throw new AssertionError("expected PlatformException but got " + error, error);
        } catch (Exception error) {
            throw new AssertionError("expected PlatformException but got " + error, error);
        }
        throw new AssertionError("expected PlatformException but nothing was thrown");
    }

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }

    /** surefire 的隔离类加载器下 java.class.path 可能只有 booter jar，优先用其提供的真实测试类路径。 */
    private static String childClasspath() {
        String classpath = System.getProperty("surefire.test.class.path");
        return classpath == null || classpath.isBlank()
                ? System.getProperty("java.class.path")
                : classpath;
    }

    private static Path javaBinary() {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return Files.exists(executable) ? executable : Path.of(System.getProperty("java.home"), "bin", "java");
    }
}
