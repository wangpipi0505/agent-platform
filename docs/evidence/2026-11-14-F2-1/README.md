# F2-1 工作区壳层（仅壳层）——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 3 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `8703e2c4` F2-1(web): 工作区壳层——智能体头+锚点导航+面板布局+运行记录独立路由（子智能体交付）
  - `54a310c9` merge: waveL4A-F2-1（任务 b 步数=3≤4，无新增失败）
  - 复查命令：`git log --oneline --all --grep="F2-1"`

## 验收口径（卡面摘录）

> 智能体头（名称/编码/状态/版本摘要/预检+提交发布）+左锚点导航（key=tabMatch）+/apps/:code/records/:runId 独立路由（page-access 注册 app-run-record）；**任务 b 步数=3≤4**（workspace-shell.test.ts 5 断言锁定链接链）；vitest 523 用例 13 失败=§9.4 基线一致零新增；vue-tsc 改动文件零错误。

## 复现命令

```bash
cd web && npm ci && npx vitest run          # workspace-shell.test.ts 在内
npx vue-tsc --noEmit                        # 或 npm run typecheck（若 package.json 定义）
grep -n "records/:runId\|app-run-record" web/admin/src/router.ts web/admin/src/page-access.ts
```

## 关键输出摘要

**未复跑（前端 vitest/vue-tsc；本工作树未装 node_modules——诚实登记）**。登记项：workspace-shell.test.ts 在 HEAD 在册；records 独立路由与 app-run-record 注册 grep 可命中；卡面待确认（/records/ vs 设计稿 /runs/，一行 redirect 对齐）转 R-20 裁决，非本卡判据。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕，复现命令附上。
