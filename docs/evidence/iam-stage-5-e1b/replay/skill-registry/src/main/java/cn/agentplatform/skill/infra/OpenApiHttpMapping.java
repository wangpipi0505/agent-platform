package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** OpenAPI参数映射是声明式数据；只替换路径段，不允许参数改变scheme/authority或引入新查询。 */
public final class OpenApiHttpMapping {
    private OpenApiHttpMapping() {}

    public record Request(String endpoint, Object body) {}

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_HTTP_MAPPING", message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("映射必须为对象");
        return (Map<String, Object>) value;
    }

    public static void validate(Map<String, Object> contract) {
        if (!contract.containsKey("httpMapping")) return;
        if (!"HTTP".equals(contract.get("type"))) throw invalid("参数映射仅支持HTTP");
        var mapping = object(contract.get("httpMapping"));
        if (!mapping.keySet().equals(Set.of("path", "query", "body")))
            throw invalid("映射只能包含path/query/body");
        String endpoint = Objects.toString(contract.get("endpoint"), "");
        var names = new HashSet<String>();
        String replaced = endpoint;
        for (String kind : List.of("path", "query")) {
            if (!(mapping.get(kind) instanceof List<?> list) || list.size() > 64)
                throw invalid("参数映射必须为至多64项数组");
            for (Object name : list) {
                if (!(name instanceof String field)
                        || !field.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")
                        || !names.add(field)) throw invalid("映射参数名无效或重复");
                if (kind.equals("path")) {
                    if (!replaced.contains("{" + field + "}")) throw invalid("路径模板与参数不匹配");
                    replaced = replaced.replace("{" + field + "}", "x");
                }
            }
        }
        if (!(mapping.get("body") instanceof String body)
                || (!body.isEmpty() && !body.equals("body"))
                || (!body.isEmpty() && !names.add(body))) throw invalid("JSON请求体只支持保留参数body");
        var uri = SkillRules.endpoint(replaced);
        if (uri.getRawQuery() != null
                || replaced.contains("{")
                || replaced.contains("}")
                || !endpoint.startsWith(uri.getScheme() + "://" + uri.getRawAuthority() + "/"))
            throw invalid("路径参数只能位于HTTP路径");
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(contract.get("httpMethod")))
            throw invalid("映射方法无效");
        if ("GET".equals(contract.get("httpMethod")) && !body.isEmpty())
            throw invalid("GET不能携带JSON体");
        if (contract.containsKey("inputSchema")) {
            var schema = object(contract.get("inputSchema"));
            var properties = object(schema.getOrDefault("properties", Map.of()));
            if (!"object".equals(schema.get("type")) || !properties.keySet().equals(names))
                throw invalid("输入Schema属性必须与path/query/body映射一致");
            var required = schema.getOrDefault("required", List.of());
            if (!(required instanceof List<?> fields)) throw invalid("required必须为数组");
            for (String kind : List.of("path", "query"))
                for (Object name : (List<?>) mapping.get(kind)) {
                    var parameter = object(properties.get(name));
                    if (!Set.of("string", "integer", "number", "boolean")
                            .contains(parameter.get("type")))
                        throw invalid("path/query映射要求标量Schema");
                    if (kind.equals("path") && !fields.contains(name))
                        throw invalid("path映射要求required参数");
                }
            if (!body.isEmpty() && !fields.contains(body))
                throw invalid("JSON请求体映射要求required body");
        }
    }

    public static Request request(Map<String, Object> contract, Map<String, Object> args) {
        validate(contract);
        var mapping = object(contract.get("httpMapping"));
        String endpoint = contract.get("endpoint").toString();
        var allowed = new HashSet<String>();
        for (Object name : (List<?>) mapping.get("path")) {
            String key = name.toString();
            allowed.add(key);
            if (!args.containsKey(key)) throw invalid("缺少path参数");
            endpoint = endpoint.replace("{" + key + "}", encode(scalar(args.get(key)), true));
        }
        var query = new StringJoiner("&");
        for (Object name : (List<?>) mapping.get("query")) {
            String key = name.toString();
            allowed.add(key);
            if (args.containsKey(key))
                query.add(encode(key, false) + "=" + encode(scalar(args.get(key)), false));
        }
        String body = mapping.get("body").toString();
        if (!body.isEmpty()) allowed.add(body);
        if (!allowed.containsAll(args.keySet())) throw invalid("输入包含未映射参数");
        if (!query.toString().isEmpty()) endpoint += "?" + query;
        SkillRules.endpoint(endpoint);
        return new Request(endpoint, body.isEmpty() ? null : args.get(body));
    }

    private static String scalar(Object value) {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
            throw invalid("path/query参数必须为非空标量");
        return value.toString();
    }

    private static String encode(String value, boolean path) {
        if (value.length() > 4096
                || (path && (value.isEmpty() || value.equals(".") || value.equals(".."))))
            throw invalid("路径值为空、点段或参数过长");
        var out = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int b = raw & 255;
            if (b >= 'A' && b <= 'Z'
                    || b >= 'a' && b <= 'z'
                    || b >= '0' && b <= '9'
                    || b == '-'
                    || b == '_'
                    || b == '~'
                    || b == '.') out.append((char) b);
            else
                out.append('%')
                        .append("0123456789ABCDEF".charAt(b >>> 4))
                        .append("0123456789ABCDEF".charAt(b & 15));
        }
        return out.toString();
    }
}
