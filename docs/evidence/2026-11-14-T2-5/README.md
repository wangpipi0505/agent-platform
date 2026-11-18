# T2-5 五类步骤执行映射+A08b 子项——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 3 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`0298a242` T2-5(engine): 五kind步骤受理为逻辑run——dispatch_key幂等/failure_policy映射/A08b下游处置
- 复查命令：`git log --oneline --all --grep="T2-5"`

## 验收口径（卡面摘录）

> StepExecutionMappingTest 5/5 绿（run 归属+dispatch 幂等/policy 映射/失败记账+必需下游阻断/可选下游跳过/CONDITION 为假）；admit（task_dispatch on conflict 幂等+task_step.run_id 归属先持久化再执行+STEP_READY 事件）/mapFailurePolicy（A08b 映射 timeoutMs/retry.max/abort）/onFailure（PLAN_STEP_FAILURE 记账+必需下游 BLOCKED/可选 SKIPPED）/evaluateCondition（受限表达式首版 const）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=StepExecutionMappingTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.StepExecutionMappingTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.127 s
```

**5/5 全绿**——A08b 映射/失败记账/下游处置/CONDITION 为假各判据实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.StepExecutionMappingTest.txt`
- 报告 mtime：**2026-11-19 01:40:14**（本补档实跑产物）
