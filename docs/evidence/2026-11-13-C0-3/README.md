# C0-3 V901 种子清单设计——证据补档（R-12）

- 台账归属：§7.1 阶段 0（预估 0.5 人日）
- 状态：**通过**（验收标准"字段与库稿一致"，2026-11-13 用户确认）
- 交付日期：2026-11-13；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `36ff0e3a` C0-3(contracts): V901演示种子设计稿——manifest示例/GUIDE+TOOL技能/规则/记忆/插件与两场景映射表
  - `852ea1ec` C0-3(contracts): 种子稿补正——示例过冻结schema校验+ACTIVE发布与激活指针步骤
  - 复查命令：`git log --oneline --all --grep="C0-3"`

## 验收口径（卡面摘录）

> 交付物：种子设计稿。验收标准：字段与库稿一致（2026-11-13 用户确认）。

## 交付物定位（仓库内可核查）

- 设计稿：`contracts/demo-seed-v901-design.md`（99 行：manifest 示例/GUIDE+TOOL 技能/规则/记忆/插件与两场景映射表；852ea1ec 补正示例过冻结 schema 校验+ACTIVE 发布与激活指针步骤）
- 设计→实现承接：M5-1 三域 `V901__demo_seed.sql` 与三个 `*DemoSeedV901PostgresTest` 类（证据见 `docs/evidence/2026-11-18-M5-1/`，本 R-12 批次内已实跑复验）

## 复现命令

```bash
git show 852ea1ec --stat
grep -n "ACTIVE\|激活指针\|两场景" contracts/demo-seed-v901-design.md | head
```

## 关键输出摘要

**未复跑（设计稿卡，验收=字段与库稿一致性核对+用户确认）**。一致性由 M5-1 落库测试实际复核：EngineDemoSeedV901PostgresTest 等三类的行数+内容 sha256 断言即"设计稿↔库稿一致"的机制级对账（本批次 2026-11-19 实跑全绿，见 M5-1 证据）。

## Surefire/测试报告指针

本卡自身无测试类（设计稿交付）；承接测试类为 M5-1 三个 DemoSeedV901PostgresTest，surefire 报告指针见 `docs/evidence/2026-11-18-M5-1/README.md`。
