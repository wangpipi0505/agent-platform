# F2-2 技能库单页闭环——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 3 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `2529e4ff` F2-2(web): 技能库闭环——kind筛选+单页分区+分型发布checklist（服务端预检驱动mock）
  - `73ff1117` merge: waveL4A-F2-2（任务 c≤8 步，16 断言+零新增失败）
  - 复查命令：`git log --oneline --all --grep="F2-2"`

## 验收口径（卡面摘录）

> 列表 kind chips 筛选；详情单页分区（TOOL/GUIDE 分型渲染）；发布 checklist 卡（TOOL 四项含副作用意图字段声明 fieldAnchor 定位/GUIDE 两项；状态由服务端预检驱动 demo:true mock，页面不自算；全绿激活发布钮）；**任务 c 步数=8≤8**；skill-precheck.test 16 断言+vitest 521 passed 13 失败=§9.4 基线一致零新增。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # skill-precheck.test.ts 在内
grep -c "" web/tests/skill-precheck.test.ts   # 文件在册核查
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：skill-precheck.test.ts 在 HEAD 在册；GUIDE demo 种子与引用统计列两项待确认转 R-20 裁决，非本卡判据。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
