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
        authenticateAs("admin", "ADMIN");
    }

    private void authenticateAs(String subject, String... roles) {
        var jwt =
                Jwt.withTokenValue("t")
                        .header("alg", "HS256")
                        .subject(subject)
                        .claim("tenant_id", "handover_test")
                        .claim("roles", List.of(roles))
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
        when(artifacts.storeProcessOutput(
                        eq("text/csv"), anyLong(), anyString(), any(), eq("r_handover"), eq(7L), eq(1L)))
                .thenAnswer(
                        inv ->
                                Map.<String, Object>of(
                                        "artifactId",
                                        "art_new",
                                        "bytes",
                                        inv.getArgument(1),
                                        "sha256",
                                        inv.getArgument(2)));
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
        verify(artifacts)
                .storeProcessOutput(
                        eq("text/csv"),
                        eq((long) received.getFirst().length),
                        anyString(),
                        any(),
                        eq("r_handover"),
                        eq(7L),
                        eq(1L));
        verify(artifacts, never()).upload(any(), anyLong(), any());
    }

    @Test
    void 真实存储路径绑定当前run归属且跨run拒绝与失败清理生效() throws Exception {
        String peer = startPeer("biz-secret");
        try (var s3 = new InMemoryS3Peer()) {
            var db = new InMemoryArtifactJdbc();
            try (var realStore = new ArtifactStore(db, s3.configuration())) {
                var realAdapters = new SkillAdapters(realStore, null);
                var output = Map.of("csvPath", "file-123");
                var raw =
                        realAdapters.read(
                                new ByteArrayInputStream(
                                        Json.write(output).getBytes(StandardCharsets.UTF_8)),
                                contract(peer, "API_KEY"),
                                42L,
                                "r_real_run",
                                3L,
                                0L);
                assertEquals(1, raw.fileRefs().size());
                String artifactId = raw.fileRefs().getFirst().get("artifactId").toString();
                assertTrue(artifactId.startsWith("art_"));
                assertEquals(
                        "/v1/runs/r_real_run/artifacts/" + artifactId + "/download",
                        raw.fileRefs().getFirst().get("externalDownloadUrl"));
                try (var download = realStore.download(artifactId, "r_real_run")) {
                    assertArrayEquals(received.getLast(), download.source().readAllBytes());
                }
                // 跨run访问必须按404 ARTIFACT_NOT_FOUND拒绝
                var crossRun =
                        assertThrows(
                                PlatformException.class,
                                () ->
                                        realStore.controlledReference(
                                                Map.of("artifactId", artifactId), "r_other_run"));
                assertEquals(404, crossRun.status());
                assertEquals("ARTIFACT_NOT_FOUND", crossRun.code());

                // 后续步骤（如cardTemplate）失败时，已归档产物必须被补偿清理为DELETED且从对象存储移除
                Map<String, Object> badCardContract = contract(peer, "API_KEY");
                var specWithBadCard = new LinkedHashMap<>(buildReturnSpec(peer, "API_KEY"));
                specWithBadCard.put("cardTemplate", "not-a-json-container");
                badCardContract.put("returnSpec", specWithBadCard);
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                realAdapters.read(
                                        new ByteArrayInputStream(
                                                Json.write(output)
                                                        .getBytes(StandardCharsets.UTF_8)),
                                        badCardContract,
                                        99L,
                                        "r_failed_run",
                                        1L,
                                        0L));
                var compensated = db.byRun("r_failed_run");
                assertEquals(1, compensated.size());
                assertEquals("DELETED", compensated.getFirst().get("state"));
                assertTrue(s3.deletedKeys.stream().anyMatch(k -> k.contains((String) compensated.getFirst().get("artifact_id"))));
            }
        }
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
    void 保存与运行边界拒绝主机占位符与越权凭据及NONE冲突() {
        var bad = invalidSource(Map.of("url", "ftp://x/{value}"));
        assertTrue(bad.getMessage().contains("retrieve.url"));
        assertTrue(invalidSource(Map.of("url", "https://h/{v}")).getMessage().contains("retrieve.url"));
        // 主机/端口部分禁止出现{value}
        assertTrue(
                invalidSource(Map.of("url", "https://{value}/api/files"))
                        .getMessage()
                        .contains("retrieve.url"));
        assertTrue(
                invalidSource(Map.of("url", "https://sub.{value}.example.com/api/files"))
                        .getMessage()
                        .contains("retrieve.url"));
        assertTrue(
                invalidSource(Map.of("url", "https://h:{value}/api/files"))
                        .getMessage()
                        .contains("retrieve.url"));
        // 执行期同样拒绝主机占位符，不发起外部请求
        Map<String, Object> hijackedContract = new LinkedHashMap<>();
        hijackedContract.put("inputSchema", Map.of("type", "object"));
        hijackedContract.put("outputMaxBytes", 1048576);
        hijackedContract.put("outputSchema", Map.of("type", "object"));
        hijackedContract.put(
                "returnSpec",
                Map.of(
                        "fileRefPaths",
                        List.of("$.csvPath"),
                        "artifactSources",
                        Map.of(
                                "$.csvPath",
                                Map.of(
                                        "retrieve",
                                        Map.of(
                                                "url", "http://{value}/api/files/1",
                                                "authScheme", "API_KEY",
                                                "authRef", downloadRef)))));
        var execRejected =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.read(
                                        new ByteArrayInputStream(
                                                Json.write(Map.of("csvPath", "127.0.0.1"))
                                                        .getBytes(StandardCharsets.UTF_8)),
                                        hijackedContract,
                                        7,
                                        "r_handover"));
        assertEquals("OUTPUT_INVALID", execRejected.semantic);

        var noRef =
                invalidSource(
                        Map.of("url", "https://h/f/{value}", "authScheme", "API_KEY", "authRef", "  "));
        assertTrue(noRef.getMessage().contains("authRef"));
        var badRef =
                invalidSource(
                        Map.of("url", "https://h/f/{value}", "authScheme", "API_KEY", "authRef", "plain-secret"));
        assertEquals("INVALID_SECRET_REF", badRef.code());
        var conflict =
                invalidSource(
                        Map.of("url", "https://h/f/{value}", "authScheme", "NONE", "authRef", "vault:biz"));
        assertEquals("SKILL_AUTH_SCHEME_CONFLICT", conflict.code());

        // 非ADMIN（如EDITOR）配置retrieve.authRef必须按403拒绝
        authenticateAs("editor", "EDITOR");
        var forbidden =
                invalidSource(
                        Map.of("url", "https://h/api/files/{value}", "authScheme", "API_KEY", "authRef", "vault:biz"));
        assertEquals(403, forbidden.status());

        // ADMIN合法声明通过
        authenticateAs("admin", "ADMIN");
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

    @Test
    void 流式接收超64MB立即中止且继承外层deadline() throws Exception {
        var oversizeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oversizeServer.createContext(
                "/api/files/",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    // 使用chunked流式传输（不预置Content-Length），验证接收循环在64MB+1字节处立即停止
                    exchange.sendResponseHeaders(200, 0);
                    byte[] chunk = new byte[64 * 1024];
                    try (var out = exchange.getResponseBody()) {
                        for (int i = 0; i < 1030; i++) {
                            out.write(chunk);
                        }
                    } catch (java.io.IOException ignored) {
                        // 客户端在超过64MB时主动断开流
                    }
                });
        oversizeServer.start();
        try {
            String peer = "http://127.0.0.1:" + oversizeServer.getAddress().getPort();
            var ex =
                    assertThrows(
                            SkillAdapters.Failure.class,
                            () ->
                                    adapters.read(
                                            new ByteArrayInputStream(
                                                    Json.write(Map.of("csvPath", "big-file"))
                                                            .getBytes(StandardCharsets.UTF_8)),
                                            contract(peer, "NONE"),
                                            7,
                                            "r_handover"));
            assertEquals("OUTPUT_INVALID", ex.semantic);
            assertTrue(ex.getMessage().contains("超过64MB上限"));
        } finally {
            oversizeServer.stop(0);
        }

        // 外层deadline已耗尽或剩余不足时中止下载
        var slowServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowServer.createContext(
                "/api/files/",
                exchange -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    exchange.sendResponseHeaders(200, -1);
                });
        slowServer.start();
        try {
            String peer = "http://127.0.0.1:" + slowServer.getAddress().getPort();
            long shortDeadline = System.nanoTime() + java.time.Duration.ofMillis(60).toNanos();
            var timeoutEx =
                    assertThrows(
                            SkillAdapters.Failure.class,
                            () ->
                                    adapters.read(
                                            new ByteArrayInputStream(
                                                    Json.write(Map.of("csvPath", "slow-file"))
                                                            .getBytes(StandardCharsets.UTF_8)),
                                            contract(peer, "NONE"),
                                            7,
                                            "r_handover",
                                            1L,
                                            shortDeadline));
            assertEquals("RETRYABLE_FAILURE", timeoutEx.semantic);
            assertTrue(timeoutEx.getMessage().contains("超时"));
        } finally {
            slowServer.stop(0);
        }
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

    private static final class InMemoryS3Peer implements AutoCloseable {
        static final String BUCKET = "handover-test-bucket";
        final HttpServer server;
        final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> deletedKeys = new CopyOnWriteArrayList<>();

        InMemoryS3Peer() throws java.io.IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    x -> {
                        try {
                            String method = x.getRequestMethod();
                            String path = x.getRequestURI().getPath();
                            String query = Objects.toString(x.getRequestURI().getRawQuery(), "");
                            if (method.equals("GET")
                                    && path.equals("/" + BUCKET)
                                    && query.equals("location=")) {
                                byte[] xml =
                                        "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">us-east-1</LocationConstraint>"
                                                .getBytes(StandardCharsets.UTF_8);
                                x.getResponseHeaders().set("Content-Type", "application/xml");
                                x.sendResponseHeaders(200, xml.length);
                                x.getResponseBody().write(xml);
                            } else if (method.equals("GET")
                                    && path.equals("/" + BUCKET)
                                    && query.contains("uploads=")) {
                                byte[] xml =
                                        "<ListMultipartUploadsResult><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>"
                                                .getBytes(StandardCharsets.UTF_8);
                                x.getResponseHeaders().set("Content-Type", "application/xml");
                                x.sendResponseHeaders(200, xml.length);
                                x.getResponseBody().write(xml);
                            } else if (method.equals("PUT") && path.startsWith("/" + BUCKET + "/")) {
                                objects.put(path, x.getRequestBody().readAllBytes());
                                x.getResponseHeaders().set("ETag", "\"etag\"");
                                x.sendResponseHeaders(200, -1);
                            } else if (method.equals("GET") && path.startsWith("/" + BUCKET + "/")) {
                                byte[] data = objects.get(path);
                                if (data == null) {
                                    x.sendResponseHeaders(404, -1);
                                } else {
                                    x.sendResponseHeaders(200, data.length);
                                    x.getResponseBody().write(data);
                                }
                            } else if (method.equals("DELETE")
                                    && path.startsWith("/" + BUCKET + "/")) {
                                deletedKeys.add(path);
                                objects.remove(path);
                                x.sendResponseHeaders(204, -1);
                            } else {
                                x.sendResponseHeaders(400, -1);
                            }
                        } finally {
                            x.close();
                        }
                    });
            server.start();
        }

        Map<String, String> configuration() {
            return Map.of(
                    "AGENT_MINIO_ENDPOINT",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "AGENT_MINIO_ACCESS_KEY",
                    "ak",
                    "AGENT_MINIO_SECRET_KEY",
                    "sk-secret-key",
                    "AGENT_MINIO_BUCKET",
                    BUCKET);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class InMemoryArtifactJdbc extends org.springframework.jdbc.core.JdbcTemplate {
        private final Map<String, Map<String, Object>> rows =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public int update(String sql, Object... args) {
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("insert into skill.artifact")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("artifact_id", args[0]);
                row.put("tenant_id", args[1]);
                row.put("principal_id", args[2]);
                row.put("run_id", args[3]);
                row.put("call_id", args[4]);
                row.put("bucket", args[5]);
                row.put("object_key", args[6]);
                row.put("state", "UPLOADING");
                row.put("content_type", args[7]);
                rows.put(args[0].toString(), row);
                return 1;
            }
            if (normalized.contains("set state='ready'")) {
                String id = args[4].toString();
                var row = rows.get(id);
                if (row == null || !"UPLOADING".equals(row.get("state"))) return 0;
                row.put("state", "READY");
                row.put("sha256", args[0]);
                row.put("size_bytes", args[1]);
                return 1;
            }
            if (normalized.contains("set state='cleanup_pending'")) {
                for (Object arg : args) {
                    if (arg instanceof String s && s.startsWith("art_") && rows.containsKey(s)) {
                        rows.get(s).put("state", "CLEANUP_PENDING");
                        return 1;
                    }
                }
                return 0;
            }
            if (normalized.contains("set state='deleted'")) {
                for (Object arg : args) {
                    if (arg instanceof String s && s.startsWith("art_") && rows.containsKey(s)) {
                        rows.get(s).put("state", "DELETED");
                        return 1;
                    }
                }
                return 0;
            }
            return 0;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            String id = args[0].toString();
            String tenant = args[1].toString();
            String principal = args[2].toString();
            var row = rows.get(id);
            if (row == null) return List.of();
            if (!tenant.equals(row.get("tenant_id")) || !principal.equals(row.get("principal_id")))
                return List.of();
            if (sql.contains("state='READY'") && !"READY".equals(row.get("state")))
                return List.of();
            if (args.length >= 6) {
                if (!args[3].equals(row.get("run_id"))
                        || !args[4].equals(row.get("call_id"))
                        || !args[5].equals(row.get("object_key"))) return List.of();
            }
            return List.of(new LinkedHashMap<>(row));
        }

        List<Map<String, Object>> byRun(String runId) {
            return rows.values().stream()
                    .filter(r -> runId.equals(r.get("run_id")))
                    .toList();
        }
    }
}
