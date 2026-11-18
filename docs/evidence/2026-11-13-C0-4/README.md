# C0-4 dsh 对照 oracle 素材——证据补档（R-12）

- 台账归属：§7.1 阶段 0（预估 1 人日）
- 状态：**通过**（验收标准"骨架可运行"，2026-11-13 用户确认）
- 交付日期：2026-11-13；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`191cad7d` C0-4(contracts,scripts): dsh对照oracle素材——回放适配器设计+行为清单+可运行骨架（4 文件 429 行）
- 复查命令：`git log --oneline --all --grep="C0-4"`

## 验收口径（卡面摘录）

> 交付物：回放适配器设计+行为清单。验收标准：骨架可运行（2026-11-13 用户确认）。

## 交付物定位（仓库内可核查）

- 设计+行为清单：`contracts/oracle-replay-adapter.md`（99 行，§4.4 定义 P0 fixture `example-recover-tool-call.json`）
- 可运行骨架：`scripts/oracle/run-oracle.mjs`（254 行）+ `scripts/oracle/event-mapping.json`（50 行）+ `scripts/oracle/fixtures/example-recover-tool-call.json`（26 行）

## 复现命令（骨架可运行=CLI 可执行）

```bash
node scripts/oracle/run-oracle.mjs --help
# 用法: run-oracle.mjs --scenario <fixture.json> [--ref-driver <cmd>] [--subject-driver <cmd>] [--out <report.json>]
node scripts/oracle/run-oracle.mjs --scenario scripts/oracle/fixtures/example-recover-tool-call.json
```

## 关键输出摘要

2026-11-19 R-12 补档时实跑 `--help`：CLI 正常打印用法行（骨架可运行成立）；fixture 场景 P0=外部受理后回执丢失→重启恢复，期望 externalAcceptCount=1（该判据由 H1-0 的 `AgentReceiptLostRecoveryGateTest` 在引擎侧机制级复验，本批次实跑 1/1 绿，见 `docs/evidence/2026-11-13-H1-0/` 与 H1-0 补档）。`--scenario` 全量回放需对接两侧 driver 命令（属 R-23/G5-0 环境项，诚实登记：本补档仅验证骨架加载与用法，未对接真实 driver）。

## Surefire/测试报告指针

本卡无 surefire 测试类（node 骨架）。判据级复验由 H1-0 测试承接（surefire 报告见 H1-0 目录说明）。
