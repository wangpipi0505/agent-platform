# PLAT-02 / T02.2 发布页同屏说明（为何被拦 / 谁能放行 / 放行多久）

## 1. 范围与依据

- 任务：优化台账 §3 子项 **T02.2**（PLAT-02 前端半，派发卡片 W-1，2026-10-28）。
- 依据：
  - 方案 §4.2「PLAT-02：发布门禁分层与可审计豁免」验收段（`平台侧优化方案与实施细则-20261027.md` 355–420 行）；
  - 计划 §6.3 前端规范第 4 条：门禁/豁免类界面必须同屏说明**为什么被拦、谁能放行、放行多久**；
  - 台账 T02.1 行（后端已落地 `88f12a8`：三模式 BLOCKING/ADVISORY/OFF、`app.publish_waiver`、TTL≤24h、ADMIN 申请、commit 复核）。
- 交付：在**现有发布页**（应用工作区「发布集成」tab 的 `AppPublishPanel.vue`）补同屏说明，不新开路由页、不改 router/AppShell/main.ts。
- 后端契约以 T02.1 代码为准（`AppService.precheck` / `PublishWaiverController` / `PublishWaiver.grant` / `PlatformErrors`），前端渲染不臆造字段。

## 2. 运行环境

- 仓库：`G:\207\项目\算法重组工具\代码\agent-platform`，分支 `feat/v1.2-implementation`。
- 开工基线：HEAD `c94f2f1`；收工时 HEAD `0b704d9`（E-0 泳道 engine-runtime 提交，未触及 web/admin；`git log -1 -- web/admin` 始终为 `052e521`，前端基线未变）。
- Node/vitest：vitest 4.1.12、vue-tsc 2.2.0、vite 6.4.4（web/ workspace，`web/package.json` scripts：test=`vitest run`、build=`vue-tsc --noEmit && vite build ×3 + copy`）。
- 后端服务（15173/18082–18085）本轮未起，页面级手验未执行（见第 5 段）。

## 3. 执行的命令原文（均在 `web/` 下）

```text
npx vitest run tests/publish-gate.test.ts     # 定向（先红后绿）
npm test                                      # 全量回归（脚本即 vitest run）
npx vue-tsc --noEmit
npm run build
```

说明：派发卡片写的是 `npm run test -- run <文件>`；`web/package.json` 的 test 脚本本身已是
`vitest run`，再拼 `run` 会把 "run" 当成第二个文件过滤器，故定向命令直接用
`npx vitest run <文件>`（与脚本语义一致）。

## 4. 结果原文

- 红（测试先行，实现前）：`vitest-red-first-run.txt` —— `Test Files 1 failed (1)` / `Tests no tests`（模块未存在，21 例全部无法运行）。
- 绿（定向）：`vitest-targeted-green.txt` —— **`Test Files 1 passed (1)` / `Tests 21 passed (21)`**。
- 全量回归：`vitest-full-regression.txt` —— **`Test Files 37 passed (37)` / `Tests 292 passed (292)`**（基线 271 + 本卡新增 21，新增失败 0）。
- `vue-tsc.txt` —— 无输出，**EXIT=0**。
- `build.txt` —— admin/webapp/assistant.js 三段 `✓ built in …`，**EXIT=0**。

### 实现要点（对应任务范围 1–5）

1. **为何被拦**：预检 409 `EVAL_GATE_BLOCKED` 被页面捕获（`AppWorkspacePage.check/commit`，不弹全局错误条）后传入面板，同屏展示错误码、后端 message（即被拦明细）、traceId；409 体不含 checks 明细（后端只落审计行），面板明示断言级"期望 vs 实际"在"2. 运行候选评测"报告区查看。200 预检的结构性失败检查列出并提示评测豁免不适用。
2. **谁能放行**：拦截卡片固定写明"仅 ADMIN 可申请豁免"；非 ADMIN（`isAdmin=false`）隐藏申请表单并展示不可操作提示；豁免申请/撤销仍由后端 `requireRole("ADMIN")` 兜底。
3. **放行多久**：展示 TTL 上限 24 小时、绑定对象（`draftRev` + `candidateSha256` 摘要）、豁免剩余有效期（`formatWaiverRemaining`，撤销显示"已撤销"）；撤销接 DELETE `/admin/apps/{code}/publish-waivers/{id}`（后端已存在，已接上）；提交前 confirm 确认。
4. **三模式可区分**：`gateModeInfo` 给 BLOCKING/ADVISORY/OFF 独立的标签、色彩（danger/warning/info）与说明；未知 gateMode 值与后端 `PublishGate.parse` 同规回落 BLOCKING（失败关闭）；ADVISORY 放行渲染后端 warnings、WAIVED 渲染"这是豁免，不是通过"+理由+到期时间。
5. **可恢复错误**：豁免申请失败（本地预校验 400 形状错误、403 `PUBLISH_WAIVER_DISABLED` 等）原样展示 message 且表单保留可重试；豁免绑定与当前候选不一致（rev/sha 漂移）显式告警，禁止静默假数据。

## 5. 未覆盖与已知失败

- **页面手验（15173）未做**：后端服务本轮未启动，无法按 §6.3 第 5 条做浏览器级 `pageerror` 手验与截图；本轮以组件级测试（setupState 行为 + 动作流）代替，留待 T02.2 验收时与 T02.1 联调补验。
- **豁免无查询端点**：后端未提供 GET 豁免列表（T02.1 亦列为未覆盖），历史会话授予的豁免只能经预检检查项带回的 `waiverId/expiresAt` 展示，其 reason 取自检查项 detail；绑定一致性告警仅对会话内豁免可算（precheck 来源的检查项不含 rev/sha）。
- **评测依赖故障 409**（`ERROR` 结果）与 BLOCKING 拦截共用同一 409 码，响应体无 gateMode，面板不臆造模式，仅展示错误码/message。
- **既有 any 未清理**：`AppPublishPanel.app`、`AppWorkspacePage.precheck` 等既有 props 的 `any` 属存量代码，本卡未重构；新增代码（publish-gate.ts、面板新增逻辑、测试新增断言）未引入新 `any`（测试挂载器沿用仓库既有 `as any`/`as unknown as` 转型惯例）。
- 已知失败：无。全量 292/292 通过，无既有失败被掩盖。

## 6. 回滚方式

- 本卡只改前端 4 个文件（无迁移、无契约变更、无路由/全局文件改动）：
  - `web/admin/src/publish-gate.ts`（新增，纯展示逻辑）
  - `web/admin/src/pages/workspace/AppPublishPanel.vue`（拦截卡片/豁免表单/模式徽标）
  - `web/admin/src/pages/AppWorkspacePage.vue`（409 捕获与 props 接线）
  - `web/tests/publish-gate.test.ts`（新增测试）
- 回滚：`git checkout -- web/admin/src/pages/AppWorkspacePage.vue web/admin/src/pages/workspace/AppPublishPanel.vue` 并删除两个新增文件即可完全恢复 `052e521` 行为；后端门禁/豁免接口（T02.1）不受影响，页面退化为"预检 409 仅全局错误条提示"的原状。
- 行为开关：豁免入口跟随后端 `agent.publish.waiver-enabled`（关闭时申请接口 403 `PUBLISH_WAIVER_DISABLED`，前端如实展示可重试错误）。
