package cn.agentplatform.engine.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Runtime subset gate: objects/required/properties/arrays/type/enum. Unsupported schema composition
 * is not claimed.
 */
public final class JsonGate {
    public static void validate(JsonNode schema, JsonNode value, String path, String semantic) {
        String type = schema.path("type").asText().toLowerCase(Locale.ROOT);
        boolean valid =
                switch (type) {
                    case "object" -> value.isObject();
                    case "array" -> value.isArray();
                    case "string" -> value.isTextual();
                    case "number" -> value.isNumber();
                    case "integer" -> value.isIntegralNumber();
                    case "boolean" -> value.isBoolean();
                    case "null" -> value.isNull();
                    case "" -> true;
                    default -> throw new RuntimeFault(semantic, path, "不支持的类型声明 " + type);
                };
        if (!valid)
            throw new RuntimeFault(semantic, path, "期望 " + type + "，实际 " + value.getNodeType());
        if (schema.path("enum").isArray()) {
            boolean found = false;
            for (JsonNode option : schema.path("enum")) if (option.equals(value)) found = true;
            if (!found) throw new RuntimeFault(semantic, path, "不在允许的枚举范围内");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required"))
                if (!value.hasNonNull(required.asText()))
                    throw new RuntimeFault(semantic, path + "." + required.asText(), "必填值缺失");
            schema.path("properties")
                    .fields()
                    .forEachRemaining(
                            e -> {
                                if (value.has(e.getKey()))
                                    validate(
                                            e.getValue(),
                                            value.path(e.getKey()),
                                            path + "." + e.getKey(),
                                            semantic);
                            });
        }
        if (value.isArray() && schema.has("items"))
            for (int i = 0; i < value.size(); i++)
                validate(schema.path("items"), value.get(i), path + "[" + i + "]", semantic);
    }
}
