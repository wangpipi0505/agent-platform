package cn.agentplatform.common.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * PLAT-08 Schema 方言归一：把技能与应用两侧互斥的两种写法（单字符串 type / 联合 type 数组，以及
 * nullable 布尔）收敛为同一规范形，供应用预检（本卡）与技能注册（T08b）复用。
 *
 * <p>规范形：单一类型字符串，或 {@code [T,"null"]}（null 恒排在最后）。nullable 只是输入写法，
 * 归一后必然消失。设计上把「改写」与「判定」分开：
 *
 * <ul>
 *   <li>{@link #normalize(JsonNode)} 只改写能无歧义归一的部分，其余原样深拷贝保留；纯函数、幂等、
 *       不修改入参。非法方言（空 type 数组、多具体类型联合、远程引用等）不会被静默改写，从而不会
 *       让调用方丢失定位信息。
 *   <li>{@link #validate(JsonNode)} 是方言的唯一判定源，返回全部错误（而不是抛出首个错误），每条以
 *       JSON Pointer 开头，调用方可直接拼进既有错误消息。
 * </ul>
 *
 * <p>兼容性边界：未知关键字原样保留、不递归、不拒绝；子 Schema 位置只接受对象或布尔 Schema（布尔
 * Schema 是 JSON Schema 2020-12 合法写法，旧应用预检不接受，属于有意放宽）；{@code dependencies}
 * 中的数组是 draft-07 属性依赖而非子 Schema，跳过。非法的子 Schema（字符串/null/数字）报错，旧实现
 * 在 properties/items 下同样拒绝，其它位置此前被忽略。
 */
public final class SchemaDialect {
    /** 与 SchemaWorkBudget 的准入层级保持一致，避免方言检查自身成为递归风险。 */
    public static final int MAX_DEPTH = 64;

    private static final String NULL = "null";
    private static final Set<String> TYPES =
            Set.of("object", "array", "string", "integer", "number", "boolean", NULL);
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

    private SchemaDialect() {}

    /** 归一为规范形；不判定非法组合（交给 {@link #validate(JsonNode)}），非法片段原样保留。 */
    public static JsonNode normalize(JsonNode schema) {
        Objects.requireNonNull(schema, "Schema不能为空");
        return rewrite(schema, 0);
    }

    /** 返回全部方言错误，每条以 JSON Pointer 开头；空列表表示接受。 */
    public static List<String> validate(JsonNode schema) {
        Objects.requireNonNull(schema, "Schema不能为空");
        var errors = new ArrayList<String>();
        check(schema, "", 0, errors);
        return List.copyOf(errors);
    }

    // ---------------------------------------------------------------- normalize

    private static JsonNode rewrite(JsonNode node, int depth) {
        if (!node.isObject()) return node;
        if (depth > MAX_DEPTH) return node.deepCopy();
        var object = (ObjectNode) node;
        var canonical = canonicalType(object.get("type"));
        var nullable = object.get("nullable");
        boolean nullableBoolean = nullable != null && nullable.isBoolean();
        boolean dropNullable = false;
        if (nullableBoolean) {
            if (nullable.booleanValue()) {
                // nullable:true 只有在 type 可归一为规范形时才改写；无 type/非法 type 原样保留给 validate。
                if (canonical.valid()) {
                    if (!canonical.hasNull()) canonical = Canonical.union(canonical.type().textValue());
                    dropNullable = true;
                }
            } else if (!canonical.hasNull()) {
                // nullable:false 是空操作；但若 type 已含 null 则保留矛盾，交给 validate 报错。
                dropNullable = true;
            }
        }
        var out = JsonNodeFactory.instance.objectNode();
        var fields = object.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            switch (field.getKey()) {
                case "type" -> out.set("type", canonical.type());
                case "nullable" -> {
                    if (!dropNullable) out.set("nullable", field.getValue().deepCopy());
                }
                default -> out.set(field.getKey(), rewriteMember(field.getKey(), field.getValue(), depth));
            }
        }
        return out;
    }

    private static JsonNode rewriteMember(String key, JsonNode value, int depth) {
        if (MAPS.contains(key) && value.isObject()) {
            var out = JsonNodeFactory.instance.objectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                // dependencies 的数组值是 draft-07 属性依赖，不是子 Schema。
                if (key.equals("dependencies") && field.getValue().isArray())
                    out.set(field.getKey(), field.getValue().deepCopy());
                else out.set(field.getKey(), rewriteChild(field.getValue(), depth + 1));
            }
            return out;
        }
        if (ARRAYS.contains(key) && value.isArray()) return rewriteArray(value, depth + 1);
        if (SINGLES.contains(key)) {
            if (key.equals("items") && value.isArray()) return rewriteArray(value, depth + 1);
            return rewriteChild(value, depth + 1);
        }
        return value.deepCopy();
    }

    private static JsonNode rewriteArray(JsonNode array, int depth) {
        var out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode child : array) out.add(rewriteChild(child, depth));
        return out;
    }

    private static JsonNode rewriteChild(JsonNode child, int depth) {
        if (!child.isObject() && !child.isBoolean()) return child.deepCopy();
        return rewrite(child, depth);
    }

    /**
     * type 的规范形；valid=false 表示无法无歧义归一（未知类型、空数组、重复项、多具体类型联合等），
     * 此时保留原值并让 validate 给出精确 pointer。
     */
    private record Canonical(JsonNode type, boolean valid, boolean hasNull) {
        static Canonical invalid(JsonNode original) {
            return new Canonical(original, false, false);
        }

        static Canonical single(String type) {
            return new Canonical(JsonNodeFactory.instance.textNode(type), true, NULL.equals(type));
        }

        static Canonical union(String concrete) {
            var array = JsonNodeFactory.instance.arrayNode().add(concrete).add(NULL);
            return new Canonical(array, true, true);
        }
    }

    private static Canonical canonicalType(JsonNode type) {
        if (type == null) return Canonical.invalid(null);
        if (type.isTextual()) {
            return TYPES.contains(type.textValue())
                    ? Canonical.single(type.textValue())
                    : Canonical.invalid(type.deepCopy());
        }
        if (!type.isArray()) return Canonical.invalid(type.deepCopy());
        var names = new ArrayList<String>();
        for (JsonNode entry : type) {
            if (!entry.isTextual() || !TYPES.contains(entry.textValue()))
                return Canonical.invalid(type.deepCopy());
            names.add(entry.textValue());
        }
        if (names.isEmpty() || new HashSet<>(names).size() != names.size())
            return Canonical.invalid(type.deepCopy());
        var concrete = names.stream().filter(name -> !NULL.equals(name)).toList();
        if (concrete.size() > 1) return Canonical.invalid(type.deepCopy());
        if (concrete.isEmpty()) return Canonical.single(NULL);
        if (names.size() == 1) return Canonical.single(concrete.get(0));
        // 规范形固定把 null 放在最后：["null","string"] 与 ["string","null"] 等价。
        return Canonical.union(concrete.get(0));
    }

    // ---------------------------------------------------------------- validate

    private static void check(JsonNode node, String path, int depth, List<String> errors) {
        if (node.isBoolean()) return;
        if (!node.isObject()) {
            errors.add(where(path) + ": Schema必须为对象或布尔值");
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add(where(path) + ": Schema层级超过" + MAX_DEPTH);
            return;
        }
        checkType(node.get("type"), path, errors);
        checkNullable(node, path, errors);
        checkReferences(node, path, errors);
        for (String key : MAPS) {
            var children = node.get(key);
            if (children == null || !children.isObject()) continue;
            var fields = children.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (key.equals("dependencies") && field.getValue().isArray()) continue;
                checkChild(field.getValue(), pointer(pointer(path, key), field.getKey()), depth, errors);
            }
        }
        for (String key : ARRAYS) {
            var children = node.get(key);
            if (children == null || !children.isArray()) continue;
            for (int i = 0; i < children.size(); i++)
                checkChild(children.get(i), pointer(pointer(path, key), Integer.toString(i)), depth, errors);
        }
        for (String key : SINGLES) {
            var children = node.get(key);
            if (children == null) continue;
            if (key.equals("items") && children.isArray()) {
                for (int i = 0; i < children.size(); i++)
                    checkChild(
                            children.get(i),
                            pointer(pointer(path, key), Integer.toString(i)),
                            depth,
                            errors);
            } else checkChild(children, pointer(path, key), depth, errors);
        }
    }

    private static void checkChild(JsonNode child, String path, int depth, List<String> errors) {
        if (!child.isObject() && !child.isBoolean()) {
            errors.add(where(path) + ": 子Schema必须为对象或布尔值");
            return;
        }
        check(child, path, depth + 1, errors);
    }

    private static void checkType(JsonNode type, String path, List<String> errors) {
        if (type == null) return;
        if (type.isTextual()) {
            if (!TYPES.contains(type.textValue()))
                errors.add(
                        pointer(path, "type")
                                + ": 未知type \""
                                + type.textValue()
                                + "\"，仅支持object/array/string/integer/number/boolean/null");
            return;
        }
        if (!type.isArray()) {
            errors.add(pointer(path, "type") + ": type必须为字符串或字符串数组");
            return;
        }
        if (type.isEmpty()) {
            errors.add(pointer(path, "type") + ": type数组不能为空");
            return;
        }
        var names = new ArrayList<String>();
        boolean entriesValid = true;
        for (int i = 0; i < type.size(); i++) {
            var entry = type.get(i);
            if (!entry.isTextual() || !TYPES.contains(entry.textValue())) {
                entriesValid = false;
                errors.add(
                        pointer(pointer(path, "type"), Integer.toString(i))
                                + ": type取值必须是object/array/string/integer/number/boolean/null，实际为"
                                + entry);
            } else names.add(entry.textValue());
        }
        if (!entriesValid) return;
        if (new HashSet<>(names).size() != names.size()) {
            errors.add(pointer(path, "type") + ": type数组不能有重复项，实际为" + type);
            return;
        }
        long concrete = names.stream().filter(name -> !NULL.equals(name)).count();
        if (concrete > 1)
            errors.add(
                    pointer(path, "type")
                            + ": 联合类型只允许单一类型加null（如[\"string\",\"null\"]），实际为"
                            + type);
    }

    private static void checkNullable(JsonNode node, String path, List<String> errors) {
        var nullable = node.get("nullable");
        if (nullable == null) return;
        String at = pointer(path, "nullable");
        if (!nullable.isBoolean()) {
            errors.add(at + ": nullable必须为布尔值");
            return;
        }
        if (node.get("type") == null) {
            if (nullable.booleanValue())
                errors.add(at + ": nullable:true需要同时声明type（等价于 type:[T,\"null\"]）");
            return;
        }
        if (!nullable.booleanValue() && typeContainsNull(node.get("type")))
            errors.add(at + ": nullable=false与type包含\"null\"冲突");
    }

    private static boolean typeContainsNull(JsonNode type) {
        if (type.isTextual()) return NULL.equals(type.textValue());
        if (!type.isArray()) return false;
        for (JsonNode entry : type) if (entry.isTextual() && NULL.equals(entry.textValue())) return true;
        return false;
    }

    private static void checkReferences(JsonNode node, String path, List<String> errors) {
        for (String key : List.of("$ref", "$dynamicRef")) {
            var ref = node.get(key);
            if (ref == null) continue;
            if (!ref.isTextual()) {
                errors.add(pointer(path, key) + ": 引用必须为字符串");
                continue;
            }
            if (!ref.textValue().startsWith("#"))
                errors.add(
                        pointer(path, key)
                                + ": 只允许本地引用（#/...），远程引用已拒绝: "
                                + ref.textValue());
        }
    }

    private static String pointer(String base, String token) {
        return base + "/" + token.replace("~", "~0").replace("/", "~1");
    }

    private static String where(String path) {
        return path.isEmpty() ? "/" : path;
    }
}
