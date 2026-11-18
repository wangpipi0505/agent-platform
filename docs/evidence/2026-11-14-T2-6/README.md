# T2-6 依赖调度+三要素——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 3 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `b899538a` T2-6(engine): 依赖调度+分派三要素——dispatch_key幂等/CAS代次/接管旧回执拒绝/依赖放行
  - `7442e8bf` merge: waveP2-T2-6（TaskDispatchTest 7 例+任务域回归 32/32 绿）
  - 复查命令：`git log --oneline --all --grep="T2-6"`

## 验收口径（卡面摘录）

> TaskDispatchTest 7 例（接管场景核心：gen1 过期→gen2 接管→旧 gen1 complete 409 STALE 留痕→gen2 DONE 恰一次）+任务域回归 32/32 绿；dispatch_key=sha256(tenant/task/planRevision/step) 单源公式；claim 租约成对 generation+1；complete owner+generation+租约三要素齐验；TASK_DISPATCH_STALE/TASK_STEP_STALE 错误码（R-3 已登记 contracts/error-codes.md §2）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=TaskDispatchTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.TaskDispatchTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.85 s
```

**7/7 全绿**——接管三要素场景（gen1 过期→gen2 接管→旧 gen1 409 STALE→gen2 DONE 恰一次）实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.TaskDispatchTest.txt`
- 报告 mtime：**2026-11-19 01:40:41**（本补档实跑产物）
