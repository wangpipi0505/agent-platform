# C0-2 四份契约冻结（manifest/事件/状态/错误码）——证据补档（R-12）

- 台账归属：§7.1 阶段 0（预估 1 人日）
- 状态：**通过**（验收标准"评审通过（M0）"，2026-11-13 用户确认）
- 交付日期：2026-11-13；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`81f6f3f1` C0-2(contracts): 冻结1109四份契约——manifest/事件目录/任务状态/错误码（新增 391 行：contracts/README.md 契约清单 + 四文件）
- 复查命令：`git log --oneline --all --grep="C0-2"`

## 验收口径（卡面摘录）

> 交付物：contracts/ 四文件。验收标准：评审通过（M0；2026-11-13 用户确认）。

## 交付物定位（仓库内可核查，四文件均在 HEAD 在册）

- `contracts/manifest.schema.json`（165 行，manifest 冻结 schema）
- `contracts/session-events.md`（82 行，事件目录冻结）
- `contracts/task-states.md`（78 行，任务状态机冻结）
- `contracts/error-codes.md`（52 行，错误码冻结；后续 R-3 补登四码、R-9 增补，演进可追溯）
- 另有 `contracts/freeze.json` 与 `contracts/README.md` 冻结索引

## 复现命令

```bash
git show 81f6f3f1 --stat          # 四文件首版冻结内容
grep -c "" contracts/manifest.schema.json contracts/session-events.md contracts/task-states.md contracts/error-codes.md
```

## 关键输出摘要

**未复跑（契约冻结为文档交付，验收=评审+用户确认）**。登记项：四文件在 HEAD 均存在且被后续卡引用（R-3 补登四码、h1-6-h1-8-stubs.md 链规则对桩、oracle-replay-adapter.md 引用事件目录），冻结→演进的引用链完整。

## Surefire/测试报告指针

本卡无测试类（契约文档交付）。无 surefire 留痕，属诚实登记。
