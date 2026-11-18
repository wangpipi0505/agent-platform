# T2-7 规划子代理、失败回执、一次合并及目录查询——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 4 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`6a60dc06` T2-7: SubagentPlanning（子智能体交付 2026-11-14；另见 merge waveP2-T2-7）
- 复查命令：`git log --oneline --all --grep="T2-7"`

## 验收口径（卡面摘录）

> SubagentPlanningTest 8/8 绿（A03 主断言根会话幂等/派发幂等/双成功 merge 幂等/单失败/全失败 SUBAGENT_NO_VALID_CANDIDATE/迟到重复回执不覆盖/目录分角色 404 不泄露/authority 恰一行）+回归 14/14+Routes 16/16；目录查询端点 GET /v1/sessions/{id}/subagents+Routes 显式接线。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=SubagentPlanningTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.SubagentPlanningTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.94 s
```

**9/9 全绿**。用例数演进对账（诚实登记）：卡面 8/8 为交付时点；R-2 规划子代理触发点接线（Tasks 受理事务内经真实受理路径调用 startPlannerSubagents）后补 1 例真实路径断言 → 现值 9/9（演进见 `docs/evidence/2026-11-18-R-125/`）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.SubagentPlanningTest.txt`
- 报告 mtime：**2026-11-19 01:40:27**（本补档实跑产物）
