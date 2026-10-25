package cn.agentplatform.engine.domain;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.*;
import java.util.regex.*;

public final class Values {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    public static JsonNode resolve(JsonNode value, JsonNode scope) {
        if (value.isObject()) {
            var result = Json.tree(Map.of()).deepCopy();
            value.fields()
                    .forEachRemaining(
                            e ->
                                    ((com.fasterxml.jackson.databind.node.ObjectNode) result)
                                            .set(e.getKey(), resolve(e.getValue(), scope)));
            return result;
        }
        if (value.isArray()) {
            var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            value.forEach(v -> result.add(resolve(v, scope)));
            return result;
        }
        if (!value.isTextual()) return value;
        var m = VARIABLE.matcher(value.asText());
        if (m.matches()) return path(scope, m.group(1));
        String text = value.asText();
        while (m.find()) text = text.replace(m.group(), path(scope, m.group(1)).asText("null"));
        return Json.tree(text);
    }

    public static JsonNode path(JsonNode scope, String path) {
        if (path.equals("$") || path.isEmpty()) return scope;
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        return walk(scope, normalized.split("\\."), 0);
    }

    private static JsonNode walk(JsonNode value, String[] parts, int index) {
        if (index == parts.length)
            return value.isMissingNode()
                    ? com.fasterxml.jackson.databind.node.NullNode.instance
                    : value;
        String part = parts[index];
        if (part.endsWith("[*]")) {
            JsonNode items = value.path(part.substring(0, part.length() - 3));
            if (!items.isArray()) return com.fasterxml.jackson.databind.node.NullNode.instance;
            var mapped = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : items) {
                JsonNode result = walk(item, parts, index + 1);
                if (result.isArray()) result.forEach(mapped::add);
                else mapped.add(result);
            }
            return mapped;
        }
        if (part.contains("[") || part.contains("]"))
            throw new RuntimeFault("PARAM_ERROR", String.join(".", parts), "只支持点路径与[*]");
        return walk(value.path(part), parts, index + 1);
    }

    public static Object expression(String text, JsonNode scope) {
        if (text.length() > 4000
                || text.matches("(?s).*(new |T\\(|@|#|;|(?<![=!<>])=(?!=)|\\.\\w+\\s*\\().*"))
            throw new IllegalArgumentException("EXPRESSION_FORBIDDEN");
        var context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        Matcher m = VARIABLE.matcher(text);
        StringBuffer b = new StringBuffer();
        int i = 0;
        while (m.find()) {
            String name = "v" + i++;
            JsonNode v = path(scope, m.group(1));
            if (v.isNull()
                    && text.matches("(?s).*(==|!=|>=|<=|>|<).*")
                    && !text.contains("isEmpty(")) return false;
            context.setVariable(name, Json.read(v.toString(), Object.class));
            m.appendReplacement(b, Matcher.quoteReplacement("#" + name));
        }
        m.appendTail(b);
        String expression = b.toString();
        Matcher functions = Pattern.compile("([a-zA-Z_][a-zA-Z_0-9]*)\\s*\\(").matcher(expression);
        StringBuffer translated = new StringBuffer();
        while (functions.find()) {
            String name = functions.group(1);
            if (!Set.of("isEmpty", "size", "toNumber", "toString", "contains").contains(name))
                throw new IllegalArgumentException("EXPRESSION_FUNCTION_FORBIDDEN");
            try {
                context.setVariable(
                        name,
                        Values.class.getMethod(
                                name,
                                name.equals("contains")
                                        ? new Class<?>[] {Object.class, Object.class}
                                        : new Class<?>[] {Object.class}));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            functions.appendReplacement(translated, "#" + name + "(");
        }
        functions.appendTail(translated);
        var task =
                new java.util.concurrent.FutureTask<Object>(
                        () ->
                                new SpelExpressionParser()
                                        .parseExpression(translated.toString())
                                        .getValue(context));
        Thread.startVirtualThread(task);
        try {
            return task.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            task.cancel(true);
            throw new IllegalArgumentException("EXPRESSION_INVALID_OR_TIMEOUT", e);
        }
    }

    public static boolean isEmpty(Object v) {
        return v == null
                || (v instanceof Collection<?> c && c.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty())
                || (v instanceof String s && s.isEmpty());
    }

    public static int size(Object v) {
        if (v instanceof Collection<?> c) return c.size();
        if (v instanceof Map<?, ?> m) return m.size();
        if (v instanceof String s) return s.length();
        throw new IllegalArgumentException("SIZE_TYPE");
    }

    public static double toNumber(Object v) {
        return Double.parseDouble(String.valueOf(v));
    }

    public static String toString(Object v) {
        return Objects.toString(v, "");
    }

    public static boolean contains(Object a, Object b) {
        if (a instanceof Collection<?> c) return c.contains(b);
        if (a instanceof String s) return s.contains(String.valueOf(b));
        return false;
    }
}
