# H1-3 Inbox 两阶段消费——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 2.5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `615147e6` H1-3(engine): harness.loop.Inbox 两阶段消费——enqueue幂等/claim代次租约/旧代次拒绝/同事务consume（子智能体交付）
  - `a92e2057` merge: wave2-H1-3（5/5 绿）
  - 复查命令：`git log --oneline --all --grep="H1-3"`

## 验收口径（卡面摘录）

> 两崩溃窗口幂等：InboxContractTest 5/5 绿（dedupe 幂等/租约代次/旧代次 409 INBOX_CLAIM_STALE/提交后重复消费静默/同事务回滚回滚后重投幂等）；consume 增 claimGeneration 配对参数（有意偏差，接管识别需要）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=InboxContractTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.harness.loop.InboxContractTest
（Flyway: jdbc:postgresql://127.0.0.1:25432/agent_platform_engine_test，PostgreSQL 16.15，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.882 s
```

**5/5 全绿**——五用例（dedupe 幂等/租约代次/旧代次 409/提交后重复消费静默/同事务回滚重投幂等）与卡面判据一一对应，本环境 PG 25432 实测复验通过。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.harness.loop.InboxContractTest.txt`
- 报告 mtime：**2026-11-19 01:38:16**（本补档实跑产物；target/ 不入 Git，本 README 即登记载体）
