package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
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

    public static URI endpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), ""))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("端点必须为无明文凭据的HTTP(S)地址");
        return uri;
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
}
