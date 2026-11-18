# T2-11 任务事件+游标+网关登记——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 2.5 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `7c8d543b` T2-11(engine): 任务事件SSE+TASK_RESUMED角色裁剪+网关events/stream登记
  - `26a1bd25` merge: waveP2-T2-11（11/11 绿+Routes 14/14）
  - 复查命令：`git log --oneline --all --grep="T2-11"`

## 验收口径（卡面摘录）

> TaskEventsSseTest 5/5+TasksApiTest 6/6=11/11 绿；定向回归 ChatSessions/SessionEvents/Plans/TaskReconciliation 24/24；events 角色裁剪（USER 剥离 checkpoint/reusedSteps/blockedReasons——detail/events/SSE 三处共用同一口径）+SSE 端点 Last-Event-ID 排他续读+15s :ping+积压 2000 慢客户端断开+断开不取消。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=TaskEventsSseTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·主类）

```
[INFO] Running cn.agentplatform.engine.application.TaskEventsSseTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.180 s
```

**主类 5/5 全绿**；TasksApiTest 7/7 同批实跑（见 T2-2 目录）——两主类合计 12/12。R-5 后非 ASCII 帧问题已由 Utf8StringMessageConvertersConfiguration 修复并经 TaskEventsSseRealPortTest 真实端口 2/2 验证（见 `docs/evidence/2026-11-18-R-125/`）；该 RealPort 类本补档未复跑（复现命令同上替换类名）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.TaskEventsSseTest.txt`
- 报告 mtime：**2026-11-19 01:40:44**（本补档实跑产物）
