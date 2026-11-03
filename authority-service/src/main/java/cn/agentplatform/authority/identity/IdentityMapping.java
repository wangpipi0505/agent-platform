package cn.agentplatform.authority.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身份源字段的有限映射器。
 *
 * <p>身份源档案（{@link IdentityProperties}）由平台管理员填写，因此这里只接受两种东西：
 * 固定的目标字段名与"点分属性路径"（最多三层，允许一层 {@code []} 列表展开）。
 * 不解析表达式、不调用脚本、不做模糊匹配——表达不了的字段就应当新增一个协议适配器，
 * 而不是在配置里塞逻辑。
 *
 * <p><b>为什么需要映射</b>：不同业务系统/身份服务的字段名不同（{@code loginName}/{@code account.login}、
 * {@code authorities[].code}/{@code roles[]}），平台不应该按业务软件名称写分支；把差异收敛到
 * 一张有限映射表，换系统只改配置，验证语义（状态、角色、租户上限）保持同一套。
 */
public final class IdentityMapping {

    /** 允许映射的目标字段；集合之外的目标在配置期拒绝，避免"映射了一个没人读的字段"。 */
    public static final Set<String> TARGETS =
            Set.of(
                    "userName",
                    "status",
                    "roles",
                    "roleKey",
                    "roleDataScope",
                    "deptId",
                    "deptName",
                    "tenant");

    /** 默认映射与原 user-mgmt 适配器的字段读取逐字一致，保证既有部署不改配置即可升级。 */
    private static final Map<String, String> DEFAULTS =
            Map.of(
                    "userName", "userName",
                    "status", "status",
                    "roles", "roles",
                    "roleKey", "roleKey",
                    "roleDataScope", "dataScope",
                    "deptId", "deptId",
                    "deptName", "deptName");

    /**
     * 目标 → 源路径。每段是 {@code [A-Za-z][A-Za-z0-9_]{0,31}}，允许在任意段后加 {@code []}
     * 展开一层列表；最多三段。禁止 {@code $}、{@code *}、括号、函数调用等表达式字符。
     */
    private static final java.util.regex.Pattern PATH =
            java.util.regex.Pattern.compile(
                    "^[A-Za-z][A-Za-z0-9_]{0,31}(\\[\\])?(\\.[A-Za-z][A-Za-z0-9_]{0,31}(\\[\\])?){0,2}$");

    private IdentityMapping() {}

    /**
     * 校验并补默认值。启用人身份会话时 {@code userName} 必须有映射；禁用时允许空映射（既有部署）。
     *
     * @throws IllegalStateException 目标字段未知、路径形状非法或启用时缺少 userName
     */
    public static Map<String, String> normalize(boolean enabled, Map<String, String> configured) {
        Map<String, String> normalized = new LinkedHashMap<>(DEFAULTS);
        if (configured != null) {
            for (Map.Entry<String, String> entry : configured.entrySet()) {
                // Spring 的松散绑定把 `mapping.user-name` 原样交给 Map；这里统一成 camelCase，
                // 让 YAML 的 kebab-case 与 Java 的字段名指向同一个目标，而不是要求运维记住大小写。
                String target = canonicalTarget(entry.getKey());
                String path = entry.getValue();
                if (!TARGETS.contains(target)) {
                    throw new IllegalStateException(
                            "AUTHORITY_IDENTITY_INVALID: 未知的映射目标 " + target + "（允许 " + TARGETS + "）");
                }
                if (path == null || !PATH.matcher(path).matches()) {
                    throw new IllegalStateException(
                            "AUTHORITY_IDENTITY_INVALID: 映射 "
                                    + target
                                    + " 的路径必须是点分属性路径（≤3 段，可含一层 []），不支持表达式");
                }
                normalized.put(target, path);
            }
        }
        if (enabled && !normalized.containsKey("userName")) {
            throw new IllegalStateException("AUTHORITY_IDENTITY_INVALID: 启用人身份会话时必须映射 userName");
        }
        return Map.copyOf(normalized);
    }

    private static String canonicalTarget(String key) {
        if (key == null || key.indexOf('-') < 0) return key;
        StringBuilder out = new StringBuilder(key.length());
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    /** 按点分路径取值；路径不存在或类型不符返回 {@code null}，由调用方决定拒绝语义。 */
    public static Object resolve(Object source, String path) {
        if (source == null || path == null) return null;
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            boolean list = segment.endsWith("[]");
            String name = list ? segment.substring(0, segment.length() - 2) : segment;
            current = step(current, name, list);
        }
        return current;
    }

    /** 取标量文本；非字符串（数字/布尔）不接受，避免宽松相等把停用状态读成可用。 */
    public static String text(Object source, String path) {
        return resolve(source, path) instanceof String value ? value : null;
    }

    private static Object step(Object node, String name, boolean list) {
        if (node instanceof List<?> items) {
            List<Object> mapped = new ArrayList<>();
            for (Object item : items) {
                Object value = step(item, name, list);
                if (value != null) mapped.add(value);
            }
            return mapped;
        }
        if (node instanceof Map<?, ?> map) {
            Object value = map.get(name);
            if (!list) return value;
            if (value instanceof List<?> items) return new ArrayList<>(items);
            return null;
        }
        return null;
    }
}
