package cn.agentplatform.app.domain;

import java.util.*;
import java.util.regex.Pattern;

public final class AppRules {
    private AppRules() {}

    private static final Pattern VARIABLE =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    public static String canonical(Object value) {
        return cn.agentplatform.common.Json.canonical(value);
    }

    public static String render(
            String content, List<Map<String, Object>> variables, Map<String, Object> values) {
        var resolved = new HashMap<String, Object>();
        for (var variable : variables) {
            String name = Objects.toString(variable.get("name"), "");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || resolved.containsKey(name))
                throw new IllegalArgumentException("变量名无效或重复: " + name);
            Object value =
                    values.containsKey(name) ? values.get(name) : variable.get("defaultValue");
            if (value == null && Boolean.TRUE.equals(variable.get("required")))
                throw new IllegalArgumentException("缺少变量: " + name);
            if (value != null
                    && !typeMatches(Objects.toString(variable.get("type"), "string"), value))
                throw new IllegalArgumentException("变量类型不匹配: " + name);
            resolved.put(name, value);
        }
        if (!resolved.keySet().containsAll(values.keySet()))
            throw new IllegalArgumentException("包含未声明变量");
        String syntax = VARIABLE.matcher(content).replaceAll("");
        if (syntax.contains("{{") || syntax.contains("}}"))
            throw new IllegalArgumentException("插值语法无效");
        var matcher = VARIABLE.matcher(content);
        var rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!resolved.containsKey(name) || resolved.get(name) == null)
                throw new IllegalArgumentException("未定义变量: " + name);
            Object v = resolved.get(name);
            matcher.appendReplacement(
                    rendered,
                    java.util.regex.Matcher.quoteReplacement(
                            v instanceof String ? v.toString() : canonical(v)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    @SuppressWarnings("unchecked")
    public static void validateValue(
            Map<String, Object> schema, Object value, String path, boolean expressions) {
        if (expressions
                && value instanceof String expression
                && expression.startsWith("${")
                && expression.endsWith("}")) return;
        String type = Objects.toString(schema.get("type"), "");
        if (value == null) {
            if (!type.equals("null")) throw new IllegalArgumentException(path + ": 不允许null");
            return;
        }
        if (!typeMatches(type, value)) throw new IllegalArgumentException(path + ": 需要" + type);
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value))
            throw new IllegalArgumentException(path + ": 不在枚举范围");
        if (value instanceof Map<?, ?> object) {
            var properties =
                    schema.get("properties") instanceof Map<?, ?> raw
                            ? (Map<String, Object>) raw
                            : Map.<String, Object>of();
            if (schema.get("required") instanceof List<?> required)
                for (Object key : required)
                    if (!object.containsKey(key) || object.get(key) == null)
                        throw new IllegalArgumentException(path + "." + key + ": 缺少必填参数");
            for (var entry : object.entrySet()) {
                String key = entry.getKey().toString();
                if (properties.get(key) instanceof Map<?, ?> child)
                    validateValue(
                            (Map<String, Object>) child,
                            entry.getValue(),
                            path + "." + key,
                            expressions);
                else if (Boolean.FALSE.equals(schema.get("additionalProperties")))
                    throw new IllegalArgumentException(path + "." + key + ": 未声明字段");
            }
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue())
                throw new IllegalArgumentException(path + ": 数组元素不足");
            if (schema.get("maxItems") instanceof Number max && list.size() > max.intValue())
                throw new IllegalArgumentException(path + ": 数组元素过多");
            if (schema.get("items") instanceof Map<?, ?> item)
                for (int i = 0; i < list.size(); i++)
                    validateValue(
                            (Map<String, Object>) item,
                            list.get(i),
                            path + "[" + i + "]",
                            expressions);
        }
        if (value instanceof Number n) {
            if (schema.get("minimum") instanceof Number min && n.doubleValue() < min.doubleValue())
                throw new IllegalArgumentException(path + ": 小于下限");
            if (schema.get("maximum") instanceof Number max && n.doubleValue() > max.doubleValue())
                throw new IllegalArgumentException(path + ": 大于上限");
        }
    }

    public static boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" ->
                    value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    public static List<String> graphErrors(Map<String, Object> graph) {
        var errors = new ArrayList<String>();
        if (!(graph.get("nodes") instanceof List<?> rawNodes)
                || !(graph.get("edges") instanceof List<?> rawEdges))
            return List.of("nodes和edges必须为数组");
        var ids = new HashSet<String>();
        var starts = new ArrayList<String>();
        var ends = new ArrayList<String>();
        var next = new HashMap<String, List<String>>();
        var indegree = new HashMap<String, Integer>();
        for (Object raw : rawNodes) {
            if (!(raw instanceof Map<?, ?> node)) {
                errors.add("节点格式错误");
                continue;
            }
            String id = Objects.toString(node.get("id"), "");
            String type = Objects.toString(node.get("type"), "");
            if (id.isBlank() || !ids.add(id)) errors.add("节点ID为空或重复: " + id);
            if (type.equals("START")) starts.add(id);
            if (type.equals("END")) ends.add(id);
            next.put(id, new ArrayList<>());
            indegree.put(id, 0);
        }
        if (starts.size() != 1 || ends.size() != 1) errors.add("必须恰有一个START和END");
        for (Object raw : rawEdges) {
            if (!(raw instanceof Map<?, ?> edge)) {
                errors.add("边格式错误");
                continue;
            }
            String source =
                    Objects.toString(
                            edge.containsKey("from") ? edge.get("from") : edge.get("source"), "");
            String target =
                    Objects.toString(
                            edge.containsKey("to") ? edge.get("to") : edge.get("target"), "");
            if (!ids.contains(source) || !ids.contains(target)) {
                errors.add("边引用不存在节点");
                continue;
            }
            next.get(source).add(target);
            indegree.merge(target, 1, Integer::sum);
        }
        for (Object raw : rawNodes) {
            if (!(raw instanceof Map<?, ?> node)) continue;
            String id = Objects.toString(node.get("id"), "");
            if (node.get("errorPolicy") instanceof Map<?, ?> policy
                    && "ERROR_EDGE".equals(policy.get("onFail"))) {
                String target = Objects.toString(policy.get("errorEdgeTo"), "");
                if (!ids.contains(target) || !next.getOrDefault(id, List.of()).contains(target))
                    errors.add("ERROR_EDGE缺少对应错误边: " + id);
            }
            if ("CONFIRM".equals(node.get("type"))
                    && (!(node.get("confirmRoles") instanceof List<?> roles) || roles.isEmpty()))
                errors.add("确认节点需声明confirmRoles: " + id);
        }
        for (Object raw : rawEdges)
            if (raw instanceof Map<?, ?> edge && edge.containsKey("when")) {
                String source =
                        Objects.toString(
                                edge.containsKey("from") ? edge.get("from") : edge.get("source"),
                                "");
                if (rawNodes.stream()
                        .noneMatch(
                                n ->
                                        n instanceof Map<?, ?> node
                                                && source.equals(node.get("id"))
                                                && "CONDITION".equals(node.get("type"))))
                    errors.add("条件出边仅允许CONDITION节点");
            }
        var queue = new ArrayDeque<String>();
        indegree.forEach(
                (id, n) -> {
                    if (n == 0) queue.add(id);
                });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove();
            visited++;
            for (String target : next.get(id))
                if (indegree.merge(target, -1, Integer::sum) == 0) queue.add(target);
        }
        if (visited != ids.size()) errors.add("图存在环");
        if (starts.size() == 1 && ends.size() == 1) {
            var reachable = new HashSet<String>();
            walk(starts.getFirst(), next, reachable);
            if (!reachable.equals(ids)) errors.add("图含START不可达节点");
            var reverse = new HashMap<String, List<String>>();
            ids.forEach(id -> reverse.put(id, new ArrayList<>()));
            next.forEach((id, targets) -> targets.forEach(t -> reverse.get(t).add(id)));
            var toEnd = new HashSet<String>();
            walk(ends.getFirst(), reverse, toEnd);
            if (!toEnd.equals(ids)) errors.add("存在无法到达END的节点");
            if (!next.get(ends.getFirst()).isEmpty()
                    || rawEdges.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof Map<?, ?> m
                                                    && starts.getFirst()
                                                            .equals(
                                                                    m.containsKey("to")
                                                                            ? m.get("to")
                                                                            : m.get("target"))))
                errors.add("START不可有入边，END不可有出边");
        }
        return errors;
    }

    private static void walk(String start, Map<String, List<String>> next, Set<String> seen) {
        var q = new ArrayDeque<String>();
        q.add(start);
        while (!q.isEmpty()) {
            String id = q.remove();
            if (seen.add(id)) q.addAll(next.getOrDefault(id, List.of()));
        }
    }
}
