package cn.agentplatform.skill.domain;

import cn.agentplatform.common.Digest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 技能契约语义摘要（PLAT-03/10）。
 *
 * <p>sha256 覆盖整份契约字节，改一个展示文案也会失效；但发布门禁真正关心的是“放行的动作是否
 * 变化”。本类把契约里纯展示的键整棵剔除（description/examples/title 等），递归按键名排序后
 * 再算 SHA-256，得到与书写顺序、文案无关的 semanticDigest。
 *
 * <p>纯函数：只依赖 Jackson 与 {@link Digest}，不读数据库、不注入 Spring、不感知租户与版本。
 * 规则本身有版本号 {@link #VERSION}；改变排除集或序列化方式必须递增，并保证历史证据仍可用旧
 * 规则解释（升级需要迁移脚本重算或保留旧列，不在本函数内做兼容分支）。
 */
public final class SkillDigest {
    /** 摘要规则版本；随响应字段 digestVersion 返回，不写入 contract。 */
    public static final int VERSION = 1;

    /**
     * 排除集只按“键名”匹配、整棵剔除：同一个键在顶层是文案，在 schema 属性里也是给人和模型看的
     * 说明，不是动作契约。刻意不排除 endpoint/httpMethod/execution/guard/timeoutMs 等字段——它们
     * 任何变化都必须要求重测。
     */
    private static final Pattern DISPLAY_FIELD =
            Pattern.compile(
                    "(?i)^(description|examples?|doclinks?|documentation|changelog|displayname|title)$");

    /** 普通 ObjectMapper：序列化按 ObjectNode 的插入顺序输出，排序由 normalize 完成。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillDigest() {}

    public static String of(Map<String, Object> contract) {
        return Digest.sha256(canonical(contract));
    }

    static String canonical(Object contract) {
        return write(normalize(toTree(contract)));
    }

    private static JsonNode toTree(Object contract) {
        if (contract == null) return MAPPER.createObjectNode();
        if (contract instanceof JsonNode node) return node;
        return MAPPER.valueToTree(contract);
    }

    /**
     * 递归规范化：对象按键名排序并剔除展示字段，数组保持原序（候选枚举、参数顺序本身是语义），
     * 标量原样。排序用自然序，跨 JVM/数据库/前端都稳定，不依赖 JSON 库的哈希顺序。
     */
    private static JsonNode normalize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode result = MAPPER.createObjectNode();
            for (String name : names) {
                if (DISPLAY_FIELD.matcher(name).matches()) continue;
                result.set(name, normalize(object.get(name)));
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode item : array) result.add(normalize(item));
            return result;
        }
        return node;
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("契约无法序列化为语义摘要", ex);
        }
    }
}
