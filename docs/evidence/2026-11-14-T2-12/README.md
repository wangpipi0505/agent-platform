# T2-12 对账提交端点（授权链+归属链+403）——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 2 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`e9f59e4d` T2-12(engine): 调用级对账提交端点——授权链+归属链+证据复核409+decision落库
- 复查命令：`git log --oneline --all --grep="T2-12"`

## 验收口径（卡面摘录）

> TaskReconciliationTest 5/5 绿（owner RETRY/已受理 RETRY 409/ADOPT 409+TRACK 过/404/枚举）；授权链（提交人本人/已授权 OPERATOR 代运维/ADMIN，非授权 403 RECONCILIATION_FORBIDDEN）+归属校验链（callId→node_instance→run 与 task_step.run_id 一致 404 CALL_STEP_MISMATCH）+证据复核（RETRY 仅 REGISTERED/ADOPT 需已受理）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=TaskReconciliationTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.TaskReconciliationTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.123 s
```

**5/5 全绿**——五分支（owner RETRY/409×2/404/枚举）实测复验通过。待确认项（OPERATOR 工作区授权目录统一）已转 R-17 收口（台账 §7.6b，未开始，非本卡判据）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.TaskReconciliationTest.txt`
- 报告 mtime：**2026-11-19 01:40:50**（本补档实跑产物）
