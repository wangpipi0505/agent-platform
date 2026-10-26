package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.util.LinkedMultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

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

    /** 组装multipart表单。运行期错误统一为PARAM_ERROR类Failure，缺参数/非法引用/超限/类型不符均拒绝。 */
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
        for (Object spec : specs(form, "files")) {
            var file = object(spec);
            String name = file.get("from").toString();
            if (!args.containsKey(name)) throw missing(name);
            Object value = args.get(name);
            if (!(value instanceof String reference))
                throw new SkillAdapters.Failure("PARAM_ERROR", false, "文件参数必须为产物引用字符串");
            long maxBytes = ((Number) file.get("maxBytes")).longValue();
            var download = load(source, reference);
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
            byte[] bytes = readBounded(download.source(), download.bytes());
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(download.contentType()));
            parts.add(
                    file.get("name").toString(),
                    new HttpEntity<>(named(bytes, file.get("filename").toString()), headers));
        }
        return parts;
    }

    /** 带文件名的字节资源；FormHttpMessageConverter以它生成Content-Disposition的filename。 */
    private static ByteArrayResource named(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
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

    /** 按声明字节数读取并多读1字节校验，防止元数据与实际流不一致导致静默截断。 */
    private static byte[] readBounded(InputStream source, long declared) {
        int budget = (int) Math.min(declared + 1, Integer.MAX_VALUE);
        var out = new ByteArrayOutputStream(Math.max(0, budget));
        byte[] buffer = new byte[8192];
        int total = 0;
        try (source) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                total += read;
                if (total > budget) break;
                out.write(buffer, 0, read);
            }
        } catch (Exception ex) {
            throw new SkillAdapters.Failure("RESULT_UNKNOWN", true, "产物字节读取失败");
        }
        if (total != declared)
            throw new SkillAdapters.Failure("RESULT_UNKNOWN", true, "产物字节数与登记不一致");
        return out.toByteArray();
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
