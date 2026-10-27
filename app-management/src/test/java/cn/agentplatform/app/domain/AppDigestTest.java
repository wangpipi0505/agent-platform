package cn.agentplatform.app.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import cn.agentplatform.app.application.AppService;
import org.junit.jupiter.api.Test;

/**
 * 应用候选的语义摘要：只忽略展示性字段，结构性字段一个都不能少。
 *
 * <p>用途：只有 description/displayName 等字段变化时，评测证据可以按语义摘要复用；一旦 config、
 * definition、tags、类型变化，摘要必须变——否则旧证据会被冒充成新候选已通过评测。
 *
 * <p>不变量：
 *
 * <ul>
 *   <li>{@code AppDigest.semantic} 不修改入参，也不依赖 map 迭代顺序（走 canonical JSON）；
 *   <li>排除键只在对象层生效，且是"键名"匹配，不按路径白名单；
 *   <li>与被替换的 sha256 同长（64 位小写十六进制），便于存 varchar(64) 与比较。
 * </ul>
 */
class AppDigestTest {

    private static Map<String, Object> candidate(String description, Map<String, Object> config) {
        var m = new LinkedHashMap<String, Object>();
        m.put("appCode", "space-visibility-agent");
        m.put("type", "CHAT");
        m.put("name", "可见性规划");
        m.put("description", description);
        m.put("domain", "space");
        m.put("bizCategory", "ops");
        m.put("tags", List.of("route1"));
        m.put("config", config);
        m.put("definition", Map.of("nodes", List.of()));
        return m;
    }

    @Test
    void descriptionChangeKeepsSemanticDigestButChangesSha() {
        var a = candidate("旧描述", Map.of("prompt", "p1"));
        var b = candidate("新描述（只改了展示文案）", Map.of("prompt", "p1"));
        assertEquals(AppDigest.semantic(a), AppDigest.semantic(b));
        assertNotEquals(AppService.sha(a), AppService.sha(b), "sha 必须覆盖描述，用于快照身份");
    }

    @Test
    void configChangeChangesSemanticDigest() {
        var a = candidate("同描述", Map.of("prompt", "p1"));
        var b = candidate("同描述", Map.of("prompt", "p2"));
        assertNotEquals(AppDigest.semantic(a), AppDigest.semantic(b));
    }

    @Test
    void nestedDescriptionIsIgnoredButNestedStructuralKeysAreNot() {
        var a = candidate("d", Map.of("nodes", List.of(Map.of("id", "n1", "description", "旧"))));
        var b = candidate("d", Map.of("nodes", List.of(Map.of("id", "n1", "description", "新"))));
        assertEquals(AppDigest.semantic(a), AppDigest.semantic(b));

        var c = candidate("d", Map.of("nodes", List.of(Map.of("id", "n2", "description", "旧"))));
        assertNotEquals(AppDigest.semantic(a), AppDigest.semantic(c), "节点 id 是结构，必须计入");
    }

    @Test
    void displayNameAndTitleAreIgnored() {
        var a = candidate("d", Map.of("displayName", "A", "title", "T1"));
        var b = candidate("d", Map.of("displayName", "B", "title", "T2"));
        assertEquals(AppDigest.semantic(a), AppDigest.semantic(b));
    }

    @Test
    void digestIsLowercaseSha256HexAndInputIsUntouched() {
        var a = candidate("d", Map.of("prompt", "p"));
        var before = deepCopy(a);
        String digest = AppDigest.semantic(a);
        assertTrue(digest.matches("[0-9a-f]{64}"), digest);
        assertEquals(before, a);
    }

    @Test
    void keyOrderDoesNotMatter() {
        var a = new LinkedHashMap<String, Object>();
        a.put("appCode", "x");
        a.put("config", Map.of("b", 1, "a", 2));
        var b = new LinkedHashMap<String, Object>();
        b.put("config", Map.of("a", 2, "b", 1));
        b.put("appCode", "x");
        assertEquals(AppDigest.semantic(a), AppDigest.semantic(b));
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object v) {
        if (v instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            m.forEach((k, x) -> out.put(String.valueOf(k), deepCopy(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            var out = new ArrayList<Object>();
            l.forEach(x -> out.add(deepCopy(x)));
            return out;
        }
        return v;
    }
}
