package cn.agentplatform.app.domain;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;

import java.util.*;

/**
 * 应用候选的语义摘要。
 *
 * <p>为什么不复用 sha256：`candidateSha256` 是候选的**快照身份**，描述文案改一个标点它就变，用来做发布
 * 快照与证据绑定是对的。但评测证据的复用条件是"结构与行为没变"，而描述不改变应用的执行语义。语义摘要
 * 就是这个更窄的身份：去掉展示性字段后，对剩余内容做规范化 JSON 的 SHA-256。
 *
 * <p>不变量（改动前先读）：
 *
 * <ul>
 *   <li>**只按键名排除**，不做路径白名单：任何层级的 description/displayName/title/notes/examples/
 *       docLinks/documentation/changelog 都不计入；其余键（包括 id、version、ref、sha）一律计入。
 *       键名比较忽略大小写。
 *   <li>规范化用 TreeMap 逐层排序键，因此同一内容的不同 map 迭代顺序得到同一摘要；数组顺序保留
 *       （数组顺序在节点/用例语义里是有意义的）。
 *   <li>入参不被修改：递归重建新 map/list，避免与 `sha(candidate)` 这类依赖原对象的调用互相影响。
 *   <li>输出是 64 位小写十六进制，长度与 sha256 相同，便于同列存放与比较。
 * </ul>
 *
 * <p>已知边界：语义摘要只覆盖应用自身声明的结构。它不检查被引用技能版本的语义摘要，因此"技能内部只改了
 * 描述"仍会让应用需要重新评测——这属于技能侧 rebind 的职责（PLAT-10），不要在这里放宽。
 */
public final class AppDigest {
    /** 展示性字段：变化不影响执行语义，因此不计入语义摘要。 */
    private static final Set<String> DISPLAY_KEYS =
            Set.of(
                    "description",
                    "displayname",
                    "title",
                    "notes",
                    "example",
                    "examples",
                    "doclinks",
                    "documentation",
                    "changelog");

    private AppDigest() {}

    /** 应用候选（`AppService.candidate(...)` 形态）的语义摘要。 */
    public static String semantic(Map<String, Object> candidate) {
        return Digest.sha256(Json.write(canonical(candidate)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new TreeMap<String, Object>();
            map.forEach(
                    (k, v) -> {
                        String key = String.valueOf(k);
                        if (!DISPLAY_KEYS.contains(key.toLowerCase(Locale.ROOT)))
                            out.put(key, canonical(v));
                    });
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (Object item : list) out.add(canonical(item));
            return out;
        }
        return value;
    }
}
