# H1-5 D2 请求材料先落库——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 2 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `f97c6dc0` H1-5(engine): D2请求材料先落库——drive出流前写request/header(含采纳集)+request/context
  - `f940bd76` merge: H1-5 D2请求材料先落库（定向回归 15/15 绿）
  - 复查命令：`git log --oneline --all --grep="H1-5"`

## 验收口径（卡面摘录）

> 重建一致：AgentD2RequestPersistTest 2/2 绿（桩内断言出流前已落库/采纳集对齐冻结词表/header.config 含消息序可重建/独立运行零会话事件）；定向回归六类 20/20；context 的 provider/contextWindow 待 H1-9 补全（X2-5 登记）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=AgentD2RequestPersistTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.AgentD2RequestPersistTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.294 s
```

**2/2 全绿**——"出流前已落库/采纳集对齐冻结词表/header.config 消息序可重建"两判据实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.AgentD2RequestPersistTest.txt`
- 报告 mtime：**2026-11-19 01:38:20**（本补档实跑产物）
