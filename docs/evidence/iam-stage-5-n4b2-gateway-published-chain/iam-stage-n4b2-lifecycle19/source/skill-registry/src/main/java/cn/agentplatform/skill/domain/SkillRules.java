package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.BoundedRegexFactory;
import cn.agentplatform.common.schema.SchemaDeadline;
import cn.agentplatform.common.schema.SchemaWorkBudget;

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

    public static void schema(Object raw) {
        prepare(raw, false);
    }

    /** Fixed MCP 2024-11-05 client uses 2020-12 references, never ignored recursiveRef. */
    public static void mcpSchema(Object raw) {
        prepare(raw, true);
    }

    private record Prepared(
            JsonSchema validator, SchemaWorkBudget.Plan plan, BoundedRegexFactory regex) {}

    private static Prepared prepare(Object raw, boolean mcp) {
        SchemaDeadline.check();
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty())
            throw new IllegalArgumentException("Schema必须为非空对象");
        var json = Json.tree(raw);
        // Pure admission precedes every networknt construction, including ordinary HTTP.
        var plan = SchemaWorkBudget.inspect(json);
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
            return new Prepared(validator, plan, regex);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("技能Schema无效或包含不可加载的引用");
        }
    }

    public static void validate(Object schema, Object value) {
        var prepared = prepare(schema, false);
        SchemaDeadline.check();
        var instance = Json.tree(value);
        prepared.plan.admitInstance(instance, prepared.regex.programUnits());
        var errors = prepared.validator.validate(instance);
        SchemaDeadline.check();
        if (!errors.isEmpty())
            throw new IllegalArgumentException(errors.iterator().next().toString());
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
