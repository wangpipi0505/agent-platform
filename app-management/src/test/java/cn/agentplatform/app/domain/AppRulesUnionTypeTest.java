package cn.agentplatform.app.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行期类型判值必须与预检（SchemaDialect）同判：预检放行 type:[T,"null"] 与 nullable:true 后，
 * 运行期不能再用"单字符串 type"去比较——否则 schema 能发布、执行时才炸（PLAT-08 的回归面）。
 */
class AppRulesUnionTypeTest {

    private static Map<String, Object> schema(Object type) {
        var schema = new HashMap<String, Object>();
        if (type != null) schema.put("type", type);
        return schema;
    }

    @Test
    @DisplayName("联合类型 [string,null] 接受字符串")
    void unionAcceptsString() {
        assertThatCode(() -> AppRules.validateValue(schema(List.of("string", "null")), "ok", "p", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("联合类型 [string,null] 接受 null")
    void unionAcceptsNull() {
        assertThatCode(() -> AppRules.validateValue(schema(List.of("string", "null")), null, "p", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("联合类型 [string,null] 拒绝整数，并在消息里列出全部声明类型")
    void unionRejectsWrongType() {
        assertThatThrownBy(() -> AppRules.validateValue(schema(List.of("string", "null")), 1, "p", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要string|null");
    }

    @Test
    @DisplayName("nullable:true 且无 type 时接受 null（预检接受的最简写法）")
    void nullableFlagAcceptsNull() {
        var schema = schema(null);
        schema.put("nullable", true);
        assertThatCode(() -> AppRules.validateValue(schema, null, "p", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单元素数组 type 归一后等价于字符串 type")
    void singleElementArray() {
        assertThatCode(() -> AppRules.validateValue(schema(List.of("string")), "ok", "p", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未声明 type 时保持旧语义：非 null 值一律失败")
    void missingTypeStillRejects() {
        assertThatThrownBy(() -> AppRules.validateValue(schema(null), "ok", "p", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("纯字符串 type 的旧行为不变：null 仍被拒绝")
    void plainStringRejectsNull() {
        assertThatThrownBy(() -> AppRules.validateValue(schema("string"), null, "p", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许null");
    }

    @Test
    @DisplayName("嵌套属性 errorMessage 声明 [string,null] 时可传 null（路线一状态响应的真实形状）")
    void nestedUnionAllowsNull() {
        var errorMessage = schema(List.of("string", "null"));
        var properties = new HashMap<String, Object>();
        properties.put("errorMessage", errorMessage);
        var root = new HashMap<String, Object>();
        root.put("type", "object");
        root.put("properties", properties);

        var value = new HashMap<String, Object>();
        value.put("errorMessage", null);

        assertThatCode(() -> AppRules.validateValue(root, value, "p", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("数组元素声明联合类型时逐元素判值")
    void unionInsideItems() {
        var item = schema(List.of("string", "null"));
        var root = new HashMap<String, Object>();
        root.put("type", "array");
        root.put("items", item);

        assertThatCode(() -> AppRules.validateValue(root, List.of("a", "b"), "p", false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AppRules.validateValue(root, List.of(1), "p", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("enum 约束在联合类型下仍然生效")
    void enumStillEnforced() {
        var schema = schema(List.of("string", "null"));
        schema.put("enum", List.of("A", "B"));
        assertThatThrownBy(() -> AppRules.validateValue(schema, "C", "p", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在枚举范围");
    }
}
