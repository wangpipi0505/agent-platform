# T2-4 Plan 模式+结构化优先计划——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 4 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`ae71dc29` T2-4(engine): Plan模式——plan:policy分节/exit_plan_mode渐进披露/结构化优先校验器/双轨解耦
- 复查命令：`git log --oneline --all --grep="T2-4"`

## 验收口径（卡面摘录）

> PlanModeTest 5/5 绿（重放+log-only/文案/渐进披露+守卫/校验器 DAG+能力+形状/双轨解耦：task.mode 受理固定与会话交互模式独立——A01 并发互不串扰机制基础）；PlanValidator：DAG 环/自依赖/未知依赖 DFS+五 kind 枚举+targetRef 形状映射+能力引用授权集合校验+inputBindings 形状，诊断逐条 path/code/message。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=PlanModeTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.PlanModeTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.699 s
```

**5/5 全绿**——与卡面判据数一致，实测复验通过。校验器接线由 R-1 消费（Plans.appendRevision 调用点，见 T2-3/R-1 证据）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.PlanModeTest.txt`
- 报告 mtime：**2026-11-19 01:41:17**（本补档实跑产物）
