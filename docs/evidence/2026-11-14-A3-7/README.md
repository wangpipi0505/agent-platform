# A3-7 todo/jobs——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 后补签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `72619e6e` A3-7(engine): todo整表替换事件投影+job事件对owner联动——重放正确
  - `5484d169` merge: waveP2-1-A3-7（9/9 绿）
  - 复查命令：`git log --oneline --all --grep="A3-7"`

## 验收口径（卡面摘录）

> TodoJobsTest 4 例+SessionStoreContractTest 5 例=9/9 绿（三态重放/非法 400 不落事件/重复 settle 单条收口/owner 联动/seq 连续）；harness.session TodoStore（todo/write 整表替换+project fold 最后一态获胜）+JobTracker（register/settle 幂等/cancelByOwner CANCELLED_OWNER_GONE 联动）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest='TodoJobsTest,SessionStoreContractTest' -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·两类全跑）

```
[INFO] Running cn.agentplatform.engine.harness.session.TodoJobsTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.251 s
[INFO] Running cn.agentplatform.engine.harness.session.SessionStoreContractTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.658 s
```

**合计 9/9 全绿**——与卡面判据数一致，实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.harness.session.TodoJobsTest.txt`（mtime 2026-11-19 01:42，batch4）
- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.harness.session.SessionStoreContractTest.txt`（mtime 2026-11-19 01:38:18，batch1）
