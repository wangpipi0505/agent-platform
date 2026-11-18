# F2-3 运行中心实时+诊断优先——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `9d728d0d` F2-3(web): 运行中心——useLiveList轮询/失败行内联摘要/详情诊断优先+输入独立
  - `4ad1fb22` merge: waveL4A-F2-3（9 例新增全绿，run-detail-navigation 基线 4 例未恶化）
  - 复查命令：`git log --oneline --all --grep="F2-3"`

## 验收口径（卡面摘录）

> useLiveList 泛型轮询 hook（15s 默认/票据守卫/onUnmounted 清理）+RunList 接入状态实时变色；失败行内联错误摘要列（reasonCode+首行错误）；详情 5 Tab 重组诊断优先+节点检查器输入/输出独立分块；vitest 514/527（基线 505/518），run-detail-navigation 基线 4 例未恶化（失败原因逐字相同）；新增 9 例全绿。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # run-detail-navigation.test.ts 等
grep -n "useLiveList" web/admin/src/composables/*.ts web/admin/src/**/RunList* 2>/dev/null | head -3 || grep -rln "useLiveList" web/admin/src | head -3
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：run-detail-navigation.test.ts 在 HEAD 在册（§9.4 基线 4 例失败=R-13 批次待清零项，非本卡判据）；useLiveList 被 F3-1 TasksPage 复用（演进链在案）。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
