# C0-1 裁剪清单执行（菜单挂起/范围文档）——证据补档（R-12）

- 台账归属：§7.1 阶段 0（预估 0.5 人日）
- 状态：**通过**（验收标准"评审通过"，2026-11-13 用户确认）
- 交付日期：2026-11-13；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `42a1098e` C0-1(web,docs): 1109裁剪——Semantic*/onboarding入口挂起移出菜单
  - `08634e5e` C0-1(web): onboarding-templates测试同步挂起线口径——断言menu:false+suspended
  - `381f0aaa` C0-1(contracts,docs): 本体契约挂起标注——contracts/README semantic-* 五文件标挂起、裁剪清单补记 v1.2 冻结物处置
  - 复查命令：`git log --oneline --all --grep="C0-1"`

## 验收口径（卡面摘录）

> 交付物：裁剪清单+page-access 标记。验收标准：评审通过（2026-11-13 用户确认）。

## 交付物定位（仓库内可核查）

- 范围文档：`docs/baseline-trim-1109.md`（1109 裁剪范围，含 v1.2 冻结物处置补记）
- 菜单挂起：`web/admin/src/router.ts` / `page-access.ts`（Semantic*/onboarding 入口 `menu:false + suspended`）；`08634e5e` 同步了 onboarding-templates 测试断言（menu:false + suspended）
- 契约挂起标注：`contracts/README.md`（semantic-* 五文件标"挂起"）

## 复现命令

```bash
# 挂起标注核查
grep -n "suspended\|挂起" web/admin/src/router.ts web/admin/src/page-access.ts contracts/README.md docs/baseline-trim-1109.md | head -20
# 挂起线测试断言（08634e5e）
git show 08634e5e --stat
```

## 关键输出摘要

**未复跑（文档/文案类卡，无后端测试用例；验收=评审通过+用户确认）**。登记项：
- 挂起标注在仓库 HEAD 仍可 grep 命中（router.ts/page-access.ts/contracts/README.md 均含 suspended 标注）；
- 08634e5e 的测试同步随前端 vitest 基线维护（前端 vitest 本工作树未装 node_modules，未复跑，命令附后：`cd web && npm ci && npx vitest run`）。

## Surefire/测试报告指针

本卡无独立后端测试类（裁剪配置+文档交付）。前端 vitest 报告未本地留痕（target/node_modules 不入 Git），复现命令如上。
