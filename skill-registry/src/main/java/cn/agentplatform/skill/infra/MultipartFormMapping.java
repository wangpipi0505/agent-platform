package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * multipart表单映射是声明式数据，与JSON体互斥（默认拒绝）：契约未声明multipart时，
 * 适配器永远不发multipart请求。文件部分只绑定产物引用——参数值必须是
 * art_…产物ID或受控下载路径（/admin/artifacts/{id}/download、/v1/runs/{run}/artifacts/{id}/download），
 * 字节一律经ArtifactStore按租户/主体/READY/过期解析，URL本身不是授权凭据。
 *
 * <p>设计不变量：</p>
 * <ul>
 *   <li>所有部分在调用时全部必填（缺失即PARAM_ERROR），不做可选部分——契约即完整请求形态；</li>
 *   <li>文件字节数上限、Content-Type白名单在契约中显式声明，白名单禁止通配；</li>
 *   <li>filename由契约固定，禁止从参数或产物元数据注入，杜绝路径注入与不可预测表单形态；</li>
 *   <li>multipart仅支持同步HTTP调用：异步（SUBMIT_QUERY/CALLBACK）的恢复与幂等语义
 *       以请求体可重放为前提，文件流不可重放。</li>
 * </ul>
 */
public final class MultipartFormMapping {
    private MultipartFormMapping() {}

    /** 单文件部分字节上限，与产物存储上限一致。 */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    /** 文件部分字节来源；测试以假实现替换，避免单测依赖PG/MinIO。返回类型复用ArtifactStore.Download。 */
    public interface FileSource {
        ArtifactStore.Download load(String artifactId) throws Exception;
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_HTTP_MAPPING", message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("multipart映射必须为对象");
        return (Map<String, Object>) value;
    }

    /** 契约是否声明了multipart表单。 */
    public static boolean declared(Map<String, Object> contract) {
        if (!(contract.get("httpMapping") instanceof Map<?, ?> mapping)) return false;
        return mapping.containsKey("multipart");
    }

    /** multipart声明的全部输入参数名（fields与files的from），供输入Schema一致性校验。 */
    public static Set<String> inputNames(Map<String, Object> contract) {
        var names = new LinkedHashSet<String>();
        var mapping = object(contract.get("httpMapping"));
        var form = object(mapping.get("multipart"));
        for (String kind : List.of("fields", "files")) {
            for (Object spec : specs(form, kind)) {
                names.add(object(spec).get("from").toString());
            }
        }
        return names;
    }

    /**
     * 结构校验。仅允许multipart与可选的path/query组合；与body互斥。
     * 该方法在四道门（SkillCatalog）与OpenAPI导入（OpenApiImports）两处收口调用。
     */
    public static void validate(Map<String, Object> contract, Map<String, Object> mapping) {
        var form = object(mapping.get("multipart"));
        if (!form.keySet().equals(Set.of("fields", "files"))
                && !form.keySet().equals(Set.of("fields"))
                && !form.keySet().equals(Set.of("files")))
            throw invalid("multipart只能包含fields/files");
        var fields = specs(form, "fields");
        var files = specs(form, "files");
        if (fields.isEmpty() && files.isEmpty()) throw invalid("multipart至少要有一个部分");
        if (fields.size() > 16 || files.size() > 4) throw invalid("multipart部分数量超限");
        if (!"SYNC".equals(Objects.toString(contract.get("invocationKind"), "SYNC")))
            throw invalid("multipart仅支持同步HTTP调用");
        if ("GET".equals(contract.get("httpMethod"))) throw invalid("GET不能携带multipart表单");
        var partNames = new HashSet<String>();
        var inputNames = new HashSet<String>();
        for (Object spec : fields) {
            var field = object(spec);
            if (!field.keySet().equals(Set.of("name", "from")))
                throw invalid("fields只能包含name/from");
            partName(field, partNames, inputNames);
        }
        for (Object spec : files) {
            var file = object(spec);
            if (!file.keySet().equals(Set.of("name", "from", "filename", "maxBytes", "allowedContentTypes")))
                throw invalid("files只能包含name/from/filename/maxBytes/allowedContentTypes");
            partName(file, partNames, inputNames);
            String filename = file.get("filename").toString();
            if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || filename.contains(".."))
                throw invalid("filename必须为安全文件名");
            if (!(file.get("maxBytes") instanceof Number limit)
                    || limit.longValue() < 1
                    || limit.longValue() > MAX_FILE_BYTES)
                throw invalid("maxBytes必须为1至" + MAX_FILE_BYTES);
            if (!(file.get("allowedContentTypes") instanceof List<?> types)
                    || types.isEmpty()
                    || types.size() > 16)
                throw invalid("allowedContentTypes必须为1至16项");
            for (Object type : types)
                if (!(type instanceof String media)
                        || !media.matches("[A-Za-z0-9][A-Za-z0-9!#$&^_.-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.-]{0,126}"))
                    throw invalid("allowedContentTypes必须为具体type/subtype，禁止通配与参数");
        }
        if (contract.containsKey("inputSchema")) {
            var schema = object(contract.get("inputSchema"));
            var properties = object(schema.getOrDefault("properties", Map.of()));
            var expected = new HashSet<String>(pathQueryNames(mapping));
            expected.addAll(inputNames);
            if (!"object".equals(schema.get("type")) || !properties.keySet().equals(expected))
                throw invalid("输入Schema属性必须与path/query/multipart映射一致");
            var required = schema.getOrDefault("required", List.of());
            if (!(required instanceof List<?> requiredNames))
                throw invalid("required必须为数组");
            // 所有部分在构建时全部必填，required必须覆盖multipart输入与path参数。
            for (String name : inputNames)
                if (!requiredNames.contains(name))
                    throw invalid("multipart映射要求required参数");
            for (String kind : List.of("fields", "files"))
                for (Object spec : specs(form, kind)) {
                    var parameter = object(properties.get(object(spec).get("from")));
                    if (!"string".equals(parameter.get("type")))
                        throw invalid("multipart参数必须为string（产物引用）");
                }
        }
    }

    /**
     * 组装multipart表单。运行期错误统一为PARAM_ERROR类Failure，缺参数/非法引用/超限/类型不符均拒绝。
     *
     * <p>T09：文件部分不再在构建期读取字节。{@link ArtifactStore.Download#source()} 是okhttp/MinIO
     * 的一次性流，构建期读取既会把整份文件搬进堆（64MB上限×4个部分），也让后续重试无法复用流。
     * 这里只登记“如何打开流”与声明长度，真正的读取发生在HTTP写出层，由
     * {@link FilePartBody} 负责一次性消费、计数、限额与关闭。</p>
     */
    public static MultiValueMap<String, Object> build(
            Map<String, Object> contract, Map<String, Object> args, FileSource source) {
        var mapping = object(contract.get("httpMapping"));
        var form = object(mapping.get("multipart"));
        var parts = new LinkedMultiValueMap<String, Object>();
        for (Object spec : specs(form, "fields")) {
            var field = object(spec);
            String name = field.get("from").toString();
            if (!args.containsKey(name)) throw missing(name);
            parts.add(field.get("name").toString(), scalar(name, args.get(name)));
        }
        // 已装载但尚未写出的流：后续部分校验失败时必须关闭，否则这些MinIO句柄无人回收。
        var opened = new ArrayList<FilePartBody>();
        try {
            for (Object spec : specs(form, "files")) {
                var file = object(spec);
                String name = file.get("from").toString();
                if (!args.containsKey(name)) throw missing(name);
                Object value = args.get(name);
                if (!(value instanceof String reference))
                    throw new SkillAdapters.Failure("PARAM_ERROR", false, "文件参数必须为产物引用字符串");
                long maxBytes = ((Number) file.get("maxBytes")).longValue();
                var download = load(source, reference);
                FilePartBody body;
                try {
                    if (download.bytes() > maxBytes)
                        throw new SkillAdapters.Failure(
                                "PARAM_ERROR", false, "文件超过契约上限" + maxBytes + "字节");
                    var allowed = ((List<?>) file.get("allowedContentTypes"))
                            .stream().map(Object::toString).toList();
                    if (!allowed.contains(baseType(download.contentType())))
                        throw new SkillAdapters.Failure(
                                "PARAM_ERROR",
                                false,
                                "文件类型" + download.contentType() + "不在契约白名单内");
                    body =
                            new FilePartBody(
                                    download,
                                    file.get("name").toString(),
                                    file.get("filename").toString());
                } catch (RuntimeException rejected) {
                    // 被拒绝的部分不会进入表单，写出层永远看不到它，流只能在这里关闭。
                    closeQuietly(download);
                    throw rejected;
                }
                var headers = new HttpHeaders();
                headers.setContentType(MediaType.parseMediaType(download.contentType()));
                opened.add(body);
                parts.add(
                        file.get("name").toString(), new HttpEntity<>(body, headers));
            }
        } catch (RuntimeException failure) {
            opened.forEach(FilePartBody::close);
            throw failure;
        }
        return parts;
    }

    /**
     * 文件部分载体：保存产物流的“打开方式”，首次（也唯一一次）{@link #getInputStream()} 时才把
     * okhttp/MinIO 流交给HTTP写出器，边写边计数——堆占用与文件大小无关，也不做base64/byte[]中转。
     *
     * <p>继承 {@link ByteArrayResource} 只为沿用“部分体是 ByteArrayResource”的既有类型契约
     * （{@code MultipartFormMappingTest} 直接强转）；字节永不加载，超类内容恒为空数组。</p>
     *
     * <p>关闭不变量：正常写完（写出器 finally 关闭流）、超限中止、读取/写出抛异常、以及构建中途
     * 失败（上面 {@code opened.forEach}）四条路径都汇聚到 {@link #close()}，由 CAS 保证源流只关一次。</p>
     */
    static final class FilePartBody extends ByteArrayResource {
        private final ArtifactStore.Download download;
        private final String partName;
        private final String filename;
        private final long limit;
        private final AtomicBoolean opened = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        FilePartBody(ArtifactStore.Download download, String partName, String filename) {
            super(new byte[0]);
            this.download = download;
            this.partName = partName;
            this.filename = filename;
            // 双重上限：契约声明(≤64MB)与全局硬上限取小者，二者任一被绕过都不会超发。
            this.limit = Math.min(download.bytes(), MAX_FILE_BYTES);
        }

        @Override
        public InputStream getInputStream() {
            if (!opened.compareAndSet(false, true))
                throw new IllegalStateException("multipart文件流只能消费一次：" + partName);
            return new BoundedStream(download.source(), partName, download.bytes(), limit, this::close);
        }

        @Override
        public long contentLength() {
            // 声明长度作为部分的Content-Length；实际字节数在写出时再次核对，短流不会静默截断。
            return download.bytes();
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public String getDescription() {
            return "流式产物部分[" + partName + "]，声明" + download.bytes() + "字节";
        }

        /** 幂等关闭。关闭失败不覆盖已经确定的写出结果；句柄最终由连接池回收。 */
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeQuietly(download);
        }
    }

    private static void closeQuietly(ArtifactStore.Download download) {
        try {
            download.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 一次性计数流：字节上限取 {@code min(登记字节数, MAX_FILE_BYTES)}，多读的第1字节即中止
     * （400 INVALID_HTTP_MAPPING，消息含部分名与上限数字）；流自身IOException同样映射为400
     * （该部分不可用，重发同一产物也不会不同）；读到EOF时核对声明字节数，短流按RESULT_UNKNOWN
     * 可重试失败，保持T09之前的防静默截断语义。关闭责任在调用方的try-with-resources，
     * 本类只做计数与语义映射。
     */
    private static final class BoundedStream extends FilterInputStream {
        private final String partName;
        private final long declared;
        private final long limit;
        private final Runnable onClose;
        private long count;

        BoundedStream(InputStream source, String partName, long declared, long limit, Runnable onClose) {
            super(source);
            this.partName = partName;
            this.declared = declared;
            this.limit = limit;
            this.onClose = onClose;
        }

        @Override
        public int read() throws IOException {
            int value;
            try {
                value = super.read();
            } catch (IOException ex) {
                throw readFailure(ex);
            }
            if (value < 0) {
                ensureComplete();
            } else {
                count++;
                ensureLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read;
            try {
                read = super.read(buffer, offset, length);
            } catch (IOException ex) {
                throw readFailure(ex);
            }
            if (read < 0) {
                ensureComplete();
            } else {
                count += read;
                ensureLimit();
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            // 关闭下载流（幂等）；不调用super.close()，避免同一源流被FilterInputStream与本类各关一次。
            onClose.run();
        }

        private void ensureLimit() {
            if (count > limit)
                throw invalid("文件部分" + partName + "超过字节上限" + limit + "字节");
        }

        private void ensureComplete() {
            if (count != declared)
                throw new SkillAdapters.Failure("RESULT_UNKNOWN", true, "产物字节数与登记不一致");
        }

        private PlatformException readFailure(IOException cause) {
            var failure = invalid("文件部分" + partName + "读取失败");
            failure.initCause(cause);
            return failure;
        }
    }


    private static ArtifactStore.Download load(FileSource source, String reference) {
        String id;
        if (reference.matches("art_[a-f0-9]{32}")) id = reference;
        else {
            var matcher = java.util.regex.Pattern
                    .compile("/(?:admin/artifacts|v1/runs/[A-Za-z0-9_-]+/artifacts)/([a-f0-9]{32})/download")
                    .matcher(reference);
            if (!matcher.matches()) id = null;
            else id = "art_" + matcher.group(1);
        }
        if (id == null)
            throw new SkillAdapters.Failure(
                    "PARAM_ERROR", false, "文件参数必须为产物ID或受控下载路径");
        try {
            var download = source.load(id);
            if (download == null || download.source() == null)
                throw new SkillAdapters.Failure("RESULT_UNKNOWN", true, "产物字节不可用");
            return download;
        } catch (SkillAdapters.Failure ex) {
            throw ex;
        } catch (Exception ex) {
            // 产物解析失败语义等同PARAM_ERROR：重发同样的坏引用不会有不同结果。
            throw new SkillAdapters.Failure("PARAM_ERROR", false, "产物不存在、已过期或无权访问");
        }
    }

    private static Object scalar(String name, Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
            throw new SkillAdapters.Failure("PARAM_ERROR", false, "multipart文本部分必须为标量");
        return value instanceof String s ? s : value.toString();
    }

    private static SkillAdapters.Failure missing(String name) {
        return new SkillAdapters.Failure("PARAM_ERROR", false, "缺少multipart参数" + name);
    }

    private static List<?> specs(Map<String, Object> form, String kind) {
        var value = form.get(kind);
        return value instanceof List<?> list ? list : List.of();
    }

    /** name是表单部分名（跨部分唯一），from是输入参数名（跨部分唯一）；二者允许相同。 */
    private static void partName(Map<String, Object> spec, Set<String> partNames, Set<String> inputNames) {
        String name = spec.get("name").toString();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") || !partNames.add(name))
            throw invalid("multipart部分名无效或重复");
        String from = spec.get("from").toString();
        if (!from.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") || !inputNames.add(from))
            throw invalid("multipart参数名无效或重复");
    }

    private static Set<String> pathQueryNames(Map<String, Object> mapping) {
        var names = new HashSet<String>();
        for (String kind : List.of("path", "query")) {
            var value = mapping.get(kind);
            if (!(value instanceof List<?> list)) continue;
            for (Object name : list) names.add(name.toString());
        }
        return names;
    }

    private static String baseType(String contentType) {
        var parsed = MediaType.parseMediaType(contentType);
        return parsed.getType() + "/" + parsed.getSubtype();
    }
}
