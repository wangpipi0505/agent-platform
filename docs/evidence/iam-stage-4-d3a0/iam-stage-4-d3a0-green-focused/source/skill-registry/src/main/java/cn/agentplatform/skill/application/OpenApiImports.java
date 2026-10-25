package cn.agentplatform.skill.application;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.OpenApiHttpMapping;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 导入只生成草稿。解析不取远程文件，执行配置来自明确人工输入，发布仍走既有双门禁。 */
@Service
public class OpenApiImports {
    private static final int MAX_BYTES = 1_048_576,
            MAX_NODES = 20_000,
            MAX_DEPTH = 32,
            MAX_OPERATIONS = 100;
    private static final Set<String> METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final Set<String> SCHEMA_KEYS =
            Set.of(
                    "type",
                    "properties",
                    "required",
                    "items",
                    "enum",
                    "minimum",
                    "maximum",
                    "minLength",
                    "maxLength",
                    "minItems",
                    "maxItems",
                    "additionalProperties",
                    "description",
                    "format",
                    "nullable");
    private static final ObjectMapper YAML = yamlMapper();
    private final SkillCatalog catalog;

    public OpenApiImports(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    private static ObjectMapper yamlMapper() {
        var limits = new LoaderOptions();
        limits.setMaxAliasesForCollections(0);
        limits.setNestingDepthLimit(MAX_DEPTH);
        limits.setCodePointLimit(MAX_BYTES);
        limits.setAllowDuplicateKeys(false);
        var factory =
                YAMLFactory.builder()
                        .loaderOptions(limits)
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(MAX_DEPTH)
                                        .maxStringLength(MAX_BYTES)
                                        .build())
                        .build();
        factory.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static PlatformException error(String code, String message) {
        return new PlatformException(400, code, message);
    }

    private static Map<String, Object> object(Object value) {
        return SkillCatalog.object(value);
    }

    private static String text(Map<String, Object> body, String name) {
        return SkillCatalog.text(body, name);
    }

    public Map<String, Object> preview(Map<String, Object> request) {
        Actor.current().requireRole("EDITOR");
        return parse(textDocument(request));
    }

    /** 整批写入与Catalog事务同属一个数据库事务，不留下只导入半批的窗口。 */
    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        Actor.current().requireRole("EDITOR");
        var preview = parse(textDocument(request));
        if (!(request.get("includeOps") instanceof List<?> selected)
                || selected.isEmpty()
                || selected.size() > MAX_OPERATIONS
                || new HashSet<>(selected).size() != selected.size())
            throw error("OPENAPI_SELECTION", "请选择不重复的operationId");
        var defaults = object(request.get("defaults"));
        String baseUrl = text(defaults, "baseUrl");
        var base = SkillRules.endpoint(baseUrl);
        if (base.getRawQuery() != null || baseUrl.contains("{") || baseUrl.contains("}"))
            throw error("OPENAPI_BASE_URL", "服务地址不能包含查询或模板");
        var overrides = object(request.get("overrides"));
        var operations = (List<Map<String, Object>>) preview.get("operations");
        var contracts = new ArrayList<Map<String, Object>>();
        for (Object selection : selected) {
            if (!(selection instanceof String id))
                throw error("OPENAPI_SELECTION", "operationId必须为字符串");
            var op =
                    operations.stream()
                            .filter(o -> id.equals(o.get("operationId")))
                            .findFirst()
                            .orElseThrow(() -> error("OPENAPI_SELECTION", "所选operation不存在"));
            if (!Boolean.TRUE.equals(op.get("supported")))
                throw error("OPENAPI_UNSUPPORTED", "所选operation不支持，请查看预览diagnostics");
            var manual = object(overrides.get(id));
            var contract = new LinkedHashMap<String, Object>();
            for (String field : List.of("code", "name", "description"))
                contract.put(field, text(manual, field));
            for (String field : List.of("execution", "guard", "returnSpec"))
                contract.put(field, object(manual.get(field)));
            var guard = object(contract.get("guard"));
            if (!guard.containsKey("roles") || !guard.containsKey("attributes"))
                throw error("OPENAPI_MANUAL_FIELDS", "必须显式填写守卫角色和属性");
            if (!(manual.get("errorSemantics") instanceof List<?>))
                throw error("OPENAPI_MANUAL_FIELDS", "必须显式填写失败语义数组");
            contract.put("errorSemantics", manual.get("errorSemantics"));
            contract.put("type", "HTTP");
            contract.put("endpoint", baseUrl.replaceAll("/+$", "") + op.get("path"));
            contract.put("httpMethod", op.get("method"));
            contract.put("inputSchema", op.get("inputSchema"));
            contract.put("outputSchema", op.get("outputSchema"));
            contract.put("httpMapping", op.get("httpMapping"));
            String auth = Objects.toString(manual.getOrDefault("authRef", ""), "");
            if (!auth.isBlank()) {
                Actor.current().requireRole("ADMIN");
                try {
                    SecretVault.resolve(auth);
                } catch (PlatformException e) {
                    throw e;
                } catch (Exception e) {
                    throw error("OPENAPI_AUTH_REF", "认证引用无效或尚未配置，请选择当前租户已登记的secret_ref");
                }
            }
            if (Boolean.TRUE.equals(op.get("requiresAuth")) && auth.isBlank())
                throw error("OPENAPI_AUTH_REQUIRED", "operation要求Bearer认证，请管理员显式选择认证引用");
            contract.put("authRef", auth);
            contract.put("timeoutMs", manual.getOrDefault("timeoutMs", 30_000));
            if (manual.containsKey("constraints"))
                contract.put("constraints", object(manual.get("constraints")));
            contract.put(
                    "importSource",
                    Map.of(
                            "kind",
                            "OPENAPI",
                            "operationId",
                            id,
                            "method",
                            op.get("method"),
                            "path",
                            op.get("path"),
                            "documentSha256",
                            preview.get("documentSha256")));
            // 先验证整批再写入；不能借额外字段覆盖确定转换得到的URL、Schema或来源。
            OpenApiHttpMapping.validate(contract);
            contracts.add(contract);
        }
        var drafts = new ArrayList<Map<String, Object>>();
        for (var contract : contracts) drafts.add(catalog.create(contract));
        return Map.of("drafts", drafts, "warnings", List.of("导入仅生成可编辑DRAFT；真实连接测试与模型选型验收通过后才能发布"));
    }

    private static String textDocument(Map<String, Object> request) {
        if (!(request.get("document") instanceof String doc)
                || doc.isBlank()
                || doc.length() > MAX_BYTES
                || doc.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw error("OPENAPI_SIZE", "请上传不超过1MiB的OpenAPI JSON/YAML文本");
        return doc;
    }

    private static Map<String, Object> parse(String doc) {
        JsonNode root;
        try {
            // Jackson的树读取不展开YAML alias；仍显式拒绝，以免后续解析器版本改变其含义。
            try (var tokens = (YAMLParser) YAML.getFactory().createParser(doc)) {
                int count = 0;
                while (tokens.nextToken() != null) {
                    if (++count > MAX_NODES * 2
                            || tokens.isCurrentAlias()
                            || tokens.getCurrentAnchor() != null)
                        throw error("OPENAPI_INVALID", "禁止YAML anchor/alias或过多标记");
                }
            }
            root = YAML.readTree(doc);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw error("OPENAPI_INVALID", "文档不是有效、无重复键的有界JSON/YAML");
        }
        bound(root, 0, new int[] {0});
        if (!root.isObject() || !root.path("openapi").asText().matches("3\\.0\\.[0-9]+"))
            throw error("OPENAPI_VERSION", "当前导入支持OpenAPI 3.0.x，3.1/Swagger2需先转换");
        // 即使解析库配置发生变化，也绝不让不可信$ref进入其读取入口；没有readLocation调用。
        var options = new ParseOptions();
        options.setResolve(false);
        options.setResolveFully(false);
        options.setResolveCombinators(false);
        options.setResolveRequestBody(false);
        try {
            if (new OpenAPIV3Parser().readContents(Json.write(root), null, options).getOpenAPI()
                    == null) throw error("OPENAPI_INVALID", "OpenAPI结构无效");
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw error("OPENAPI_INVALID", "OpenAPI结构无效");
        }
        var result = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        if (!root.path("paths").isObject()) throw error("OPENAPI_INVALID", "paths必须为对象");
        var paths = root.path("paths").fields();
        while (paths.hasNext()) {
            var entry = paths.next();
            String path = entry.getKey();
            var item = entry.getValue();
            for (String method : new TreeSet<>(METHODS))
                if (item.has(method)) {
                    if (result.size() >= MAX_OPERATIONS)
                        throw error("OPENAPI_COMPLEXITY", "最多允许100个operation");
                    var op = item.get(method);
                    String id = op.path("operationId").asText("");
                    if (id.isBlank()) id = method.toUpperCase(Locale.ROOT) + " " + path;
                    if (id.length() > 256 || !ids.add(id))
                        throw error("OPENAPI_OPERATION_ID", "operationId必须在文档中唯一且不超过256字符");
                    var candidate = new LinkedHashMap<String, Object>();
                    candidate.put("operationId", id);
                    candidate.put("method", method.toUpperCase(Locale.ROOT));
                    candidate.put("path", path);
                    var diagnostics = new ArrayList<String>();
                    candidate.put("diagnostics", diagnostics);
                    try {
                        convert(root, item, op, candidate);
                    } catch (PlatformException e) {
                        diagnostics.add(e.getMessage());
                    } catch (IllegalArgumentException e) {
                        diagnostics.add("Schema或参数不符合受支持的安全子集");
                    }
                    candidate.put("supported", diagnostics.isEmpty());
                    result.add(candidate);
                }
        }
        if (result.isEmpty()) throw error("OPENAPI_INVALID", "文档没有operation");
        return Map.of(
                "documentSha256",
                Digest.sha256(doc),
                "operations",
                result,
                "warnings",
                List.of("请人工填写服务地址、职责说明、执行属性、访问守卫、返回规范和失败语义；文档扩展字段不会作为授权或凭据"));
    }

    private static void bound(JsonNode node, int depth, int[] count) {
        if (node == null || depth > MAX_DEPTH || ++count[0] > MAX_NODES)
            throw error("OPENAPI_COMPLEXITY", "文档层级或节点数超过上限");
        if (node.isObject() && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (!ref.startsWith("#/components/") || ref.length() > 1024)
                throw error("OPENAPI_EXTERNAL_REF", "仅支持文档内components引用；禁止远程、文件与相对路径$ref");
        }
        if (node.isContainerNode()) node.forEach(child -> bound(child, depth + 1, count));
    }

    private static JsonNode ref(JsonNode root, JsonNode node, Set<String> stack) {
        if (node.has("$ref")) {
            String key = node.path("$ref").asText();
            if (stack.size() >= 16 || !stack.add(key))
                throw error("OPENAPI_SCHEMA", "不支持循环Schema或超过16层的引用");
            var target = root.at(key.substring(1));
            if (target.isMissingNode()) throw error("OPENAPI_SCHEMA", "本地引用目标不存在");
            return ref(root, target, stack);
        }
        return node;
    }

    private static void convert(
            JsonNode root, JsonNode item, JsonNode op, Map<String, Object> out) {
        String path = out.get("path").toString(), method = out.get("method").toString();
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.contains("?")
                || path.contains("#")
                || path.contains("\\")
                || path.contains("%")
                || Arrays.asList(path.split("/")).contains(".."))
            throw error("OPENAPI_PATH", "路径包含不受支持的URL或转义结构");
        if (item.has("$ref") || op.has("callbacks") || op.has("servers") || item.has("servers"))
            throw error("OPENAPI_UNSUPPORTED", "不支持path引用、回调或operation/path级服务器覆盖");
        if (Set.of("HEAD", "OPTIONS", "TRACE").contains(method))
            throw error("OPENAPI_METHOD", "当前JSON结果适配器不支持HEAD/OPTIONS/TRACE");
        out.put("requiresAuth", security(root, op));
        var properties = new LinkedHashMap<String, Object>();
        var required = new LinkedHashSet<String>();
        var pathNames = new ArrayList<String>();
        var queryNames = new ArrayList<String>();
        var parameters = new LinkedHashMap<String, JsonNode>();
        for (var owner : List.of(item, op)) {
            if (owner.has("parameters") && !owner.get("parameters").isArray())
                throw error("OPENAPI_PARAMETER", "parameters必须为数组");
            for (var raw : owner.path("parameters")) {
                var parameter = ref(root, raw, new HashSet<>());
                parameters.put(
                        parameter.path("in").asText() + ":" + parameter.path("name").asText(),
                        parameter);
            }
        }
        if (parameters.size() > 64) throw error("OPENAPI_COMPLEXITY", "单个operation最多64个参数");
        for (var parameter : parameters.values()) {
            String name = parameter.path("name").asText(), location = parameter.path("in").asText();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") || properties.containsKey(name))
                throw error("OPENAPI_PARAMETER", "参数名称必须简单且path/query之间不能重名");
            if (!Set.of("path", "query").contains(location)
                    || parameter.has("content")
                    || parameter.path("allowReserved").asBoolean(false)
                    || parameter.path("allowEmptyValue").asBoolean(false))
                throw error("OPENAPI_PARAMETER", "仅支持默认序列化的path/query标量参数");
            String style =
                    parameter.path("style").asText(location.equals("path") ? "simple" : "form");
            if (!style.equals(location.equals("path") ? "simple" : "form"))
                throw error("OPENAPI_PARAMETER", "不支持此参数序列化style");
            var schema = schema(root, parameter.path("schema"), new HashSet<>(), 0);
            if (!Set.of("string", "number", "integer", "boolean")
                    .contains(Objects.toString(schema.get("type"), "")))
                throw error("OPENAPI_PARAMETER", "path/query仅支持非空标量Schema");
            properties.put(name, schema);
            if (parameter.path("required").asBoolean(false)) required.add(name);
            if (location.equals("path")) {
                if (!parameter.path("required").asBoolean(false)
                        || !path.contains("{" + name + "}"))
                    throw error("OPENAPI_PARAMETER", "path参数必须required并对应路径模板");
                pathNames.add(name);
            } else queryNames.add(name);
        }
        String replaced = path;
        for (String name : pathNames) replaced = replaced.replace("{" + name + "}", "x");
        if (replaced.contains("{") || replaced.contains("}"))
            throw error("OPENAPI_PARAMETER", "路径模板缺少匹配参数");
        boolean hasBody = op.has("requestBody");
        if (hasBody) {
            if (method.equals("GET")) throw error("OPENAPI_BODY", "GET不支持requestBody");
            var body = ref(root, op.get("requestBody"), new HashSet<>());
            var content = body.path("content");
            if (content.size() != 1 || !content.has("application/json"))
                throw error("OPENAPI_BODY", "requestBody只支持单一application/json媒体类型");
            if (!body.path("required").asBoolean(false))
                throw error("OPENAPI_BODY", "当前只支持required JSON请求体");
            var bodySchema =
                    schema(
                            root,
                            content.path("application/json").path("schema"),
                            new HashSet<>(),
                            0);
            properties.put("body", bodySchema);
            required.add("body");
            if (pathNames.contains("body") || queryNames.contains("body"))
                throw error("OPENAPI_BODY", "参数body与JSON体保留名称冲突");
        }
        var input = new LinkedHashMap<String, Object>();
        input.put("type", "object");
        input.put("properties", properties);
        input.put("required", new ArrayList<>(required));
        input.put("additionalProperties", false);
        var successes = new ArrayList<Map<String, Object>>();
        var responses = op.path("responses").fields();
        while (responses.hasNext()) {
            var response = responses.next();
            if (!response.getKey().matches("2[0-9][0-9]")) continue;
            var value = ref(root, response.getValue(), new HashSet<>());
            var content = value.path("content");
            if (!content.has("application/json") || content.size() != 1)
                throw error("OPENAPI_RESPONSE", "成功响应必须为单一application/json，不能为空或多媒体");
            successes.add(
                    schema(
                            root,
                            content.path("application/json").path("schema"),
                            new HashSet<>(),
                            0));
        }
        if (successes.isEmpty()) throw error("OPENAPI_RESPONSE", "需要明确的2xx JSON成功响应Schema");
        if (successes.stream().map(Json::canonical).distinct().count() != 1)
            throw error("OPENAPI_RESPONSE", "多个成功响应Schema不同，需人工拆分operation");
        SkillRules.schema(input);
        SkillRules.schema(successes.getFirst());
        out.put("inputSchema", input);
        out.put("outputSchema", successes.getFirst());
        out.put(
                "httpMapping",
                Map.of("path", pathNames, "query", queryNames, "body", hasBody ? "body" : ""));
    }

    private static boolean security(JsonNode root, JsonNode op) {
        var security = op.has("security") ? op.get("security") : root.path("security");
        if (security.isMissingNode()) return false;
        if (!security.isArray()) throw error("OPENAPI_SECURITY", "security必须为数组");
        if (security.isEmpty()) return false;
        if (security.size() != 1 || !security.get(0).isObject() || security.get(0).size() != 1)
            throw error("OPENAPI_SECURITY", "仅支持单一HTTP Bearer认证，不支持组合或备选安全方案");
        String name = security.get(0).fieldNames().next();
        var scheme =
                ref(
                        root,
                        root.path("components").path("securitySchemes").path(name),
                        new HashSet<>());
        if (!scheme.path("type").asText().equals("http")
                || !scheme.path("scheme").asText().equalsIgnoreCase("bearer")
                || !security.get(0).path(name).isArray()
                || !security.get(0).path(name).isEmpty())
            throw error("OPENAPI_SECURITY", "适配器当前只发送HTTP Bearer认证");
        return true;
    }

    private static Map<String, Object> schema(
            JsonNode root, JsonNode raw, Set<String> inherited, int depth) {
        return schema(root, raw, inherited, depth, new int[] {0});
    }

    private static Map<String, Object> schema(
            JsonNode root, JsonNode raw, Set<String> inherited, int depth, int[] expanded) {
        if (depth > 16 || ++expanded[0] > 2048)
            throw error("OPENAPI_SCHEMA", "Schema展开超过16层或2048节点");
        var stack = new HashSet<>(inherited);
        var node = ref(root, raw, stack);
        if (!node.isObject()) throw error("OPENAPI_SCHEMA", "Schema必须为对象");
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!SCHEMA_KEYS.contains(name)
                    && !name.startsWith("x-")
                    && !Set.of(
                                    "example",
                                    "examples",
                                    "default",
                                    "title",
                                    "readOnly",
                                    "writeOnly",
                                    "deprecated",
                                    "xml",
                                    "externalDocs")
                            .contains(name)) throw error("OPENAPI_SCHEMA", "不支持Schema关键字: " + name);
        }
        String type = node.path("type").asText();
        if (!Set.of("object", "array", "string", "integer", "number", "boolean").contains(type))
            throw error("OPENAPI_SCHEMA", "Schema需要明确的单一type");
        if (node.path("nullable").asBoolean(false)
                || node.path("readOnly").asBoolean(false)
                || node.path("writeOnly").asBoolean(false))
            throw error("OPENAPI_SCHEMA", "不支持nullable/readOnly/writeOnly自动转换，请先拆分输入输出Schema");
        var result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        for (String key :
                List.of(
                        "required",
                        "enum",
                        "minimum",
                        "maximum",
                        "minLength",
                        "maxLength",
                        "minItems",
                        "maxItems",
                        "description"))
            if (node.has(key))
                result.put(key, Json.mapper().convertValue(node.get(key), Object.class));
        if (node.has("format")
                && Set.of("date", "date-time", "uuid", "email", "hostname", "ipv4", "ipv6", "uri")
                        .contains(node.path("format").asText()))
            result.put("format", node.path("format").asText());
        if (type.equals("object")) {
            var properties = new LinkedHashMap<String, Object>();
            if (node.has("properties") && !node.path("properties").isObject())
                throw error("OPENAPI_SCHEMA", "properties必须为对象");
            var fields = node.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (properties.size() >= 128) throw error("OPENAPI_SCHEMA", "单Schema最多128属性");
                properties.put(
                        field.getKey(), schema(root, field.getValue(), stack, depth + 1, expanded));
            }
            result.put("properties", properties);
            if (node.has("additionalProperties")) {
                var additional = node.get("additionalProperties");
                result.put(
                        "additionalProperties",
                        additional.isBoolean()
                                ? additional.asBoolean()
                                : schema(root, additional, stack, depth + 1, expanded));
            }
        }
        if (type.equals("array"))
            result.put("items", schema(root, node.path("items"), stack, depth + 1, expanded));
        return result;
    }
}
