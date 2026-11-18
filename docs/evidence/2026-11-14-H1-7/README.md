# H1-7 步骤工具快照绑定——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `b1e97c2d` H1-7(engine): 步骤工具快照绑定——重启恢复只收窄/撤权拒绝/放宽不生效（子智能体交付）
  - `0032ed57` merge: H1-7 步骤工具快照绑定（3/3 绿+扩展回归 49/49）
  - 复查命令：`git log --oneline --all --grep="H1-7"`

## 验收口径（卡面摘录）

> 快照共用+只收窄：StepSnapshotBindingTest 3/3 绿（恢复收窄/放宽不生效/撤权拒绝）+扩展回归 10 类 49/49 绿；待确认：同名新版恢复按 toolId 求交（当步不热切换）/header 读回源选择 request/header 事件。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=StepSnapshotBindingTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.StepSnapshotBindingTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.509 s
```

**3/3 全绿**——恢复只收窄/放宽不生效/撤权 TOOL_NOT_DECLARED 拒绝三判据实测复验通过（snapshotBinding 字段落库在案）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.StepSnapshotBindingTest.txt`
- 报告 mtime：**2026-11-19 01:38:47**（本补档实跑产物）
