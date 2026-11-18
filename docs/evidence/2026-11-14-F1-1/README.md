# F1-1 tokens.css 改造——证据补档（R-12）

- 台账归属：§7.5 阶段 4（预估 1 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `9a906ae9` F1-1(web): tokens改造——间距六档/elevation/亮色段/mono与100dvh壳层
  - `fe719c57` merge: wave1-F1-1（vitest 504/517 较基线无新增失败）
  - 复查命令：`git log --oneline --all --grep="F1-1"`

## 验收口径（卡面摘录）

> tokens 扩展：--sp-1..6/--elev-1..3/warning 对/亮色段(html.light)/--font-mono/tabular-nums/.page-scroll；删 .shell min-width 改 100dvh grid；vitest 504/517 较 §9.4 基线无新增失败；:root 首块契约保持（assistant.js 注入不破）。

## 复现命令

```bash
cd web && npm ci && npx vitest run     # 全量，判据=较 §9.4 基线无新增失败
grep -c "sp-1\|elev-1\|font-mono" web/admin/src/styles/tokens.css 2>/dev/null || find web/admin/src -name "tokens.css" | head -1
```

## 关键输出摘要

**未复跑（前端 vitest；本工作树未装 node_modules——诚实登记）**。登记项：
- 交付时点判据 vitest 504/517（较 §9.4 基线 13 例失败无新增）于 2026-11-14 核验；
- tokens 消费链下游（F2-3/F4-4 亮色段补齐、clamp 清零）后续卡演进在案（`9d728d0d`/`960c77ef`）。

## Surefire/测试报告指针

无后端测试类；前端 vitest 报告未本地留痕（node_modules 不入 Git），复现命令附上。
