# F0-1 名词口径替换（page-access/页面标题"智能体"）——证据补档（R-12）

- 台账归属：§7.1 阶段 0（预估 0.5 人日）
- 状态：**通过**（验收标准"名词表核对"，2026-11-13 用户确认）
- 交付日期：2026-11-13；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `4665e51d` F0-1(web): 名词口径替换——构建现场页头更名"智能体工作区"（AppWorkspacePage/router/page-access）
  - `3e2e6f81` F0-1(web): 名词表核对补齐——用户可见"应用/应用版本"文案统一为智能体口径（AppsPage/CapabilityPackagesPage/ConfigReleasesPanel/ExperienceEditor/EvaluationReport 等）
  - 复查命令：`git log --oneline --all --grep="F0-1"`

## 验收口径（卡面摘录）

> 交付物：UI 文案替换。验收标准：名词表核对（2026-11-13 用户确认）。

## 交付物定位（仓库内可核查）

- 页头更名：`web/admin/src/pages/AppWorkspacePage.vue`（"智能体工作区"）+ `router.ts`/`page-access.ts` 标题同步
- 用户可见文案：`web/admin/src/pages/AppsPage.vue` 等五文件"应用/应用版本"→"智能体/智能体版本"

## 复现命令

```bash
grep -n "智能体工作区" web/admin/src/pages/AppWorkspacePage.vue web/admin/src/router.ts
# vitest 全量（本工作树未装 node_modules，未复跑——诚实登记；在主线 web/ 可复现）：
# cd web && npm ci && npx vitest run
```

## 关键输出摘要

**未复跑（UI 文案卡；前端 vitest 本工作树无 node_modules）**。登记项：文案替换已在 HEAD 在册（grep 可命中"智能体工作区"）；当轮 vitest 判据为"较 §9.4 基线无新增失败"，由交付时点（2026-11-13/14）核验记录承接，本补档未重跑前端全量。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕（node_modules/target 不入 Git），复现命令附上。
