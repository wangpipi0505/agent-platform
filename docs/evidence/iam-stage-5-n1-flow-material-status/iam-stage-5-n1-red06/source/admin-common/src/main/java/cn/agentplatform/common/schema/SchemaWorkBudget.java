package cn.agentplatform.common.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.*;

/**
 * Admission only; networknt remains the validator. No validator is built before this graph passes.
 */
public final class SchemaWorkBudget {
    public static final long MAX_EXPANDED_WORK = 8192;
    private static final List<String> MAPS =
            List.of(
                    "$defs",
                    "definitions",
                    "properties",
                    "patternProperties",
                    "dependentSchemas",
                    "dependencies");
    private static final List<String> ARRAYS = List.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final List<String> SINGLES =
            List.of(
                    "items",
                    "additionalItems",
                    "additionalProperties",
                    "contains",
                    "propertyNames",
                    "not",
                    "if",
                    "then",
                    "else",
                    "unevaluatedItems",
                    "unevaluatedProperties",
                    "contentSchema");
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final Map<String, String> anchors = new HashMap<>();
    private final Set<String> expressions = new LinkedHashSet<>();
    private boolean comparisons, uniqueItems, characterScans, numericOperations;
    private long resourceUnits;

    private SchemaWorkBudget() {}

    public static void check(JsonNode schema) {
        inspect(schema);
    }

    /** Bound raw declarations before copying or converting them to schemas. */
    public static void checkStructure(JsonNode declaration) {
        statistics(declaration, 65536, 1024 * 1024);
    }

    public static Plan inspect(JsonNode schema) {
        return inspect(schema, MAX_EXPANDED_WORK);
    }

    /** Callers may lower admission limits, never raise the shared ceiling. */
    public static Plan inspect(JsonNode schema, long maxExpandedWork) {
        if (maxExpandedWork < 1 || maxExpandedWork > MAX_EXPANDED_WORK)
            throw new IllegalArgumentException("Schema展开限额无效");
        checkStructure(schema);
        var budget = new SchemaWorkBudget();
        budget.collect(schema, "", 0);
        for (var position : budget.positions.values()) {
            for (String key : List.of("$ref", "$dynamicRef")) {
                var ref = position.node.get(key);
                if (ref == null) continue;
                if (!ref.isTextual() || !ref.textValue().startsWith("#")) throw denied("仅支持本地引用");
                String fragment;
                try {
                    fragment = URI.create(ref.textValue()).getFragment();
                } catch (RuntimeException ex) {
                    throw denied("本地引用编码无效");
                }
                String target =
                        fragment.isEmpty() || fragment.startsWith("/")
                                ? fragment
                                : budget.anchors.get(fragment);
                if (target == null || !budget.positions.containsKey(target))
                    throw denied("本地引用没有指向子Schema");
                // Duplicate edges are intentional: definitions expand separately at every use.
                position.children.add(target);
            }
        }
        var costs = new HashMap<String, Long>();
        var heights = new HashMap<String, Integer>();
        long total = 0;
        for (String path : budget.positions.keySet()) {
            total = plus(total, budget.expansion(path, costs, heights, new HashSet<>(), 0));
            if (total > MAX_EXPANDED_WORK) throw denied("引用展开工作量超过8192");
        }
        if (total > maxExpandedWork) throw denied("引用展开工作量超过" + maxExpandedWork);
        return new Plan(
                List.copyOf(budget.positions.keySet()),
                Set.copyOf(budget.expressions),
                total,
                budget.comparisons,
                budget.uniqueItems,
                budget.characterScans,
                budget.numericOperations);
    }

    private void collect(JsonNode node, String path, int depth) {
        SchemaDeadline.check();
        if (!node.isObject() && !node.isBoolean()) return;
        if (depth > 64 || positions.size() >= 4096) throw denied("层级或子Schema数量超过上限");
        if (node.has("$id")) {
            if (!path.isEmpty()) throw denied("暂不支持嵌套$id资源边界");
            try {
                URI id = URI.create(node.path("$id").asText());
                if (!node.path("$id").isTextual() || !id.isAbsolute() || id.getFragment() != null)
                    throw denied("根$id必须为无片段绝对URI");
            } catch (RuntimeException ex) {
                throw denied("根$id无效");
            }
            resourceUnits = (node.path("$id").textValue().length() + 63L) / 64;
        }
        if (node.has("$schema")
                && !Set.of(
                                "https://json-schema.org/draft/2020-12/schema",
                                "http://json-schema.org/draft/2020-12/schema")
                        .contains(node.path("$schema").asText())) throw denied("仅支持2020-12元Schema");
        if (node.has("$recursiveRef")) throw denied("2020-12不支持$recursiveRef");
        for (String key : List.of("$anchor", "$dynamicAnchor"))
            if (node.has(key)) {
                String anchor = node.path(key).asText();
                if (!node.path(key).isTextual()
                        || !anchor.matches("[A-Za-z_][A-Za-z0-9._-]{0,127}"))
                    throw denied("本地anchor无效");
                String previous = anchors.putIfAbsent(anchor, path);
                if (previous != null && !previous.equals(path)) throw denied("本地anchor重复");
            }
        // Paths/resource IRIs also appear in expanded framework contexts and messages.
        long weight = 1L + node.size() + resourceUnits + (path.length() + 63L) / 64;
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!MAPS.contains(field.getKey())
                    && !ARRAYS.contains(field.getKey())
                    && !SINGLES.contains(field.getKey())) {
                var literal = statistics(field.getValue(), 65536, 1024 * 1024);
                weight += literal.nodes + (literal.chars + 63) / 64;
            } else weight += field.getValue().size();
        }
        var position = new Position(node, new ArrayList<>(), weight);
        positions.put(path, position);
        if (node.has("pattern")) {
            if (!node.path("pattern").isTextual()) throw denied("pattern必须为字符串");
            expressions.add(node.path("pattern").textValue());
        }
        if (node.path("patternProperties").isObject())
            node.path("patternProperties").fieldNames().forEachRemaining(expressions::add);
        // format:regex uses a separate JDK compiler, outside the factory extension.
        if (node.path("format").asText().equals("regex"))
            throw denied("暂不支持format:regex，请使用受限pattern");
        comparisons |= node.has("const") || node.has("enum");
        uniqueItems |= node.path("uniqueItems").asBoolean(false);
        characterScans |= node.has("minLength") || node.has("maxLength");
        numericOperations |=
                node.has("multipleOf")
                        || node.has("minimum")
                        || node.has("maximum")
                        || node.has("exclusiveMinimum")
                        || node.has("exclusiveMaximum");
        for (String key : MAPS) {
            var children = node.path(key);
            if (children.isObject())
                children.fields()
                        .forEachRemaining(
                                e ->
                                        child(
                                                position,
                                                e.getValue(),
                                                path + "/" + key + "/" + escape(e.getKey()),
                                                depth));
        }
        for (String key : ARRAYS) {
            var children = node.path(key);
            if (children.isArray())
                for (int i = 0; i < children.size(); i++)
                    child(position, children.get(i), path + "/" + key + "/" + i, depth);
        }
        for (String key : SINGLES) {
            var child = node.path(key);
            if (key.equals("items") && child.isArray())
                for (int i = 0; i < child.size(); i++)
                    child(position, child.get(i), path + "/" + key + "/" + i, depth);
            else child(position, child, path + "/" + key, depth);
        }
        if (expressions.size() > 32) throw denied("正则数量超过32");
    }

    private void child(Position parent, JsonNode node, String path, int depth) {
        if (!node.isObject() && !node.isBoolean()) return;
        parent.children.add(path);
        collect(node, path, depth + 1);
    }

    private long expansion(
            String path,
            Map<String, Long> memo,
            Map<String, Integer> heights,
            Set<String> active,
            int depth) {
        SchemaDeadline.check();
        if (depth > 64 || !active.add(path)) throw denied("暂不支持循环引用或超过64层的引用路径");
        try {
            if (memo.containsKey(path)) {
                if (depth + heights.get(path) > 64) throw denied("引用路径超过64层");
                return memo.get(path);
            }
            var position = positions.get(path);
            long work = position.weight;
            int height = 0;
            for (String child : position.children) {
                work = plus(work, expansion(child, memo, heights, active, depth + 1));
                height = Math.max(height, heights.get(child) + 1);
                if (work > MAX_EXPANDED_WORK) throw denied("引用展开工作量超过8192");
            }
            memo.put(path, work);
            heights.put(path, height);
            return work;
        } finally {
            active.remove(path);
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static long plus(long a, long b) {
        return Math.min(MAX_EXPANDED_WORK + 1, a + b);
    }

    private static IllegalArgumentException denied(String message) {
        return new IllegalArgumentException("Schema预算拒绝：" + message);
    }

    private record Position(JsonNode node, List<String> children, long weight) {}

    public record Plan(
            List<String> locations,
            Set<String> expressions,
            long expandedWork,
            boolean comparisons,
            boolean uniqueItems,
            boolean characterScans,
            boolean numericOperations) {
        public void admitInstance(JsonNode instance, long regexPrograms) {
            admitInstance(instance, regexPrograms, 2_000_000L);
        }

        public void admitInstance(JsonNode instance, long regexPrograms, long maxInstanceWork) {
            if (maxInstanceWork < 1 || maxInstanceWork > 2_000_000L)
                throw new IllegalArgumentException("Schema实例限额无效");
            // Match the existing ArtifactStore receive ceiling. Lowering it to the
            // MCP transport ceiling would break valid offloaded HTTP results.
            var size = statistics(instance, 250000, 64 * 1024 * 1024, 100);
            // Bound all schema branches times instance positions. Large plain strings
            // need no character scanning by regex/comparison and remain admissible.
            if (expandedWork * size.nodes > maxInstanceWork
                    || regexPrograms * expandedWork * (size.chars + size.nodes) > 4_000_000L
                    || (comparisons && expandedWork * (size.chars + size.nodes) > 4_000_000L)
                    || (characterScans && expandedWork * size.chars > 4_000_000L)
                    || (numericOperations
                            && expandedWork * size.nodes * size.maxDigits * size.maxDigits
                                    > 4_000_000L)
                    || (uniqueItems && expandedWork * size.nodes * size.nodes > 2_000_000L))
                throw denied("本次实例的校验工作量超过上限");
        }
    }

    private record Size(long nodes, long chars, long maxDigits) {}

    private record Pending(JsonNode node, int depth) {}

    private static Size statistics(JsonNode root, long maxNodes, long maxChars) {
        return statistics(root, maxNodes, maxChars, 64);
    }

    private static Size statistics(JsonNode root, long maxNodes, long maxChars, int maxDepth) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 0));
        long nodes = 0, chars = 0, maxDigits = 1;
        while (!pending.isEmpty()) {
            SchemaDeadline.check();
            var entry = pending.pop();
            var node = entry.node;
            if (++nodes > maxNodes || entry.depth > maxDepth) throw denied("数据节点或层级超过上限");
            if (node.isTextual()) chars += node.textValue().length();
            else if (node.isNumber()) {
                int digits = node.asText().length();
                if (digits > 1000) throw denied("数值长度超过1000");
                try {
                    if (Math.abs((long) node.decimalValue().scale()) > 1000)
                        throw denied("数值小数位或指数超过1000");
                } catch (NumberFormatException ex) {
                    throw denied("数值必须为有限值");
                }
                chars += digits;
                maxDigits = Math.max(maxDigits, digits);
            }
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getKey().length() > 1024) throw denied("字段名超过1024字符");
                    chars += field.getKey().length();
                    if (nodes + pending.size() >= maxNodes) throw denied("数据节点总量超过上限");
                    pending.push(new Pending(field.getValue(), entry.depth + 1));
                }
            } else if (node.isArray())
                for (var child : node) {
                    if (nodes + pending.size() >= maxNodes) throw denied("数据节点总量超过上限");
                    pending.push(new Pending(child, entry.depth + 1));
                }
            if (chars > maxChars || nodes + pending.size() > maxNodes)
                throw denied("数据字符或节点总量超过上限");
        }
        return new Size(nodes, chars, maxDigits);
    }
}
