package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.SubjectSnapshot;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.SkillRules;

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
 * PLAT-12 出站身份头注入：声明了 identity 的技能出站追加 X-User-*，值只来自已验主体快照；
 * 与 AuthScheme 鉴权头是叠加关系（鉴权头照发、身份头追加）；无可用主体（系统级调用）时跳过
 * 注入不伪造值。
 *
 * <p>用本机 HttpServer 记录收到的请求头，断言"线上字节"而不是内部方法调用（与
 * AuthSchemeOutboundTest 同口径）：只有这样才能证明身份头真的随请求发出、且未声明 identity
 * 的契约一个身份头都不发（向后兼容）。
 */
class IdentityOutboundTest {
    private static final String TENANT = "tenant-identity";

    private final List<Map<String, String>> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Path secretDir;
    private String previousDir;
    private String previousKey;

    @BeforeEach
    void setUp() throws Exception {
        received.clear();
        secretDir = Files.createTempDirectory("plat12-vault");
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "plat12-test-master-key-0123456789abcdef");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/onto/query",
                exchange -> {
                    var headers = new LinkedHashMap<String, String>();
                    for (String name :
                            List.of(
                                    "Authorization",
                                    "X-API-Key",
                                    "X-User-Id",
                                    "X-User-Tenant",
                                    "X-User-Roles",
                                    "X-User-Attributes"))
                        headers.put(name, exchange.getRequestHeaders().getFirst(name));
                    received.add(headers);
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
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

    private static void signInAs(String principal, List<String> roles, Map<String, List<String>> attributes) {
        var builder =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", TENANT)
                        .claim("roles", roles);
        if (attributes != null) builder.claim("attributes", attributes);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    private static Map<String, Object> contract(Map<String, String> identityHeaders) {
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
        if (identityHeaders != null)
            contract.put(
                    "identity",
                    Map.of("mode", "PLATFORM_SUBJECT", "headers", identityHeaders));
        return contract;
    }

    private static final Map<String, String> STANDARD_HEADERS =
            Map.of(
                    "X-User-Id", "principalId",
                    "X-User-Tenant", "tenantId",
                    "X-User-Roles", "roles",
                    "X-User-Attributes", "attributes");

    /** 同步适配器的真实出站路径；endpoint 用当前桩端口重写，其余契约字段与归一化结果一致。 */
    private void invoke(Map<String, Object> contract) {
        contract.put("endpoint", url());
        new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class))
                .invoke(contract, Map.of(), "key-identity", 1, false, null, 1);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/onto/query";
    }

    @Test
    void declaredIdentityInjectsUserHeadersFromTheVerifiedSnapshot() {
        signInAs(
                "operator-a",
                List.of("USER_ADMIN", "EDITOR"),
                Map.of("dept", List.of("103"), "region", List.of("LOCAL")));
        var contract = contract(STANDARD_HEADERS);
        invoke(contract);
        assertEquals(1, received.size());
        var headers = received.getFirst();
        assertEquals("operator-a", headers.get("X-User-Id"));
        assertEquals(TENANT, headers.get("X-User-Tenant"));
        assertEquals("EDITOR,USER_ADMIN", headers.get("X-User-Roles"), "roles按字典序逗号连接（契约§13）");
        assertEquals(
                "{\"dept\":[\"103\"],\"region\":[\"LOCAL\"]}",
                headers.get("X-User-Attributes"),
                "attributes为键排序的JSON串，值为字符串数组（契约§13）");
        assertNull(headers.get("Authorization"), "未声明authRef的契约不因身份注入多出鉴权头");
    }

    @Test
    void undeclaredIdentitySendsNoUserHeadersAtAll() {
        signInAs("operator-a", List.of("EDITOR"), Map.of("dept", List.of("103")));
        invoke(contract(null));
        assertEquals(1, received.size());
        var headers = received.getFirst();
        assertNull(headers.get("X-User-Id"), "未声明identity的技能出站行为零变化");
        assertNull(headers.get("X-User-Roles"));
        assertNull(headers.get("X-User-Attributes"));
        assertNull(headers.get("X-User-Tenant"));
    }

    @Test
    void asyncAdapterInjectsTheSameIdentityHeaders() {
        signInAs("operator-async", List.of("EDITOR"), Map.of());
        var adapter = new HttpAsyncAdapter(null);
        adapter
                .request(contract(Map.of("X-User-Id", "principalId")), url(), "POST", null, "k")
                .retrieve()
                .toBodilessEntity();
        assertEquals(1, received.size());
        assertEquals("operator-async", received.getFirst().get("X-User-Id"));
        assertNull(received.getFirst().get("X-User-Roles"), "未声明的快照字段不得自行补发");
    }

    @Test
    void identityHeadersAreAdditiveToApiKeyAuthentication() {
        signInAs("operator-key", List.of("EDITOR"), Map.of());
        var contract = contract(Map.of("X-User-Id", "principalId", "X-User-Roles", "roles"));
        contract.put(AuthScheme.FIELD, "API_KEY");
        contract.put("authRef", cn.agentplatform.common.SecretVault.put("ontopa_shared_secret", new Actor(TENANT, "admin", Set.of("ADMIN"), Map.of())));
        invoke(contract);
        assertEquals(1, received.size());
        var headers = received.getFirst();
        assertEquals("ontopa_shared_secret", headers.get("X-API-Key"), "鉴权头照发");
        assertEquals("operator-key", headers.get("X-User-Id"), "身份头叠加，互不替换");
        assertEquals("EDITOR", headers.get("X-User-Roles"));
        assertNull(headers.get("Authorization"));
    }

    @Test
    void systemCallWithoutActorSkipsInjectionInsteadOfFabricating() {
        SecurityContextHolder.clearContext();
        invoke(contract(STANDARD_HEADERS));
        assertEquals(1, received.size(), "无主体只是跳过注入，调用本身照常发出");
        var headers = received.getFirst();
        assertNull(headers.get("X-User-Id"), "不伪造principalId");
        assertNull(headers.get("X-User-Roles"));
        assertNull(headers.get("X-User-Attributes"));

        // 运行详情可见：trace 用与出站同一裁决规则记录跳过结论，不落任何值。
        var trace = SkillRules.identityTrace(contract(STANDARD_HEADERS), null);
        assertEquals(Boolean.FALSE, trace.get("injected"));
        assertEquals("NO_ACTOR_SUBJECT", trace.get("skipReason"));
    }

    @Test
    void machineWorkloadTicketIsNotProjectedIntoUserHeaders() {
        var jwt =
                Jwt.withTokenValue("workload-ticket")
                        .header("alg", "HS256")
                        .subject("svc-skill")
                        .claim("tenant_id", TENANT)
                        .claim("roles", List.of("SERVICE"))
                        .claim("authority_mode", "workload")
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        invoke(contract(STANDARD_HEADERS));
        assertEquals(1, received.size());
        assertNull(received.getFirst().get("X-User-Id"), "机器能力不能映射为用户身份，更不能作为出站用户头");
    }

    @Test
    void emptyRolesAndAttributesAreNotSentAsFabricatedValues() {
        signInAs("operator-bare", List.of(), Map.of());
        invoke(contract(STANDARD_HEADERS));
        assertEquals(1, received.size());
        var headers = received.getFirst();
        assertEquals("operator-bare", headers.get("X-User-Id"));
        assertNull(headers.get("X-User-Roles"), "空集合不发送空串头（对端split会得到幽灵角色）");
        assertNull(headers.get("X-User-Attributes"));
        var trace = SkillRules.identityTrace(contract(STANDARD_HEADERS), Actor.current().subject());
        assertEquals(Boolean.TRUE, trace.get("injected"), "至少发出了身份头即视为已注入");
    }

    @Test
    void traceListsDeclaredHeadersAndProvenanceForRunDetails() {
        signInAs("operator-c", List.of("EDITOR"), Map.of("dept", List.of("103")));
        var subject = Actor.current().subject();
        var trace = SkillRules.identityTrace(contract(STANDARD_HEADERS), subject);
        assertEquals("PLATFORM_SUBJECT", trace.get("mode"));
        assertEquals(Boolean.TRUE, trace.get("injected"));
        assertNull(trace.get("skipReason"));
        var declared = (List<Map<String, Object>>) trace.get("headers");
        assertEquals(4, declared.size());
        // trace 只带头名/来源字段/是否发送，不带值：身份明细不向运行详情扩散。
        for (var row : declared) {
            assertTrue(row.containsKey("name"));
            assertTrue(row.containsKey("from"));
            assertEquals(Boolean.TRUE, row.get("sent"));
            assertFalse(((String) row.get("name")).isBlank());
        }
    }
}
