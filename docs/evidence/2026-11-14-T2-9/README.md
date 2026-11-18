# T2-9 压缩+模型切换检查——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 4 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：`0de9bc97` T2-9(engine): 压缩三事件锁协议——配对边界/孤儿锁检测/模型切换窗口检查
- 复查命令：`git log --oneline --all --grep="T2-9"`

## 验收口径（卡面摘录）

> CompactionStoreTest 4/4 绿（三事件锁+replace+投影 A14 摘要入+重放一致/配对边界拒绝 409 COMPACTION_PAIRING_BROKEN/孤儿锁检测收口/切换窗口三分支）；触发点接线随 PreStepChain 由 AgentEngine 消费（接缝已留）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=CompactionStoreTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.CompactionStoreTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.049 s
```

**4/4 全绿**——三事件锁/配对边界/孤儿锁/切换窗口判据实测复验通过。COMPACTION_PAIRING_BROKEN 错误码已由 R-3 登记（contracts/error-codes.md §2）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.CompactionStoreTest.txt`
- 报告 mtime：**2026-11-19 01:41:11**（本补档实跑产物）
