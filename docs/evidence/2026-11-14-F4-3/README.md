# F4-3 三类消息卡片——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`88a7da23` F4-1/F4-2/F4-3(webapp) 同笔交付（`accf14af` merge waveL4C 合入）
- 复查命令：`git log --oneline --all --grep="F4-3"`

## 验收口径（卡面摘录）

> ToolCallCard 折叠卡（名称/参数摘要/状态+aria-expanded）；PlanCard 确认状态徽章+按钮不自判（canConfirm/canExecute 直通 planButtons 纯函数矩阵，CONFIRMED_EXPIRED→重新确认，PLAN_NOT_CONFIRMED→错误卡引导）；TaskCard 状态/模式/步骤树+REISSUE；confirm→execute 受理→执行任务卡闭环（demoCreateExecTask 同源事件）；webapp-assistant.test confirmState 按钮矩阵+闭环 26 例全绿。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # webapp-assistant.test.ts
grep -n "planButtons\|CONFIRMED_EXPIRED\|PLAN_NOT_CONFIRMED" web/tests/webapp-assistant.test.ts web/admin/webapp/src/**/*.* 2>/dev/null | head -5 || grep -rln "planButtons" web | head -3
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：webapp-assistant.test.ts 在 HEAD 在册；计划 API 三错误码（PLAN_NOT_CONFIRMED/PLAN_REVISION_CONFLICT/REQUEST_REPLAY）服务端判据 PlansApiTest 已于本补档实跑 6/6 绿（T2-3 交付）——两侧判据齐备；历史修订切换待确认转 R-20 裁决。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
