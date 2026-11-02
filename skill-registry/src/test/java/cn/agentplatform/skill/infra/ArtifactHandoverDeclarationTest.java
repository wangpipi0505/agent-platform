package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.SkillRules;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R01附录I.9/R13：声明式外部产物受控交接——按契约登记的GET模板获取业务文件并归档为受控产物；
 * URL来自契约而非运行数据，{value}只替换业务标识，不跟随重定向，失败按OUTPUT_INVALID如实失败。
 */
class ArtifactHandoverDeclarationTest {
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final SkillAdapters adapters = new SkillAdapters(artifacts, null);
    private final CopyOnWriteArrayList<byte[]> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private String previousDir;
    private String previousKey;
    private String downloadRef;
    private java.nio.file.Path secretDir;

    @BeforeEach
    void signIn() throws Exception {
        // SecretVault经AuthScheme.headers解析vault:引用；测试注入临时目录与主密钥。
        previousDir = System.getProperty("agent.secret.dir");
        previousKey = System.getProperty("agent.master.key");
        secretDir = java.nio.file.Files.createTempDirectory("handover-vault");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "handover-test-master-key-0123456789");
        // SecretVault.put要求已验身份（vault条目绑定tenant）；先设置安全上下文。
        downloadRef = cn.agentplatform.common.SecretVault.put("biz-secret", currentActor());
        var jwt =
                Jwt.withTokenValue("t")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", "handover_test")
                        .claim("roles", List.of("EDITOR"))
                        .claim("attributes", Map.of())
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        if (previousDir != null) System.setProperty("agent.secret.dir", previousDir);
        else System.clearProperty("agent.secret.dir");
        if (previousKey != null) System.setProperty("agent.master.key", previousKey);
        else System.clearProperty("agent.master.key");
    }

    private static cn.agentplatform.common.Actor currentActor() {
        return new cn.agentplatform.common.Actor("handover_test", "editor", Set.of("EDITOR"), Map.of());
    }

    private String startPeer(String secretHeader) throws Exception { // secretHeader=真实凭据值
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/files/",
                exchange -> {
                    var path = exchange.getRequestURI().getPath();
                    if (!"GET".equals(exchange.getRequestMethod()) || !path.startsWith("/api/files/")) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    var auth = exchange.getRequestHeaders().getFirst("X-API-Key");
                    if (secretHeader != null && !secretHeader.equals(auth)) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    exchange.getRequestBody().readAllBytes();
                    received.add(("id,region" + System.lineSeparator() + "1,A").getBytes(StandardCharsets.UTF_8));
                    if (status.get() != 200) {
                        exchange.sendResponseHeaders(status.get(), -1);
                        return;
                    }
                    byte[] body = received.getLast();
                    exchange.getResponseHeaders().set("Content-Type", "text/csv");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Map<String, Object> contract(String peer, String authScheme) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("inputSchema", Map.of("type", "object"));
        c.put("outputMaxBytes", 1048576);
        c.put("outputSchema", Map.of("type", "object"));
        c.put("returnSpec", buildReturnSpec(peer, authScheme));
        return c;
    }

    private Map<String, Object> buildReturnSpec(String peer, String authScheme) {
        Map<String, Object> sources =
                Map.of(
                        "$.csvPath",
                        Map.of(
                                "retrieve",
                                Map.of(
                                        "url", peer + "/api/files/{value}",
                                        "authScheme", authScheme,
                                        "authRef", authScheme.equals("NONE") ? "" : downloadRef),
                                "contentType",
                                "text/csv"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("fileRefPaths", List.of("$.csvPath"));
        spec.put("artifactSources", sources);
        return spec;
    }

    @Test
    void 声明获取归档受控产物并返回引用() throws Exception {
        String peer = startPeer("biz-secret");
        when(artifacts.upload(eq("text/csv"), anyLong(), any()))
                .thenAnswer(inv -> Map.<String, Object>of("artifactId", "art_new", "size_bytes", inv.getArgument(1), "sha256", "c".repeat(64)));
        when(artifacts.controlledReference(any(), eq("r_handover")))
                .thenReturn(Map.of("artifactId", "art_new", "runId", "r_handover"));
        var output = Map.of("csvPath", "file-123");
        var raw =
                adapters.read(
                        new ByteArrayInputStream(Json.write(output).getBytes(StandardCharsets.UTF_8)),
                        contract(peer, "API_KEY"),
                        7,
                        "r_handover");
        assertEquals(1, raw.fileRefs().size());
        assertEquals("art_new", raw.fileRefs().getFirst().get("artifactId"));
        assertEquals(1, received.size());
        verify(artifacts).upload(eq("text/csv"), eq((long) received.getFirst().length), any());
    }

    @Test
    void 获取失败按声明语义如实失败() throws Exception {
        String peer = startPeer(null);
        status.set(500);
        var output = Map.of("csvPath", "file-123");
        var ex =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.read(
                                        new ByteArrayInputStream(Json.write(output).getBytes(StandardCharsets.UTF_8)),
                                        contract(peer, "NONE"),
                                        7,
                                        "r_handover"));
        assertEquals("OUTPUT_INVALID", ex.semantic);
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void 未声明路径的行为零变化() throws Exception {
        String peer = startPeer(null);
        when(artifacts.controlledReference(any(), eq("r_handover")))
                .thenReturn(Map.of("artifactId", "art_existing"));
        var output = Map.of("csvPath", "art_existing");
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("inputSchema", Map.of("type", "object"));
        contract.put("outputMaxBytes", 1048576);
        contract.put("outputSchema", Map.of("type", "object"));
        contract.put("returnSpec", Map.of("fileRefPaths", List.of("$.csvPath")));
        var raw =
                adapters.read(
                        new ByteArrayInputStream(Json.write(output).getBytes(StandardCharsets.UTF_8)),
                        contract,
                        7,
                        "r_handover");
        assertEquals("art_existing", raw.fileRefs().getFirst().get("artifactId"));
        verify(artifacts, never()).upload(any(), anyLong(), any());
    }

    @Test
    void 保存边界拒绝非法声明() {
        var bad = invalidSource(Map.of("url", "ftp://x/{value}"));
        assertTrue(bad.getMessage().contains("retrieve.url"));
        assertTrue(invalidSource(Map.of("url", "https://h/{v}")).getMessage().contains("retrieve.url"));
        var noRef =
                invalidSource(
                        Map.of("url", "https://h/f/{value}", "authScheme", "API_KEY", "authRef", "  "));
        assertTrue(noRef.getMessage().contains("authRef"));
        // 合法声明通过。
        SkillRules.validateReturnSpec(
                Map.of(
                        "fileRefPaths", List.of("$.csvPath"),
                        "artifactSources",
                                Map.of(
                                        "$.csvPath",
                                        Map.of(
                                                "retrieve",
                                                Map.of(
                                                        "url", "https://h/api/files/{value}",
                                                        "authScheme", "API_KEY",
                                                        "authRef", "vault:biz")))));
    }

    private PlatformException invalidSource(Object retrieve) {
        return assertThrows(
                PlatformException.class,
                () ->
                        SkillRules.validateReturnSpec(
                                Map.of(
                                        "fileRefPaths", List.of("$.csvPath"),
                                        "artifactSources", Map.of("$.csvPath", Map.of("retrieve", retrieve)))));
    }
}
