# F3-1 任务工作台（管理台：监控/诊断/恢复/对账）——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `2f2370ec` F3-1(web): 任务工作台——任务卡列表/状态管道/任务树/availableActions矩阵/恢复面板（mock驱动）
  - `a9172539` merge: waveL4B-F3-1（合并冲突收口：router双追加+菜单计数14+1=15）
  - 复查命令：`git log --oneline --all --grep="F3-1"`

## 验收口径（卡面摘录）

> TasksPage：任务卡列表（useLiveList 轮询 mock-task）+状态管道 chips+卡内任务树；availableActions 矩阵逐项映射（filter 只放行已知枚举不自判）；对账处置按 pendingCalls.verified 出 callId 级动作；恢复面板 USER 三字段；**A13-1 口径：task-console.test 9 例+navigation-policy 16 例全绿**（菜单 15=14+1）；mock 增补纯追加冻结契约零改动。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # task-console.test.ts + navigation-policy.test.ts
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：task-console.test.ts 与 navigation-policy.test.ts 均在 HEAD 在册（grep 可命中）；resume stateVersion CAS 待确认转 R-19/R-20 承接，非本卡判据。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
