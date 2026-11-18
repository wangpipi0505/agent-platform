# R-3 错误码登记（四码落契约+前端兜底）——证据补档（R-12）

- 台账归属：§7.6 R 系列收口补丁（预估 0.2 人日）
- 状态：**通过（2026-11-18，c2d26a8f）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`c2d26a8f` R-3(docs+web): 错误码登记——四码落契约§2含语义/触发/归属卡+集成方说明+前端兜底文案（reason-code-text四处接线+锁定测试2/2）
- 复查命令：`git log --oneline --all --grep="R-3"`

## 验收口径（卡面摘录）

> 四码各在契约文件中命中 ≥1 且语义表有归属卡；前端按码有兜底文案。登记 `TASK_DISPATCH_STALE`/`TASK_STEP_STALE`/`COMPACTION_PAIRING_BROKEN`/`INTEGRATION_PATH_FORBIDDEN` 至 contracts/error-codes.md §2（语义/触发条件/归属卡），同步前端错误文案与外部集成方说明。

## 复现命令

```bash
grep -n "TASK_DISPATCH_STALE\|TASK_STEP_STALE\|COMPACTION_PAIRING_BROKEN\|INTEGRATION_PATH_FORBIDDEN" contracts/error-codes.md | head -8
grep -n "TASK_DISPATCH_STALE\|COMPACTION_PAIRING_BROKEN\|INTEGRATION_PATH_FORBIDDEN" web/shared/reason-code-text.ts
cd web && npm ci && npx vitest run      # reason-code-text 锁定测试 2/2（未复跑，见下）
```

## 关键输出摘要

**契约命中未复跑即核（grep 实测）：四码在 contracts/error-codes.md §2 命中且各有归属卡**（T2-6/T2-9/T2-15）；前端兜底文案接线四处（TasksPage 恢复面板/SessionDetailPage 对账列与恢复事实/RunList 失败摘要/RunDetail 节点错误）在 reason-code-text.ts 可 grep 命中。
**未复跑项（诚实登记）**：前端锁定测试 2/2 为交付时点口径，本工作树无 node_modules，vitest 命令附上。

## Surefire/测试报告指针

无后端测试类（契约文档+前端文案卡）；前端 vitest 报告未本地留痕，复现命令附上。
