# A3-5 插件发现、差异、授权和停用——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 3.5 人日）
- 状态：**通过（2026-11-18 返工复验，R-6 合入 f469a47c）**
- 交付日期：2026-11-18；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `5964144f` A3-5(skill): 插件发现执行器——原子认领/租约接管/批次串行发布/失败保留目录/差异授权闭环
  - `6397fac6` merge: waveP3-A3-5（ExecutorTest 11/11+邻接回归 24/24）
  - `f469a47c` R-6 合入：controller→trigger+@Scheduled pulse 生产接线（返工复验通过的前提）
  - 复查命令：`git log --oneline --all --grep="A3-5"`

## 验收口径（卡面摘录）

> PluginDiscoveryExecutorTest 11/11 绿（并发认领恰一胜者/租约接管 gen1 STALE/失败保留目录/成功差异+批次递增/旧批次拒绝/来源停用拦截/差异确认门/授权闭环/pulse 接管/同摘要重放）；返工复验另含 PluginDiscoveryWiringApiTest 4/4（真实 Spring 上下文+真实 @Scheduled 消费）+未接线基底对照 3/4 失败=缺陷复现。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl skill-registry test -Dtest=PluginDiscoveryExecutorTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·主类）

```
[INFO] Running cn.agentplatform.skill.application.PluginDiscoveryExecutorTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

**主类 11/11 全绿**（与卡面判据数一致）。诚实登记：PluginDiscoveryWiringApiTest（R-6 返工复验的接线证据 4/4）本补档未复跑，复现命令同上替换类名；接线证据归档在 `docs/evidence/2026-11-18-R-6/`（已有目录）。同批实跑 PluginGovernancePostgresTest 9/9（邻接回归全绿）。

## Surefire 报告指针

- `skill-registry/target/surefire-reports/cn.agentplatform.skill.application.PluginDiscoveryExecutorTest.txt`
- 报告 mtime：**2026-11-19 01:41:28**（本补档实跑产物）
