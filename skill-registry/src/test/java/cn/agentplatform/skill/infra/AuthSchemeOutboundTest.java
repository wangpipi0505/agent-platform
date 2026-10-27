package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecretVault;
import cn.agentplatform.common.auth.AuthScheme;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PLAT-11 出站鉴权头：契约里的 authScheme 决定真实发哪一个头。
 *
 * <p>用本机 HttpServer 记录收到的请求头，断言的是“线上字节”而不是内部方法调用：只有这样才能
 * 证明 API_KEY 技能没有在发 X-API-Key 的同时继续发 Authorization（多发的头会让对端按旧凭据拒绝）。
 */
class AuthSchemeOutboundTest {
    private static final String TENANT = "tenant-auth";

    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Path secretDir;
    private String previousDir;
    private String previousKey;

    @BeforeEach
    void setUp() throws Exception {
        received.clear();
        secretDir = Files.createTempDirectory("plat11-vault");
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "plat11-test-master-key-0123456789abcdef");
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/onto/query",
                exchange -> {
                    var headers = new LinkedHashMap<String, String>();
                    for (String name : List.of("Authorization", "X-API-Key"))
                        headers.put(name, exchange.getRequestHeaders().getFirst(name));
                    received.add(headers);
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        if (server != null) server.stop(0);
        if (previousDir == null) System.clearProperty("agent.secret.dir");
        else System.setProperty("agent.secret.dir", previousDir);
        if (previousKey == null) System.clearProperty("agent.master.key");
        else System.setProperty("agent.master.key", previousKey);
        if (secretDir != null)
            try (var files = Files.walk(secretDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
    }

    private static Actor actor() {
        return new Actor(TENANT, "editor", Set.of("ADMIN"), Map.of());
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/onto/query";
    }

    private static Map<String, Object> contract(Object scheme, String authRef) {
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("endpoint", "http://127.0.0.1:0/onto/query");
        contract.put("httpMethod", "POST");
        contract.put("timeoutMs", 2000);
        contract.put("inputSchema", Map.of("type", "object"));
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put("outputMaxBytes", 4096);
        contract.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        if (scheme != null) contract.put(AuthScheme.FIELD, scheme);
        if (authRef != null) contract.put("authRef", authRef);
        return contract;
    }

    /** 同步适配器的真实出站路径；endpoint 用当前桩端口重写，其余契约字段与归一化结果一致。 */
    private void invoke(Map<String, Object> contract) {
        contract.put("endpoint", url());
        new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class))
                .invoke(contract, Map.of(), "key-auth", 1, false, null, 1);
    }

    @Test
    void apiKeySendsOnlyXApiKey() {
        var contract = contract("API_KEY", SecretVault.put("ontopa_live_secret", actor()));
        invoke(contract);
        assertEquals(1, received.size());
        assertEquals("ontopa_live_secret", received.getFirst().get("X-API-Key"));
        assertNull(received.getFirst().get("Authorization"), "API_KEY不得再发Authorization");
    }

    @Test
    void undeclaredSchemeKeepsLegacyBearerForStoredContracts() {
        // 未经本卡重新保存的历史契约没有 authScheme 字段；执行侧必须继续按 BEARER 发头。
        var contract = contract(null, SecretVault.put("legacy-secret", actor()));
        invoke(contract);
        assertEquals(1, received.size());
        assertEquals("Bearer legacy-secret", received.getFirst().get("Authorization"));
        assertNull(received.getFirst().get("X-API-Key"));
    }

    @Test
    void noneSendsNoAuthHeader() {
        var contract = contract("NONE", null);
        invoke(contract);
        assertEquals(1, received.size());
        assertNull(received.getFirst().get("Authorization"));
        assertNull(received.getFirst().get("X-API-Key"));
    }

    @Test
    void unresolvableCredentialFailsWith401BeforeAnyRequest() {
        var contract = contract("BEARER", "vault:" + UUID.randomUUID());
        var ex = assertThrows(PlatformException.class, () -> invoke(contract));
        assertEquals(401, ex.status(), "凭据缺失属于身份/凭据问题而非远端业务失败");
        assertEquals(AuthScheme.CODE_CREDENTIAL_MISSING, ex.code());
        assertEquals(0, received.size(), "凭据不可用时不得先发一次无鉴权请求");
    }

    @Test
    void asyncAdapterAppliesTheSameSchemeMapping() {
        var adapter = new HttpAsyncAdapter(null);
        var apiKey = contract("API_KEY", SecretVault.put("async-secret", actor()));
        adapter.request(apiKey, url(), "POST", null, "key-async").retrieve().toBodilessEntity();
        assertEquals(1, received.size());
        assertEquals("async-secret", received.getFirst().get("X-API-Key"));
        assertNull(received.getFirst().get("Authorization"));

        var bearer = contract(null, SecretVault.put("async-legacy", actor()));
        adapter.request(bearer, url(), "POST", null, "key-async").retrieve().toBodilessEntity();
        assertEquals(2, received.size());
        assertEquals("Bearer async-legacy", received.get(1).get("Authorization"));
        assertNull(received.get(1).get("X-API-Key"));
    }
}
