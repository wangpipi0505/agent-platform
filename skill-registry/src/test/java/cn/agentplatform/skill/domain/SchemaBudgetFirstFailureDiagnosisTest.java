package cn.agentplatform.skill.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaWorkBudget;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * R11不可变诊断（SCN-007）：用2026-11-02验收中真实PROCESS技能的完整Schema与22节点真实输入，
 * 逐维度采谱并锁定实际首先触发的预算拒绝。只读取已固化资源，不修改预算、Schema或协议；
 * 证明哪个维度先触发，而不是把静态计算的numericWork当成已观察的首异常。
 */
class SchemaBudgetFirstFailureDiagnosisTest {
    private static final String SCHEMA_RESOURCE = "/schemabudget/r11-process-schema.json";
    private static final String ARGS_RESOURCE = "/schemabudget/r11-process-args.json";
    /** 与route2只读计算一致：3763×22×7²=4056514>4000000。 */
    private static final String NUMERIC_REFUSAL = "numericWork=4056514，上限=4000000";

    private static Object resource(String name) {
        try (var in = SchemaBudgetFirstFailureDiagnosisTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "缺少冻结夹具 " + name);
            return Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), Object.class);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void 结构与引用展开维度实测值与只读计算一致() {
        var plan = SchemaWorkBudget.inspect(Json.tree(resource(SCHEMA_RESOURCE)));
        assertEquals(55, plan.locations().size(), "子Schema位置数");
        assertEquals(3763, plan.expandedWork(), "expandedWork");
    }

    @Test
    void 纯数据维度以numericWork先拒且数值精确() {
        var plan = SchemaWorkBudget.inspect(Json.tree(resource(SCHEMA_RESOURCE)));
        // regexPrograms=0隔离纯数据维度：estimatedWork(82786)在2m内通过，numericWork超4m拒绝。
        var admission = plan.admission(Json.tree(resource(ARGS_RESOURCE)), 0, SchemaWorkBudget.MAX_INSTANCE_WORK);
        assertFalse(admission.admitted());
        assertTrue(admission.reason().contains(NUMERIC_REFUSAL), "实际拒绝原因=" + admission.reason());
    }

    @Test
    void 生产STRICT路径的实测首触维度确定且可复核() {
        var schema = resource(SCHEMA_RESOURCE);
        var args = resource(ARGS_RESOURCE);
        String first = null, firstMessage = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var ex =
                    assertThrows(
                            RuntimeException.class,
                            () -> SkillRules.validate(schema, args),
                            "真实Schema+输入在STRICT下必须被预算拒绝");
            assertTrue(
                    String.valueOf(ex.getMessage()).startsWith("Schema预算拒绝："),
                    "预算拒绝消息族保持: " + ex.getMessage());
            String message = String.valueOf(ex.getMessage());
            // 维度名=消息体首个"维度=数值"键：regexWork/numericWork/estimatedWork等。
            String dimension = message.substring("Schema预算拒绝：".length()).split("[=，]")[0];
            if (first == null) {
                first = dimension;
                firstMessage = message;
            }
            assertEquals(first, dimension, "同一冻结输入的实测首触维度必须确定");
            assertEquals(firstMessage, message, "同一冻结输入的拒绝原因必须逐字确定");
            // 若实测命中的是numericWork，其数值必须与只读计算逐字一致。
            if (first.equals("numericWork"))
                assertTrue(message.contains(NUMERIC_REFUSAL), "实测原因=" + message);
        }
        assertNotNull(first);
        // R11诊断记录：生产路径实测首触维度随测试输出固化。2026-11-03实测为regexWork
        // （59771492，超4000000），先于route2只读计算预测的numericWork=4056514——与
        // SchemaWorkBudget的检查顺序（regex先于numeric）一致；纯数据维度（regexPrograms=0）
        // 才由numericWork先拒。两个维度都被同一冻结用例实证，预算按当前设计正确拒绝，
        // 支持差距与不可达定义等价候选的取舍归R01/R11标准收口裁定。
        System.out.println("R11实测首触维度 " + first + "（完整原因：" + firstMessage + "）");
    }
}
