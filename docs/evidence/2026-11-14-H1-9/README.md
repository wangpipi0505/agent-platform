# H1-9 会话只读快照、事件、控制、SSE 及角色字段投影——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 3 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `f10dfbde` H1-9(engine): 会话只读快照/事件增量/控制/SSE+角色投影与网关登记（子智能体交付）
  - `5de63813` merge: wave4-H1-9（21/21 绿+RoutesTest 10/10）
  - 复查命令：`git log --oneline --all --grep="H1-9"`

## 验收口径（卡面摘录）

> SessionEventsEndpointTest 11 例+ChatSessionsTest+SessionListEndpointTest =21/21 绿；网关及 authority 登记（3 目录行+Routes 正则扩展，verify-routes MISSING=0）；Last-Event-ID 续读/15s 心跳/有界队列慢客户端断开/断开不取消；surfaceOnly 角色门 403 记审计。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=SessionEventsEndpointTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·主类）

```
[INFO] Running cn.agentplatform.engine.infra.SessionEventsEndpointTest
（11 个 TestDispatcherServlet 初始化=11 用例；日志含 AUDIT SESSION_DIAGNOSTIC_VIEW_FORBIDDEN ... reason=surfaceOnly=false requires OPERATOR/ADMIN —— 角色门 403 审计在案）
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.453 s
```

**主类 11/11 全绿**（角色门 403 审计在实跑日志可见）。同批实跑 SessionListEndpointTest 6/6（S2-1 交付类、本卡回归邻接）；ChatSessionsTest 未在本补档复跑（复现命令同上替换类名）——合计本补档实测 17/21，余 4 例诚实登记为未复跑。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.SessionEventsEndpointTest.txt`
- 报告 mtime：**2026-11-19 01:38:42**（本补档实跑产物）
