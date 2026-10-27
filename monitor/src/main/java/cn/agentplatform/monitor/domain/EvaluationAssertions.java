package cn.agentplatform.monitor.domain;

import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 评测断言模型（T01 / PLAT-01）。
 *
 * <p>背景：路线一发布门禁把 {@code Json.tree(expected).equals(Json.tree(output))} 当唯一判据。CHAT 输出天然带
 * 每次变化的 {@code usage}/{@code actualModel}，整份相等永远不成立，数据集冻结后的结论无法转绿，形成发布死锁。
 *
 * <p>设计不变量：
 * <ul>
 *   <li>纯函数：无 IO、无 Spring、无时间/随机依赖；同一入参永远同一结论，报告才可复算。</li>
 *   <li>向后兼容：用例没有 {@code assertion} 字段时按 {@link Mode#EXACT} 解释，旧数据集/旧冻结报告语义不变。</li>
 *   <li>fail-closed：未知模式、非法参数、配置与用例不符，一律拒绝或判失败，绝不静默降级为通过。</li>
 *   <li>安全用例（{@code safety=true}）只允许 EXACT / SCHEMA：包含、正则、子集、容差都可能把危险输出判成通过。</li>
 *   <li>失败原因有界：detail 只给模式名 + 差异位置摘要，绝不回贴整份输出（CHAT 输出可达数百 KB）。</li>
 * </ul>
 *
 * <p>防灾难回溯：正则走仓库既有的 RE2J（线性时间、无回溯、不支持 lookaround/backreference），并在保存期编译校验；
 * 文本类断言对 actual 文本长度设上限，超限直接判失败而不是截断后“恰好通过”。
 */
public final class EvaluationAssertions {

    /** 用例JSON中承载断言配置的字段名；缺失等价于 {@link Mode#EXACT}。 */
    public static final String FIELD = "assertion";

    /** 报告上的判分模型标识：旧语义，整份输出精确相等。 */
    public static final String MODEL_EXACT = "EXACT";

    /** 报告上的判分模型标识：按用例 assertion 配置判分。 */
    public static final String MODEL_CONFIGURABLE = "CONFIGURABLE";

    public static final Mode DEFAULT = Mode.EXACT;

    /** 失败原因上限：够说明模式与差异位置，又不把输出抄进 results。 */
    static final int MAX_DETAIL = 320;

    /** 正则模式长度上限：RE2 是线性时间，仍限制编译期内存放大。 */
    static final int MAX_PATTERN = 2000;

    /** 参与文本判分的输出上限；超限判失败，不截断后比较。 */
    static final int MAX_TEXT = 1_000_000;

    static final String CODE_UNKNOWN = "EVALUATION_ASSERTION_UNKNOWN";
    static final String CODE_INVALID = "EVALUATION_ASSERTION_INVALID";
    static final String CODE_SAFETY = "EVALUATION_ASSERTION_SAFETY_CONSTRAINT";
    static final String CODE_MATCH = "MATCH";
    static final String CODE_EXACT = "ASSERTION_EXACT_MISMATCH";
    static final String CODE_SUBSET = "ASSERTION_JSON_SUBSET_MISMATCH";
    static final String CODE_TEXT = "ASSERTION_TEXT_MISMATCH";
    static final String CODE_CONTAINS = "ASSERTION_CONTAINS_MISSING";
    static final String CODE_REGEX = "ASSERTION_REGEX_MISMATCH";
    static final String CODE_SCHEMA = "ASSERTION_SCHEMA_MISMATCH";
    static final String CODE_NUMERIC = "ASSERTION_NUMERIC_TOLERANCE_EXCEEDED";
    static final String CODE_NUMERIC_TYPE = "ASSERTION_NUMERIC_TYPE_MISMATCH";
    static final String CODE_TOO_LARGE = "ASSERTION_OUTPUT_TOO_LARGE";

    private static final JsonSchemaFactory SCHEMAS =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private EvaluationAssertions() {}

    public enum Mode {
        EXACT,
        JSON_SUBSET,
        TEXT_EQUALS,
        CONTAINS,
        REGEX,
        SCHEMA,
        NUMERIC_TOLERANCE
    }

    /** 判分结果；code 为稳定标识，detail 为有界可读原因（绝不含整份输出）。 */
    public record CompareResult(boolean pass, String code, String detail) {}

    /** 规范化后的断言配置：容差模式带 tolerance，其余模式只用 mode。 */
    public record Spec(Mode mode, double tolerance) {
        /** 写进报告快照的稳定形状：字符串模式名，或 {mode, tolerance}。 */
        public Object canonical() {
            if (mode == Mode.NUMERIC_TOLERANCE) {
                var canonical = new LinkedHashMap<String, Object>();
                canonical.put("mode", mode.name());
                canonical.put("tolerance", tolerance);
                return canonical;
            }
            return mode.name();
        }
    }

    /** 保存/发布期断言配置错误：code 稳定、userMessage 面向用户可读。 */
    public static final class InvalidAssertion extends RuntimeException {
        private final String code;
        private final String userMessage;

        public InvalidAssertion(String code, String userMessage) {
            super(userMessage);
            this.code = code;
            this.userMessage = userMessage;
        }

        public String code() {
            return code;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    /**
     * 保存/发布期校验：用例即将被冻结进数据集（快照哈希），必须在写库前拒绝不可执行的断言配置。
     *
     * @param assertion 用例 assertion 字段，null/缺失表示历史用例
     * @param expected  用例 expected，REGEX/SCHEMA/CONTAINS/NUMERIC_TOLERANCE 需要它做参数校验
     * @param safety    安全类用例；true 时只允许 EXACT / SCHEMA
     */
    public static Spec requireValid(Object assertion, Object expected, boolean safety) {
        Mode mode = parseMode(assertion);
        if (safety && mode != Mode.EXACT && mode != Mode.SCHEMA) {
            throw new InvalidAssertion(CODE_SAFETY,
                    "安全用例只允许 EXACT 或 SCHEMA 断言，当前为 " + mode.name()
                            + "；包含/正则/子集/容差匹配可能把危险输出判为通过，已拒绝保存");
        }
        return validate(assertion, mode, expected);
    }

    /**
     * 判分。配置非法或与 expected 不符时返回 fail-closed 的失败结果（不是抛异常、更不是降级通过），
     * 保证历史报告重算与运行期异常不会把错误数据写成“通过”。
     */
    public static CompareResult compare(Object assertion, Object expected, Object actual) {
        Spec spec;
        try {
            Mode mode = parseMode(assertion);
            spec = validate(assertion, mode, expected);
        } catch (InvalidAssertion e) {
            return new CompareResult(false, e.code(), clip(e.userMessage()));
        }
        JsonNode exp = Json.tree(expected);
        JsonNode act = Json.tree(actual);
        return switch (spec.mode()) {
            case EXACT -> exact(exp, act);
            case JSON_SUBSET -> subset(exp, act);
            case TEXT_EQUALS -> textEquals(exp, act);
            case CONTAINS -> contains(exp, act);
            case REGEX -> regex(exp, act);
            case SCHEMA -> schema(exp, act);
            case NUMERIC_TOLERANCE -> numeric(exp, act, spec);
        };
    }

    /** 仅供报告记录：配置缺省返回 EXACT，非法配置返回 INVALID（不抛异常，避免污染结果写入）。 */
    public static String modeOf(Object assertion) {
        try {
            return parseMode(assertion).name();
        } catch (InvalidAssertion e) {
            return "INVALID";
        }
    }

    // ------------------------------------------------------------------ 配置解析与校验

    private static Mode parseMode(Object assertion) {
        if (assertion == null) {
            return DEFAULT;
        }
        if (assertion instanceof CharSequence text) {
            return modeOfName(text.toString());
        }
        if (assertion instanceof Map<?, ?> map) {
            Object rawMode = map.get("mode");
            if (!(rawMode instanceof CharSequence text)) {
                throw new InvalidAssertion(CODE_INVALID,
                        "断言配置缺少字符串字段 mode；可用模式：" + modeNames());
            }
            return modeOfName(text.toString());
        }
        throw new InvalidAssertion(CODE_INVALID,
                "断言配置只能是模式名字符串或 {mode, tolerance} 对象，当前为 "
                        + assertion.getClass().getSimpleName());
    }

    private static Mode modeOfName(String raw) {
        String text = raw == null ? "" : raw.trim();
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(text)) {
                return mode;
            }
        }
        throw new InvalidAssertion(CODE_UNKNOWN,
                "未知评测断言模式：\"" + clip(text) + "\"；可用模式：" + modeNames());
    }

    private static Spec validate(Object assertion, Mode mode, Object expected) {
        double tolerance = 0;
        if (mode == Mode.NUMERIC_TOLERANCE) {
            tolerance = toleranceOf(assertion);
        }
        rejectExtraKeys(assertion, mode == Mode.NUMERIC_TOLERANCE);
        JsonNode exp = Json.tree(expected);
        switch (mode) {
            case REGEX -> {
                String pattern = requireText(expected, "REGEX 的 expected 必须是模式字符串");
                if (pattern.isBlank()) {
                    throw invalid("REGEX 的 expected 不能为空模式（空模式会匹配任意输出）");
                }
                if (pattern.length() > MAX_PATTERN) {
                    throw invalid("REGEX 模式长度 " + pattern.length() + " 超过上限 " + MAX_PATTERN);
                }
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException e) {
                    throw invalid("REGEX 模式无法编译：" + e.getDescription() + "；RE2 不支持 lookaround/反向引用");
                }
            }
            case SCHEMA -> {
                if (!exp.isObject()) {
                    throw invalid("SCHEMA 的 expected 必须是JSON Schema对象（当前为 " + typeName(exp) + "）");
                }
                try {
                    SCHEMAS.getSchema(exp);
                } catch (RuntimeException e) {
                    throw invalid("SCHEMA 不是合法的JSON Schema：" + e.getMessage());
                }
            }
            case CONTAINS -> {
                String text = requireText(expected, "CONTAINS 的 expected 必须是文本片段");
                if (text.isBlank()) {
                    throw invalid("CONTAINS 的 expected 不能为空文本（空文本会匹配任意输出）");
                }
            }
            case NUMERIC_TOLERANCE -> {
                if (!exp.isNumber()) {
                    throw invalid("NUMERIC_TOLERANCE 的 expected 必须是数值，当前为 " + typeName(exp));
                }
            }
            case EXACT, JSON_SUBSET, TEXT_EQUALS -> {
                // 无需额外参数；TEXT_EQUALS 允许对象/数组按紧凑JSON文本比较
            }
        }
        return new Spec(mode, tolerance);
    }

    private static double toleranceOf(Object assertion) {
        if (!(assertion instanceof Map<?, ?> map)) {
            throw invalid("NUMERIC_TOLERANCE 需要 {mode, tolerance} 对象配置（tolerance 缺失）");
        }
        Object raw = map.get("tolerance");
        if (!(raw instanceof Number number)) {
            throw invalid("NUMERIC_TOLERANCE 的 tolerance 必须是数值");
        }
        double tolerance = number.doubleValue();
        if (!Double.isFinite(tolerance) || tolerance < 0) {
            throw invalid("NUMERIC_TOLERANCE 的 tolerance 必须是非负有限数值，当前为 " + raw);
        }
        return tolerance;
    }

    private static void rejectExtraKeys(Object assertion, boolean numeric) {
        if (!(assertion instanceof Map<?, ?> map)) {
            return;
        }
        Set<String> allowed = numeric ? Set.of("mode", "tolerance") : Set.of("mode");
        List<String> extra = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                extra.add(String.valueOf(key));
            }
        }
        if (!extra.isEmpty()) {
            throw invalid("断言配置含不支持的参数：" + String.join("、", extra) + "；允许参数：" + String.join("、", allowed));
        }
    }

    private static String requireText(Object expected, String message) {
        JsonNode node = Json.tree(expected);
        if (!node.isTextual()) {
            throw invalid(message + "（当前为 " + typeName(node) + "）");
        }
        return node.textValue();
    }

    private static String modeNames() {
        List<String> names = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            names.add(mode.name());
        }
        return String.join("、", names);
    }

    private static InvalidAssertion invalid(String userMessage) {
        return new InvalidAssertion(CODE_INVALID, userMessage);
    }

    // ------------------------------------------------------------------ 各模式判分

    private static CompareResult exact(JsonNode expected, JsonNode actual) {
        if (expected.equals(actual)) {
            return new CompareResult(true, CODE_MATCH, "");
        }
        return new CompareResult(false, CODE_EXACT, clip("EXACT 不匹配：" + difference(expected, actual, "$", 0)));
    }

    private static CompareResult subset(JsonNode expected, JsonNode actual) {
        String difference = subsetDifference(expected, actual, "$", 0);
        if (difference == null) {
            return new CompareResult(true, CODE_MATCH, "");
        }
        return new CompareResult(false, CODE_SUBSET, clip("JSON_SUBSET 不匹配：" + difference));
    }

    private static CompareResult textEquals(JsonNode expected, JsonNode actual) {
        String exp = textOf(expected);
        String act = textOf(actual);
        if (exp == null || act == null) {
            return tooLarge();
        }
        if (exp.equals(act)) {
            return new CompareResult(true, CODE_MATCH, "");
        }
        return new CompareResult(false, CODE_TEXT,
                clip("TEXT_EQUALS 不匹配：expected=\"" + excerpt(exp, 60) + "\" actual=\"" + excerpt(act, 60) + "\""));
    }

    private static CompareResult contains(JsonNode expected, JsonNode actual) {
        String text = textOf(expected);
        String act = textOf(actual);
        if (text == null || act == null) {
            return tooLarge();
        }
        if (act.contains(text)) {
            return new CompareResult(true, CODE_MATCH, "");
        }
        return new CompareResult(false, CODE_CONTAINS,
                clip("CONTAINS 未命中：actual 文本（长度 " + act.length() + "）不含 expected 片段 \""
                        + excerpt(text, 60) + "\""));
    }

    private static CompareResult regex(JsonNode expected, JsonNode actual) {
        String pattern = textOf(expected);
        String act = textOf(actual);
        if (pattern == null || act == null) {
            return tooLarge();
        }
        try {
            if (Pattern.compile(pattern).matcher(act).matches()) {
                return new CompareResult(true, CODE_MATCH, "");
            }
            return new CompareResult(false, CODE_REGEX,
                    clip("REGEX 不匹配：actual 文本（长度 " + act.length() + "）不匹配模式 \"" + excerpt(pattern, 80) + "\""));
        } catch (PatternSyntaxException e) {
            return new CompareResult(false, CODE_INVALID,
                    clip("REGEX 模式无法编译：" + e.getDescription() + "；模式 \"" + excerpt(pattern, 80) + "\""));
        }
    }

    private static CompareResult schema(JsonNode expected, JsonNode actual) {
        try {
            JsonSchema schema = SCHEMAS.getSchema(expected);
            Set<ValidationMessage> errors = schema.validate(actual);
            if (errors.isEmpty()) {
                return new CompareResult(true, CODE_MATCH, "");
            }
            ValidationMessage first = errors.iterator().next();
            return new CompareResult(false, CODE_SCHEMA,
                    clip("SCHEMA 校验失败（" + errors.size() + " 处）：" + schemaMessage(first)));
        } catch (RuntimeException e) {
            return new CompareResult(false, CODE_INVALID,
                    clip("SCHEMA 无法编译：" + e.getMessage()));
        }
    }

    private static CompareResult numeric(JsonNode expected, JsonNode actual, Spec spec) {
        if (!expected.isNumber() || !actual.isNumber()) {
            return new CompareResult(false, CODE_NUMERIC_TYPE,
                    clip("NUMERIC_TOLERANCE 要求 expected 与 actual 均为数值：expected="
                            + typeName(expected) + " actual=" + typeName(actual)));
        }
        BigDecimal exp = expected.decimalValue();
        BigDecimal act = actual.decimalValue();
        BigDecimal tolerance = BigDecimal.valueOf(spec.tolerance());
        BigDecimal gap = exp.subtract(act).abs();
        if (gap.compareTo(tolerance) <= 0) {
            return new CompareResult(true, CODE_MATCH, "");
        }
        return new CompareResult(false, CODE_NUMERIC,
                clip("NUMERIC_TOLERANCE 超出容差：expected=" + plain(exp) + " actual=" + plain(act)
                        + " 差值=" + plain(gap) + " 容差=" + plain(tolerance)));
    }

    // ------------------------------------------------------------------ 差异摘要

    /** EXACT 差异摘要：返回第一个差异位置（对象字段按字典序，保证同一输入同一原因）。 */
    private static String difference(JsonNode expected, JsonNode actual, String path, int depth) {
        if (expected.equals(actual)) {
            return null;
        }
        if (depth > 16) {
            return path + "：嵌套过深，仅报告到该层";
        }
        if (expected.isObject() && actual.isObject()) {
            for (String name : sorted(expected.fieldNames())) {
                JsonNode child = actual.get(name);
                if (child == null) {
                    return path + "." + name + " 字段缺失（expected 要求该字段）";
                }
                String nested = difference(expected.get(name), child, path + "." + name, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            List<String> extra = new ArrayList<>();
            for (String name : sorted(actual.fieldNames())) {
                if (!expected.has(name)) {
                    extra.add(name);
                }
            }
            if (!extra.isEmpty()) {
                return path + "：actual 多出字段 " + join(extra, 3) + "（EXACT 要求整份输出相等；CHAT 的 usage/actualModel 即属此类）";
            }
            return path + "：对象内容不同";
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                return path + "：数组长度 expected=" + expected.size() + " actual=" + actual.size();
            }
            for (int i = 0; i < expected.size(); i++) {
                String nested = difference(expected.get(i), actual.get(i), path + "[" + i + "]", depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return path + "：数组内容不同";
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + "：类型不同 expected=" + typeName(expected) + " actual=" + typeName(actual);
        }
        return path + "：值不同 expected=" + value(expected) + " actual=" + value(actual);
    }

    /** JSON_SUBSET 差异摘要：返回 null 表示 expected 是 actual 子集；actual 多出字段合法。 */
    private static String subsetDifference(JsonNode expected, JsonNode actual, String path, int depth) {
        if (depth > 16) {
            return path + "：嵌套过深，仅报告到该层";
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                return path + "：类型不同 expected=object actual=" + typeName(actual);
            }
            for (String name : sorted(expected.fieldNames())) {
                JsonNode child = actual.get(name);
                if (child == null) {
                    return path + "." + name + " 字段缺失（expected 要求该字段存在）";
                }
                String nested = subsetDifference(expected.get(name), child, path + "." + name, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (expected.isArray()) {
            if (!actual.isArray()) {
                return path + "：类型不同 expected=array actual=" + typeName(actual);
            }
            if (expected.size() != actual.size()) {
                return path + "：数组长度 expected=" + expected.size() + " actual=" + actual.size();
            }
            for (int i = 0; i < expected.size(); i++) {
                String nested = subsetDifference(expected.get(i), actual.get(i), path + "[" + i + "]", depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                return path + "：数值不同 expected=" + plain(expected.decimalValue()) + " actual=" + plain(actual.decimalValue());
            }
            return null;
        }
        if (expected.equals(actual)) {
            return null;
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + "：类型不同 expected=" + typeName(expected) + " actual=" + typeName(actual);
        }
        return path + "：值不同 expected=" + value(expected) + " actual=" + value(actual);
    }

    /** 节点转文本：文本原样，其它按紧凑JSON；返回 null 表示超过 {@link #MAX_TEXT}，调用方判失败。 */
    private static String textOf(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        String text = node.isTextual() ? node.textValue() : Json.write(node);
        return text.length() > MAX_TEXT ? null : text;
    }

    private static CompareResult tooLarge() {
        return new CompareResult(false, CODE_TOO_LARGE,
                "文本类断言拒绝判分：actual 文本超过 " + MAX_TEXT + " 字符上限（不截断后比较，避免误判通过）");
    }

    private static String schemaMessage(ValidationMessage message) {
        String text = message.getMessage();
        String location = String.valueOf(message.getInstanceLocation());
        if (text == null || text.isBlank()) {
            text = message.getCode();
        }
        if (text.startsWith("$")) {
            return text;
        }
        return location + "：" + text;
    }

    private static List<String> sorted(java.util.Iterator<String> names) {
        var sorted = new TreeSet<String>();
        names.forEachRemaining(sorted::add);
        return new ArrayList<>(sorted);
    }

    private static String join(List<String> items, int limit) {
        if (items.size() <= limit) {
            return String.join("、", items);
        }
        return String.join("、", items.subList(0, limit)) + " 等" + items.size() + "个";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String value(JsonNode node) {
        String text = node.isTextual() ? "\"" + node.textValue() + "\"" : Json.write(node);
        return excerpt(text, 60);
    }

    private static String typeName(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "missing";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String clip(String text) {
        return excerpt(text, MAX_DETAIL);
    }
}
