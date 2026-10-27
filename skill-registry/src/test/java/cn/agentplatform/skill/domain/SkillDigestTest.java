package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

/**
 * T10：语义摘要只回答一个问题——“这份契约放行的动作变了吗”。
 *
 * <p>这里刻意用可变的 LinkedHashMap 构造契约，覆盖四种典型改写：只改展示文案（description/title）、
 * 换 HTTP 地址（语义变了）、同内容不同键序（不是变化）、嵌套 inputSchema 里的展示文案（不是变化）。
 * 这些用例是发布门禁正确性的底座：digest 只要多算或少算一类字段，就会分别造成“误拦正常发布”或
 * “旧证据给新契约放行”。
 */
class SkillDigestTest {

    @Test
    void displayOnlyEditsKeepDigest() {
        var original = contract();
        var edited = contract();
        edited.put("description", "完全重写的展示文案，不描述任何动作");
        edited.put("title", "另一个标题");

        assertEquals(SkillDigest.of(original), SkillDigest.of(edited));
    }

    @Test
    void semanticEditChangesDigest() {
        var original = contract();
        var edited = contract();
        edited.put("endpoint", "https://example.com/inventory-v2");

        assertNotEquals(SkillDigest.of(original), SkillDigest.of(edited));
    }

    @Test
    void keyOrderDoesNotChangeDigest() {
        var original = contract();
        var reversed = reverse(original);

        assertEquals(SkillDigest.of(original), SkillDigest.of(reversed));
        // 嵌套层同样无关键序：inputSchema 与其 properties 也倒序构造。
        assertEquals(SkillDigest.of(original), SkillDigest.of(reverseDeep(original)));
    }

    @Test
    void nestedDisplayFieldsAreExcluded() {
        var original = contract();
        var edited = contract();
        var schema = new LinkedHashMap<>(asMap(edited.get("inputSchema")));
        var properties = new LinkedHashMap<>(asMap(schema.get("properties")));
        var warehouse = new LinkedHashMap<>(asMap(properties.get("warehouse")));
        warehouse.put("description", "换了字段说明");
        warehouse.put("examples", List.of("C", "D"));
        properties.put("warehouse", warehouse);
        schema.put("properties", properties);
        edited.put("inputSchema", schema);

        assertEquals(SkillDigest.of(original), SkillDigest.of(edited));
    }

    @Test
    void digestIsStableLowercaseSha256AndVersioned() {
        var digest = SkillDigest.of(contract());

        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);
        assertEquals(1, SkillDigest.VERSION);
        // 纯函数：同一输入重复计算结果一致，且与公开的规范化入口一致。
        assertEquals(digest, SkillDigest.of(contract()));
        assertEquals(
                digest,
                cn.agentplatform.common.Digest.sha256(SkillRules.semanticCanonical(contract())));
    }

    @Test
    void arrayOrderIsSemantic() {
        var original = contract();
        var edited = contract();
        edited.put("allowedRegions", List.of("B", "A"));

        original.put("allowedRegions", List.of("A", "B"));

        // 数组表达的是有意义的次序（如候选枚举/参数顺序），必须参与摘要。
        assertNotEquals(SkillDigest.of(original), SkillDigest.of(edited));
    }

    private static Map<String, Object> contract() {
        var contract = new LinkedHashMap<String, Object>();
        contract.put("type", "HTTP");
        contract.put("description", "查询仓库库存");
        contract.put("title", "库存查询");
        contract.put("endpoint", "https://example.com/inventory");
        contract.put("httpMethod", "POST");
        contract.put("authScheme", "NONE");
        contract.put("timeoutMs", 30000);

        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        var properties = new LinkedHashMap<String, Object>();
        var warehouse = new LinkedHashMap<String, Object>();
        warehouse.put("type", "string");
        warehouse.put("description", "仓库编号");
        warehouse.put("examples", List.of("A", "B"));
        properties.put("warehouse", warehouse);
        schema.put("properties", properties);
        contract.put("inputSchema", schema);

        contract.put(
                "execution",
                Map.of(
                        "readonly", true,
                        "sideEffect", false,
                        "idempotent", true,
                        "cancellable", false));
        contract.put("guard", Map.of("attributes", Map.of("warehouse", List.of("A", "B"))));
        return contract;
    }

    private static Map<String, Object> reverse(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        var keys = new ArrayList<>(source.keySet());
        Collections.reverse(keys);
        for (String key : keys) result.put(key, source.get(key));
        return result;
    }

    private static Map<String, Object> reverseDeep(Map<String, Object> source) {
        var result = reverse(source);
        result.put("inputSchema", reverse(asMap(source.get("inputSchema"))));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
