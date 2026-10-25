# 优化台账执行报告：UIF-A1 / VIS-1 / P2-18 / 详设落稿五项

> 执行日期：2026-10-25。对应《优化台账-智能体侧-agent-platform》UIF-A1、VIS-1、详设问题 P1-6/P1-7/P1-16/P1-10/P2-12/P2-13/P2-18。

## UIF-A1：SSE 客户端 + 认证 + 深链

**核查结论（核心已实现）**：`web/debug-panel/events.ts` 的 `streamEvents` 已是 fetch 流式客户端——Bearer 认证头、`Last-Event-ID` 游标续传、401 触发 `onUnauthorized` 失效会话、半帧缓冲（`parseFrames` 按空行切帧并保留 rest）、abort 语义与观察 scope 融合、授权失效 `stream_reset` 显式中断。**详设问题 P1-10（"原生 EventSource 不带认证头会 401"）不成立于当前实现**——EventSource 方案从未落地，该问题源自旧设计稿口径，随本项关闭。

**本轮新增（深链定位）**：`web/admin/src/RunDetail.vue` 支持 `?nodeId=<nodeDefId>`——进入页面即：节点摘要表过滤到该节点（带"来自链接定位"提示与一键清除）、自动选中该节点打开检查器、事件回放滑杆跳到该节点首个事件并切换到回放页签。日志/告警/审批通知可据此构造一键直达 URL。

验证：`vue-tsc --noEmit` 全 workspace 通过（exit 0）。

## VIS-1 / P2-18：三层令牌与对比度

**核查结论（结构已存在且实测达标）**：`web/shared/tokens.css` 已实现"原始色 → 语义令牌（`--st-*` 填充 / `--st-*-text` 文字双令牌）→ Element Plus 映射（`--el-color-primary` 等）"结构；状态标签使用 10% 底 + 深文字配方。对五组状态色实测对比度（10% mix 底）：wait 6.98 / run 6.16 / ok 4.50 / fail 5.54 / suspend 4.50——**全部 ≥4.5:1，P2-18 的"2.34~2.86 不达标"不成立于当前实现**（评估口径来自旧设计稿）。

**本轮加固**：ok/suspend 恰在 4.50 临界值，文字色加深留余量——`--st-ok-text #15803d→#166534`（6.40）、`--st-suspend-text #b45309→#92400e`（6.36）、`--st-fail-text #b91c1c→#991b1b`（7.11），并在 tokens.css 注释中记录实测依据。品牌色映射部分仍按台账挂 D5 决策，不阻塞本项。

## 详设问题落稿（纯文档裁决，5 项）

在 `文档/设计/详细设计/智能体构建/` 五个分册追加"裁决与修订注记（2026-10-25）"：

| 问题 | 落稿位置 | 裁决摘要 |
|---|---|---|
| P1-6 工具循环控制权两册相反 | 10 册 + 31 册（联动注记） | AgentDriver 持循环、Spring AI 单次调用（`internalToolExecutionEnabled(false)`）；10 册相反旧表述作废，31 册为权威侧 |
| P1-7 ANY 汇合两册冲突 | 30 册 | 以 `GraphRules.join()` 现实现为准：任一父 SUCCEEDED 即 READY，胜者锁定、独占前驱 ABANDONED、剩余分支按图规则收尾 |
| P1-16 吊销时效矛盾 | 前置契约册 | 每次使用实时校验吊销 + 短票据 TTL≤300s 为残留上界；"旧票据 5 分钟"表述作废；SSE 断开由前端显式执行 |
| P2-12 全字段 DDL 未闭合 | 00 总册（索引）+ 31 册（归属） | 字段级 DDL 唯一归属 31 册（配合 V200~V219 迁移为实现权威），00 册仅导航 |
| P2-13 Boot/Spring AI 矩阵 | 00 总册 | 基线确认 Java21+Boot3.5.0+AI1.0.0（官方支持组合），"3.3.x"表述作废 |
