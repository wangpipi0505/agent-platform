# H1-8 检查点恢复四态、RESUME 资格与恢复事实输出——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 3 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `ac914640` H1-8(engine): 恢复事实只读出口——RecoveryFact/角色投影/检查点水位/复用步骤/阻塞原因
  - （契约先行 `d5c68d90`，与 H1-6 共桩）
  - 复查命令：`git log --oneline --all --grep="H1-8"`

## 验收口径（卡面摘录）

> RecoveryFactExitTest 2/2 绿（外部采纳→稳定 AUTO 事实重启可重复取得+USER 投影；手动恢复→MANUAL+阻塞原因）；外部受理恒 1 由 M1-① 基线保证；四态核验/RESUME 资格已在 H1-0/H1-4 影子与既有 ExecutionRecovery 路径交付。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=RecoveryFactExitTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.RecoveryFactExitTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.530 s
```

**2/2 全绿**——AUTO 事实稳定可重复取得+USER 三字段投影、MANUAL+阻塞原因两判据实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.RecoveryFactExitTest.txt`
- 报告 mtime：**2026-11-19 01:38:37**（本补档实跑产物）
