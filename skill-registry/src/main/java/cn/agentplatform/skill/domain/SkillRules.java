package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SubjectSnapshot;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.common.schema.BoundedRegexFactory;
import cn.agentplatform.common.schema.SchemaDeadline;
import cn.agentplatform.common.schema.SchemaDialect;
import cn.agentplatform.common.schema.SchemaWorkBudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;

import java.net.URI;
import java.util.*;

/** 技能边界规则：Schema使用现有networknt实现；守卫只缩小已验身份范围，绝不接受请求自报授权。 */
public final class SkillRules {
    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012,
                    builder ->
                            builder.enableSchemaCache(false)
                                    .schemaLoaders(
                                            loaders ->
                                                    loaders.add(
                                                            iri -> {
                                                                throw new IllegalArgumentException(
                                                                        "技能Schema禁止外部加载");
                                                            })));

    private SkillRules() {}

    /** 导入预检与目录保存共用的纯规则；分页只声明参数及上限，不承诺续拉或累计行数限制。 未声明的新键保持缺省，来源风险与投影冲突的拒绝不扩到旧手工技能。 */
    public static Map<String, Object> projection(Map<String, Object> body) {
        var result = new LinkedHashMap<String, Object>();
        boolean ontology =
                body.get("importSource") instanceof Map<?, ?> source
                        && "ONTOLOGY".equals(source.get("kind"));
        boolean exposed = body.containsKey("exposure");
        if (ontology || exposed) {
            var execution = body.get("execution") instanceof Map<?, ?> map ? map : Map.of();
            if (!(execution.get("readonly") instanceof Boolean readonly)
                    || !(execution.get("sideEffect") instanceof Boolean sideEffect))
                throw projectionError("execution.readonly/sideEffect必须为显式布尔");
            if (exposed) {
                var exposure =
                        projectionMap(
                                body.get("exposure"), "exposure", Set.of("kind"), Set.of("kind"));
                String kind = projectionText(exposure, "kind", "exposure.kind");
                if (!Set.of("QUERY", "MUTATION").contains(kind))
                    throw projectionError("exposure.kind只能是QUERY或MUTATION");
                if ("QUERY".equals(kind) && (!readonly || sideEffect)
                        || "MUTATION".equals(kind) && readonly)
                    throw projectionError("exposure.kind与execution.readonly/sideEffect冲突");
                result.put("exposure", Map.of("kind", kind));
            }
            if (readonly && sideEffect)
                throw projectionError("execution.readonly与execution.sideEffect互斥");
            if (body.containsKey("riskLevel")) {
                Object risk = body.get("riskLevel");
                if (!(risk instanceof String level)
                        || !Set.of("READ_ONLY", "CONFIRM_REQUIRED").contains(level))
                    throw projectionError("riskLevel只能是READ_ONLY或CONFIRM_REQUIRED");
                if ((!readonly || sideEffect) && "READ_ONLY".equals(level))
                    throw projectionError("riskLevel不能降低execution要求的确认风险");
                result.put("riskLevel", level);
            }
        }
        if (body.containsKey("pagination")) {
            if (!(body.get("pagination") instanceof Map<?, ?> raw))
                throw projectionError("pagination必须为非空对象");
            String mode =
                    raw.containsKey("mode")
                            ? projectionText(raw, "mode", "pagination.mode")
                            : "PAGE";
            if (!Set.of("PAGE", "OFFSET").contains(mode))
                throw projectionError("pagination.mode只能是PAGE或OFFSET");
            boolean offset = "OFFSET".equals(mode);
            var page =
                    projectionMap(
                            raw,
                            "pagination",
                            offset
                                    ? Set.of("mode", "request", "maxPageSize", "maxRows")
                                    : Set.of("mode", "request", "response", "maxPageSize"),
                            offset
                                    ? Set.of("mode", "request", "maxPageSize", "maxRows")
                                    : Set.of("request"));
            String coordinate = offset ? "offset" : "page";
            var request =
                    projectionMap(
                            page.get("request"),
                            "pagination.request",
                            Set.of(coordinate, "size"),
                            Set.of(coordinate, "size"));
            var normalized = new LinkedHashMap<String, Object>();
            if (page.containsKey("mode")) normalized.put("mode", mode);
            normalized.put(
                    "request",
                    Map.of(
                            coordinate,
                            projectionText(request, coordinate, "pagination.request." + coordinate),
                            "size",
                            projectionText(request, "size", "pagination.request.size")));
            if (page.containsKey("response")) {
                var response =
                        projectionMap(
                                page.get("response"),
                                "pagination.response",
                                Set.of("itemsPath", "totalPath"),
                                Set.of());
                var paths = new LinkedHashMap<String, Object>();
                for (String key : List.of("itemsPath", "totalPath"))
                    if (response.containsKey(key))
                        paths.put(key, projectionText(response, key, "pagination.response." + key));
                normalized.put("response", paths);
            }
            for (String key : List.of("maxPageSize", "maxRows"))
                if (page.containsKey(key))
                    normalized.put(key, projectionLimit(page.get(key), "pagination." + key));
            result.put("pagination", normalized);
        }
        return result;
    }

    private static Map<?, ?> projectionMap(
            Object raw, String path, Set<String> allowed, Set<String> required) {
        if (!(raw instanceof Map<?, ?> map)
                || !allowed.containsAll(map.keySet())
                || !map.keySet().containsAll(required))
            throw projectionError(path + "对象字段缺失或包含未知字段");
        return map;
    }

    private static String projectionText(Map<?, ?> map, String key, String path) {
        if (!(map.get(key) instanceof String text) || text.isBlank())
            throw projectionError(path + "必须为非空字符串");
        return text;
    }

    private static int projectionLimit(Object raw, String path) {
        var value = Json.tree(raw);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
            throw projectionError(path + "必须为正32位整数");
        return value.intValue();
    }

    /**
     * R01附录I.1/I.2 注册期返回声明方言校验：唯一目标形状为 {@code modelSummary.mode=TEMPLATE/tpl}
     * （或 PATH/path）+ JSON 容器卡片。旧 {@code template} 字段仅作输入别名按同一 {@code {{}}}
     * 语法解释，双字段不同值必须 400 并定位到 modelSummary；普通文本卡片不再可保存。
     * 运行边界（SkillAdapters）对同一方言做同构解释；已发布历史版本不在本方法约束内。
     */
    public static void validateReturnSpec(Object raw) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> spec))
            throw projectionError("returnSpec必须为对象");
        Object summary = spec.get("modelSummary");
        if (summary != null) {
            if (!(summary instanceof Map<?, ?> model)) throw projectionError("returnSpec.modelSummary必须为对象");
            String mode = Objects.toString(model.get("mode"), "");
            if (!mode.isEmpty() && !Set.of("PATH", "TEMPLATE").contains(mode))
                throw projectionError("returnSpec.modelSummary.mode只能是PATH或TEMPLATE");
            if ("TEMPLATE".equals(mode)) {
                Object tpl = model.get("tpl");
                Object alias = model.get("template");
                for (String key : List.of("tpl", "template")) {
                    Object value = model.get(key);
                    if (value != null && (!(value instanceof String text) || text.isBlank()))
                        throw projectionError("returnSpec.modelSummary." + key + "必须为非空字符串");
                }
                if (tpl != null && alias != null && !tpl.equals(alias))
                    throw projectionError("returnSpec.modelSummary的tpl与template冲突，请只保留一个");
                if (tpl == null && alias == null)
                    throw projectionError("returnSpec.modelSummary为TEMPLATE时必须提供tpl（或旧别名template）");
            }
            if ("PATH".equals(mode)
                    && (!(model.get("path") instanceof String path) || path.isBlank()))
                throw projectionError("returnSpec.modelSummary为PATH时必须提供非空字符串path");
        }
        Object card = spec.get("cardTemplate");
        if (card != null && !(card instanceof Map) && !(card instanceof List)) {
            if (!(card instanceof String text))
                throw projectionError("returnSpec.cardTemplate必须为JSON对象或数组容器");
            JsonNode parsed;
            try {
                parsed = Json.mapper().readTree(text);
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                throw projectionError("returnSpec.cardTemplate必须是可解析的JSON容器，普通文本卡片不再支持");
            }
            if (parsed == null || !parsed.isContainerNode())
                throw projectionError("returnSpec.cardTemplate必须为JSON对象或数组容器，普通文本卡片不再支持");
        }
    }

    private static PlatformException projectionError(String message) {
        return new PlatformException(400, "INVALID_SKILL", message);
    }

    /**
     * 注册期 Schema 唯一入口（PLAT-08b）：先按 {@link SchemaDialect} 把可空写法归一为规范形并一次性
     * 判定方言，再交给 networknt 编译与资源预算。
     *
     * <p>返回值就是 {@code skill_version.contract} 必须落库的形状：归一后不会再有 {@code nullable}
     * 或多具体类型联合，执行期 {@link #validate} 直接编译存储值，不必在运行期把旧写法重新解释一遍
     * （否则同一份契约会因入口不同而有不同语义，摘要也随读回路径漂移）。方言的改写与判定只存在于
     * admin-common 的 SchemaDialect，这里不维护第二份 type 白名单。
     *
     * <p>错误仍是 {@link IllegalArgumentException}（目录层映射为既有 400 契约错误），消息保留
     * Schema 关键字供前端识别，方言错误带 JSON Pointer 精确定位并追加在消息尾部。
     */
    public static Map<String, Object> normalizedSchema(Object raw) {
        SchemaDeadline.check();
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty())
            throw new IllegalArgumentException("Schema必须为非空对象");
        var canonical = SchemaDialect.normalize(Json.tree(raw));
        var errors = SchemaDialect.validate(canonical);
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    "Schema方言无效（不接受多具体类型联合与远程引用）: "
                            + String.join("; ", errors));
        build(canonical);
        @SuppressWarnings("unchecked")
        var normalized = (Map<String, Object>) Json.mapper().convertValue(canonical, Map.class);
        return normalized;
    }

    public static void schema(Object raw) {
        normalizedSchema(raw);
    }

    /**
     * MCP 2024-11-05 客户端使用 2020-12 引用；它与其它适配器共用同一方言入口（PLAT-08b），
     * 保留方法名是因为 MCP 注册与远端工具目录按它调用，不应该有两套 Schema 规则。
     */
    public static void mcpSchema(Object raw) {
        normalizedSchema(raw);
    }

    /**
     * 输出校验模式（PLAT-07）。预算拒绝是安全边界，不因模式而消失：STRICT 保持“超预算即失败”，
     * DEGRADED 只把“实例工作量超限”降级为顶层校验并显式告警，类型/必填/引用错误仍全部失败。
     * 回调、治理与产物入库走无 mode 的 {@link #validate(Object, Object)}，语义恒为 STRICT。
     */
    public enum SchemaMode {
        STRICT,
        DEGRADED
    }

    private record Prepared(
            JsonSchema validator,
            SchemaWorkBudget.Plan plan,
            BoundedRegexFactory regex,
            JsonNode schema) {}

    private static Prepared prepare(Object raw) {
        return prepare(raw, SchemaWorkBudget.MAX_EXPANDED_WORK);
    }

    private static Prepared prepare(Object raw, long maxExpandedWork) {
        SchemaDeadline.check();
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty())
            throw new IllegalArgumentException("Schema必须为非空对象");
        return build(Json.tree(raw), maxExpandedWork);
    }

    private static Prepared build(JsonNode json) {
        return build(json, SchemaWorkBudget.MAX_EXPANDED_WORK);
    }

    /** networknt 编译与预算：只接受已归一的规范形；方言判定在 {@link #normalizedSchema} 之前完成。 */
    private static Prepared build(JsonNode json, long maxExpandedWork) {
        // Pure admission precedes every networknt construction, including ordinary HTTP.
        var plan = SchemaWorkBudget.inspect(json, maxExpandedWork);
        var regex = new BoundedRegexFactory(plan.expressions());
        try {
            var config = new SchemaValidatorsConfig();
            config.setPreloadJsonSchemaRefMaxNestingDepth(64);
            config.setRegularExpressionFactory(regex);
            config.setFailFast(true);
            var validator = SCHEMAS.getSchema(json, config);
            // Check unused definitions too. The admitted acyclic graph bounds the total
            // repeated expansion of all these roots; the closed loader remains final defense.
            for (String pointer : plan.locations()) {
                SchemaDeadline.check();
                var location = new JsonNodePath(PathType.JSON_POINTER);
                var cursor = json;
                if (!pointer.isEmpty())
                    for (String segment : pointer.substring(1).split("/", -1)) {
                        String key = segment.replace("~1", "/").replace("~0", "~");
                        location =
                                cursor.isArray()
                                        ? location.append(Integer.parseInt(key))
                                        : location.append(key);
                        cursor =
                                cursor.isArray()
                                        ? cursor.path(Integer.parseInt(key))
                                        : cursor.path(key);
                    }
                (pointer.isEmpty() ? validator : validator.getSubSchema(location))
                        .initializeValidators();
            }
            SchemaDeadline.check();
            return new Prepared(validator, plan, regex, json);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("技能Schema无效或包含不可加载的引用");
        }
    }

    public static void validate(Object schema, Object value) {
        validate(schema, value, SchemaMode.STRICT);
    }

    /** 默认展开上限的 STRICT/DEGRADED 重载；只有输出校验路径需要显式模式。 */
    public static SchemaWorkBudget.Admission validate(
            Object schema, Object value, SchemaMode mode) {
        return validate(schema, value, mode, SchemaWorkBudget.MAX_EXPANDED_WORK);
    }

    /**
     * 一次输出校验并返回准入结论（PLAT-07）。
     *
     * <p>STRICT 与旧行为完全一致：预算拒绝抛 {@code Schema预算拒绝：} 异常。DEGRADED 只在
     * “节点数 × 展开工作量”这一主维度超限时降级（{@link SchemaWorkBudget.Admission#degradable()}）：
     * 跳过 networknt 的全量递归，只做两条顶层检查——根 type 与根 required 是否存在——然后把
     * 未通过（admitted=false）的结论交还调用方去告警。正则/比较/扫描/数字/唯一性这五类
     * “校验自身成本”上限不属于可降级范围，DEGRADED 下仍拒绝。未被降级的失败（类型不符、
     * 必填缺失、引用非法、展开超限）也仍抛 {@link IllegalArgumentException}，所以降级永远不会
     * 被读成“通过严格校验”。
     */
    public static SchemaWorkBudget.Admission validate(
            Object schema, Object value, SchemaMode mode, long maxExpandedWork) {
        Objects.requireNonNull(mode, "Schema校验模式不能为空");
        var prepared = prepare(schema, maxExpandedWork);
        SchemaDeadline.check();
        var instance = Json.tree(value);
        var admission =
                prepared
                        .plan
                        .admission(
                                instance,
                                prepared.regex.programUnits(),
                                SchemaWorkBudget.MAX_INSTANCE_WORK);
        if (!admission.admitted()) {
            // 只有主维度（节点数 × 展开工作量）可降级；regex/comparison/scan/numeric/unique
            // 是“校验本身的成本”边界，跳过后等于放弃资源保护，DEGRADED 下也照常拒绝。
            if (mode == SchemaMode.STRICT || !admission.degradable()) admission.require();
            topLevel(prepared.schema, instance);
            SchemaDeadline.check();
            return admission;
        }
        var errors = prepared.validator.validate(instance);
        SchemaDeadline.check();
        if (!errors.isEmpty())
            throw new IllegalArgumentException(errors.iterator().next().toString());
        return admission;
    }

    /**
     * DEGRADED 的顶层检查：只做“根 type 对实例类型”与“根 required 的键存在”两件事，不递归深层。
     *
     * <p>这是有意限制：它保证形状明显错误（把数组当对象、缺主键）仍然失败，但不承诺深层语义；
     * 告警字段 {@code SCHEMA_BUDGET_EXCEEDED} 就是告诉调用方“未做严格校验”，不能省略。
     */
    private static void topLevel(JsonNode schema, JsonNode instance) {
        var type = schema.path("type");
        if (!type.isMissingNode() && !type.isNull() && !matchesType(type, instance))
            throw new IllegalArgumentException("实例不符合Schema根类型：" + type);
        var required = schema.path("required");
        if (required.isArray() && instance.isObject())
            for (JsonNode name : required)
                if (!instance.has(name.asText()))
                    throw new IllegalArgumentException("实例缺少根必填字段：" + name.asText());
    }

    private static boolean matchesType(JsonNode declared, JsonNode instance) {
        if (declared.isArray()) {
            for (JsonNode type : declared)
                if (type.isTextual() && matchesTypeName(type.asText(), instance)) return true;
            return false;
        }
        return declared.isTextual() && matchesTypeName(declared.asText(), instance);
    }

    private static boolean matchesTypeName(String type, JsonNode instance) {
        return switch (type) {
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "number" -> instance.isNumber();
            case "integer" -> instance.isIntegralNumber();
            case "boolean" -> instance.isBoolean();
            case "null" -> instance.isNull();
            default -> false;
        };
    }

    /**
     * 注册期 authScheme 归一化与组合校验的唯一入口（PLAT-11）：目录层只调用本方法，不自行比较
     * 字符串，也不在别处复制“NONE 不得带凭据/API_KEY 必须带凭据”这类规则。
     *
     * <p>归一化的结果必须显式落库：执行侧只按存储值发头，不再回头读 legacy 开关，否则同一契约
     * 会因部署配置不同而在运行期被解释成不同鉴权方式。未声明时是否补 BEARER 由
     * {@code legacy-bearer} 决定，默认 true 保持存量技能行为不变。
     */
    public static AuthScheme authScheme(
            Object declared, String authRef, String adapterType, boolean legacyBearer) {
        AuthScheme scheme = AuthScheme.registration(declared, legacyBearer);
        AuthScheme.checkCombination(scheme, authRef, adapterType);
        return scheme;
    }

    /**
     * identity 出站声明（PLAT-12，方案 §4.12）的唯一解释入口：注册期校验、执行期头构造与运行详情
     * trace 都在本类，其他模块不得自行解释该字段，否则同一契约会在不同入口被注入不同身份头。
     *
     * <p>不变量一：mode 只接受 {@link #IDENTITY_MODE}（PLATFORM_SUBJECT）——出站身份只有一个来源，
     * 就是进程内已验主体快照；引入第二种 mode 等于在契约里承诺一个实现不存在的身份通道。
     *
     * <p>不变量二：headers 的值只允许主体快照字段白名单（{@link #IDENTITY_SOURCES}）。声明任意
     * header→值的映射会把出站头变成自由写入通道（伪造 X-User-*、覆盖对端语义头），这正是方案
     * “伪造 X-User-* 被网关拒绝”要堵的口子；header 名为 HTTP token 子集且拒绝与鉴权/幂等/内容
     * 语义冲突的保留名。
     *
     * <p>不变量三：只有 HTTP 适配器接受声明。同步与异步的发头点都在 HTTP 适配器内；MCP 的 SSE
     * transport、ALGORITHM 的 SDK、PROCESS 的 stdin 都没有 X-User-* 通道，接受声明等于承诺一个
     * 不兑现的行为（与 AuthScheme 对 API_KEY 的适配器限制同一裁决）。
     */
    public static final String IDENTITY_FIELD = "identity";
    public static final String IDENTITY_MODE = "PLATFORM_SUBJECT";
    /** 允许映射为出站头的主体快照字段白名单。 */
    public static final Set<String> IDENTITY_SOURCES =
            Set.of("principalId", "tenantId", "roles", "attributes");
    /** 与适配器固定头或鉴权语义冲突的保留头名（比较不区分大小写）。 */
    private static final Set<String> IDENTITY_RESERVED_HEADERS =
            Set.of("authorization", "x-api-key", "idempotency-key", "accept", "content-type",
                    "host", "cookie", "set-cookie");

    public static final String CODE_IDENTITY_INVALID = "SKILL_IDENTITY_INVALID";
    public static final String CODE_IDENTITY_MODE_INVALID = "SKILL_IDENTITY_MODE_INVALID";
    public static final String CODE_IDENTITY_HEADER_INVALID = "SKILL_IDENTITY_HEADER_INVALID";
    public static final String CODE_IDENTITY_UNSUPPORTED = "SKILL_IDENTITY_UNSUPPORTED";

    /**
     * 注册期校验并归一化 identity 声明；非法声明一律 400 可读错误（带字段路径与收到的值）。
     * 归一化结果原样落库（mode + headers），执行期与摘要都只读存储值。
     *
     * @param declared 契约里的 identity 原始值（非 null，调用方保证缺省时不进入本方法）
     * @param adapterType 适配器类型；仅 HTTP 有出站头注入点
     */
    public static Map<String, Object> identity(Object declared, String adapterType) {
        if (!"HTTP".equals(adapterType))
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_UNSUPPORTED,
                    "identity出站身份声明仅支持HTTP技能：同步与异步适配器才有X-User-*发头点，"
                            + "MCP/ALGORITHM/PROCESS没有出站HTTP头通道，收到适配器类型: "
                            + adapterType);
        if (!(declared instanceof Map<?, ?> declaration) || declaration.isEmpty())
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_INVALID,
                    "identity必须为对象，形如"
                        + " {\"mode\":\"PLATFORM_SUBJECT\",\"headers\":{\"X-User-Id\":\"principalId\"}}");
        Object mode = declaration.get("mode");
        if (!IDENTITY_MODE.equals(Objects.toString(mode, "")))
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_MODE_INVALID,
                    "identity.mode只能是PLATFORM_SUBJECT（出站身份只来自进程内已验主体快照），收到: "
                            + Objects.toString(mode, ""));
        Object rawHeaders = declaration.get("headers");
        if (!(rawHeaders instanceof Map<?, ?> headers) || headers.isEmpty())
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_INVALID,
                    "identity.headers必须为非空对象，键为出站头名，值为主体快照字段（principalId/tenantId/roles/attributes）");
        var normalized = new LinkedHashMap<String, Object>();
        for (var entry : headers.entrySet()) {
            String name = Objects.toString(entry.getKey(), "");
            String source = entry.getValue() == null ? "" : entry.getValue().toString();
            checkIdentityHeader(name, source);
            normalized.put(name, source);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", IDENTITY_MODE);
        result.put("headers", normalized);
        return result;
    }

    private static void checkIdentityHeader(String name, String source) {
        if (!name.matches("[A-Za-z0-9-]{1,128}"))
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_HEADER_INVALID,
                    "identity.headers的键必须为合法HTTP头名（字母/数字/连字符），收到: " + name);
        if (IDENTITY_RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_HEADER_INVALID,
                    "identity.headers不得声明与鉴权/幂等/传输语义冲突的保留头: " + name);
        if (!IDENTITY_SOURCES.contains(source))
            throw new PlatformException(
                    400,
                    CODE_IDENTITY_HEADER_INVALID,
                    "identity.headers."
                            + name
                            + " 的值只能是主体快照字段 principalId/tenantId/roles/attributes，收到: "
                            + source);
    }

    /**
     * 执行期把已验主体快照翻译成出站身份头；未声明或声明畸形返回空表（历史脏数据按未声明处理，
     * 注册期校验负责挡新增）。声明了 identity 但 {@code subject} 为 null（系统级调用没有可用主体）
     * 时同样返回空表——注入是补充可读身份而不是鉴权依据，跳过不伪造，结论经 {@link #identityTrace}
     * 落运行详情。空集合不发送空串头：对端按逗号拆分 roles 会得到幽灵角色。
     */
    public static Map<String, String> identityHeaders(
            Map<String, Object> contract, SubjectSnapshot subject) {
        var declared = declaredIdentity(contract);
        if (declared == null || subject == null) return Map.of();
        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) declared.get("headers");
        if (headers == null) return Map.of();
        var result = new LinkedHashMap<String, String>();
        for (var entry : headers.entrySet()) {
            String value = subjectValue(subject, entry.getValue().toString());
            if (value != null) result.put(entry.getKey().toString(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    /** 快照字段取值与序列化（定死）：principalId/tenantId 原文，roles 字典序逗号连接，attributes 键排序 JSON。 */
    private static String subjectValue(SubjectSnapshot subject, String source) {
        return switch (source) {
            case "principalId" -> blankToNull(subject.principalId());
            case "tenantId" -> blankToNull(subject.tenantId());
            case "roles" ->
                    subject.roles().isEmpty()
                            ? null
                            : subject.roles().stream()
                                    .sorted()
                                    .reduce((a, b) -> a + "," + b)
                                    .orElseThrow();
            case "attributes" ->
                    subject.attributes().isEmpty() ? null : attributesJson(subject.attributes());
            default -> null;
        };
    }

    /** attributes 的出站格式：键字典序、值排序字符串数组的紧凑JSON（同一主体序列化结果恒定）。 */
    private static String attributesJson(Map<String, Set<String>> attributes) {
        var sorted = new TreeMap<String, Object>();
        attributes.forEach((key, values) -> sorted.put(key, values.stream().sorted().toList()));
        return Json.write(sorted);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 容忍读取契约上的 identity 声明；畸形（历史脏数据）按未声明处理返回 null。 */
    private static Map<?, ?> declaredIdentity(Map<String, Object> contract) {
        Object declared = contract.get(IDENTITY_FIELD);
        return declared instanceof Map<?, ?> declaration
                        && "PLATFORM_SUBJECT".equals(Objects.toString(declaration.get("mode"), ""))
                ? declaration
                : null;
    }

    /**
     * 运行详情可见的注入结论：声明了 identity 的技能在执行事实信封里记录 声明了哪些头、值来自
     * 哪个快照字段、本次是否真的注入；未声明返回 null，信封形状零变化。trace 只带头名/来源/布尔，
     * 不带头值——身份明细不向运行详情扩散。
     */
    public static Map<String, Object> identityTrace(
            Map<String, Object> contract, SubjectSnapshot subject) {
        Map<?, ?> declared = declaredIdentity(contract);
        if (declared == null) return null;
        @SuppressWarnings("unchecked")
        var headers =
                declared.get("headers") instanceof Map<?, ?> raw
                        ? (Map<String, Object>) raw
                        : Map.<String, Object>of();
        var rows = new ArrayList<Map<String, Object>>();
        boolean anySent = false;
        for (var entry : headers.entrySet()) {
            boolean sent =
                    subject != null && subjectValue(subject, entry.getValue().toString()) != null;
            anySent |= sent;
            var row = new LinkedHashMap<String, Object>();
            row.put("name", entry.getKey().toString());
            row.put("from", entry.getValue().toString());
            row.put("sent", sent);
            rows.add(row);
        }
        var trace = new LinkedHashMap<String, Object>();
        trace.put("mode", IDENTITY_MODE);
        trace.put("headers", rows);
        trace.put("injected", anySent);
        trace.put(
                "skipReason",
                anySent ? null : subject == null ? "NO_ACTOR_SUBJECT" : "SUBJECT_VALUES_EMPTY");
        return trace;
    }

    public static URI endpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("端点必须为无明文凭据的HTTP(S)地址");
        return uri;
    }

    /**
     * PLAT-15（契约 §14）execution.approval 的唯一解释入口：模式、风险与 PREPARE 入口都只在本类
     * 读取，其他模块不得自行解释该字段，否则同一契约会在不同入口被解释成不同审批流程。
     *
     * <p>兼容口径（定死 D1）：approval 键缺省 = SINGLE_STEP——存量技能不落键，行为零变化；
     * 显式声明 PREPARE_EXECUTE 才进入两段，显式 SINGLE_STEP 等价缺省（旧技能自述用）。
     */
    public static final String APPROVAL_FIELD = "approval";
    public static final String APPROVAL_MODE_TWO_PHASE = "PREPARE_EXECUTE";
    public static final String APPROVAL_MODE_SINGLE_STEP = "SINGLE_STEP";
    public static final String APPROVAL_RISK_HIGH = "HIGH";

    /** 高危判别（定死 D4）：只认契约声明 execution.approval.risk=HIGH，不从 URL/方法猜测。 */
    public static String approvalMode(Map<String, Object> contract) {
        var declared = declaredApproval(contract);
        return declared != null
                        && APPROVAL_MODE_TWO_PHASE.equals(
                                Objects.toString(declared.get("mode"), ""))
                ? APPROVAL_MODE_TWO_PHASE
                : APPROVAL_MODE_SINGLE_STEP;
    }

    public static String approvalRisk(Map<String, Object> contract) {
        var declared = declaredApproval(contract);
        return declared != null && APPROVAL_RISK_HIGH.equals(declared.get("risk"))
                ? APPROVAL_RISK_HIGH
                : "STANDARD";
    }

    /** PREPARE 独立入口声明（定死 D2）：{endpoint, httpMethod?}；未声明两段时返回 null。 */
    public static Map<?, ?> prepareDeclaration(Map<String, Object> contract) {
        var declared = declaredApproval(contract);
        if (declared == null
                || !APPROVAL_MODE_TWO_PHASE.equals(Objects.toString(declared.get("mode"), "")))
            return null;
        return declared.get("prepare") instanceof Map<?, ?> prepare ? prepare : null;
    }

    private static Map<?, ?> declaredApproval(Map<String, Object> contract) {
        var execution = contract.get("execution");
        return execution instanceof Map<?, ?> e
                        && e.get(APPROVAL_FIELD) instanceof Map<?, ?> a
                        && !((Map<?, ?>) a).isEmpty()
                ? a
                : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> guard(
            Actor actor, Map<String, Object> guard, Map<String, Object> arguments) {
        var roles = (Collection<String>) guard.getOrDefault("roles", List.of());
        var attributes = (Map<String, Object>) guard.getOrDefault("attributes", Map.of());
        if (roles.isEmpty() && attributes.isEmpty())
            throw new IllegalArgumentException("技能未声明守卫，默认拒绝");
        for (String role : roles)
            if (!actor.roles().contains(role) && !actor.roles().contains("ADMIN"))
                throw new IllegalArgumentException("缺少技能所需角色");
        var secured = new LinkedHashMap<String, Object>(arguments);
        for (var entry : attributes.entrySet()) {
            if (!(entry.getValue() instanceof Collection<?> declared))
                throw new IllegalArgumentException("守卫属性声明无效");
            var allowed =
                    new HashSet<String>(actor.attributes().getOrDefault(entry.getKey(), Set.of()));
            // guardAttrs仅声明属性名时没有静态值清单，此时仍只能使用已验Actor的非空授权集。
            if (!declared.isEmpty())
                allowed.retainAll(declared.stream().map(Object::toString).toList());
            if (allowed.isEmpty()) throw new IllegalArgumentException("用户无该范围权限");
            Object proposed = arguments.get(entry.getKey());
            // 仅声明属性名（没有静态值清单）时只校验调用者持有该范围，不要求上游参数重复携带：
            // 这类查询技能的远端接口没有对应参数，缺失参数不代表越权，授权边界已由已验Actor收窄。
            if (proposed == null && declared.isEmpty()) continue;
            if (proposed instanceof Collection<?> values) {
                var intersection =
                        values.stream()
                                .map(Object::toString)
                                .filter(allowed::contains)
                                .distinct()
                                .toList();
                if (intersection.isEmpty()) throw new IllegalArgumentException("用户无该范围权限");
                secured.put(entry.getKey(), intersection);
            } else if (proposed instanceof String value) {
                if (!allowed.contains(value)) throw new IllegalArgumentException("用户无该范围权限");
            } else throw new IllegalArgumentException("守卫参数缺失或类型不正确");
        }
        return secured;
    }

    /**
     * 语义摘要的规范化入口（PLAT-03/10）：谁算“契约语义”只在 {@link SkillDigest} 里定义一次，
     * 注册/保存、发布门禁与证据重绑定都从这里取同一份规范化结果，不在调用方另写排除集。
     * 本方法保持纯函数性质（不读 DB、不碰 SecurityContext），因此单元测试可直接对 Map 断言。
     */
    public static String semanticCanonical(Object contract) {
        return SkillDigest.canonical(contract);
    }
}
