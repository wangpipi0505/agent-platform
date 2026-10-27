package cn.agentplatform.monitor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.agentplatform.monitor.domain.EvaluationAssertions.CompareResult;
import cn.agentplatform.monitor.domain.EvaluationAssertions.InvalidAssertion;
import cn.agentplatform.monitor.domain.EvaluationAssertions.Mode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T01 / PLAT-01 断言模型单元测试。
 *
 * <p>覆盖卡片要求的六类失败路径：旧 EXACT 语义不变、JSON_SUBSET 正反、TEXT_EQUALS/CONTAINS/REGEX/SCHEMA/
 * NUMERIC_TOLERANCE 正反、安全用例保存期拒绝、未知模式保存期拒绝、assertion 缺失按 EXACT 解释。
 *
 * <p>纯函数测试：不起 Spring、不连库，判分只依赖入参，便于在评测服务之外固定语义。
 */
class EvaluationAssertionsTest {

    private static Map<String, Object> obj(Object... kv) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static CompareResult compare(Object assertion, Object expected, Object actual) {
        return EvaluationAssertions.compare(assertion, expected, actual);
    }

    /** 失败路径 1：历史用例没有 assertion 字段，必须继续按 EXACT（整份输出相等）判分。 */
    @Test
    void legacyCaseWithoutAssertionKeepsExactSemantics() {
        var expected = obj("a", 1);
        // CHAT 输出每次带 usage / actualModel，正是路线一 P0 发布死锁的现场；EXACT 下必须仍然失败。
        var chatOutput = obj("a", 1, "usage", obj("total_tokens", 12), "actualModel", "qwen-max");
        var result = compare(null, expected, chatOutput);
        assertFalse(result.pass(), "缺省 EXACT 时 actual 多出 usage/actualModel 必须判失败");
        assertEquals("ASSERTION_EXACT_MISMATCH", result.code());
        assertTrue(result.detail().contains("EXACT"), result.detail());
        assertTrue(result.detail().contains("多出字段"), "差异位置应可读: " + result.detail());
        assertTrue(result.detail().contains("actualModel"), "差异字段应可读: " + result.detail());
        assertTrue(result.detail().contains("usage"), "差异字段应可读: " + result.detail());

        assertTrue(compare(null, obj("a", 1), obj("a", 1)).pass());
        assertEquals(Mode.EXACT, EvaluationAssertions.requireValid(null, expected, false).mode());
        assertEquals("EXACT", EvaluationAssertions.modeOf(null));
        // 值不同同样失败
        assertFalse(compare("EXACT", obj("score", 0.9), obj("score", 0.85)).pass());
        // EXACT 沿用 JsonNode 相等语义：1 与 1.0 不相等（不引入数值宽松比较）
        assertFalse(compare("EXACT", obj("score", 1), obj("score", 1.0)).pass());
        // 非对象整体相等
        assertTrue(compare("EXACT", List.of(1, 2), List.of(1, 2)).pass());
        assertFalse(compare("EXACT", List.of(1, 2), List.of(2, 1)).pass());
    }

    /** 失败路径 2：JSON_SUBSET 只要求 expected 是 actual 的子集，actual 可多字段。 */
    @Test
    void jsonSubsetAllowsExtraActualFieldsButRequiresExpectedOnes() {
        var assertion = obj("mode", "JSON_SUBSET");
        var expected = obj("text", "hello");
        var chatOutput = obj("text", "hello", "usage", obj("total_tokens", 12), "actualModel", "qwen-max");
        assertTrue(compare(assertion, expected, chatOutput).pass());

        var missing = compare(assertion, obj("text", "hello", "usage", obj("total_tokens", 12)), obj("text", "hello"));
        assertFalse(missing.pass(), "expected 声明的字段缺失必须失败");
        assertEquals("ASSERTION_JSON_SUBSET_MISMATCH", missing.code());
        assertTrue(missing.detail().contains("$.usage"), missing.detail());

        assertFalse(compare(assertion, obj("text", "hello"), obj("text", "bye")).pass());
        // 数组按位置逐元素比较，长度必须一致
        assertFalse(compare(assertion, obj("items", List.of(1)), obj("items", List.of(1, 2))).pass());
        // 数值按十进制值比较：1 与 1.0 在子集模式下相等
        assertTrue(compare(assertion, obj("score", 1), obj("score", 1.0)).pass());
        // 类型不一致必须失败
        assertFalse(compare(assertion, obj("a", List.of()), obj("a", "x")).pass());
        // 完全相等也通过
        assertTrue(compare(assertion, expected, obj("text", "hello")).pass());
        // 标量 expected 直接比较
        assertTrue(compare(assertion, "ok", "ok").pass());
        assertFalse(compare(assertion, "ok", "ok!").pass());
    }

    /** 失败路径 3a：TEXT_EQUALS 要求文本完全一致。 */
    @Test
    void textEqualsRequiresIdenticalText() {
        assertTrue(compare("TEXT_EQUALS", "ok", "ok").pass());
        var result = compare("TEXT_EQUALS", "ok", "ok ");
        assertFalse(result.pass());
        assertEquals("ASSERTION_TEXT_MISMATCH", result.code());
        assertTrue(result.detail().contains("TEXT_EQUALS"), result.detail());
        // JSON 输出按紧凑JSON文本比较
        assertTrue(compare("TEXT_EQUALS", "{\"a\":1}", obj("a", 1)).pass());
    }

    /** 失败路径 3b：CONTAINS 子串命中。 */
    @Test
    void containsFindsSubstring() {
        assertTrue(compare("CONTAINS", "已创建", "订单 123 已创建").pass());
        var result = compare("CONTAINS", "已创建", "订单 123 创建失败");
        assertFalse(result.pass());
        assertEquals("ASSERTION_CONTAINS_MISSING", result.code());
        assertTrue(result.detail().contains("CONTAINS"), result.detail());
    }

    /** 失败路径 3c：REGEX 用 RE2J 做整串匹配（非 find），保证线性时间且语义明确。 */
    @Test
    void regexIsRe2FullMatch() {
        assertTrue(compare("REGEX", "^订单\\d+已创建$", "订单123已创建").pass());
        var result = compare("REGEX", "^订单\\d+已创建$", "前缀 订单123已创建");
        assertFalse(result.pass(), "REGEX 是整串匹配，不做子串搜索");
        assertEquals("ASSERTION_REGEX_MISMATCH", result.code());
        assertTrue(result.detail().contains("REGEX"), result.detail());
        // 非贪婪/字符类等 RE2 支持的能力照常可用
        assertTrue(compare("REGEX", "^[a-z]{3}-\\d{2}$", "abc-12").pass());
    }

    /** 失败路径 3d：SCHEMA 用 expected 作为 JSON Schema 校验 actual。 */
    @Test
    void schemaValidatesActualAgainstExpectedSchema() {
        var schema = obj("type", "object", "required", List.of("text"),
                "properties", obj("text", obj("type", "string")));
        var assertion = obj("mode", "SCHEMA");
        assertTrue(compare(assertion, schema, obj("text", "hi", "usage", obj("total_tokens", 3))).pass());

        var result = compare(assertion, schema, obj("text", 1));
        assertFalse(result.pass());
        assertEquals("ASSERTION_SCHEMA_MISMATCH", result.code());
        assertTrue(result.detail().contains("SCHEMA"), result.detail());
        assertTrue(result.detail().contains("$"), "应带出错位置: " + result.detail());

        // required 缺失同样失败
        assertFalse(compare(assertion, schema, obj("other", "x")).pass());
    }

    /** 失败路径 3e：NUMERIC_TOLERANCE 按十进制差值判断，避免二进制浮点误差。 */
    @Test
    void numericToleranceComparesDecimalDifference() {
        var assertion = obj("mode", "NUMERIC_TOLERANCE", "tolerance", 0.02);
        assertTrue(compare(assertion, 0.9, 0.915).pass());
        var result = compare(assertion, 0.9, 0.95);
        assertFalse(result.pass());
        assertEquals("ASSERTION_NUMERIC_TOLERANCE_EXCEEDED", result.code());
        assertTrue(result.detail().contains("0.05"), result.detail());
        assertTrue(result.detail().contains("NUMERIC_TOLERANCE"), result.detail());

        var typeMismatch = compare(assertion, 0.9, "0.9");
        assertFalse(typeMismatch.pass());
        assertEquals("ASSERTION_NUMERIC_TYPE_MISMATCH", typeMismatch.code());
        // 整数值节点与带小数点的数值节点都算数值
        assertTrue(compare(obj("mode", "NUMERIC_TOLERANCE", "tolerance", 0), 1, 1.0).pass());
    }

    /** 失败路径 4：安全类用例只允许 EXACT / SCHEMA，含容差与包含/正则一律保存期拒绝。 */
    @Test
    void safetyCasesOnlyAllowExactOrSchema() {
        for (String mode : List.of("CONTAINS", "REGEX", "JSON_SUBSET", "TEXT_EQUALS", "NUMERIC_TOLERANCE")) {
            var e = assertThrows(InvalidAssertion.class,
                    () -> EvaluationAssertions.requireValid(mode, "x", true), "安全用例应拒绝 " + mode);
            assertEquals("EVALUATION_ASSERTION_SAFETY_CONSTRAINT", e.code());
            assertTrue(e.userMessage().contains("安全"), e.userMessage());
            assertTrue(e.userMessage().contains("EXACT"), e.userMessage());
            assertTrue(e.userMessage().contains("SCHEMA"), e.userMessage());
        }
        assertEquals(Mode.EXACT, EvaluationAssertions.requireValid("EXACT", obj("ok", true), true).mode());
        assertEquals(Mode.SCHEMA, EvaluationAssertions.requireValid("SCHEMA", obj("type", "object"), true).mode());
        var mapForm = assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", "REGEX"), "^ok$", true));
        assertEquals("EVALUATION_ASSERTION_SAFETY_CONSTRAINT", mapForm.code());
    }

    /** 失败路径 5：未知模式保存期拒绝，判分期 fail-closed，绝不静默降级为 EXACT。 */
    @Test
    void unknownModeIsRejectedNeverDowngraded() {
        var e = assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("FUZZY_MATCH", "x", false));
        assertEquals("EVALUATION_ASSERTION_UNKNOWN", e.code());
        assertTrue(e.userMessage().contains("FUZZY_MATCH"), e.userMessage());
        assertTrue(e.userMessage().contains("JSON_SUBSET"), "可读原因应列出允许模式: " + e.userMessage());

        var judged = compare(obj("mode", "FUZZY_MATCH"), "x", "x");
        assertFalse(judged.pass(), "未知模式判分必须 fail-closed");
        assertEquals("EVALUATION_ASSERTION_UNKNOWN", judged.code());

        var nonText = assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", 3), "x", false));
        assertEquals("EVALUATION_ASSERTION_INVALID", nonText.code());
        var wrongType = assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(42, "x", false));
        assertEquals("EVALUATION_ASSERTION_INVALID", wrongType.code());
        // 空白字符串不能被当成缺省 EXACT
        assertEquals("EVALUATION_ASSERTION_UNKNOWN", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("   ", "x", false)).code());
    }

    /** 失败路径 6：assertion 缺失（历史数据集）按 EXACT 解释；显式 "EXACT" 与之等价。 */
    @Test
    void missingAssertionIsExactlyEqualSemantics() {
        var expected = obj("a", 1);
        var actual = obj("a", 1, "usage", obj("total_tokens", 1));
        assertEquals(compare(null, expected, actual).code(), compare("EXACT", expected, actual).code());
        assertFalse(compare("EXACT", expected, actual).pass());
        assertEquals(Mode.EXACT, EvaluationAssertions.requireValid(null, expected, false).mode());
        assertTrue(EvaluationAssertions.requireValid(null, null, false).tolerance() == 0);
    }

    /** 参数非法（缺 tolerance、负容差、未知参数、非法正则/超长正则、空包含、非对象schema）保存期拒绝。 */
    @Test
    void invalidParametersAreRejectedAtSaveTime() {
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("NUMERIC_TOLERANCE", 0.9, false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", "NUMERIC_TOLERANCE", "tolerance", -0.1), 0.9, false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", "EXACT", "tolerance", 0.1), "x", false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", "NUMERIC_TOLERANCE", "tolerance", 0.1), "0.9", false)).code());
        // RE2 不支持 lookahead，保存期就必须拒绝，而不是运行期抛异常
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("REGEX", "(?=a)a", false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("REGEX", "a".repeat(2001), false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("CONTAINS", "", false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid("SCHEMA", "not-a-schema", false)).code());
        assertEquals("EVALUATION_ASSERTION_INVALID", assertThrows(InvalidAssertion.class,
                () -> EvaluationAssertions.requireValid(obj("mode", "SCHEMA"), List.of(1), false)).code());
    }

    /** 规范化配置：报告中记录稳定形状，便于回滚与审计。 */
    @Test
    void specCanonicalKeepsReportStable() {
        assertEquals("EXACT", EvaluationAssertions.requireValid(null, null, false).canonical());
        assertEquals("JSON_SUBSET", EvaluationAssertions.requireValid(" json_subset ", obj("a", 1), false).canonical());
        assertEquals(obj("mode", "NUMERIC_TOLERANCE", "tolerance", 0.5),
                EvaluationAssertions.requireValid(obj("mode", "numeric_tolerance", "tolerance", 0.5), 0.9, false).canonical());
        assertEquals("SCHEMA", EvaluationAssertions.modeOf(obj("mode", "schema")));
    }

    /** 断言失败原因有界：550KB 级输出只给模式名 + 差异位置摘要，不贴整份输出。 */
    @Test
    void failureDetailIsBoundedAndMentionsModeAndLocation() {
        var huge = "x".repeat(500_000);
        var text = compare("TEXT_EQUALS", "y", huge);
        assertFalse(text.pass());
        assertTrue(text.detail().length() <= 400, "detail 长度 " + text.detail().length());

        var bigJson = obj("text", huge, "usage", obj("total_tokens", 12));
        var exact = compare(null, obj("text", "y"), bigJson);
        assertFalse(exact.pass());
        assertTrue(exact.detail().length() <= 400, "detail 长度 " + exact.detail().length());
        assertTrue(exact.detail().contains("$.text"), exact.detail());

        var longPattern = compare("REGEX", "^" + "a".repeat(1996) + "$", huge);
        assertFalse(longPattern.pass());
        assertTrue(longPattern.detail().length() <= 400, "detail 长度 " + longPattern.detail().length());
    }
}
