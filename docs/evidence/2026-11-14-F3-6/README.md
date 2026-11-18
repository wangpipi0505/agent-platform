# F3-6 会话详情与步骤调用下钻——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `0c637cea` F3-6(web): 会话详情与步骤调用下钻——双视角投影/压缩标记/锚点定位/恢复事实
  - `3a713646` merge: waveL4D-F3-6（7/7 绿+零新增失败）
  - 复查命令：`git log --oneline --all --grep="F3-6"`

## 验收口径（卡面摘录）

> SessionDetailPage 双视角：USER=白名单投影（诊断四类剔除+恢复恰三字段）/OPERATOR=诊断视图整包+调用链 reconciliation；锚点定位（eventSeq 齐→jump 高亮/三空→no-session/压缩区间→shadowed）；**session-drill.test 7/7 绿**（压缩无正文×2+锚点×2+恢复三出口一致+刷新重查+直链拒绝审计）+vitest 全量 572 例 13 失败=§9.4 基线一致零新增。

## 复现命令

```bash
cd web && npm ci && npx vitest run      # session-drill.test.ts
grep -n "SESSION_DIAGNOSTIC_VIEW_FORBIDDEN\|shadowed" web/tests/session-drill.test.ts web/admin/src/pages/SessionDetailPage.vue | head -5
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：session-drill.test.ts 在 HEAD 在册；双视角投影的服务端对应实现（SessionEventsEndpointTest surfaceOnly 403 审计）已于本补档实跑 11/11 绿（见 H1-9 目录）——前端静态锁定与服务端机制两侧判据齐备。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
