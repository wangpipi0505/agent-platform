# T2-2 任务 API/入口步骤/调用列表/任务级工具范围与恢复只读投影——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 4 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认；三半卡 12ba024d+3dd39ab5+9ca2a9f1 全卡完成）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `12ba024d` T2-2(engine): 任务域API——受理/详情/事件游标/取消恢复/TASK_INTERNAL根会话（半卡①）
  - `3dd39ab5` 半卡②（详情投影/availableActions/恢复 USER 三字段）
  - `9ca2a9f1` T2-2(engine): 步骤calls列表+entry运行归属（半卡③，T2-2全卡完成）
  - 复查命令：`git log --oneline --all --grep="T2-2"`

## 验收口径（卡面摘录）

> TasksApiTest 6/6 绿+定向回归 22/22（M1-① 门/ChatSessions/SessionEvents）；mode 必填/requestId 幂等（同参重放/异参 REQUEST_REPLAY）/availableActions 服务端计算/recovery 投影 USER 三字段/TASK_INTERNAL 根会话受理事务创建/事件游标 nextAfterSeq 不+1/Routes 显式接线（RoutesTest 12/12）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=TasksApiTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.TasksApiTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.250 s
```

**7/7 全绿**。用例数演进对账（诚实登记）：卡面签认时点 6/6（2026-11-14 交付），R-9⑤ 任务端点收业务号后 `resolveTaskId` 双形态补 1 例 → 现值 7/7（演进在 R-9 证据 `docs/evidence/2026-11-18-R-9/` 在案）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.TasksApiTest.txt`
- 报告 mtime：**2026-11-19 01:40:57**（本补档实跑产物）
