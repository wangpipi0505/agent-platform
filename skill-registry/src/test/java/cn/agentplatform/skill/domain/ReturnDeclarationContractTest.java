package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** R01附录I/R04：保存/导入边界的返回声明方言校验（别名窗口、冲突拒绝、卡片容器）。 */
class ReturnDeclarationContractTest {
    private static PlatformException invalid(Object returnSpec) {
        return assertThrows(PlatformException.class, () -> SkillRules.validateReturnSpec(returnSpec));
    }

    @Test
    void 缺省与目标方言通过() {
        SkillRules.validateReturnSpec(null);
        SkillRules.validateReturnSpec(Map.of());
        SkillRules.validateReturnSpec(
                Map.of("modelSummary", Map.of("mode", "TEMPLATE", "tpl", "文档《{{title}}》"),
                        "structuredPath", "$.content",
                        "cardTemplate", Map.of("title", "{{title}}")));
        // PATH模式与JSON字符串卡片（解析为容器）同样合法。
        SkillRules.validateReturnSpec(
                Map.of("modelSummary", Map.of("mode", "PATH", "path", "$.name"),
                        "cardTemplate", "{\"title\":\"{{title}}\"}"));
    }

    @Test
    void 旧template别名按同语法接受() {
        SkillRules.validateReturnSpec(Map.of("modelSummary", Map.of("mode", "TEMPLATE", "template", "值{{value}}")));
        // 双字段内容相同：接受。
        SkillRules.validateReturnSpec(
                Map.of("modelSummary", Map.of("mode", "TEMPLATE", "tpl", "同值", "template", "同值")));
    }

    @Test
    void 双字段冲突必须拒绝并定位到modelSummary() {
        var ex = invalid(Map.of(
                "modelSummary", Map.of("mode", "TEMPLATE", "tpl", "A{{v}}", "template", "B{{v}}")));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().contains("returnSpec.modelSummary"));
        assertTrue(ex.getMessage().contains("冲突"));
    }

    @Test
    void 缺字段空值坏类型分别拒绝() {
        assertTrue(invalid(Map.of("modelSummary", Map.of("mode", "TEMPLATE"))).getMessage()
                .contains("必须提供tpl"));
        assertTrue(invalid(Map.of("modelSummary", Map.of("mode", "TEMPLATE", "tpl", "  "))).getMessage()
                .contains("tpl必须为非空字符串"));
        assertTrue(invalid(Map.of("modelSummary", Map.of("mode", "TEMPLATE", "tpl", 36))).getMessage()
                .contains("必须为非空字符串"));
        assertTrue(invalid(Map.of("modelSummary", "raw")).getMessage().contains("必须为对象"));
        assertTrue(invalid(Map.of("modelSummary", Map.of("mode", "NONE", "tpl", "x"))).getMessage()
                .contains("只能是PATH或TEMPLATE"));
        assertTrue(invalid(Map.of("modelSummary", Map.of("mode", "PATH"))).getMessage()
                .contains("非空字符串path"));
    }

    @Test
    void 文本卡片与不可解析卡片拒绝且定位到cardTemplate() {
        var text = invalid(Map.of("cardTemplate", "查询结果"));
        assertEquals(400, text.status());
        assertTrue(text.getMessage().contains("returnSpec.cardTemplate"));
        assertTrue(invalid(Map.of("cardTemplate", "{not-json")).getMessage().contains("cardTemplate"));
        assertTrue(invalid(Map.of("cardTemplate", 36)).getMessage().contains("cardTemplate"));
        // JSON对象/数组容器（字符串或结构）均通过。
        SkillRules.validateReturnSpec(Map.of("cardTemplate", List.of(Map.of("t", "{{t}}"))));
        SkillRules.validateReturnSpec(Map.of("cardTemplate", "[{\"t\":\"{{t}}\"}]"));
    }
}
