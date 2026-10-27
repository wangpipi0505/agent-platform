package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Json;

import org.junit.jupiter.api.Test;

/**
 * 渲染必须逐字节稳定（参与模型请求哈希）：同一包两次渲染一致，长包按段落顺序截断到预算内。
 */
class ContextPackTextTest {

    @Test
    void rendersFixedSectionsAndTruncatesOversizePackWithinBudget() {
        var pack = Json.object();
        var card = pack.putArray("conceptCards").addObject();
        card.put("name", "学生").put("description", "在籍学生");
        var properties = card.putArray("properties");
        properties.addObject().put("name", "学号");
        properties.addObject().put("name", "姓名");
        pack.putArray("termDictionary").addObject().put("term", "学号").put("definition", "唯一标识");
        pack.putArray("relationSummaries").addObject().put("name", "选修").put("description", "学生选修课程");
        pack.putArray("exampleQa").addObject().put("question", "如何创建学生？").put("answer", "调用建卡接口");

        String text = ContextPackText.render(pack);
        assertTrue(text.startsWith("【概念卡】"));
        int concept = text.indexOf("【概念卡】");
        int term = text.indexOf("【术语表】");
        int relation = text.indexOf("【关系摘要】");
        int example = text.indexOf("【示例问答】");
        assertTrue(concept >= 0 && concept < term && term < relation && relation < example);
        assertTrue(text.contains("- 学生：在籍学生；属性：学号、姓名"));
        assertTrue(text.contains("- 学号：唯一标识"));
        assertTrue(text.contains("- 选修：学生选修课程"));
        assertTrue(text.contains("- 问：如何创建学生？ 答：调用建卡接口"));
        // 同一输入两次渲染逐字节一致；经序列化/反序列化后亦然（不得依赖迭代顺序或时间）。
        assertEquals(text, ContextPackText.render(pack));
        assertEquals(text, ContextPackText.render(Json.read(Json.write(pack))));

        assertEquals("", ContextPackText.render(Json.object()));
        var empty = ContextPackText.renderDetailed(Json.object());
        assertEquals("", empty.text());
        assertEquals(0, empty.items());
        assertFalse(empty.truncated());

        var oversize = Json.object();
        var cards = oversize.putArray("conceptCards");
        for (int i = 0; i < 200; i++)
            cards.addObject()
                    .put("name", "概念卡-" + i)
                    .put("description", "用于验证截断行为的长描述，编号 " + i + "，重复填充以超过八千字符预算。");
        // 其余三段也有条目：预算按段落顺序消费，截断计数包含全部段落。
        oversize.putArray("termDictionary").addObject().put("term", "术语").put("definition", "定义");
        oversize.putArray("relationSummaries").addObject().put("name", "关系");
        oversize.putArray("exampleQa").addObject().put("question", "问").put("answer", "答");

        var rendered = ContextPackText.renderDetailed(oversize);
        assertTrue(rendered.text().length() <= ContextPackText.MAX_CHARS);
        assertTrue(rendered.truncated());
        assertTrue(rendered.text().contains("已截断"));
        assertTrue(rendered.text().matches("(?s).*…（共 \\d+ 项，已截断）$"));
        assertTrue(rendered.text().endsWith("…（共 203 项，已截断）"));
        assertEquals(203, rendered.items());
        assertFalse(rendered.text().contains("概念卡-199"));
        assertTrue(rendered.text().startsWith("【概念卡】"));
        assertEquals(ContextPackText.render(oversize), rendered.text());
    }
}
