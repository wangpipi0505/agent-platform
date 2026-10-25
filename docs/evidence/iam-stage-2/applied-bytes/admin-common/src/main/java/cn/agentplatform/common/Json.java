package cn.agentplatform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** JSON边界统一：摘要用canonical稳定排序，展示JSON不承担授权控制。 */
public final class Json {
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectMapper CANONICAL =
            MAPPER.copy()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private Json() {}

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON序列化失败", e);
        }
    }

    public static JsonNode read(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new PlatformException(400, "INVALID_JSON", "数据不是有效JSON");
        }
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new PlatformException(400, "INVALID_JSON", "JSON结构与要求不符");
        }
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(normalize(tree(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("摘要材料序列化失败", e);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k -> sorted.set(k, normalize(node.get(k))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(x -> result.add(normalize(x)));
            return result;
        }
        return node;
    }
}
