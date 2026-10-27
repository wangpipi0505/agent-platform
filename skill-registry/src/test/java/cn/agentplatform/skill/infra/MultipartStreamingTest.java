package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
}
