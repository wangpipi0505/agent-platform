# F3-2 计划详情（管理台只读）——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `f86d9cef` F3-2(web): 计划详情只读页——步骤/依赖DAG分层/绑定/修订对比（管理台运维视角）
  - `11c6d67b` merge: waveL4B-F3-2（8/8 绿+零新增失败）
  - 复查命令：`git log --oneline --all --grep="F3-2"`

## 验收口径（卡面摘录）

> PlanDetailPage 只读：步骤表（五 kind tag）/依赖 DAG 分层（纯 CSS 无图库）/输入绑定拍平表/修订对比（双选择器+digest/确认状态/步骤差异）；零 el-button 零确认执行文案；确认状态只读徽章三态；**plan-detail.test 8/8 绿**+vitest 全量 556 用例 13 失败=§9.4 基线一致零新增。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # plan-detail.test.ts
grep -n "只读\|canConfirm\|canExecute" web/admin/src/pages/PlanDetailPage.vue | head -5
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：plan-detail.test.ts 与 PlanDetailPage.vue 在 HEAD 在册；零确认执行文案与只读徽章三态在测试断言内锁定（交付时点 8/8 全绿口径在案）。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
