# H1-4 AgentEngine 循环改造——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 5 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `9af6b4a8` H1-4(engine): harness.loop.AgentEngine——turn信封/inbox认领/取消语义/UNKNOWN补写+影子验证
  - `4da3293c` merge: wave2 H1-4 AgentEngine影子验证（七类定向回归 23/23 绿，默认路径未切）
  - 复查命令：`git log --oneline --all --grep="H1-4"`

## 验收口径（卡面摘录）

> 行为清单映射等价：AgentEngineShadowTest 3/3 绿（B4 信封完整有序、B1 采纳集=实际执行、B2 影子口径=运行级受理恰一次——会话级事件对属 H1-6、失败轮次不消费输入重投幂等）；默认路径未切（R2 影子策略，切换待评审）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=AgentEngineShadowTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.AgentEngineShadowTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.227 s
```

**3/3 全绿**——B4/B1/B2 三影子判据实测复验通过（oracle 三场景之一，H1-10 门引用本类）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.AgentEngineShadowTest.txt`
- 报告 mtime：**2026-11-19 01:38:25**（本补档实跑产物）
