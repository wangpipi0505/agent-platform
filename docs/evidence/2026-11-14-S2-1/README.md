# S2-1 会话列表端点+网关登记——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 1.5 人日；L1 链首卡）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `ecf342a8` S2-1(engine): 本人会话列表端点——mine=true过滤/游标分页/TASK_INTERNAL排除+网关登记
  - `47f9dc37` merge: wave3-S2-1（6/6 绿+无回归）
  - 复查命令：`git log --oneline --all --grep="S2-1"`

## 验收口径（卡面摘录）

> SessionListEndpointTest 6/6+ChatSessionsTest 无回归；GET /v1/apps/{code}/sessions：mine 必填/principal+app 过滤/origin≠TASK_INTERNAL/title≤48 稳定规则/lastActiveAt 倒序+before 排他游标+nextBefore 末页省略/state=最近轮次终态否则 ACTIVE；网关目录行登记（delegated，verify-routes MISSING=0）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=SessionListEndpointTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.application.SessionListEndpointTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.510 s
```

**6/6 全绿**——mine 过滤/TASK_INTERNAL 排除/游标分页判据实测复验通过。ChatSessionsTest 本补档未复跑（复现命令同上替换类名，诚实登记）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.SessionListEndpointTest.txt`
- 报告 mtime：**2026-11-19 01:42**（本补档实跑产物，batch4）
