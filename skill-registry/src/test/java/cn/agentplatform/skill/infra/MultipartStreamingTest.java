package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T09：multipart 文件部分必须“构建期不读、写出期一次流式消费”，字节上限在写出层计数，
 * 正常写完 / 超限中止 / 写出抛异常三条路径都要关闭 {@link ArtifactStore.Download} 的源流。
 *
 * <p>测试以合成长度的假流替代 MinIO 流：不分配真实字节即可验证 64MB 上限与
 * “声明 64MB 实际 100MB”场景，且能精确统计读次数、关闭次数与失败注入点。</p>
 */
class MultipartStreamingTest {
    private static final String ARTIFACT = "art_" + "e".repeat(32);
    private static final long MIB = 1024L * 1024;

    /** 合成流：按需产出 total 字节；读到 failAfter 字节后抛 IOException；记录读/关闭次数。 */
    private static final class SyntheticStream extends InputStream {
        private final long total;
        private final long failAfter;
        private long position;
        final AtomicInteger readCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();

        SyntheticStream(long total, long failAfter) {
            this.total = total;
            this.failAfter = failAfter;
        }

        SyntheticStream(long total) {
            this(total, Long.MAX_VALUE);
        }

        @Override
        public int read() throws IOException {
            readCalls.incrementAndGet();
            if (position >= failAfter) throw new IOException("simulated storage read failure");
            if (position >= total) return -1;
            position++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            readCalls.incrementAndGet();
            if (position >= failAfter) throw new IOException("simulated storage read failure");
            if (position >= total) return -1;
            int count = (int) Math.min(length, total - position);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            position += count;
            return count;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static Map<String, Object> contract(long maxBytes) {
        var file = fileSpec("file", "text/csv", maxBytes);
        return contractWithFiles(List.of(file));
    }

    private static Map<String, Object> contractWithFiles(List<Map<String, Object>> files) {
        var form = new LinkedHashMap<String, Object>();
        form.put("fields", List.of());
        form.put("files", files);
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("path", List.of());
        mapping.put("query", List.of());
        mapping.put("multipart", form);
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("endpoint", "http://127.0.0.1:2/api/v1/ballistic/files");
        contract.put("httpMethod", "POST");
        contract.put("httpMapping", mapping);
        return contract;
    }

    private static Map<String, Object> fileSpec(String from, String contentType, long maxBytes) {
        var file = new LinkedHashMap<String, Object>();
        file.put("name", from);
        file.put("from", from);
        file.put("filename", "trajectory.csv");
        file.put("maxBytes", maxBytes);
        file.put("allowedContentTypes", List.of(contentType));
        return file;
    }

    private static MultiValueMap<String, Object> build(
            long declaredBytes, InputStream stream, long maxBytes) {
        return MultipartFormMapping.build(
                contract(maxBytes),
                Map.of("file", ARTIFACT),
                id -> new ArtifactStore.Download(stream, declaredBytes, "text/csv", "sha-256"));
    }

    private static Resource fileBody(MultiValueMap<String, Object> parts) {
        var entity = (HttpEntity<?>) parts.getFirst("file");
        assertNotNull(entity);
        assertInstanceOf(Resource.class, entity.getBody());
        return (Resource) entity.getBody();
    }

    @Test
    void 构建期不读流且写出时单次消费并关闭源流() throws IOException {
        var stream = new SyntheticStream(6);
        var parts = build(6, stream, MIB);
        // 构建只拿到“如何打开流”，不读一个字节：大文件不会在构建期进堆。
        assertEquals(0, stream.readCalls.get(), "构建期不得读取产物流");
        assertEquals(0, stream.closeCalls.get(), "构建期不得关闭产物流");
        var body = fileBody(parts);
        assertEquals(6, body.contentLength());
        assertEquals("trajectory.csv", body.getFilename());

        try (var in = body.getInputStream();
                var sink = OutputStream.nullOutputStream()) {
            assertEquals(6, in.transferTo(sink), "写出字节数必须与声明一致");
        }
        assertTrue(stream.readCalls.get() > 0, "写出时必须真正读源流");
        assertEquals(1, stream.closeCalls.get(), "正常写完必须关闭下载流且只关一次");
    }

    @Test
    void 表单转换器端到端流式写出并关闭源流() throws IOException {
        var stream = new SyntheticStream(6);
        var message = new MockHttpOutputMessage();
        new FormHttpMessageConverter()
                .write(build(6, stream, MIB), MediaType.MULTIPART_FORM_DATA, message);

        var written = new String(message.getBodyAsBytes(), StandardCharsets.UTF_8);
        assertTrue(written.contains("filename=\"trajectory.csv\""), "文件名必须来自契约");
        assertTrue(written.contains("text/csv"), "部分Content-Type必须来自产物元数据");
        assertTrue(written.contains("x".repeat(6)), "全部字节必须进入请求体");
        assertEquals(1, stream.closeCalls.get(), "转换器写完必须关闭源流");
        assertTrue(stream.readCalls.get() > 0);
    }

    @Test
    void 声明64MB实际100MB的流在写出时400拒绝并关闭() throws IOException {
        var stream = new SyntheticStream(100 * MIB);
        // 契约上限与产物登记都是 64MB，实际流是 100MB：必须在第 64MB+1 字节处中止，不能整段读入。
        var body = fileBody(build(64 * MIB, stream, 64 * MIB));
        var ex = assertThrows(
                PlatformException.class,
                () -> {
                    try (var in = body.getInputStream();
                            var sink = OutputStream.nullOutputStream()) {
                        in.transferTo(sink);
                    }
                });
        assertEquals(400, ex.status());
        assertEquals("INVALID_HTTP_MAPPING", ex.code());
        assertTrue(ex.getMessage().contains("file"), "错误消息必须含部分名：" + ex.getMessage());
        assertTrue(
                ex.getMessage().contains(Long.toString(64 * MIB)),
                "错误消息必须含上限数字：" + ex.getMessage());
        assertEquals(1, stream.closeCalls.get(), "超限中止后必须关闭下载流");
    }

    @Test
    void 流读到一半抛IOException时400拒绝并关闭() throws IOException {
        var stream = new SyntheticStream(8192, 4096);
        var body = fileBody(build(8192, stream, MIB));
        var ex = assertThrows(
                PlatformException.class,
                () -> {
                    try (var in = body.getInputStream();
                            var sink = OutputStream.nullOutputStream()) {
                        in.transferTo(sink);
                    }
                });
        assertEquals(400, ex.status());
        assertEquals("INVALID_HTTP_MAPPING", ex.code());
        assertTrue(ex.getMessage().contains("file"));
        assertTrue(stream.readCalls.get() > 0);
        assertEquals(1, stream.closeCalls.get(), "写出抛异常后必须关闭下载流");
    }

    @Test
    void 实际字节少于声明时写出期按RESULT_UNKNOWN失败并关闭() throws IOException {
        var stream = new SyntheticStream(3);
        var body = fileBody(build(8, stream, MIB));
        var failure = assertThrows(
                SkillAdapters.Failure.class,
                () -> {
                    try (var in = body.getInputStream();
                            var sink = OutputStream.nullOutputStream()) {
                        in.transferTo(sink);
                    }
                });
        // 与改造前同一语义：字节数与登记不一致属可重试的未知结果，不得静默截断。
        assertEquals("RESULT_UNKNOWN", failure.semantic);
        assertTrue(failure.unknown);
        assertEquals(1, stream.closeCalls.get(), "短流失败后必须关闭下载流");
    }

    @Test
    void 文件流只允许消费一次() throws IOException {
        var stream = new SyntheticStream(4);
        var body = fileBody(build(4, stream, MIB));
        try (var in = body.getInputStream()) {
            assertEquals(4, in.transferTo(OutputStream.nullOutputStream()));
        }
        assertThrows(IllegalStateException.class, body::getInputStream, "重复打开会让源流被二次读取");
        assertEquals(1, stream.closeCalls.get());
    }

    @Test
    void 构建期拒绝部分时关闭已装载与刚装载的产物流() {
        var first = new SyntheticStream(4);
        var second = new SyntheticStream(4);
        var firstId = "art_" + "e".repeat(32);
        var secondId = "art_" + "f".repeat(32);
        // 第二个部分声明的来源类型是 application/zip；下载后白名单校验失败，构建期拒绝。
        var rejected = new LinkedHashMap<>(fileSpec("file2", "text/csv", MIB));
        rejected.put("allowedContentTypes", List.of("text/csv"));
        var contract =
                contractWithFiles(
                        List.of(fileSpec("file", "text/csv", MIB), rejected));
        var streams = new LinkedHashMap<String, SyntheticStream>();
        streams.put(firstId, first);
        streams.put(secondId, second);

        var ex = assertThrows(
                SkillAdapters.Failure.class,
                () -> MultipartFormMapping.build(
                        contract,
                        Map.of("file", firstId, "file2", secondId),
                        id -> new ArtifactStore.Download(
                                streams.get(id),
                                4,
                                id.equals(firstId) ? "text/csv" : "application/zip",
                                "sha-256")));

        assertEquals("PARAM_ERROR", ex.semantic);
        assertEquals(0, first.readCalls.get(), "拒绝发生在装载期，一个字节都不该读");
        assertEquals(0, second.readCalls.get());
        assertEquals(1, first.closeCalls.get(), "已装载的前序部分必须在构建失败时关闭");
        assertEquals(1, second.closeCalls.get(), "被拒绝的部分必须就地关闭");
    }

    @Test
    void 真实产物引用生成器的门户下载路径可直接用于文件参数() throws Exception {
        consumeGeneratedReference("downloadUrl");
    }

    @Test
    void 真实产物引用生成器的运行下载路径可直接用于文件参数() throws Exception {
        consumeGeneratedReference("externalDownloadUrl");
    }

    private static void consumeGeneratedReference(String field) throws Exception {
        var stream = new SyntheticStream(4);
        var loads = new AtomicInteger();
        var reference = generatedReference();
        var parts = MultipartFormMapping.build(
                contract(MIB),
                Map.of("file", reference.get(field)),
                id -> {
                    assertEquals(ARTIFACT, id, "下载路径必须解析回同一 opaque artifactId");
                    loads.incrementAndGet();
                    return new ArtifactStore.Download(stream, 4, "text/csv", "e".repeat(64));
                });
        assertEquals(1, loads.get(), "必须经过受控产物解析，而非将 URL 当作远程下载地址");
        assertEquals(0, stream.readCalls.get(), "构建期仍不得读取文件内容");
        try (var input = fileBody(parts).getInputStream()) {
            assertEquals(4, input.transferTo(OutputStream.nullOutputStream()));
        }
        assertEquals(1, stream.closeCalls.get());
    }

    /** 仅替代持久查询；URL 必须来自生产 ArtifactStore，不能在测试里复制其拼接规则。 */
    private static Map<String, Object> generatedReference() {
        String tenant = "multipart-reference-test", principal = "owner", run = "r_multipart_reference";
        var row = Map.<String, Object>of(
                "artifact_id", ARTIFACT,
                "run_id", run,
                "sha256", "e".repeat(64),
                "size_bytes", 4L,
                "content_type", "text/csv");
        var db = new JdbcTemplate() {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                assertArrayEquals(new Object[] {ARTIFACT, tenant, principal}, args);
                assertTrue(sql.contains("state='READY' and expires_at>now()"));
                return List.of(row);
            }
        };
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("verified-fixture")
                .header("alg", "HS256")
                .subject(principal)
                .claim("tenant_id", tenant)
                .claim("roles", List.of("SERVICE"))
                .build();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        try (var store = new ArtifactStore(db, Map.of())) {
            return store.controlledReference(Map.of("artifactId", ARTIFACT), run);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Test
    void 真实出站第一文件断流后关闭尚未消费的第二文件() throws Exception {
        var first = new SyntheticStream(16 * 1024, 4096);
        var second = new SyntheticStream(4);
        var received = new AtomicBoolean();
        var failure = failOutbound(first, 16 * 1024, second, false, received);
        assertEquals("RETRYABLE_FAILURE", failure.semantic);
        assertFalse(failure.unknown, "只读调用失败不能伪造已成功的远端结果");
        assertTrue(first.readCalls.get() > 0, "必须进入真实 HTTP 写出层后触发断流");
        assertAll(
                () -> assertEquals(1, first.closeCalls.get(), "断流的当前部分必须只关闭一次"),
                () -> assertEquals(0, second.readCalls.get(), "后续部分不得在失败后继续发送"),
                () -> assertEquals(1, second.closeCalls.get(), "已装载但未消费的后续部分也必须关闭"));
    }

    @Test
    void 真实出站对端断连后关闭全部已装载文件() throws Exception {
        var first = new SyntheticStream(64 * MIB);
        var second = new SyntheticStream(4);
        var received = new AtomicBoolean();
        var failure = failOutbound(first, 64 * MIB, second, true, received);
        assertEquals("RETRYABLE_FAILURE", failure.semantic);
        assertFalse(failure.unknown);
        assertTrue(received.get(), "对端必须实际收到请求后关闭，不能把配置失败当作发送失败");
        assertAll(
                () -> assertEquals(1, first.closeCalls.get(), "发送中止必须关闭当前部分"),
                () -> assertEquals(0, second.readCalls.get(), "对端中止后不得继续读取第二部分"),
                () -> assertEquals(1, second.closeCalls.get(), "发送中止必须关闭未消费的已装载部分"));
    }

    private static SkillAdapters.Failure failOutbound(
            SyntheticStream first,
            long firstBytes,
            SyntheticStream second,
            boolean disconnect,
            AtomicBoolean received) throws Exception {
        String secondId = "art_" + "f".repeat(32);
        var loaded = new LinkedHashSet<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            received.set(true);
            try (exchange) {
                if (disconnect) return;
                exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
                exchange.sendResponseHeaders(204, -1);
            } catch (IOException expectedDisconnect) {
                // 断流用例故意中止 chunked 请求；服务端不得为等完整请求而遗留连接。
            }
        });
        server.start();
        try (var store = new ArtifactStore(Map.of()) {
            @Override
            public Download download(String id, String runId) {
                assertNull(runId, "同步文件输入沿用已验主体的受控下载语义");
                assertTrue(loaded.add(id), "每部分必须只装载一次");
                if (ARTIFACT.equals(id))
                    return new Download(first, firstBytes, "text/csv", "e".repeat(64));
                assertEquals(secondId, id);
                return new Download(second, 4, "text/csv", "f".repeat(64));
            }
        }) {
            var contract = contractWithFiles(List.of(
                    fileSpec("file", "text/csv", firstBytes),
                    fileSpec("file2", "text/csv", MIB)));
            contract.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/upload");
            contract.put("timeoutMs", 2000);
            contract.put("execution", Map.of("readonly", true, "sideEffect", false));
            contract.put("outputSchema", Map.of("type", "object"));
            contract.put("outputMaxBytes", 1024);
            var adapters = new SkillAdapters(store, null);
            var failure = assertThrows(
                    SkillAdapters.Failure.class,
                    () -> adapters.http(contract, Map.of("file", ARTIFACT, "file2", secondId),
                            "multipart-close-test", 1, "r_multipart_close", 1));
            assertEquals(Set.of(ARTIFACT, secondId), loaded,
                    "断流前必须已装载两个部分，不能用未创建资源来满足关闭断言");
            return failure;
        } finally {
            server.stop(0);
        }
    }
}
