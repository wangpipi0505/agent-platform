# T2-3 计划 API（确认/执行/修订读）——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 3 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`7c6feb14` T2-3(engine): 计划API——修订CAS/确认去重/execute五项/修订读投影（Plans服务+四端点）
- 复查命令：`git log --oneline --all --grep="T2-3"`

## 验收口径（卡面摘录）

> PlansApiTest 4/4 绿（CAS 链/digest/去重双分支/五项/继承/投影三态）；appendRevision baseRevision CAS 409 PLAN_REVISION_CONFLICT/confirm 请求号去重同号同参重放原凭据/execute 五项受理校验/revisionDetail confirmation 三态+canConfirm/canExecute 按修订计算。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=PlansApiTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.PlansApiTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.037 s
```

**6/6 全绿**。用例数演进对账（诚实登记）：卡面 4/4 为交付时点（2026-11-14），R-1 计划校验器接线后 Plans.appendRevision 固化前调 PlanValidator 并补断言 → 现值 6/6（R-1 演进见 `docs/evidence/2026-11-18-R-125/`）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.PlansApiTest.txt`
- 报告 mtime：**2026-11-19 01:40:05**（本补档实跑产物）
